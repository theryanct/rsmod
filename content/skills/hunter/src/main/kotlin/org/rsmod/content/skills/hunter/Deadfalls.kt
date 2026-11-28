package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Hunter deadfalls (OSRS Wiki Hunter info + catch charts): "Set-trap" on a deadfall boulder with
 * logs (used up) and a knife sets the deadfall; a few ticks later it rolls against the best
 * kebbit/fox the player can catch within 8 tiles: success shows the caught boulder ("Check" for xp
 * and loot), failure lets the boulder fall back. "Dismantle" a set deadfall to undo it (the logs are
 * lost, as in OSRS).
 *
 * Wild 23, barb-tailed 33, prickly 37, sabre-toothed 51 kebbits and the pyre fox 57. Baits are not
 * needed (they only lure creatures in OSRS); the barb-tailed kebbit's Wiki "barb-tail harpoon"
 * drop is left out. Deadfalls share the 1 + level/20 trap limit with no other trap kind here.
 */
class Deadfalls
@Inject
constructor(
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private class Trap(val owner: Player, val boulder: BoundLocInfo, var caught: Prey?)

    private val traps = HashMap<CoordGrid, Trap>()

    override fun ScriptContext.startup() {
        onOpLoc1(DeadfallLocs.boulder) { setTrap(it.loc) }
        onOpLoc1(DeadfallLocs.set) { dismantle(it.loc) }
        for (prey in Prey.entries) {
            onOpLoc1(prey.full()) { check(it.loc) }
        }
        onPlayerQueueWithArgs<CoordGrid>(DeadfallQueues.deadfall_check) { resolve(it.args) }
    }

    private suspend fun ProtectedAccess.setTrap(boulder: BoundLocInfo) {
        val lowest = Prey.entries.minOf { it.level }
        if (player.hunterLvl < lowest) {
            mes("You need a Hunter level of at least $lowest to set a deadfall.")
            return
        }
        if (invTotal(inv, DeadfallObjs.knife) <= 0) {
            mes("You need a knife to set up a deadfall.")
            return
        }
        if (invTotal(inv, DeadfallObjs.logs) <= 0) {
            mes("You need some logs to set up a deadfall.")
            return
        }
        val limit = minOf(MAX_TRAPS, 1 + player.hunterLvl / 20)
        if (traps.values.count { it.owner === player } >= limit) {
            mes("You can't lay more than $limit traps at your Hunter level.")
            return
        }
        if (traps.containsKey(boulder.coords)) {
            mes("Someone has already set a deadfall here.")
            return
        }
        anim(HunterSeqs.lay_trap)
        delay(2)
        if (invDel(inv, DeadfallObjs.logs, 1).failure) {
            return
        }
        locRepo.change(boulder, DeadfallLocs.set, TRAP_LIFE)
        traps[boulder.coords] = Trap(player, boulder, caught = null)
        softQueue(DeadfallQueues.deadfall_check, random.of(8, 16), boulder.coords)
    }

    private fun ProtectedAccess.resolve(coords: CoordGrid) {
        val trap = traps[coords] ?: return
        if (trap.owner !== player || trap.caught != null) {
            return
        }
        if (locRepo.findExact(coords, DeadfallLocs.set) == null) {
            traps.remove(coords)
            return
        }
        val prey = nearestPrey(coords)
        if (prey == null) {
            softQueue(DeadfallQueues.deadfall_check, random.of(10, 20), coords)
            return
        }
        if (statRandom(stats.hunter, prey.low, prey.high, 0)) {
            locRepo.change(trap.boulder, prey.full(), TRAP_LIFE)
            trap.caught = prey
        } else {
            traps.remove(coords)
            reset(trap.boulder)
            mes("The ${prey.title} escapes the deadfall.")
        }
    }

    private fun ProtectedAccess.nearestPrey(coords: CoordGrid): Prey? =
        npcRepo
            .findAll(ZoneKey.from(coords), 1)
            .filter { abs(it.coords.x - coords.x) <= RANGE && abs(it.coords.z - coords.z) <= RANGE }
            .mapNotNull { npc -> Prey.entries.firstOrNull { it.npc().id == npc.id } }
            .filter { it.level <= player.hunterLvl }
            .maxByOrNull { it.level }

    private suspend fun ProtectedAccess.check(loc: BoundLocInfo) {
        val trap = traps[loc.coords]
        val prey = trap?.caught ?: return
        if (trap.owner !== player) {
            mes("This isn't your trap.")
            return
        }
        anim(HunterSeqs.pick_up)
        delay(1)
        traps.remove(loc.coords)
        reset(trap.boulder)
        for (obj in prey.loot()) {
            invAddOrDrop(objRepo, obj)
        }
        statAdvance(stats.hunter, prey.xp * xpMods.get(player, stats.hunter))
        mes("You've caught a ${prey.title}.")
    }

    private suspend fun ProtectedAccess.dismantle(loc: BoundLocInfo) {
        val trap = traps[loc.coords]
        if (trap != null && trap.owner !== player) {
            mes("This isn't your trap.")
            return
        }
        anim(HunterSeqs.pick_up)
        delay(1)
        traps.remove(loc.coords)
        reset(trap?.boulder ?: loc)
    }

    /** Back to the plain boulder (the temporary trap loc expires on the next tick). */
    private fun reset(boulder: BoundLocInfo) {
        locRepo.change(boulder, DeadfallLocs.boulder, 1)
    }

    /** Wiki Hunter info (level, xp), Skilling success chart, "Always" drops; cache npc/loc names. */
    private enum class Prey(
        val title: String,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        val npc: () -> NpcType,
        val full: () -> LocType,
        val loot: () -> List<ObjType>,
    ) {
        Wild("wild kebbit", 23, 102.4, 29, 385, { DeadfallNpcs.wild }, { DeadfallLocs.full_claw },
            { listOf(DeadfallObjs.bones, DeadfallObjs.kebbit_claws, DeadfallObjs.raw_wild_kebbit) }),
        BarbTailed("barb-tailed kebbit", 33, 134.4, -220, 1037, { DeadfallNpcs.barbed }, { DeadfallLocs.full_barbed },
            { listOf(DeadfallObjs.bones, DeadfallObjs.raw_barbed_kebbit) }),
        Prickly("prickly kebbit", 37, 147.2, -70, 331, { DeadfallNpcs.prickly }, { DeadfallLocs.full_spike },
            { listOf(DeadfallObjs.bones, DeadfallObjs.kebbit_spike) }),
        SabreToothed("sabre-toothed kebbit", 51, 160.0, -434, 820, { DeadfallNpcs.sabre }, { DeadfallLocs.full_sabre },
            { listOf(DeadfallObjs.bones, DeadfallObjs.kebbit_teeth) }),
        PyreFox("pyre fox", 57, 177.6, -475, 750, { DeadfallNpcs.pyre_fox }, { DeadfallLocs.full_fennec },
            { listOf(DeadfallObjs.bones, DeadfallObjs.fox_fur, DeadfallObjs.raw_pyre_fox) }),
    }

    private companion object {
        const val MAX_TRAPS = 5
        const val RANGE = 8
        const val TRAP_LIFE = 300
    }
}

