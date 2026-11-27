package org.rsmod.api.music.plugin.configs

import org.rsmod.api.type.refs.comp.ComponentReferences

internal typealias music_components = MusicComponents

internal object MusicComponents : ComponentReferences() {
    val jukebox = find("music:jukebox")
    val area = find("music:area")
    val manual = find("music:manual")
    val random = find("music:random")
}
