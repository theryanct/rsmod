package org.rsmod.content.quests.naturespirit

import jakarta.inject.Inject
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onArea
import org.rsmod.api.script.onAreaExit
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.script.onPlayerTimer
import org.rsmod.api.type.builders.area.AreaBuilder
import org.rsmod.api.type.builders.map.area.MapAreaBuilder
import org.rsmod.api.type.refs.area.AreaReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.naturespirit.NatureSpirit.Companion.NATURE_SPIRIT
import org.rsmod.content.quests.naturespirit.NatureSpirit.Companion.SICKLE
import org.rsmod.game.area.polygon.VertexCoord
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.square.MapSquareKey
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mort Myre's hazards (OSRS Wiki "Swamp decay", "Ghast", "Druid pouch").
 * - Swamp decay: entering the decay area (map squares 53-54 x 52-53, custom map area
 *   `mortmyre_decay`) starts a two-minute timer unless the player wears or carries a blessed silver
 *   sickle, a Rod of Ivandis, or an Ivandis or Blisterwood flail. If the timer runs out inside the
 *   area: "The swamp decays you!", 1-3 damage, and the timer restarts. Outside the area: "The swamp
 *   decay effect is now over." Leaving through the north gate cancels it silently.
 * - Ghasts: every 8 ticks in the swamp, an invisible ghast within 2 tiles attacks (1 in 2). A
 *   charged druid pouch (from the sickle stage of Nature Spirit) reveals it, using one charge and
 *   saving the food. Without one, the ghast rots one food item into rotten food; a player with no
 *   food is hit for 1-3 instead.
 *
 * Simplified: any way into the area starts decay (OSRS: only the north gate, the Mort'ton bridge,
 * fairy ring BKR, the Myreque hideout and the swamp boat), and there is no Fire of
 * Dehumidification (Making Friends with My Arm isn't built).
 */
class MortMyreHazards
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val random: GameRandom,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onArea(MmAreas.decay) { enterSwamp() }
        onAreaExit(MmAreas.decay) { clearTimer(MmTimers.ghast) }
        onPlayerSoftTimer(MmTimers.decay) { decayTick(player) }
        onPlayerTimer(MmTimers.ghast) { ghastTick() }
        onPlayerLogin {
            SwampDecay.active.remove(player.uid.packed)
            if (inSwamp(player.coords)) {
                startDecay(player)
                player.timer(MmTimers.ghast, GHAST_INTERVAL)
            }
        }
        onPlayerLogout {
            SwampDecay.active.remove(player.uid.packed)
            ghastHunters.remove(player.uid.packed)
        }
    }

    private fun ProtectedAccess.enterSwamp() {
        startDecay(player)
        timer(MmTimers.ghast, GHAST_INTERVAL)
    }

    /* Swamp decay */

    private fun startDecay(player: Player) {
        if (isProtected(player)) return
        if (!SwampDecay.active.add(player.uid.packed)) return
        player.softTimer(MmTimers.decay, DECAY_TICKS)
    }

    private fun isProtected(player: Player): Boolean =
        MmObjs.decayWards.any { it in player.inv || it in player.worn }

    private fun decayTick(player: Player) {
        if (!inSwamp(player.coords) || isProtected(player)) {
            player.mes("The swamp decay effect is now over.")
            SwampDecay.cancel(player)
            return
        }
        player.mes("The swamp decays you!")
        player.spotanim(MmSpots.stench)
        player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(1, 3))
    }

    /* Ghasts */

    private suspend fun ProtectedAccess.ghastTick() {
        if (!inSwamp(player.coords)) {
            clearTimer(MmTimers.ghast)
            return
        }
        val hunter = ghastHunters[player.uid.packed]
        if (hunter != null && hunter.isSlotAssigned) return
        val ghast =
            npcRepo.findAll(ZoneKey.from(player.coords), 1).firstOrNull {
                it.id == NsNpcs.ghast_invis.id &&
                    it.isVisible &&
                    it.coords.chebyshevDistance(player.coords) <= GHAST_RANGE
            } ?: return
        if (random.of(1, GHAST_ODDS) != 1) return
        if (player.questState(NATURE_SPIRIT) >= SICKLE && carries(NsObjs.pouch)) {
            usePouchCharge()
            summonVisibleGhast(ghast, npcRepo, npcTypes, aiInteractions)
            mes("Your druid pouch reveals a ghast!")
            return
        }
        val foods = inv.indices.filter { slot -> inv[slot]?.let { isFood(it) } == true }
        if (foods.isEmpty()) {
            // Authored line (not Wiki): the Wiki gives no message for this hit.
            mes("Something unseen claws at you from the swamp mist!")
            player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(1, 3))
            return
        }
        val slot = foods[random.of(0, foods.size - 1)]
        val obj = inv[slot] ?: return
        invDel(inv, objTypes[obj], count = 1, slot = slot)
        invAdd(inv, MmObjs.rotten_food, count = 1, slot = slot)
        mes("You feel something attacking your backpack, and smell a terrible stench.")
    }

    private fun isFood(obj: InvObj): Boolean {
        val type = objTypes[obj]
        if (type.id == MmObjs.rotten_food.id) return false
        return type.iop.any { it.equals("eat", ignoreCase = true) }
    }

    private companion object {
        const val DECAY_TICKS = 200
        const val GHAST_INTERVAL = 8
        const val GHAST_RANGE = 2
        const val GHAST_ODDS = 2
        val DECAY_SQUARES_X = 53..54
        val DECAY_SQUARES_Z = 52..53

        fun inSwamp(coords: CoordGrid): Boolean {
            val square = MapSquareKey.from(coords)
            return square.x in DECAY_SQUARES_X && square.z in DECAY_SQUARES_Z
        }
    }
}

