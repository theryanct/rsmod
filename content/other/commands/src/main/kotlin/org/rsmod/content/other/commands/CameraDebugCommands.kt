package org.rsmod.content.other.commands

import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.game.cheat.Cheat
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rev 243 zoom diagnostics: `::zoom <fov> [fov_big]` sets the camera zoom directly through the
 * client's `camera_do_zoom` (cs 2234; 128 = zoomed out, 896 = zoomed in), and `::zoomlimits`
 * re-sends the zoom limits (cs 603). If `::zoom` moves the camera but the mouse wheel doesn't,
 * the wheel handler is the problem, not the limits.
 */
class CameraDebugCommands : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("zoom", "Set camera zoom (128-896)", ::zoom) {
            invalidArgs = "Use as ::zoom 512 (or ::zoom small big)"
        }
        onCommand("zoomlimits", "Re-send camera zoom limits (128-896)", ::zoomLimits)
    }

    private fun zoom(cheat: Cheat) =
        with(cheat) {
            val small = args.getOrNull(0)?.toIntOrNull()
            if (small == null) {
                player.mes("Use as ::zoom 512 (128 = out, 896 = in).")
                return
            }
            val big = args.getOrNull(1)?.toIntOrNull() ?: small
            player.runClientScript(CAMERA_DO_ZOOM, small, big)
            player.mes("Zoom set to $small/$big.")
        }

    private fun zoomLimits(cheat: Cheat) =
        with(cheat) {
            player.runClientScript(CAMERA_SET_ZOOM_LIMITS, MIN, MAX, MIN, MAX)
            player.mes("Zoom limits re-sent ($MIN-$MAX).")
        }

    private companion object {
        const val CAMERA_DO_ZOOM = 2234
        const val CAMERA_SET_ZOOM_LIMITS = 603
        const val MIN = 128
        const val MAX = 896
    }
}
