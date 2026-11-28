package org.rsmod.api.net.rsprot.handlers

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.locs.OpLocTV1
import net.rsprot.protocol.game.incoming.locs.OpLocTV2
import org.rsmod.api.net.rsprot.player.InterfaceEvents
import org.rsmod.api.player.interact.LocTInteractions
import org.rsmod.api.player.output.clearMapFlag
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.vars.ctrlMoveSpeed
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionLocT
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.movement.RouteRequestLoc
import org.rsmod.game.type.comp.ComponentTypeList
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.interf.InterfaceTypeList
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.ui.Component
import org.rsmod.map.CoordGrid

class OpLocTHandler
@Inject
constructor(
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val locRegistry: LocRegistry,
    private val componentTypes: ComponentTypeList,
    private val interfaceTypes: InterfaceTypeList,
    private val locInteractions: LocTInteractions,
) : MessageHandler<OpLocTV1> {
    private val logger = InlineLogger()

    override fun handle(player: Player, message: OpLocTV1): Unit =
        handleOpT(
            player,
            message.id,
            message.x,
            message.z,
            message.controlKey,
            Component(message.selectedInterfaceId, message.selectedComponentId),
            message.selectedSub,
            message.selectedObj,
        )

    /** Shared by [OpLocTV1] (16-bit loc ids) and [OpLocTV2] (int loc ids) since rev 243. */
    internal fun handleOpT(
        player: Player,
        id: Int,
        x: Int,
        z: Int,
        controlKey: Boolean,
        selectedComponent: Component,
        selectedSub: Int,
        selectedObj: Int,
    ) {
        if (player.isDelayed) {
            return
        }

        val coords = CoordGrid(x, z, player.level)
        val loc = locRegistry.findType(coords, id)
        if (loc == null) {
            player.clearMapFlag()
            player.clearPendingAction(eventBus)
            return
        }
        val type = locTypes[id] ?: return
        val interfaceType = interfaceTypes[selectedComponent]
        val componentType = componentTypes[selectedComponent]
        val objType = selectedObj.takeIf { it != -1 }?.let(objTypes::get)

        val isValidInterface =
            player.ui.containsOverlay(interfaceType) || player.ui.containsModal(interfaceType)
        if (!isValidInterface) {
            player.clearMapFlag()
            player.clearPendingAction(eventBus)
            return
        }

        val comsub = selectedSub

        val targetEnabled =
            InterfaceEvents.isEnabled(player.ui, componentType, comsub, IfEvent.TgtLoc)
        if (!targetEnabled) {
            player.clearMapFlag()
            player.clearPendingAction(eventBus)
            return
        }

        val speed = if (controlKey) player.ctrlMoveSpeed() else null
        val boundLoc = BoundLocInfo(loc, type)
        val opTrigger =
            locInteractions.hasOpTrigger(player, boundLoc, type, objType, componentType, comsub)
        val apTrigger =
            locInteractions.hasApTrigger(player, boundLoc, type, objType, componentType, comsub)
        val interaction =
            InteractionLocT(
                target = boundLoc,
                comsub = comsub,
                objType = objType,
                component = componentType,
                hasOpTrigger = opTrigger,
                hasApTrigger = apTrigger,
            )
        val routeRequest =
            RouteRequestLoc(
                destination = coords,
                width = type.width,
                length = type.length,
                shape = loc.entity.shape,
                angle = loc.entity.angle,
                forceApproachFlags = type.forceApproachFlags,
                clientRequest = true,
            )
        player.clearPendingAction(eventBus)
        player.resetFaceEntity()
        player.faceLoc(loc, type.width, type.length)
        player.interaction = interaction
        player.routeRequest = routeRequest
        player.tempMoveSpeed = speed
        logger.debug {
            "OpLocT: loc=$boundLoc, type=$type, comsub=$comsub, " +
                "component=$componentType, obj=$objType"
        }
    }
}


class OpLocTV2Handler @Inject constructor(private val delegate: OpLocTHandler) :
    MessageHandler<OpLocTV2> {
    override fun handle(player: Player, message: OpLocTV2): Unit =
        delegate.handleOpT(
            player,
            message.id,
            message.x,
            message.z,
            message.controlKey,
            Component(message.selectedInterfaceId, message.selectedComponentId),
            message.selectedSub,
            message.selectedObj,
        )
}
