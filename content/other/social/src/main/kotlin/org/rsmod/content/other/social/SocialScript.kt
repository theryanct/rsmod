package org.rsmod.content.other.social

import jakarta.inject.Inject
import org.rsmod.api.player.social.PlayerSocial
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Sends friend/ignore lists on login and keeps friends' online status current. */
class SocialScript @Inject constructor(private val social: PlayerSocial) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { social.onLogin(player) }
        onPlayerLogout { social.onLogout(player) }
    }
}
