package org.rsmod.content.interfaces.bank.sort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BankSorterTest {
    private val rules = BankSortRules.load()
    private val sorter = BankSorter(rules)

    private fun gear(name: String, wearpos: Int, level: Int = 1) =
        SortItem(name, wearpos = wearpos, iops = listOf("Wear"), level = level)

    private fun entries(vararg items: SortItem) = items.map { BankSorter.Entry(it.name, it) }

    @Test
    fun `rules file loads`() {
        assert(rules.rules.isNotEmpty())
    }

    @Test
    fun `classifies the basic categories`() {
        fun tab(item: SortItem) = rules.classify(item).tab
        assertEquals(SortTab.Main, tab(SortItem("Coins", stackable = true)))
        assertEquals(SortTab.Tools, tab(gear("Rune pickaxe", wearpos = 3, level = 40)))
        assertEquals(SortTab.Tools, tab(SortItem("Varrock teleport", iops = listOf("Break"))))
        assertEquals(SortTab.Tools, tab(gear("Amulet of glory(4)", wearpos = 2)))
        assertEquals(SortTab.Ammo, tab(SortItem("Nature rune", stackable = true)))
        assertEquals(SortTab.Ammo, tab(SortItem("Rune arrow", wearpos = 13, stackable = true)))
        assertEquals(SortTab.Ammo, tab(SortItem("Adamant dart", wearpos = 3, stackable = true)))
        assertEquals(SortTab.Consumables, tab(SortItem("Shark", iops = listOf("Eat"))))
        assertEquals(SortTab.Consumables, tab(SortItem("Prayer potion(4)", iops = listOf("Drink"))))
        assertEquals(SortTab.Herblore, tab(SortItem("Grimy ranarr weed")))
        assertEquals(SortTab.Herblore, tab(SortItem("Ranarr potion (unf)")))
        assertEquals(SortTab.Farming, tab(SortItem("Ranarr seed", stackable = true)))
        assertEquals(SortTab.Raw, tab(SortItem("Iron ore")))
        assertEquals(SortTab.Raw, tab(SortItem("Yew logs")))
        assertEquals(SortTab.Raw, tab(SortItem("Dragon bones", iops = listOf("Bury"))))
        assertEquals(SortTab.Processed, tab(SortItem("Steel bar")))
        assertEquals(SortTab.Combat, tab(gear("Rune platebody", wearpos = 4, level = 40)))
        assertEquals(SortTab.Misc, tab(SortItem("Ghostspeak amulet thing", tradeable = false)))
        assertEquals(SortTab.Main, tab(SortItem("Pot of flour")))
    }

    @Test
    fun `combat gear groups by set, lowest tier first, slot order inside`() {
        val sorted =
            sorter
                .sortBank(
                    entries(
                        gear("Rune scimitar", wearpos = 3, level = 40),
                        gear("Rune platebody", wearpos = 4, level = 40),
                        gear("Bronze full helm", wearpos = 0),
                        gear("Rune full helm", wearpos = 0, level = 40),
                        gear("Fire cape", wearpos = 1),
                        gear("Green d'hide body", wearpos = 4, level = 40),
                        gear("Rune kiteshield", wearpos = 5, level = 40),
                        gear("Rune platelegs", wearpos = 7, level = 40),
                    )
                )
                .tabs
                .single()
        assertEquals(SortTab.Combat, sorted.first)
        assertEquals(
            listOf(
                "Bronze full helm",
                "Green d'hide body",
                "Rune full helm",
                "Rune platebody",
                "Rune platelegs",
                "Rune kiteshield",
                "Rune scimitar",
                "Fire cape",
            ),
            sorted.second,
        )
    }

    @Test
    fun `empty categories are skipped and coins lead the main tab, fillers last`() {
        val coins = SortItem("Coins", stackable = true)
        val flour = SortItem("Pot of flour")
        val filler = SortItem("Bank filler")
        val ore = SortItem("Iron ore")
        val shark = SortItem("Shark", iops = listOf("Eat"))
        val layout =
            sorter.sortBank(
                listOf(
                    BankSorter.Entry("filler", filler, isFiller = true),
                    BankSorter.Entry("flour", flour),
                    BankSorter.Entry("ore", ore),
                    BankSorter.Entry("coins", coins),
                    BankSorter.Entry("shark", shark),
                )
            )
        assertEquals(listOf(SortTab.Consumables, SortTab.Raw), layout.tabs.map { it.first })
        assertEquals(listOf("coins", "flour", "filler"), layout.main)
        assertEquals(listOf("shark", "ore", "coins", "flour", "filler"), layout.ordered)
    }

    @Test
    fun `potions group by name with the highest dose first`() {
        val drink = listOf("Drink")
        val layout =
            sorter.sortBank(
                entries(
                    SortItem("Prayer potion(1)", iops = drink),
                    SortItem("Attack potion(3)", iops = drink),
                    SortItem("Prayer potion(4)", iops = drink),
                )
            )
        assertEquals(
            listOf("Attack potion(3)", "Prayer potion(4)", "Prayer potion(1)"),
            layout.ordered,
        )
    }

    @Test
    fun `inventory puts stackables first`() {
        val sorted =
            sorter.sortInv(
                entries(
                    SortItem("Shark", iops = listOf("Eat")),
                    SortItem("Coins", stackable = true),
                    SortItem("Iron ore"),
                    SortItem("Law rune", stackable = true),
                )
            )
        assertEquals(listOf("Law rune", "Coins", "Shark", "Iron ore"), sorted)
    }

    @Test
    fun `set names lose their set suffix`() {
        assertEquals("dharok's", BankSorter.stripSetSuffix("dharok's armour set"))
        assertEquals("mystic", BankSorter.stripSetSuffix("mystic set (light)"))
        assertEquals("prayer potion", BankSorter.baseName("prayer potion(4)"))
        assertEquals(4, BankSorter.dose("prayer potion(4)"))
    }
}
