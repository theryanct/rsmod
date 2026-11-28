package org.rsmod.content.quests.doricsquest

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.miningLvl
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
 * Doric's Quest (OSRS Wiki): Doric, north of Falador, wants 6 clay, 4 copper ore and 2 iron ore.
 * `doricquest` varp: 0 not started, 10 started, 100 complete (cache db end state). Materials are
 * handed in together. Reward: 1300 Mining xp, 180 coins, 1 QP (anvil use is not gated).
 */
class DoricsQuest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DoricsQuest::class.java, "dorics_quest") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, DoricsQuestNpcs.doric, "Talk-to") { talk(it) }
    }

    private fun ProtectedAccess.hasMaterials(): Boolean =
        DoricsQuestObjs.materials.all { (obj, count) -> carries(obj, count) }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        var handedIn = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player already has all of the materials:" to { hasMaterials() },
                        "If the player does not already have all of the materials:" to
                            { !hasMaterials() },
                        "If the player has the exact amounts:" to { true },
                        "If the player does not have 15 Mining:" to { player.miningLvl < 15 },
                    ),
                options =
                    mapOf(
                        "Yes." to {
                            if (player.questState(DORICS_QUEST) == 0) {
                                player.setQuestState(DORICS_QUEST, STARTED)
                            }
                        },
                    ),
                boxes =
                    mapOf(
                        "You hand the clay, copper, and iron to Doric." to {
                            if (hasMaterials()) {
                                for ((obj, count) in DoricsQuestObjs.materials) {
                                    invDel(inv, obj, count)
                                }
                                handedIn = true
                            }
                            false
                        },
                    ),
            )
        when (player.questState(DORICS_QUEST)) {
            0 -> runQuestSection(npc, dialogue, "Starting off", hooks)
            STARTED -> {
                val section =
                    if (hasMaterials()) {
                        "Returning with all the materials"
                    } else {
                        "Talking to Doric without all of the materials"
                    }
                runQuestSection(npc, dialogue, section, hooks)
            }
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Hello traveller, feel free to use my anvils any time.")
                }
        }
        if (handedIn) {
            statAdvance(stats.mining, 1300.0 * xpMods.get(player, stats.mining))
            invAddOrDrop(objRepo, DoricsQuestObjs.coins, 180)
            completeQuest(DORICS_QUEST)
        }
    }

    companion object {
        const val STARTED = 10

        val DORICS_QUEST =
            QuestDef(
                name = "Doric's Quest",
                varp = { DoricsQuestVarps.doricquest },
                complete = 100,
                questPoints = 1,
                rewards = listOf("1,300 Mining XP", "180 coins", "Use of Doric's anvils"),
                scrollObj = { DoricsQuestObjs.iron_ore },
            )
    }
}

// Every cache ref Doric's Quest uses (re-check after the rev 243 cutover).

internal object DoricsQuestNpcs : NpcReferences() {
    val doric = find("doric")
}

internal object DoricsQuestObjs : ObjReferences() {
    val clay = find("clay")
    val copper_ore = find("copper_ore")
    val iron_ore = find("iron_ore")
    val coins = find("coins")

    val materials = listOf(clay to 6, copper_ore to 4, iron_ore to 2)
}

internal object DoricsQuestVarps : VarpReferences() {
    val doricquest = find("doricquest")
}
