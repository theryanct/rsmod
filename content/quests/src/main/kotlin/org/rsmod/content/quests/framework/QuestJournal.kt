package org.rsmod.content.quests.framework

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.output.GameplayLog
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * The quest journal. The rev 243 quest list (cs2 2633) makes one child per quest with child index =
 * the quest's id (dbtable `quest` column 0), ops 1 "View info", 2 "Read journal", ...; the client
 * only sends those clicks once the server enables the ops (IF_SETEVENTS).
 *
 * The text is the OSRS Wiki's journal transcript for the quest ([JournalBook], resources
 * `journals/<dbrow>.journal`, from tools/quests/journals_from_wiki.py), as in OSRS:
 * - not started: the start text, difficulty, length, ... and the requirements, struck through
 *   once met (red while not);
 * - in progress: every step so far, the finished ones struck through and the current one in the
 *   journal's colours (dark blue text, dark red names and places). The Wiki records one snapshot
 *   per milestone; the snapshot shown is the last one whose stage the quest has reached (a stage
 *   pinned in the file, else the snapshots are spread evenly over the quest's states);
 * - complete: the finished story, "QUEST COMPLETE!" and the rewards.
 * A built quest without a Wiki journal shows a short summary (and is logged once).
 */
class QuestJournal
@Inject
constructor(private val protectedAccess: ProtectedAccessLauncher, private val statTypes: StatTypeList) :
    PluginScript() {
    private val loggedMissing = HashSet<String>()

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
        val book = JournalBook.forRow(entry.row)
        val text =
            when {
                quest == null -> book?.let { notStarted(it) } ?: listOf("", "Not yet available on this server.")
                book == null -> {
                    if (loggedMissing.add(entry.row)) {
                        logger.info { "Quest journal: no Wiki journal for ${entry.row} (${entry.title})." }
                    }
                    summary(quest)
                }
                else -> pages(quest, book)
            }
        val lines = text.flatMap { wrap(it.replace("{player}", player.displayName)) }
        ifOpenMainModal(JournalInterfaces.questjournal)
        ifSetText(JournalComponents.title, entry.title)
        for ((index, component) in JournalComponents.lines.withIndex()) {
            ifSetText(component, lines.getOrElse(index) { "" })
        }
    }

    private fun ProtectedAccess.pages(quest: QuestDef, book: JournalBook): List<String> {
        val state = player.questState(quest)
        return when {
            state <= 0 -> notStarted(book)
            state >= quest.complete -> {
                val done = book.done.ifEmpty { listOf("<col=ff0000>QUEST COMPLETE!</col>") }
                done + listOf("", "<col=000080>Rewards:</col>") + quest.rewards.map { "<col=800000>$it</col>" }
            }
            else -> {
                val custom = JournalStages.snapshotFor(quest, player)
                val step = custom?.let { book.steps.getOrNull(it - 1) } ?: book.stepFor(state, quest.complete)
                step?.lines ?: summary(quest)
            }
        }
    }

    private fun ProtectedAccess.notStarted(book: JournalBook): List<String> =
        book.before.map { line ->
            when (line) {
                is JournalBook.Before.Text -> line.text
                is JournalBook.Before.Requirement ->
                    when (meets(line.text)) {
                        true -> "<str>${line.text}</str>"
                        false -> "<col=800000>${line.text}</col>"
                        // Not a skill/quest/points line (e.g. "Ability to defeat a level 70 monster").
                        null -> "<col=000080>${line.text}</col>"
                    }
            }
        }

    /**
     * A Wiki requirement line ("Level 20 Crafting", "32 Quest Points", a quest name...) met?
     * `null` when it isn't one the server can check.
     */
    private fun ProtectedAccess.meets(text: String): Boolean? {
        val t = text.trim().removeSuffix(".")
        Regex("""^(?:Level )?(\d+) ([A-Za-z]+)(?: \(boostable\))?$""").find(t)?.let { m ->
            val level = m.groupValues[1].toInt()
            val name = m.groupValues[2]
            if (name.equals("Quest", true)) return player.questPoints >= level
            val stat = statTypes.values.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
            if (stat != null) return player.statBase(stat) >= level
        }
        Regex("""^(\d+) Quest Points?$""", RegexOption.IGNORE_CASE).find(t)?.let {
            return player.questPoints >= it.groupValues[1].toInt()
        }
        Regex("""^Combat Level (\d+)$""", RegexOption.IGNORE_CASE).find(t)?.let {
            return player.combatLevel >= it.groupValues[1].toInt()
        }
        val name = t.removePrefix("Completion of ").trim()
        val quest = BuiltQuests.all.firstOrNull { it.name.equals(name, true) }
        if (quest != null) return player.isQuestComplete(quest)
        // An unbuilt quest can't be done yet; anything else isn't checkable.
        return if (QuestIds.byId.values.any { it.title.equals(name, true) }) false else null
    }

    private fun ProtectedAccess.summary(quest: QuestDef): List<String> {
        val state = player.questState(quest)
        return when {
            state <= 0 -> listOf("", "<col=000080>I haven't started this quest yet.</col>")
            state >= quest.complete ->
                listOf("", "<col=ff0000>QUEST COMPLETE!</col>", "", "<col=000080>Rewards:</col>") +
                    quest.rewards.map { "<col=800000>$it</col>" }
            else -> listOf("", "<col=000080>I have started this quest.</col>")
        }
    }

    private companion object {
        const val MAX_QUEST_ID = 300
        /** Visible characters per journal line (the qj components' width in the p12 font). */
        const val LINE_WIDTH = 56

        /**
         * Splits [text] into journal lines of at most [LINE_WIDTH] visible characters at spaces,
         * re-opening the colour and strike tags that span the break (each component is one line).
         */
        fun wrap(text: String): List<String> {
            if (text.isEmpty()) return listOf("")
            val tokens = Regex("""<[^>]+>|[^<\s]+|\s+""").findAll(text).map { it.value }.toList()
            val out = ArrayList<String>()
            val open = ArrayList<String>()
            val line = StringBuilder()
            var width = 0
            fun flush() {
                val closes = open.asReversed().joinToString("") { if (it.startsWith("<col")) "</col>" else "</str>" }
                out += line.toString().trimEnd() + closes
                line.clear()
                line.append(open.joinToString(""))
                width = 0
            }
            for (token in tokens) {
                when {
                    token.startsWith("</") -> {
                        val idx = open.indexOfLast { (token == "</col>") == it.startsWith("<col") }
                        if (idx >= 0) open.removeAt(idx)
                        line.append(token)
                    }
                    token.startsWith("<") -> {
                        open += token
                        line.append(token)
                    }
                    token.isBlank() -> if (width > 0) {
                        line.append(' ')
                        width++
                    }
                    else -> {
                        if (width > 0 && width + token.length > LINE_WIDTH) flush()
                        line.append(token)
                        width += token.length
                    }
                }
            }
            out += line.toString().trimEnd()
            return out
        }
    }
}

