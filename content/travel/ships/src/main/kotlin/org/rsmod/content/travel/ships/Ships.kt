package org.rsmod.content.travel.ships

import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Shared "pay, board, fade, arrive" ship travel used by charter ships (and, as world moves them
 * over, the Karamja/Corsair boats and other fixed-route ships).
 *
 * @param fare coins taken before sailing (0 = free).
 * @param message the boarding message ("You pay 400 coins and board the ship.").
 * @param fade black fade out/in around the jump (the OSRS charter/boat look); `false` keeps the
 *   short "The ship sails..." pause used by quest boats.
 * @param canSail quest or unlock gate checked first; it should tell the player why when it fails.
 * @return `true` once the player has arrived.
 */
public suspend fun ProtectedAccess.sailTo(
    dest: CoordGrid,
    fare: Int,
    message: String,
    fade: Boolean = true,
    canSail: (ProtectedAccess.() -> Boolean)? = null,
): Boolean {
    if (canSail != null && !canSail()) {
        return false
    }
    if (fare > 0) {
        if (invTotal(inv, objs.coins) < fare) {
            mes("You need $fare coins to sail there.")
            return false
        }
        invDel(inv, objs.coins, fare)
    }
    mes(message)
    if (!fade) {
        delay(SAIL_TICKS)
        telejump(dest)
        return true
    }
    // Fade to black, cross over, fade back in (canoe/charter timings).
    fadeOverlay(startColour = 0, startTransparency = 255, endColour = 0, endTransparency = 0, clientDuration = 50)
    delay(SAIL_TICKS)
    telejump(dest)
    delay(1)
    fadeOverlay(startColour = 0, startTransparency = 0, endColour = 0, endTransparency = 255, clientDuration = 50)
    closeFadeOverlay()
    mes("The ship arrives at its destination.")
    return true
}

/**
 * The walkable tile nearest [pin] within [radius] (Wiki map pins sit on docks but may land on a
 * rail or the water), or [pin] itself when nothing nearby is free.
 */
public fun CollisionFlagMap.nearestWalkable(pin: CoordGrid, radius: Int = 5): CoordGrid {
    for (r in 0..radius) {
        for (dx in -r..r) {
            for (dz in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) {
                    continue
                }
                val tile = pin.translate(dx, dz)
                if (this[tile.x, tile.z, tile.level] and BLOCKED == 0) {
                    return tile
                }
            }
        }
    }
    return pin
}

private const val SAIL_TICKS = 3
private const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
