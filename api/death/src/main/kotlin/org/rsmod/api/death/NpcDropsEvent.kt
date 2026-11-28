package org.rsmod.api.death

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.UnpackedObjType

/**
 * Published when [npc]'s wiki drop table has been rolled for [hero] (the player receiving the
 * drops), before they are spawned. [drops] are (type, count) pairs as rolled, before the "noted
 * drops" feature notes them. Used by the collection log.
 */
public class NpcDropsEvent(
    public val npc: Npc,
    public val hero: Player,
    public val drops: List<Pair<UnpackedObjType, Int>>,
) : UnboundEvent
