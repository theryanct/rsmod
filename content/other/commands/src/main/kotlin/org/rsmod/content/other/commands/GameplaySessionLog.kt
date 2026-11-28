package org.rsmod.content.other.commands

import org.rsmod.api.player.output.GameplayLog
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Login/logout lines for `.data/logs/gameplay.log` (see [GameplayLog]). */
class GameplaySessionLog : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { GameplayLog.session(player) { "login" } }
        onPlayerLogout { GameplayLog.session(player) { "logout" } }
    }
}
