package org.rsmod.content.skills.agility

import jakarta.inject.Inject
import java.util.WeakHashMap
import kotlin.math.abs
import org.rsmod.api.config.Constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.agility.AgilityData.Obstacle
import org.rsmod.content.skills.agility.AgilityData.Shortcut
import org.rsmod.content.skills.agility.AgilityData.Target
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Agility courses and shortcuts (data: `agility.tsv`).
 * - Each obstacle checks the level, plays its anim and either walks the player across (exact
 *   move, e.g. log balances, tightropes, pipes) or climbs/jumps after a delay (plane changes).
 * - Obstacles done in order are tracked per player; finishing the last one after a full lap gives
 *   the course's lap bonus and, on rooftop-style courses, a chance of a mark of grace spawning on
 *   the course for the player.
 * - Shortcuts cross to whichever side the player is not on.
 *
 * The op used is the loc type's first op (agility locs only have one, e.g. "Walk-across").
 */
class Agility
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    /** Player -> (course key, last obstacle step completed in order). */
    private val progress = WeakHashMap<Player, Pair<String, Int>>()

    private val courses by lazy { AgilityData.courses.associateBy { it.key } }
    private val lastSteps by lazy {
        AgilityData.obstacles.groupBy { it.course }.mapValues { (_, list) -> list.maxOf { it.step } }
    }

    override fun ScriptContext.startup() {
        val registered = HashSet<Int>()
        for (obstacle in AgilityData.obstacles) {
            for (name in obstacle.locs) {
                register(name, registered) { obstacle(obstacle) }
            }
        }
        for (shortcut in AgilityData.shortcuts) {
            for (name in shortcut.locs) {
                register(name, registered) { shortcut(shortcut) }
            }
        }
    }

    private fun ScriptContext.register(
        name: String,
        registered: MutableSet<Int>,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        val loc = AgilityLocs.byName.getValue(name)
        if (!registered.add(loc.id)) {
            return
        }
        val op = locTypes[loc].op.indexOfFirst { it != null && !it.equals("Examine", ignoreCase = true) }
        when (op + 1) {
            1 -> onOpLoc1(loc) { this.action() }
            2 -> onOpLoc2(loc) { this.action() }
            3 -> onOpLoc3(loc) { this.action() }
            4 -> onOpLoc4(loc) { this.action() }
            5 -> onOpLoc5(loc) { this.action() }
        }
    }

    private suspend fun ProtectedAccess.obstacle(obstacle: Obstacle) {
        if (player.agilityLvl < obstacle.level) {
            mes("You need an Agility level of ${obstacle.level} to attempt this.")
            return
        }
        val start = obstacle.start
        if (start != null && !isNear(start)) {
            mes("You can't do that from here.")
            return
        }
        move(obstacle.anim, obstacle.ticks, resolve(obstacle.end), obstacle.climb)
        statAdvance(stats.agility, obstacle.xp * xpMods.get(player, stats.agility))
        trackLap(obstacle)
    }

    private fun ProtectedAccess.trackLap(obstacle: Obstacle) {
        val last = progress[player]
        val previous = if (last != null && last.first == obstacle.course) last.second else 0
        val now = if (obstacle.step == 1 || obstacle.step == previous + 1) obstacle.step else 0
        if (obstacle.step != lastSteps[obstacle.course]) {
            progress[player] = obstacle.course to now
            return
        }
        progress.remove(player)
        if (now != obstacle.step) {
            return
        }
        val course = courses[obstacle.course] ?: return
        if (course.lapBonusXp > 0) {
            statAdvance(stats.agility, course.lapBonusXp * xpMods.get(player, stats.agility))
        }
        mes("You complete a lap of the ${course.title}.")
        val markSpawn = course.markSpawn
        if (course.markChance > 0 && markSpawn != null && random.of(course.markChance) == 0) {
            objRepo.add(AgilityObjs.mark_of_grace, resolve(markSpawn), MARK_DURATION, player)
            mes("A Mark of Grace appears on the course.")
        }
    }

    private suspend fun ProtectedAccess.shortcut(shortcut: Shortcut) {
        if (player.agilityLvl < shortcut.level) {
            mes("You need an Agility level of ${shortcut.level} to use this shortcut.")
            return
        }
        val a = resolve(shortcut.sideA)
        val b = resolve(shortcut.sideB)
        val end = if (distance(player.coords, a) <= distance(player.coords, b)) b else a
        move(shortcut.anim, shortcut.ticks, end, climb = false)
        if (shortcut.xp > 0) {
            statAdvance(stats.agility, shortcut.xp * xpMods.get(player, stats.agility))
        }
    }

    private suspend fun ProtectedAccess.move(anim: String, ticks: Int, end: CoordGrid, climb: Boolean) {
        anim(AgilitySeqs.byName.getValue(anim))
        if (climb) {
            delay(ticks)
            telejump(end)
            return
        }
        val from = player.coords
        val crossingBas = AgilityBas.byAnim[anim]
        val previousBas = player.bas
        if (crossingBas != null) {
            player.bas = crossingBas
        }
        try {
            exactMove(from, end, 0, ticks * CLIENT_CYCLES_PER_TICK, direction(from, end))
            delay(ticks)
        } finally {
            if (crossingBas != null) {
                player.bas = previousBas
            }
        }
    }

    private fun ProtectedAccess.isNear(target: Target): Boolean {
        val coords = player.coords
        if (coords.level != target.level) return false
        val x = target.x
        val z = target.z
        return (x == null || abs(coords.x - x) <= 1) && (z == null || abs(coords.z - z) <= 1)
    }

    private fun ProtectedAccess.resolve(target: Target): CoordGrid =
        CoordGrid(target.x ?: player.coords.x, target.z ?: player.coords.z, target.level)

    private fun distance(a: CoordGrid, b: CoordGrid): Int = abs(a.x - b.x) + abs(a.z - b.z)

    private fun direction(from: CoordGrid, to: CoordGrid): Int {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (abs(dz) >= abs(dx)) {
            if (dz > 0) Constants.em_face_north else Constants.em_face_south
        } else {
            if (dx > 0) Constants.em_face_east else Constants.em_face_west
        }
    }

    private companion object {
        const val CLIENT_CYCLES_PER_TICK = 30
        /** Marks of grace stay on the course for about ten minutes. */
        const val MARK_DURATION = 1000
    }
}
