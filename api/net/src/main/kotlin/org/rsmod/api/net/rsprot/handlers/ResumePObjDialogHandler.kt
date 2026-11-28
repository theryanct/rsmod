package org.rsmod.api.net.rsprot.handlers

import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.resumed.ResumePObjDialog
import org.rsmod.api.player.input.ObjDialogCancelledException
import org.rsmod.api.player.input.ResumePObjDialogInput
import org.rsmod.api.player.output.GameplayLog
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjTypeList

class ResumePObjDialogHandler @Inject constructor(private val objTypes: ObjTypeList) :
    MessageHandler<ResumePObjDialog> {
    override fun handle(player: Player, message: ResumePObjDialog) {
        if (player.activeCoroutine?.isAwaiting(ResumePObjDialogInput::class) != true) {
            GameplayLog.unhandled(player) { "resume-without-dialog=ResumePObjDialog $message" }
        }
        val objType = objTypes.getOrDefault(message.obj, null)
        if (objType == null) {
            // Escape in the obj search sends a null obj: end the paused dialog instead of leaving
            // the coroutine suspended forever.
            cancelObjDialog(player)
            return
        }
        val input = ResumePObjDialogInput(objType)
        player.resumeActiveCoroutine(input)
    }

    private fun cancelObjDialog(player: Player) {
        val coroutine = player.activeCoroutine ?: return
        if (!coroutine.isAwaiting(ResumePObjDialogInput::class)) {
            return
        }
        player.activeCoroutine = null
        coroutine.cancel(ObjDialogCancelledException())
    }
}
