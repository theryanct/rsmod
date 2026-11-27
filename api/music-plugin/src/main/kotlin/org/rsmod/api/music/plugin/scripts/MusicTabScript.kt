package org.rsmod.api.music.plugin.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.varps
import org.rsmod.api.music.plugin.configs.music_components
import org.rsmod.api.player.music.MusicPlayMode
import org.rsmod.api.player.music.MusicPlayer
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.enumVarp
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Music tab: track list (Play / Unlock hint) and the Area / Manual / Random mode buttons. */
public class MusicTabScript @Inject constructor(private val musicPlayer: MusicPlayer) :
    PluginScript() {
    private var Player.playMode by enumVarp<MusicPlayMode>(varps.musicplay)

    override fun ScriptContext.startup() {
        onIfOpen(interfaces.music) { player.onTabOpen() }
        onIfOverlayButton(music_components.jukebox) { player.selectTrack(comsub, op) }
        onIfOverlayButton(music_components.area) { player.selectMode(MusicPlayMode.Area) }
        onIfOverlayButton(music_components.manual) { player.selectMode(MusicPlayMode.Manual) }
        onIfOverlayButton(music_components.random) { player.selectMode(MusicPlayMode.Random) }
    }

    private fun Player.onTabOpen() {
        val lastIndex = musicPlayer.jukeboxSize - 1
        if (lastIndex >= 0) {
            ifSetEvents(music_components.jukebox, 0..lastIndex, IfEvent.Op1, IfEvent.Op2)
        }
    }

    private fun Player.selectTrack(listIndex: Int, op: IfButtonOp) {
        when (op) {
            IfButtonOp.Op1 -> {
                // Picking a track by hand switches the player to manual mode, like OSRS.
                if (musicPlayer.playJukeboxEntry(this, listIndex)) {
                    playMode = MusicPlayMode.Manual
                }
            }
            IfButtonOp.Op2 -> musicPlayer.sendJukeboxHint(this, listIndex)
            else -> {}
        }
    }

    private fun Player.selectMode(mode: MusicPlayMode) {
        if (playMode == mode) {
            return
        }
        playMode = mode
        if (mode != MusicPlayMode.Manual) {
            musicPlayer.playNext(this)
        }
    }
}
