package org.rsmod.content.quests.framework

import org.rsmod.api.config.constants
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.game.type.npc.NpcType

/**
 * A quest's dialogue tree, generated from the OSRS Wiki "Transcript:<Quest>" page by
 * tools/quests/transcript_to_dialogue.py (`<quest>.dialogue` resource next to the quest).
 *
 * Each section ("Starting off", "Talking to the Cook again", ...) is a list of nodes; option and
 * condition nodes own the deeper nodes that follow them.
 */
public class QuestDialogue private constructor(private val sections: Map<String, List<Node>>) {
    public sealed class Node(public val children: MutableList<Node> = mutableListOf()) {
        public class Line(public val speaker: String, public val text: String) : Node()

        public class Select(public val title: String) : Node()

        public class Option(public val label: String) : Node()

        public class Cond(public val text: String) : Node()

        public class Box(public val text: String) : Node()

        public class Action(public val tag: String) : Node()
    }

    public fun section(title: String): List<Node> =
        sections[title] ?: error("Quest dialogue has no section \"$title\".")

    public companion object {
        /** Loads `<name>.dialogue` from [anchor]'s resource package. */
        public fun load(anchor: Class<*>, name: String): QuestDialogue {
            val input =
                checkNotNull(anchor.getResourceAsStream("$name.dialogue")) {
                    "$name.dialogue not found next to ${anchor.name}"
                }
            val lines = input.bufferedReader().use { it.readLines() }
            return parse(lines)
        }

        internal fun parse(lines: List<String>): QuestDialogue {
            val sections = LinkedHashMap<String, MutableList<Node>>()
            var current: MutableList<Node>? = null
            // Stack of (depth, node) for attaching children to the nearest shallower node.
            val stack = ArrayDeque<Pair<Int, Node>>()
            for (raw in lines) {
                if (raw.isBlank() || raw.startsWith("#")) continue
                val p = raw.split('\t')
                val depth = p[0].toInt()
                if (p[1] == "section") {
                    // Repeated Wiki headings become "Title (2)", "Title (3)", ...
                    var title = p[2]
                    var copy = 2
                    while (title in sections) title = "${p[2]} (${copy++})"
                    current = mutableListOf<Node>().also { sections[title] = it }
                    stack.clear()
                    continue
                }
                val list = current ?: continue
                val node =
                    when (p[1]) {
                        "line" -> Node.Line(p[2], p.getOrElse(3) { "" })
                        "select" -> Node.Select(p[2])
                        "option" -> Node.Option(p[2])
                        "cond" -> Node.Cond(p[2])
                        "box" -> Node.Box(p[2])
                        "action" -> Node.Action(p[2])
                        else -> continue
                    }
                while (stack.isNotEmpty() && stack.last().first >= depth) {
                    stack.removeLast()
                }
                val parent = stack.lastOrNull()?.second
                if (parent != null) parent.children += node else list += node
                stack.addLast(depth to node)
            }
            return QuestDialogue(sections)
        }
    }
}

/** What a quest supplies to run its transcript: conditions, option/box side effects. */
public interface QuestDialogueHooks {
    /** Whether the transcript condition (exact Wiki text) holds; unknown conditions are false. */
    public fun condition(text: String): Boolean = false

    /** Called when the player picks [label], before its branch runs (e.g. start the quest). */
    public suspend fun Dialogue.onOption(label: String) {}

    /**
     * A transcript box (item hand-ins etc.). Return `true` when the quest handled it itself;
     * otherwise the text is shown in a message box.
     */
    public suspend fun Dialogue.onBox(text: String): Boolean = false

    /** Transcript actions other than end / above / other (e.g. "continues"). */
    public suspend fun Dialogue.onAction(tag: String) {}

    /** Fills Wiki placeholders such as "[1-19]" in a line or box before it is shown. */
    public fun rewrite(text: String): String = text
}

/**
 * Walks a [QuestDialogue] section inside a [Dialogue]:
 * - lines are chat (the player's or the npc's), `{player}` becomes the player's name and
 *   gendered "[lad/lass]" pairs pick by body type (A first, B second);
 * - consecutive options form one menu (2-5 entries); a lone option just runs;
 * - consecutive conditions form one branch: the first that [QuestDialogueHooks.condition] accepts;
 * - action `end` stops, `above` / `other` / `previous` show the enclosing menu again; an
 *   "action" whose text reads like a condition ("If ...", "On ...") is treated as one (a Wiki
 *   markup slip);
 * - nodes nested under a line or box (inconsistent Wiki indentation) run right after it;
 * - lines by a speaker in `speakers` (e.g. voices through a grill) use that npc's chathead,
 *   others go to the dialogue's npc.
 */
