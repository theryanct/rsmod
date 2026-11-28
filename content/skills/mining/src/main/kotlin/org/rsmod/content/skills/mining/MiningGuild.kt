package org.rsmod.content.skills.mining

import org.rsmod.api.config.refs.stats
import org.rsmod.api.stats.levelmod.InvisibleLevelMod
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.module.PluginModule

/**
 * Mining Guild members area (OSRS Wiki "Mining Guild", under Falador): an invisible +7 Mining and
 * rock respawns halved (runite 12 -> 6 minutes). Approximate bounding box of the underground guild
 * incl. the amethyst crystals (not from a cache area).
 */
internal object MiningGuild {
    private val X = 3008..3063
    private val Z = 9690..9760
    const val BOOST = 7

    fun contains(coords: CoordGrid): Boolean = coords.level == 0 && coords.x in X && coords.z in Z
}

class MiningGuildBoost : InvisibleLevelMod(stats.mining) {
    override fun Player.calculateBoost(): Int = if (MiningGuild.contains(coords)) MiningGuild.BOOST else 0
}

class MiningModule : PluginModule() {
    override fun bind() {
        addSetBinding<InvisibleLevelMod>(MiningGuildBoost::class.java)
    }
}
