package org.rsmod.api.player.features

import kotlin.math.roundToInt
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.varp.VarpType

/**
 * Local pet-server feature toggles, saved per character in server-only varps (so they persist
 * through the normal varp save path and change live with no reboot).
 *
 * Each toggle is stored as two bits of `feature_flags`: `0` = use [Feature.defaultOn], `1` = on,
 * `2` = off. Per-skill xp multipliers live in one varp per skill, in hundredths; `0` means "not
 * set", which falls back to [skillXpDefaults] and then to [Player.xpRate].
 */
public enum class Feature(
    public val key: String,
    public val displayName: String,
    public val defaultOn: Boolean,
    internal val slot: Int,
) {
    InfiniteRun("run", "Infinite run energy", defaultOn = true, slot = 0),
    InfinitePrayer("prayer", "Infinite prayer", defaultOn = false, slot = 1),
    InfiniteHealth("health", "Infinite health", defaultOn = false, slot = 2),
    InfiniteRunes("runes", "Infinite runes", defaultOn = true, slot = 3),
    NoHomeTeleportCooldown("hometele", "No home teleport cooldown", defaultOn = true, slot = 4),
    AutoResumeGathering("autoresume", "Auto-resume gathering", defaultOn = true, slot = 5),
    NotedGathering("notedgather", "Noted gathering", defaultOn = true, slot = 6),
    NotedDrops("noteddrops", "Noted combat drops", defaultOn = true, slot = 7),
    BankAnywhere("bankanywhere", "Bank anywhere", defaultOn = true, slot = 8);

    public companion object {
        public fun forKey(key: String): Feature? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/**
 * Per-skill xp multiplier used when the player hasn't set one (replaces the old fixed
 * `SkillXpRateOverrides`; prayer = 10x per Ryan).
 */
private val skillXpDefaults: Map<StatType, Double> = mapOf(stats.prayer to 10.0)

/** The default multiplier for [stat] when the player hasn't set one, if any. */
public fun skillXpDefault(stat: StatType): Double? =
    skillXpDefaults.entries.firstOrNull { it.key.isType(stat) }?.value

public fun Player.isFeatureOn(feature: Feature): Boolean =
    when (featureState(feature)) {
        STATE_ON -> true
        STATE_OFF -> false
        else -> feature.defaultOn
    }

public fun Player.setFeature(feature: Feature, on: Boolean) {
    val shift = feature.slot * 2
    val cleared = vars[FeatureVarps.flags] and (0b11 shl shift).inv()
    val state = if (on) STATE_ON else STATE_OFF
    VarPlayerIntMapSetter.set(this, FeatureVarps.flags, cleared or (state shl shift))
}

private fun Player.featureState(feature: Feature): Int =
    (vars[FeatureVarps.flags] shr (feature.slot * 2)) and 0b11

/** The player's own multiplier for [stat], or `null` if they haven't set one. */
public fun Player.skillXpOverride(stat: StatType): Double? {
    val varp = FeatureVarps.skillXp(stat) ?: return null
    val hundredths = vars[varp]
    return if (hundredths <= 0) null else hundredths / 100.0
}

/** Sets (or with `null`, clears) the player's multiplier for [stat]. */
public fun Player.setSkillXpOverride(stat: StatType, rate: Double?) {
    val varp = FeatureVarps.skillXp(stat) ?: return
    val hundredths = if (rate == null) 0 else (rate * 100).roundToInt().coerceAtLeast(1)
    VarPlayerIntMapSetter.set(this, varp, hundredths)
}

/**
 * Effective xp multiplier for [stat]: the player's override, then the skill default, then [rate]
 * (normally [Player.xpRate]). Overrides replace the personal rate; they don't stack with it.
 */
public fun Player.skillXpRate(stat: StatType, rate: Double = xpRate): Double =
    skillXpOverride(stat) ?: skillXpDefault(stat) ?: rate

private const val STATE_ON = 1
private const val STATE_OFF = 2

/** Stat internal names that have a per-skill xp varp, in stat id order. */
public val featureSkillNames: List<String> =
    listOf(
        "attack",
        "defence",
        "strength",
        "hitpoints",
        "ranged",
        "prayer",
        "magic",
        "cooking",
        "woodcutting",
        "fletching",
        "fishing",
        "firemaking",
        "crafting",
        "smithing",
        "mining",
        "herblore",
        "agility",
        "thieving",
        "slayer",
        "farming",
        "runecrafting",
        "hunter",
        "construction",
        "sailing",
    )

internal object FeatureVarps : VarpReferences() {
    val flags = find("feature_flags")

    private val skillXp: List<VarpType> = featureSkillNames.map { find("feature_skillxp_$it") }

    // featureSkillNames is in stat id order.
    fun skillXp(stat: StatType): VarpType? = skillXp.getOrNull(stat.id)
}
