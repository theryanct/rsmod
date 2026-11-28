package org.rsmod.content.interfaces.bank.sort

/**
 * Orders bank / inventory contents by [BankSortRules]. Pure: callers map their objs to [Entry]s
 * and write the result back.
 */
class BankSorter(private val rules: BankSortRules) {
    /**
     * @param value the caller's payload (an inv obj), returned in sorted order.
     * @param item what the item is; for a placeholder, describe the real obj it stands for.
     * @param isFiller bank fillers always go last in the main tab.
     */
    data class Entry<T>(val value: T, val item: SortItem, val isFiller: Boolean = false)

    /**
     * @param tabs non-empty categories in bank tab order (tab 1 first), each with its entries.
     *   Empty categories are skipped, so the client's tab numbers shift down: bank tabs can't
     *   have gaps.
     * @param main the main tab's entries (coins + everything unclaimed, fillers last).
     */
    data class BankLayout<T>(val tabs: List<Pair<SortTab, List<T>>>, val main: List<T>) {
        val ordered: List<T>
            get() = tabs.flatMap { it.second } + main
    }

    fun <T> sortBank(entries: List<Entry<T>>): BankLayout<T> {
        val (fillers, items) = entries.partition { it.isFiller }
        val classified = items.map { Classified(it, rules.classify(it.item)) }
        val byTab = classified.groupBy { it.match.tab }
        val tabs =
            SortTab.entries
                .filter { it != SortTab.Main }
                .mapNotNull { tab ->
                    val inTab = byTab[tab] ?: return@mapNotNull null
                    tab to order(tab, inTab).map { it.entry.value }
                }
        val main =
            order(SortTab.Main, byTab[SortTab.Main].orEmpty()).map { it.entry.value } +
                fillers.map { it.value }
        return BankLayout(tabs, main)
    }

    /** Inventory: stackables first, then the bank's category order, same order within it. */
    fun <T> sortInv(entries: List<Entry<T>>): List<T> {
        val classified = entries.map { Classified(it, rules.classify(it.item)) }
        val (stackables, others) = classified.partition { it.entry.item.stackable }
        return listOf(stackables, others).flatMap { group ->
            group
                .groupBy { it.match.tab }
                .toSortedMap(compareBy(SortTab::ordinal))
                .flatMap { (tab, inTab) -> order(tab, inTab) }
                .map { it.entry.value }
        }
    }

    private class Classified<T>(val entry: Entry<T>, val match: BankSortRules.Match) {
        val item: SortItem
            get() = entry.item
    }

    private fun <T> order(tab: SortTab, items: List<Classified<T>>): List<Classified<T>> =
        if (tab == SortTab.Combat) orderGear(items) else items.sortedWith(itemOrder())

    /**
     * Rule group (file order), then tier, base name, highest dose first, full name. Wear levels
     * only count for wearables (pickaxes etc.): some potions carry odd statreq params.
     */
    private fun <T> itemOrder(): Comparator<Classified<T>> =
        compareBy<Classified<T>> { it.match.ruleIndex }
            .thenBy { rules.tier(it.item)?.level ?: wearLevel(it.item) }
            .thenBy { baseName(it.item.lowerName) }
            .thenByDescending { dose(it.item.lowerName) }
            .thenBy { it.item.lowerName }

    /**
     * Combat gear: grouped by set (tier word like "rune" / "black d'hide", else the GE item set),
     * sets lowest tier first, pieces in [SLOT_ORDER]. Gear with no set follows, by slot.
     */
    private fun <T> orderGear(items: List<Classified<T>>): List<Classified<T>> {
        val setKey = items.associateWith { setKey(it.item) }
        val setTier = mutableMapOf<String, Int>()
        for ((entry, key) in setKey) {
            key ?: continue
            val tier =
                rules.tier(entry.item, prefixOnly = true)?.takeIf { it.key == key }?.level
                    ?: entry.item.level
            setTier.merge(key, tier) { a, b -> maxOf(a, b) }
        }
        val (sets, loose) = items.partition { setKey[it] != null }
        val pieceOrder =
            compareBy<Classified<T>> { slotRank(it.item.wearpos) }
                .thenBy { it.item.level }
                .thenBy { baseName(it.item.lowerName) }
                .thenByDescending { dose(it.item.lowerName) }
                .thenBy { it.item.lowerName }
        val setOrder =
            compareBy<Classified<T>> { setTier.getValue(setKey.getValue(it)!!) }
                .thenBy { setKey.getValue(it) }
                .then(pieceOrder)
        return sets.sortedWith(setOrder) + loose.sortedWith(pieceOrder)
    }

    private fun setKey(item: SortItem): String? =
        rules.tier(item, prefixOnly = true)?.key ?: item.setName?.lowercase()?.let(::stripSetSuffix)

    private fun wearLevel(item: SortItem): Int = if (item.wearpos != -1) item.level else 0

    companion object {
        /** head body legs hands feet shield weapon back neck ring ammo (cache wearpos ids). */
        val SLOT_ORDER: List<Int> = listOf(0, 4, 7, 9, 10, 5, 3, 1, 2, 12, 13)

        private val DOSE = Regex("""^(.*?)\s*\((\d+)\)$""")
        private val SET_SUFFIX = Regex("""\s*(armour|robes?|equipment)?\s*set(\s*\(.*\))?$""")

        fun slotRank(wearpos: Int): Int =
            SLOT_ORDER.indexOf(wearpos).let { if (it == -1) SLOT_ORDER.size else it }

        /** "Prayer potion(4)" -> "prayer potion"; names without a number are unchanged. */
        fun baseName(lowerName: String): String =
            DOSE.matchEntire(lowerName)?.groupValues?.get(1) ?: lowerName

        /** "Prayer potion(4)" -> 4, "Amulet of glory(6)" -> 6; 0 when there is no number. */
        fun dose(lowerName: String): Int =
            DOSE.matchEntire(lowerName)?.groupValues?.get(2)?.toIntOrNull() ?: 0

        /** "dharok's armour set" -> "dharok's", "mystic set (light)" -> "mystic". */
        fun stripSetSuffix(lowerName: String): String =
            lowerName.replace(SET_SUFFIX, "").ifBlank { lowerName }
    }
}
