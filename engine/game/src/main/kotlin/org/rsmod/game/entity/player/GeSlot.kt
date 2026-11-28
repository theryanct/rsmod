package org.rsmod.game.entity.player

/**
 * One Grand Exchange offer slot. Offers on this local server fill instantly, so a slot only ever
 * holds a completed offer plus whatever is still waiting to be collected.
 *
 * @param buy `true` for a buy offer, `false` for a sell offer.
 * @param obj the obj id offered (uncerted).
 * @param count the quantity offered (always fully traded).
 * @param price the price per item the offer traded at.
 * @param collectObj obj id waiting in the slot's collection (bought obj, or coins for a sale), or
 *   -1 when nothing is left to collect.
 * @param collectCount amount of [collectObj] waiting.
 */
public data class GeSlot(
    val buy: Boolean,
    val obj: Int,
    val count: Int,
    val price: Int,
    val collectObj: Int,
    val collectCount: Int,
) {
    val hasCollection: Boolean
        get() = collectObj != -1 && collectCount > 0

    public companion object {
        public const val SLOT_COUNT: Int = 8
    }
}
