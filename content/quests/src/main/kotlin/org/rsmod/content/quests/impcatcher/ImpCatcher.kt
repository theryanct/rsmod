package org.rsmod.content.quests.impcatcher

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Imp Catcher (OSRS Wiki): Wizard Mizgog wants his red, yellow, black and white beads back (imps
 * drop them). `imp` varp: 0 not started, 1 started, 2 complete. All four beads are handed in
 * together (the transcript has no partial hand-in). Reward: amulet of accuracy, 875 Magic xp, 1 QP.
 */
class ImpCatcher
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ImpCatcher::class.java, "imp_catcher") }

    override fun ScriptContext.startup() {
        // The spawned base type is a multinpc; its quest/post-quest variants carry Talk-to.
        for (mizgog in ImpCatcherNpcs.variants) {
            onNpcOpLabel(npcTypes, mizgog, "Talk-to") { talk(it) }
        }
    }

    private fun ProtectedAccess.hasBeads(): Boolean = ImpCatcherObjs.beads.all { carries(it) }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        var finish = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player already has all four beads in their inventory:" to
                            { hasBeads().also { finish = it } },
                        "If the player does not have all four beads in their inventory:" to
                            { !hasBeads() },
                    ),
                options =
                    mapOf(
                        "Yes." to { player.setQuestState(IMP_CATCHER, 1) },
                    ),
            )
        when (player.questState(IMP_CATCHER)) {
            0 -> runQuestSection(npc, dialogue, "Talking to Wizard Mizgog", hooks)
            1 ->
                if (hasBeads()) {
                    finish = true
                    runQuestSection(npc, dialogue, "Giving the beads to Wizard Mizgog", hooks)
                } else {
                    val section =
                        if (ImpCatcherObjs.beads.any { carries(it) }) {
                            "Speaking to Wizard Mizgog with some beads"
                        } else {
                            "Speaking to Wizard Mizgog with no beads"
                        }
                    runQuestSection(npc, dialogue, section, hooks)
                }
            else ->
                startDialogue(npc) { chatNpc(happy, "Thank you for finding my beads, friend!") }
        }
        if (finish && hasBeads()) {
            for (bead in ImpCatcherObjs.beads) {
                invDel(inv, bead)
            }
            runQuestSection(npc, dialogue, "Quest complete")
            invAddOrDrop(objRepo, ImpCatcherObjs.amulet_of_accuracy)
            statAdvance(stats.magic, 875.0 * xpMods.get(player, stats.magic))
            completeQuest(IMP_CATCHER)
        }
    }

    companion object {
        val IMP_CATCHER =
            QuestDef(
                name = "Imp Catcher",
                varp = { ImpCatcherVarps.imp },
                complete = 2,
                questPoints = 1,
                rewards = listOf("875 Magic XP", "Amulet of accuracy"),
                scrollObj = { ImpCatcherObjs.amulet_of_accuracy },
            )
    }
}

// Every cache ref Imp Catcher uses (re-check after the rev 243 cutover).

internal object ImpCatcherNpcs : NpcReferences() {
    val variants = listOf(find("wizard_mizgog_quest"), find("wizard_mizgog_post"))
}

internal object ImpCatcherObjs : ObjReferences() {
    val red_bead = find("red_bead")
    val yellow_bead = find("yellow_bead")
    val black_bead = find("black_bead")
    val white_bead = find("white_bead")
    val amulet_of_accuracy = find("amulet_of_accuracy")

    val beads = listOf(red_bead, yellow_bead, black_bead, white_bead)
}

internal object ImpCatcherVarps : VarpReferences() {
    val imp = find("imp")
}
