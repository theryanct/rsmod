package org.rsmod.content.interfaces.gameframe.script

import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Login camera setup for rev 243. Rev 243 clamps the mouse-wheel zoom (`camera_do_zoom`) to the varcs
 * `camera_zoom_{small,big}_{min,max}`, which start at 0, so the wheel couldn't zoom at all. OSRS
 * sets them at login (`[clientscript,login]` -> `script605(128, 896, 128, 896)`); we do the same
 * with `camera_set_zoom_limits`, which also re-applies the current zoom.
 */
class CameraZoomScript : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin {
            player.clearStaleFovClamp()
            player.runClientScript(
                CAMERA_SET_ZOOM_LIMITS,
                ZOOM_MIN,
                ZOOM_MAX,
                ZOOM_MIN,
                ZOOM_MAX,
            )
        }
    }

    /**
     * 243 widened `fov_clamp` to bits 7-9 of `toplevel_temp` (and moved `chatmodal_unclamp` from
     * bit 9 to bit 28). Saves from 233 kept bit 9 set, which 243 reads as `fov_clamp = 4`: a
     * clamped cutscene camera, so the wheel and the zoom slider snap back. The camera is never
     * clamped at login, so clear it and send the varp.
     */
    private fun Player.clearStaleFovClamp() {
        if (vars[varbits.fov_clamp] == 0) {
            return
        }
        VarPlayerIntMapSetter.set(this, varbits.fov_clamp, 0)
        resyncVar(varbits.fov_clamp)
    }

    private companion object {
        /** `[clientscript,camera_set_zoom_limits](small_min, small_max, big_min, big_max)`. */
        const val CAMERA_SET_ZOOM_LIMITS = 603
        const val ZOOM_MIN = 128
        const val ZOOM_MAX = 896
    }
}
