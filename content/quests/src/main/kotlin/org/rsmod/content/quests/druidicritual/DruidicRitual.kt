package org.rsmod.content.quests.druidicritual

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
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
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Druidic Ritual (OSRS Wiki). `druidquest` varp (cache db end state 4): 0, 1 Kaqemeex sent you to
 * Sanfew, 2 Sanfew wants the four enchanted meats (raw beef, rat, bear and chicken dipped in the
 * Cauldron of Thunder, Taverley Dungeon), 3 meats handed over, 4 complete (Kaqemeex: 250 Herblore
 * xp, 4 QP). When the QuestGates toggle is on, Herblore checks this quest (Herblore.kt).
 *
 * Not done: the suits of armour guarding the cauldron's door.
 */
class DruidicRitual
@Inject
constructor(private val npcTypes: NpcTypeList, private val xpMods: XpModifiers) :
    PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DruidicRitual::class.java, "druidic_ritual") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, DruidNpcs.kaqemeex, "Talk-to") { talkKaqemeex(it) }
        onNpcOpLabel(npcTypes, DruidNpcs.sanfew, "Talk-to") { talkSanfew(it) }
        for ((raw, enchanted) in DruidObjs.meats) {
            onOpLocU(DruidLocs.cauldron, raw) { dip(raw, enchanted) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(DRUIDIC_RITUAL)

    private fun ProtectedAccess.hasAllMeats(): Boolean =
        DruidObjs.meats.all { (_, enchanted) -> carries(enchanted) }

    private suspend fun ProtectedAccess.talkKaqemeex(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf("Yes." to { if (state == 0) player.setQuestState(DRUIDIC_RITUAL, 1) }),
                // The Wiki's combat warning: only shown below the recommended level.
                boxes =
                    mapOf(
                        COMBAT_WARNING to { player.appearance.combatLevel >= RECOMMENDED_COMBAT },
                    ),
            )
        when (state) {
            0 -> runQuestSection(npc, dialogue, "Starting the quest", hooks)
            1,
            2 -> runQuestSection(npc, dialogue, "Talking to Kaqemeex again")
            MEATS_GIVEN -> {
                runQuestSection(npc, dialogue, "Finishing up")
                statAdvance(stats.herblore, HERBLORE_XP * xpMods.get(player, stats.herblore))
                completeQuest(DRUIDIC_RITUAL)
            }
            else ->
                startDialogue(npc) { chatNpc(happy, "May Guthix guide your herblore, friend.") }
        }
    }

    private suspend fun ProtectedAccess.talkSanfew(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have all four meats:" to { !hasAllMeats() },
                        "If the player does have all four meats:" to { hasAllMeats() },
                    ),
                options =
                    mapOf(
                        "I've been sent to help purify the Varrock stone circle." to {
                            if (state == 1) player.setQuestState(DRUIDIC_RITUAL, 2)
                        },
                    ),
            )
        when (state) {
            1 -> runQuestSection(npc, dialogue, "Talking to Sanfew", hooks)
            2 -> {
                val had = hasAllMeats()
                runQuestSection(npc, dialogue, "Gathering the ingredients", hooks)
                if (had) {
                    for ((_, enchanted) in DruidObjs.meats) invDel(inv, enchanted)
                    player.setQuestState(DRUIDIC_RITUAL, MEATS_GIVEN)
                }
            }
            else -> startDialogue(npc) { chatNpc(neutral, "What can I do for you young 'un?") }
        }
    }

    private fun ProtectedAccess.dip(raw: ObjType, enchanted: ObjType) {
        invDel(inv, raw)
        invAdd(inv, enchanted)
        mes("You dip the meat in the cauldron.")
    }

    companion object {
        private const val MEATS_GIVEN = 3
        private const val RECOMMENDED_COMBAT = 10
        private const val HERBLORE_XP = 250.0
        private const val COMBAT_WARNING =
            "Before starting this quest, be aware that your combat level is lower than the " +
                "recommended level of 10."

        val DRUIDIC_RITUAL =
            QuestDef(
                name = "Druidic Ritual",
                varp = { DruidVarps.druidquest },
                complete = 4,
                questPoints = 4,
                rewards = listOf("250 Herblore XP", "Access to Herblore"),
                scrollObj = { DruidObjs.enchanted_beef },
            )
    }
}

// Every cache ref Druidic Ritual uses (re-check after the rev 243 cutover).

internal object DruidNpcs : NpcReferences() {
    val kaqemeex = find("kaqemeex")
    val sanfew = find("sanfew")
}

internal object DruidLocs : LocReferences() {
    val cauldron = find("cauldron_of_thunder")
}

internal object DruidObjs : ObjReferences() {
    val enchanted_beef = find("enchanted_beef")
    val meats =
        listOf(
            find("raw_beef") to enchanted_beef,
            find("raw_rat_meat") to find("enchanted_rat_meat"),
            find("raw_bear_meat") to find("enchanted_bear_meat"),
            find("raw_chicken") to find("enchanted_chicken"),
        )
}

internal object DruidVarps : VarpReferences() {
    val druidquest = find("druidquest")
}
