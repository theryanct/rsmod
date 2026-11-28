package org.rsmod.content.quests.belowicemountain

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseStrengthLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questPoints
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Below Ice Mountain (OSRS Wiki). Progress is in the cache varp `bim_main`: varbit `bim` (cache db
 * end state 120): 0, 10 Willow wants her crew, 30 the crew opened the ruins (the entrance multiloc
 * shows the door from 30) and the Ancient Guardian woke, 40 guardian killed, 120 complete (Willow's
 * bag: 2,000 coins, 1,200 Mining xp, 1 QP). Each recruit has a varbit in the same varp; 40 moves
 * them to the ruins entrance (cache multinpcs `bim_*_temple_outside`):
 * - Checkal (Barbarian Village): 10 sent to Atlas, 20 trained (Flex emote unlocked), 40 flexed.
 * - Burntof (Rising Sun Inn): 10 wants a drink, 40 after the drink and rock-paper-scissors.
 * - Marley (Edgeville ruins): 10 wants a steak sandwich, 20 the Blue Moon cook told you the recipe
 *   (knife on bread with cooked meat), 40 fed.
 * Needs 16 QP when the QuestGates toggle is on.
 *
 * Cutscenes are played as their dialogue only: Atlas's training goes straight to the Flex unlock,
 * Checkal sees you flex without the emote tab, and the break-in ends with the player teleported
 * into the ruins facing the guardian. Not done: mining the structural pillars to hurt the guardian,
 * Charlie the Tramp's hint, Ramarno and the rest of Camdozaal, extra paid Atlas sessions.
 */
