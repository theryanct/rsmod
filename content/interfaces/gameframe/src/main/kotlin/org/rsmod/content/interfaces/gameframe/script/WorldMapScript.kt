package org.rsmod.content.interfaces.gameframe.script

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.content.interfaces.gameframe.config.worldmap_components
import org.rsmod.content.interfaces.gameframe.config.worldmap_interfaces
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * World map button on the minimap orbs: toggles the floating world map.
 *
 * The client only knows where the player is on the map through `%worldmap_youarehere`, which the
 * server sets with clientscript `worldmap_transmitdata` (1749: you-are-here, clue, gravestone
 * coords). `worldmap_init` uses it to pick the map and draw the "You are here" marker, and
 * `worldmap_overlay` follows it, so it is sent on open and again whenever the player moves while
 * the map is open.
 */
class WorldMapScript @Inject constructor(private val eventBus: EventBus) : PluginScript() {
    /** Last coord sent per player (weak keys drop on logout). */
    private val lastSent = WeakHashMap<Player, Int>()

    override fun ScriptContext.startup() {
        onIfOverlayButton(worldmap_components.orb_worldmap) { player.toggleWorldMap() }
        onIfOverlayButton(worldmap_components.close) { player.closeWorldMap() }
        onPlayerSoftTimer(WorldMapTimers.youarehere) { player.followPlayer() }
    }

    private fun Player.toggleWorldMap() {
        if (ui.containsOverlay(worldmap_interfaces.worldmap)) {
            closeWorldMap()
            return
        }
        // Set before the interface opens: worldmap_init reads it on load.
        transmitPosition()
        // The floater layer is full-viewport (and remapped on fixed/resizable switches); the world
        // map's own `worldmap_init` centres and sizes its window inside it. `map_container` is the
        // 211x207 minimap slot, which made the map a tiny square.
        ifOpenOverlay(worldmap_interfaces.worldmap, eventBus)
        softTimer(WorldMapTimers.youarehere, 1)
    }

    private fun Player.closeWorldMap() {
        ifCloseOverlay(worldmap_interfaces.worldmap, eventBus)
        clearSoftTimer(WorldMapTimers.youarehere)
    }

    private fun Player.followPlayer() {
        if (!ui.containsOverlay(worldmap_interfaces.worldmap)) {
            // Closed some other way (Esc key, logout reset, ...).
            clearSoftTimer(WorldMapTimers.youarehere)
            return
        }
        if (lastSent[this] != coords.packed) {
            transmitPosition()
        }
    }

    private fun Player.transmitPosition() {
        lastSent[this] = coords.packed
        runClientScript(TRANSMIT_DATA_SCRIPT, coords.packed, NO_COORD, NO_COORD)
    }

    private companion object {
        /** `[clientscript,worldmap_transmitdata](coord, coord, coord)`. */
        private const val TRANSMIT_DATA_SCRIPT = 1749
        private const val NO_COORD = -1
    }
}

internal object WorldMapTimers : TimerReferences() {
    /** Re-sends the player's map position while the world map is open (custom, 65300). */
    val youarehere = find("worldmap_youarehere")
}
