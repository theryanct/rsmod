package org.rsmod.api.player.features

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Player
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.game.type.stat.UnpackedStatType

/**
 * The `::features` menu and its helpers, shared by the commands and the Settings tab button (the
 * former "Bonds" button).
 */
@Singleton
public class FeatureMenu @Inject constructor(private val statTypes: StatTypeList) {
    /** Opens the toggle menu; must run inside a protected access. */
    public suspend fun open(access: ProtectedAccess): Unit = access.featureMenu()

    public fun toggle(player: Player, feature: Feature) {
        val enabled = !player.isFeatureOn(feature)
        player.setFeature(feature, enabled)
        player.mes("${feature.displayName}: ${onOff(enabled)}.")
    }

    /** Applies a typed rate (a number, or `reset`/`default`) to [stat] for [player]. */
    public fun applySkillRate(player: Player, stat: UnpackedStatType, input: String) {
        if (input.isEmpty()) {
            return
        }
        if (input.equals("reset", ignoreCase = true) || input.equals("default", ignoreCase = true)) {
            player.setSkillXpOverride(stat, null)
            player.mes("${stat.displayName} xp rate reset: ${describeSkillRate(player, stat)}.")
            return
        }
        val rate = input.removeSuffix("x").toDoubleOrNull()
        if (rate == null || !rate.isFinite() || rate < MIN_RATE || rate > MAX_RATE) {
            player.mes("Xp rate must be a number from $MIN_RATE to ${MAX_RATE.toInt()}, or 'reset'.")
            return
        }
        player.setSkillXpOverride(stat, rate)
        player.mes("${stat.displayName} xp rate is now ${formatRate(player.skillXpRate(stat))}x.")
    }

    public fun describeSkillRate(player: Player, stat: UnpackedStatType): String {
        val rate = formatRate(player.skillXpRate(stat))
        return when {
            player.skillXpOverride(stat) != null -> "${rate}x (set)"
            skillXpDefault(stat) != null -> "${rate}x (default)"
            else -> "${rate}x (::xprate)"
        }
    }

    public fun findStat(name: String): UnpackedStatType? =
        skillStats().firstOrNull {
            it.internalName.equals(name, ignoreCase = true) ||
                it.displayName.equals(name, ignoreCase = true)
        }

    private suspend fun ProtectedAccess.featureMenu() {
        while (true) {
            val toggles = Feature.entries
            val lines =
                toggles.map { "${it.displayName}: ${onOff(player.isFeatureOn(it))}" } +
                    listOf("Skill xp rates...", "Close")
            val index = menu(title = "Feature toggles", hotkeys = false, choices = lines)
            when {
                index < toggles.size -> toggle(player, toggles[index])
                index == toggles.size -> skillRateMenu()
                else -> return
            }
        }
    }

    private suspend fun ProtectedAccess.skillRateMenu() {
        val stats = skillStats()
        while (true) {
            val lines =
                stats.map { "${it.displayName}: ${describeSkillRate(player, it)}" } + listOf("Back")
            val index = menu(title = "Skill xp rates", hotkeys = false, choices = lines)
            if (index >= stats.size) {
                return
            }
            val stat = stats[index]
            val input = stringDialog("${stat.displayName} xp rate (number, or 'reset'):")
            applySkillRate(player, stat, input.trim())
        }
    }

    private fun skillStats(): List<UnpackedStatType> =
        statTypes.types.values.filter { it.internalName != "unreleased" }.sortedBy { it.id }

    private fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

    private fun formatRate(rate: Double): String =
        rate.toBigDecimal().stripTrailingZeros().toPlainString()

    private companion object {
        private const val MIN_RATE = 0.01
        private const val MAX_RATE = 1000.0
    }
}
