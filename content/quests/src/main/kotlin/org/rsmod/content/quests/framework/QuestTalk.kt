package org.rsmod.content.quests.framework

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.ScriptContext

/**
 * One "Talk-to" per npc shared by several quests (one handler per op, or startup throws): every
 * quest registers its conversation with a [wants] predicate, and the single binding runs the first
 * registered handler that wants the player, then the [default] one (e.g. Duke Horacio is Rune
 * Mysteries' by default, Dragon Slayer's while you need his shield).
 *
 * ```
 * QuestTalk.register(npcTypes, npcs.duke, wants = { needsShield() }) { talkShield(it) }
 * QuestTalk.default(npcTypes, npcs.duke) { talkDuke(it) }
 * ```
 * Call from `ScriptContext.startup()`; the first registration for an npc binds its Talk-to.
 */
public object QuestTalk {
    private class Handler(
        val wants: ProtectedAccess.() -> Boolean,
        val talk: suspend ProtectedAccess.(Npc) -> Unit,
    )

    private val handlers = HashMap<Int, MutableList<Handler>>()
    private val defaults = HashMap<Int, suspend ProtectedAccess.(Npc) -> Unit>()

    public fun ScriptContext.register(
        npcTypes: NpcTypeList,
        npc: NpcType,
        wants: ProtectedAccess.() -> Boolean,
        talk: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        bindOnce(npcTypes, npc)
        handlers.getValue(npc.id) += Handler(wants, talk)
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
        val handler = handlers[id]?.firstOrNull { it.wants(this) }
        when {
            handler != null -> handler.talk(this, npc)
            else -> defaults[id]?.invoke(this, npc)
        }
    }
}
