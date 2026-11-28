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
 * set", which falls back to [skillXpDefaults] and then to [Player.xpRate]. The Slayer point
 * multiplier is a whole number in `feature_slayer_points` (`0` = not set = 1x).
 */
public enum class Feature(
    public val key: String,
    public val displayName: String,
    public val defaultOn: Boolean,
    internal val slot: Int,
    private val declaredCategory: FeatureCategory? = null,
) {
    InfiniteRun("run", "Infinite run energy", defaultOn = true, slot = 0, declaredCategory = FeatureCategory.Movement),
    InfinitePrayer("prayer", "Infinite prayer", defaultOn = false, slot = 1, declaredCategory = FeatureCategory.Combat),
    InfiniteHealth("health", "Infinite health", defaultOn = false, slot = 2, declaredCategory = FeatureCategory.Combat),
    InfiniteRunes("runes", "Infinite runes", defaultOn = true, slot = 3, declaredCategory = FeatureCategory.Combat),
    NoHomeTeleportCooldown(
        "hometele",
        "No home teleport cooldown",
        defaultOn = true,
        slot = 4,
        declaredCategory = FeatureCategory.Movement,
    ),
    AutoResumeGathering(
        "autoresume",
        "Auto-resume gathering",
        defaultOn = true,
        slot = 5,
        declaredCategory = FeatureCategory.Skilling,
    ),
    NotedGathering("notedgather", "Noted gathering", defaultOn = true, slot = 6, declaredCategory = FeatureCategory.Skilling),
    NotedDrops("noteddrops", "Noted combat drops", defaultOn = true, slot = 7, declaredCategory = FeatureCategory.Loot),
    BankAnywhere("bankanywhere", "Bank anywhere", defaultOn = true, slot = 8, declaredCategory = FeatureCategory.Movement),
    /** Content locked behind quests (e.g. Runecraft behind Rune Mysteries); off = no gating. */
    QuestGates(
        "questgates",
        "Quest requirements gate content",
        defaultOn = false,
        slot = 9,
        declaredCategory = FeatureCategory.Quests,
    ),
    /** Pickpocket the same npc again after a success; re-steal from a restocked stall. */
    AutoRepeatThieving(
        "autothieve",
        "Auto-repeat thieving",
        defaultOn = true,
        slot = 10,
        declaredCategory = FeatureCategory.Skilling,
    ),
    /**
     * Clue scrolls stack with no limit: a clue drop while you already hold (or bank) a clue of
     * that tier gives a stackable scroll box instead; off = no drop (the old one-per-tier rule).
     */
    UnlimitedClues(
        "unlimitedclues",
        "Unlimited stacked clue scrolls",
        defaultOn = true,
        slot = 11,
        declaredCategory = FeatureCategory.Loot,
    );

    /**
     * The menu category: declared on the entry, else by enum name (features added on other
     * branches before they declare one), else [FeatureCategory.Other].
     */
    public val category: FeatureCategory
        get() = declaredCategory ?: FeatureCategory.byFeatureName[name] ?: FeatureCategory.Other

    public companion object {
        public fun forKey(key: String): Feature? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** Top-level groups of the `::features` menu, in menu order. */
public enum class FeatureCategory(public val displayName: String) {
    Movement("Movement & Travel"),
    Combat("Combat"),
    Skilling("Skilling"),
    Loot("Loot"),
    Quests("Quests"),
    Other("Other");

    internal companion object {
        /** Categories for features that don't declare one yet (merged from other branches). */
        val byFeatureName: Map<String, FeatureCategory> =
            mapOf("QuestGates" to Quests, "AutoRepeatThieving" to Skilling)
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

/** Whole-number multiplier on Slayer task points (incl. streak milestones); default 1. */
public val Player.slayerPointMultiplier: Int
    get() = vars[FeatureVarps.slayerPoints].takeIf { it > 0 } ?: 1

/** Sets (or with `null`, resets to 1x) the Slayer point multiplier. */
public fun Player.setSlayerPointMultiplier(multiplier: Int?) {
    val value = multiplier?.coerceIn(1, MAX_SLAYER_POINT_MULTIPLIER) ?: 0
    VarPlayerIntMapSetter.set(this, FeatureVarps.slayerPoints, value)
}

public const val MAX_SLAYER_POINT_MULTIPLIER: Int = 1000

/** Every toggle back to its default, every skill xp rate cleared, Slayer points back to 1x. */
public fun Player.resetAllFeatures() {
    VarPlayerIntMapSetter.set(this, FeatureVarps.flags, 0)
    for (index in featureSkillNames.indices) {
        FeatureVarps.skillXpAt(index)?.let { VarPlayerIntMapSetter.set(this, it, 0) }
    }
    VarPlayerIntMapSetter.set(this, FeatureVarps.slayerPoints, 0)
}

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
    val slayerPoints = find("feature_slayer_points")

    private val skillXp: List<VarpType> = featureSkillNames.map { find("feature_skillxp_$it") }

    // featureSkillNames is in stat id order.
    fun skillXp(stat: StatType): VarpType? = skillXp.getOrNull(stat.id)

    fun skillXpAt(index: Int): VarpType? = skillXp.getOrNull(index)
}
