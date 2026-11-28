package org.rsmod.content.other.login

import com.github.michaelbull.logging.InlineLogger
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * One-off fixes for characters saved before a cache upgrade, applied at login and recorded as bits
 * of the server-only varp `migration_flags`, so each runs once per character.
 */
class SaveMigrationScript : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { player.migrate() }
    }

    private fun Player.migrate() {
        val done = vars[MigrationVarps.flags]
        var applied = done
        for (migration in Migration.entries) {
            if (done and migration.bit != 0) {
                continue
            }
            migration.apply(this)
            applied = applied or migration.bit
            logger.info { "Applied save migration ${migration.name} for $username." }
        }
        if (applied != done) {
            VarPlayerIntMapSetter.set(this, MigrationVarps.flags, applied)
        }
    }

    private enum class Migration(val bit: Int) {
        /**
         * Rev 233 -> 243: varp 965 held `option_level_up_popup_list` (bits 0-9) and varbit 4372
         * (bits 10-14); in 243 it is `skill_guide_v2` / the `levelup_list_*` counters, so old
         * values show up as bogus level-up unlock counts. Start it clean.
         */
        Rev243Varp965(1 shl 0) {
            override fun apply(player: Player) {
                VarPlayerIntMapSetter.set(player, MigrationVarps.levelup_lists, 0)
                player.resyncVar(MigrationVarps.levelup_lists)
            }
        };

        abstract fun apply(player: Player)
    }
}

internal object MigrationVarps : VarpReferences() {
    val flags = find("migration_flags")
    val levelup_lists = find("skill_guide_v2")
}
