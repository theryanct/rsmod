package org.rsmod.content.quests.lostlover

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Lost Lover (OSRS Wiki miniquest, Tlati Rainforest). An unlisted miniquest: no quest-list dbrow,
 * no quest points, no scroll, so it has no [org.rsmod.content.quests.framework.QuestDef] and no
 * BuiltQuests entry; the two Talk-tos are QuestTalk defaults.
 *
 * State is the cache's two varbits on `varlamore_general`, which the client reads through the
 * multinpcs: `tlati_lost_lover_fabia` / `tlati_lost_lover_xilo` 0 not met, 1 met (either shows
 * the waiting npc at her own tree), 2 departed (hides her and shows her `_united` npc, spawned next
 * to the other one). Whoever the player meets second leaves to join the first.
 */
class LostLover @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(LostLover::class.java, "lost_lover") }

    private val bothSpeakers: Map<String, NpcType> by lazy {
        mapOf("Fabia" to LlNpcs.fabia, "Xilo" to LlNpcs.xilo)
    }

    override fun ScriptContext.startup() {
        // The waiting and the `_united` multinpcs share one child each, which carries Talk-to.
        with(QuestTalk) {
            default(npcTypes, LlNpcs.fabia) { talk(it, self = LlVarBits.fabia, other = LlVarBits.xilo, name = "Fabia") }
            default(npcTypes, LlNpcs.xilo) { talk(it, self = LlVarBits.xilo, other = LlVarBits.fabia, name = "Xilo") }
        }
    }

    private suspend fun ProtectedAccess.talk(npc: Npc, self: VarBitType, other: VarBitType, name: String) {
        if (player.vars[self] >= DEPARTED || player.vars[other] >= DEPARTED) {
            runQuestSection(npc, dialogue, "Talking to Fabia or Xilo", speakers = bothSpeakers)
            return
        }
        val otherMet = player.vars[other] >= MET
        val otherName = if (name == "Fabia") "Xilo" else "Fabia"
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not already talked to $otherName:" to { !otherMet },
                        "If the player has already talked to $otherName:" to { otherMet },
                    )
            )
        runQuestSection(npc, dialogue, "Talking to $name", hooks)
        VarPlayerIntMapSetter.set(player, self, if (otherMet) DEPARTED else MET)
    }

    private companion object {
        const val MET = 1
        const val DEPARTED = 2
    }
}

// Every cache ref Lost Lover uses (re-check after cache updates).

internal object LlNpcs : NpcReferences() {
    /** The Talk-to children of `tlati_lost_lover_fabia(_united)` / `tlati_lost_lover_xilo(_united)`. */
    val fabia = find("tlati_lost_lover_fabia_vis")
    val xilo = find("tlati_lost_lover_xilo_vis")
}

internal object LlVarBits : VarBitReferences() {
    val fabia = find("tlati_lost_lover_fabia")
    val xilo = find("tlati_lost_lover_xilo")
}
