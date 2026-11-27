package org.rsmod.content.generic.shops

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.shops.Shops
import org.rsmod.content.generic.shops.GenericShopData.ShopDef
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Opens the generated wiki shops from each shopkeeper's "Trade" op. Prices use the store's wiki
 * multipliers (sell/buy/delta, stored x10 like the `shop_*_percentage` npc params).
 */
class GenericShopsScript @Inject constructor(private val shops: Shops) : PluginScript() {
    override fun ScriptContext.startup() {
        for (shop in GenericShopData.shops) {
            for ((npcName, op) in shop.npcs) {
                val npc = GenericShopNpcs.byName.getValue(npcName)
                when (op) {
                    1 -> onOpNpc1(npc) { openShop(shop) }
                    2 -> onOpNpc2(npc) { openShop(shop) }
                    3 -> onOpNpc3(npc) { openShop(shop) }
                    4 -> onOpNpc4(npc) { openShop(shop) }
                    5 -> onOpNpc5(npc) { openShop(shop) }
                    else -> error("Invalid trade op $op for $npcName in ${shop.title}")
                }
            }
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
}
