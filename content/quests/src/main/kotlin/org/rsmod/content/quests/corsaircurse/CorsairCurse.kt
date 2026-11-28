package org.rsmod.content.quests.corsaircurse

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Corsair Curse (OSRS Wiki). Varbit `corscurs_progress` (cache db end state 60; the cache
 * multis: Tock leaves the crossroads after 5, his Rimmington ferry Talks from 10 and Travels
 * from 15, the driftwood burns from 49, the sick Ithoi goes after 51, the crew's post-quest forms
 * and the cove bank from 60): 10 agreed to help, 15 sailed, 20 Ithoi's curse theories, 30 Tock's
 * "traitor" talk, 40 Ithoi confessed, 49 driftwood lit (Ithoi seen running), 50 Tock's order, 52
 * fighting Ithoi, 55 Ithoi dead, 60 complete (1,200 Firemaking xp, 2 QP).
 *
 * Each crew member's "curse" has its own cache varbit (1 story, 2 investigated, 3 debunked):
 * Arsen (`corscurs_thief`: Tock's relic back to Chief Tess; 4 = told you about Francois),
 * Colin (`corscurs_cabinboy`: Ithoi's telescope), Gnocci (`corscurs_cook`: dig up the doll; the
 * cache shows it at 2). `corscurs_navigator` = Gnocchi said Ithoi cooked dinner.
 *
 * Boats here until content/travel exists: Tock's Travel op between Rimmington and the cove.
 * Not done: the cutscenes, Ithoi's instance (he's spawned beside you), Big Chompy Bird Hunting
 * lines (treated as not done), "nothing interesting" lines for item-on-npc.
 */
