package org.rsmod.content.interfaces.bank.configs

import org.rsmod.api.type.editors.comp.ComponentEditor
import org.rsmod.api.type.refs.comp.ComponentReferences

/** The inventory side-panel tab button ("stone3") in every gameframe: fixed, resizable x2, mobile. */
internal object BankAnywhereComponents : ComponentReferences() {
    val inventory_stones =
        listOf(
            find("toplevel:stone3"),
            find("toplevel_osrs_stretch:stone3"),
            find("toplevel_pre_eoc:stone3"),
            find("toplevel_osm:stone3"),
        )
}

/**
 * "Bank anywhere": adds op2 "Bank" to the inventory tab buttons, plus op3 "Sort" (inventory sort,
 * BankSortScript) and op4 "Tool belt" (content/interfaces/tool-belt). All stone ops live in this
 * one edit: a second ComponentEditor on the same component would overwrite these.
 */
internal object BankAnywhereEdits : ComponentEditor() {
    init {
        for (stone in BankAnywhereComponents.inventory_stones) {
            editOps(stone, 2 to "Bank", 3 to "Sort", 4 to "Tool belt")
        }
    }
}
