package org.rsmod.content.skills.sailing

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerQueue
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.PlayerWorldEntity
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sailing tier 1 spike (see TIER1_PLAN.md): a stationary, boardable raft as a rev 243 world entity,
 * single player (one avatar, owned by the player; the network side is in RspCycle).
 *
 * - `::raft [zoneX zoneZ level [widthZones lengthZones]]` - probe: builds the raft template region
 *   (default guess: map square 60,100 level 1, from the cache hotspot coord `1_60_100_3_58`), puts
 *   the raft's pivot 2 tiles east of the player and the player on its deck. Everything the first
 *   login round needs is logged with a `[raft]` prefix (and the network side logs `[boat]`).
 * - `::raftoff` / the Port Sarim gangplank ("Cross") toggle board/disembark.
 * - Relog: logging out on deck saves the player on the dock tile with the boarded varbits kept,
 *   and the login re-boards them on the next tick.
 *
 * Boarding sets the client's boarding varbits and opens the Sailing side panel (937) over the
 * combat tab; disembarking restores the combat tab.
 */
class Raft
@Inject
constructor(
    private val eventBus: EventBus,
    private val regionRepo: RegionRepository,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private val logger = InlineLogger()

    /** Per player: the dock tile to step back onto and the template the raft was built from. */
    private class Boarding(val dock: CoordGrid, val template: Template)

    private data class Template(val zoneX: Int, val zoneZ: Int, val level: Int, val width: Int, val length: Int)

    private val boarded = WeakHashMap<Player, Boarding>()

    override fun ScriptContext.startup() {
        onCommand("raft") {
            modLevel = modlevels.admin
            desc = "Sailing tier 1 probe: board a raft world entity (::raft [zoneX zoneZ level [w l]])"
            cheat {
                val nums = args.mapNotNull { it.toIntOrNull() }
                val template =
                    if (nums.size >= 3) {
                        Template(nums[0], nums[1], nums[2], nums.getOrElse(3) { 1 }, nums.getOrElse(4) { 1 })
                    } else {
                        DEFAULT_TEMPLATE
                    }
                protectedAccess.launch(player) { board(template, dock = player.coords) }
            }
        }
        onCommand("raftoff") {
            modLevel = modlevels.admin
            desc = "Sailing tier 1 probe: leave the raft"
            cheat { protectedAccess.launch(player) { disembark() } }
        }
        for (plank in listOf(SailingLocs.gangplank_on, SailingLocs.gangplank_off)) {
            onOpLoc1(plank) {
                if (player.worldEntity != null) {
                    disembark()
                } else if (vars[SailingVarBits.boat_1_owned] == 0) {
                    mes("You don't have a boat moored here. Shipwright Sam can help with that.")
                } else {
                    board(DEFAULT_TEMPLATE, dock = player.coords)
                }
            }
        }
        onPlayerLogout { saveOnDock(player) }
        onPlayerLogin {
            if (player.vars[SailingVarBits.on_player_boat] != 0) {
                logger.info { "[raft] ${player.username} logged in boarded; re-boarding next tick" }
                player.queue(SailingQueues.reboard, 1)
            }
        }
        onPlayerQueue(SailingQueues.reboard) { board(DEFAULT_TEMPLATE, dock = player.coords) }
    }

    private fun ProtectedAccess.board(template: Template, dock: CoordGrid) {
        if (player.worldEntity != null) {
            mes("You're already aboard.")
            return
        }
        val regionTemplate =
            RegionTemplate.create {
                copyAllLevels(template.zoneX, template.zoneZ) {
                    zoneWidth = template.width
                    zoneLength = template.length
                }
            }
        val region = regionRepo.add(regionTemplate)
        if (region == null) {
            mes("The raft can't be launched right now (no free region).")
            logger.warn { "[raft] region capacity reached for ${player.username}" }
            return
        }
        // The deck tile: the template's hotspot tile (local 3,58 of square 60,100) mapped into the
        // region, else the centre of the copied block.
        val hotspot = CoordGrid(template.level, 60, 100, 3, 58)
        val hotspotZone = ZoneKey.from(hotspot)
        val inTemplate =
            hotspotZone.x in template.zoneX until template.zoneX + template.width &&
                hotspotZone.z in template.zoneZ until template.zoneZ + template.length
        val sw = region.southWest
        val deck =
            if (inTemplate) {
                CoordGrid(
                    sw.x + (hotspot.x - template.zoneX * 8),
                    sw.z + (hotspot.z - template.zoneZ * 8),
                    template.level,
                )
            } else {
                CoordGrid(sw.x + template.width * 4, sw.z + template.length * 4, template.level)
            }
        val root = CoordGrid(dock.x + 2, dock.z, dock.level)
        val state =
            PlayerWorldEntity(
                index = BOAT_INDEX,
                typeId = RAFT_WORLD_ENTITY,
                region = region,
                rootCoords = root,
                angle = 0,
                activeLevel = RAFT_MAIN_LEVEL,
                sizeXZones = template.width,
                sizeZZones = template.length,
            )
        logger.info {
            "[raft] board ${player.username}: template=$template region uid=${region.uid} " +
                "sw=${region.southWest} ne=${region.northEast} swZone=${region.southWestZone} " +
                "deck=$deck (hotspot in template=$inTemplate) root=$root dock=$dock"
        }
        boarded[player] = Boarding(dock, template)
        player.worldEntity = state
        telejump(deck)
        vars[SailingVarBits.on_player_boat] = 1
        vars[SailingVarBits.boarded_boat] = 1
        vars[SailingVarBits.boarded_boat_type] = RAFT_TYPE
        vars[SailingVarBits.boarded_boat_world] = BOAT_INDEX
        vars[SailingVarBits.sidepanel_visible] = 1
        player.ifOpenOverlay(SailingInterfaces.sailing_sidepanel, components.toplevel_target_side0, eventBus)
        mes("You board your raft. (Tier 1 probe: ::raftoff or the gangplank to leave.)")
    }

    private fun ProtectedAccess.disembark() {
        val state = player.worldEntity
        if (state == null) {
            mes("You're not on a boat.")
            return
        }
        val boarding = boarded.remove(player)
        val dock = boarding?.dock ?: CoordGrid(state.rootCoords.x - 2, state.rootCoords.z, state.rootCoords.level)
        logger.info { "[raft] disembark ${player.username}: from ${player.coords} to dock $dock" }
        player.worldEntity = null
        telejump(dock)
        clearBoardingVars()
        player.ifOpenOverlay(interfaces.combat_interface, components.toplevel_target_side0, eventBus)
        mes("You step off your raft.")
    }

    /** Logout on deck: save the player on the dock, keep the boarded varbits for the re-board. */
    private fun saveOnDock(player: Player) {
        val state = player.worldEntity ?: return
        val dock = boarded.remove(player)?.dock ?: state.rootCoords
        logger.info { "[raft] logout on deck ${player.username}: saving at dock $dock (was ${player.coords})" }
        player.worldEntity = null
        player.coords = dock
    }

    private fun ProtectedAccess.clearBoardingVars() {
        vars[SailingVarBits.on_player_boat] = 0
        vars[SailingVarBits.boarded_boat] = 0
        vars[SailingVarBits.boarded_boat_type] = 0
        vars[SailingVarBits.boarded_boat_world] = 0
        vars[SailingVarBits.sidepanel_visible] = 0
    }

    private companion object {
        /** Guess from the cache: sailing_boat raft hotspot coord `1_60_100_3_58` -> zone (480, 807). */
        val DEFAULT_TEMPLATE = Template(zoneX = 480, zoneZ = 807, level = 1, width = 1, length = 1)

        const val BOAT_INDEX = 1
        /** `worldentity_1` (raft): mainlevel=1, bounds 128x384 fine units. */
        const val RAFT_WORLD_ENTITY = 1
        const val RAFT_MAIN_LEVEL = 1
        /** `sailing_boat` raft type_id. */
        const val RAFT_TYPE = 0
    }
}
