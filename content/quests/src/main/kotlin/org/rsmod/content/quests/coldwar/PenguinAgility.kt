package org.rsmod.content.quests.coldwar

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.config.Constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onApLoc1
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.quests.coldwar.ColdWar.Companion.COLD_WAR
import org.rsmod.content.quests.coldwar.ColdWar.Companion.COURSE_DONE
import org.rsmod.content.quests.coldwar.ColdWar.Companion.DEBRIEFED
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Penguin Agility Course (OSRS Wiki "Penguin Agility Course" and its obstacle pages), on the
 * iceberg behind the KGP base. Open once Cold War's debriefing is done (and after the quest), only
 * inside the clockwork suit, 30 Agility (boostable). A lap, in order:
 * - Ice steps down into the water and past the crushers (55 xp, given on climbing the first stone).
 * - Stepping stones: climb the first, then seven jumps (one per tick) to the far shore (80 xp);
 *   success 155-308/256 by level (Mod Ash), the first jump never fails, a failure drops you into
 *   the water for a little damage. Clicking a stone continues from the one you stand on.
 * - Icicles: four "Tread-softly" crossings, 40 xp each, same rate; a failure hurts and you stay.
 * - Ice: "Cross" one row at a time down the slope (180 xp at the bottom row, which never fails); a
 *   failure deals 2 and slides you back to the top. Then the snow jumps carry you to the finish.
 * - The gate out of the finish pen: 65 xp when the lap was done in order (540 a lap in all).
 * The first lap during the quest lets the Agility Instructor sign you off.
 *
 * Simplified: the crushers don't move or push (their xp comes with the stones); the ice's failure
 * rate isn't published, so it uses the icicles' rate; the slide is a short telejump sequence rather
 * than a cutscene; no giant squirrel roll.
 */