class CorsairCurse
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(CorsairCurse::class.java, "corsair_curse") }

    override fun ScriptContext.startup() {
        for (tock in CorsairNpcs.tocks) {
            onNpcOpLabel(npcTypes, tock, "Talk-to") { talkTock(it) }
        }
        onNpcOpLabel(npcTypes, CorsairNpcs.tock_ferry, "Travel") { sail() }
        onNpcOpLabel(npcTypes, CorsairNpcs.ithoi, "Talk-to") { talkIthoi(it) }
        for (arsen in CorsairNpcs.arsens) {
            onNpcOpLabel(npcTypes, arsen, "Talk-to") { talkArsen(it) }
        }
        for (colin in CorsairNpcs.colins) {
            onNpcOpLabel(npcTypes, colin, "Talk-to") { talkColin(it) }
        }
        onNpcOpLabel(npcTypes, CorsairNpcs.gnocci, "Talk-to") { talkGnocci(it) }
        onNpcOpLabel(npcTypes, CorsairNpcs.tess, "Talk-to") { talkTess(it) }
        onLocOpLabel(locTypes, CorsairLocs.telescope, "Observe") { telescope() }
        onOpLocU(CorsairLocs.driftwood, CorsairObjs.tinderbox) { lightDriftwood() }
        QuestDigSpots.register { digDoll() }
        onEvent<NpcKilledEvent> { ithoiKilled(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(CORSAIR_CURSE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(CORSAIR_CURSE, value)
    }

    private fun ProtectedAccess.crew(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setCrew(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.allDebunked(): Boolean =
        crew(CorsairVarBits.thief) >= DEBUNKED &&
            crew(CorsairVarBits.cabinboy) >= DEBUNKED &&
            crew(CorsairVarBits.cook) >= DEBUNKED

    private fun ProtectedAccess.wantsRelic(): Boolean =
        crew(CorsairVarBits.thief) == STORY && !carries(CorsairObjs.relic)

    private fun ProtectedAccess.atCove(): Boolean = player.coords.z < COVE_MAX_Z

    /** The crew's sections after Ithoi's confession / the proof / his death. */
    private fun ProtectedAccess.lateSection(name: String, gnocchi: Boolean = false): String? =
        when {
            state >= ITHOI_DEAD -> "Talking to $name after killing Ithoi"
            state >= DRIFTWOOD_LIT -> "Talking to $name after proving Ithoi poisoned the crew"
            state >= CONFESSED -> {
                val who = if (gnocchi) "Gnocchi" else name
                "Talking to $who after Ithoi admits he poisoned the crew"
            }
            else -> null
        }

    /* Captain Tock and the ferry */

    private suspend fun ProtectedAccess.sail() {
        if (state < SAILED) return
        val dest = if (atCove()) RIMMINGTON_LANDING else COVE_LANDING
        mes("The ship sails...")
        delay(SAIL_TICKS)
        telejump(dest)
    }

    private suspend fun ProtectedAccess.talkTock(npc: Npc) {
        var sailNow = false
        var gaveRelic = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Sure, I'll try to help with your curse." to {
                            if (state == 0) setState(AGREED)
                        },
                        "Okay, I'm ready go to Corsair Cove." to { sailNow = true },
                        "Please take me back to Rimmington." to { sailNow = true },
                        "I've ruled out all the Corsairs' theories..." to {
                            if (state == THEORIES) setState(TRAITOR)
                        },
                    ),
                boxes =
                    mapOf(
                        RELIC_BOX to {
                            gaveRelic = true
                            false
                        },
                    ),
            )
        val section =
            when {
                state == 0 -> "Talking to Captain Tock"
                !atCove() -> "Talking to Captain Tock again"
                state >= ITHOI_DEAD -> TOCK_DONE
                state >= DRIFTWOOD_LIT -> TOCK_PROVEN
                state >= CONFESSED -> TOCK_CONFESSED
                state >= TRAITOR -> TOCK_TRAITOR_AGAIN
                state == THEORIES && allDebunked() -> "Talking to Captain Tock (3)"
                state == THEORIES && wantsRelic() -> "Talking to Captain Tock (2)"
                state == THEORIES -> TOCK_INVESTIGATION
                else -> "Talking to Captain Tock after Ithoi"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (gaveRelic) invAddOrDrop(objRepo, CorsairObjs.relic)
        when {
            section == TOCK_PROVEN && state == DRIFTWOOD_LIT -> setState(KILL_ORDER)
            section == TOCK_DONE && state == ITHOI_DEAD -> {
                statAdvance(stats.firemaking, FIREMAKING_XP * xpMods.get(player, stats.firemaking))
                completeQuest(CORSAIR_CURSE)
                return
            }
        }
        if (sailNow) {
            if (state == AGREED) setState(SAILED)
            sail()
        }
    }

    /* Ithoi */

    private suspend fun ProtectedAccess.talkIthoi(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Maybe because the Captain's thinking of firing you." to {
                            if (state == TRAITOR) setState(CONFESSED)
                        },
                    ),
            )
        val toldEveryone =
            crew(CorsairVarBits.navigator) == 1 && crew(CorsairVarBits.thief) >= FRANCOIS
        when {
            state < SAILED -> startDialogue(npc) { chatNpc(sad, "Leave me be... I'm cursed.") }
            state == SAILED -> {
                runQuestSection(npc, dialogue, "Talking to Ithoi", hooks)
                setState(THEORIES)
            }
            state == THEORIES -> runQuestSection(npc, dialogue, ITHOI_INVESTIGATION, hooks)
            state == TRAITOR && toldEveryone -> runQuestSection(npc, dialogue, ITHOI_ALL, hooks)
            state == TRAITOR -> runQuestSection(npc, dialogue, ITHOI_NOT_ALL, hooks)
            state == CONFESSED -> runQuestSection(npc, dialogue, "Talking to Ithoi again", hooks)
            state == DRIFTWOOD_LIT ->
                runQuestSection(npc, dialogue, "Talking to Ithoi again (2)", hooks)
            state == KILL_ORDER -> {
                setState(FIGHTING)
                val ithoi = Npc(npcTypes[CorsairNpcs.ithoi_combat], player.coords.translate(1, 0))
                npcRepo.add(ithoi, ITHOI_TICKS)
                ithoi.opPlayer2(player, aiInteractions)
            }
        }
    }

    private fun ithoiKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != CorsairNpcs.ithoi_combat.id) return
        val player = event.hero
        if (player.questState(CORSAIR_CURSE) !in KILL_ORDER until ITHOI_DEAD) return
        player.setQuestState(CORSAIR_CURSE, ITHOI_DEAD)
        player.mes("Ithoi is dead. You should tell Captain Tock.")
    }

    /* Arsen */

    private suspend fun ProtectedAccess.talkArsen(npc: Npc) {
        val thief = CorsairVarBits.thief
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not talked to Captain Tock yet:" to {
                            !carries(CorsairObjs.relic)
                        },
                        "If the player has not talked to Chief Tess:" to {
                            carries(CorsairObjs.relic)
                        },
                    ),
            )
        lateSection("Arsen")?.let {
            runQuestSection(npc, dialogue, it, hooks)
            return
        }
        when {
            state < THEORIES ->
                runQuestSection(npc, dialogue, "Talking to Arsen before talking to Ithoi")
            state >= TRAITOR -> {
                runQuestSection(npc, dialogue, "Talking to Arsen (2)", hooks)
                if (crew(thief) < FRANCOIS) setCrew(thief, FRANCOIS)
            }
            crew(thief) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Arsen", hooks)
                setCrew(thief, STORY)
            }
            crew(thief) == INVESTIGATED -> {
                runQuestSection(npc, dialogue, "Returning to Arsen", hooks)
                setCrew(thief, DEBUNKED)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Arsen again", hooks)
        }
    }

    private suspend fun ProtectedAccess.talkTess(npc: Npc) {
        var returned = false
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "You present Tess with Arsen's loot." to {
                            returned = carries(CorsairObjs.relic)
                            false
                        },
                    ),
            )
        val section =
            if (carries(CorsairObjs.relic)) {
                "Talking to Chief Tess after talking to Captain Tock"
            } else {
                "Talking to Chief Tess before talking to Captain Tock"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (returned) {
            invDel(inv, CorsairObjs.relic)
            setCrew(CorsairVarBits.thief, INVESTIGATED)
        }
    }

    /* Colin */

    private suspend fun ProtectedAccess.talkColin(npc: Npc) {
        val boy = CorsairVarBits.cabinboy
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not completed Big Chompy Bird Hunting:" to { true },
                        "If the player has completed Big Chompy Bird Hunting:" to { false },
                    ),
            )
        lateSection("Colin")?.let {
            runQuestSection(npc, dialogue, it, hooks)
            return
        }
        when {
            state < THEORIES ->
                runQuestSection(npc, dialogue, "Talking to Colin before talking to Ithoi")
            state >= TRAITOR -> runQuestSection(npc, dialogue, "Talking to Colin (2)", hooks)
            crew(boy) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Colin", hooks)
                setCrew(boy, STORY)
            }
            crew(boy) == INVESTIGATED -> {
                runQuestSection(npc, dialogue, "Returning to Colin", hooks)
                setCrew(boy, DEBUNKED)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Colin again", hooks)
        }
    }

    private suspend fun ProtectedAccess.telescope() {
        val boy = CorsairVarBits.cabinboy
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not completed Big Chompy Bird Hunting:" to { true },
                        "If the player has completed Big Chompy Bird Hunting:" to { false },
                    ),
            )
        when {
            state == THEORIES && crew(boy) == STORY -> {
                runQuestSection(null, dialogue, "Using Ithoi's telescope", hooks)
                setCrew(boy, INVESTIGATED)
            }
            state >= TRAITOR -> runQuestSection(null, dialogue, "Observing the telescope again")
            else -> runQuestSection(null, dialogue, "Using Ithoi's telescope again", hooks)
        }
    }

    /* Gnocci */

    private suspend fun ProtectedAccess.talkGnocci(npc: Npc) {
        val cook = CorsairVarBits.cook
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has spoken to Arsen beforehand:" to {
                            crew(CorsairVarBits.thief) >= FRANCOIS
                        },
                    ),
            )
        lateSection("Gnocci", gnocchi = true)?.let {
            runQuestSection(npc, dialogue, it, hooks)
            return
        }
        when {
            state < THEORIES ->
                runQuestSection(npc, dialogue, "Talking to Gnocci before talking to Ithoi")
            state >= TRAITOR && crew(CorsairVarBits.navigator) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Gnocchi", hooks)
                setCrew(CorsairVarBits.navigator, 1)
            }
            state >= TRAITOR -> runQuestSection(npc, dialogue, "Talking to Gnocchi again", hooks)
            crew(cook) == 0 -> {
                runQuestSection(npc, dialogue, "Talking to Gnocci", hooks)
                setCrew(cook, STORY)
            }
            crew(cook) == INVESTIGATED -> {
                runQuestSection(npc, dialogue, "Returning to Gnocci", hooks)
                setCrew(cook, DEBUNKED)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Gnocci again", hooks)
        }
    }

    /** The doll's lump of sand west of Ithoi's hut (via [QuestDigSpots]). */
    private suspend fun ProtectedAccess.digDoll(): Boolean {
        val here = player.coords
        if (here.level != DOLL_SPOT.level || here.chebyshevDistance(DOLL_SPOT) > 1) return false
        if (state != THEORIES || crew(CorsairVarBits.cook) != STORY) return false
        var dig = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have 18 Crafting:" to {
                            player.statMap.getBaseLevel(stats.crafting) < DOLL_CRAFTING
                        },
                        "If the player has 18 Crafting:" to {
                            player.statMap.getBaseLevel(stats.crafting) >= DOLL_CRAFTING
                        },
                    ),
                options =
                    mapOf(
                        "Search for the possessed doll and face the consequences." to {
                            dig = true
                        },
                    ),
            )
        runQuestSection(null, dialogue, "Digging for Her", hooks)
        if (dig) setCrew(CorsairVarBits.cook, INVESTIGATED)
        return true
    }

    /* The driftwood under Ithoi's hut */

    private suspend fun ProtectedAccess.lightDriftwood() {
        if (state != CONFESSED) {
            runQuestSection(null, dialogue, DRIFTWOOD_EARLY)
            return
        }
        runQuestSection(null, dialogue, DRIFTWOOD_LATE)
        setState(DRIFTWOOD_LIT)
    }

    companion object {
        const val AGREED = 10
        const val SAILED = 15
        const val THEORIES = 20
        const val TRAITOR = 30
        const val CONFESSED = 40
        const val DRIFTWOOD_LIT = 49
        const val KILL_ORDER = 50
        const val FIGHTING = 52
        const val ITHOI_DEAD = 55
        const val COMPLETE = 60

        /* Crew varbits. */
        private const val STORY = 1
        private const val INVESTIGATED = 2
        private const val DEBUNKED = 3
        private const val FRANCOIS = 4

        private const val SAIL_TICKS = 3
        private const val ITHOI_TICKS = 500
        private const val DOLL_CRAFTING = 18
        private const val FIREMAKING_XP = 1200.0
        private const val COVE_MAX_Z = 3000

        private val RIMMINGTON_LANDING = CoordGrid(2909, 3226, 0)
        private val COVE_LANDING = CoordGrid(2574, 2837, 0)
        private val DOLL_SPOT = CoordGrid(2504, 2839, 0)

        private const val TOCK_PROVEN =
            "Talking to Captain Tock after proving Ithoi poisoned the crew"
        private const val TOCK_DONE = "Talking to Captain Tock after killing Ithoi"
        private const val RELIC_BOX =
            "Captain Tock gives you the loot that Arsen stole from the ogre chief."
        private const val ITHOI_ALL = "Talking to Ithoi after talking to everyone else"
        private const val ITHOI_NOT_ALL = "Talking to Ithoi before talking to everyone else"
        private const val TOCK_CONFESSED =
            "Talking to Captain Tock after Ithoi admits he poisoned the crew"
        private const val TOCK_TRAITOR_AGAIN = "Talking to Captain Tock again (2)"
        private const val TOCK_INVESTIGATION =
            "Talking to Captain Tock during the investigation of the \"curses\""
        private const val ITHOI_INVESTIGATION =
            "Talking to Ithoi during the investigation of the \"curses\""
        private const val DRIFTWOOD_EARLY =
            "Attempting to light the the driftwood before discovering Ithoi's lie"
        private const val DRIFTWOOD_LATE =
            "Attempting to light the the driftwood after discovering Ithoi's lie"

        val CORSAIR_CURSE =
            QuestDef(
                name = "The Corsair Curse",
                varbit = { CorsairVarBits.progress },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("1,200 Firemaking XP", "Access to Corsair Cove's bank"),
                scrollObj = { CorsairObjs.relic },
            )
    }
}

