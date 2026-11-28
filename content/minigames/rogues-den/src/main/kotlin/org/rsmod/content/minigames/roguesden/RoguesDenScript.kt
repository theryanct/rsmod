package org.rsmod.content.minigames.roguesden

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.builders.map.obj.MapObjSpawnBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rogues' Den maze (Burthorpe), solo. Data: ~/code/rsmod-roguesden-data.md (OSRS Wiki, live
 * bot routes and the Wiki tile markers; every coordinate matches this cache).
 *
 * - The doorway needs base 50 Agility and Thieving and nothing worn or carried; Prayer goes to 0,
 *   Thieving and Agility to their base levels, and you get a mystic jewel (Activate = leave).
 * - Interactive obstacles: contortion bars (-5 Agility, across the bars), ledges (-4; -7 for the
 *   long one; to the far end of the ledge), grills and locked doors (onto the tile beyond, rot
 *   0=W 1=N 2=E 3=S; locked doors need base 80 Thieving, -10), passage pairs, the mosaic door
 *   (only the correct tile works; a wrong one ejects you), trap Search (-1 Thieving, disarms it).
 * - Walk-on hazards, checked every tick: pendulums and floor blades (-3 Agility, you land on the
 *   first tile past them), spinning blades (ejected), floor traps you stand on (ejected unless
 *   disarmed). Rogue guards catch you within [GUARD_RANGE] unless dazed with flash powder.
 * - A drain below 0 ejects you. Cracking a safe (Mod Ash's rules) ends the run with 12 xp per
 *   level drained in both skills; leaving early pays [EARLY_FRACTION] of that (fraction unknown).
 * - Not yet: the gear door and spin-blade NPCs (off the main route), the viewing gallery (its
 *   landing tiles are unknown; its doors just end the run), wall saws/blades (no data).
 */
class RoguesDenScript
@Inject
constructor(
    private val locRepo: LocRepository,
    private val locTypes: LocTypeList,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val npcList: NpcList,
) : PluginScript() {
    private enum class HazardKind { Pendulum, Blade, Spinning, FloorTrap }

    private class Hazard(val kind: HazardKind, val origin: CoordGrid)

    private class Run {
        var last: CoordGrid? = null
        var stood = 0
        val disarmed = HashSet<CoordGrid>()
    }

    private val runs = WeakHashMap<Player, Run>()
    private val dazedUntil = HashMap<Npc, Long>()
    private var clock = 0L

    /** Every hazard tile in the maze, built on first entry from the map's locs. */
    private val hazards: Map<CoordGrid, Hazard> by lazy { scanHazards() }

    override fun ScriptContext.startup() {
        bind(RdLocs.trapdoor, 1) { telejump(LOBBY_ARRIVAL) }
        bind(RdLocs.pub_passage, 1) { telejump(PUB) }
        bind(RdLocs.maze_entrance, 1) { enterMaze() }
        bind(RdLocs.contortion_bars, 1) { cross(it, mirror(it), stats.agility, BARS_DRAIN) }
        bind(RdLocs.hanging_ledge, 1) { ledge(it, hanging = true) }
        bind(RdLocs.balancing_ledge, 1) { ledge(it, hanging = false) }
        bind(RdLocs.grill, 1) { cross(it, beyond(it), null, 0) }
        bind(RdLocs.locked_door, 1) { lockedDoor(it) }
        bind(RdLocs.passage, 1) { passage(it) }
        bind(RdLocs.mosaic_door, 1) { mosaicDoor() }
        bind(RdLocs.maze_safe, 1) { crackSafe() }
        bind(RdLocs.gallery_door, 1) { leave(success = false, "You leave the maze for the viewing gallery.") }
        for (trap in RdLocs.searchable_traps) bind(trap, 5) { searchTrap(it) }
        if (!isHeldOpBound(RdObjs.mystic_jewel, 1)) {
            onOpHeld1(RdObjs.mystic_jewel) { leave(success = false, "The jewel whisks you back to the lobby.") }
        }
        for (guard in RdNpcs.guards) {
            onOpNpcU(guard, RdObjs.flash_powder) { daze(it.npc) }
        }
        onPlayerSoftTimer(RdTimers.tick) { player.mazeTick() }
        onPlayerQueueWithArgs<HazardHit>(RdQueues.hazard) { hazardHit(it.args) }
        onPlayerLogout { player.endRun() }
    }

    private fun ScriptContext.bind(type: LocType, op: Int, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            5 -> onOpLoc5(type) { action(it.loc) }
        }
    }

    /* Entering and leaving */

    private fun ProtectedAccess.enterMaze() {
        if (statBase(stats.agility) < MIN_LEVEL || statBase(stats.thieving) < MIN_LEVEL) {
            mes("You need level $MIN_LEVEL Agility and Thieving to enter the maze.")
            return
        }
        if (!inv.isEmpty() || !worn.isEmpty()) {
            mes("Tut tut tut, now you know you're not allowed to take anything except that jewel in with you.")
            return
        }
        player.statRestore(stats.agility)
        player.statRestore(stats.thieving)
        player.statSub(stats.prayer, player.stat(stats.prayer), 0)
        invAdd(inv, RdObjs.mystic_jewel, 1)
        runs[player] = Run()
        player.softTimer(RdTimers.tick, 1)
        telejump(MAZE_START)
    }

    private fun ProtectedAccess.leave(success: Boolean, message: String) {
        if (runs[player] == null) return
        val agility = statBase(stats.agility) - player.stat(stats.agility)
        val thieving = statBase(stats.thieving) - player.stat(stats.thieving)
        val share = if (success) 1.0 else EARLY_FRACTION
        player.endRun()
        telejump(LOBBY)
        if (agility > 0) statAdvance(stats.agility, agility * XP_PER_LEVEL * share * xpMods.get(player, stats.agility))
        if (thieving > 0) statAdvance(stats.thieving, thieving * XP_PER_LEVEL * share * xpMods.get(player, stats.thieving))
        mes(message)
    }

    private fun Player.endRun() {
        runs.remove(this) ?: return
        clearSoftTimer(RdTimers.tick)
        val mazeObjs = listOf(RdObjs.mystic_jewel, RdObjs.flash_powder) + RdObjs.tiles
        for (slot in inv.indices) {
            if (mazeObjs.any { it.id == inv[slot]?.id }) inv[slot] = null
        }
        statRestore(stats.agility)
        statRestore(stats.thieving)
        statRestore(stats.prayer)
    }

    /** Drains [amount] of [stat]; false (and the caller ejects) if that would go below 0. */
    private fun ProtectedAccess.drain(stat: StatType, amount: Int): Boolean {
        if (player.stat(stat) < amount) {
            return false
        }
        player.statSub(stat, amount, 0)
        return true
    }

    private fun ProtectedAccess.eject(reason: String) = leave(success = false, reason)

    /* Interactive obstacles */

    private fun ProtectedAccess.cross(loc: BoundLocInfo, dest: CoordGrid, stat: StatType?, amount: Int) {
        if (runs[player] == null) return
        if (stat != null && !drain(stat, amount)) {
            return eject("You're too exhausted to carry on and slip out of the maze.")
        }
        telejump(dest)
    }

    /** Across a bars/obstacle loc: the tile mirrored through it from where you stand. */
    private fun ProtectedAccess.mirror(loc: BoundLocInfo): CoordGrid =
        CoordGrid(loc.coords.x * 2 - coords.x, loc.coords.z * 2 - coords.z, loc.coords.level)

    /** Through a wall-shape loc onto the tile beyond (rot 0 = W, 1 = N, 2 = E, 3 = S). */
    private fun beyond(loc: BoundLocInfo): CoordGrid {
        val (dx, dz) = DIRS[loc.angleId and 3]
        return loc.coords.translate(dx, dz)
    }

    private fun ProtectedAccess.ledge(loc: BoundLocInfo, hanging: Boolean) {
        // The ledge is a row (or column) of the same loc; climb to the far end.
        val type = loc.id
        fun isLedge(c: CoordGrid) = locRepo.findAll(c).any { it.id == type }
        var minX = loc.coords.x
        var maxX = loc.coords.x
        var minZ = loc.coords.z
        var maxZ = loc.coords.z
        while (isLedge(CoordGrid(minX - 1, loc.coords.z, loc.coords.level))) minX--
        while (isLedge(CoordGrid(maxX + 1, loc.coords.z, loc.coords.level))) maxX++
        while (isLedge(CoordGrid(loc.coords.x, minZ - 1, loc.coords.level))) minZ--
        while (isLedge(CoordGrid(loc.coords.x, maxZ + 1, loc.coords.level))) maxZ++
        val level = loc.coords.level
        val dest =
            if (maxX > minX) {
                val z = loc.coords.z
                if (coords.x > maxX) CoordGrid(minX - 1, z, level) else CoordGrid(maxX + 1, z, level)
            } else {
                val x = loc.coords.x
                if (coords.z > maxZ) CoordGrid(x, minZ - 1, level) else CoordGrid(x, maxZ + 1, level)
            }
        val length = maxOf(maxX - minX, maxZ - minZ) + 1
        val amount = if (hanging && length >= LONG_LEDGE) LONG_LEDGE_DRAIN else LEDGE_DRAIN
        cross(loc, dest, stats.agility, amount)
    }

    private fun ProtectedAccess.lockedDoor(loc: BoundLocInfo) {
        if (statBase(stats.thieving) < SHORTCUT_THIEVING) {
            mes("You need a Thieving level of $SHORTCUT_THIEVING to pick this lock.")
            return
        }
        cross(loc, beyond(loc), stats.thieving, SHORTCUT_DRAIN)
    }

    /** Passage pairs: two locs 2 tiles apart on one axis; out past the other one. */
    private fun ProtectedAccess.passage(loc: BoundLocInfo) {
        val here = loc.coords
        val other =
            listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2)
                .map { (dx, dz) -> here.translate(dx, dz) }
                .firstOrNull { c -> locRepo.findAll(c).any { it.id == loc.id } }
                ?: return
        val dx = (other.x - here.x) / 2
        val dz = (other.z - here.z) / 2
        cross(loc, other.translate(dx, dz), null, 0)
    }

    private fun ProtectedAccess.mosaicDoor() {
        if (runs[player] == null) return
        val correct = RdObjs.tiles.first()
        if (invTotal(inv, correct) > 0) {
            invDel(inv, correct, 1)
            mes("A perfect match!")
            telejump(MOSAIC_EXIT)
            return
        }
        if (RdObjs.tiles.drop(1).any { invTotal(inv, it) > 0 }) {
            return eject("The tile doesn't fit, and a trap springs beneath you!")
        }
        mes("The door has a missing tile in its mosaic.")
    }

    private fun ProtectedAccess.searchTrap(loc: BoundLocInfo) {
        val run = runs[player] ?: return
        if (!drain(stats.thieving, TRAP_DRAIN)) {
            return eject("You fumble the trap and it goes off!")
        }
        run.disarmed += loc.coords
        mes("You temporarily disarm the trap!")
    }

    /** Mod Ash: roll 0..199 against Thieving; over it, half eject empty, half drain 1 and loot. */
    private fun ProtectedAccess.crackSafe() {
        if (runs[player] == null) return
        val roll = random.of(SAFE_ROLL)
        if (roll > player.stat(stats.thieving)) {
            if (random.of(2) == 0) {
                return eject("You slip and trigger a trap!")
            }
            drain(stats.thieving, 1)
        }
        val reward = if (random.of(8) < 3) RdObjs.rogue_kit else RdObjs.equipment_crate
        leave(success = true, "You crack the safe and find something inside.")
        invAdd(inv, reward, 1)
    }

    private fun daze(npc: Npc) {
        dazedUntil[npc] = clock + DAZE_TICKS
    }

    /* Walk-on hazards and guards */

    private class HazardHit(val land: CoordGrid?, val drainAgility: Int, val ejectReason: String?)

    private fun Player.mazeTick() {
        val run = runs[this] ?: return clearSoftTimer(RdTimers.tick)
        clock++
        val here = coords
        val prev = run.last
        run.stood = if (prev == here) run.stood + 1 else 0
        run.last = here
        for (npc in npcList) {
            if (npc.type.id !in RdNpcs.guardIds) continue
            if ((dazedUntil[npc] ?: 0) > clock) continue
            if (npc.coords.level == here.level && npc.coords.chebyshevDistance(here) <= GUARD_RANGE) {
                strongQueue(RdQueues.hazard, 1, HazardHit(null, 0, "A rogue guard spots you and throws you out!"))
                return
            }
        }
        val hazard = hazards[here] ?: return
        when (hazard.kind) {
            HazardKind.Spinning ->
                strongQueue(RdQueues.hazard, 1, HazardHit(null, 0, "The spinning blades catch you!"))
            HazardKind.FloorTrap ->
                if (run.stood >= 1 && hazard.origin !in run.disarmed) {
                    strongQueue(RdQueues.hazard, 1, HazardHit(null, 0, "You set off a trap!"))
                }
            HazardKind.Pendulum, HazardKind.Blade -> {
                val land = landPast(here, prev ?: here)
                strongQueue(RdQueues.hazard, 1, HazardHit(land, HAZARD_DRAIN, null))
            }
        }
    }

    /** The first tile past the hazard footprint, continuing in the direction you were moving. */
    private fun landPast(here: CoordGrid, prev: CoordGrid): CoordGrid {
        val dx = (here.x - prev.x).coerceIn(-1, 1)
        val dz = (here.z - prev.z).coerceIn(-1, 1)
        if (dx == 0 && dz == 0) return here
        var c = here
        var steps = 0
        while (hazards[c] != null && steps < 6) {
            c = c.translate(dx, dz)
            steps++
        }
        return c
    }

    private fun ProtectedAccess.hazardHit(hit: HazardHit) {
        if (runs[player] == null) return
        if (hit.ejectReason != null) {
            return eject(hit.ejectReason)
        }
        if (!drain(stats.agility, hit.drainAgility)) {
            return eject("You're too exhausted to dodge and get knocked out of the maze.")
        }
        hit.land?.let { telejump(it) }
    }

    private fun scanHazards(): Map<CoordGrid, Hazard> {
        val kinds =
            mapOf(
                RdLocs.pendulum.id to HazardKind.Pendulum,
                RdLocs.floor_blade.id to HazardKind.Blade,
                RdLocs.spinning_blades.id to HazardKind.Spinning,
            ) + RdLocs.floor_traps.associate { it.id to HazardKind.FloorTrap }
        val out = HashMap<CoordGrid, Hazard>()
        for (x in MAZE_X) {
            for (z in MAZE_Z) {
                val c = CoordGrid(x, z, MAZE_LEVEL)
                for (loc in locRepo.findAll(c)) {
                    val kind = kinds[loc.id] ?: continue
                    val type = locTypes[loc.id] ?: continue
                    val odd = loc.angleId and 1 == 1
                    val w = if (odd) type.length else type.width
                    val l = if (odd) type.width else type.length
                    for (dx in 0 until w) for (dz in 0 until l) {
                        out.putIfAbsent(c.translate(dx, dz), Hazard(kind, c))
                    }
                }
            }
        }
        return out
    }

    private companion object {
        const val MIN_LEVEL = 50
        const val SHORTCUT_THIEVING = 80
        const val SHORTCUT_DRAIN = 10
        const val BARS_DRAIN = 5
        const val LEDGE_DRAIN = 4
        const val LONG_LEDGE = 8
        const val LONG_LEDGE_DRAIN = 7
        const val HAZARD_DRAIN = 3
        const val TRAP_DRAIN = 1
        const val SAFE_ROLL = 200
        const val XP_PER_LEVEL = 12.0
        /** Unknown on the Wiki; half the xp for leaving early (authored). */
        const val EARLY_FRACTION = 0.5
        /** Unknown; 2 tiles (authored). */
        const val GUARD_RANGE = 2
        const val DAZE_TICKS = 10L
        const val MAZE_LEVEL = 1
        val MAZE_X = 2940..3070
        val MAZE_Z = 4980..5115
        val DIRS = listOf(-1 to 0, 0 to 1, 1 to 0, 0 to -1)

        val PUB = CoordGrid(2906, 3537, 0)
        val LOBBY_ARRIVAL = CoordGrid(3061, 4985, 1)
        /** Back in the lobby after the maze (unknown on the Wiki; outside the doorway). */
        val LOBBY = CoordGrid(3056, 4990, 1)
        val MAZE_START = CoordGrid(3056, 4992, 1)
        val MOSAIC_EXIT = CoordGrid(3024, 5082, 1)
    }
}

