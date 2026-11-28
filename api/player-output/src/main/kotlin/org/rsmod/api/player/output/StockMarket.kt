package org.rsmod.api.player.output

import net.rsprot.protocol.game.outgoing.misc.player.UpdateStockMarketSlot
import org.rsmod.game.entity.Player

/**
 * Grand Exchange slot packets. Rev 233 only has `UpdateStockMarketSlot` (32-bit prices); after the
 * rev 243 cutover switch these to `UpdateStockMarketSlotV2`.
 */
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
            UpdateStockMarketSlot.SetStockMarketSlot(
                status,
                obj,
                price,
                count,
                completedCount,
                completedGold,
            )
        player.client.write(UpdateStockMarketSlot(slot, update))
    }

    public fun resetSlot(player: Player, slot: Int) {
        player.client.write(
            UpdateStockMarketSlot(slot, UpdateStockMarketSlot.ResetStockMarketSlot)
        )
    }
}
