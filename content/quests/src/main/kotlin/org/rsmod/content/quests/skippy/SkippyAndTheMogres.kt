package org.rsmod.content.quests.skippy

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.plaguecity.PlagueCity.Companion.NOTE
import org.rsmod.content.quests.plaguecity.PlagueCity.Companion.PLAGUE_CITY
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Skippy and the Mogres (OSRS Wiki miniquest). The cache's `skippy_state` varbit (0-3) drives the
 * `multi_skippy` multinpc: drunk, damp, hungover, sober (3 = done).
 * - A bucket of water on Skippy (or Sober-Up): he's damp and wants nettle tea.
 * - Nettle tea on him: he's hungover and wants a hangover cure (chocolate dust in a bucket of milk,
 *   then snape grass; the mixing is Plague City's).
 * - The hangover cure: he tells you about the Mogres at Mudskipper Point.
 *
 * Simplified: Skippy is never "moving too fast" to be drenched.
 */
class SkippyAndTheMogres @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(SkippyAndTheMogres::class.java, "skippy_and_the_mogres")
    }

    override fun ScriptContext.startup() {
        for (skippy in SkippyNpcs.all) {
            onNpcOpLabel(npcTypes, skippy, "Talk-To") { talk(it) }
            onNpcOpLabel(npcTypes, skippy, "Sober-Up") { soberUp(it) }
            onOpNpcU(skippy, SkippyObjs.bucket_water) { drench(it.npc) }
            onOpNpcU(skippy, SkippyObjs.nettle_tea) { giveTea(it.npc) }
            onOpNpcU(skippy, SkippyObjs.hangover_cure) { giveCure(it.npc) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SKIPPY_AND_THE_MOGRES)

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        when (state) {
            DRUNK ->
                startDialogue(npc) {
                    // Authored (not Wiki): his shouting at the sea (Wiki trivia).
                    chatNpc(neutral, "The horror! The horror!")
                    chatPlayer(neutral, "He's too drunk to make any sense. Maybe some cold water would help.")
                }
            DAMP ->
                if (carries(SkippyObjs.nettle_tea)) giveTea(npc)
                else runQuestSection(npc, dialogue, "Talking to him before obtaining nettle tea")
            HUNGOVER ->
                if (carries(SkippyObjs.hangover_cure)) giveCure(npc)
                else runQuestSection(npc, dialogue, "Talking to him before obtaining hangover cure")
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): after the miniquest.
                    chatNpc(neutral, "Thanks again for the cure. Watch out for those Mogres!")
                }
        }
    }

    private suspend fun ProtectedAccess.soberUp(npc: Npc) {
        if (state != DRUNK) {
            talk(npc)
            return
        }
        if (!carries(SkippyObjs.bucket_water)) {
            runQuestSection(npc, dialogue, "Using the Sober-Up option on Skippy without a bucket of water")
            return
        }
        var thrown = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Sober-Up option:" to { true },
                        "Use bucket of water -> Skippy:" to { false },
                        "If Skippy is moving around:" to { false },
                        "If Skippy is immobile:" to { true },
                    ),
                options = mapOf("Throw the water!" to { thrown = true }),
            )
        runQuestSection(npc, dialogue, DRENCH, hooks)
        if (thrown) drench(npc)
    }

    private suspend fun ProtectedAccess.drench(npc: Npc) {
        if (state != DRUNK) {
            talk(npc)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("Sober-Up option:" to { false }, "Use bucket of water -> Skippy:" to { true })
            )
        runQuestSection(npc, dialogue, DRENCH, hooks)
        invReplace(inv, SkippyObjs.bucket_water, 1, SkippyObjs.bucket)
        player.setQuestState(SKIPPY_AND_THE_MOGRES, DAMP)
    }

    private suspend fun ProtectedAccess.giveTea(npc: Npc) {
        if (state != DAMP) {
            talk(npc)
            return
        }
        val learned = player.questState(PLAGUE_CITY) >= NOTE
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has learned the hangover cure recipe during Plague City:" to
                            { learned },
                        "If the player has not learned the hangover cure recipe:" to { !learned },
                    )
            )
        runQuestSection(npc, dialogue, "Giving Skippy nettle tea", hooks)
        invReplace(inv, SkippyObjs.nettle_tea, 1, SkippyObjs.bowl)
        player.setQuestState(SKIPPY_AND_THE_MOGRES, HUNGOVER)
    }

    private suspend fun ProtectedAccess.giveCure(npc: Npc) {
        if (state != HUNGOVER) {
            talk(npc)
            return
        }
        runQuestSection(npc, dialogue, "Bringing him the hangover cure")
        invReplace(inv, SkippyObjs.hangover_cure, 1, SkippyObjs.bucket)
        completeQuest(SKIPPY_AND_THE_MOGRES)
    }

    companion object {
        const val DRUNK = 0
        const val DAMP = 1
        const val HUNGOVER = 2
        const val COMPLETE = 3

        private const val DRENCH = "Using a bucket of water on Skippy to sober him up"

        val SKIPPY_AND_THE_MOGRES =
            QuestDef(
                name = "Skippy and the Mogres",
                varbit = { SkippyVarBits.state },
                complete = COMPLETE,
                questPoints = 0,
                rewards = listOf("The ability to slay Mogres"),
                scrollObj = { SkippyObjs.hangover_cure },
                listRow = "miniquest_skippyandthemogres",
            )
    }
}

// Every cache ref Skippy and the Mogres uses (re-check after cache updates).

internal object SkippyNpcs : NpcReferences() {
    /** The children of `multi_skippy` (skippy_state 0-3). */
    val all =
        listOf(find("skippy"), find("damp_skippy"), find("hungover_skippy"), find("sober_skippy"))
}

internal object SkippyObjs : ObjReferences() {
    val bucket_water = find("bucket_water")
    val bucket = find("bucket_empty")
    val nettle_tea = find("bowl_nettletea")
    val bowl = find("bowl_empty")
    val hangover_cure = find("hangover_cure")
}

internal object SkippyVarBits : VarBitReferences() {
    val state = find("skippy_state")
}
