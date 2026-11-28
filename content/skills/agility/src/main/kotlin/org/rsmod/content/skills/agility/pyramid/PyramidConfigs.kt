package org.rsmod.content.skills.agility.pyramid

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.seq.SeqType
import org.rsmod.map.CoordGrid

/** A coordinate where `null` x/z means "keep the player's current value" (as in agility.tsv). */
internal class PyramidTarget(val x: Int?, val z: Int?, val level: Int)

internal enum class FailClass {
    /** Ledges, planks, hanging gaps and low walls (Wiki: none fail from 69). */
    Cross,
    /** Jump gaps (Wiki: the only failable obstacle at 69, none fail from 75). */
    Jump,
}

internal class PyramidObstacle(
    val loc: String,
    val tile: CoordGrid,
    val xp: Double,
    val anim: String,
    val ticks: Int,
    val a: PyramidTarget,
    val b: PyramidTarget,
    val failClass: FailClass,
    /** True: a failure drops the player a level; false: damage only (low walls). */
    val falls: Boolean,
    val damage: Int,
)

internal class PyramidStairs(val loc: String, val tile: CoordGrid, val dest: CoordGrid)

/** Rows of `pyramid.tsv` (see its header). */
internal object PyramidData {
    private class Parsed(val obstacles: List<PyramidObstacle>, val stairs: List<PyramidStairs>)

    private val parsed: Parsed by lazy { load() }

    val obstacles: List<PyramidObstacle>
        get() = parsed.obstacles

    val stairs: List<PyramidStairs>
        get() = parsed.stairs

    private fun coords(text: String): CoordGrid {
        val p = text.split(',')
        return CoordGrid(p[0].toInt(), p[1].toInt(), p[2].toInt())
    }

    private fun target(text: String): PyramidTarget {
        val p = text.split(',')
        return PyramidTarget(p[0].toIntOrNull(), p[1].toIntOrNull(), p[2].toInt())
    }

    private fun load(): Parsed {
        val input =
            PyramidData::class.java.getResourceAsStream("pyramid.tsv")
                ?: error("Agility resource not found: pyramid.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val obstacles = ArrayList<PyramidObstacle>()
        val stairs = ArrayList<PyramidStairs>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            when (p[0]) {
                "obstacle" ->
                    obstacles.add(
                        PyramidObstacle(
                            loc = p[1],
                            tile = coords(p[2]),
                            xp = p[3].toDouble(),
                            anim = p[4],
                            ticks = p[5].toInt(),
                            a = target(p[6]),
                            b = target(p[7]),
                            failClass = if (p[8] == "jump") FailClass.Jump else FailClass.Cross,
                            falls = p[9] == "fall",
                            damage = p[10].toInt(),
                        )
                    )
                "stairs" -> stairs.add(PyramidStairs(p[1], coords(p[2]), coords(p[3])))
                else -> error("Unknown pyramid line: $raw")
            }
        }
        return Parsed(obstacles, stairs)
    }
}

/**
 * Authored fail rules (the Wiki gives only rough rates: "at 50 expect 6-12 fails a climb, at 69
 * only the jump gaps fail, from 75 nothing fails"). The chance falls linearly from [CROSS_MAX] /
 * [JUMP_MAX] at level 30 to zero at the no-fail level.
 */
internal object PyramidTuning {
    const val LEVEL = 30
    const val CROSS_NO_FAIL = 69
    const val JUMP_NO_FAIL = 75
    const val CROSS_MAX = 0.25
    const val JUMP_MAX = 0.35

    /** Completion bonus: 300 + 8 per Agility level, capped at 1,000 (Wiki, Feb 2016). */
    const val BONUS_BASE = 300
    const val BONUS_PER_LEVEL = 8
    const val BONUS_CAP = 1000

    /** Simon Templeton pays 10,000 coins per pyramid top (Wiki, up from 1,000 in Feb 2016). */
    const val TOP_PRICE = 10_000

    /** `agility_pyramid_top` varbit value once this lap's top is taken (multiloc 1 = bare top). */
    const val TOP_TAKEN = 1

    /** Main pyramid middle (level-1 ring spans x 3354-3376, z 2831-2852); falls go outwards. */
    val MAIN_CENTRE = CoordGrid(3365, 2841, 0)
    /** Upper area (levels 4 and top) middle. */
    val UPPER_CENTRE = CoordGrid(3045, 4697, 0)
    /** Upper area is everything west of this x (the main pyramid is at x >= 3350). */
    const val UPPER_MAX_X = 3200
    /** A fall from level 4 lands back on level 3, by its up-stairs (guess). */
    val LEVEL4_FALL = CoordGrid(3360, 2836, 3)
    /** Fall distance outwards: each level is inset two tiles from the one below. */
    const val FALL_OUT = 2
    const val LAND_RADIUS = 3

    /** The bottom doorway (3364-3365, 2830), on its south side. */
    val BOTTOM = CoordGrid(3364, 2829, 0)
}

internal object PyramidLocs : LocReferences() {
    val byName: Map<String, LocType> =
        (PyramidData.obstacles.map { it.loc } + PyramidData.stairs.map { it.loc })
            .distinct()
            .associateWith { find(it) }
    val wall_rocks = find("agility_pyramid_wall_rocks")
    val door = find("agility_pyramid_door_hotspot")
    val door_mirror = find("agility_pyramid_door_hotspot_mirror")
}

internal object PyramidSeqs : SeqReferences() {
    val byName: Map<String, SeqType> =
        PyramidData.obstacles.map { it.anim }.distinct().associateWith { find(it) }
    val top_climb = find("agility_pyramid_top_climb")
    val jump_fall = find("agility_pyramid_gap_jump_fall")
    val ledge_fall = find("agility_pyramid_ledge_fall_left")
    val stumble = find("human_walk_logbalance_stumble")
}

internal object PyramidObjs : ObjReferences() {
    val top = find("agility_pyramid_gold_pyramid")
    val coins = find("coins")
}

internal object PyramidNpcs : NpcReferences() {
    val simon = find("agility_pyramid_simon")
}

internal object PyramidVarBits : VarBitReferences() {
    /** Drives `agility_pyramid_top_multiloc` (the capstone at 3044, 4697, 3). */
    val top = find("agility_pyramid_top")
}
