package org.rsmod.content.quests.ghostsahoy

import jakarta.inject.Inject
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Ghosts Ahoy, the translation manual: Ak-Haranu wants an oak longbow signed by Robin, who only
 * plays Runedraw (25 coins a game) and never pays up; once he owes you 100 coins he signs the bow
 * rather than have the ghosts told. Runedraw: a bag of ten runes, air (1 point) up to nature (9)
 * and a death rune; players take turns drawing or holding, drawing death loses, and the higher
 * total wins once both hold. Robin's debt is bits 15-21 of ahoy_progress; `ahoy_subquest_bow` is 1
 * asked, 2 signed, 3 manual given.
 */
class GhostsAhoyManual @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, GaNpcs.akharanu, "Talk-to") { talkAkharanu(it) }
        onNpcOpLabel(npcTypes, GaNpcs.robin, "Talk-to") { talkRobin(it) }
    }

    private suspend fun ProtectedAccess.talkAkharanu(npc: Npc) {
        val bow = bit(GaVarBits.bow)
        when {
            gaState() < GhostsAhoy.REMEMBERED ->
                startDialogue(npc) { chatNpc(happy, "Ak-Haranu trader from far east. You want trade?") }
            bow == 0 -> {
                var agree = false
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(
                                "Okay, wait here - I'll get you your bow." to { agree = true },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Ak-Haranu", hooks, GaNpcs.speakers)
                if (agree) setBit(GaVarBits.bow, BOW_ASKED)
            }
            bow == BOW_ASKED -> {
                val has = carries(GaObjs.longbow)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have an oak longbow in their inventory:" to { !has },
                                "If the player has an oak longbow in their inventory:" to { has },
                            ),
                        rewrites = { it.replace("{player}", player.displayName) },
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Subsequent dialogue with Ak-Haranu",
                    hooks,
                    GaNpcs.speakers,
                )
            }
            bow == BOW_SIGNED && carries(GaObjs.longbow_signed) -> {
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Ak-Haranu after getting the bow signed",
                    SimpleQuestHooks(),
                    GaNpcs.speakers,
                )
                invDel(inv, GaObjs.longbow_signed)
                invAdd(inv, GaObjs.manual)
                setBit(GaVarBits.bow, BOW_TRADED)
            }
            bow == BOW_SIGNED ->
                startDialogue(npc) { chatNpc(neutral, "You have bow signed by Master Bowman? Bring it, please.") }
            else -> {
                val lost =
                    !carries(GaObjs.manual) && bit(GaVarBits.given_manual) == 0
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player lost the Translation manual:" to { lost },
                                "If the player has the Translation manual:" to { !lost },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Subsequent dialogue with Ak-Haranu (2)",
                    hooks,
                    GaNpcs.speakers,
                )
                if (lost && !inv.isFull()) invAdd(inv, GaObjs.manual)
            }
        }
    }

    /* Robin */

    private fun ProtectedAccess.debt(): Int = (player.vars[GaVarps.progress] shr DEBT_SHIFT) and DEBT_MASK

    private fun ProtectedAccess.setDebt(value: Int) {
        val v = player.vars[GaVarps.progress]
        val cleared = v and (DEBT_MASK shl DEBT_SHIFT).inv()
        val next = cleared or (value.coerceIn(0, DEBT_MASK) shl DEBT_SHIFT)
        VarPlayerIntMapSetter.set(player, GaVarps.progress, next)
    }

    private suspend fun ProtectedAccess.talkRobin(npc: Npc) {
        val clean = carries(GaObjs.bedsheet)
        when {
            bit(GaVarBits.bow) >= BOW_SIGNED ->
                gaRun(npc, "Talking to Robin after getting the bow signed")
            bit(GaVarBits.requested_sheet) != 0 && clean && !carries(GaObjs.longbow) -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has a clean bedsheet in their inventory:" to { true },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Robin with the innkeeper's task",
                    hooks,
                    GaNpcs.speakers,
                )
                invDel(inv, GaObjs.bedsheet)
                setBit(GaVarBits.requested_sheet, 0)
            }
            else -> runedraw(npc)
        }
    }

    private suspend fun ProtectedAccess.runedraw(npc: Npc) {
        startDialogue(npc) {
            chatNpc(
                happy,
                "Hey you, why don't you have a game of Runedraw with me? If you've got 25 gold " +
                    "pieces I've got a bag of runes we can use.",
            )
            while (true) {
                if (access.invTotal(access.inv, GaObjs.coins) < STAKE) {
                    chatPlayer(
                        sad,
                        "I don't have enough money on me for a game of Runedraw at the moment.",
                    )
                    return@startDialogue
                }
                val pick =
                    choice3(
                        "Yes, I'll give you a game.",
                        1,
                        "How do you play Runedraw?",
                        2,
                        "No, that doesn't sound fun.",
                        0,
                    )
                when (pick) {
                    0 -> {
                        chatPlayer(neutral, "No, that doesn't sound fun.")
                        return@startDialogue
                    }
                    2 -> {
                        explain()
                        continue
                    }
                }
                chatPlayer(happy, "Yes, I'll give you a game.")
                access.invDel(access.inv, GaObjs.coins, STAKE)
                val won = play()
                if (won) {
                    val owed = access.debt() + STAKE * 2
                    access.setDebt(owed)
                    val asked = access.bit(GaVarBits.bow) == BOW_ASKED
                    if (owed >= SIGN_DEBT && asked && access.carries(GaObjs.longbow)) {
                        chatPlayer(
                            angry,
                            "I've had enough of you not paying up - you owe me $owed gold coins. " +
                                "I'm going to tell the ghosts what you're doing.",
                        )
                        chatNpc(
                            shocked,
                            "Please don't do that!!! They will suck the life from my bones!!!",
                        )
                        chatPlayer(neutral, "How about you signing my longbow then?")
                        chatNpc(shocked, "Yes, anything!!!")
                        access.invDel(access.inv, GaObjs.longbow)
                        access.invAdd(access.inv, GaObjs.longbow_signed)
                        access.setBit(GaVarBits.bow, BOW_SIGNED)
                        access.setDebt(0)
                        mesbox("Robin signs the oak longbow for you.")
                        return@startDialogue
                    }
                    chatPlayer(
                        neutral,
                        "So are you going to pay up then? You owe me $owed gold coins!",
                    )
                    chatNpc(neutral, "How about another game? I'll pay you back with the winnings.")
                } else {
                    chatPlayer(
                        sad,
                        "Well done, you beat me. But you still owe me ${access.debt()} gold pieces.",
                    )
                    chatNpc(
                        neutral,
                        "Err ... how about another game? We can settle up after we've finished playing.",
                    )
                }
            }
        }
    }

    private suspend fun Dialogue.explain() {
        chatPlayer(quiz, "How do you play Runedraw?")
        chatNpc(
            neutral,
            "Two players take turns to draw a rune from a bag, which contains ten runes in total. " +
                "Each rune has a different value: an air rune is worth one point, up to a Nature " +
                "rune which is worth nine points.",
        )
        chatNpc(
            neutral,
            "If a player draws the Death rune then the game is over, and they have lost.",
        )
        chatNpc(
            neutral,
            "A player can choose to hold if they wish and not draw any more runes, but this runs " +
                "the risk of the other player drawing more runes until they have a greater points total and win.",
        )
    }

    /** One game; true if the player wins. Robin draws until he's ahead or deems it too risky. */
    private suspend fun Dialogue.play(): Boolean {
        val seed = access.random.of(0, Int.MAX_VALUE - 1).toLong()
        val bag = (0..9).shuffled(java.util.Random(seed)).toMutableList()
        var mine = 0
        var his = 0
        var iHold = false
        var heHolds = false
        while (bag.isNotEmpty() && !(iHold && heHolds)) {
            if (!iHold) {
                val draw =
                    choice2(
                        "Draw a rune (you have $mine points).",
                        true,
                        "Hold with $mine points.",
                        false,
                    )
                if (draw) {
                    val rune = bag.removeAt(0)
                    if (rune == DEATH) {
                        mesbox("You draw the Death rune! You lose.")
                        return false
                    }
                    mine += rune
                    mesbox("You draw a ${RUNES[rune]} rune, worth $rune. You have $mine points.")
                } else {
                    iHold = true
                }
            }
            if (bag.isEmpty()) break
            if (!heHolds) {
                val risky = his > mine && iHold || his >= ROBIN_HOLD
                if (risky) {
                    heHolds = true
                    mesbox("Robin holds with $his points.")
                } else {
                    val rune = bag.removeAt(0)
                    if (rune == DEATH) {
                        mesbox("Robin draws the Death rune! You win.")
                        return true
                    }
                    his += rune
                    mesbox("Robin draws a ${RUNES[rune]} rune. He has $his points.")
                }
            }
            if (iHold && !heHolds && his > mine) {
                mesbox("Robin has $his points to your $mine. You lose.")
                return false
            }
        }
        val win = mine > his
        mesbox(
            if (win) "You have $mine points to Robin's $his. You win!" else "Robin has $his points to your $mine. You lose.",
        )
        return win
    }

    private companion object {
        const val STAKE = 25
        const val SIGN_DEBT = 100
        const val DEBT_SHIFT = 15
        const val DEBT_MASK = 0x7F
        const val ROBIN_HOLD = 25
        const val DEATH = 0
        const val BOW_ASKED = 1
        const val BOW_SIGNED = 2
        const val BOW_TRADED = 3

        /** Index = value; 0 is the death rune. */
        val RUNES =
            listOf(
                "Death",
                "Air",
                "Water",
                "Earth",
                "Fire",
                "Mind",
                "Body",
                "Chaos",
                "Cosmic",
                "Nature",
            )
    }
}
