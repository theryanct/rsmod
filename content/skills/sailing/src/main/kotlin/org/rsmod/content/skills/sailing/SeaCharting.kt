package org.rsmod.content.skills.sailing

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseSailingLvl
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Generic sea charting: the "Chart" op on `sailing_charting_generic_*` locs records the task in its
 * `..._complete` varbit and gives 35 xp once (Wiki "Sea charting", captain's log tasks). The level
 * comes from the Wiki row nearest the loc. Many of these locs are at sea and need a boat (tier 1);
 * the land ones work now. Not yet: spyglass/crate/current/diving/weather charts, sea and ocean
 * completion bonuses (claimed from Chartin' Charles), the captain's log item requirement (its quest
 * is skipped).
 */
class SeaCharting
@Inject
constructor(private val locTypes: LocTypeList, private val xpMods: XpModifiers) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var bound = 0
        for (chart in SailingData.chartLocs) {
            val loc = SailingLocs.charts.getValue(chart.loc)
            val varbit = SailingVarBits.charts.getValue(chart.varbit)
            val slot = locTypes[loc].op.indexOfFirst { it.equals("Chart", ignoreCase = true) }
            if (slot == -1) {
                continue
            }
            bindOp(loc, slot + 1) { chart(it, varbit) }
            bound++
        }
        logger.info { "Sailing: bound $bound generic charting locs." }
    }

    private fun ScriptContext.bindOp(
        type: LocType,
        op: Int,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        when (op) {
            1 -> if (!isLocOpBound(type, 1)) onOpLoc1(type) { action(it.loc) }
            2 -> if (!isLocOpBound(type, 2)) onOpLoc2(type) { action(it.loc) }
            3 -> if (!isLocOpBound(type, 3)) onOpLoc3(type) { action(it.loc) }
            4 -> if (!isLocOpBound(type, 4)) onOpLoc4(type) { action(it.loc) }
            5 -> if (!isLocOpBound(type, 5)) onOpLoc5(type) { action(it.loc) }
        }
    }

    private fun ProtectedAccess.chart(loc: BoundLocInfo, varbit: VarBitType) {
        if (vars[varbit] != 0) {
            mes("You have already charted this.")
            return
        }
        val level = levelAt(loc.coords.x, loc.coords.z)
        if (player.baseSailingLvl < level) {
            mes("You need a Sailing level of $level to chart this.")
            return
        }
        faceSquare(loc.coords)
        vars[varbit] = 1
        statAdvance(stats.sailing, CHART_XP * xpMods.get(player, stats.sailing))
        mes("You make a note of this in your charts.")
    }

    /** Level of the nearest Wiki generic chart row (all generic rows within a sea share it). */
    private fun levelAt(x: Int, z: Int): Int =
        SailingData.chartRows
            .filter { !it.spyglass }
            .minByOrNull { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }
            ?.level ?: 1

    private companion object {
        const val CHART_XP = 35.0
    }
}
