package org.rsmod.content.quests.learningtheropes

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.syncQuestPoints
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Learning the Ropes (OSRS Wiki): the Tutorial Island quest, 1 QP. Its progress is the cache
 * `tutorial` varp (281), end state 671 (the dbrow's), which the client also uses for Tutorial
 * Island's interface.
 *
 * This server has no Tutorial Island: everyone starts on the mainland, which in OSRS means the
 * quest is done. So on login a player who is off the island and below the end state gets the end
 * state (and its quest point through [syncQuestPoints]); anyone on Tutorial Island is never
 * touched, and a value already at or past the end state is left alone. [ENABLED] turns it off
 * (Ryan's call: revert by setting it false; the varp keeps whatever it was set to).
 */
class LearningTheRopes : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { if (ENABLED) player.markTutorialDone() }
    }

    private fun Player.markTutorialDone() {
        if (onTutorialIsland()) return
        if (vars[LtrVarps.tutorial] >= COMPLETE) return
        VarPlayerIntMapSetter.set(this, LtrVarps.tutorial, COMPLETE)
        syncQuestPoints()
    }

    private fun Player.onTutorialIsland(): Boolean {
        val at = coords
        return at.x in ISLAND_X && at.z in ISLAND_Z
    }

    companion object {
        /** Off switch for the login auto-completion (see [LearningTheRopes]). */
        const val ENABLED = true
        const val COMPLETE = 671

        /** Tutorial Island (Wiki map), with a margin. */
        private val ISLAND_X = 3050..3160
        private val ISLAND_Z = 3050..3140

        val LEARNING_THE_ROPES =
            QuestDef(
                name = "Learning the Ropes",
                varp = { LtrVarps.tutorial },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("Access to the mainland", "Lumbridge Home Teleport"),
                scrollObj = { LtrObjs.bronze_axe },
                listRow = "quest_learningtheropes",
            )
    }
}

internal object LtrVarps : VarpReferences() {
    val tutorial = find("tutorial")
}

internal object LtrObjs : ObjReferences() {
    val bronze_axe = find("bronze_axe")
}
