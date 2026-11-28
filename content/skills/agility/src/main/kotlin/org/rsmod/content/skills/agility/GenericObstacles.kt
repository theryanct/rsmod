package org.rsmod.content.skills.agility

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.Constants
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onApLoc1
import org.rsmod.api.script.onApLoc2
import org.rsmod.api.script.onApLoc3
import org.rsmod.api.script.onApLoc4
import org.rsmod.api.script.onApLoc5
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.seq.SeqType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Level-free obstacles (stiles, low walls and fences, railings, stepping stones), bound by op label
 * on every loc type not already handled (Agility course / shortcut locs and any op another script
 * owns are skipped):
 * - "Climb-over" (stile anim), "Squeeze-through" (railing squeeze), "Jump-over" / "Step-over"
 *   (hurdle jump): the player is moved to the opposite side of the loc - across the wall edge
 *   for wall locs (rotation picks the edge), or past the loc's footprint along the approach axis.
 * - "Cross" on stepping stones (by loc name): a jump onto the stone.
 * No xp; level-gated shortcuts stay data rows in agility.tsv.
 */
class GenericObstacles @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    private val logger = InlineLogger()

    /**
     * Locs whose "Climb-over" a quest owns (the eventBus.contains guard below depends on load
     * order): the Goblin Cave mud pile is Dwarf Cannon's cave exit; the Hallowed Sepulchre's
     * floor-4 end gate is the sepulchre's (it pauses the floor timer).
     */
    private val QUEST_OWNED =
        setOf(
            "mcanmudpile",
            "fairy_wall_tangle_door_open",
            "fairy_wall_tangle_door_open1",
            "hallowed_path_end_gate",
            // Agility Pyramid low walls give xp and can fail (pyramid/Pyramid.kt).
            "agility_pyramid_low_wall",
            // Brimhaven Agility Arena low walls give xp (brimhaven/BrimhavenArena.kt).
            "agilityarena_lowwall",
            // The Mountain Camp rockslide is Mountain Daughter's (guard lines, quest gate).
            "mdaughter_rockslide",
        )

    private enum class Kind(val labels: Set<String>) {
        ClimbOver(setOf("climb-over")),
        Squeeze(setOf("squeeze-through")),
        Jump(setOf("jump-over", "step-over")),
        SteppingStone(setOf("cross")),
    }

    override fun ScriptContext.startup() {
        val agilityLocs =
            (AgilityData.obstacles.flatMap { it.locs } + AgilityData.shortcuts.flatMap { it.locs })
                .asSequence()
                .map { AgilityLocs.byName.getValue(it).id }
                .toSet()
        val counts = HashMap<Kind, Int>()
        for (type in locTypes.values) {
            if (type.id in agilityLocs || type.internalName in QUEST_OWNED) continue
            for ((index, text) in type.op.withIndex()) {
                val label = text?.trim()?.lowercase() ?: continue
                val kind = Kind.entries.firstOrNull { label in it.labels } ?: continue
                if (kind == Kind.SteppingStone && !type.isSteppingStone()) continue
                if (bind(type, index + 1, kind)) {
                    counts[kind] = (counts[kind] ?: 0) + 1
                }
            }
        }
        logger.info {
            "GenericObstacles: " + Kind.entries.joinToString { "${it.name} ${counts[it] ?: 0}" } +
                " loc types."
        }
    }

    private fun UnpackedLocType.isSteppingStone(): Boolean =
        name.contains("stepping stone", ignoreCase = true) ||
            name.equals("stones", ignoreCase = true)

    private fun ScriptContext.bind(type: UnpackedLocType, op: Int, kind: Kind): Boolean {
        val event =
            when (op) {
                1 -> LocEvents.Op1::class.java
                2 -> LocEvents.Op2::class.java
                3 -> LocEvents.Op3::class.java
                4 -> LocEvents.Op4::class.java
                else -> LocEvents.Op5::class.java
            }
        if (eventBus.contains(event, type.id)) return false
        if (kind == Kind.SteppingStone) {
            // Stones sit in water with no adjacent walkable tile, so act from the bank (ap-op with
            // reach) instead of requiring an adjacent tile like a normal op.
            val apEvent =
                when (op) {
                    1 -> LocEvents.Ap1::class.java
                    2 -> LocEvents.Ap2::class.java
                    3 -> LocEvents.Ap3::class.java
                    4 -> LocEvents.Ap4::class.java
                    else -> LocEvents.Ap5::class.java
                }
            if (eventBus.contains(apEvent, type.id)) return false
            when (op) {
                1 -> onApLoc1(type) { if (isWithinApRange(it.loc, STONE_REACH)) cross(it.loc, kind) }
                2 -> onApLoc2(type) { if (isWithinApRange(it.loc, STONE_REACH)) cross(it.loc, kind) }
                3 -> onApLoc3(type) { if (isWithinApRange(it.loc, STONE_REACH)) cross(it.loc, kind) }
                4 -> onApLoc4(type) { if (isWithinApRange(it.loc, STONE_REACH)) cross(it.loc, kind) }
                5 -> onApLoc5(type) { if (isWithinApRange(it.loc, STONE_REACH)) cross(it.loc, kind) }
            }
            return true
        }
        when (op) {
            1 -> if (!isLocOpBound(type, 1)) onOpLoc1(type) { cross(it.loc, kind) }
            2 -> if (!isLocOpBound(type, 2)) onOpLoc2(type) { cross(it.loc, kind) }
            3 -> if (!isLocOpBound(type, 3)) onOpLoc3(type) { cross(it.loc, kind) }
            4 -> if (!isLocOpBound(type, 4)) onOpLoc4(type) { cross(it.loc, kind) }
            5 -> if (!isLocOpBound(type, 5)) onOpLoc5(type) { cross(it.loc, kind) }
        }
        return true
    }

    private suspend fun ProtectedAccess.cross(loc: BoundLocInfo, kind: Kind) {
        if (kind == Kind.SteppingStone) {
            val from = player.coords
            if (loc.coords != from) moveWith(ObstacleSeqs.stepping_stone, from, loc.coords)
            return
        }
        val (start, dest) = crossing(player.coords, loc) ?: return
        // Step onto this side's start tile first, so the climb always runs straight across.
        if (player.coords != start) {
            exactMove(player.coords, start, 0, CLIENT_CYCLES_PER_TICK, direction(start, dest))
            delay(1)
        }
        val from = start
        if (dest == from) return
        val anim =
            when (kind) {
                Kind.ClimbOver -> ObstacleSeqs.climb_over
                Kind.Squeeze -> ObstacleSeqs.squeeze
                Kind.Jump -> ObstacleSeqs.jump
                Kind.SteppingStone -> ObstacleSeqs.stepping_stone
            }
        moveWith(anim, from, dest)
    }

    private suspend fun ProtectedAccess.moveWith(anim: SeqType, from: CoordGrid, dest: CoordGrid) {
        anim(anim)
        exactMove(from, dest, 0, MOVE_TICKS * CLIENT_CYCLES_PER_TICK, direction(from, dest))
        delay(MOVE_TICKS)
    }

    /**
     * (start, landing) for a two-sided obstacle. Both ends are fixed by the loc, not by where the
     * player clicked from, so crossing works the same way from either side:
     * - Wall locs (shapes 0-3): across the wall's edge (rotation 0 west, 1 north, 2 east, 3 south);
     *   the two tiles are the loc tile and the tile beyond that edge.
     * - Other locs (stiles are 1x2): across the SHORT side of the rotated footprint, from the tile
     *   just outside it on the player's side to the tile just outside the far side, in line with
     *   the player (clamped onto the footprint). Square footprints fall back to the approach axis.
     */
    private fun crossing(player: CoordGrid, loc: BoundLocInfo): Pair<CoordGrid, CoordGrid>? {
        val shape = loc.shapeId
        if (shape in 0..3) {
            val (dx, dz) =
                when (loc.angleId) {
                    0 -> -1 to 0
                    1 -> 0 to 1
                    2 -> 1 to 0
                    else -> 0 to -1
                }
            val tile = loc.coords
            val outside = tile.translate(dx, dz)
            // On the loc tile -> go out; anywhere else (outside the edge or beside it) -> come in.
            return if (player.x == tile.x && player.z == tile.z) tile to outside else outside to tile
        }
        val minX = loc.coords.x
        val minZ = loc.coords.z
        val maxX = minX + loc.adjustedWidth - 1
        val maxZ = minZ + loc.adjustedLength - 1
        val level = loc.coords.level
        val acrossX =
            when {
                loc.adjustedWidth < loc.adjustedLength -> true
                loc.adjustedWidth > loc.adjustedLength -> false
                // Square: cross along whichever axis the player is outside of.
                else -> player.x < minX || player.x > maxX
            }
        return if (acrossX) {
            val z = player.z.coerceIn(minZ, maxZ)
            val west = CoordGrid(minX - 1, z, level)
            val east = CoordGrid(maxX + 1, z, level)
            val onWest = player.x < (minX + maxX + 1) / 2.0
            if (onWest) west to east else east to west
        } else {
            val x = player.x.coerceIn(minX, maxX)
            val south = CoordGrid(x, minZ - 1, level)
            val north = CoordGrid(x, maxZ + 1, level)
            val onSouth = player.z < (minZ + maxZ + 1) / 2.0
            if (onSouth) south to north else north to south
        }
    }

    private fun direction(from: CoordGrid, to: CoordGrid): Int {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (kotlin.math.abs(dz) >= kotlin.math.abs(dx)) {
            if (dz > 0) Constants.em_face_north else Constants.em_face_south
        } else {
            if (dx > 0) Constants.em_face_east else Constants.em_face_west
        }
    }

    private companion object {
        const val MOVE_TICKS = 2
        /** Tiles from which a stepping stone can be jumped onto (the bank or the previous stone). */
        const val STONE_REACH = 2
        const val CLIENT_CYCLES_PER_TICK = 30
    }
}

internal object ObstacleSeqs : SeqReferences() {
    val climb_over = find("human_walk_style")
    val squeeze = find("railing_squeeze")
    val jump = find("human_jump_hurdle")
    val stepping_stone = find("human_steppingstonejump")
}
