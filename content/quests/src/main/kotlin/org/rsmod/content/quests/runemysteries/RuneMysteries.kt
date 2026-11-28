package org.rsmod.content.quests.runemysteries

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rune Mysteries (OSRS Wiki): Duke Horacio's air talisman -> Archmage Sedridor (Wizards' Tower
 * basement) -> research package -> Aubury (Varrock) -> research notes -> Sedridor.
 * `runemysteries` varp (cache db end state 6): 0 not started, 1 has the talisman, 3 has the
 * package, 5 has the notes, 6 complete. Lost items are handed out again by whoever gave them.
 *
 * Aubury's Talk-to belongs to the Runecraft module (essence mine teleport), so his steps run when
 * the research package or notes are used on him. The Runecraft gate on this quest is the
 * QuestGates feature toggle (default off).
 */
class RuneMysteries
@Inject
constructor(private val npcTypes: NpcTypeList, private val objRepo: ObjRepository) :
    PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(RuneMysteries::class.java, "rune_mysteries")
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) { default(npcTypes, RuneMysteriesNpcs.duke) { talkDuke(it) } }
        for (sedridor in RuneMysteriesNpcs.sedridors) {
            with(QuestTalk) { default(npcTypes, sedridor) { talkSedridor(it) } }
        }
        val aubury = RuneMysteriesNpcs.aubury
        onOpNpcU(aubury, RuneMysteriesObjs.research_package) { auburyStep(it.npc) }
        onOpNpcU(aubury, RuneMysteriesObjs.research_notes) { auburyStep(it.npc) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(RUNE_MYSTERIES)

    private fun ProtectedAccess.give(obj: ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    private suspend fun ProtectedAccess.talkDuke(npc: Npc) {
        val talisman = RuneMysteriesObjs.air_talisman
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player lost the talisman:" to { !carries(talisman) },
                        "If the player has the talisman:" to { carries(talisman) },
                    ),
                boxes =
                    mapOf(
                        "The Duke hands you the talisman." to {
                            give(talisman)
                            if (state == 0) player.setQuestState(RUNE_MYSTERIES, HAS_TALISMAN)
                            false
                        },
                    ),
            )
        when (state) {
            0 -> runQuestSection(npc, dialogue, "Talking to Duke Horacio", hooks)
            HAS_TALISMAN -> runQuestSection(npc, dialogue, "Talking to Duke Horacio again", hooks)
            else ->
                startDialogue(npc) { chatNpc(neutral, "Greetings. Welcome to my castle.") }
        }
    }

    private suspend fun ProtectedAccess.talkSedridor(npc: Npc) {
        val talisman = RuneMysteriesObjs.air_talisman
        val pkg = RuneMysteriesObjs.research_package
        val notes = RuneMysteriesObjs.research_notes
        var complete = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the talisman:" to { carries(talisman) },
                        "If the player does have the talisman:" to { carries(talisman) },
                        "If the player does not have the talisman:" to { !carries(talisman) },
                        "If the player doesn't have the talisman:" to { !carries(talisman) },
                        WITHOUT_DOUBT to { true },
                        "If the player gave the talisman to while doubting his identity:" to
                            { false },
                        "If the player lost the package:" to { !carries(pkg) },
                        "If the player has the package:" to { carries(pkg) },
                        "If the player has the notes:" to { carries(notes) },
                        "If the player does not have the notes:" to { !carries(notes) },
                    ),
                boxes =
                    mapOf(
                        "You hand the talisman to Sedridor." to {
                            if (carries(talisman)) invDel(inv, talisman)
                            false
                        },
                        "Sedridor hands you a package." to {
                            give(pkg)
                            player.setQuestState(RUNE_MYSTERIES, HAS_PACKAGE)
                            false
                        },
                        "You hand the notes to Sedridor." to {
                            if (carries(notes)) {
                                invDel(inv, notes)
                                complete = true
                            }
                            false
                        },
                    ),
            )
        when (state) {
            0 ->
                startDialogue(npc) {
                    chatNpc(neutral, "Welcome, adventurer, to the Wizards' Tower.")
                }
            HAS_TALISMAN -> runQuestSection(npc, dialogue, "Talking to Archmage Sedridor", hooks)
            HAS_PACKAGE -> runQuestSection(npc, dialogue, "Talking to Sedridor again", hooks)
            HAS_NOTES -> runQuestSection(npc, dialogue, "Talking to Sedridor", hooks)
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Thanks to you, we have rediscovered the art of Runecrafting!")
                }
        }
        if (complete) {
            give(RuneMysteriesObjs.air_talisman)
            completeQuest(RUNE_MYSTERIES)
        }
    }

    private suspend fun ProtectedAccess.auburyStep(npc: Npc) {
        val pkg = RuneMysteriesObjs.research_package
        val notes = RuneMysteriesObjs.research_notes
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the package:" to { carries(pkg) },
                        "If the player does not have the package:" to { !carries(pkg) },
                        "If the player lost the notes:" to { !carries(notes) },
                        "If the player has the notes:" to { carries(notes) },
                    ),
                boxes =
                    mapOf(
                        "You hand the package to Aubury." to {
                            if (carries(pkg)) invDel(inv, pkg)
                            false
                        },
                        "Aubury hands you some research notes." to {
                            give(notes)
                            player.setQuestState(RUNE_MYSTERIES, HAS_NOTES)
                            false
                        },
                    ),
            )
        when (state) {
            HAS_PACKAGE -> runQuestSection(npc, dialogue, "Talking to Aubury", hooks)
            HAS_NOTES -> runQuestSection(npc, dialogue, "Talking to Aubury again", hooks)
            else -> startDialogue(npc) { chatNpc(neutral, "I don't need that, thank you.") }
        }
    }

    companion object {
        private const val WITHOUT_DOUBT =
            "If the player gave the talisman to him without doubting his identity:"

        const val HAS_TALISMAN = 1
        const val HAS_PACKAGE = 3
        const val HAS_NOTES = 5

        val RUNE_MYSTERIES =
            QuestDef(
                name = "Rune Mysteries",
                varp = { RuneMysteriesVarps.runemysteries },
                complete = 6,
                questPoints = 1,
                rewards = listOf("Air talisman", "Access to the Rune essence mine"),
                scrollObj = { RuneMysteriesObjs.air_talisman },
            )
    }
}

// Every cache ref Rune Mysteries uses (re-check after the rev 243 cutover).

internal object RuneMysteriesNpcs : NpcReferences() {
    val duke = find("duke_of_lumbridge")
    val aubury = find("aubury_2op")

    /** The spawned head_wizard is a multinpc; its variants carry Talk-to. */
    val sedridors = listOf(find("head_wizard_1op"), find("head_wizard_2op"))
}

internal object RuneMysteriesObjs : ObjReferences() {
    val air_talisman = find("air_talisman")
    val research_package = find("research_package")
    val research_notes = find("research_notes")
}

internal object RuneMysteriesVarps : VarpReferences() {
    val runemysteries = find("runemysteries")
}
