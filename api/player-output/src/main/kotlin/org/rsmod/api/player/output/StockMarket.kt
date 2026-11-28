package org.rsmod.api.player.output

import net.rsprot.protocol.game.outgoing.misc.player.UpdateStockMarketSlotV2
import org.rsmod.game.entity.Player

/** Grand Exchange slot packets (rev 243 `UpdateStockMarketSlotV2`: 64-bit price and gold). */
public object StockMarket {
    /** Offer state bits read by the client (`stockmarket_*` commands): `state & 7`, sell = bit 3. */
    public const val STATE_FINISHED: Int = 5
    public const val STATE_SELL_FLAG: Int = 8

    public fun setSlot(
        player: Player,
        slot: Int,
        status: Int,
        obj: Int,
        price: Int,
        count: Int,
        completedCount: Int,
        completedGold: Int,
    ) {
        val update =
            UpdateStockMarketSlotV2.SetStockMarketSlot(
                status = status,
                obj = obj,
                price = price.toLong(),
                count = count,
                completedCount = completedCount,
                completedGold = completedGold.toLong(),
            )
        player.client.write(UpdateStockMarketSlotV2(slot, update))
    }

    public fun resetSlot(player: Player, slot: Int) {
        player.client.write(
            UpdateStockMarketSlotV2(slot, UpdateStockMarketSlotV2.ResetStockMarketSlot)
        )
    }
}
