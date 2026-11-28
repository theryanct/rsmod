package org.rsmod.content.quests.fishingcontest

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fishingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fishing Contest (OSRS Wiki). `fishingcompo` varp (cache db end state 5): 1 the dwarves' fishing
 * pass, 3 won the Hemenster trophy, 5 complete (back to Austri / Vestri: 2,437 Fishing xp, 1 QP,
 * the White Wolf Mountain tunnel). The contest uses the cache varbits in varp `garlicpipe`:
 * `fishingcompo_paid` (5 coins to Bonzo), `fishingcompo_garlicpipe` (garlic stuffed in the wall
 * pipe), `fishingcompo_stranger` (the Sinister Stranger moves off the pipe spot; his multinpc
 * follows it). With garlic in the pipe the player gets the spot beside the pipes, where a fishing
 * rod and red vine worms catch the giant carp; the default contest spot only catches sardines.
 * Needs Fishing 10.
 *
 * Simplified: no contest timer or rival catches; the gate is Morris's pass check.
 */
class FishingContest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(FishingContest::class.java, "fishing_contest")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Vestri/Austri" to FcNpcs.austri, "Sinister Stranger" to FcNpcs.strangers.first())
    }

    override fun ScriptContext.startup() {
        for (dwarf in FcNpcs.dwarves) {
            onNpcOpLabel(npcTypes, dwarf, "Talk-to") { talkDwarf(it) }
        }
        onNpcOpLabel(npcTypes, FcNpcs.morris, "Talk-to") {
            runQuestSection(it, dialogue, "Talking to Morris", passHooks())
        }
        for (gate in FcLocs.gates) {
            onLocOpLabel(locTypes, gate, "Open") { contestGate(it) }
        }
        onNpcOpLabel(npcTypes, FcNpcs.bonzo, "Talk-to") { talkBonzo(it) }
        onNpcOpLabel(npcTypes, FcNpcs.grandpa_jack, "Talk-to") {
            runQuestSection(it, dialogue, "Speaking to Grandpa Jack")
        }
        onNpcOpLabel(npcTypes, FcNpcs.big_dave, "Talk-to") {
            runQuestSection(it, dialogue, "Big Dave")
        }
        onNpcOpLabel(npcTypes, FcNpcs.joshua, "Talk-to") {
            runQuestSection(it, dialogue, "Joshua")
        }
        for (stranger in FcNpcs.strangers) {
            onNpcOpLabel(npcTypes, stranger, "Talk-to") {
                runQuestSection(it, dialogue, "Sinister Stranger", speakers = speakers)
            }
        }
        onLocOpLabel(locTypes, FcLocs.pipe, "Search") { searchPipe() }
        onOpLocU(FcLocs.pipe, FcObjs.garlic) { stuffGarlic() }
        for ((spot, section) in FcLocs.rival_spots) {
            onLocOpLabel(locTypes, spot, "Fish") { runQuestSection(null, dialogue, section) }
        }
        for (spot in FcNpcs.contest_spots) {
            val op = npcTypes[spot].op.indexOfFirst { it.equals("Bait", ignoreCase = true) } + 1
            val event =
                when (op) {
                    1 -> NpcEvents.Op1::class.java
                    2 -> NpcEvents.Op2::class.java
                    else -> NpcEvents.Op3::class.java
                }
            if (op > 0 && !eventBus.contains(event, spot.id)) {
                onNpcOpLabel(npcTypes, spot, "Bait") { fishContest(spot) }
            }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(FISHING_CONTEST)

    private fun ProtectedAccess.bit(varbit: VarBitType): Boolean = player.vars[varbit] == 1

    private fun ProtectedAccess.setBit(varbit: VarBitType, on: Boolean = true) {
        VarPlayerIntMapSetter.set(player, varbit, if (on) 1 else 0)
    }

    private fun passHooks(): SimpleQuestHooks = SimpleQuestHooks()

    /* Austri and Vestri */

    private suspend fun ProtectedAccess.talkDwarf(npc: Npc) {
        var gavePass = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is on a Quest Speedrunning world:" to { false },
                        NO_REQUIREMENTS to { player.fishingLvl < REQUIRED_FISHING },
                    ),
                options =
                    mapOf("Yes." to { if (state == 0) player.setQuestState(FISHING_CONTEST, 1) }),
                boxes =
                    mapOf(
                        "You got the Fishing Contest Pass!" to {
                            gavePass = true
                            false
                        },
                    ),
            )
        when {
            state == 0 -> runQuestSection(npc, dialogue, START_SECTION, hooks, speakers)
            state < TROPHY && !carries(FcObjs.pass) -> {
                runQuestSection(npc, dialogue, PASS_AGAIN, hooks, speakers)
                gavePass = true
            }
            state < TROPHY -> runQuestSection(npc, dialogue, BEFORE_WINNING, hooks, speakers)
            state == TROPHY && carries(FcObjs.trophy) -> {
                runQuestSection(npc, dialogue, "Returning to Vestri or Austri", hooks, speakers)
                invDel(inv, FcObjs.trophy)
                statAdvance(stats.fishing, FISHING_XP * xpMods.get(player, stats.fishing))
                completeQuest(FISHING_CONTEST)
            }
            else -> startDialogue(npc) { chatNpc(neutral, "Go on through, friend.") }
        }
        if (gavePass && !carries(FcObjs.pass)) invAddOrDrop(objRepo, FcObjs.pass)
    }

    /* The contest */

    private suspend fun ProtectedAccess.contestGate(gate: BoundLocInfo) {
        if (state in 1 until TROPHY && !carries(FcObjs.pass) && player.coords.x > gate.coords.x) {
            startDialogue { mesbox("You need a fishing pass to enter the competition.") }
            return
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.talkBonzo(npc: Npc) {
        when {
            state >= TROPHY -> runQuestSection(npc, dialogue, "Talking to Bonzo after winning")
            carries(FcObjs.giant_carp) -> handInCarp(npc)
            bit(FcVarBits.paid) -> runQuestSection(npc, dialogue, BONZO_PAID)
            else -> {
                var enter = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If garlic was placed in the pipes:" to { bit(FcVarBits.garlic) },
                            ),
                        options = mapOf("I'll enter the competition please." to { enter = true }),
                    )
                runQuestSection(npc, dialogue, "Speaking to Bonzo", hooks)
                if (!enter || state == 0) return
                if (!carries(FcObjs.coins, ENTRY_FEE)) {
                    startDialogue(npc) { chatNpc(neutral, "You need 5 coins to enter, mate.") }
                    return
                }
                invDel(inv, FcObjs.coins, ENTRY_FEE)
                setBit(FcVarBits.paid)
                runQuestSection(npc, dialogue, "If choosing Bonzo's pay option")
                if (bit(FcVarBits.garlic)) {
                    setBit(FcVarBits.stranger)
                    mes("Your fishing competition spot is now beside the pipes.")
                }
            }
        }
    }

    private suspend fun ProtectedAccess.handInCarp(npc: Npc) {
        startDialogue(npc) {
            chatNpc(neutral, "So how are you doing so far?")
            chatPlayer(happy, "I have this big fish. Is it enough to win?")
            mesbox("You hand over your catch.")
            chatNpc(happy, "Well done! That's the biggest fish anyone's caught. You win!")
            mesbox("You are given the Hemenster fishing trophy!")
        }
        invDel(inv, FcObjs.giant_carp)
        invAddOrDrop(objRepo, FcObjs.trophy)
        setBit(FcVarBits.paid, on = false)
        setBit(FcVarBits.passed)
        player.setQuestState(FISHING_CONTEST, TROPHY)
    }

    private suspend fun ProtectedAccess.searchPipe() {
        val section =
            if (bit(FcVarBits.garlic)) {
                "Searching the pipe after stuffing garlic in it"
            } else {
                "Searching the wall pipe"
            }
        runQuestSection(null, dialogue, section)
    }

    private suspend fun ProtectedAccess.stuffGarlic() {
        if (bit(FcVarBits.garlic)) {
            runQuestSection(null, dialogue, "Searching the pipe after stuffing garlic in it")
            return
        }
        invDel(inv, FcObjs.garlic)
        setBit(FcVarBits.garlic)
        runQuestSection(null, dialogue, "Putting the garlic into the wall pipe")
    }

    /** The contest spots: the pipe spot (garlic + paid) gives the giant carp, the rest sardines. */
    private suspend fun ProtectedAccess.fishContest(spot: NpcType) {
        if (state == 0 || state >= TROPHY || !bit(FcVarBits.paid)) {
            runQuestSection(null, dialogue, "Fishing before entering the competition")
            return
        }
        val pipeSpot = spot.id == FcNpcs.pipe_spot.id
        if (pipeSpot && !bit(FcVarBits.stranger)) {
            runQuestSection(null, dialogue, "Sinister Stranger (2)", speakers = speakers)
            return
        }
        if (!carries(FcObjs.rod) || !carries(FcObjs.worm)) {
            mes("You need a fishing rod and red vine worms to fish here.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        anim(FcSeqs.fish)
        delay(FISH_TICKS)
        invDel(inv, FcObjs.worm)
        val carp = pipeSpot && random.of(1, CARP_CHANCE) == 1
        invAdd(inv, if (carp) FcObjs.giant_carp else FcObjs.sardine)
        mes(if (carp) "You catch a giant carp!" else "You catch a sardine.")
    }

    companion object {
        const val TROPHY = 3
        const val COMPLETE = 5

        private const val REQUIRED_FISHING = 10
        private const val FISHING_XP = 2437.0
        private const val ENTRY_FEE = 5
        private const val FISH_TICKS = 5
        private const val CARP_CHANCE = 3

        private const val NO_REQUIREMENTS =
            "If the player does not meet the requirements to begin Fishing Contest:"
        private const val BONZO_PAID = "Trying to pay Bonzo during the competition"
        private const val START_SECTION = "Speaking to Vestri or Austri to start the quest"
        private const val PASS_AGAIN = "Retrieving another fishing pass from Vestri or Austri"
        private const val BEFORE_WINNING = "Speaking to Vestri or Austri before winning"

        val FISHING_CONTEST =
            QuestDef(
                name = "Fishing Contest",
                varp = { FcVarps.fishingcompo },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("2,437 Fishing XP", "White Wolf Mountain tunnel"),
                scrollObj = { FcObjs.trophy },
            )
    }
}

// Every cache ref Fishing Contest uses (re-check after the rev 243 cutover).

internal object FcNpcs : NpcReferences() {
    val austri = find("tunnel_dwarf")
    val dwarves = listOf(austri, find("tunnel_dwarf1"))
    val morris = find("morris")
    val bonzo = find("bonzo")
    val grandpa_jack = find("grandpa_jack")
    val big_dave = find("bigdave")
    val joshua = find("joshua")

    /** Children of the `sinister_stranger` multinpc (fishingcompo_stranger). */
    val strangers = listOf(find("sinister_stranger0"), find("sinister_stranger1"))
    val pipe_spot = find("0_41_53_sinisterfishspot")
    val contest_spots = listOf(find("0_41_53_compofishspot"), pipe_spot)
}

internal object FcLocs : LocReferences() {
    val pipe = find("garlicpipe")
    val gates = listOf(find("fishinggateclosedl"), find("fishinggateclosedr"))
    val rival_spots =
        listOf(
            find("sinisterfishspot") to "Sinister Stranger (2)",
            find("bigdavefishspot") to "Big Dave (2)",
            find("joshuafishspot") to "Joshua (2)",
        )
}

internal object FcObjs : ObjReferences() {
    val pass = find("fishing_competition_pass")
    val trophy = find("hemenster_fishing_trophy")
    val giant_carp = find("raw_giant_carp")
    val sardine = find("raw_sardine")
    val rod = find("fishing_rod")
    val worm = find("red_vine_worm")
    val garlic = find("garlic")
    val coins = find("coins")
}

internal object FcSeqs : SeqReferences() {
    val fish = find("human_fishing_casting")
}

internal object FcVarps : VarpReferences() {
    val fishingcompo = find("fishingcompo")
}

internal object FcVarBits : VarBitReferences() {
    val garlic = find("fishingcompo_garlicpipe")
    val paid = find("fishingcompo_paid")
    val passed = find("fishingcompo_passed")
    val stranger = find("fishingcompo_stranger")
}
