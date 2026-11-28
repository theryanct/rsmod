package org.rsmod.content.skills.fishing.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.cookingLvl
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.fishing.configs.fishing_objs
import org.rsmod.content.skills.fishing.configs.fishing_seqs
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Barbarian Training: a knife on a leaping fish guts it (OSRS Wiki Leaping trout/salmon/sturgeon,
 * Fish offcuts; Mod Ash's rates). Each cut uses up the fish and rolls for roe (caviar from
 * sturgeon): trout level/150, salmon and sturgeon level/80 (certain at 80 Cooking). A good cut
 * gives 10 (trout, salmon) or 15 (sturgeon) Cooking xp and then a 1/2, 3/4 or 5/6 chance of fish
 * offcuts as well; a failed cut gives nothing. After the first cut the next fish is cut every
 * [CUT_TICKS] ticks automatically while any remain.
 */
class BarbarianGutting @Inject constructor(private val xpMods: XpModifiers) : PluginScript() {
    override fun ScriptContext.startup() {
        for (fish in Gut.entries) {
            onOpHeldU(fishing_objs.knife, fish.fish()) { gut(fish) }
        }
    }

    private suspend fun ProtectedAccess.gut(fish: Gut) {
        while (fish.fish() in inv) {
            if (!cut(fish)) return
            if (fish.fish() !in inv) return
            delay(CUT_TICKS)
        }
    }

    private fun ProtectedAccess.cut(fish: Gut): Boolean {
        val eggs = fish.eggs()
        // The fish's slot is freed first; roe and offcuts stack.
        val slotsNeeded =
            listOf(eggs, fishing_objs.fish_offcuts).count { it !in inv }
        if (inv.freeSpace() + 1 < slotsNeeded) {
            mes("You don't have enough inventory space to gut the fish.") // Authored (not Wiki)
            return false
        }
        anim(fishing_seqs.knife_use)
        invDel(inv, fish.fish())
        val chance = (player.cookingLvl.toDouble() / fish.eggDivisor).coerceAtMost(1.0)
        if (random.of(1_000_000) >= (chance * 1_000_000).toInt()) {
            spam("You cut open the fish but find nothing of use, so you discard it.") // Authored (not Wiki)
            return true
        }
        invAdd(inv, eggs)
        statAdvance(stats.cooking, fish.cookingXp * xpMods.get(player, stats.cooking))
        if (random.of(fish.offcutDenominator) < fish.offcutNumerator) {
            invAdd(inv, fishing_objs.fish_offcuts)
            spam("You cut open the fish and extract some ${fish.eggName} and some offcuts.") // Authored (not Wiki)
        } else {
            spam("You cut open the fish and extract some ${fish.eggName}, but the rest is discarded.") // Authored (not Wiki)
        }
        return true
    }

    private enum class Gut(
        val fish: () -> ObjType,
        val eggs: () -> ObjType,
        val eggName: String,
        /** Roe chance = Cooking level / [eggDivisor], capped at 1. */
        val eggDivisor: Int,
        val cookingXp: Double,
        val offcutNumerator: Int,
        val offcutDenominator: Int,
    ) {
        Trout({ fishing_objs.leaping_trout }, { fishing_objs.roe }, "roe", 150, 10.0, 1, 2),
        Salmon({ fishing_objs.leaping_salmon }, { fishing_objs.roe }, "roe", 80, 10.0, 3, 4),
        Sturgeon({ fishing_objs.leaping_sturgeon }, { fishing_objs.caviar }, "caviar", 80, 15.0, 5, 6),
    }

    private companion object {
        /** Wiki Fish offcuts: "a new fish is cut every 3 ticks automatically". */
        const val CUT_TICKS = 3
    }
}
