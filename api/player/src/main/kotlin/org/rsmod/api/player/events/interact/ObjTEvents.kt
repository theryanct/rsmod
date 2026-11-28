package org.rsmod.api.player.events.interact

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.comp.UnpackedComponentType
import org.rsmod.game.type.obj.UnpackedObjType

/**
 * A player used an interface target (e.g. a spell such as Telekinetic Grab) on a ground [obj].
 *
 * Published by the `OpObjT` message handler once the selected component is open and allows
 * targeting ground objs. There is no route/approach step: scripts handle range themselves (spells
 * act from a distance), typically by launching a protected access for the [player].
 *
 * @param objType The type of the targeted ground [obj].
 * @param selectedObj The inventory obj selected with the component, if any.
 */
public data class OpObjTEvent(
    public val player: Player,
    public val obj: Obj,
    public val objType: UnpackedObjType,
    public val component: UnpackedComponentType,
    public val comsub: Int,
    public val selectedObj: UnpackedObjType?,
) : UnboundEvent