internal object DeadfallLocs : LocReferences() {
    val boulder = find("hunting_deadfall_boulder")
    val set = find("hunting_deadfall_trap")
    val full_claw = find("hunting_deadfall_full_claw")
    val full_barbed = find("hunting_deadfall_full_barbed")
    val full_spike = find("hunting_deadfall_full_spike")
    val full_sabre = find("hunting_deadfall_full_sabre")
    val full_fennec = find("hunting_deadfall_full_fennec")
}

internal object DeadfallNpcs : NpcReferences() {
    val wild = find("huntingbeast_claws")
    val barbed = find("huntingbeast_barbedtail")
    val prickly = find("huntingbeast_spiky")
    val sabre = find("huntingbeast_sabreteeth")
    val pyre_fox = find("varlamore_hunterfox01")
}

internal object DeadfallObjs : ObjReferences() {
    val knife = find("knife")
    val logs = find("logs")
    val bones = find("bones")
    val kebbit_claws = find("huntingbeast_claws")
    val raw_wild_kebbit = find("huntingbeast_wild_meat")
    val raw_barbed_kebbit = find("huntingbeast_barbed_meat")
    val kebbit_spike = find("huntingbeast_spike")
    val kebbit_teeth = find("huntingbeast_sabreteeth")
    val fox_fur = find("hunting_fennecfox_fur")
    val raw_pyre_fox = find("hunting_fennecfox_meat")
}

internal object DeadfallQueues : QueueReferences() {
    val deadfall_check = find("hunter_deadfall_check")
}
