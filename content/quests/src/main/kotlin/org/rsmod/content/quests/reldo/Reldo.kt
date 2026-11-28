package org.rsmod.content.quests.reldo

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.knightssword.KnightsSword.Companion.KNIGHTS_SWORD
import org.rsmod.content.quests.shieldofarrav.ArravObjs
import org.rsmod.content.quests.shieldofarrav.ArravVarps
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.SHIELD_OF_ARRAV
import org.rsmod.content.quests.shieldofarrav.ShieldOfArravDialogue
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Reldo, the Varrock Palace librarian: one Talk-to (one handler per op) for every quest that needs
 * him. The Knight's Sword (asking about the Imcando dwarves, state 1 -> 2) comes first, then Shield
 * of Arrav (start, the book, the shield half). `reldo` is a multinpc; its children carry the op.
 */
class Reldo @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    private val arrav by lazy { ShieldOfArravDialogue.dialogue }

    override fun ScriptContext.startup() {
        for (reldo in ReldoNpcs.variants) {
            with(QuestTalk) { default(npcTypes, reldo) { talk(it) } }
        }
    }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        if (player.questState(KNIGHTS_SWORD) == 1) {
            imcandoDwarves(npc)
            return
        }
        shieldOfArrav(npc)
    }

    private suspend fun ProtectedAccess.imcandoDwarves(npc: Npc) {
        startDialogue(npc) {
            chatPlayer(quiz, "What do you know about the Imcando dwarves?")
            chatNpc(
                neutral,
                "The Imcando dwarves, you say? They were the world's most skilled smiths. A few " +
                    "of them still live south of Port Sarim, I believe - near Mudskipper Point.",
            )
            chatNpc(neutral, "They're very fond of redberry pie, I hear.")
        }
        player.setQuestState(KNIGHTS_SWORD, 2)
    }

    private suspend fun ProtectedAccess.shieldOfArrav(npc: Npc) {
        val phoenix = player.vars[ArravVarps.phoenixgang]
        val blackArm = player.vars[ArravVarps.blackarmgang]
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If talking to Reldo for the first time ever:" to { false },
                        "Otherwise:" to { true },
                        "If the player's combat level is less than 10." to {
                            player.appearance.combatLevel < RECOMMENDED_COMBAT
                        },
                    ),
                options =
                    mapOf(
                        "Yes." to {
                            if (player.questState(SHIELD_OF_ARRAV) == 0) {
                                ShieldOfArrav.start(player)
                            }
                        },
                    ),
            )
        val section =
            when {
                player.questState(SHIELD_OF_ARRAV) == 0 -> "Talking to Reldo"
                player.questState(SHIELD_OF_ARRAV) >= 2 -> null
                carries(ArravObjs.shield_phoenix) -> "Talking to Reldo with half of the shield (2)"
                carries(ArravObjs.shield_blackarm) -> "Talking to Reldo with half of the shield"
                phoenix == ShieldOfArrav.STARTED && carries(ArravObjs.book) ->
                    "Talking to Reldo before reading the book"
                phoenix == ShieldOfArrav.STARTED -> "Talking to Reldo before finding the book"
                phoenix == ShieldOfArrav.BOOK_READ -> "Talking to Reldo after reading the book"
                else -> null
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "Hello stranger. Welcome to the library.") }
            return
        }
        runQuestSection(npc, arrav, section, hooks)
        if (phoenix == ShieldOfArrav.BOOK_READ && blackArm == 0) {
            VarPlayerIntMapSetter.set(player, ArravVarps.phoenixgang, ShieldOfArrav.RELDO_TOLD)
        }
    }

    private companion object {
        const val RECOMMENDED_COMBAT = 10
    }
}

internal object ReldoNpcs : NpcReferences() {
    /** Children of the `reldo` multinpc (the base has no ops). */
    val variants = listOf(find("reldo_normal"), find("reldo_withbook"))
}
