package org.rsmod.content.interfaces.gameframe.script

import jakarta.inject.Inject
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.content.interfaces.gameframe.config.worldmap_components
import org.rsmod.content.interfaces.gameframe.config.worldmap_interfaces
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** World map button on the minimap orbs: toggles the floating world map. */
class WorldMapScript @Inject constructor(private val eventBus: EventBus) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(worldmap_components.orb_worldmap) { player.toggleWorldMap() }
        onIfOverlayButton(worldmap_components.close) { player.closeWorldMap() }
    }

    private fun Player.toggleWorldMap() {
        if (ui.containsOverlay(worldmap_interfaces.worldmap)) {
            closeWorldMap()
            return
        }
        // The floater layer is full-viewport (and remapped on fixed/resizable switches); the world
        // map's own `worldmap_init` centres and sizes its window inside it. `map_container` is the
        // 211x207 minimap slot, which made the map a tiny square.
        ifOpenOverlay(worldmap_interfaces.worldmap, eventBus)
    }

    private fun Player.closeWorldMap() {
        ifCloseOverlay(worldmap_interfaces.worldmap, eventBus)
    }
}
