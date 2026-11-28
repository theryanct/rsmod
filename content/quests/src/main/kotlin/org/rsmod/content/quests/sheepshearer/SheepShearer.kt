package org.rsmod.content.quests.sheepshearer

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sheep Shearer (OSRS Wiki): Fred the Farmer wants 20 balls of wool. Dialogue from the Wiki
 * transcript (`sheep_shearer.dialogue`); state is the cache `sheep` varp: 0 not started,
 * 1 + balls delivered while in progress (1-20), 21 complete. Balls can be handed in over several
 * visits. Reward: 1 quest point, 150 Crafting xp and 60 coins.
 */
class SheepShearer
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(SheepShearer::class.java, "sheep_shearer")
    }

    override fun ScriptContext.startup() {
        // Fred's own conversation is the QuestTalk default (Cold War asks him about penguins).
        with(QuestTalk) { default(npcTypes, SheepShearerNpcs.fred) { talk(it) } }
    }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        val state = player.questState(SHEEP_SHEARER)
        if (state >= SHEEP_SHEARER.complete) {
            startDialogue(npc) { chatNpc(happy, "Thanks again for shearing those sheep!") }
            return
        }
        if (state == 0) {
            val hooks = Hooks(this, given = 0, remaining = BALLS)
            startDialogue(npc) {
                QuestDialogueRunner(hooks).run(this, dialogue.section("Starting off"))
            }
            if (hooks.instantHandIn && invTotal(inv, SheepShearerObjs.ball_of_wool) >= BALLS) {
                invDel(inv, SheepShearerObjs.ball_of_wool, BALLS)
                finish()
            }
            return
        }
        val delivered = state - 1
        val carried = invTotal(inv, SheepShearerObjs.ball_of_wool)
        val given = min(carried, BALLS - delivered)
        val section =
            when {
                given > 0 && delivered + given >= BALLS -> "Finishing up"
                given > 0 -> "Handing in any balls of wool"
                invTotal(inv, SheepShearerObjs.wool) > 0 -> "With regular wool"
                else -> "Before delivering any balls of wool"
            }
        if (given > 0) {
            invDel(inv, SheepShearerObjs.ball_of_wool, given)
            // The last hand-in leaves the end state to completeQuest (it shows the scroll).
            player.setQuestState(SHEEP_SHEARER, minOf(1 + delivered + given, SHEEP_SHEARER.complete - 1))
        }
        val hooks = Hooks(this, given = given, remaining = BALLS - delivered - given)
        startDialogue(npc) { QuestDialogueRunner(hooks).run(this, dialogue.section(section)) }
        if (delivered + given >= BALLS) {
            finish()
        }
    }

    private fun ProtectedAccess.finish() {
        statAdvance(stats.crafting, 150.0 * xpMods.get(player, stats.crafting))
        invAddOrDrop(objRepo, SheepShearerObjs.coins, 60)
        completeQuest(SHEEP_SHEARER)
    }

    private class Hooks(
        private val access: ProtectedAccess,
        private val given: Int,
        private val remaining: Int,
    ) : QuestDialogueHooks {
        /** The player already carried 20 balls of wool when starting: hand them in at once. */
        var instantHandIn = false

        override fun condition(text: String): Boolean {
            val shears = access.invTotal(access.inv, SheepShearerObjs.shears) > 0
            return when (text) {
                "If the player doesn't have shears:" -> !shears
                "If the player has shears already:" -> shears
                "If the player has shears:" -> shears
                "If the player does not have shears:" -> !shears
                "If the player already has 20 balls of wool in their inventory:" -> {
                    val have = access.invTotal(access.inv, SheepShearerObjs.ball_of_wool)
                    (have >= BALLS).also { instantHandIn = it }
                }
                else -> false
            }
        }

        override suspend fun Dialogue.onOption(label: String) {
            if (label == "Yes." && access.player.questState(SHEEP_SHEARER) == 0) {
                access.player.setQuestState(SHEEP_SHEARER, 1)
            }
        }

        override suspend fun Dialogue.onBox(text: String): Boolean {
            if (text.startsWith("Fred gives you")) {
                access.invAdd(access.inv, SheepShearerObjs.shears)
            }
            return false
        }

        override fun rewrite(text: String): String =
            text
                .replace("[1-19] [ball/balls]", "$given ${if (given == 1) "ball" else "balls"}")
                .replace("[1-19]", "$remaining")
                .replace("[#]", "$remaining")
                .replace("collect 20 more", "collect $remaining more")
    }

    companion object {
        const val BALLS = 20

        val SHEEP_SHEARER =
            QuestDef(
                name = "Sheep Shearer",
                varp = { SheepShearerVarps.sheep },
                complete = 21,
                questPoints = 1,
                rewards = listOf("150 Crafting XP", "60 coins"),
                scrollObj = { SheepShearerObjs.ball_of_wool },
            )
    }
}

// Every cache ref Sheep Shearer uses lives below (re-check after the rev 243 cutover).

internal object SheepShearerNpcs : NpcReferences() {
    val fred = find("fred_the_farmer")
}

internal object SheepShearerObjs : ObjReferences() {
    val ball_of_wool = find("ball_of_wool")
    val wool = find("wool")
    val shears = find("shears")
    val coins = find("coins")
}

internal object SheepShearerVarps : VarpReferences() {
    val sheep = find("sheep")
}
