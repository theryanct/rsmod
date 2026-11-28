package org.rsmod.api.player.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player

/**
 * Asks the tool belt (content/interfaces/tool-belt) to open for [player]; published by the
 * inventory tab button's "Tool belt" op, which lives with the other stone ops in the bank module.
 */
public class ToolBeltOpenRequest(public val player: Player) : UnboundEvent
