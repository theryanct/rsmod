package org.rsmod.game.entity.player

/**
 * One completed Grand Exchange trade, newest first in [org.rsmod.game.entity.Player.geHistory].
 *
 * @param sell `true` for a sale, `false` for a purchase.
 * @param gold total coins paid or received.
 */
public data class GeHistoryEntry(val sell: Boolean, val obj: Int, val count: Int, val gold: Long) {
    public companion object {
        /** OSRS keeps the last 20 trades. */
        public const val MAX_ENTRIES: Int = 20
    }
}
