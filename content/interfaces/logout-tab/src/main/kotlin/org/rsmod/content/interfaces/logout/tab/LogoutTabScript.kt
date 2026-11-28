package org.rsmod.content.interfaces.logout.tab

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.ifClose
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

class LogoutTabScript
@Inject
constructor(private val eventBus: EventBus, private val protectedAccess: ProtectedAccessLauncher) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(logout_components.logout) { player.requestLogout() }
    }

    private fun Player.requestLogout() {
        logger.info {
            "Logout button: $username protected=$isAccessProtected delayed=$isDelayed " +
                "modals=${ui.modals.values} coroutineSuspended=${activeCoroutine?.isSuspended}"
        }
        ifClose(eventBus)
        val launched = protectedAccess.launch(this) {
            if (isBusy2 && mapClock > preventLogoutUntil) {
                playerWalk(coords)
            }
            logOut()
        }
        if (!launched) {
            logger.info { "Logout refused (busy): $username modals=${ui.modals.values}" }
            mes("You can't log out right now. (Try ::unstuck.)")
        }
    }
}
