package org.rsmod.content.interfaces.journal.tab.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.content.interfaces.journal.tab.SideJournalTab
import org.rsmod.content.interfaces.journal.tab.configs.journal_components
import org.rsmod.content.interfaces.journal.tab.openJournalTab
import org.rsmod.content.interfaces.journal.tab.sideJournalTab
import org.rsmod.content.interfaces.journal.tab.switchJournalTab
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class JournalTabScript
@Inject
constructor(private val eventBus: EventBus, private val protectedAccess: ProtectedAccessLauncher) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onIfOpen(interfaces.side_journal) { player.openActiveJournal() }

        onIfOverlayButton(journal_components.summary_list) {
            player.switchJournalTab(SideJournalTab.Summary)
        }

        onIfOverlayButton(journal_components.quest_list) {
            player.switchJournalTab(SideJournalTab.Quests)
        }

        onIfOverlayButton(journal_components.task_list) {
            player.switchJournalTab(SideJournalTab.Tasks)
        }

        // The quest list's cog: OSRS keeps the quest list's filter/sort options (cache varbits
        // questlist_sort_type, questlist_hide_*, ...) in the All Settings menu's Interfaces >
        // Quest List section, and the client's settings scripts set those varbits themselves.
        // Opening the category directly would mean writing varbit settings_category, whose varp
        // (settings_tracking) also holds client-only bits, so this opens All Settings as is.
        onIfOverlayButton(journal_components.questlist_settings) { player.openQuestListSettings() }
    }

    private fun Player.openActiveJournal() = openJournalTab(sideJournalTab, eventBus)

    private fun Player.openQuestListSettings() {
        val opened = protectedAccess.launch(this) { ifOpenOverlay(interfaces.settings) }
        if (!opened) {
            mes("Please finish what you are doing before opening the settings menu.")
        }
    }

    private fun Player.switchJournalTab(open: SideJournalTab) = switchJournalTab(open, eventBus)
}
