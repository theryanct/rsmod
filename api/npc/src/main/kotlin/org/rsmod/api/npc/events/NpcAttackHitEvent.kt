package org.rsmod.api.npc.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType

/**
 * An npc's attack on [target] has rolled its [damage] and queued the hit (published by npc vs
 * player combat). Lets content add on-hit effects such as poison without touching combat.
 */
public data class NpcAttackHitEvent(
    public val npc: Npc,
    public val target: Player,
    public val type: HitType,
    public val damage: Int,
) : UnboundEvent
