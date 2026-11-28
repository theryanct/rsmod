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
 * "Bank anywhere": adds op2 "Bank" to the inventory tab buttons. Isolated here so the rev 243
 * cutover only has to re-check the component names.
 */
internal object BankAnywhereEdits : ComponentEditor() {
    init {
        for (stone in BankAnywhereComponents.inventory_stones) {
            editOps(stone, 2 to "Bank")
        }
    }
}
