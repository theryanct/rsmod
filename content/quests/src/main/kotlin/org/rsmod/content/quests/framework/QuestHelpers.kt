package org.rsmod.content.quests.framework

import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.ScriptContext

/** Binds [npc]'s op labelled [label] (e.g. "Talk-to"); returns `false` if it has no such op. */
public fun ScriptContext.onNpcOpLabel(
    npcTypes: NpcTypeList,
    npc: NpcType,
    label: String,
    action: suspend ProtectedAccess.(Npc) -> Unit,
): Boolean {
    when (npcTypes[npc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
        1 -> onOpNpc1(npc) { action(it.npc) }
        2 -> onOpNpc2(npc) { action(it.npc) }
        3 -> onOpNpc3(npc) { action(it.npc) }
        4 -> onOpNpc4(npc) { action(it.npc) }
        5 -> onOpNpc5(npc) { action(it.npc) }
        else -> return false
    }
    return true
}

/** Binds [loc]'s op labelled [label] (e.g. "Search"); returns `false` if it has no such op. */
public fun ScriptContext.onLocOpLabel(
    locTypes: LocTypeList,
    loc: LocType,
    label: String,
    action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
): Boolean {
    when (locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
        1 -> onOpLoc1(loc) { action(it.loc) }
        2 -> onOpLoc2(loc) { action(it.loc) }
        3 -> onOpLoc3(loc) { action(it.loc) }
        4 -> onOpLoc4(loc) { action(it.loc) }
        5 -> onOpLoc5(loc) { action(it.loc) }
        else -> return false
    }
    return true
}

/** Hooks from lambdas, for quests that only need a few conditions and side effects. */
public class SimpleQuestHooks(
    private val conditions: Map<String, () -> Boolean> = emptyMap(),
    private val defaultCondition: (String) -> Boolean = { false },
    private val options: Map<String, suspend () -> Unit> = emptyMap(),
    private val boxes: Map<String, suspend () -> Boolean> = emptyMap(),
    private val actions: Map<String, suspend () -> Unit> = emptyMap(),
    private val rewrites: (String) -> String = { it },
) : QuestDialogueHooks {
    override fun condition(text: String): Boolean =
        conditions[text]?.invoke() ?: defaultCondition(text)

    override suspend fun Dialogue.onOption(label: String) {
        options[label]?.invoke()
    }

    override suspend fun Dialogue.onBox(text: String): Boolean = boxes[text]?.invoke() ?: false

    override suspend fun Dialogue.onAction(tag: String) {
        actions[tag]?.invoke()
    }

    override fun rewrite(text: String): String = rewrites(text)
}

/** Runs one transcript [section] with [npc] (or without an npc for item/loc dialogue). */
public suspend fun ProtectedAccess.runQuestSection(
    npc: Npc?,
    dialogue: QuestDialogue,
    section: String,
    hooks: QuestDialogueHooks = SimpleQuestHooks(),
) {
    val nodes = dialogue.section(section)
    if (npc != null) {
        startDialogue(npc) { QuestDialogueRunner(hooks).run(this, nodes) }
    } else {
        startDialogue { QuestDialogueRunner(hooks).run(this, nodes) }
    }
}

public fun ProtectedAccess.carries(obj: ObjType, count: Int = 1): Boolean =
    invTotal(inv, obj) >= count

/** Removes one of each obj (or the given counts); call only after [carries] checks. */
public fun ProtectedAccess.takeAll(vararg objs: Pair<ObjType, Int>) {
    for ((obj, count) in objs) {
        invDel(inv, obj, count)
    }
}
