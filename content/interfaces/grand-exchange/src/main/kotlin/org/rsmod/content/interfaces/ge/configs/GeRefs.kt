package org.rsmod.content.interfaces.ge.configs

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences

/*
 * Every Grand Exchange interface/component/var/inv name in one file, for the rev 243 rename pass.
 * Semantics are from the rev 233 client scripts (`ge_offers_*`, osrs-dumps 7b2fb2a8).
 */

internal object GeInterfaces : InterfaceReferences() {
    val offers = find("ge_offers")
    val offers_side = find("ge_offers_side")
    /** Collection box (also opened by bankers' "Collect"). */
    val collect = find("ge_collect")
}

internal object GeComponents : ComponentReferences() {
    val history = find("ge_offers:history")
    val back = find("ge_offers:back")
    val collectall = find("ge_offers:collectall")
    val index =
        listOf(
            find("ge_offers:index_0"),
            find("ge_offers:index_1"),
            find("ge_offers:index_2"),
            find("ge_offers:index_3"),
            find("ge_offers:index_4"),
            find("ge_offers:index_5"),
            find("ge_offers:index_6"),
            find("ge_offers:index_7"),
        )
    val details_desc = find("ge_offers:details_desc")
    val details_marketprice = find("ge_offers:details_marketprice")
    val details_fee = find("ge_offers:details_fee")
    val details_status = find("ge_offers:details_status")
    val details_collect = find("ge_offers:details_collect")
    val setup = find("ge_offers:setup")
    val setup_desc = find("ge_offers:setup_desc")
    val setup_marketprice = find("ge_offers:setup_marketprice")
    val setup_fee = find("ge_offers:setup_fee")
    val setup_confirm = find("ge_offers:setup_confirm")

    val side_items = find("ge_offers_side:items")

    val collectbox_inv = find("ge_collect:collect_inv")
    val collectbox_bank = find("ge_collect:collect_bank")
    val collectbox_slots =
        listOf(
            find("ge_collect:collect_0"),
            find("ge_collect:collect_1"),
            find("ge_collect:collect_2"),
            find("ge_collect:collect_3"),
            find("ge_collect:collect_4"),
            find("ge_collect:collect_5"),
            find("ge_collect:collect_6"),
            find("ge_collect:collect_7"),
        )
}

internal object GeVarBits : VarBitReferences() {
    /** 0 = index panel; 1..8 = slot + 1 (setup panel if empty, details panel otherwise). */
    val selected_slot = find("ge_selectedslot")
    /** 0 = buy, 1 = sell. */
    val newoffer_type = find("ge_newoffer_type")
    val newoffer_quantity = find("ge_newoffer_quantity")
    /** Custom +/-X% price button value; 0 = unset. */
    val price_custom = find("ge_price_custom")
    /** Sell fee per mille; 0 = no fee shown (no tax on this server). */
    val transmit_taxrate = find("ge_transmit_taxrate")
}

internal object GeVarps : VarpReferences() {
    /** Obj chosen on the setup panel; -1 = "Choose an item...". (Varp 1151.) */
    val newoffer_obj = find("tradingpost_search")
    /** Price on the setup screen (rev 243: a varp; was a varbit of varp 1043 in 233). */
    val newoffer_price = find("ge_newoffer_price")
    val last_searched = find("ge_last_searched")
    /** Repeat Offer: hidden while the obj is -1. */
    val last_offer_item = find("ge_last_offer_item")
    /** Item-sink offers: a slot whose obj isn't -1 is drawn as occupied. */
    val itemsink_objs =
        listOf(
            find("ge_itemsink_obj_0"),
            find("ge_itemsink_obj_1"),
            find("ge_itemsink_obj_2"),
            find("ge_itemsink_obj_3"),
            find("ge_itemsink_obj_4"),
            find("ge_itemsink_obj_5"),
            find("ge_itemsink_obj_6"),
            find("ge_itemsink_obj_7"),
        )
}

internal object GeInvs : InvReferences() {
    /** Per-slot collection boxes (client enum 150): index 0 = items, index 1 = coins. */
    val collect =
        listOf(
            find("tradingpost_sell_0"),
            find("tradingpost_sell_1"),
            find("tradingpost_sell_2"),
            find("tradingpost_sell_3"),
            find("tradingpost_sell_4"),
            find("tradingpost_sell_5"),
            find("ge_collect_6"),
            find("ge_collect_7"),
        )
}

internal object GeConstants {
    /** `ge_offers_setdesc(desc, extra, desc component, fee component)`. */
    const val SETDESC_SCRIPT = 5730

    /** Setup panel cc children (comsubs), counted from `ge_offers_setup_init`. */
    const val SETUP_CHOOSE_ITEM = 0
    const val SETUP_QTY_MINUS_1 = 1
    const val SETUP_QTY_PLUS_1_SMALL = 2
    const val SETUP_QTY_PLUS_1 = 3
    const val SETUP_QTY_PLUS_10 = 4
    const val SETUP_QTY_PLUS_100 = 5
    const val SETUP_QTY_1K_OR_ALL = 6
    const val SETUP_QTY_ENTER = 7
    const val SETUP_PRICE_FIRST = 8
    const val SETUP_PRICE_LAST = 15

    /** Index slot cc children. */
    const val INDEX_OFFER = 2
    const val INDEX_BUY = 3
    const val INDEX_SELL = 4

    /** collectall cc children. */
    const val COLLECT_ALL = 0
    const val REPEAT_OFFER = 1

    /** details_collect cc children: collect inv index 0 / 1. */
    const val DETAILS_COLLECT_ITEM = 2
    const val DETAILS_COLLECT_COINS = 3

    /** ge_collect:collect_N cc children (`ge_collect_initslot`): collect inv index 0 / 1. */
    const val COLLECTBOX_ITEM = 3
    const val COLLECTBOX_COINS = 4
}
