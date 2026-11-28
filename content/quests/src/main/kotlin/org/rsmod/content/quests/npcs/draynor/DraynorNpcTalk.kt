package org.rsmod.content.quests.npcs.draynor

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Talk-to for Draynor Village npcs that said nothing (Ryan's login log): their Wiki transcripts'
 * standard dialogue. Each is a [QuestTalk.default], so a quest can still claim the conversation.
 * - Twiggy O'Korn: the "not completed all Achievement Diaries" chat (diaries aren't built).
 * - Diango: his greeting menu (holiday items / the shop aren't opened from it).
 * - Town Crier, Miss Schism, the bank guard, the Wise Old Man: their standard / first chats
 *   (Miss Schism and the bank guard as before the bank-robbery recording is watched).
 * - Master Farmer: one of the Wiki's five greetings at random.
 * Branches the transcripts mark as interfaces or rewards (cape purchase, holiday item retrieval,
 * the Wise Old Man's jobs) just end the conversation.
 */
class DraynorNpcTalk
@Inject
constructor(private val npcTypes: NpcTypeList, private val random: GameRandom) : PluginScript() {
    private val dialogues = HashMap<String, QuestDialogue>()

    private fun dialogue(name: String): QuestDialogue =
        dialogues.getOrPut(name) { QuestDialogue.load(DraynorNpcTalk::class.java, name) }

    override fun ScriptContext.startup() {
        talk(DraynorNpcs.twiggy, "twiggy_okorn", "If the player has not completed all Achievement Diaries")
        talk(DraynorNpcs.diango, "diango", "Standard dialogue")
        talk(DraynorNpcs.town_crier, "town_crier", "Standard dialogue")
        talk(DraynorNpcs.bank_guard, "bank_guard", "Before knowing about the bank robbery")
        // The Wise Old Man's base multinpc has no ops of its own: bind op1 directly.
        onOpNpc1(DraynorNpcs.wise_old_man) { runQuestSection(it.npc, dialogue("wise_old_man"), "Initial dialogue") }
        talk(
            DraynorNpcs.miss_schism,
            "miss_schism",
            "Standard dialogue",
            conditions = setOf("If the player hasn't watched the bank robbery cutscene:"),
        )
        with(QuestTalk) { default(npcTypes, DraynorNpcs.master_farmer) { masterFarmer(it) } }
    }

    private fun ScriptContext.talk(
        npc: NpcType,
        file: String,
        section: String,
        conditions: Set<String> = emptySet(),
    ) {
        val hooks = SimpleQuestHooks(defaultCondition = { it in conditions })
        with(QuestTalk) {
            default(npcTypes, npc) { target -> runQuestSection(target, dialogue(file), section, hooks) }
        }
    }

    private suspend fun ProtectedAccess.masterFarmer(npc: Npc) {
        val pick = random.of(1, FARMER_GREETINGS)
        runQuestBranch(npc, dialogue("master_farmer"), FARMER_SECTION, "Dialogue $pick")
    }

    private companion object {
        const val FARMER_SECTION = "Talking to Master Farmers in Varlamore"
        const val FARMER_GREETINGS = 5
    }
}

internal object DraynorNpcs : NpcReferences() {
    val twiggy = find("diary_queen")
    val diango = find("aprilfoolshorsesalesman")
    val town_crier = find("pmod_town_crier_draynor")
    val master_farmer = find("varlamore_master_farmer_f_3")
    val miss_schism = find("wom_gossip")
    val bank_guard = find("wom_bankguard")

    /** The Wise Old Man's base multinpc (npc op events key on the base). */
    val wise_old_man = find("wom_multi")
}
