package org.rsmod.content.quests.aggie

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.princeali.PrinceAliRescue
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Aggie the witch (Draynor), shared by the quests that need her: Talk-to runs her Prince Ali
 * Rescue menu (skin paste from ashes, pot of flour, bucket of water and redberries), and "Can you
 * make dyes" / the Dyes op make red / yellow / blue dye (3 redberries / 2 onions / 2 woad leaves +
 * 5 coins) for Goblin Diplomacy and the blonde wig. Her other chat options are not done.
 */
class Aggie
@Inject
constructor(private val npcTypes: NpcTypeList, private val objRepo: ObjRepository) :
    PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(PrinceAliRescue::class.java, "prince_ali_rescue")
    }

    override fun ScriptContext.startup() {
        for (aggie in AggieNpcs.variants) {
            onNpcOpLabel(npcTypes, aggie, "Talk-to") { talk(it) }
            onNpcOpLabel(npcTypes, aggie, "Dyes") { dyes(it) }
        }
    }

    private fun ProtectedAccess.hasPasteIngredients(): Boolean =
        carries(AggieObjs.ashes) &&
            carries(AggieObjs.pot_flour) &&
            carries(AggieObjs.bucket_water) &&
            carries(AggieObjs.redberries)

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        var wantsDyes = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have all the required items:" to {
                            !hasPasteIngredients()
                        },
                        "If the player has all the required items:" to { hasPasteIngredients() },
                    ),
                options = mapOf("Can you make dyes for me please?" to { wantsDyes = true }),
                boxes =
                    mapOf(
                        PASTE_BOX to {
                            if (hasPasteIngredients()) {
                                invDel(inv, AggieObjs.ashes)
                                invDel(inv, AggieObjs.pot_flour)
                                invDel(inv, AggieObjs.bucket_water)
                                invDel(inv, AggieObjs.redberries)
                                invAddOrDrop(objRepo, AggieObjs.empty_pot)
                                invAddOrDrop(objRepo, AggieObjs.bucket)
                                invAddOrDrop(objRepo, AggieObjs.skin_paste)
                            }
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Aggie", hooks)
        if (wantsDyes) {
            dyes(npc)
        }
    }

    private suspend fun ProtectedAccess.dyes(npc: Npc) {
        var pick = 0
        startDialogue(npc) {
            chatNpc(happy, "What can I help you with, dearie?")
            pick =
                choice4(
                    "Can you make red dye for me? (3 redberries, 5 coins)",
                    1,
                    "Can you make yellow dye for me? (2 onions, 5 coins)",
                    2,
                    "Can you make blue dye for me? (2 woad leaves, 5 coins)",
                    3,
                    "Never mind.",
                    0,
                )
        }
        val (input, count, dye) =
            when (pick) {
                1 -> Triple(AggieObjs.redberries, 3, AggieObjs.red_dye)
                2 -> Triple(AggieObjs.onion, 2, AggieObjs.yellow_dye)
                3 -> Triple(AggieObjs.woad_leaf, 2, AggieObjs.blue_dye)
                else -> return
            }
        if (!carries(input, count) || !carries(AggieObjs.coins, DYE_PRICE)) {
            startDialogue(npc) { chatNpc(neutral, "You don't have what I need for that, dearie.") }
            return
        }
        invDel(inv, input, count)
        invDel(inv, AggieObjs.coins, DYE_PRICE)
        invAdd(inv, dye)
        startDialogue(npc) { chatNpc(happy, "There you go, dearie.") }
    }

    private companion object {
        const val DYE_PRICE = 5
        const val PASTE_BOX =
            "You hand the ash, flour, water and redberries to Aggie. She tips the ingredients " +
                "into a cauldron and mutters some words."
    }
}

// Every cache ref Aggie uses (re-check after the rev 243 cutover).

internal object AggieNpcs : NpcReferences() {
    val variants = listOf(find("aggie_1op"), find("aggie_2ops"))
}

internal object AggieObjs : ObjReferences() {
    val ashes = find("ashes")
    val pot_flour = find("pot_flour")
    val empty_pot = find("pot_empty")
    val bucket_water = find("bucket_water")
    val bucket = find("bucket_empty")
    val redberries = find("redberries")
    val skin_paste = find("skinpaste")
    val red_dye = find("reddye")
    val yellow_dye = find("yellowdye")
    val blue_dye = find("bluedye")
    val onion = find("onion")
    val woad_leaf = find("woadleaf")
    val coins = find("coins")
}
