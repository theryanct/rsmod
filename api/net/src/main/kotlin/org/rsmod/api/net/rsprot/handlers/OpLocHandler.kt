package org.rsmod.api.net.rsprot.handlers

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.locs.OpLoc
import net.rsprot.protocol.game.incoming.locs.OpLocV3
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.output.clearMapFlag
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.vars.ctrlMoveSpeed
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionLocOp
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.movement.RouteRequestLoc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid

class OpLocHandler
@Inject
constructor(
    private val eventBus: EventBus,
    private val locTypes: LocTypeList,
    private val locRegistry: LocRegistry,
    private val locInteractions: LocInteractions,
) : MessageHandler<OpLoc> {
    private val logger = InlineLogger()

    private fun interactionOpOf(op: Int): InteractionOp =
        when (op) {
            1 -> InteractionOp.Op1
            2 -> InteractionOp.Op2
            3 -> InteractionOp.Op3
            4 -> InteractionOp.Op4
            5 -> InteractionOp.Op5
            else -> throw NotImplementedError("Unhandled `op` conversion: $op")
        }

    override fun handle(player: Player, message: OpLoc): Unit =
        handleOp(player, message.id, message.x, message.z, message.controlKey, message.op)

    /** Shared by [OpLoc] (16-bit loc ids) and [OpLocV3] (int loc ids) since rev 243. */
    internal fun handleOp(
        player: Player,
        id: Int,
        x: Int,
        z: Int,
        controlKey: Boolean,
        op: Int,
    ) {
        val interactionOp = interactionOpOf(op)
        if (player.isDelayed) {
            return
        }
        val coords = CoordGrid(x, z, player.level)
        val loc = locRegistry.findType(coords, id)
        if (loc == null) {
            player.clearMapFlag()
            return
        }
        val type = locTypes[id] ?: return
        val speed = if (controlKey) player.ctrlMoveSpeed() else null
        val boundLoc = BoundLocInfo(loc, type)
        val opTrigger = locInteractions.hasOpTrigger(player, boundLoc, interactionOp, type)
        val apTrigger = locInteractions.hasApTrigger(player, boundLoc, interactionOp, type)
        val interaction =
            InteractionLocOp(
                target = boundLoc,
                op = interactionOp,
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
        if (!locInteractions.hasOp(boundLoc, type, player.vars, interactionOp)) {
            logger.debug { "OpLoc invalid op blocked: op=${op}, loc=$boundLoc type=$type" }
            return
        }
        player.clearPendingAction(eventBus)
        player.resetFaceEntity()
        player.faceLoc(loc, type.width, type.length)
        player.interaction = interaction
        player.routeRequest = routeRequest
        player.tempMoveSpeed = speed
        logger.debug { "OpLoc: op=${op}, loc=$boundLoc type=$type" }
    }
}


class OpLocV3Handler @Inject constructor(private val delegate: OpLocHandler) :
    MessageHandler<OpLocV3> {
    override fun handle(player: Player, message: OpLocV3): Unit =
        delegate.handleOp(player, message.id, message.x, message.z, message.controlKey, message.op)
}