// Every cache ref The Corsair Curse uses (re-check after the rev 243 cutover).

internal object CorsairNpcs : NpcReferences() {
    /* Children of the corsair_captain_* / corsair_ferry_* multinpcs. */
    val tock_ferry = find("corsair_captain_2ops")
    val tocks = listOf(find("corsair_captain_1op"), tock_ferry)

    /* Children of the corscurs_*_sick / _postquest multinpcs. */
    val ithoi = find("corscurs_navigator")
    val ithoi_combat = find("corscurs_navigator_combat")
    val arsens = listOf(find("corscurs_thief"))
    val colins = listOf(find("corscurs_cabinboy"), find("corscurs_cabinboy_2ops"))
    val gnocci = find("corscurs_cook")
    val tess = find("ogress_tess")
}

internal object CorsairLocs : LocReferences() {
    val telescope = find("corscurs_telescope")
    val driftwood = find("corscurs_driftwood_dry")
}

internal object CorsairObjs : ObjReferences() {
    val relic = find("corscurs_relic")
    val tinderbox = find("tinderbox")
}

internal object CorsairVarBits : VarBitReferences() {
    val progress = find("corscurs_progress")
    val cabinboy = find("corscurs_cabinboy")
    val cook = find("corscurs_cook")
    val thief = find("corscurs_thief")
    val navigator = find("corscurs_navigator")
}
