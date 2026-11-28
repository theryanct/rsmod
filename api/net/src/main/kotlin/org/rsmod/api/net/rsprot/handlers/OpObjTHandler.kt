package org.rsmod.api.net.rsprot.handlers

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.objs.OpObjT
import org.rsmod.api.net.rsprot.player.InterfaceEvents
import org.rsmod.api.player.events.interact.OpObjTEvent
import org.rsmod.api.player.output.clearMapFlag
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentTypeList
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.interf.InterfaceTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.ui.Component
import org.rsmod.map.CoordGrid

/**
 * Spell (or other interface target) on a ground obj. Validates the selected component like
 * [OpLocTHandler], then publishes [OpObjTEvent] for content scripts (e.g. Telekinetic Grab).
 */
class OpObjTHandler
@Inject
constructor(
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val objRegistry: ObjRegistry,
    private val componentTypes: ComponentTypeList,
    private val interfaceTypes: InterfaceTypeList,
) : MessageHandler<OpObjT> {
    private val logger = InlineLogger()

    private val OpObjT.asComponent: Component
        get() = Component(selectedInterfaceId, selectedComponentId)

    override fun handle(player: Player, message: OpObjT) {
        if (player.isDelayed) {
            return
        }
        val coords = CoordGrid(message.x, message.z, player.level)
        val obj =
            objRegistry.findAll(coords).firstOrNull {
                it.type == message.id && it.isVisibleTo(player)
            }
        val type = objTypes[message.id]
        if (obj == null || type == null) {
            player.clearMapFlag()
            return
        }
        val interfaceType = interfaceTypes[message.asComponent]
        val componentType = componentTypes[message.asComponent]
        val isValidInterface =
            player.ui.containsOverlay(interfaceType) || player.ui.containsModal(interfaceType)
        if (!isValidInterface) {
            player.clearMapFlag()
            return
        }
        val comsub = message.selectedSub
        if (!InterfaceEvents.isEnabled(player.ui, componentType, comsub, IfEvent.TgtObj)) {
            player.clearMapFlag()
            return
        }
        val selectedObj = message.selectedObj.takeIf { it != -1 }?.let(objTypes::get)
        player.clearPendingAction(eventBus)
        player.clearMapFlag()
        player.resetFaceEntity()
        player.faceSquare(coords)
        eventBus.publish(OpObjTEvent(player, obj, type, componentType, comsub, selectedObj))
        logger.debug { "OpObjT: obj=$obj, component=$componentType, comsub=$comsub" }
    }
}
