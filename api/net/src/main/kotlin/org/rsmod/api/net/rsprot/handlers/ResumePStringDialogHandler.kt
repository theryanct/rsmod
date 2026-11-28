package org.rsmod.api.net.rsprot.handlers

import net.rsprot.protocol.game.incoming.resumed.ResumePStringDialog
import org.rsmod.api.player.input.ResumePStringDialogInput
import org.rsmod.api.player.output.GameplayLog
import org.rsmod.game.entity.Player

class ResumePStringDialogHandler : MessageHandler<ResumePStringDialog> {
    override fun handle(player: Player, message: ResumePStringDialog) {
        if (player.activeCoroutine?.isAwaiting(ResumePStringDialogInput::class) != true) {
            GameplayLog.unhandled(player) { "resume-without-dialog=ResumePStringDialog $message" }
        }
        val input = ResumePStringDialogInput(message.string)
        player.resumeActiveCoroutine(input)
    }
}
