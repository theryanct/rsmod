package org.rsmod.api.player.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.type.stat.StatType

/**
 * Published after [player] gains [xp] (base xp, before rates) in [stat] through
 * `ProtectedAccess.statAdvance` - one event per skilling action. Used by skilling pets.
 */
public class StatAdvanced(
    public val player: Player,
    public val stat: StatType,
    public val xp: Double,
) : UnboundEvent
