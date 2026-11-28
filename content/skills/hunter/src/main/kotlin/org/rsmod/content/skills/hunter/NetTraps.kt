package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Hunter PR2: net traps for swamp lizards and orange/red/black salamanders (OSRS Wiki levels, xp
 * and catch charts). "Set-trap" on the creature's young tree with a net trap in the inventory
 * bends the tree; a few ticks later the trap rolls the Wiki chart: success hangs the caught
 * creature ("Check" for xp + the creature + the net trap back), failure drops the net trap and the
 * tree springs back. "Dismantle" a set trap to take the net trap back.
 *
 * Simplified from OSRS: the trap is the one tree loc (no separate net loc on the next tile), the
 * creature does not have to walk into it, and the tar baits are not needed. Tecu salamanders
 * (Varlamore, 79) are not included.
 */
class NetTraps
@Inject
constructor(
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private class Trap(val owner: Player, val creature: Salamander, val tree: BoundLocInfo, var caught: Boolean)

    private val traps = HashMap<CoordGrid, Trap>()

    override fun ScriptContext.startup() {
        for (creature in Salamander.entries) {
            onOpLoc1(creature.tree()) { setTrap(it.loc, creature) }
            onOpLoc1(creature.set()) { dismantle(it.loc) }
            onOpLoc1(creature.full()) { check(it.loc) }
            onOpLoc2(creature.full()) { check(it.loc) }
        }
        onPlayerQueueWithArgs<CoordGrid>(NetTrapQueues.net_trap_check) { resolve(it.args) }
    }

    private suspend fun ProtectedAccess.setTrap(tree: BoundLocInfo, creature: Salamander) {
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to catch a ${creature.title}.")
            return
        }
        if (invTotal(inv, NetTrapObjs.net_trap) <= 0) {
            mes("You need a net trap to set a trap here.")
            return
        }
        val limit = minOf(MAX_TRAPS, 1 + player.hunterLvl / 20)
        if (traps.values.count { it.owner === player } >= limit) {
            mes("You can't lay more than $limit traps at your Hunter level.")
            return
        }
        if (traps.containsKey(tree.coords)) {
            mes("Someone has already set a trap here.")
            return
        }
        anim(HunterSeqs.lay_trap)
        delay(2)
        if (invDel(inv, NetTrapObjs.net_trap, 1).failure) {
            return
        }
        locRepo.change(tree, creature.set(), TRAP_LIFE)
        traps[tree.coords] = Trap(player, creature, tree, caught = false)
        softQueue(NetTrapQueues.net_trap_check, random.of(8, 16), tree.coords)
    }

    private fun ProtectedAccess.resolve(coords: CoordGrid) {
        val trap = traps[coords] ?: return
        if (trap.owner !== player || trap.caught) {
            return
        }
        if (locRepo.findExact(coords, trap.creature.set()) == null) {
            // Timed out: the tree sprang back; the net trap falls to the ground.
            traps.remove(coords)
            objRepo.add(NetTrapObjs.net_trap, coords, NET_DROP_DURATION, player)
            return
        }
        if (statRandom(stats.hunter, trap.creature.low, trap.creature.high, 0)) {
            locRepo.change(trap.tree, trap.creature.full(), TRAP_LIFE)
            trap.caught = true
        } else {
            traps.remove(coords)
            springBack(trap.tree)
            objRepo.add(NetTrapObjs.net_trap, coords, NET_DROP_DURATION, player)
            mes("The ${trap.creature.title} got away and your net trap fell.")
        }
    }

    private suspend fun ProtectedAccess.check(loc: BoundLocInfo) {
        val trap = traps[loc.coords]
        if (trap == null || !trap.caught) {
            return
        }
        if (trap.owner !== player) {
            mes("This isn't your trap.")
            return
        }
        anim(HunterSeqs.pick_up)
        delay(1)
        traps.remove(loc.coords)
        springBack(trap.tree)
        invAddOrDrop(objRepo, trap.creature.loot())
        invAddOrDrop(objRepo, NetTrapObjs.net_trap)
        statAdvance(stats.hunter, trap.creature.xp * xpMods.get(player, stats.hunter))
        mes("You've caught a ${trap.creature.title}.")
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
        springBack(trap?.tree ?: loc)
        invAddOrDrop(objRepo, NetTrapObjs.net_trap)
    }

    /** Back to the plain young tree (the temporary trap loc expires on the next tick). */
    private fun springBack(tree: BoundLocInfo) {
        val id = tree.entity.id
        val original =
            Salamander.entries
                .firstOrNull { id == it.tree().id || id == it.set().id || id == it.full().id }
                ?.tree()
        if (original != null) {
            locRepo.change(tree, original, 1)
        }
    }

    /** Wiki Hunter info + Skilling success chart; tree/trap locs per creature from the cache. */
    private enum class Salamander(
        val title: String,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        val tree: () -> LocType,
        val set: () -> LocType,
        val full: () -> LocType,
        val loot: () -> ObjType,
    ) {
        SwampLizard("swamp lizard", 29, 152.0, 52, 360,
            { NetTrapLocs.up_swamp }, { NetTrapLocs.set_swamp }, { NetTrapLocs.full_green }, { NetTrapObjs.green_salamander }),
        Orange("orange salamander", 47, 224.0, 16, 288,
            { NetTrapLocs.up_orange }, { NetTrapLocs.set_orange }, { NetTrapLocs.full_orange }, { NetTrapObjs.orange_salamander }),
        Red("red salamander", 59, 272.0, 0, 240,
            { NetTrapLocs.up_red }, { NetTrapLocs.set_red }, { NetTrapLocs.full_red }, { NetTrapObjs.red_salamander }),
        Black("black salamander", 67, 319.2, 0, 212,
            { NetTrapLocs.up_black }, { NetTrapLocs.set_black }, { NetTrapLocs.full_black }, { NetTrapObjs.black_salamander }),
    }

    private companion object {
        const val MAX_TRAPS = 5
        const val TRAP_LIFE = 300
        const val NET_DROP_DURATION = 200
    }
}

internal object NetTrapLocs : LocReferences() {
    val up_swamp = find("hunting_sapling_up_swamp")
    val set_swamp = find("hunting_sapling_set_swamp")
    val full_green = find("hunting_sapling_full_green")
    val up_orange = find("hunting_sapling_up_orange")
    val set_orange = find("hunting_sapling_set_orange")
    val full_orange = find("hunting_sapling_full_orange")
    val up_red = find("hunting_sapling_up_red")
    val set_red = find("hunting_sapling_set_red")
    val full_red = find("hunting_sapling_full_red")
    val up_black = find("hunting_sapling_up_black")
    val set_black = find("hunting_sapling_set_black")
    val full_black = find("hunting_sapling_full_black")
}

internal object NetTrapObjs : ObjReferences() {
    val net_trap = find("net_trap")
    val green_salamander = find("green_salamander")
    val orange_salamander = find("orange_salamander")
    val red_salamander = find("red_salamander")
    val black_salamander = find("black_salamander")
}

internal object NetTrapQueues : QueueReferences() {
    val net_trap_check = find("hunter_net_trap_check")
}