class PenguinAgility
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ColdWar::class.java, "cold_war") }

    /** Obstacles done this lap (bits below), per player. */
    private val laps = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, CwNpcs.instructor, COLD_WAR, wants = { true }) { talkInstructor(it) }
        }
        onLocOpLabel(locTypes, CwLocs.steps, "Climb") { steps() }
        onLocOpLabel(locTypes, CwLocs.first_stone, "Climb") { climbStone() }
        for (zone in CwLocs.stone_zones) {
            onLocOpLabel(locTypes, zone, "Jump") { continueStones() }
            onApLoc1(zone) { if (isWithinApRange(it.loc, STONE_REACH)) continueStones() }
        }
        onLocOpLabel(locTypes, CwLocs.icicles, "Tread-softly") { icicles(it) }
        onApLoc1(CwLocs.icicles) { if (isWithinApRange(it.loc, 1)) icicles(it.loc) }
        for (ice in CwLocs.ice) {
            onLocOpLabel(locTypes, ice, "Cross") { crossIce(it) }
            onApLoc1(ice) { if (isWithinApRange(it.loc, 1)) crossIce(it.loc) }
        }
        onLocOpLabel(locTypes, CwLocs.gate, "Open") { gate(it) }
    }

    private var ProtectedAccess.lap: Int
        get() = laps[player.uid.packed] ?: 0
        set(value) {
            laps[player.uid.packed] = value
        }

    private fun ProtectedAccess.canRun(): Boolean {
        if (player.vars[CwVarBits.transmog] == 0) {
            mes("You need to be in your penguin suit to use the course.") // Authored (not Wiki).
            return false
        }
        if (player.questState(COLD_WAR) < DEBRIEFED) {
            mes("You haven't been cleared to use the course yet.") // Authored (not Wiki).
            return false
        }
        if (stat(stats.agility) < LEVEL) {
            mes("You need an Agility level of $LEVEL to attempt this.")
            return false
        }
        return true
    }

    private fun ProtectedAccess.xp(amount: Double) {
        statAdvance(stats.agility, amount * xpMods.get(player, stats.agility))
    }

    private fun ProtectedAccess.passes(): Boolean = statRandom(stats.agility, LOW, HIGH, 0)

    private fun ProtectedAccess.hurt(damage: Int) {
        player.queueHit(1, HitType.Typeless, damage)
    }

    private suspend fun ProtectedAccess.move(to: CoordGrid, ticks: Int) {
        val from = player.coords
        exactMove(from, to, 0, ticks * CLIENT_CYCLES_PER_TICK, direction(from, to))
        delay(ticks)
        if (player.coords != to) telejump(to)
    }

    /* Ice steps and stepping stones */

    private suspend fun ProtectedAccess.steps() {
        if (!canRun()) return
        lap = STEPS
        anim(CwSeqs.jump)
        delay(1)
        telejump(WATER_START)
    }

    private suspend fun ProtectedAccess.climbStone() {
        if (!canRun()) return
        if ((lap and STEPS) != 0 && (lap and CRUSHERS) == 0) {
            xp(CRUSHER_XP)
            lap = lap or CRUSHERS
        }
        anim(CwSeqs.jump)
        delay(1)
        telejump(STONES[0])
        jumpStones(from = 0)
    }

    private suspend fun ProtectedAccess.continueStones() {
        if (!canRun()) return
        val at = STONES.indexOf(player.coords)
        if (at < 0) {
            mes("You can't reach that from here.") // Authored (not Wiki).
            return
        }
        jumpStones(from = at)
    }

    private suspend fun ProtectedAccess.jumpStones(from: Int) {
        for (next in from + 1 until STONES.size) {
            if (next > 1 && !passes()) {
                anim(CwSeqs.jump_fail)
                delay(1)
                telejump(WATER_FALL)
                hurt(random.of(1, 3))
                mes("You slip and fall into the icy water!") // Authored (not Wiki).
                return
            }
            anim(CwSeqs.jump)
            move(STONES[next], 1)
        }
        anim(CwSeqs.jump)
        move(STONES_LAND, 1)
        xp(STONES_XP)
        lap = lap or STONES_DONE
    }

    /* Icicles */

    private suspend fun ProtectedAccess.icicles(loc: BoundLocInfo) {
        if (!canRun()) return
        val c = player.coords
        val side = c.x - loc.coords.x
        if (c.level != loc.coords.level || abs(side) != 1 || abs(c.z - loc.coords.z) > 1) {
            mes("You can't reach that from here.") // Authored (not Wiki).
            return
        }
        val dest = CoordGrid(loc.coords.x - side, loc.coords.z, c.level)
        if (!passes()) {
            anim(CwSeqs.icicle_fail)
            delay(2)
            hurt(random.of(1, 3))
            mes("An icicle falls on you!") // Authored (not Wiki).
            return
        }
        anim(CwSeqs.icicle_walk)
        move(dest, 2)
        xp(ICICLE_XP)
        val bit = ICICLE_BITS[ICICLE_XS.indexOf(loc.coords.x).coerceAtLeast(0)]
        lap = lap or bit
    }

    /* Ice */

    private suspend fun ProtectedAccess.crossIce(loc: BoundLocInfo) {
        if (!canRun()) return
        val c = player.coords
        if (c.level != loc.coords.level || c.z != loc.coords.z + 1 || abs(c.x - loc.coords.x) > 1) {
            mes("You need to cross the ice one step at a time, from the top.") // Authored (not Wiki).
            return
        }
        val last = loc.coords.z == ICE_BOTTOM_Z
        if (!last && !passes()) {
            anim(CwSeqs.ice_fail)
            delay(1)
            hurt(ICE_DAMAGE)
            telejump(ICE_TOP)
            mes("You slip and slide back to the top of the ice.") // Authored (not Wiki).
            return
        }
        anim(CwSeqs.ice_walk)
        move(loc.coords, 1)
        if (!last) return
        xp(ICE_XP)
        lap = lap or ICE_DONE
        slide()
    }

    /** Down the snow jumps to the finish line (Wiki: a short cutscene). */
    private suspend fun ProtectedAccess.slide() {
        for (point in SLIDE) {
            anim(CwSeqs.slide)
            delay(2)
            telejump(point)
        }
        lap = lap or SLID
    }

    /* The finish */

    private suspend fun ProtectedAccess.gate(gate: BoundLocInfo) {
        val inside = player.coords.x >= gate.coords.x
        passThroughWall(gate)
        if (!inside) return
        if ((lap and FULL_LAP) != FULL_LAP) {
            laps.remove(player.uid.packed)
            return
        }
        laps.remove(player.uid.packed)
        xp(GATE_XP)
        mes("You complete a lap of the Penguin Agility Course.") // Authored (not Wiki).
        if (player.questState(COLD_WAR) == DEBRIEFED) VarPlayerIntMapSetter.set(player, CwVarBits.first_lap, 1)
    }

    private suspend fun ProtectedAccess.talkInstructor(npc: Npc) {
        val state = player.questState(COLD_WAR)
        when {
            state == DEBRIEFED && player.vars[CwVarBits.first_lap] == 1 -> {
                runQuestSection(npc, dialogue, "Finishing the agility course")
                player.setQuestState(COLD_WAR, COURSE_DONE)
            }
            state == DEBRIEFED ->
                startDialogue(npc) { chatNpc(neutral, "Get going on the course, soldier! Down the steps by the water.") }
            else ->
                startDialogue(npc) { chatNpc(neutral, "Back to tone up your body, soldier? The course is all yours.") }
        }
    }

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
        const val LEVEL = 30
        const val LOW = 155
        const val HIGH = 308
        const val CRUSHER_XP = 55.0
        const val STONES_XP = 80.0
        const val ICICLE_XP = 40.0
        const val ICE_XP = 180.0
        const val GATE_XP = 65.0
        const val ICE_DAMAGE = 2
        const val STONE_REACH = 2

        const val STEPS = 1
        const val CRUSHERS = 2
        const val STONES_DONE = 4
        val ICICLE_BITS = listOf(8, 16, 32, 64)
        const val ICE_DONE = 128
        const val SLID = 256
        const val FULL_LAP = CRUSHERS or STONES_DONE or 8 or 16 or 32 or 64 or ICE_DONE or SLID

        /* Positions from the rev 243 map (tools/agility/maplocs.py) and its tile flags. */
        /** In the water below the ice steps (2635,4054). */
        val WATER_START = CoordGrid(2634, 4054, 0)
        val WATER_FALL = CoordGrid(2629, 4056, 0)
        /** The stone tops (the jump clickzones, level 1), then the far shore. */
        val STONES =
            listOf(
                CoordGrid(2630, 4057, 1),
                CoordGrid(2631, 4057, 1),
                CoordGrid(2631, 4059, 1),
                CoordGrid(2633, 4059, 1),
                CoordGrid(2635, 4059, 1),
                CoordGrid(2635, 4061, 1),
                CoordGrid(2635, 4063, 1),
                CoordGrid(2635, 4065, 1),
            )
        val STONES_LAND = CoordGrid(2635, 4067, 1)
        /** The four icicle crossings (clickzones between the pillars). */
        val ICICLE_XS = listOf(2644, 2652, 2658, 2662)
        val ICE_TOP = CoordGrid(2665, 4078, 1)
        const val ICE_BOTTOM_Z = 4069
        /** The snow jumps (2662,4064 / 2661,4057 / 2656,4043) to the finish pen by the gate (2652,4039). */
        val SLIDE =
            listOf(
                CoordGrid(2663, 4065, 1),
                CoordGrid(2662, 4058, 1),
                CoordGrid(2657, 4044, 1),
                CoordGrid(2653, 4039, 1),
            )
    }
}
