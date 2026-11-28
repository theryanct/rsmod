package org.rsmod.content.other.commands

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.ui.ifClose
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Rev 243 cutover diagnostics: `::debugstate` reports what makes the player "busy" (which blocks
 * logout and most buttons), `::unstuck` clears it (cancels a suspended dialog coroutine and closes
 * modals), as a workaround while the cause is found.
 */
class DebugStateCommands @Inject constructor(private val eventBus: EventBus) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("debugstate", "Show why you count as busy", ::debugState)
        onCommand("unstuck", "Cancel a stuck dialog and close modals", ::unstuck)
    }

    private fun debugState(cheat: Cheat) =
        with(cheat) {
            val state = player.describeState()
            logger.info { "debugstate ${player.username}: $state" }
            player.mes(state)
        }

    private fun unstuck(cheat: Cheat) =
        with(cheat) {
            val before = player.describeState()
            player.cancelActiveCoroutine()
            player.ifClose(eventBus)
            logger.info { "unstuck ${player.username}: before=$before" }
            player.mes("Cleared dialogs and modals. Before: $before")
        }
}

internal fun Player.describeState(): String =
    "protected=$isAccessProtected delayed=$isDelayed modals=$modalsDescription " +
        "coroutine=${activeCoroutine?.let { if (it.isSuspended) "suspended" else "idle" } ?: "none"}"

/** Open modal interface ids (target component -> interface) and pending closes. */
private val Player.modalsDescription: String
    get() =
        ui.modals.entries().joinToString(prefix = "[", postfix = "]") {
            "${it.key ushr 16}:${it.key and 0xFFFF}->${it.value}"
        } + " closing=${ui.closeQueue}"
