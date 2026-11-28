package org.rsmod.api.player.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player

/**
 * Publish when [player] obtains [count] of [obj] from a collection-log source that isn't an npc
 * drop (quest rewards, clue caskets, minigame purchases...). Objs not in the log are ignored.
 */
public class CollectionLogObtained(
    public val player: Player,
    public val obj: Int,
    public val count: Int = 1,
) : UnboundEvent
