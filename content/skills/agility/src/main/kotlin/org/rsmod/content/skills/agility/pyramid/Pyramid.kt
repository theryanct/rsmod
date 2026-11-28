package org.rsmod.content.skills.agility.pyramid

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.config.Constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.player.stat.baseAgilityLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.agility.AgilityBas
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.seq.SeqType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Agility Pyramid (Wiki "Agility Pyramid", 30 Agility; data: `pyramid.tsv`).
 * - Obstacles are keyed by loc + tile because the pyramid places each loc type many times. Each
 *   crossing moves the player to the far end; failing (authored rates in [PyramidTuning]) costs
 *   hitpoints and, except on low walls, drops the player a level (two tiles outwards).
 * - The climbing rocks at the top give the pyramid top and the completion bonus (300 + 8 per
 *   level, max 1,000) once per lap; the doorway returns to the base and resets the top.
 * - Simon Templeton buys pyramid tops for 10,000 coins each.
 * Not yet: the rolling stone blocks and moving pyramid blocks (walk-on hazards), desert heat.
 */
class Pyramid
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val obstacles by lazy {
        PyramidData.obstacles.associateBy { PyramidLocs.byName.getValue(it.loc).id to it.tile }
    }
    private val stairs by lazy {
        PyramidData.stairs.associateBy { PyramidLocs.byName.getValue(it.loc).id to it.tile }
    }

    override fun ScriptContext.startup() {
        val bound = HashSet<Int>()
        for (name in PyramidLocs.byName.keys) {
            val type = PyramidLocs.byName.getValue(name)
            if (bound.add(type.id)) bindLoc(type) { placed(it) }
        }
        bindLoc(PyramidLocs.wall_rocks) { climbRocks() }
        bindLoc(PyramidLocs.door) { leave() }
        bindLoc(PyramidLocs.door_mirror) { leave() }
        if (!isNpcOpBound(PyramidNpcs.simon, 1)) onOpNpc1(PyramidNpcs.simon) { simon(it.npc) }
    }

    /** Binds the loc's first non-Examine op, unless another script owns it. */
    private fun ScriptContext.bindLoc(type: LocType, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[type].op.indexOfFirst { it != null && !it.equals("Examine", ignoreCase = true) } + 1
        if (op <= 0 || isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
        }
    }

    private suspend fun ProtectedAccess.placed(loc: BoundLocInfo) {
        val key = loc.id to loc.coords
        val step = stairs[key]
        if (step != null) {
            if (player.agilityLvl < PyramidTuning.LEVEL) {
                mes("You need an Agility level of ${PyramidTuning.LEVEL} to attempt the Agility Pyramid.")
                return
            }
            delay(1)
            telejump(step.dest)
            return
        }
        val obstacle = obstacles[key]
        if (obstacle == null) {
            mes("You can't do that from here.")
            return
        }
        cross(obstacle)
    }

    private suspend fun ProtectedAccess.cross(obstacle: PyramidObstacle) {
        if (player.agilityLvl < PyramidTuning.LEVEL) {
            mes("You need an Agility level of ${PyramidTuning.LEVEL} to attempt this.")
            return
        }
        val a = resolve(obstacle.a)
        val b = resolve(obstacle.b)
        val here = player.coords
        val nearA = distance(here, a) <= distance(here, b)
        val near = if (nearA) a else b
        val end = if (nearA) b else a
        if (distance(here, near) > NEAR_DISTANCE) {
            mes("You can't do that from here.")
            return
        }
        if (fails(obstacle.failClass)) {
            fail(obstacle)
            return
        }
        move(PyramidSeqs.byName.getValue(obstacle.anim), obstacle.anim, obstacle.ticks, end)
        statAdvance(stats.agility, obstacle.xp * xpMods.get(player, stats.agility))
    }

    private fun ProtectedAccess.fails(failClass: FailClass): Boolean {
        val (noFail, max) =
            when (failClass) {
                FailClass.Cross -> PyramidTuning.CROSS_NO_FAIL to PyramidTuning.CROSS_MAX
                FailClass.Jump -> PyramidTuning.JUMP_NO_FAIL to PyramidTuning.JUMP_MAX
            }
        val level = player.agilityLvl
        if (level >= noFail) return false
        val span = (noFail - PyramidTuning.LEVEL).coerceAtLeast(1)
        val chance = (max * (noFail - level) / span).coerceIn(0.0, max)
        return random.of(FAIL_ROLL) < (chance * FAIL_ROLL).toInt()
    }

    private suspend fun ProtectedAccess.fail(obstacle: PyramidObstacle) {
        if (!obstacle.falls) {
            anim(PyramidSeqs.stumble)
            delay(1)
            queueHit(1, HitType.Typeless, obstacle.damage)
            mes("You slip and hurt yourself on the wall.")
            return
        }
        val seq = if (obstacle.failClass == FailClass.Jump) PyramidSeqs.jump_fall else PyramidSeqs.ledge_fall
        anim(seq)
        delay(2)
        telejump(fallTile(player.coords))
        queueHit(1, HitType.Typeless, obstacle.damage)
        mes("You lose your footing and fall to the level below.")
    }

    /** Two tiles outwards and one level down, snapped to the nearest walkable tile. */
    private fun fallTile(from: CoordGrid): CoordGrid {
        val upper = from.x < PyramidTuning.UPPER_MAX_X
        if (upper && from.level <= 2) return PyramidTuning.LEVEL4_FALL
        val centre = if (upper) PyramidTuning.UPPER_CENTRE else PyramidTuning.MAIN_CENTRE
        val dx = from.x - centre.x
        val dz = from.z - centre.z
        val out = PyramidTuning.FALL_OUT
        val pin =
            if (abs(dx) >= abs(dz)) {
                CoordGrid(from.x + out * Integer.signum(dx), from.z, (from.level - 1).coerceAtLeast(0))
            } else {
                CoordGrid(from.x, from.z + out * Integer.signum(dz), (from.level - 1).coerceAtLeast(0))
            }
        return land(pin)
    }

    private fun land(pin: CoordGrid): CoordGrid {
        for (r in 0..PyramidTuning.LAND_RADIUS) {
            for (dz in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val tile = pin.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) == 0) return tile
                }
            }
        }
        return pin
    }

    /* Top */

    private suspend fun ProtectedAccess.climbRocks() {
        if (player.vars[PyramidVarBits.top] == PyramidTuning.TOP_TAKEN) {
            mes("You have already taken the pyramid top. Use the doorway to start another climb.")
            return
        }
        anim(PyramidSeqs.top_climb)
        delay(2)
        VarPlayerIntMapSetter.set(player, PyramidVarBits.top, PyramidTuning.TOP_TAKEN)
        invAddOrDrop(objRepo, PyramidObjs.top)
        val bonus =
            (PyramidTuning.BONUS_BASE + PyramidTuning.BONUS_PER_LEVEL * player.baseAgilityLvl)
                .coerceAtMost(PyramidTuning.BONUS_CAP)
        statAdvance(stats.agility, bonus * xpMods.get(player, stats.agility))
        mes("You find a golden pyramid!")
    }

    private suspend fun ProtectedAccess.leave() {
        delay(1)
        VarPlayerIntMapSetter.set(player, PyramidVarBits.top, 0)
        telejump(PyramidTuning.BOTTOM)
    }

    /* Simon Templeton */

    private suspend fun ProtectedAccess.simon(npc: Npc) {
        val tops = invTotal(inv, PyramidObjs.top)
        var sell = false
        // Authored (not Wiki transcript): short lines in Simon's voice.
        startDialogue(npc) {
            if (tops == 0) {
                chatNpc(neutral, "Bring me a golden pyramid top from the Agility Pyramid and I'll pay you ${PyramidTuning.TOP_PRICE} coins for it.")
                return@startDialogue
            }
            chatNpc(happy, "Is that a pyramid top? I'll give you ${PyramidTuning.TOP_PRICE} coins for each one.")
            sell = choice2("Sell ${if (tops == 1) "it" else "all $tops"}.", true, "No thanks.", false)
        }
        if (!sell) return
        val coins = tops.toLong() * PyramidTuning.TOP_PRICE
        if (coins > Int.MAX_VALUE) return
        invDel(inv, PyramidObjs.top, tops)
        invAdd(inv, PyramidObjs.coins, coins.toInt())
        mes("You sell $tops pyramid top${if (tops == 1) "" else "s"} to Simon for $coins coins.")
    }

    /* Movement */

    private suspend fun ProtectedAccess.move(seq: SeqType, animName: String, ticks: Int, end: CoordGrid) {
        anim(seq)
        val from = player.coords
        val crossingBas = AgilityBas.byAnim[animName]
        val previousBas = player.bas
        if (crossingBas != null) player.bas = crossingBas
        try {
            exactMove(from, end, 0, ticks * CLIENT_CYCLES_PER_TICK, direction(from, end))
            delay(ticks)
        } finally {
            if (crossingBas != null) player.bas = previousBas
        }
    }

    private fun ProtectedAccess.resolve(target: PyramidTarget): CoordGrid =
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
        const val NEAR_DISTANCE = 3
        const val FAIL_ROLL = 10_000
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
    }
}
