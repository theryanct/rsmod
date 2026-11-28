package org.rsmod.content.interfaces.gameframe.script

import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rev 243 clamps the mouse-wheel zoom (`camera_do_zoom`) to the varcs
 * `camera_zoom_{small,big}_{min,max}`, which start at 0, so the wheel couldn't zoom at all. OSRS
 * sets them at login (`[clientscript,login]` -> `script605(128, 896, 128, 896)`); we do the same
 * with `camera_set_zoom_limits`, which also re-applies the current zoom.
 */
class CameraZoomScript : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin {
            player.runClientScript(
                CAMERA_SET_ZOOM_LIMITS,
                ZOOM_MIN,
                ZOOM_MAX,
                ZOOM_MIN,
                ZOOM_MAX,
            )
        }
    }

    private companion object {
        /** `[clientscript,camera_set_zoom_limits](small_min, small_max, big_min, big_max)`. */
        const val CAMERA_SET_ZOOM_LIMITS = 603
        const val ZOOM_MIN = 128
        const val ZOOM_MAX = 896
    }
}
