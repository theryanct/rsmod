package org.rsmod.content.quests.cooksassistant

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Cook's Assistant (OSRS Wiki): the Lumbridge Castle cook needs a bucket of milk, a pot of flour
 * and an egg. Dialogue comes from the Wiki transcript (`cooks_assistant.dialogue`); state is the
 * cache `cookquest` varp (0 not started, 1 started, 2 complete). Reward: 1 quest point and 300
 * Cooking xp. Simplification: the three ingredients are handed in together.
 */
class CooksAssistant
@Inject
constructor(private val npcTypes: NpcTypeList, private val xpMods: XpModifiers) :
    PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(CooksAssistant::class.java, "cooks_assistant")
    }

    override fun ScriptContext.startup() {
        val cook = CooksAssistantRefs.cook
        when (npcTypes[cook].op.indexOfFirst { it.equals("Talk-to", ignoreCase = true) } + 1) {
            1 -> onOpNpc1(cook) { talk(it.npc) }
            2 -> onOpNpc2(cook) { talk(it.npc) }
            3 -> onOpNpc3(cook) { talk(it.npc) }
            4 -> onOpNpc4(cook) { talk(it.npc) }
            5 -> onOpNpc5(cook) { talk(it.npc) }
        }
    }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        val hooks = Hooks(this)
        val state = player.questState(COOKS_ASSISTANT)
        startDialogue(npc) {
            when (state) {
                0 -> QuestDialogueRunner(hooks).run(this, dialogue.section("Starting off"))
                1 ->
                    QuestDialogueRunner(hooks)
                        .run(this, dialogue.section("Talking to the Cook again"))
                else -> chatNpc(happy, "Thanks for your help, the Duke enjoyed his cake!")
            }
        }
        if (hooks.handIn && hasIngredients()) {
            finish()
        }
    }

    private fun ProtectedAccess.hasIngredients(): Boolean =
        CooksAssistantRefs.ingredients.all { invTotal(inv, it) > 0 }

    private fun ProtectedAccess.finish() {
        for (ingredient in CooksAssistantRefs.ingredients) {
            invDel(inv, ingredient)
        }
        statAdvance(stats.cooking, 300.0 * xpMods.get(player, stats.cooking))
        completeQuest(COOKS_ASSISTANT)
    }

    private inner class Hooks(private val access: ProtectedAccess) : QuestDialogueHooks {
        /** Set when the transcript reaches the hand-in branch with all ingredients. */
        var handIn = false

        private fun has(obj: ObjType): Boolean =
            access.invTotal(access.inv, obj) > 0

        override fun condition(text: String): Boolean {
            val all = access.hasIngredients()
            val any = CooksAssistantRefs.ingredients.any { has(it) }
            return when (text) {
                "If the player already has the necessary items:" -> all.also { handIn = it }
                "If the player doesn't have the necessary items:" -> !all
                "If the player has an empty pot with them:" -> has(CooksAssistantRefs.pot)
                "If the player does not have an empty pot with them:" ->
                    !has(CooksAssistantRefs.pot)
                "If the player has an empty bucket with them:" -> has(CooksAssistantRefs.bucket)
                "If the player does not have an empty bucket with them:" ->
                    !has(CooksAssistantRefs.bucket)
                "If the player has not obtained anything yet:" -> !any
                "If the player has the ingredients:" -> any
                "If the player has not brought all the ingredients:" -> !all
                "If the player has brought all the ingredients:" -> all.also { handIn = it }
                else -> text.startsWith("If the player hasn't handed in")
            }
        }

        override suspend fun Dialogue.onOption(label: String) {
            if (label == "Yes." && access.player.questState(COOKS_ASSISTANT) == 0) {
                access.player.setQuestState(COOKS_ASSISTANT, 1)
            }
        }
    }

    companion object {
        val COOKS_ASSISTANT =
            QuestDef(
                name = "Cook's Assistant",
                varp = { CooksAssistantRefs.varp },
                complete = 2,
                questPoints = 1,
                rewards = listOf("300 Cooking XP", "Permission to use the Cook's range"),
                scrollObj = { CooksAssistantRefs.cake },
            )
    }
}

/** Every cache ref Cook's Assistant uses lives below (re-check after the rev 243 cutover). */
internal object CooksAssistantRefs {
    val cook get() = CooksAssistantNpcs.cook
    val varp get() = CooksAssistantVarps.cookquest
    val cake get() = CooksAssistantObjs.cake
    val pot get() = CooksAssistantObjs.pot_empty
    val bucket get() = CooksAssistantObjs.bucket_empty
    val ingredients: List<ObjType>
        get() =
            listOf(
                CooksAssistantObjs.bucket_milk,
                CooksAssistantObjs.pot_flour,
                CooksAssistantObjs.egg,
            )
}

internal object CooksAssistantNpcs : NpcReferences() {
    val cook = find("cook")
}

internal object CooksAssistantObjs : ObjReferences() {
    val bucket_milk = find("bucket_milk")
    val pot_flour = find("pot_flour")
    val egg = find("egg")
    val cake = find("cake")
    val pot_empty = find("pot_empty")
    val bucket_empty = find("bucket_empty")
}

internal object CooksAssistantVarps : VarpReferences() {
    val cookquest = find("cookquest")
}
