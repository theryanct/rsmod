package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.features.setFeature
import org.rsmod.api.player.features.setSkillXpOverride
import org.rsmod.api.player.features.skillXpDefault
import org.rsmod.api.player.features.skillXpOverride
import org.rsmod.api.player.features.skillXpRate
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.game.type.stat.UnpackedStatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Local feature toggles (see [Feature]): `::features` opens a menu, `::toggle <feature>` flips one,
 * `::skillxp <skill> <rate|reset>` sets a per-skill xp multiplier. Changes apply immediately and
 * are saved with the character.
 */
class FeatureCommands
@Inject
constructor(
    private val statTypes: StatTypeList,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("features", "Open the feature toggle menu", ::features)
        onCommand("toggle", "Toggle a feature on/off", ::toggle) {
            invalidArgs = "Use as ::toggle feature (${Feature.entries.joinToString { it.key }})"
        }
        onCommand("skillxp", "Set a per-skill xp multiplier", ::skillXp) {
            invalidArgs = "Use as ::skillxp skill rate (ex: prayer 10, cooking 2.5, prayer reset)"
        }
    }

    private fun features(cheat: Cheat) =
        with(cheat) {
            val launched = protectedAccess.launch(player) { featureMenu() }
            if (!launched) {
                player.mes(constants.dm_busy)
            }
        }

    private fun toggle(cheat: Cheat) =
        with(cheat) {
            val key = args.getOrNull(0)
            val feature = key?.let(Feature::forKey)
            if (feature == null) {
                player.mes("Features: ${Feature.entries.joinToString { it.key }}")
                return
            }
            player.toggleFeature(feature)
        }

    private fun skillXp(cheat: Cheat) =
        with(cheat) {
            val stat = args.getOrNull(0)?.let(::findStat)
            if (stat == null) {
                player.mes("Use as ::skillxp skill rate (ex: prayer 10, cooking 2.5, prayer reset)")
                return
            }
            val arg = args.getOrNull(1)
            if (arg == null) {
                player.mes("${stat.displayName} xp rate: ${player.describeSkillRate(stat)}")
                return
            }
            player.applySkillRate(stat, arg)
        }

    private suspend fun ProtectedAccess.featureMenu() {
        while (true) {
            val toggles = Feature.entries
            val lines =
                toggles.map { "${it.displayName}: ${onOff(player.isFeatureOn(it))}" } +
                    listOf("Skill xp rates...", "Close")
            val index = menu(title = "Feature toggles", hotkeys = false, choices = lines)
            when {
                index < toggles.size -> player.toggleFeature(toggles[index])
                index == toggles.size -> skillRateMenu()
                else -> return
            }
        }
    }

    private suspend fun ProtectedAccess.skillRateMenu() {
        val stats = skillStats()
        while (true) {
            val lines =
                stats.map { "${it.displayName}: ${player.describeSkillRate(it)}" } + listOf("Back")
            val index = menu(title = "Skill xp rates", hotkeys = false, choices = lines)
            if (index >= stats.size) {
                return
            }
            val stat = stats[index]
            val input = stringDialog("${stat.displayName} xp rate (number, or 'reset'):")
            player.applySkillRate(stat, input.trim())
        }
    }

    private fun Player.toggleFeature(feature: Feature) {
        val enabled = !isFeatureOn(feature)
        setFeature(feature, enabled)
        mes("${feature.displayName}: ${onOff(enabled)}.")
    }

    private fun Player.applySkillRate(stat: UnpackedStatType, input: String) {
        if (input.isEmpty()) {
            return
        }
        if (input.equals("reset", ignoreCase = true) || input.equals("default", ignoreCase = true)) {
            setSkillXpOverride(stat, null)
            mes("${stat.displayName} xp rate reset: ${describeSkillRate(stat)}.")
            return
        }
        val rate = input.removeSuffix("x").toDoubleOrNull()
        if (rate == null || !rate.isFinite() || rate < MIN_RATE || rate > MAX_RATE) {
            mes("Xp rate must be a number from $MIN_RATE to ${MAX_RATE.toInt()}, or 'reset'.")
            return
        }
        setSkillXpOverride(stat, rate)
        mes("${stat.displayName} xp rate is now ${formatRate(skillXpRate(stat))}x.")
    }

    private fun Player.describeSkillRate(stat: UnpackedStatType): String {
        val rate = formatRate(skillXpRate(stat))
        return when {
            skillXpOverride(stat) != null -> "${rate}x (set)"
            skillXpDefault(stat) != null -> "${rate}x (default)"
            else -> "${rate}x (::xprate)"
        }
    }

    private fun findStat(name: String): UnpackedStatType? =
        skillStats().firstOrNull {
            it.internalName.equals(name, ignoreCase = true) ||
                it.displayName.equals(name, ignoreCase = true)
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
