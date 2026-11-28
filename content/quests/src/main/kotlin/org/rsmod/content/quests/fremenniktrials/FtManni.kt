package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerTimer
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.fremenniktrials.FremennikTrials.Companion.FREMENNIK_TRIALS
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Manni the Reveller's drinking contest (Wiki): the keg of beer on the longhall table loses to
 * Manni every time; a beer (or beer tankard) for the council workman by the bridge buys his
 * strange object, a tinderbox lights it, and once it is hissing in the drain pipe on the
 * longhall's east wall the Poison Salesman's low alcohol keg (250 coins, Seers' Village) can be
 * swapped into your keg under cover of the bang.
 *
 * Simplified: the drink leaves you no drunker than the dialogue says (no stat drain), and the bang
 * comes when you swap the kegs rather than on a fixed fuse.
 */
internal class FtManni(private val ft: FtContext) {
    private val speakers by lazy { mapOf("Manni the Reveller" to FtNpcs.manni) }

    fun ScriptContext.bind() {
        with(QuestTalk) {
            register(ft.npcTypes, FtNpcs.workman, FREMENNIK_TRIALS, wants = { player.ftStarted }) {
                runQuestSection(it, ft.dialogue, "Talking to the Council workman")
            }
            register(ft.npcTypes, FtNpcs.poison_salesman, FREMENNIK_TRIALS, wants = { player.onTrial(Trial.Manni) }) {
                talkSalesman(it)
            }
        }
        onOpNpcU(FtNpcs.workman, FtObjs.beer) { beerForWorkman(it.npc, FtObjs.beer) }
        onOpNpcU(FtNpcs.workman, FtObjs.beer_tankard) { beerForWorkman(it.npc, FtObjs.beer_tankard) }
        onOpHeldU(FtObjs.tinderbox, FtObjs.strange_object) { lightObject() }
        onPlayerTimer(FtTimers.firecracker) { explode() }
        ft.bound("pipe Put-inside") { onLocOpLabel(ft.locTypes, FtLocs.pipe, "Put-inside") { putInPipe() } }
        onOpLocU(FtLocs.pipe, FtObjs.lit_strange_object) { putInPipe() }
        onOpLocU(FtLocs.pipe, FtObjs.strange_object) { mes("Nothing interesting happens.") }
        onOpHeldU(FtObjs.low_alcohol_keg, FtObjs.keg_of_beer) { switchKegs() }
    }

    suspend fun ProtectedAccess.talk(npc: Npc) {
        val hasKeg = carries(FtObjs.keg_of_beer)
        when {
            !player.accepted(Trial.Manni) -> {
                var accepted = false
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player does not already have the tankard and/or beer tankard:" to { !hasKeg }),
                        options = mapOf("Yes" to { accepted = true }),
                    )
                runQuestSection(npc, ft.dialogue, "Manni the Reveller", hooks)
                if (accepted) player.accept(Trial.Manni)
            }
            player.voted(Trial.Manni) -> runQuestSection(npc, ft.dialogue, "Subsequent dialogue with Manni")
            hasKeg && player.ftHas(FtVarps.manni, KEG_SWITCHED) -> winContest(npc)
            hasKeg -> loseContest(npc)
            else -> {
                val hooks = SimpleQuestHooks(conditions = mapOf("If the player does not have a keg of beer:" to { true }))
                runQuestSection(npc, ft.dialogue, "Talking to Manni after the player's loss", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.loseContest(npc: Npc) {
        var drank = false
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player has the beer tankards:" to { true }),
                options = mapOf("Yes" to { drank = true }),
                actions =
                    mapOf(
                        "Manni the Reveller takes a big drink of his tankard first." to {
                            mes("The Fremennik drinks his tankard first. He staggers a little bit.")
                        },
                        "The player then takes a big drink from his tankard." to {
                            mes("You drink from your keg. You feel extremely drunk...")
                        },
                    ),
            )
        runQuestSection(npc, ft.dialogue, "Talking to Manni the Reveller again", hooks)
        if (drank && carries(FtObjs.keg_of_beer)) {
            invDel(inv, FtObjs.keg_of_beer)
            player.ftSet(FtVarps.manni, LOST)
        }
    }

    private suspend fun ProtectedAccess.winContest(npc: Npc) {
        var drank = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes" to { drank = true }))
        runQuestSection(npc, ft.dialogue, "Talking to Manni after switching with the low alcohol beer", hooks)
        if (!drank || !carries(FtObjs.keg_of_beer)) return
        invDel(inv, FtObjs.keg_of_beer)
        mes("The Fremennik drinks his tankard first. He staggers a little bit.")
        mes("You drink from your keg. You don't feel at all drunk.")
        player.ftClear(FtVarps.manni, KEG_SWITCHED or PIPE_LIT)
        player.giveVote(Trial.Manni)
        mes("Congratulations! You have completed the Revellers' trial.")
    }

    /* The strange object */

    private suspend fun ProtectedAccess.beerForWorkman(npc: Npc, beer: ObjType) {
        if (!player.onTrial(Trial.Manni) || carries(FtObjs.strange_object) || carries(FtObjs.lit_strange_object)) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, beer)
        runQuestSection(npc, ft.dialogue, "Using a normal beer or beer tankard on the Council workman")
        invAddOrDrop(ft.objRepo, FtObjs.strange_object)
    }

    private fun ProtectedAccess.lightObject() {
        invReplace(inv, FtObjs.strange_object, 1, FtObjs.lit_strange_object)
        mes("You light the string of the strange object. It starts to hiss slightly.")
        player.timer(FtTimers.firecracker, ft.random.of(FUSE_MIN, FUSE_MAX))
    }

    private fun ProtectedAccess.explode() {
        player.clearTimer(FtTimers.firecracker)
        if (!carries(FtObjs.lit_strange_object)) return
        invDel(inv, FtObjs.lit_strange_object)
        mes("The strange object you lit earlier explodes in your inventory!")
        mes("Ow!")
        // Wiki: the player loses 4% of their hitpoints, minimum 2.
        statSub(stats.hitpoints, constant = 2, percent = 4)
    }

    private suspend fun ProtectedAccess.putInPipe() {
        if (!carries(FtObjs.lit_strange_object)) {
            startDialogue { chatPlayer(neutral, "I don't have anything I really want to put inside a smelly old drain pipe.") }
            return
        }
        invDel(inv, FtObjs.lit_strange_object)
        player.clearTimer(FtTimers.firecracker)
        player.ftSet(FtVarps.manni, PIPE_LIT)
        mes("You put the lit strange object into the pipe.")
        startDialogue {
            chatPlayer(
                neutral,
                "That is going to make a really loud bang when it goes off! It would be a perfect distraction to help me cheat in the drinking contest!",
            )
        }
    }

    private suspend fun ProtectedAccess.switchKegs() {
        if (!player.ftHas(FtVarps.manni, PIPE_LIT)) {
            runQuestSection(null, ft.dialogue, "Attempting to use the low alcohol keg with the regular keg of beer", speakers = speakers)
            return
        }
        mes("You hear a loud bang from outside. It echoes through the drain.")
        mes("Everyone in the longhall: what was THAT?")
        invDel(inv, FtObjs.low_alcohol_keg)
        player.ftClear(FtVarps.manni, PIPE_LIT)
        player.ftSet(FtVarps.manni, KEG_SWITCHED)
        mes("You empty the keg and refill it with low alcohol beer.")
    }

    /* The Poison Salesman (Seers' Village pub) */

    private suspend fun ProtectedAccess.talkSalesman(npc: Npc) {
        val coins = carries(FtObjs.coins, KEG_PRICE)
        val room = inv.freeSpace() > 0
        var buy = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have 250 gp:" to { !coins },
                        "If the player has 250 gp:" to { coins },
                        "If the player does not have inventory space:" to { !room },
                        "If the player has inventory space:" to { room },
                    ),
                options = mapOf("Yes" to { buy = coins && room }),
            )
        with(ft) { branch(npc, "Talking to the Poison Salesman", "Talk about the Fremennik Trials", hooks) }
        if (buy) {
            invDel(inv, FtObjs.coins, KEG_PRICE)
            invAdd(inv, FtObjs.low_alcohol_keg)
        }
    }

    private companion object {
        const val LOST = 1 shl 0
        const val PIPE_LIT = 1 shl 1
        const val KEG_SWITCHED = 1 shl 2

        const val KEG_PRICE = 250
        /** 70-100 seconds. */
        const val FUSE_MIN = 117
        const val FUSE_MAX = 167
    }
}
