package org.rsmod.content.generic.locs.ladders

import jakarta.inject.Inject
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Generic fallback for ladders, staircases, trapdoors and the like (Ryan: the Wizards' Tower
 * ladder down to Sedridor's basement did nothing; only a few ladder/stair types have a content
 * group).
 *
 * Any loc named like a ladder/stair/steps/trapdoor/hatch/manhole whose clicked op reads
 * "Climb-up"/"Climb-down" (or "Climb up", "Walk-up", "Go-down", ...) and that no script handles
 * (no op or ap trigger: bound ladders and quest ladders keep priority) moves the player:
 * - up: level + 1, or from an underground level-0 area (y >= 6400) to the surface (y - 6400);
 * - down: level - 1, or from the surface's level 0 underground (y + 6400, the OSRS convention).
 * A bare "Climb" asks up or down. On arrival it looks for the paired loc (one that climbs back the
 * other way) within [PAIR_RADIUS] tiles of that spot and lands on a free tile beside it; failing
 * that, the spot itself or the nearest free tile. If nothing is walkable there it says so and
 * doesn't move the player.
 *
 * Runs as an [OpInterceptors.LocOp] whose claim checks that nothing else would handle the op, like
 * the door fallback.
 */
class LadderFallback
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val locInteractions: LocInteractions,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    override fun ScriptContext.startup() {
        OpInterceptors.register(Interceptor())
    }

    private inner class Interceptor : OpInterceptors.LocOp {
        override fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean {
            val type = locTypes[loc]
            if (!type.isClimbable()) return false
            val label = type.op.getOrNull(op.slot - 1) ?: return false
            if (direction(label) == null) return false
            return locInteractions.opTrigger(player, loc, op, type) == null &&
                locInteractions.apTrigger(player, loc, op, type) == null
        }

        override val auditName: String = "ladder fallback"

        override fun auditCovers(type: UnpackedLocType, shape: Int, coords: CoordGrid, op: Int): Boolean {
            val label = type.op.getOrNull(op - 1) ?: return false
            return type.isClimbable() && direction(label) != null
        }

        override suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp) {
            val type = locTypes[loc]
            val label = type.op.getOrNull(op.slot - 1) ?: return
            val dir = direction(label) ?: return
            if (dir != ASK) {
                climb(dir)
                return
            }
            startDialogue {
                val picked = choice2("Climb-up", UP, "Climb-down", DOWN, title = "Climb up or down?")
                climb(picked)
            }
        }
    }

    private suspend fun ProtectedAccess.climb(dir: Int) {
        val here = player.coords
        val target =
            when {
                dir == UP && here.level == 0 && here.z >= UNDERGROUND_OFFSET -> here.translateZ(-UNDERGROUND_OFFSET)
                dir == UP -> if (here.level >= MAX_LEVEL) null else here.translateLevel(1)
                here.level > 0 -> here.translateLevel(-1)
                else -> here.translateZ(UNDERGROUND_OFFSET)
            }
        val dest = target?.let { landing(it, dir) }
        if (dest == null) {
            mes("This doesn't seem to lead anywhere.")
            return
        }
        anim(if (dir == UP) seqs.human_reachforladder else seqs.human_pickupfloor)
        delay(1)
        telejump(dest)
    }

    /** Beside the paired loc near [target] if there is one, else [target] or the nearest free tile. */
    private fun landing(target: CoordGrid, dir: Int): CoordGrid? {
        val back = if (dir == UP) DOWN else UP
        val pair =
            nearbyLocs(target)
                .filter { info ->
                    val type = locTypes.types[info.id] ?: return@filter false
                    type.isClimbable() &&
                        type.op.any { label -> label != null && direction(label).let { it == back || it == ASK } }
                }
                .minByOrNull { it.coords.chebyshevDistance(target) }
        if (pair != null) {
            val type = locTypes.types[pair.id]
            val beside = type?.let { besideLoc(pair, it, target) }
            if (beside != null) return beside
        }
        return nearestFree(target)
    }

    private fun nearbyLocs(target: CoordGrid): List<LocInfo> {
        val centre = ZoneKey.from(target)
        val zones = (PAIR_RADIUS + 7) / 8
        val out = ArrayList<LocInfo>()
        for (dx in -zones..zones) {
            for (dz in -zones..zones) {
                for (info in locRepo.findAll(centre.translate(dx, dz))) {
                    if (info.coords.level != target.level) continue
                    if (info.coords.chebyshevDistance(target) > PAIR_RADIUS) continue
                    out += info
                }
            }
        }
        return out
    }

    /** A free tile touching [loc]'s footprint, closest to [target]. */
    private fun besideLoc(loc: LocInfo, type: UnpackedLocType, target: CoordGrid): CoordGrid? {
        val turned = loc.angleId and 1 == 1
        val width = if (turned) type.length else type.width
        val length = if (turned) type.width else type.length
        val base = loc.coords
        val tiles = ArrayList<CoordGrid>()
        for (x in -1..width) {
            for (z in -1..length) {
                val inside = x in 0 until width && z in 0 until length
                if (!inside) tiles += base.translate(x, z)
            }
        }
        return tiles.filter { it.isFree() }.minByOrNull { it.chebyshevDistance(target) }
    }

    private fun nearestFree(target: CoordGrid): CoordGrid? {
        for (radius in 0..FREE_RADIUS) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                    val tile = target.translate(dx, dz)
                    if (tile.isFree()) return tile
                }
            }
        }
        return null
    }

    private fun CoordGrid.isFree(): Boolean {
        // Unallocated zones are outside any loaded map square.
        if (!collision.isZoneAllocated(x, z, level)) return false
        return collision[x, z, level] and BLOCKED == 0
    }

    private fun UnpackedLocType.isClimbable(): Boolean {
        val n = name.lowercase()
        return CLIMBABLE_WORDS.any { it in n }
    }

    /** [UP], [DOWN], [ASK] for a bare "Climb", or null for any other op. */
    private fun direction(label: String): Int? {
        val l = label.trim().lowercase().replace(' ', '-')
        return when (l) {
            "climb-up", "walk-up", "go-up" -> UP
            "climb-down", "walk-down", "go-down" -> DOWN
            "climb" -> ASK
            else -> null
        }
    }

    private companion object {
        const val UP = 1
        const val DOWN = -1
        const val ASK = 0
        const val MAX_LEVEL = 3
        const val UNDERGROUND_OFFSET = 6400
        const val PAIR_RADIUS = 20
        const val FREE_RADIUS = 3
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
        val CLIMBABLE_WORDS = listOf("ladder", "stair", "steps", "trapdoor", "hatch", "manhole", "rope", "vine")
    }
}