internal object RdLocs : LocReferences() {
    val trapdoor = find("roguesden_trapdoor_entrance")
    val pub_passage = find("roguesden_passage_to_pub")
    val maze_entrance = find("roguesden_mazeentrance")
    val contortion_bars = find("roguesden_obstacle_contortion_bars")
    val hanging_ledge = find("roguesden_obstacle_wall_hanging_ledge")
    val balancing_ledge = find("roguesden_obstacle_floor_balancing_ledge")
    val grill = find("roguesden_obstacle_blocking_door_enter")
    val locked_door = find("roguesden_obstacle_door")
    val passage = find("roguesden_passage_across")
    val mosaic_door = find("roguesden_puzzle_door_mosaic")
    val maze_safe = find("roguesden_walldecor_mazesafe")
    val gallery_door = find("roguesden_gallery_door")
    val pendulum = find("roguesden_trap_floor_pendulum")
    val floor_blade = find("roguesden_trap_floor_timedblade")
    val spinning_blades = find("roguesden_trap_floor_scytheblade")
    val floor_traps =
        listOf(
            find("roguesden_trap_floor_spikes"),
            find("roguesden_trap_floor_springtile"),
            find("roguesden_trap_floor_explosion"),
        )
    val searchable_traps =
        floor_traps +
            listOf(
                find("roguesden_trap_wall_spikes"),
                find("roguesden_trap_wall_poison_gas"),
                find("roguesden_trap_wall_crusher"),
            )
}