class BelowIceMountain
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(BelowIceMountain::class.java, "below_ice_mountain")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Willow" to BimNpcs.willow,
            "Checkal" to BimNpcs.checkal,
            "Marley" to BimNpcs.marley,
            "Burntof" to BimNpcs.burntof,
            "Tina" to BimNpcs.tina,
            "Ramarno" to BimNpcs.ramarno,
        )
    }

    /** The guardian each player woke, so re-entering doesn't spawn a second one. */
    private val guardians = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, BimNpcs.willow, "Talk-to") { talkWillow(it) }
        onNpcOpLabel(npcTypes, BimNpcs.checkal, "Talk-to") { talkCheckal(it) }
        onNpcOpLabel(npcTypes, BimNpcs.atlas, "Talk-to") { talkAtlas(it) }
        for (burntof in BimNpcs.burntofs) {
            onNpcOpLabel(npcTypes, burntof, "Talk-to") { talkBurntof(it) }
            for (drink in BimObjs.drinks) {
                onOpNpcU(burntof, drink) { talkBurntof(it.npc) }
            }
        }
        onNpcOpLabel(npcTypes, BimNpcs.marley, "Talk-to") { talkMarley(it) }
        onOpNpcU(BimNpcs.marley, BimObjs.sandwich) { talkMarley(it.npc) }
        onNpcOpLabel(npcTypes, BimNpcs.cook, "Talk-to") { talkCook(it) }
        onOpHeldU(BimObjs.knife, BimObjs.bread) { makeSandwich() }
        onLocOpLabel(locTypes, BimLocs.door, "Enter") { enterRuins() }
        onLocOpLabel(locTypes, BimLocs.exit, "Exit") { telejump(OUTSIDE) }
        onLocOpLabel(locTypes, BimLocs.bag, "Investigate") { investigateBag() }
        onEvent<NpcKilledEvent> { guardianKilled(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BELOW_ICE_MOUNTAIN)

    private fun ProtectedAccess.recruit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setRecruit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.recruited(): Int =
        BimVarBits.recruits.count { recruit(it) >= RECRUITED }

    /** "If this was (not) the last member..." after the current recruit is set to 40. */
    private fun ProtectedAccess.lastMemberHooks(): Map<String, () -> Boolean> {
        val last = { recruited() == BimVarBits.recruits.size }
        return mapOf(
            "If this was not the last member the player needed to recruit:" to { !last() },
            "If this was the last member the player needed to recruit:" to last,
            "If this was not the last member the player needed to convince:" to { !last() },
            "If this was the last member the player needed to convince:" to last,
        )
    }

    /* Willow */

    private suspend fun ProtectedAccess.talkWillow(npc: Npc) {
        var breakIn = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes." to {
                            when {
                                state == 0 -> player.setQuestState(BELOW_ICE_MOUNTAIN, STARTED)
                                state == STARTED && recruited() == BimVarBits.recruits.size ->
                                    breakIn = true
                            }
                        },
                    ),
            )
        when {
            state == 0 -> {
                val gated = player.isFeatureOn(Feature.QuestGates)
                if (gated && player.questPoints < REQUIRED_QP) {
                    startDialogue(npc) {
                        chatNpc(
                            neutral,
                            "Come back when you've made more of a name for yourself. You'll " +
                                "need at least $REQUIRED_QP quest points.",
                        )
                    }
                    return
                }
                runQuestSection(npc, dialogue, "Talking to Willow", hooks)
            }
            state == STARTED && recruited() == BimVarBits.recruits.size -> {
                runQuestSection(npc, dialogue, "Talking to Willow (2)", hooks, speakers)
                if (breakIn) {
                    player.setQuestState(BELOW_ICE_MOUNTAIN, RUINS_OPEN)
                    enterRuins()
                }
            }
            state == STARTED -> runQuestSection(npc, dialogue, "Talking to Willow again", hooks)
            state < COMPLETE ->
                startDialogue(npc) {
                    chatNpc(angry, "What are you still doing out here? Deal with that thing!")
                }
            else -> runQuestSection(npc, dialogue, "Willow", speakers = speakers)
        }
    }

    /* Checkal and Atlas */

    private suspend fun ProtectedAccess.talkCheckal(npc: Npc) {
        val checkal = BimVarBits.checkal
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has 99 Strength:" to {
                            player.baseStrengthLvl >= MAX_LEVEL
                        },
                        "If the player doesn't have 99 Strength:" to {
                            player.baseStrengthLvl < MAX_LEVEL
                        },
                    ) + lastMemberHooks(),
                actions =
                    mapOf(
                        "Show your strength by flexing your muscles." to {
                            anim(BimSeqs.flex)
                            setRecruit(checkal, RECRUITED)
                        },
                    ),
            )
        when {
            player.isQuestComplete(BELOW_ICE_MOUNTAIN) ->
                runQuestSection(npc, dialogue, "Checkal", speakers = speakers)
            state == 0 ->
                startDialogue(npc) { chatNpc(neutral, "Can't talk now, I'm working out.") }
            recruit(checkal) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Checkal", hooks)
                setRecruit(checkal, SENT)
            }
            recruit(checkal) == SENT -> runQuestSection(npc, dialogue, "Talking to Checkal again")
            recruit(checkal) == TRAINED ->
                runQuestSection(npc, dialogue, CHECKAL_TRAINED, hooks)
            else -> runQuestSection(npc, dialogue, "Talking to Checkal at Ice Mountain")
        }
    }

    private suspend fun ProtectedAccess.talkAtlas(npc: Npc) {
        val checkal = BimVarBits.checkal
        if (state == 0 || recruit(checkal) != SENT) {
            startDialogue(npc) { chatNpc(neutral, "What do you want, little one?") }
            return
        }
        val hooks =
            SimpleQuestHooks(
                actions =
                    mapOf(
                        "You have unlocked the flex emote!" to {
                            VarPlayerIntMapSetter.set(player, BimVarBits.emote_flex, 1)
                            setRecruit(checkal, TRAINED)
                            mes("You have unlocked the flex emote!")
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Atlas", hooks)
    }

    /* Burntof */

    private fun ProtectedAccess.drink(): ObjType? = BimObjs.drinks.firstOrNull { carries(it) }

    private suspend fun ProtectedAccess.talkBurntof(npc: Npc) {
        val burntof = BimVarBits.burntof
        var gave = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't got an alcoholic drink:" to { drink() == null },
                        "If the player doesn't have an alcoholic drink:" to { drink() == null },
                        "If the player has an alcoholic drink:" to { drink() != null },
                    ) + lastMemberHooks(),
                actions =
                    mapOf(
                        "You give Burntof the drink." to {
                            drink()?.let { invDel(inv, it) }
                            gave = true
                        },
                        "Tina comes over." to { setRecruit(burntof, RECRUITED) },
                    ),
            )
        when {
            player.isQuestComplete(BELOW_ICE_MOUNTAIN) ->
                runQuestSection(npc, dialogue, "Burntof", speakers = speakers)
            state == 0 -> startDialogue(npc) { chatNpc(neutral, "Buy ush a drink.") }
            recruit(burntof) >= RECRUITED ->
                runQuestSection(npc, dialogue, "Talking to Burntof at Ice Mountain")
            recruit(burntof) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Burntof", hooks)
                if (!gave) setRecruit(burntof, SENT)
            }
            else -> runQuestSection(npc, dialogue, BURNTOF_AGAIN, hooks)
        }
        if (gave) {
            runQuestSection(npc, dialogue, "Rock, paper, scissors", hooks, speakers)
            setRecruit(burntof, RECRUITED)
        }
    }

    /* Marley and the Blue Moon Inn cook */

    private suspend fun ProtectedAccess.talkMarley(npc: Npc) {
        val marley = BimVarBits.marley
        val hooks =
            SimpleQuestHooks(
                conditions = lastMemberHooks(),
                actions =
                    mapOf(
                        "You give the steak sandwich to Marley." to {
                            invDel(inv, BimObjs.sandwich)
                            setRecruit(marley, RECRUITED)
                        },
                    ),
            )
        when {
            player.isQuestComplete(BELOW_ICE_MOUNTAIN) ->
                runQuestSection(npc, dialogue, "Marley", speakers = speakers)
            state == 0 ->
                startDialogue(npc) { chatNpc(neutral, "Spare a coin for a hungry man?") }
            recruit(marley) >= RECRUITED ->
                runQuestSection(npc, dialogue, "Talking to Marley at Ice Mountain")
            carries(BimObjs.sandwich) ->
                runQuestSection(npc, dialogue, "Returning to Marley", hooks)
            recruit(marley) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Marley", hooks)
                setRecruit(marley, SENT)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Marley again", hooks)
        }
    }

    private suspend fun ProtectedAccess.talkCook(npc: Npc) {
        val marley = BimVarBits.marley
        val section =
            when {
                state == 0 || recruit(marley) == 0 || recruit(marley) >= RECRUITED -> null
                carries(BimObjs.sandwich) -> COOK_AFTER
                recruit(marley) == SENT -> "Talking to the Cook"
                else -> "Talking to the cook again"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "What do you want? I'm busy.") }
            return
        }
        runQuestSection(npc, dialogue, section)
        if (recruit(marley) == SENT) {
            setRecruit(marley, RECIPE)
        }
    }

    /** Knife on bread with cooked meat, once the cook has told the recipe (or after the quest). */
    private fun ProtectedAccess.makeSandwich() {
        val known =
            recruit(BimVarBits.marley) >= RECIPE || player.isQuestComplete(BELOW_ICE_MOUNTAIN)
        if (!known) {
            mes("Nothing interesting happens.")
            return
        }
        if (!carries(BimObjs.cooked_meat)) {
            mes("You need some cooked meat to make a steak sandwich.")
            return
        }
        invDel(inv, BimObjs.bread)
        invDel(inv, BimObjs.cooked_meat)
        invAdd(inv, BimObjs.sandwich)
        mes("You cut the bread and make a steak sandwich.")
    }

    /* The ruins */

    private suspend fun ProtectedAccess.enterRuins() {
        if (state < RUINS_OPEN) {
            mes("The entrance is blocked by rocks.")
            return
        }
        telejump(INSIDE)
        delay(1)
        if (state == RUINS_OPEN) {
            val current = guardians[player.uid.packed]
            if (current == null || !current.isSlotAssigned) {
                wakeGuardian(player)
            }
        }
    }

    private fun wakeGuardian(player: Player) {
        val guardian = Npc(npcTypes[BimNpcs.guardian], GUARDIAN_SPAWN)
        npcRepo.add(guardian, GUARDIAN_TICKS)
        guardians[player.uid.packed] = guardian
        guardian.opPlayer2(player, aiInteractions)
    }

    private fun guardianKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != BimNpcs.guardian.id) return
        val player = event.hero
        if (player.questState(BELOW_ICE_MOUNTAIN) != RUINS_OPEN) return
        guardians.remove(player.uid.packed)
        player.setQuestState(BELOW_ICE_MOUNTAIN, GUARDIAN_DEAD)
        player.mes("The Ancient Guardian crumbles. Willow left her bag behind in the panic...")
    }

    private suspend fun ProtectedAccess.investigateBag() {
        if (state != GUARDIAN_DEAD) {
            mes("The bag is empty.")
            return
        }
        runQuestSection(null, dialogue, "Finishing up", speakers = speakers)
        invAddOrDrop(objRepo, BimObjs.coins, REWARD_COINS)
        statAdvance(stats.mining, MINING_XP * xpMods.get(player, stats.mining))
        VarPlayerIntMapSetter.set(player, BimVarBits.claimed_reward, 1)
        completeQuest(BELOW_ICE_MOUNTAIN)
    }

    companion object {
        const val STARTED = 10
        const val RUINS_OPEN = 30
        const val GUARDIAN_DEAD = 40
        const val COMPLETE = 120

        /* Recruit varbit values. */
        private const val SENT = 10
        private const val TRAINED = 20
        private const val RECIPE = 20
        private const val RECRUITED = 40

        private const val REQUIRED_QP = 16
        private const val MAX_LEVEL = 99
        private const val REWARD_COINS = 2000
        private const val MINING_XP = 1200.0
        private const val GUARDIAN_TICKS = 500

        private const val CHECKAL_TRAINED = "Talking to Checkal after completing Atlas' training"
        private const val BURNTOF_AGAIN = "Talking to Burntof again before he gets a beer"
        private const val COOK_AFTER =
            "Talking to the cook after managing to make yourself a Steak sandwich"

        /** Inside the ruins by the exit (bim_exit at 2951,5761), and outside the entrance. */
        private val INSIDE = CoordGrid(2952, 5763, 0)
        private val OUTSIDE = CoordGrid(2998, 3493, 0)
        private val GUARDIAN_SPAWN = CoordGrid(2952, 5774, 0)

        val BELOW_ICE_MOUNTAIN =
            QuestDef(
                name = "Below Ice Mountain",
                varbit = { BimVarBits.bim },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("2,000 coins", "1,200 Mining XP", "Flex emote"),
                scrollObj = { BimObjs.sandwich },
            )
    }
}

