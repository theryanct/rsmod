package org.rsmod.content.interfaces.ge.configs

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences

/** Grand Exchange item sets (clerk "Sets"). Rev 243 names; enums are by id (unnamed in 243). */
internal object GeItemSetInterfaces : InterfaceReferences() {
    val itemsets = find("itemsets")
    val itemsets_side = find("itemsets_side")
}

internal object GeItemSetComponents : ComponentReferences() {
    /** One cc child per set, in enum 1033 order: op1 Pack/Contents, op10 Examine. */
    val itemlist = find("itemsets:itemlist")

    /** One cc child per inventory slot: op1 Unpack (sets only), op10 Examine. */
    val side_items = find("itemsets_side:items")
}

internal object GeItemSetEnums {
    /** Set index -> per-set enum (key -1 = the set obj, keys 0..n = the pieces). */
    const val SET_LIST = 1033

    /** Set obj -> its per-set enum (used by the side panel to show "Unpack"). */
    const val SET_BY_OBJ = 1034
}