public class QuestDialogueRunner(
    private val hooks: QuestDialogueHooks,
    private val speakers: Map<String, NpcType> = emptyMap(),
) {
    private companion object {
        val CONDITION_TEXT = Regex("^(If|On|When) ", RegexOption.IGNORE_CASE)
    }

    private enum class Flow {
        Continue,
        Repeat,
        End,
    }

    public suspend fun run(dialogue: Dialogue, nodes: List<Node>) {
        dialogue.runNodes(nodes, menuTitle = null)
    }

    private suspend fun Dialogue.runNodes(nodes: List<Node>, menuTitle: String?): Flow {
        var index = 0
        var title = menuTitle
        while (index < nodes.size) {
            when (val node = nodes[index]) {
                is Node.Line -> {
                    line(node)
                    val flow = runNodes(node.children, title)
                    if (flow != Flow.Continue) return flow
                }
                is Node.Box -> {
                    with(hooks) {
                        if (!onBox(node.text)) mesbox(personalise(hooks.rewrite(node.text)))
                    }
                    val flow = runNodes(node.children, title)
                    if (flow != Flow.Continue) return flow
                }
                is Node.Select -> title = node.title
                is Node.Action ->
                    when (node.tag.lowercase()) {
                        "end" -> return Flow.End
                        "above",
                        "other",
                        "previous" -> return Flow.Repeat
                        else ->
                            if (CONDITION_TEXT.containsMatchIn(node.tag)) {
                                if (hooks.condition(node.tag)) {
                                    val flow = runNodes(node.children, title)
                                    if (flow != Flow.Continue) return flow
                                }
                            } else {
                                with(hooks) { onAction(node.tag) }
                            }
                    }
                is Node.Option -> {
                    val group = nodes.drop(index).takeWhile { it is Node.Option }
                    index += group.size
                    val flow = menu(group.map { it as Node.Option }, title)
                    if (flow == Flow.End) return Flow.End
                    title = null
                    continue
                }
                is Node.Cond -> {
                    val group = nodes.drop(index).takeWhile { it is Node.Cond }
                    index += group.size
                    val branch = group.firstOrNull { hooks.condition((it as Node.Cond).text) }
                    if (branch != null) {
                        val flow = runNodes(branch.children, title)
                        if (flow != Flow.Continue) return flow
                    }
                    continue
                }
            }
            index++
        }
        return Flow.Continue
    }

    private suspend fun Dialogue.menu(options: List<Node.Option>, title: String?): Flow {
        while (true) {
            val picked = if (options.size == 1) options[0] else choose(options.take(5), title)
            with(hooks) { onOption(picked.label) }
            when (runNodes(picked.children, menuTitle = null)) {
                Flow.End -> return Flow.End
                // Re-show the menu only after a branch that said something (Wiki transcripts
                // sometimes abbreviate an answer to a bare "above", which would loop forever).
                Flow.Repeat ->
                    if (options.size > 1 && picked.children.any { it is Node.Line }) {
                        continue
                    } else {
                        return Flow.Continue
                    }
                Flow.Continue -> return Flow.Continue
            }
        }
    }

    private suspend fun Dialogue.choose(options: List<Node.Option>, title: String?): Node.Option {
        val labels = options.map { it.label }
        val header = title ?: "Select an option"
        val index =
            when (labels.size) {
                2 -> choice2(labels[0], 0, labels[1], 1, title = header)
                3 -> choice3(labels[0], 0, labels[1], 1, labels[2], 2, title = header)
                4 ->
                    choice4(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3, title = header)
                else ->
                    choice5(
                        labels[0],
                        0,
                        labels[1],
                        1,
                        labels[2],
                        2,
                        labels[3],
                        3,
                        labels[4],
                        4,
                        title = header,
                    )
            }
        return options[index]
    }

    /** `{player}` -> name; "[lad/lass]" -> first word for body type A, second for B. */
    private fun Dialogue.personalise(text: String): String {
        val first = player.appearance.bodyType == constants.bodytype_a
        return text
            .replace("{player}", player.displayName)
            .replace(GENDERED) {
                val (a, b) = it.destructured
                when {
                    (a.lowercase() to b.lowercase()) !in GENDER_PAIRS -> it.value
                    first -> a
                    else -> b
                }
            }
    }

    private suspend fun Dialogue.line(node: Node.Line) {
        val text = personalise(hooks.rewrite(node.text))
        val other = speakers[node.speaker]
        when {
            node.speaker.equals("Player", ignoreCase = true) -> chatPlayer(neutral, text)
            other != null -> chatNpcSpecific(node.speaker, other, neutral, text)
            else -> chatNpc(neutral, text)
        }
    }
}

/** Wiki "[A/B]" pairs that are body-type words; others ("[red/green]") are left for hooks. */
private val GENDER_PAIRS =
    setOf(
        "lad" to "lass",
        "sir" to "madam",
        "man" to "woman",
        "man" to "lady",
        "strongman" to "strongwoman",
        "sir" to "miss",
        "boy" to "girl",
        "he" to "she",
        "him" to "her",
        "his" to "her",
        "brother" to "sister",
        "son" to "daughter",
        "gentleman" to "lady",
        "mister" to "miss",
        "king" to "queen",
        "lord" to "lady",
    )

/* "[lad/lass]", also "[lad/lass/kid]" (the third, neutral option is dropped). */
private val GENDERED = Regex("""\[([A-Za-z']+)/([A-Za-z']+)(?:/[A-Za-z']+)?]""")