/**
 * Per-quest journal progress for quests whose progress isn't (only) the quest var, e.g. The
 * Fremennik Trials' trials in server varps: register from the quest's startup with the snapshot
 * (1-based `step` number in its journal file) to show, or `null` to fall back to the stage.
 */
public object JournalStages {
    private val hooks = HashMap<String, (Player) -> Int?>()

    public fun register(quest: QuestDef, snapshot: (Player) -> Int?) {
        check(quest.listRow !in hooks) { "JournalStages: two hooks for ${quest.listRow}" }
        hooks[quest.listRow] = snapshot
    }

    internal fun snapshotFor(quest: QuestDef, player: Player): Int? = hooks[quest.listRow]?.invoke(player)
}

/** One quest's Wiki journal, from resources `journals/<dbrow>.journal`. */
internal class JournalBook(val before: List<Before>, val steps: List<Step>, val done: List<String>) {
    sealed class Before {
        class Text(val text: String) : Before()

        class Requirement(val text: String) : Before()
    }

    class Step(val pinned: Int?, val lines: List<String>)

    /** The last snapshot reached at [state] (pinned stages, else spread over 1 until [complete]). */
    fun stepFor(state: Int, complete: Int): Step? {
        var current = steps.firstOrNull() ?: return null
        for ((index, step) in steps.withIndex()) {
            val from = step.pinned ?: (1 + index * (complete - 1) / steps.size)
            if (state >= from) current = step
        }
        return current
    }

    companion object {
        private val cache = HashMap<String, JournalBook?>()

        fun forRow(row: String): JournalBook? =
            synchronized(cache) { cache.getOrPut(row) { load(row) } }

        private fun load(row: String): JournalBook? {
            val stream = JournalBook::class.java.getResourceAsStream("journals/$row.journal") ?: return null
            val before = ArrayList<Before>()
            val steps = ArrayList<Step>()
            val done = ArrayList<String>()
            var lines: MutableList<String>? = null
            var pinned: Int? = null
            fun close() {
                lines?.let { steps += Step(pinned, it) }
                lines = null
            }
            stream.bufferedReader().useLines { input ->
                for (raw in input) {
                    if (raw.startsWith("#") || raw.isBlank()) continue
                    val p = raw.split('\t')
                    val text = p.getOrElse(1) { "" }
                    when (p[0]) {
                        "before" -> before += Before.Text(text)
                        "req" -> before += Before.Requirement(text)
                        "step" -> {
                            close()
                            pinned = p.getOrNull(2)?.toIntOrNull()
                            lines = ArrayList()
                        }
                        "line" -> lines?.add(text)
                        "done" -> done += text
                    }
                }
            }
            close()
            if (steps.isEmpty() && done.isEmpty()) return null
            return JournalBook(before, steps, done)
        }
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
    /** The journal's text lines (qj1-qj206). */
    val lines = (1..LINES).map { find("questjournal:qj$it") }

    private const val LINES = 206
}
