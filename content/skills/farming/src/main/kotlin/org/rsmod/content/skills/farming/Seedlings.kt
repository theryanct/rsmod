package org.rsmod.content.skills.farming

import java.util.WeakHashMap
import org.rsmod.api.config.refs.invs
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.farmingLvl
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tree saplings (Wiki "Seedling"): use a tree seed on a filled plant pot (gardening trowel in the
 * inventory) to get a seedling, water it with a watering can, and on the next 5-minute farming
 * growth tick it becomes a sapling, in the inventory or the bank. Saplings are planted in tree,
 * fruit tree, hardwood and spirit tree patches by [Farming]. Plant pots are filled by using an
 * empty pot on a weeded patch (also in [Farming]).
 */
internal object SeedlingData {
    /** seed obj, pot stem (`plantpot_<stem>` / `_watered`), sapling obj, Farming level. */
    class Tree(val seed: String, val stem: String, val sapling: String, val level: Int, val label: String)

    val trees =
        listOf(
            Tree("acorn", "acorn", "plantpot_oak_sapling", 15, "oak"),
            Tree("willow_seed", "willow_seed", "plantpot_willow_sapling", 30, "willow"),
            Tree("maple_seed", "maple_seed", "plantpot_maple_sapling", 45, "maple"),
            Tree("yew_seed", "yew_seed", "plantpot_yew_sapling", 60, "yew"),
            Tree("magic_tree_seed", "magic_tree_seed", "plantpot_magic_tree_sapling", 75, "magic"),
            Tree("spirit_tree_seed", "spirit_tree_seed", "plantpot_spirit_tree_sapling", 83, "spirit"),
            Tree("apple_tree_seed", "apple_seed", "plantpot_apple_sapling", 27, "apple"),
            Tree("banana_tree_seed", "banana_seed", "plantpot_banana_sapling", 33, "banana"),
            Tree("orange_tree_seed", "orange_seed", "plantpot_orange_sapling", 39, "orange"),
            Tree("curry_tree_seed", "curry_seed", "plantpot_curry_sapling", 42, "curry"),
            Tree("pineapple_tree_seed", "pineapple_seed", "plantpot_pineapple_sapling", 51, "pineapple"),
            Tree("papaya_tree_seed", "papaya_seed", "plantpot_papaya_sapling", 57, "papaya"),
            Tree("palm_tree_seed", "palm_seed", "plantpot_palm_sapling", 68, "palm"),
            Tree("calquat_tree_seed", "calquat_seed", "plantpot_calquat_sapling", 72, "calquat"),
            Tree("teak_seed", "teak_seed", "plantpot_teak_sapling", 35, "teak"),
            Tree("mahogany_seed", "mahogany_seed", "plantpot_mahogany_sapling", 55, "mahogany"),
            Tree("dragonfruit_tree_seed", "dragonfruit_seed", "plantpot_dragonfruit_sapling", 81, "dragonfruit"),
            Tree("celastrus_tree_seed", "celastrus_tree_seed", "plantpot_celastrus_tree_sapling", 85, "celastrus"),
            Tree("redwood_tree_seed", "redwood_tree_seed", "plantpot_redwood_tree_sapling", 90, "redwood"),
        )

    /** Seedlings grow on the 5-minute farming growth tick (Wiki "Growth tick"). */
    const val GROWTH_MINUTES = 5
}

internal object SeedlingObjs : ObjReferences() {
    val trowel = find("gardening_trowel")
    val empty_pot = find("plantpot_empty")
    val filled_pot = find("plantpot_compost")
    val watering_cans: List<ObjType> = (0..8).map { find("watering_can_$it") }

    val seeds: List<ObjType> = SeedlingData.trees.map { find(it.seed) }
    val seedlings: List<ObjType> = SeedlingData.trees.map { find("plantpot_${it.stem}") }
    val watered: List<ObjType> = SeedlingData.trees.map { find("plantpot_${it.stem}_watered") }
    val saplings: List<ObjType> = SeedlingData.trees.map { find(it.sapling) }
}

class Seedlings : PluginScript() {
    override fun ScriptContext.startup() {
        for ((index, tree) in SeedlingData.trees.withIndex()) {
            onOpHeldU(SeedlingObjs.seeds[index], SeedlingObjs.filled_pot) { sow(tree, index) }
            for (can in SeedlingObjs.watering_cans.drop(1)) {
                onOpHeldU(can, SeedlingObjs.seedlings[index]) { water(tree, index, it.first.id) }
            }
        }
    }

    private fun ProtectedAccess.sow(tree: SeedlingData.Tree, index: Int) {
        if (SeedlingObjs.trowel !in inv) {
            mes("You need a gardening trowel to do that.")
            return
        }
        if (player.farmingLvl < tree.level) {
            mes("You need a Farming level of ${tree.level} to plant this seed.")
            return
        }
        invDel(inv, SeedlingObjs.seeds[index])
        invDel(inv, SeedlingObjs.filled_pot)
        invAdd(inv, SeedlingObjs.seedlings[index])
        anim(FarmingSeqs.dibbing)
        mes("You sow the seed in the pot.")
    }

    private fun ProtectedAccess.water(tree: SeedlingData.Tree, index: Int, canId: Int) {
        val cans = SeedlingObjs.watering_cans
        val charges = cans.indexOfFirst { it.id == canId }
        if (charges <= 0) return
        invDel(inv, cans[charges])
        invAdd(inv, cans[charges - 1])
        invDel(inv, SeedlingObjs.seedlings[index])
        invAdd(inv, SeedlingObjs.watered[index])
        anim(FarmingSeqs.watering)
        mes("You water the ${tree.label} seedling.")
    }

    internal companion object {
        /** Last growth cycle each online player was checked in (transient). */
        private val lastCycle = WeakHashMap<Player, Int>()

        /**
         * Called from [Farming]'s refresh timer. On each new growth cycle (and on login) every
         * watered seedling in the inventory or bank becomes a sapling, in place.
         */
        internal fun grow(player: Player, nowMinutes: Int) {
            val cycle = nowMinutes / SeedlingData.GROWTH_MINUTES
            if (lastCycle.put(player, cycle) == cycle) return
            var grown = growIn(player.inv, bank = false)
            val bank = player.invMap[invs.bank]
            if (bank != null) grown += growIn(bank, bank = true)
            if (grown > 0) {
                // Authored (not Wiki): there is no growth message in OSRS.
                player.mes(if (grown == 1) "Your seedling has grown into a sapling." else "Your seedlings have grown into saplings.")
            }
        }

        private fun growIn(inv: Inventory, bank: Boolean): Int {
            var grown = 0
            for (slot in inv.indices) {
                val obj = inv[slot] ?: continue
                val index = SeedlingObjs.watered.indexOfFirst { it.id == obj.id }
                if (index < 0) continue
                val sapling = SeedlingObjs.saplings[index]
                // A bank stack of saplings elsewhere would duplicate; leave this one to grow later.
                if (bank && inv.indices.any { inv[it]?.id == sapling.id }) continue
                inv[slot] = InvObj(sapling, obj.count, obj.vars)
                grown += obj.count
            }
            return grown
        }
    }
}
