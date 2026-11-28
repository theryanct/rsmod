package org.rsmod.content.skills.woodcutting

import org.rsmod.api.config.refs.stats
import org.rsmod.api.stats.levelmod.InvisibleLevelMod
import org.rsmod.game.entity.Player

class WoodcuttingLevelBoosts : InvisibleLevelMod(stats.woodcutting) {
    override fun Player.calculateBoost(): Int {
        // Wiki "Woodcutting Guild": an invisible +7 inside the guild (Hosidius). Bounding box of the
        // guild grounds incl. the redwood platforms, approximate (not from a cache area).
        val c = coords
        val inGuild = c.x in GUILD_X && c.z in GUILD_Z
        // TODO: Each player chopping same tree provides +1 boost (capped at +10).
        return if (inGuild) GUILD_BOOST else 0
    }

    private companion object {
        const val GUILD_BOOST = 7
        val GUILD_X = 1562..1660
        val GUILD_Z = 3471..3519
    }
}
