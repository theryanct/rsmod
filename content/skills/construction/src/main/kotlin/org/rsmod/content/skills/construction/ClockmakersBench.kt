package org.rsmod.content.skills.construction

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The workshop's clockmaker's bench ("Craft" on crafting tables 1-4, OSRS Wiki "Clockmaking space",
 * "Clockwork", "Clockwork suit"): Crafting xp, not Construction.
 * - Clockwork: a steel bar, 8 Crafting, 15 xp (crafting table 2 or better).
 * - Clockwork toy -> Clockwork penguin: clockwork + plank + silk, 30 Crafting, 15 xp (table 3 or
 *   better), once Larry has handed over his clockwork book in Cold War (the quest's cache varbit
 *   is read here directly; this module doesn't depend on quests).
 *
 * Simplified: only what Cold War needs. The table's other products (toy horsey, wooden cat, toy
 * soldier, toy doll, sextant, watch, toy mouse, clockwork cat) are not built yet; [recipes] takes
 * them as further rows.
 */
class ClockmakersBench
@Inject
constructor(private val objTypes: ObjTypeList, private val xpMods: XpModifiers) : PluginScript() {
    private class Recipe(
        val group: String,
        val label: String,
        val table: Int,
        val level: Int,
        val xp: Double,
        val product: () -> ObjType,
        val materials: () -> List<ObjType>,
        val unlocked: ProtectedAccess.() -> Boolean = { true },
    )

    private val recipes =
        listOf(
            Recipe(
                "Clockwork mechanism",
                "Clockwork mechanism",
                2,
                8,
                15.0,
                { BenchObjs.clockwork },
                { listOf(BenchObjs.steel_bar) },
            ),
            Recipe(
                "Clockwork toy",
                "Clockwork penguin",
                3,
                30,
                15.0,
                { BenchObjs.penguin_suit },
                { listOf(BenchObjs.clockwork, BenchObjs.plank, BenchObjs.silk) },
                unlocked = { player.vars[BenchVarBits.cold_war] >= COLD_WAR_BOOK },
            ),
        )

    override fun ScriptContext.startup() {
        for ((index, bench) in BenchLocs.benches.withIndex()) {
            if (isLocOpBound(bench, 1)) continue
            onOpLoc1(bench) { craft(tier = index + 1) }
        }
    }

    private suspend fun ProtectedAccess.craft(tier: Int) {
        val available = recipes.filter { it.table <= tier && it.unlocked(this) }
        if (available.isEmpty()) {
            mes("You can't make anything useful on this table yet.") // Authored (not Wiki).
            return
        }
        val groups = available.map { it.group }.distinct()
        val group =
            if (groups.size == 1) {
                groups.single()
            } else {
                groups.getOrNull(menu("What would you like to make?", hotkeys = true, choices = groups))
                    ?: return
            }
        val inGroup = available.filter { it.group == group }
        val recipe =
            if (inGroup.size == 1) {
                inGroup.single()
            } else {
                inGroup.getOrNull(menu(group, hotkeys = true, choices = inGroup.map { it.label })) ?: return
            }
        make(recipe)
    }

    private suspend fun ProtectedAccess.make(recipe: Recipe) {
        if (statBase(stats.crafting) < recipe.level) {
            mes("You need a Crafting level of ${recipe.level} to make that.")
            return
        }
        val missing = recipe.materials().firstOrNull { invTotal(inv, it) < 1 }
        if (missing != null) {
            mes("You need ${objTypes[missing].name.lowercase()} to make that.") // Authored (not Wiki).
            return
        }
        anim(BenchSeqs.craft)
        delay(3)
        for (material in recipe.materials()) invDel(inv, material, 1)
        invAdd(inv, recipe.product())
        statAdvance(stats.crafting, recipe.xp * xpMods.get(player, stats.crafting))
        mes("You make a ${recipe.label.lowercase()}.") // Authored (not Wiki).
    }

    private companion object {
        /** Cold War's quest varbit value once Larry has given you his clockwork book (ColdWar.BOOK). */
        const val COLD_WAR_BOOK = 20
    }
}

internal object BenchLocs : LocReferences() {
    /** Crafting tables 1-4 as built ("Clockmaker's bench", op1 Craft). */
    val benches: List<LocType> =
        listOf(
            find("poh_clockmaking_1"),
            find("poh_clockmaking_2"),
            find("poh_clockmaking_3"),
            find("poh_clockmaking_4"),
        )
}

internal object BenchObjs : ObjReferences() {
    val steel_bar = find("steel_bar")
    val clockwork = find("poh_clockwork_mechanism")
    val plank = find("woodplank")
    val silk = find("silk")
    val penguin_suit = find("peng_suit_unwound")
}

internal object BenchSeqs : SeqReferences() {
    val craft = find("human_hammer_hit")
}

internal object BenchVarBits : VarBitReferences() {
    val cold_war = find("peng_quest")
}
