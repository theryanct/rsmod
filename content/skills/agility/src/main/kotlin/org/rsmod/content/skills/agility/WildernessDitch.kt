package org.rsmod.content.skills.agility

import jakarta.inject.Inject
import org.rsmod.api.config.Constants
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Wilderness Ditch "Cross" (23271, and the members-world copy 50652): the OSRS ditch jump
 * (`wild_ditch_jump`, a three-tile forced move over the two-tile-thick ditch).
 *
 * The jump runs perpendicular to the ditch's long axis, read from the neighbouring ditch locs
 * (east-west neighbours: the ditch runs along x, so jump along z); a lone loc falls back to its
 * long side being the ditch's thickness, as on the real ditch.
 *
 * The first jump INTO the Wilderness (northwards) opens the warning screen
 * (`wilderness_warningscreen`); "Yes" jumps and remembers it (server varp
 * `wilderness_ditch_warned`), "No" closes it. Later jumps skip the warning.
 */
class WildernessDitch @Inject constructor(private val locRepo: LocRepository) : PluginScript() {
    /** The ditch loc each player clicked before the warning screen opened. */
    private val pending = HashMap<Int, BoundLocInfo>()

    override fun ScriptContext.startup() {
        onOpLoc1(DitchLocs.ditch) { cross(it.loc) }
        onOpLoc1(DitchLocs.ditch_members) { cross(it.loc) }
        onIfModalButton(DitchComponents.yes) { confirm() }
        onIfModalButton(DitchComponents.no) {
            pending.remove(player.uid.packed)
            ifClose()
        }
    }

    private suspend fun ProtectedAccess.cross(loc: BoundLocInfo) {
        val (start, dest) = crossing(player.coords, loc)
        val entering = dest.z > start.z
        if (entering && vars[DitchVarps.warned] == 0) {
            pending[player.uid.packed] = loc
            ifOpenMainModal(DitchInterfaces.warning)
            ifSetEvents(DitchComponents.yes, 0..0, IfEvent.Op1)
            ifSetEvents(DitchComponents.no, 0..0, IfEvent.Op1)
            return
        }
        jump(start, dest)
    }

    private suspend fun ProtectedAccess.confirm() {
        val loc = pending.remove(player.uid.packed)
        ifClose()
        if (loc == null) return
        VarPlayerIntMapSetter.set(player, DitchVarps.warned, 1)
        val (start, dest) = crossing(player.coords, loc)
        jump(start, dest)
    }

    private suspend fun ProtectedAccess.jump(start: CoordGrid, dest: CoordGrid) {
        // Step onto this side's take-off tile first (the player may stand beside the loc's end).
        if (player.coords != start) {
            exactMove(player.coords, start, 0, CLIENT_CYCLES_PER_TICK, direction(start, dest))
            delay(1)
        }
        anim(DitchSeqs.jump)
        exactMove(start, dest, JUMP_START_CYCLES, JUMP_END_CYCLES, direction(start, dest))
        delay(JUMP_TICKS)
    }

    /**
     * (take-off, landing): from the tile just outside the player's side to just past the far side.
     */
    private fun crossing(player: CoordGrid, loc: BoundLocInfo): Pair<CoordGrid, CoordGrid> {
        val minX = loc.coords.x
        val minZ = loc.coords.z
        val maxX = minX + loc.adjustedWidth - 1
        val maxZ = minZ + loc.adjustedLength - 1
        val level = loc.coords.level
        val acrossZ = runsAlongX(loc, maxX, maxZ) ?: (loc.adjustedLength >= loc.adjustedWidth)
        return if (acrossZ) {
            val x = player.x.coerceIn(minX, maxX)
            val south = CoordGrid(x, minZ - 1, level)
            val north = CoordGrid(x, maxZ + 1, level)
            if (player.z < (minZ + maxZ + 1) / 2.0) south to north else north to south
        } else {
            val z = player.z.coerceIn(minZ, maxZ)
            val west = CoordGrid(minX - 1, z, level)
            val east = CoordGrid(maxX + 1, z, level)
            if (player.x < (minX + maxX + 1) / 2.0) west to east else east to west
        }
    }

    /** True if more ditch sits east/west of [loc], false if north/south, null if neither. */
    private fun runsAlongX(loc: BoundLocInfo, maxX: Int, maxZ: Int): Boolean? {
        fun isDitch(x: Int, z: Int): Boolean =
            locRepo.findAll(CoordGrid(x, z, loc.coords.level)).any { it.id in DITCH_IDS }
        val minX = loc.coords.x
        val minZ = loc.coords.z
        val alongX = isDitch(maxX + 1, minZ) || isDitch(minX - 1, minZ)
        val alongZ = isDitch(minX, maxZ + 1) || isDitch(minX, minZ - 1)
        return when {
            alongX -> true
            alongZ -> false
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
        val DITCH_IDS: Set<Int> by lazy { setOf(DitchLocs.ditch.id, DitchLocs.ditch_members.id) }
        const val CLIENT_CYCLES_PER_TICK = 30
        /** OSRS ditch forced move: leaves the take-off tile at 33 cycles, lands at 60. */
        const val JUMP_START_CYCLES = 33
        const val JUMP_END_CYCLES = 60
        const val JUMP_TICKS = 2
    }
}

internal object DitchLocs : LocReferences() {
    val ditch = find("ditch_wilderness_cover")
    val ditch_members = find("ditch_wilderness_cover_members")
}

internal object DitchSeqs : SeqReferences() {
    val jump = find("wild_ditch_jump")
}

internal object DitchInterfaces : InterfaceReferences() {
    val warning = find("wilderness_warningscreen")
}

internal object DitchComponents : ComponentReferences() {
    val yes = find("wilderness_warningscreen:yes")
    val no = find("wilderness_warningscreen:no")
}

/** Saved per character (varp 65690): 1 once the ditch warning has been accepted. */
internal object DitchVarpBuilds : VarpBuilder() {
    init {
        build("wilderness_ditch_warned")
    }
}

internal object DitchVarps : VarpReferences() {
    val warned = find("wilderness_ditch_warned")
}