internal object RdNpcs : NpcReferences() {
    val guards = listOf(find("roguesden_guard1"), find("roguesden_guard2"), find("roguesden_guard3"))
    val guardIds: Set<Int> by lazy { guards.map { it.id }.toSet() }
}

internal object RdObjs : ObjReferences() {
    val mystic_jewel = find("roguesden_gem")
    val flash_powder = find("roguesden_flash_powder")
    /** The mosaic tiles; the first is the correct one (5568). */
    val tiles =
        listOf(
            find("roguesden_puzzle_mosaic_tile1"),
            find("roguesden_puzzle_mosaic_tile2"),
            find("roguesden_puzzle_mosaic_tile3"),
            find("roguesden_puzzle_mosaic_tile4"),
        )
    val rogue_kit = find("roguesden_kit")
    val equipment_crate = find("roguesden_crate")
}

internal object RdTimers : TimerReferences() {
    /** Custom (ui range 65304): maze hazards and guards. */
    val tick = find("roguesden_tick")
}

internal object RdQueues : QueueReferences() {
    /** Custom (ui range 65305): resolve a hazard / guard catch with protected access. */
    val hazard = find("roguesden_hazard")
}

internal object RdNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<RdNpcSpawns>("npcs.toml")
    }
}

internal object RdObjSpawns : MapObjSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<RdObjSpawns>("objs.toml")
    }
}
