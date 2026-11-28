package org.rsmod.content.quests.bearyoursoul

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The ornate Arceuus stairs (library, church and houses): each flight is a "lower" loc (3 long)
 * climbing to an "upper" loc (4 long) on the floor above, both 2 wide, rotation = climbing
 * direction (0 north, 1 east, 2 south, 3 west). Pairs in the map (e.g. the church's lower at
 * 1692,3793,0 rot 3 and upper at 1689,3793,1) put the upper 2 tiles ahead (rot 0/1) or 3 tiles
 * ahead (rot 2/3) of the lower's origin, so:
 * - climbing a lower lands one tile past the top of its upper, a floor up;
 * - climbing an upper lands one tile before the foot of its lower, a floor down.
 */
class ArceuusStairs @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for (stairs in ArceuusStairLocs.lower) onLocOpLabel(locTypes, stairs, "Climb") { climb(it, up = true) }
        for (stairs in ArceuusStairLocs.upper) onLocOpLabel(locTypes, stairs, "Climb") { climb(it, up = false) }
    }

    private suspend fun ProtectedAccess.climb(loc: BoundLocInfo, up: Boolean) {
        arriveDelay()
        val at = loc.coords
        val dest: CoordGrid =
            when (loc.angleId and 3) {
                0 -> if (up) at.translate(0, 6, 1) else at.translate(0, -3, -1)
                1 -> if (up) at.translate(6, 0, 1) else at.translate(-3, 0, -1)
                2 -> if (up) at.translate(0, -4, 1) else at.translate(0, 6, -1)
                else -> if (up) at.translate(-4, 0, 1) else at.translate(6, 0, -1)
            }
        if (dest.level !in 0..3) return
        telejump(dest)
        delay(1)
    }
}

internal object ArceuusStairLocs : LocReferences() {
    val lower =
        listOf(
            find("archeuus_stairs_lower"),
            find("archeuus_stairs_lower_right"),
            find("archeuus_stairs_lower_left"),
        )
    val upper =
        listOf(
            find("archeuus_stairs_upper"),
            find("archeuus_stairs_upper_right"),
            find("archeuus_stairs_upper_left"),
        )
}
