package org.rsmod.api.player.stat

import org.rsmod.api.config.refs.stats
import org.rsmod.game.type.stat.StatType

/**
 * Local-server tweak: fixed per-skill xp rates.
 *
 * A skill listed here earns xp at the listed rate **instead of** the player's personal
 * [org.rsmod.game.entity.Player.xpRate] (it does not stack with it). Global rates (e.g. double xp
 * events) still apply on top. Content code keeps using OSRS base xp values; the override is applied
 * centrally in [statAdvance], so every xp source for the skill picks it up automatically.
 */
public object SkillXpRateOverrides {
    private val overrides: Map<StatType, Double> = mapOf(stats.prayer to 10.0)

    public fun rateFor(stat: StatType): Double? =
        overrides.entries.firstOrNull { it.key.isType(stat) }?.value
}
