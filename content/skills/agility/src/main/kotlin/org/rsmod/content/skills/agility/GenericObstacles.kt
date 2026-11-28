package org.rsmod.content.skills.agility

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.Constants
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isLocOpBound
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
     * order): the Goblin Cave mud pile is Dwarf Cannon's cave exit.
     */
    private val QUEST_OWNED =
        setOf(
            "mcanmudpile",
            "fairy_wall_tangle_door_open",
            "fairy_wall_tangle_door_open1",
            // Agility Pyramid low walls give xp and can fail (pyramid/Pyramid.kt).
            "agility_pyramid_low_wall",
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
        val from = player.coords
        val dest =
            if (kind == Kind.SteppingStone) {
                loc.coords
            } else {
                oppositeSide(from, loc)
            } ?: return
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
     * Wall locs (shapes 0-3): across the wall's edge (rotation 0 west, 1 north, 2 east, 3 south).
     * Other locs: past the footprint on the side opposite the player.
     */
    private fun oppositeSide(player: CoordGrid, loc: BoundLocInfo): CoordGrid? {
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
            return if (player.x == tile.x && player.z == tile.z) outside else tile
        }
        val minX = loc.coords.x
        val minZ = loc.coords.z
        val maxX = minX + loc.width - 1
        val maxZ = minZ + loc.length - 1
        val x = player.x.coerceIn(minX, maxX)
        val z = player.z.coerceIn(minZ, maxZ)
        return when {
            player.x < minX -> CoordGrid(maxX + 1, z, player.level)
            player.x > maxX -> CoordGrid(minX - 1, z, player.level)
            player.z < minZ -> CoordGrid(x, maxZ + 1, player.level)
            player.z > maxZ -> CoordGrid(x, minZ - 1, player.level)
            else -> null
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
        const val CLIENT_CYCLES_PER_TICK = 30
    }
}

internal object ObstacleSeqs : SeqReferences() {
    val climb_over = find("human_walk_style")
    val squeeze = find("railing_squeeze")
    val jump = find("human_jump_hurdle")
    val stepping_stone = find("human_steppingstonejump")
}
