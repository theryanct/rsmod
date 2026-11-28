package org.rsmod.content.skills.mining.scripts

import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid

/**
 * Per-rock ore replacements for quest rocks that share a standard rock type (e.g. Family Crest's
 * Witchaven Dungeon gold rocks give 'perfect' gold ore): the first override returning an obj for
 * the player and rock tile wins; with none, [Mining] gives the rock's normal ore.
 */
public object MiningOreOverrides {
    private val overrides = mutableListOf<(Player, CoordGrid) -> ObjType?>()

    public fun register(override: (Player, CoordGrid) -> ObjType?) {
        overrides += override
    }

    public fun ore(player: Player, rock: CoordGrid): ObjType? =
        overrides.firstNotNullOfOrNull { it(player, rock) }
}
