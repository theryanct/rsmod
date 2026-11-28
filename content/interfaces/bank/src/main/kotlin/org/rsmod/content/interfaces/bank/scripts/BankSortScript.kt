package org.rsmod.content.interfaces.bank.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.content.interfaces.bank.BankTab
import org.rsmod.content.interfaces.bank.configs.bank_objs
import org.rsmod.content.interfaces.bank.selectedTab
import org.rsmod.content.interfaces.bank.sort.BankSortRules
import org.rsmod.content.interfaces.bank.sort.BankSorter
import org.rsmod.content.interfaces.bank.sort.SortItem
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.inv.isType
import org.rsmod.game.type.enums.EnumTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Bank and inventory auto-sort: `::sortbank` / `::sortinv`, right-click "Sort" on the bank's
 * Swap/Insert button (bank) and on the inventory tab button (inventory; see BankAnywhereScript).
 *
 * The bank is laid out into the 9 tabs by category (rules in `sort/bank_sort_rules.tsv`), with
 * coins and anything unmatched in the main tab. Empty categories are skipped, since bank tabs
 * can't have gaps. Worn items are never touched.
 */
class BankSortScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val enumTypes: EnumTypeList,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    // Lazy, not set in startup(): other scripts get their own (never started) injected instance.
    private val rules by lazy { BankSortRules.load() }
    private val sorter by lazy { BankSorter(rules) }

    /** Obj id -> name of the Grand Exchange item set it's a piece of. */
    private val setNames by lazy { loadSetNames() }

    override fun ScriptContext.startup() {
        logger.info { "Bank sort: ${rules.rules.size} rules, ${setNames.size} item-set pieces." }

        onCommand("sortbank") {
            modLevel = modlevels.admin
            desc = "Sort your bank into tabs by category"
            cheat { player.launchSort { sortBank() } }
        }
        onCommand("sortinv") {
            modLevel = modlevels.admin
            desc = "Sort your inventory (stackables first, then by category)"
            cheat { player.launchSort { sortInv() } }
        }
    }

    private fun Player.launchSort(block: suspend ProtectedAccess.() -> Unit) {
        protectedAccess.launch(this, busyText = "You're busy right now.", block = block)
    }

    /** Sorts the bank in place; safe with the bank open or closed. */
    fun ProtectedAccess.sortBank() {
        val entries =
            bank.indices.mapNotNull { slot ->
                val obj = bank[slot] ?: return@mapNotNull null
                val type = objTypes[obj]
                val isFiller = obj.isType(bank_objs.filler)
                BankSorter.Entry(obj, sortItem(describedType(type)), isFiller)
            }
        if (entries.isEmpty()) {
            mes("Your bank is empty.")
            return
        }
        val layout = sorter.sortBank(entries)
        writeBack(bank, layout.ordered)

        for (tab in BankTab.tabs) {
            vars[tab.sizeVarBit] = layout.tabs.getOrNull(tab.index)?.second?.size ?: 0
        }
        vars[BankTab.Main.sizeVarBit] = layout.main.size
        selectedTab = BankTab.Main
        mes("Your bank has been sorted into ${layout.tabs.size} tabs.")
    }

    /** Sorts the inventory in place. */
    fun ProtectedAccess.sortInv() {
        val entries =
            inv.indices.mapNotNull { slot ->
                val obj = inv[slot] ?: return@mapNotNull null
                BankSorter.Entry(obj, sortItem(objTypes[obj]))
            }
        if (entries.isEmpty()) {
            return
        }
        writeBack(inv, sorter.sortInv(entries))
        mes("Your inventory has been sorted.")
    }

    /** Writes [ordered] to the front of [into] and clears the rest; only changed slots update. */
    private fun writeBack(into: Inventory, ordered: List<InvObj>) {
        check(ordered.size <= into.size) { "Sorted more objs than the inv holds." }
        for (slot in into.indices) {
            val obj = ordered.getOrNull(slot)
            if (into[slot] != obj) {
                into[slot] = obj
            }
        }
    }

    /** A placeholder sorts as the real obj it stands for. */
    private fun describedType(type: UnpackedObjType): UnpackedObjType {
        if (!type.isPlaceholder) {
            return type
        }
        return objTypes[type.placeholderlink] ?: type
    }

    private fun sortItem(type: UnpackedObjType): SortItem {
        val level =
            maxOf(
                type.paramOrNull(params.statreq1_level) ?: 0,
                type.paramOrNull(params.statreq2_level) ?: 0,
                type.paramOrNull(params.levelrequire) ?: 0,
            )
        return SortItem(
            name = type.name,
            wearpos = type.wearpos1,
            stackable = type.isStackable,
            iops = type.iop.filterNotNull(),
            tradeable = type.stockmarket,
            level = level,
            setName = setNames[type.id],
        )
    }

    /** GE item sets (enum 1033 -> one enum per set: key -1 = set obj, 0.. = pieces). */
    private fun loadSetNames(): Map<Int, String> {
        val list = enumTypes[SET_LIST_ENUM] ?: return emptyMap()
        val names = mutableMapOf<Int, String>()
        for (value in list.primitiveMap.values) {
            val setEnum = enumTypes[value as? Int ?: continue] ?: continue
            val entries = setEnum.primitiveMap.mapKeys { it.key as Int }
            val setObj = entries[-1] as? Int ?: continue
            val setName = objTypes[setObj]?.name ?: continue
            for ((key, piece) in entries) {
                if (key >= 0 && piece is Int) {
                    names.putIfAbsent(piece, setName)
                }
            }
        }
        return names
    }

    private companion object {
        private const val SET_LIST_ENUM = 1033
    }
}
