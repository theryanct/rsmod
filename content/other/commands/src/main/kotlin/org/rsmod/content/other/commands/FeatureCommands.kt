package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.FeatureMenu
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.game.cheat.Cheat
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
    private val featureMenu: FeatureMenu,
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
            val launched = protectedAccess.launch(player) { featureMenu.open(this) }
            if (!launched) {
                player.mes(constants.dm_busy)
            }
        }

    private fun toggle(cheat: Cheat) =
        with(cheat) {
            val feature = args.getOrNull(0)?.let(Feature::forKey)
            if (feature == null) {
                player.mes("Features: ${Feature.entries.joinToString { it.key }}")
                return
            }
            featureMenu.toggle(player, feature)
        }

    private fun skillXp(cheat: Cheat) =
        with(cheat) {
            val stat = args.getOrNull(0)?.let(featureMenu::findStat)
            if (stat == null) {
                player.mes("Use as ::skillxp skill rate (ex: prayer 10, cooking 2.5, prayer reset)")
                return
            }
            val arg = args.getOrNull(1)
            if (arg == null) {
                val rate = featureMenu.describeSkillRate(player, stat)
                player.mes("${stat.displayName} xp rate: $rate")
                return
            }
            featureMenu.applySkillRate(player, stat, arg)
        }
}
