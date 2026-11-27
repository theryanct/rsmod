package org.rsmod.content.skills.firemaking.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.content
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.firemakingLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.firemaking.configs.firemaking_locs
import org.rsmod.content.skills.firemaking.configs.firemaking_objs
import org.rsmod.content.skills.firemaking.configs.firemaking_seqs
import org.rsmod.game.MapClock
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

// Out of scope for now: lighting logs already on the ground ("Light" op), line firemaking,
// coloured logs/firelighters, Wintertodt, pyromancer outfit.
class Firemaking
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val mapClock: MapClock,
) : PluginScript() {
    override fun ScriptContext.startup() {
        for (log in burnableLogs) {
            // Both directions: tinderbox -> logs and logs -> tinderbox.
            onOpHeldU(firemaking_objs.tinderbox, log.obj) { light(log) }
        }
    }

    private suspend fun ProtectedAccess.light(log: BurnableLog) {
        if (player.firemakingLvl < log.level) {
            mes("You need a Firemaking level of ${log.level} to burn these logs.")
            return
        }
        if (!canLightAt(coords)) {
            mes("You can't light a fire here.")
            return
        }

        // Like OSRS, the logs are placed on the floor first and lit there. Walking away leaves
        // them on the ground for the player to pick back up.
        val removed = invDel(inv, log.obj, count = 1)
        if (removed.failure) {
            return
        }
        val tile = coords
        val groundLogs = objRepo.add(log.obj, tile, duration = GROUND_LOG_DURATION, player)
        mes("You attempt to light the logs.")

        while (true) {
            anim(firemaking_seqs.human_createfire)
            delay(LIGHT_ATTEMPT_TICKS)
            if (statRandom(stats.firemaking, LIGHT_LOW, LIGHT_HIGH, invisibleLvls)) {
                break
            }
        }

        // Logs may have been picked up by someone else (or despawned) while we were lighting.
        if (!objRepo.del(groundLogs, duration = Int.MAX_VALUE)) {
            resetAnim()
            return
        }

        val burnTicks = random.of(FIRE_MIN_TICKS, FIRE_MAX_TICKS)
        locRepo.add(tile, firemaking_locs.fire, burnTicks, LocAngle.West, LocShape.CentrepieceStraight)
        objRepo.addDelayed(
            Obj.fromServer(this@Firemaking.mapClock, tile, firemaking_objs.ashes, 1),
            spawnDelay = burnTicks,
            duration = ASHES_DURATION,
        )

        val xp = log.xp * xpMods.get(player, stats.firemaking)
        statAdvance(stats.firemaking, xp)
        mes("The fire catches and the logs begin to burn.")
        resetAnim()
        stepAwayFromFire(tile)
    }

    /** OSRS order: west, then east, south, north. */
    private fun ProtectedAccess.stepAwayFromFire(fire: CoordGrid) {
        val options = listOf(fire.translateX(-1), fire.translateX(1), fire.translateZ(-1), fire.translateZ(1))
        val dest = options.firstOrNull { lineOfWalk(fire, it) } ?: return
        walk(dest)
        faceSquare(fire)
    }

    private fun canLightAt(tile: CoordGrid): Boolean {
        // No fire on top of another fire, or on scenery like ferns, flowers or other centrepieces.
        val occupied =
            locRepo.findAll(tile).any {
                it.shape == LocShape.CentrepieceStraight ||
                    it.shape == LocShape.CentrepieceDiagonal ||
                    it.shape == LocShape.GroundDecor
            }
        if (occupied) {
            return false
        }
        return !isNearBank(tile)
    }

    /** There are no bank area definitions yet, so treat tiles near a bank booth/chest as a bank. */
    private fun isNearBank(tile: CoordGrid): Boolean {
        for (dx in -1..1) {
            for (dz in -1..1) {
                val zone = ZoneKey.from(tile.translate(dx * 8, dz * 8))
                val nearBank =
                    locRepo.findAll(zone).any {
                        if (it.coords.level != tile.level) return@any false
                        if (it.coords.chebyshevDistance(tile) > BANK_RADIUS) return@any false
                        val type = locTypes[it.id] ?: return@any false
                        type.isContentType(content.bank_booth) ||
                            type.isContentType(content.bank_chest)
                    }
                if (nearBank) {
                    return true
                }
            }
        }
        return false
    }

    private class BurnableLog(val obj: ObjType, val level: Int, val xp: Double)

    private companion object {
        /** OSRS Wiki: one Firemaking success curve for all regular logs. */
        const val LIGHT_LOW = 64
        const val LIGHT_HIGH = 512
        const val LIGHT_ATTEMPT_TICKS = 4

        /** OSRS Wiki (Mod Ash): fires last 60-119 seconds. */
        const val FIRE_MIN_TICKS = 100
        const val FIRE_MAX_TICKS = 198

        const val GROUND_LOG_DURATION = 200
        const val ASHES_DURATION = 200
        const val BANK_RADIUS = 6

        val burnableLogs =
            listOf(
                BurnableLog(firemaking_objs.logs, 1, 40.0),
                BurnableLog(firemaking_objs.achey_tree_logs, 1, 40.0),
                BurnableLog(firemaking_objs.oak_logs, 15, 60.0),
                BurnableLog(firemaking_objs.willow_logs, 30, 90.0),
                BurnableLog(firemaking_objs.teak_logs, 35, 105.0),
                BurnableLog(firemaking_objs.arctic_pine_logs, 42, 125.0),
                BurnableLog(firemaking_objs.maple_logs, 45, 135.0),
                BurnableLog(firemaking_objs.mahogany_logs, 50, 157.5),
                BurnableLog(firemaking_objs.yew_logs, 60, 202.5),
                BurnableLog(firemaking_objs.magic_logs, 75, 303.8),
                BurnableLog(firemaking_objs.redwood_logs, 90, 350.0),
            )
    }
}
