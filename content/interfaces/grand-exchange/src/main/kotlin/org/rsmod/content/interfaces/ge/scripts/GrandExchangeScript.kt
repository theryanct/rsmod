package org.rsmod.content.interfaces.ge.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.objs
import org.rsmod.api.market.MarketPrices
import org.rsmod.api.player.input.ObjDialogCancelledException
import org.rsmod.api.player.output.ClientScripts.mesLayerClose
import org.rsmod.api.player.output.StockMarket
import org.rsmod.api.player.output.VarpSync
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.startInvTransmit
import org.rsmod.api.player.stopInvTransmit
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onApNpc1
import org.rsmod.api.script.onApNpc2
import org.rsmod.api.script.onApNpc3
import org.rsmod.api.script.onApNpc4
import org.rsmod.api.script.onApNpc5
import org.rsmod.api.script.onIfClose
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.interfaces.ge.configs.GeComponents
import org.rsmod.content.interfaces.ge.configs.GeConstants
import org.rsmod.content.interfaces.ge.configs.GeInterfaces
import org.rsmod.content.interfaces.ge.configs.GeInvs
import org.rsmod.content.interfaces.ge.configs.GeVarBits
import org.rsmod.content.interfaces.ge.configs.GeVarps
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.GeSlot
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.varp.VarpType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/** Clerks are used across the booth counter, like bankers (OSRS ap range 2). */
private const val CLERK_AP_RANGE = 2

/**
 * Local-server Grand Exchange (rev 233 `ge_offers` / `ge_offers_side`).
 *
 * Offers fill instantly at the market price ([MarketPrices], the OSRS Wiki mid snapshot): no order
 * book and no other players. Defaults (Ryan's to change): no tax, buy limits ignored, collection
 * goes to the inventory and spills into the bank when the inventory is full. A slot only ever holds
 * a finished offer waiting to be collected ([Player.geSlots], persisted in `ge_slots`).
 *
 * The client changes some setup vars itself (quantity buttons) and transmits the click; the server
 * repeats the same calculation so both sides agree. Prices are pinned to the market price, so any
 * price button snaps the client back to it.
 */
class GrandExchangeScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val invTypes: InvTypeList,
    private val prices: MarketPrices,
) : PluginScript() {
    override fun ScriptContext.startup() {
        bindOpeners()

        onPlayerLogin { player.clearCollectInvs() }
        onIfClose(GeInterfaces.offers) { player.onClose() }

        onIfModalButton(GeComponents.back) { selectSlot(-1) }
        onIfModalButton(GeComponents.history) {
            mes("Trade history isn't kept on this server.")
        }
        for ((slot, component) in GeComponents.index.withIndex()) {
            onIfModalButton(component) { indexOp(slot, it.comsub, it.op) }
        }
        onIfModalButton(GeComponents.setup) { setupOp(it.comsub, it.op) }
        onIfModalButton(GeComponents.setup_confirm) { confirm() }
        onIfModalButton(GeComponents.setup_fee) { feeInfo() }
        onIfModalButton(GeComponents.details_fee) { feeInfo() }
        onIfModalButton(GeComponents.details_status) {
            mes("That offer has already completed.")
        }
        onIfModalButton(GeComponents.details_collect) { detailsCollectOp(it.comsub, it.op) }
        onIfModalButton(GeComponents.collectall) { collectAllOp(it.comsub, it.op) }
        onIfModalButton(GeComponents.side_items) { sideItemOp(it.comsub, it.op) }

        onIfOpen(GeInterfaces.collect) { player.onCollectionBoxOpen() }
        onIfClose(GeInterfaces.collect) { player.onCollectionBoxClose() }
        onIfModalButton(GeComponents.collectbox_inv) { collectAll(toBank = false) }
        onIfModalButton(GeComponents.collectbox_bank) { collectAll(toBank = true) }
        for ((slot, component) in GeComponents.collectbox_slots.withIndex()) {
            onIfModalButton(component) { collectionBoxOp(slot, it.comsub, it.op) }
        }
    }

    /* Openers: bound by op label, like shops and furnaces. */

    private fun ScriptContext.bindOpeners() {
        var npcs = 0
        for (type in npcTypes.values) {
            if (!type.name.equals("Grand Exchange Clerk", ignoreCase = true)) {
                continue
            }
            val slot = type.op.indexOfFirst { it.equals("Exchange", ignoreCase = true) }
            if (slot != -1) {
                // Clerks stand behind the booths: an approach (ap) trigger lets the player use them
                // across the counter (OSRS range 2, like bankers); the op trigger covers adjacency.
                onApNpcSlot(type, slot + 1) { npc ->
                    if (isWithinApRange(npc, CLERK_AP_RANGE)) {
                        openExchange()
                    }
                }
                onOpNpcSlot(type, slot + 1) { openExchange() }
                npcs++
            }
        }
        var locs = 0
        for (type in locTypes.values) {
            if (!type.name.equals("Grand Exchange booth", ignoreCase = true)) {
                continue
            }
            val slot = type.op.indexOfFirst { it.equals("Exchange", ignoreCase = true) }
            if (slot != -1) {
                onOpLocSlot(type, slot + 1) { openExchange() }
                locs++
            }
            // Only the GE's own booths: other bank booths' "Collect" is left to the bank module.
            val collect = type.op.indexOfFirst { it.equals("Collect", ignoreCase = true) }
            if (collect != -1) {
                onOpLocSlot(type, collect + 1) { openCollectionBox() }
            }
        }
        logger.info { "Grand Exchange: bound $npcs clerks and $locs booths." }
    }

    private fun ScriptContext.onOpNpcSlot(
        type: NpcType,
        slot: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        when (slot) {
            1 -> onOpNpc1(type) { action() }
            2 -> onOpNpc2(type) { action() }
            3 -> onOpNpc3(type) { action() }
            4 -> onOpNpc4(type) { action() }
            5 -> onOpNpc5(type) { action() }
        }
    }

    private fun ScriptContext.onApNpcSlot(
        type: NpcType,
        slot: Int,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        when (slot) {
            1 -> onApNpc1(type) { action(it.npc) }
            2 -> onApNpc2(type) { action(it.npc) }
            3 -> onApNpc3(type) { action(it.npc) }
            4 -> onApNpc4(type) { action(it.npc) }
            5 -> onApNpc5(type) { action(it.npc) }
        }
    }

    private fun ScriptContext.onOpLocSlot(
        type: LocType,
        slot: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        when (slot) {
            1 -> onOpLoc1(type) { action() }
            2 -> onOpLoc2(type) { action() }
            3 -> onOpLoc3(type) { action() }
            4 -> onOpLoc4(type) { action() }
            5 -> onOpLoc5(type) { action() }
        }
    }

    private fun ProtectedAccess.openExchange() {
        player.setVar(GeVarBits.selected_slot, 0)
        player.setVar(GeVarBits.transmit_taxrate, 0)
        player.setVar(GeVarBits.price_custom, 0)
        player.setVar(GeVarps.last_searched, -1)
        // Repeat Offer: re-send the saved last offer (obj varps default to 0, which is a real obj).
        val lastItem = player.vars[GeVarps.last_offer_item].takeIf { it > 0 } ?: -1
        player.setVar(GeVarps.last_offer_quantity, player.vars[GeVarps.last_offer_quantity])
        player.setVar(GeVarps.last_offer_type, player.vars[GeVarps.last_offer_type])
        player.setVar(GeVarps.last_offer_item, lastItem)
        for (varp in GeVarps.itemsink_objs) {
            player.setVar(varp, -1)
        }
        player.resetSetup(buy = true)
        for (slot in 0 until GeSlot.SLOT_COUNT) {
            player.refreshSlot(slot)
            player.startInvTransmit(player.collectInv(slot))
        }

        ifOpenMainSidePair(GeInterfaces.offers, GeInterfaces.offers_side)

        for (component in GeComponents.index) {
            ifSetEvents(component, 2..4, IfEvent.Op1, IfEvent.Op2, IfEvent.Op3)
        }
        ifSetEvents(GeComponents.collectall, 0..1, IfEvent.Op1, IfEvent.Op2)
        ifSetEvents(GeComponents.setup, 0..15, IfEvent.Op1, IfEvent.Op2)
        ifSetEvents(GeComponents.details_status, 0..1, IfEvent.Op1)
        ifSetEvents(
            GeComponents.details_collect,
            2..3,
            IfEvent.Op1,
            IfEvent.Op2,
            IfEvent.Op3,
            IfEvent.Op10,
        )
        ifSetEvents(GeComponents.side_items, 0..27, IfEvent.Op1, IfEvent.Op10)
    }

    private fun Player.onClose() {
        mesLayerClose(this, constants.meslayer_mode_objsearch)
        for (slot in 0 until GeSlot.SLOT_COUNT) {
            stopInvTransmit(collectInv(slot))
        }
        setVar(GeVarBits.selected_slot, 0)
    }

    /* Navigation. */

    /** Selects [slot] (-1 = back to the index) and clears the setup panel. */
    private fun ProtectedAccess.selectSlot(slot: Int, buy: Boolean = true) {
        player.resetSetup(buy)
        player.setVar(GeVarBits.selected_slot, slot + 1)
    }

    private val Player.selectedSlot: Int
        get() = vars[GeVarBits.selected_slot] - 1

    private suspend fun ProtectedAccess.indexOp(slot: Int, comsub: Int, op: IfButtonOp) {
        val offer = player.geSlots[slot]
        when (comsub) {
            GeConstants.INDEX_OFFER -> {
                if (offer != null && op == IfButtonOp.Op1) {
                    selectSlot(slot)
                    showDetails(offer)
                } else if (offer != null) {
                    mes("That offer has already completed.")
                }
            }
            GeConstants.INDEX_BUY -> {
                if (offer == null) {
                    selectSlot(slot, buy = true)
                    searchItem()
                }
            }
            GeConstants.INDEX_SELL -> {
                if (offer == null) {
                    selectSlot(slot, buy = false)
                }
            }
        }
    }

    /* Setup panel. */

    private suspend fun ProtectedAccess.searchItem() {
        val chosen =
            try {
                objDialog("What would you like to buy?")
            } catch (_: ObjDialogCancelledException) {
                // Escape in the search: back to the offers index with nothing set up.
                if (player.ui.containsModal(GeInterfaces.offers)) {
                    selectSlot(-1)
                }
                return
            }
        if (player.selectedSlot !in 0 until GeSlot.SLOT_COUNT || !player.isBuying) {
            return
        }
        setItem(chosen)
    }

    private val Player.isBuying: Boolean
        get() = vars[GeVarBits.newoffer_type] == 0

    private fun ProtectedAccess.setItem(chosen: UnpackedObjType) {
        val type = objTypes.uncert(chosen)
        if (!type.stockmarket) {
            mes("You can't trade that item on the Grand Exchange.")
            return
        }
        val price = marketPrice(type)
        player.setVar(GeVarps.newoffer_obj, type.id)
        player.setVar(GeVarps.last_searched, type.id)
        player.setVar(GeVarps.newoffer_price, price)
        val quantity = if (player.isBuying) 1 else available(type).coerceAtLeast(1)
        player.setVar(GeVarBits.newoffer_quantity, quantity)
        ifSetText(GeComponents.setup_marketprice, "%,d coins".format(price))
        runClientScript(
            GeConstants.SETDESC_SCRIPT,
            type.desc,
            "",
            GeComponents.setup_desc.packed,
            GeComponents.setup_fee.packed,
        )
    }

    /** Details panel text for a finished offer: description and the price it traded at. */
    private fun ProtectedAccess.showDetails(offer: GeSlot) {
        val type = objTypes[offer.obj] ?: return
        ifSetText(GeComponents.details_marketprice, "%,d coins".format(offer.price))
        runClientScript(
            GeConstants.SETDESC_SCRIPT,
            type.desc,
            "",
            GeComponents.details_desc.packed,
            GeComponents.details_fee.packed,
        )
    }

    private suspend fun ProtectedAccess.setupOp(comsub: Int, op: IfButtonOp) {
        if (player.selectedSlot !in 0 until GeSlot.SLOT_COUNT) {
            return
        }
        val obj = player.vars[GeVarps.newoffer_obj]
        if (comsub == GeConstants.SETUP_CHOOSE_ITEM) {
            if (player.isBuying) {
                searchItem()
            } else {
                mes("Choose an item from your inventory to sell.")
            }
            return
        }
        if (obj == -1) {
            return
        }
        val type = objTypes[obj] ?: return
        when (comsub) {
            GeConstants.SETUP_QTY_MINUS_1 -> changeQuantity(type, -1)
            GeConstants.SETUP_QTY_PLUS_1_SMALL,
            GeConstants.SETUP_QTY_PLUS_1 -> changeQuantity(type, 1)
            GeConstants.SETUP_QTY_PLUS_10 -> changeQuantity(type, 10)
            GeConstants.SETUP_QTY_PLUS_100 -> changeQuantity(type, 100)
            GeConstants.SETUP_QTY_1K_OR_ALL -> changeQuantity(type, Int.MAX_VALUE)
            GeConstants.SETUP_QTY_ENTER -> {
                if (op == IfButtonOp.Op2 && player.isBuying) {
                    buyAll()
                } else {
                    enterQuantity(type)
                }
            }
            in GeConstants.SETUP_PRICE_FIRST..GeConstants.SETUP_PRICE_LAST -> {
                // Offers here always trade at the market price: undo the client's local change.
                player.setVar(GeVarps.newoffer_price, marketPrice(type))
                mes("Offers on this server always trade at the guide price.")
            }
        }
    }

    /** Server copy of the client's `ge_offers_setup_changequantity`. */
    private fun ProtectedAccess.changeQuantity(type: UnpackedObjType, requested: Int) {
        var step = requested
        val current = player.vars[GeVarBits.newoffer_quantity]
        val next: Int
        if (player.isBuying) {
            if (step == Int.MAX_VALUE) {
                step = 1000
            }
            if (step > 1 && current == 1) {
                step -= 1
            }
            next =
                if (step < 0) {
                    if (current <= -step) 1 else current + step
                } else {
                    (current.toLong() + step).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
        } else {
            val avail = available(type).coerceAtLeast(1)
            next =
                when {
                    step == Int.MAX_VALUE -> avail
                    step > 0 -> {
                        if (step > 1 && current == 1) {
                            step -= 1
                        }
                        if (avail - step < current) avail else current + step
                    }
                    else -> if (current <= -step) 1 else current + step
                }
        }
        player.setVar(GeVarBits.newoffer_quantity, next.coerceAtLeast(1))
    }

    private suspend fun ProtectedAccess.enterQuantity(type: UnpackedObjType) {
        val verb = if (player.isBuying) "buy" else "sell"
        val entered = countDialog("How many do you wish to $verb?")
        if (entered <= 0) {
            player.resyncVar(GeVarBits.newoffer_quantity)
            return
        }
        val quantity = if (player.isBuying) entered else minOf(entered, available(type))
        player.setVar(GeVarBits.newoffer_quantity, quantity.coerceAtLeast(1))
    }

    /** Server copy of `script5156` ("All" when buying): as many as the coins carried can pay. */
    private fun ProtectedAccess.buyAll() {
        val price = player.vars[GeVarps.newoffer_price]
        val coins = invTotal(inv, objs.coins)
        val quantity = if (price > 0) maxOf(1, coins / price) else 1
        player.setVar(GeVarBits.newoffer_quantity, quantity)
    }

    /** Snapshot mid price; objs without market data fall back to their store value. */
    private fun marketPrice(type: UnpackedObjType): Int = (prices[type] ?: type.cost).coerceAtLeast(1)

    private fun ProtectedAccess.feeInfo() {
        mes("There is no Grand Exchange tax on this server.")
    }

    /* Selling from the inventory side panel. */

    private fun ProtectedAccess.sideItemOp(invSlot: Int, op: IfButtonOp) {
        if (op != IfButtonOp.Op1) {
            return
        }
        val obj = inv[invSlot] ?: return
        val selected = player.selectedSlot
        val slot =
            if (selected in 0 until GeSlot.SLOT_COUNT && player.geSlots[selected] == null) {
                selected
            } else {
                player.geSlots.indexOfFirst { it == null }
            }
        if (slot == -1) {
            mes("You have no free Grand Exchange slots.")
            return
        }
        val type = objTypes.uncert(objTypes[obj.id] ?: return)
        if (!type.stockmarket) {
            mes("You can't trade that item on the Grand Exchange.")
            return
        }
        selectSlot(slot, buy = false)
        setItem(type)
    }

    /** Held amount of [type] counting both its noted and unnoted forms. */
    private fun ProtectedAccess.available(type: UnpackedObjType): Int {
        val base = objTypes.uncert(type)
        val noted = objTypes.cert(base)
        var total = invTotal(inv, base).toLong()
        if (noted.id != base.id) {
            total += invTotal(inv, noted)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /* Confirm: validate everything (the button transmits even when the client shows "---"). */

    private fun ProtectedAccess.confirm() {
        val slot = player.selectedSlot
        if (slot !in 0 until GeSlot.SLOT_COUNT || player.geSlots[slot] != null) {
            return
        }
        val obj = player.vars[GeVarps.newoffer_obj]
        val quantity = player.vars[GeVarBits.newoffer_quantity]
        if (obj == -1) {
            mes("You must choose an item first.")
            return
        }
        if (quantity < 1) {
            mes("You must choose a quantity first.")
            return
        }
        val type = objTypes.uncert(objTypes[obj] ?: return)
        if (!type.stockmarket) {
            mes("You can't trade that item on the Grand Exchange.")
            return
        }
        val price = marketPrice(type)
        val total = price.toLong() * quantity
        if (total > Int.MAX_VALUE) {
            mes("That offer is too large: one offer can trade at most 2,147,483,647 coins.")
            return
        }
        val offer: GeSlot? =
            if (player.isBuying) {
                buy(type, quantity, price, total.toInt())
            } else {
                sell(type, quantity, price, total.toInt())
            }
        if (offer == null) {
            return
        }
        player.geSlots[slot] = offer
        player.refreshSlot(slot)
        selectSlot(-1)
        val verb = if (offer.buy) "buying" else "selling"
        mes("Grand Exchange: Finished $verb ${"%,d".format(quantity)} x ${type.name}.")
        player.setVar(GeVarps.last_offer_quantity, quantity)
        player.setVar(GeVarps.last_offer_type, if (offer.buy) 0 else 1)
        player.setVar(GeVarps.last_offer_item, type.id)
    }

    private fun ProtectedAccess.buy(
        type: UnpackedObjType,
        quantity: Int,
        price: Int,
        total: Int,
    ): GeSlot? {
        if (invTotal(inv, objs.coins) < total) {
            mes("You don't have enough coins.")
            return null
        }
        val removed = invDel(inv, objs.coins, total)
        if (removed.failure) {
            mes("You don't have enough coins.")
            return null
        }
        return GeSlot(
            buy = true,
            obj = type.id,
            count = quantity,
            price = price,
            collectObj = type.id,
            collectCount = quantity,
        )
    }

    private fun ProtectedAccess.sell(
        type: UnpackedObjType,
        quantity: Int,
        price: Int,
        total: Int,
    ): GeSlot? {
        if (available(type) < quantity) {
            mes("You don't have that many to sell.")
            return null
        }
        val noted = objTypes.cert(type)
        var remaining = quantity
        val unnotedHeld = invTotal(inv, type)
        if (unnotedHeld > 0) {
            val take = minOf(unnotedHeld, remaining)
            if (invDel(inv, type, take, strict = true).failure) {
                return null
            }
            remaining -= take
        }
        if (remaining > 0 && noted.id != type.id) {
            if (invDel(inv, noted, remaining, strict = true).failure) {
                mes("You don't have that many to sell.")
                return null
            }
        }
        return GeSlot(
            buy = false,
            obj = type.id,
            count = quantity,
            price = price,
            collectObj = objs.coins.id,
            collectCount = total,
        )
    }

    /* Collection. */

    private fun ProtectedAccess.collectAllOp(comsub: Int, op: IfButtonOp) {
        when (comsub) {
            GeConstants.COLLECT_ALL -> {
                collectAll(toBank = op == IfButtonOp.Op2)
            }
            GeConstants.REPEAT_OFFER -> repeatOffer()
        }
    }

    /**
     * Repeat Offer: set up the last confirmed offer again in the first free slot (item, quantity
     * and buy/sell; the price is the market price as always). The player still clicks Confirm.
     */
    private fun ProtectedAccess.repeatOffer() {
        val obj = player.vars[GeVarps.last_offer_item]
        if (obj <= 0) {
            return
        }
        val type = objTypes[obj] ?: return
        val slot = player.geSlots.indexOfFirst { it == null }
        if (slot == -1) {
            mes("You have no free Grand Exchange slots.")
            return
        }
        val buy = player.vars[GeVarps.last_offer_type] == 0
        selectSlot(slot, buy = buy)
        setItem(type)
        val wanted = player.vars[GeVarps.last_offer_quantity].coerceAtLeast(1)
        val quantity = if (buy) wanted else minOf(wanted, available(type)).coerceAtLeast(1)
        player.setVar(GeVarBits.newoffer_quantity, quantity)
    }

    /**
     * details_collect ops (client `ge_offers_detail_drawcollection`): certable items show
     * op1 "Collect-item"/op2 "Collect-note" for one item and op1 "Collect-notes"/op2 "Collect-items"
     * for several; coins and other non-certable objs only have op2 "Collect"; op3 is "Bank".
     */
    private fun ProtectedAccess.detailsCollectOp(comsub: Int, op: IfButtonOp) {
        val slot = player.selectedSlot
        val index =
            when (comsub) {
                GeConstants.DETAILS_COLLECT_ITEM -> 0
                GeConstants.DETAILS_COLLECT_COINS -> 1
                else -> return
            }
        collectItemOp(slot, index, op)
        if (player.geSlots.getOrNull(slot) == null) {
            selectSlot(-1)
        }
    }

    /** Collection box (`ge_collect`) slot item ops: same op meanings as the details panel. */
    private fun ProtectedAccess.collectionBoxOp(slot: Int, comsub: Int, op: IfButtonOp) {
        val index =
            when (comsub) {
                GeConstants.COLLECTBOX_ITEM -> 0
                GeConstants.COLLECTBOX_COINS -> 1
                else -> return
            }
        collectItemOp(slot, index, op)
    }

    private fun ProtectedAccess.collectItemOp(slot: Int, index: Int, op: IfButtonOp) {
        val offer = player.geSlots.getOrNull(slot) ?: return
        if (index != offer.collectIndex) {
            return
        }
        when (op) {
            IfButtonOp.Op1 -> collect(slot, toBank = false, noted = offer.collectCount > 1)
            IfButtonOp.Op2 -> collect(slot, toBank = false, noted = offer.collectCount == 1)
            IfButtonOp.Op3 -> collect(slot, toBank = true, noted = false)
            IfButtonOp.Op10 -> mes(objTypes[offer.collectObj]?.desc ?: return)
            else -> return
        }
    }

    private fun ProtectedAccess.collectAll(toBank: Boolean) {
        for (slot in 0 until GeSlot.SLOT_COUNT) {
            collect(slot, toBank = toBank, noted = true)
        }
    }

    /* Collection box (interface 402): opened by GE booth "Collect" and by bankers' "Collect". */

    private fun ProtectedAccess.openCollectionBox() {
        ifOpenMainModal(GeInterfaces.collect)
    }

    private fun Player.onCollectionBoxOpen() {
        for (slot in 0 until GeSlot.SLOT_COUNT) {
            refreshSlot(slot)
            startInvTransmit(collectInv(slot))
            ifSetEvents(
                GeComponents.collectbox_slots[slot],
                GeConstants.COLLECTBOX_ITEM..GeConstants.COLLECTBOX_COINS,
                IfEvent.Op1,
                IfEvent.Op2,
                IfEvent.Op3,
                IfEvent.Op10,
            )
        }
    }

    private fun Player.onCollectionBoxClose() {
        for (slot in 0 until GeSlot.SLOT_COUNT) {
            stopInvTransmit(collectInv(slot))
        }
    }

    /**
     * Moves slot [slot]'s collection to the inventory (as notes if [noted] and certable), spilling
     * into the bank when the inventory is full; [toBank] sends it all to the bank. Whatever fits
     * nowhere stays in the slot.
     */
    private fun ProtectedAccess.collect(slot: Int, toBank: Boolean, noted: Boolean) {
        val offer = player.geSlots[slot] ?: return
        if (!offer.hasCollection) {
            player.geSlots[slot] = null
            player.refreshSlot(slot)
            return
        }
        val base = objTypes.uncert(objTypes[offer.collectObj] ?: return)
        var remaining = offer.collectCount
        if (!toBank) {
            remaining -= addToInv(base, remaining, noted)
        }
        if (remaining > 0) {
            if (invAdd(bank, base, remaining, strict = true).success) {
                remaining = 0
                if (!toBank) {
                    mes("Your inventory was full, so the rest went to your bank.")
                }
            }
        }
        if (remaining > 0) {
            mes("You don't have enough space to collect everything.")
        }
        player.geSlots[slot] =
            if (remaining > 0) offer.copy(collectCount = remaining) else null
        player.refreshSlot(slot)
    }

    /** Adds up to [count] of [base] to the inventory; returns how many were added. */
    private fun ProtectedAccess.addToInv(base: UnpackedObjType, count: Int, noted: Boolean): Int {
        val type = if (noted && count > 1 && base.canCert) objTypes.cert(base) else base
        val fits =
            if (type.isStackable) {
                if (invTotal(inv, type) > 0 || inv.hasFreeSpace()) {
                    val held = invTotal(inv, type).toLong()
                    (Int.MAX_VALUE - held).coerceAtMost(count.toLong()).toInt()
                } else {
                    0
                }
            } else {
                minOf(count, inv.freeSpace())
            }
        if (fits <= 0) {
            return 0
        }
        return if (invAdd(inv, type, fits, strict = true).success) fits else 0
    }

    /* Slot state -> client: the stockmarket slot packet and the slot's collect inv. */

    private val GeSlot.collectIndex: Int
        get() = if (buy) 0 else 1

    private fun Player.refreshSlot(slot: Int) {
        val offer = geSlots[slot]
        val inv = collectInv(slot)
        for (index in inv.indices) {
            if (inv[index] != null) {
                inv[index] = null
            }
        }
        if (offer == null) {
            StockMarket.resetSlot(this, slot)
            return
        }
        val status =
            StockMarket.STATE_FINISHED or if (offer.buy) 0 else StockMarket.STATE_SELL_FLAG
        val gold = (offer.price.toLong() * offer.count).coerceAtMost(Int.MAX_VALUE.toLong())
        StockMarket.setSlot(
            player = this,
            slot = slot,
            status = status,
            obj = offer.obj,
            price = offer.price,
            count = offer.count,
            completedCount = offer.count,
            completedGold = gold.toInt(),
        )
        if (offer.hasCollection) {
            val type = objTypes[offer.collectObj] ?: return
            inv[offer.collectIndex] = InvObj(type, offer.collectCount)
        }
    }

    private fun Player.collectInv(slot: Int): Inventory =
        invMap.getOrPut(invTypes[GeInvs.collect[slot]])

    /** Collect invs are rebuilt from [Player.geSlots] on open; drop any persisted copy. */
    private fun Player.clearCollectInvs() {
        for (ref in GeInvs.collect) {
            val inv = invMap[invTypes[ref]] ?: continue
            for (index in inv.indices) {
                if (inv[index] != null) {
                    inv[index] = null
                }
            }
        }
    }

    private fun Player.resetSetup(buy: Boolean) {
        setVar(GeVarps.newoffer_obj, -1)
        setVar(GeVarBits.newoffer_type, if (buy) 0 else 1)
        setVar(GeVarBits.newoffer_quantity, 0)
        setVar(GeVarps.newoffer_price, 0)
    }

    /*
     * Var writes always transmit: the client changes several of these locally, so a "skip if
     * unchanged" write could leave it showing a different value from the server.
     */

    private fun Player.setVar(varp: VarpType, value: Int) {
        VarPlayerIntMapSetter.set(this, varp, value)
        if (varp.id == GeVarps.newoffer_price.id) {
            // Rev 243: the setup panel reads the price as a long varp (`tostring_spacer_long`).
            VarpSync.writeVarpLong(this, varp, vars[varp].toLong())
        } else {
            VarpSync.writeVarp(this, varp, vars[varp])
        }
    }

    private fun Player.setVar(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(this, varbit, value)
        VarpSync.writeVarp(this, varbit.baseVar, vars[varbit.baseVar])
    }

    private fun Player.resyncVar(varbit: VarBitType) {
        VarpSync.writeVarp(this, varbit.baseVar, vars[varbit.baseVar])
    }
}
