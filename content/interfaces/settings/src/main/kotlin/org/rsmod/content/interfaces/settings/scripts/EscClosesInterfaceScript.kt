package org.rsmod.content.interfaces.settings.scripts

import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.interfaces.settings.configs.setting_varbits
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Turns "Esc closes the current interface" on at every login.
 *
 * The rev 233 All Settings toggles flip their varbit client-side only (`settings_clicked_toggle`),
 * and the toggle's comsub is its position in the currently drawn list (category, search, filters),
 * so the server can't tell which setting was clicked or store it. Without this the client starts
 * every session with the server's 0.
 */
class EscClosesInterfaceScript : PluginScript() {
    private var Player.escClosesInterface by boolVarBit(setting_varbits.esc_closes_interface)

    override fun ScriptContext.startup() {
        onPlayerLogin {
            player.escClosesInterface = true
            // The delegate may skip the write when the value is unchanged; always send it.
            player.resyncVar(setting_varbits.esc_closes_interface)
        }
    }
}
