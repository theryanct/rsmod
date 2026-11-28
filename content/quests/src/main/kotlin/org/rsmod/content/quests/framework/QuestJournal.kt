package org.rsmod.content.quests.framework

import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.output.GameplayLog
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The quest journal (Ryan: clicking a quest did nothing). The rev 243 quest list (cs2 2633) makes
 * one child per quest with child index = the quest's id (dbtable `quest` column 0, looked up with
 * `db_find`), ops 1 "View info" (speedrun worlds), 2 "Read journal", 3 "Show on map", 4/5 wiki
 * guides, 6 "Pin journal". The client only sends those clicks once the server enables the ops
 * (IF_SETEVENTS), which nothing did, so clicks never reached the server.
 *
 * On "Read journal" (or "View info") the quest journal opens with the quest's title and its state;
 * a quest this server hasn't built says so. The id -> dbrow -> title table is resources
 * `quest_ids.txt`, generated from the cache. OSRS's own per-stage journal text isn't built.
 */
class QuestJournal @Inject constructor(private val protectedAccess: ProtectedAccessLauncher) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onIfOpen(interfaces.questlist) { player.enableQuestOps() }
        for (list in JournalComponents.lists) {
            onIfOverlayButton(list) {
                if (op == IfButtonOp.Op1 || op == IfButtonOp.Op2) {
                    val id = comsub
                    protectedAccess.launch(player) { openJournal(id) }
                }
            }
        }
    }

    private fun Player.enableQuestOps() {
        for (list in JournalComponents.lists) {
            ifSetEvents(list, 0..MAX_QUEST_ID, IfEvent.Op1, IfEvent.Op2, IfEvent.Op3, IfEvent.Op4, IfEvent.Op5, IfEvent.Op6)
        }
    }

    private fun ProtectedAccess.openJournal(questId: Int) {
        val entry = QuestIds.byId[questId]
        GameplayLog.input(player) { "questjournal id=$questId row=${entry?.row}" }
        if (entry == null) return
        val quest = BuiltQuests.all.firstOrNull { it.listRow == entry.row }
        val lines =
            if (quest == null) {
                listOf("", "Not yet available on this server.")
            } else {
                val state = player.questState(quest)
                when {
                    state <= 0 -> listOf("", "I haven't started this quest yet.")
                    state >= quest.complete ->
                        listOf("", "<col=ff0000>QUEST COMPLETE!</col>", "", "Rewards:") +
                            quest.rewards.take(JournalComponents.lines.size - 5)
                    else -> listOf("", "I have started this quest. (Stage $state of ${quest.complete})")
                }
            }
        ifOpenMainModal(JournalInterfaces.questjournal)
        ifSetText(JournalComponents.title, entry.title)
        for ((index, component) in JournalComponents.lines.withIndex()) {
            ifSetText(component, lines.getOrElse(index) { "" })
        }
    }

    private companion object {
        const val MAX_QUEST_ID = 300
    }
}

/** Quest id (the quest list child index) -> dbrow name and title, from `quest_ids.txt`. */
internal object QuestIds {
    class Entry(val row: String, val title: String)

    val byId: Map<Int, Entry> by lazy {
        val stream =
            checkNotNull(QuestIds::class.java.getResourceAsStream("quest_ids.txt")) { "quest_ids.txt missing" }
        stream.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
                .filter { it.size >= 3 }
                .associate { it[0].toInt() to Entry(it[1], it[2]) }
        }
    }
}

internal object JournalInterfaces : InterfaceReferences() {
    val questjournal = find("questjournal")
}

internal object JournalComponents : ComponentReferences() {
    /** The list's layers (the cs2 creates the quest children on one of them). */
    val lists = listOf(find("questlist:list"), find("questlist:list_container"), find("questlist:container"))
    val title = find("questjournal:title")
    val lines = (1..12).map { find("questjournal:qj$it") }
}
