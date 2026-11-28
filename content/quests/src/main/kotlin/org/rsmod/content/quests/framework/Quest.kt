package org.rsmod.content.quests.framework

import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.varp.VarpType

/**
 * A quest whose progress lives in the cache quest varp the client already reads: the quest list
 * colours (not started / started / complete) and the journal come from comparing that varp with
 * the quest's cache db row (unstarted / end state), so setting it is all the client needs.
 *
 * @param varp the cache quest varp (e.g. `cookquest`), or
 * @param varbit the cache quest varbit, for newer quests (e.g. `cluequest`); exactly one is set.
 * @param listRow the quest's dbrow (dbtable `quest`) when it isn't `quest_` + the lowercased name
 *   without punctuation. The quest tab lists only quests in [BuiltQuests] (see QuestListEditor).
 * @param complete the varp value of a finished quest (the db row's end state).
 * @param rewards lines for the quest complete scroll (max 7), e.g. "300 Cooking XP".
 * @param scrollObj the obj shown on the scroll.
 */
public class QuestDef(
    public val name: String,
    public val varp: (() -> VarpType)? = null,
    public val complete: Int,
    public val questPoints: Int,
    public val rewards: List<String>,
    public val scrollObj: () -> ObjType,
    public val varbit: (() -> VarBitType)? = null,
    listRow: String? = null,
) {
    /** The quest's dbrow name, e.g. `quest_cooksassistant`. */
    public val listRow: String =
        listRow ?: ("quest_" + name.lowercase().filter { it.isLetterOrDigit() })

    init {
        require((varp == null) != (varbit == null)) { "$name: set exactly one of varp / varbit" }
    }
}

public fun Player.questState(quest: QuestDef): Int {
    val varbit = quest.varbit
    return if (varbit != null) vars[varbit()] else vars[checkNotNull(quest.varp)()]
}

public fun Player.isQuestStarted(quest: QuestDef): Boolean = questState(quest) > 0

public fun Player.isQuestComplete(quest: QuestDef): Boolean = questState(quest) >= quest.complete

public fun Player.setQuestState(quest: QuestDef, state: Int) {
    val varbit = quest.varbit
    if (varbit != null) {
        VarPlayerIntMapSetter.set(this, varbit(), state)
    } else {
        VarPlayerIntMapSetter.set(this, checkNotNull(quest.varp)(), state)
    }
}

/** Total quest points (the cache `qp` varp shown in the quest tab). */
public val Player.questPoints: Int
    get() = vars[QuestVarps.qp]

/**
 * Sets [quest]'s varp to its end state and adds its quest points, without the scroll (for when no
 * [ProtectedAccess] is available, e.g. an npc death event). Returns `false` if already complete.
 */
public fun Player.grantQuest(quest: QuestDef): Boolean {
    if (isQuestComplete(quest)) return false
    setQuestState(quest, quest.complete)
    VarPlayerIntMapSetter.set(this, QuestVarps.qp, questPoints + quest.questPoints)
    return true
}

/**
 * Finishes [quest]: sets the varp to its end state, adds the quest points and shows the quest
 * complete scroll with [QuestDef.rewards]. The quest itself grants its xp/items/unlocks before
 * calling this (so the scroll and the rewards can't disagree).
 */
public fun ProtectedAccess.completeQuest(quest: QuestDef) {
    if (!player.grantQuest(quest)) return

    ifOpenMainModal(QuestInterfaces.questscroll)
    ifSetText(QuestComponents.quest_title, "You have completed ${quest.name}!")
    ifSetObj(QuestComponents.quest_model, quest.scrollObj(), 250)
    ifSetText(QuestComponents.quest_points, "Quest Points: ${player.questPoints}")
    val lines = QuestComponents.rewards
    val pointsLine = "${quest.questPoints} Quest Point${if (quest.questPoints == 1) "" else "s"}"
    val rewardLines = (listOf(pointsLine) + quest.rewards).take(lines.size)
    for ((index, component) in lines.withIndex()) {
        ifSetText(component, rewardLines.getOrElse(index) { "" })
    }
    player.mes("Congratulations, you've completed a quest: <col=7f0000>${quest.name}</col>")
}

internal object QuestVarps : VarpReferences() {
    val qp = find("qp")
}

internal object QuestInterfaces : InterfaceReferences() {
    val questscroll = find("questscroll")
}

internal object QuestComponents : ComponentReferences() {
    val quest_title = find("questscroll:quest_title")
    val quest_model = find("questscroll:quest_model")
    val quest_points = find("questscroll:quest_points")
    val rewards =
        (1..7).map { find("questscroll:quest_reward$it") }
}