/** Each player's running swamp decay timer (cancelled by Nature Spirit's north gate). */
internal object SwampDecay {
    val active = HashSet<Int>()

    fun cancel(player: Player) {
        player.clearSoftTimer(MmTimers.decay)
        active.remove(player.uid.packed)
    }
}

/** The visible ghast hunting each player (one at a time), by player uid. */
internal val ghastHunters = HashMap<Int, Npc>()

private const val VISIBLE_GHAST_TICKS = 200

internal fun ProtectedAccess.hasGhastHunter(): Boolean =
    ghastHunters[player.uid.packed]?.isSlotAssigned == true

internal fun ProtectedAccess.usePouchCharge() {
    invDel(inv, NsObjs.pouch)
    if (!carries(NsObjs.pouch)) invAdd(inv, NsObjs.pouch_empty)
}

/** Hides [ghast] and spawns its visible form, which attacks the player. */
internal fun ProtectedAccess.summonVisibleGhast(
    ghast: Npc,
    npcRepo: NpcRepository,
    npcTypes: NpcTypeList,
    aiInteractions: AiPlayerInteractions,
) {
    npcRepo.hide(ghast, VISIBLE_GHAST_TICKS)
    val visible = Npc(npcTypes[NsNpcs.ghast_vis], ghast.coords)
    npcRepo.add(visible, VISIBLE_GHAST_TICKS)
    ghastHunters[player.uid.packed] = visible
    visible.opPlayer2(player, aiInteractions)
}

/** The swamp decay area: the four map squares the Wiki's decay map covers. */
internal object MortMyreDecayArea : MapAreaBuilder() {
    override fun onPackMapTask() {
        // One polygon over all four squares (an area block takes a single `mapSquare`).
        area(MmAreas.decay) {
            polygon(levels = 0..0) {
                vertex(VertexCoord(53, 52, 0, 0))
                vertex(VertexCoord(53, 53, 0, 63))
                vertex(VertexCoord(54, 53, 63, 63))
                vertex(VertexCoord(54, 52, 63, 0))
            }
        }
    }
}

internal object MmAreas : AreaReferences() {
    /** Custom map area (32762). */
    val decay = find("mortmyre_decay")
}

/** Creates the custom `mortmyre_decay` area type (the map squares only place it). */
internal object MmAreaBuilds : AreaBuilder() {
    init {
        build("mortmyre_decay")
    }
}

internal object MmTimers : TimerReferences() {
    /** Custom timer (65703): swamp decay, every 200 ticks. */
    val decay = find("mortmyre_decay")

    /** Custom timer (65704): invisible ghast attacks, every 8 ticks while in the swamp. */
    val ghast = find("mortmyre_ghast")
}

internal object MmObjs : ObjReferences() {
    val rotten_food = find("rotten_food")
    /** Blessed silver sickle, Ivandis and Blisterwood flails, Rod of Ivandis (10..1 charges). */
    val decayWards =
        listOf(find("silver_sickle_blessed"), find("ivandis_flail"), find("blisterwood_flail")) +
            (1..10).map { find("burgh_rod_command_final_$it") }
}

internal object MmSpots : SpotanimReferences() {
    val stench = find("mortmyre_swampstench")
}
