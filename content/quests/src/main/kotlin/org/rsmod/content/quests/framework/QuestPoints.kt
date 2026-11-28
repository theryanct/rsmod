package org.rsmod.content.quests.framework

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Keeps the quest tab's quest point header right. The rev 243 header (cs2 1356) prints
 * "Quest Points: `qp` / `qp_max`": `qp` is varp 101 and `qp_max` is a varbit (bits 0-9 of varp
 * `qp_total`), which nothing used to set, so the total read 0. The tab lists only [BuiltQuests],
 * so `qp_max` is their quest point sum.
 *
 * On login (and after every completion or reset) [syncQuestPoints] recomputes `qp` from the
 * completed built quests, so quests finished through any path (a quest that sets its own end
 * state, `::questcomplete`, an older save) count. It also sets `quests_completed_count`
 * (bits 20-28 of `qp_total`) and resends both varps.
 */
public class QuestPoints : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { player.syncQuestPoints() }
    }
}

/** Recomputes and resends `qp`, `qp_max` and `quests_completed_count` (see [QuestPoints]). */
public fun Player.syncQuestPoints() {
    val built = BuiltQuests.all
    val done = built.filter { isQuestComplete(it) }
    VarPlayerIntMapSetter.set(this, QuestVarps.qp, done.sumOf { it.questPoints })
    VarPlayerIntMapSetter.set(this, QuestPointVarBits.qp_max, built.sumOf { it.questPoints }.coerceAtMost(QP_MAX_LIMIT))
    val quests = done.count { it.listRow.startsWith("quest_") }
    VarPlayerIntMapSetter.set(this, QuestPointVarBits.completed, quests.coerceAtMost(COMPLETED_LIMIT))
    resyncVar(QuestVarps.qp)
    resyncVar(QuestPointVarBits.qp_max)
}

/** `qp_max` is 10 bits wide, `quests_completed_count` 9 bits. */
private const val QP_MAX_LIMIT = 1023
private const val COMPLETED_LIMIT = 511

internal object QuestPointVarBits : VarBitReferences() {
    val qp_max = find("qp_max")
    val completed = find("quests_completed_count")
}
