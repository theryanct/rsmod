package org.rsmod.api.combat.commons.slayer

import org.rsmod.game.entity.Player

/**
 * Answers "is this npc type on [Player]'s current slayer task?" for combat formulas (black mask /
 * slayer helmet accuracy and damage boosts). The Slayer content module installs the real check at
 * startup; until then (or without that module) nothing is ever on task.
 */
public object SlayerTaskResolver {
    @Volatile private var resolver: (Player, Int) -> Boolean = { _, _ -> false }

    public fun install(check: (player: Player, npcTypeId: Int) -> Boolean) {
        resolver = check
    }

    public fun isOnTask(player: Player, npcTypeId: Int): Boolean = resolver(player, npcTypeId)
}
