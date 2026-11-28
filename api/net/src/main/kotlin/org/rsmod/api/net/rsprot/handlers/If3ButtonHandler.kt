package org.rsmod.api.net.rsprot.handlers

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.buttons.If3Button
import org.rsmod.annotations.InternalApi
import org.rsmod.api.net.rsprot.player.InterfaceEvents
import org.rsmod.api.player.input.PauseButtonOps
import org.rsmod.api.player.input.ResumePauseButtonInput
import org.rsmod.api.player.output.GameplayLog
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.IfModalButton
import org.rsmod.api.player.ui.IfOverlayButton
import org.rsmod.api.player.ui.ifCloseInputDialog
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentTypeList
import org.rsmod.game.type.comp.UnpackedComponentType
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.interf.InterfaceTypeList
import org.rsmod.game.type.interf.UnpackedInterfaceType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.ui.Component
import org.rsmod.game.ui.UserInterface

private val CLIENT_HANDLED = listOf("settings:searchbar_image")
private val STONE = Regex("""^toplevel[a-z_]*:stone\d+$""")

class If3ButtonHandler
@Inject
constructor(
    private val eventBus: EventBus,
    private val interfaceTypes: InterfaceTypeList,
    private val componentTypes: ComponentTypeList,
    private val objTypes: ObjTypeList,
    private val protectedAccess: ProtectedAccessLauncher,
) : MessageHandler<If3Button> {
    private val logger = InlineLogger()

    private val If3Button.asComponent: Component
        get() = Component(interfaceId, componentId)

    private val If3Button.buttonOp: IfButtonOp
        get() =
            when (op) {
                1 -> IfButtonOp.Op1
                2 -> IfButtonOp.Op2
                3 -> IfButtonOp.Op3
                4 -> IfButtonOp.Op4
                5 -> IfButtonOp.Op5
                6 -> IfButtonOp.Op6
                7 -> IfButtonOp.Op7
                8 -> IfButtonOp.Op8
                9 -> IfButtonOp.Op9
                10 -> IfButtonOp.Op10
                else -> throw NotImplementedError("Unhandled If3Button op: $this")
            }

    @OptIn(InternalApi::class)
    override fun handle(player: Player, message: If3Button) {
        val componentType = componentTypes[message.asComponent]
        val interfaceType = interfaceTypes[message.asComponent]
        val comsub = message.sub
        val buttonOp = message.buttonOp
        GameplayLog.input(player) {
            "button=${componentType.internalName ?: message.asComponent} sub=$comsub " +
                "op=${buttonOp.slot} obj=${message.obj}"
        }

        val opEnabled =
            InterfaceEvents.isEnabled(player.ui, componentType, comsub, buttonOp.toIfEvent())
        if (!opEnabled) {
            GameplayLog.unhandled(player) {
                "button-disabled=${componentType.internalName ?: message.asComponent} " +
                    "sub=$comsub op=${buttonOp.slot}"
            }
            return
        }

        if (resumePauseButtonOp(player, componentType, interfaceType, buttonOp)) {
            return
        }

        if (player.ui.containsOverlay(interfaceType) || player.ui.containsTopLevel(interfaceType)) {
            val event =
                IfOverlayButton(player, componentType, comsub, objTypes[message.obj], buttonOp)
            logger.debug { "[Overlay] If3Button: $message (event=$event)" }
            if (!eventBus.publish(event) && !componentType.isClientHandled()) {
                GameplayLog.unhandled(player) {
                    "overlay-button=${componentType.internalName} sub=$comsub op=${buttonOp.slot}"
                }
            }
            return
        }

        if (player.ui.containsModal(interfaceType)) {
            val event = IfModalButton(componentType, comsub, objTypes[message.obj], buttonOp)
            player.ifCloseInputDialog()
            if (player.isModalButtonProtected) {
                logger.debug { "[Modal][BLOCKED] If3Button: $message (event=$event)" }
                GameplayLog.refused(player) {
                    "modal-button=${componentType.internalName} delayed=${player.isDelayed} " +
                        "dialogSuspended=${player.activeCoroutine?.isSuspended}"
                }
                return
            }
            logger.debug { "[Modal] If3Button: $message (event=$event)" }
            protectedAccess.launchLenient(player) {
                if (!eventBus.publish(this, event)) {
                    GameplayLog.unhandled(player) {
                        "modal-button=${componentType.internalName} sub=$comsub op=${buttonOp.slot}"
                    }
                }
            }
        }
    }

    /**
     * An op registered in [PauseButtonOps] (e.g. skillmulti "Make-5") answers the dialog that is
     * waiting on that component, like a pause-button click would.
     */
    private fun resumePauseButtonOp(
        player: Player,
        component: UnpackedComponentType,
        interfaceType: UnpackedInterfaceType,
        op: IfButtonOp,
    ): Boolean {
        val sub = PauseButtonOps.resumeSub(component, op.slot) ?: return false
        if (player.activeCoroutine?.isAwaiting(ResumePauseButtonInput::class) != true) {
            return false
        }
        val modal = player.ui.modals.getComponent(UserInterface(interfaceType))
        if (modal != null) {
            player.ui.queueClose(modal)
        }
        player.resumeActiveCoroutine(ResumePauseButtonInput(component, sub))
        return true
    }

    /**
     * Overlay buttons the client handles itself (side-panel tab stones, the All Settings search
     * box): the transmit is informational, so an unbound one is not missing content.
     */
    private fun UnpackedComponentType.isClientHandled(): Boolean {
        val name = internalName ?: return false
        return CLIENT_HANDLED.any { name.startsWith(it) } || STONE.matches(name)
    }

    private fun IfButtonOp.toIfEvent(): IfEvent =
        when (this) {
            IfButtonOp.Op1 -> IfEvent.Op1
            IfButtonOp.Op2 -> IfEvent.Op2
            IfButtonOp.Op3 -> IfEvent.Op3
            IfButtonOp.Op4 -> IfEvent.Op4
            IfButtonOp.Op5 -> IfEvent.Op5
            IfButtonOp.Op6 -> IfEvent.Op6
            IfButtonOp.Op7 -> IfEvent.Op7
            IfButtonOp.Op8 -> IfEvent.Op8
            IfButtonOp.Op9 -> IfEvent.Op9
            IfButtonOp.Op10 -> IfEvent.Op10
            IfButtonOp.Op11 -> IfEvent.Op11
            IfButtonOp.Op12 -> IfEvent.Op12
            IfButtonOp.Op13 -> IfEvent.Op13
            IfButtonOp.Op14 -> IfEvent.Op14
            IfButtonOp.Op15 -> IfEvent.Op15
            IfButtonOp.Op16 -> IfEvent.Op16
            IfButtonOp.Op17 -> IfEvent.Op17
            IfButtonOp.Op18 -> IfEvent.Op18
            IfButtonOp.Op19 -> IfEvent.Op19
            IfButtonOp.Op20 -> IfEvent.Op20
            IfButtonOp.Op21 -> IfEvent.Op21
            IfButtonOp.Op22 -> IfEvent.Op22
            IfButtonOp.Op23 -> IfEvent.Op23
            IfButtonOp.Op24 -> IfEvent.Op24
            IfButtonOp.Op25 -> IfEvent.Op25
            IfButtonOp.Op26 -> IfEvent.Op26
            IfButtonOp.Op27 -> IfEvent.Op27
            IfButtonOp.Op28 -> IfEvent.Op28
            IfButtonOp.Op29 -> IfEvent.Op29
            IfButtonOp.Op30 -> IfEvent.Op30
            IfButtonOp.Op31 -> IfEvent.Op31
            IfButtonOp.Op32 -> IfEvent.Op32
        }
}
