package org.rsmod.content.interfaces.collection

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.varp.VarpReferences

internal object CollectionLogInvs : InvReferences() {
    /** The client reads a page's obtained objs from here (`inv_total`), 500 slots. */
    val transmit = find("collection_transmit")
}

internal object CollectionLogComponents : ComponentReferences() {
    /** Tab buttons, by tab slot (struct param 684); op1 "View" transmits statically. */
    val tabs =
        listOf(
            find("collection:boss_tab"),
            find("collection:raid_tab"),
            find("collection:clue_tab"),
            find("collection:minigame_tab"),
            find("collection:other_tab"),
        )

    /**
     * Category list backgrounds, by tab slot (struct param 686): the client gives each entry
     * (comsub = index) op1 "Check".
     */
    val lists =
        listOf(
            find("collection:boss_background"),
            find("collection:raid_background"),
            find("collection:clue_background"),
            find("collection:minigame_background"),
            find("collection:other_background"),
        )
}

internal object CollectionLogVarps : VarpReferences() {
    /** Kill-count lines drawn under the page title (client proc collection_category_count). */
    val category_count = find("collection_category_count")
    val category_count2 = find("collection_category_count2")
    val category_count3 = find("collection_category_count3")

    /** The three lines, in client order (varps 2048, 2941, 2942). */
    val kill_count_lines = listOf(category_count, category_count2, category_count3)
}
