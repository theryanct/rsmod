package org.rsmod.content.quests.framework

import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.ScriptContext

/**
 * One "Talk-to" per npc shared by several quests (one handler per op, or startup throws): every
 * quest registers its conversation with a [wants] predicate, and the single binding picks:
 * - one handler that wants the player: straight into it;
 * - two or more: a "Speak about <quest>" menu (quest list order) plus "Something else", which runs
 *   the [default] conversation;
 * - none: the [default] one (e.g. Duke Horacio is Rune Mysteries' by default, Dragon Slayer's while
 *   you need his shield).
 *
 * ```
 * QuestTalk.register(npcTypes, npcs.duke, DRAGON_SLAYER, wants = { needsShield() }) {
 *     talkShield(it)
 * }
 * QuestTalk.default(npcTypes, npcs.duke) { talkDuke(it) }
 * ```
 * Call from `ScriptContext.startup()`; the first registration for an npc binds its Talk-to.
 */
public object QuestTalk {
    private class Handler(
        val quest: QuestDef,
        val wants: ProtectedAccess.() -> Boolean,
        val talk: suspend ProtectedAccess.(Npc) -> Unit,
    )

    private val handlers = HashMap<Int, MutableList<Handler>>()
    private val defaults = HashMap<Int, suspend ProtectedAccess.(Npc) -> Unit>()

    public fun ScriptContext.register(
        npcTypes: NpcTypeList,
        npc: NpcType,
        quest: QuestDef,
        wants: ProtectedAccess.() -> Boolean,
        talk: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        bindOnce(npcTypes, npc)
        handlers.getValue(npc.id) += Handler(quest, wants, talk)
    }

    public fun ScriptContext.default(
        npcTypes: NpcTypeList,
        npc: NpcType,
        talk: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        bindOnce(npcTypes, npc)
        check(npc.id !in defaults) { "QuestTalk: two default conversations for npc ${npc.id}" }
        defaults[npc.id] = talk
    }

    private fun ScriptContext.bindOnce(npcTypes: NpcTypeList, npc: NpcType) {
        if (npc.id in handlers) return
        handlers[npc.id] = ArrayList()
        onNpcOpLabel(npcTypes, npc, "Talk-to") { target -> dispatch(target, npc.id) }
    }

    private suspend fun ProtectedAccess.dispatch(npc: Npc, id: Int) {
        val order = BuiltQuests.all
        val wanted =
            handlers[id].orEmpty().filter { it.wants(this) }.sortedBy { order.indexOf(it.quest) }
        val fallback = defaults[id]
        when {
            wanted.isEmpty() -> fallback?.invoke(this, npc)
            wanted.size == 1 -> wanted.single().talk(this, npc)
            else -> {
                val choices = wanted.take(MAX_QUEST_CHOICES)
                val labels = choices.map { "Speak about ${it.quest.name}" } + SOMETHING_ELSE
                var picked = -1
                startDialogue(npc) { picked = menu(labels) }
                val handler = choices.getOrNull(picked)
                when {
                    handler != null -> handler.talk(this, npc)
                    picked == choices.size -> fallback?.invoke(this, npc)
                }
            }
        }
    }

    private suspend fun Dialogue.menu(labels: List<String>): Int =
        when (labels.size) {
            2 -> choice2(labels[0], 0, labels[1], 1)
            3 -> choice3(labels[0], 0, labels[1], 1, labels[2], 2)
            4 -> choice4(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3)
            else -> choice5(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3, labels[4], 4)
        }

    private const val MAX_QUEST_CHOICES = 4
    private const val SOMETHING_ELSE = "Something else"
}
