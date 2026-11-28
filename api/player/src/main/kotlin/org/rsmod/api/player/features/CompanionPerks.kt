package org.rsmod.api.player.features

import org.rsmod.game.entity.Player

/**
 * Companion perks that skill scripts read (like `SlayerTaskResolver` for combat): the Companions
 * content module installs the real checks at startup; until then (or without that module) every
 * perk is off.
 */
public object CompanionPerks {
    @Volatile private var extraOre: (Player) -> Int = { 0 }

    /** [chance] returns the % chance (0-100) that a mined ore comes with one extra ore. */
    public fun installExtraOre(chance: (player: Player) -> Int) {
        extraOre = chance
    }

    /** % chance (0-100) that an ore mined by [player] gives one extra ore (Doric companion). */
    public fun extraOreChance(player: Player): Int = extraOre(player)
}
