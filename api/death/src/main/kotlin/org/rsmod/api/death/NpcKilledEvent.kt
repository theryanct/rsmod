package org.rsmod.api.death

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player

/**
 * Published when [npc] dies from combat and [hero] is the player credited with the kill (the one
 * who receives its drops). Used by content such as Slayer to count kills.
 */
public class NpcKilledEvent(public val npc: Npc, public val hero: Player) : UnboundEvent
