package org.rsmod.content.generic.shops

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.shops.Shops
import org.rsmod.content.generic.shops.GenericShopData.ShopDef
import org.rsmod.content.generic.shops.GenericShopData.VendorDef
import org.rsmod.content.generic.shops.GenericShopData.VendorItem
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.ShopGates
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Opens the generated wiki shops from each shopkeeper's "Trade" op (prices use the store's wiki
 * sell/buy/delta multipliers, stored x10 like the `shop_*_percentage` npc params), and runs a
 * small buy dialogue from the "Talk-to" op of dialogue vendors such as bartenders.
 */
class GenericShopsScript
@Inject
constructor(private val shops: Shops, private val npcTypes: NpcTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for (shop in GenericShopData.shops) {
            for ((npcName, op) in shop.npcs) {
                val npc = GenericShopNpcs.byName.getValue(npcName)
                onNpcOp(npc, op, shop.title) { target ->
                    if (with(ShopGates) { shopAllowed(target) }) openShop(shop)
                }
            }
        }
        for (vendor in GenericShopData.vendors) {
            val npc = GenericShopNpcs.byName.getValue(vendor.npc)
            // A Talk-to vendor goes through QuestTalk, so quests (e.g. the Barcrawl) can claim the
            // conversation; with no quest wanting it the vendor dialogue runs as before.
            if (vendor.op == 1 && npcTypes[npc].op.getOrNull(0).equals("Talk-to", ignoreCase = true)) {
                with(QuestTalk) { default(npcTypes, npc) { target -> vendorDialogue(target, vendor) } }
            } else {
                onNpcOp(npc, vendor.op, vendor.title) { target -> vendorDialogue(target, vendor) }
            }
        }
    }

    private fun ScriptContext.onNpcOp(
        npc: NpcType,
        op: Int,
        title: String,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        when (op) {
            1 -> onOpNpc1(npc) { this.action(it.npc) }
            2 -> onOpNpc2(npc) { this.action(it.npc) }
            3 -> onOpNpc3(npc) { this.action(it.npc) }
            4 -> onOpNpc4(npc) { this.action(it.npc) }
            5 -> onOpNpc5(npc) { this.action(it.npc) }
            else -> error("Invalid op $op for $npc in $title")
        }
    }

    private fun ProtectedAccess.openShop(shop: ShopDef) {
        shops.open(
            player = player,
            title = shop.title,
            shopInv = GenericShopInvs.byName.getValue(shop.inv),
            buyPercentage = shop.buyPercentage / 10.0,
            sellPercentage = shop.sellPercentage / 10.0,
            changePercentage = shop.changePercentage / 10.0,
        )
    }

    private suspend fun ProtectedAccess.vendorDialogue(npc: Npc, vendor: VendorDef) {
        val items = vendor.items
        val labels = items.map { "${it.label} - ${it.price} coin" + if (it.price == 1) "" else "s" }
        val none = "Nothing, thanks."
        var chosen = -1
        startDialogue(npc) {
            chatNpc(happy, "What can I get you?")
            chosen =
                when (labels.size) {
                    1 -> choice2(labels[0], 0, none, -1)
                    2 -> choice3(labels[0], 0, labels[1], 1, none, -1)
                    3 -> choice4(labels[0], 0, labels[1], 1, labels[2], 2, none, -1)
                    else -> choice5(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3, none, -1)
                }
            if (chosen < 0) {
                chatPlayer(neutral, none)
            }
        }
        if (chosen >= 0) {
            buy(npc, items[chosen])
        }
    }

    private suspend fun ProtectedAccess.buy(npc: Npc, item: VendorItem) {
        if (invTotal(player.inv, objs.coins) < item.price) {
            startDialogue(npc) { chatNpc(sad, "Sorry, you don't have enough coins for that.") }
            return
        }
        val obj = GenericShopObjs.byName.getValue(item.obj)
        val add = invAdd(player.inv, obj, count = 1)
        if (add.failure) {
            player.mes("You don't have enough inventory space.")
            return
        }
        val pay = invDel(player.inv, objs.coins, count = item.price)
        if (pay.failure) {
            invDel(player.inv, obj, count = 1)
            player.mes("You don't have enough coins.")
            return
        }
        startDialogue(npc) { chatNpc(happy, "There you go.") }
    }
}