// Every cache ref Below Ice Mountain uses (re-check after the rev 243 cutover).

internal object BimNpcs : NpcReferences() {
    val willow = find("bim_willow")
    val checkal = find("bim_checkal")
    val atlas = find("bim_atlas")
    val burntof = find("bim_burntof")
    val burntofs = listOf(burntof, find("bim_burntof_nodrink"))
    val marley = find("bim_marley")
    val cook = find("fai_varrock_bluemoon_chef")
    val tina = find("bim_cutscene_tina")
    val ramarno = find("camzodaal_ramarno")
    val guardian = find("bim_golem_boss")
}

internal object BimLocs : LocReferences() {
    /** The entrance multiloc (`bim_entrance`) shows this door from bim 30. */
    val door = find("bim_door")
    val exit = find("bim_exit")
    val bag = find("bim_willows_bag")
}

internal object BimObjs : ObjReferences() {
    val sandwich = find("bim_steak_sandwich")
    val knife = find("knife")
    val bread = find("bread")
    val cooked_meat = find("cooked_meat")
    val coins = find("coins")
    val drinks =
        listOf(
            find("beer"),
            find("asgarnian_ale"),
            find("dwarven_stout"),
            find("wizards_mind_bomb"),
        )
}

internal object BimSeqs : SeqReferences() {
    val flex = find("emote_flex")
}

internal object BimVarBits : VarBitReferences() {
    val bim = find("bim")
    val marley = find("bim_marley")
    val checkal = find("bim_checkal")
    val burntof = find("bim_burntof")
    val claimed_reward = find("bim_claimed_reward")
    val emote_flex = find("emote_flex")

    val recruits = listOf(marley, checkal, burntof)
}
