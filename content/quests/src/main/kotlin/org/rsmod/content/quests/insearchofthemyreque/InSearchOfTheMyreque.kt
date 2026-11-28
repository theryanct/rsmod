package org.rsmod.content.quests.insearchofthemyreque

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.naturespirit.NatureSpirit.Companion.NATURE_SPIRIT
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * In Search of the Myreque (OSRS Wiki). The quest varp is the cache's `routequest` (0-105); Sani
 * and Harold's multinpcs read it, and the hideout members show while `route_hideout_npcs` is 0.
 * - Vanstrom Klause (Canifis pub) asks you to take steel weapons to the Myreque.
 * - Cyreg Paddlehorn (Mort'ton) is talked round; with a druid pouch he takes three planks to mend
 *   his Swamp Boaty (10 coins a trip, free with a Ring of Charos (a)).
 * - Curpile Fyod (the Hollows) asks three of six questions; all right lets you in.
 * - The cave behind the tree leads to the hideout: Veliaf takes the weapons (a steel longsword,
 *   two steel swords, a steel mace, a steel warhammer and a steel dagger); a skeleton hellhound
 *   attacks; Veliaf shows the way out to Canifis, where the Stranger sits in Vanstrom's chair.
 *   2 QP, 600 Attack, Defence, Strength, Hitpoints and Crafting xp.
 *
 * Simplified (Authored, not Wiki): the Hollows' broken tree bridge isn't modelled (its locs aren't
 * placed in this cache), so no nails or extra planks are needed; the hideout doors, tunnel and
 * escape arrival tiles and the hideout spawns are best guesses (Ryan-test).
 */
class InSearchOfTheMyreque
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val rng: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(InSearchOfTheMyreque::class.java, "in_search_of_the_myreque")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Vanstrom Klause" to npcTypes[MyqNpcs.vanstrom],
            "Cyreg Paddlehorn" to npcTypes[MyqNpcs.cyreg],
            "Curpile Fyod" to npcTypes[MyqNpcs.curpile],
            "Veliaf Hurtz" to npcTypes[MyqNpcs.veliaf],
            "Harold Evans" to npcTypes[MyqNpcs.harold],
            "Radigad Ponfit" to npcTypes[MyqNpcs.radigad],
            "Polmafi Ferdygris" to npcTypes[MyqNpcs.polmafi],
            "Sani Piliu" to npcTypes[MyqNpcs.sani],
            "Ivan Strom" to npcTypes[MyqNpcs.ivan],
            "Stranger" to npcTypes[MyqNpcs.stranger],
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, MyqNpcs.vanstrom, IN_SEARCH_OF_THE_MYREQUE, wants = { true }) { talkVanstrom(it) }
            register(npcTypes, MyqNpcs.cyreg, IN_SEARCH_OF_THE_MYREQUE, wants = { true }) { talkCyreg(it) }
            register(npcTypes, MyqNpcs.curpile, IN_SEARCH_OF_THE_MYREQUE, wants = { true }) { talkCurpile(it) }
            register(npcTypes, MyqNpcs.veliaf, IN_SEARCH_OF_THE_MYREQUE, wants = { true }) { talkVeliaf(it) }
            for ((npc, name) in MyqNpcs.members) {
                register(npcTypes, npc, IN_SEARCH_OF_THE_MYREQUE, wants = { true }) { talkMember(it, name) }
            }
            register(npcTypes, MyqNpcs.stranger, IN_SEARCH_OF_THE_MYREQUE, wants = { stage >= HELLHOUND_DEAD }) {
                talkStranger(it)
            }
        }
        onLocOpLabel(locTypes, MyqLocs.boat_mortton, "Board") { board(toHollows = true) }
        onLocOpLabel(locTypes, MyqLocs.boat_mortton, "Quick-board") { board(toHollows = true) }
        onLocOpLabel(locTypes, MyqLocs.boat_hollows, "Board") { board(toHollows = false) }
        for (door in MyqLocs.hideout_doors) onLocOpLabel(locTypes, door, "Open") { enterTunnels() }
        onLocOpLabel(locTypes, MyqLocs.tunnel, "Enter") { enterHideout() }
        onLocOpLabel(locTypes, MyqLocs.escape, "Escape") { escape() }
        onLocOpLabel(locTypes, MyqLocs.escape, "Quick-escape") { escape() }
        onEvent<NpcKilledEvent> { hellhoundKilled(this) }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(IN_SEARCH_OF_THE_MYREQUE)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(IN_SEARCH_OF_THE_MYREQUE, to)
    }

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: SimpleQuestHooks = SimpleQuestHooks()) {
        runQuestSection(npc, dialogue, title, hooks, speakers)
    }

    private fun ProtectedAccess.hasWeapons(): Boolean =
        carries(MyqObjs.longsword) &&
            carries(MyqObjs.sword, 2) &&
            carries(MyqObjs.mace) &&
            carries(MyqObjs.warhammer) &&
            carries(MyqObjs.dagger)

    /* Vanstrom and Cyreg */

    private suspend fun ProtectedAccess.talkVanstrom(npc: Npc) {
        if (stage > 0) {
            section(npc, "Talking to Vanstrom Klause again")
            return
        }
        if (!player.isQuestComplete(NATURE_SPIRIT) || stat(stats.agility) < AGILITY_REQUIRED) {
            // Authored line (not Wiki): the quest's requirements.
            mes("You need to have completed Nature Spirit and have $AGILITY_REQUIRED Agility to help the Myreque.")
            return
        }
        var start = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes." to { start = true }))
        section(npc, "Vanstrom Klause", hooks)
        if (start) advance(STARTED)
    }

    private suspend fun ProtectedAccess.talkCyreg(npc: Npc) {
        when {
            stage < STARTED -> section(npc, "Talking to Cyreg again")
            stage >= BOAT_FIXED -> section(npc, "Talking to Cyreg after fixing the boaty")
            else -> {
                val pouch = carries(MyqObjs.druid_pouch) || carries(MyqObjs.druid_pouch_empty)
                var planks = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have a druid pouch in their inventory:" to { !pouch },
                                "If the player has a Druid Pouch in their inventory:" to { pouch },
                            ),
                        options =
                            mapOf(
                                "What kind of a man are you to say that you don't care?" to { advance(CYREG) },
                                "Here are some planks for you." to { planks = carries(MyqObjs.plank, PLANKS) },
                            ),
                    )
                section(npc, if (stage >= CYREG) "Talking to Cyreg after convincing him" else "Talking to Cyreg", hooks)
                if (stage >= CYREG && !planks && pouch && carries(MyqObjs.plank, PLANKS)) {
                    // The convinced boatman still needs his planks (Authored, not Wiki).
                    mes("The boatman takes three wooden planks from you and uses them to fix his boat.")
                    planks = true
                }
                if (planks) {
                    invDel(inv, MyqObjs.plank, PLANKS)
                    advance(BOAT_FIXED)
                }
            }
        }
    }

    private fun ProtectedAccess.board(toHollows: Boolean) {
        if (stage < BOAT_FIXED && !player.isQuestComplete(IN_SEARCH_OF_THE_MYREQUE)) {
            mes("The boatman won't take you anywhere just yet.") // Authored (not Wiki).
            return
        }
        val charos = invTotal(worn, MyqObjs.ring_of_charos) > 0
        if (!charos) {
            if (!carries(MyqObjs.coins, FARE)) {
                mes("You need $FARE coins to pay the boatman.") // Authored (not Wiki).
                return
            }
            invDel(inv, MyqObjs.coins, FARE)
        }
        // Authored line (not Wiki).
        mes(if (toHollows) "The boatman rows you through the swamp to the Hollows." else "The boatman rows you back to Mort'ton.")
        telejump(if (toHollows) HOLLOWS else MORTTON)
    }

    /* Curpile's questions */

    private class Question(val text: String, val answers: List<String>, val correct: Int)

    private suspend fun ProtectedAccess.talkCurpile(npc: Npc) {
        if (stage >= CURPILE) {
            section(npc, "Talking to Curpile Fyod again after convincing him")
            return
        }
        if (stage < BOAT_FIXED) {
            startDialogue(npc) { chatNpc(neutral, "Hey. What're you doin' here?") }
            return
        }
        var correct = 0
        val pool = QUESTIONS.toMutableList()
        val picked = List(QUIZ_LENGTH) { rng.pick(pool).also { pool.remove(it) } }
        val armed = hasWeapons()
        startDialogue(npc) {
            chatNpc(neutral, "Hey. What're you doin' here?")
            chatPlayer(neutral, "I've come to help the Myreque. I've brought weapons.")
            if (!armed) {
                chatNpc(angry, "It don't look like you have suitable weapons to me! Get out of here you fake!")
                return@startDialogue
            }
            chatNpc(
                neutral,
                "Okay, I's got an idea. Say I asks you a few questions and you answer them all " +
                    "correct and so on... Well that'd make me trust you's. How's dat?",
            )
            chatPlayer(neutral, "Sounds fine to me... Go ahead, shoot!")
            for ((i, q) in picked.withIndex()) {
                chatNpc(neutral, "Okay, ${ORDINALS[i]} question. ${q.text}")
                val a = q.answers
                val choice = choice5(a[0], 0, a[1], 1, a[2], 2, a[3], 3, a[4], 4, title = q.text)
                chatPlayer(neutral, a[choice])
                if (choice == q.correct) correct++
                chatNpc(neutral, AFTER_ANSWER[i])
            }
            when (correct) {
                QUIZ_LENGTH ->
                    chatNpc(
                        happy,
                        "Okay, I believes ya... You can go on in. Head through the doors behind the " +
                            "tree. Once you's in, take the first cave on the right.",
                    )
                0 -> chatNpc(angry, "I'm sorry but you got no questions correct! Go on get outta here...")
                1 -> chatNpc(angry, "I'm sorry but you only got one question correct! Go on get outta here...")
                else -> chatNpc(angry, "I'm sorry but you only got two questions correct! Go on get outta here...")
            }
        }
        if (!armed) return
        if (correct == QUIZ_LENGTH) {
            advance(CURPILE)
        } else {
            mesbox("Curpile knocks you out and takes you back to Mort'ton.")
            telejump(MORTTON)
        }
    }

    /* The hideout */

    private fun ProtectedAccess.enterTunnels() {
        if (stage < CURPILE && !player.isQuestComplete(IN_SEARCH_OF_THE_MYREQUE)) {
            mes("The doors won't budge.") // Authored (not Wiki).
            return
        }
        telejump(TUNNELS)
    }

    private suspend fun ProtectedAccess.enterHideout() {
        if (stage < CURPILE && !player.isQuestComplete(IN_SEARCH_OF_THE_MYREQUE)) {
            mes("You'd better not go wandering into strange caves.") // Authored (not Wiki).
            return
        }
        telejump(HIDEOUT)
        if (stage == CURPILE) {
            section(null, "When first entering the hideout")
            advance(ENTERED)
        }
    }

    private suspend fun ProtectedAccess.talkMember(npc: Npc, name: String) {
        val title =
            when {
                stage < VELIAF -> name
                stage < HELLHOUND_DEAD -> "$name (2)"
                name in AFTER_HELLHOUND -> "$name (3)"
                else -> "$name (2)"
            }
        val hooks =
            SimpleQuestHooks(conditions = mapOf("If first time speaking:" to { true }, "If speaking again:" to { false }))
        section(npc, title, hooks)
    }

    private suspend fun ProtectedAccess.talkVeliaf(npc: Npc) {
        when {
            stage < VELIAF -> {
                section(npc, "Talking to Veliaf")
                advance(VELIAF)
            }
            stage == VELIAF -> {
                val armed = hasWeapons()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does have the weapons:" to { armed },
                                "If the player does not have the weapons:" to { !armed },
                            )
                    )
                section(npc, "Speaking to Veliaf after introduced to other members", hooks)
                if (armed && hasWeapons()) {
                    invDel(inv, MyqObjs.longsword)
                    invDel(inv, MyqObjs.sword, 2)
                    invDel(inv, MyqObjs.mace)
                    invDel(inv, MyqObjs.warhammer)
                    invDel(inv, MyqObjs.dagger)
                    advance(WEAPONS)
                    spawnHellhound(player)
                }
            }
            stage == WEAPONS -> {
                mes("Watch out for the skeleton hellhound!") // Authored (not Wiki).
                spawnHellhound(player)
            }
            else -> {
                val hooks =
                    SimpleQuestHooks(conditions = mapOf("If first time speaking:" to { true }, "If speaking again:" to { false }))
                section(npc, "Veliaf Hurtz", hooks)
            }
        }
    }

    private fun spawnHellhound(player: Player) {
        val hound = Npc(npcTypes[MyqNpcs.hellhound], HELLHOUND_SPOT)
        npcRepo.add(hound, HELLHOUND_TICKS)
        hound.opPlayer2(player, aiInteractions)
    }

    private fun hellhoundKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != MyqNpcs.hellhound.id) return
        val player = event.hero
        if (player.questState(IN_SEARCH_OF_THE_MYREQUE) != WEAPONS) return
        player.setQuestState(IN_SEARCH_OF_THE_MYREQUE, HELLHOUND_DEAD)
        player.mes("The skeleton hellhound collapses into a pile of bones. Talk to Veliaf.") // Authored.
    }

    private fun ProtectedAccess.escape() {
        if (stage < HELLHOUND_DEAD && !player.isQuestComplete(IN_SEARCH_OF_THE_MYREQUE)) {
            mes("You should speak to Veliaf before leaving.") // Authored (not Wiki).
            return
        }
        // Authored line (not Wiki): the escape tunnel and ladder come up in Canifis.
        mes("You crawl through the tunnel and climb up into Canifis.")
        telejump(CANIFIS)
    }

    private suspend fun ProtectedAccess.talkStranger(npc: Npc) {
        section(npc, "Finishing Up")
        if (player.isQuestComplete(IN_SEARCH_OF_THE_MYREQUE)) return
        for (stat in listOf(stats.attack, stats.defence, stats.strength, stats.hitpoints, stats.crafting)) {
            statAdvance(stat, REWARD_XP * xpMods.get(player, stat))
        }
        completeQuest(IN_SEARCH_OF_THE_MYREQUE)
    }

    companion object {
        const val STARTED = 5
        const val CYREG = 10
        const val BOAT_FIXED = 15
        const val CURPILE = 20
        const val ENTERED = 30
        const val VELIAF = 40
        const val WEAPONS = 50
        const val HELLHOUND_DEAD = 60
        const val COMPLETE = 105

        private const val AGILITY_REQUIRED = 25
        private const val PLANKS = 3
        private const val FARE = 10
        private const val QUIZ_LENGTH = 3
        private const val REWARD_XP = 600.0
        private const val HELLHOUND_TICKS = 500

        private val ORDINALS = listOf("first", "second", "third and final")
        private val AFTER_ANSWER =
            listOf(
                "Okay, interesting answer. That's the first question done.",
                "An interesting response. That's the second question answered.",
                "Hmm. Very interesting. That's the third question answered.",
            )

        /** Curpile's six questions (OSRS Wiki transcript), with the right answer's index. */
        private val QUESTIONS =
            listOf(
                Question(
                    "Name the only female member of the Myreque.",
                    listOf("Sani Piliu.", "Santi Peliou.", "Sani Peridou.", "Sandi Pherimou.", "I don't know."),
                    0,
                ),
                Question(
                    "Who is the youngest member of the Myreque?",
                    listOf("Sani Piliu.", "Ivan Strom.", "Veliaf Hurtz.", "Radigad Ponfit.", "I don't know."),
                    1,
                ),
                Question(
                    "Who is the leader of the Myreque in Mort Myre?",
                    listOf("Sani Piliu.", "Harold Evans.", "Veliaf Hurtz.", "Radigad Ponfit.", "I don't know."),
                    2,
                ),
                Question(
                    "Which member of the Myreque was originally a scholar?",
                    listOf("Sani Piliu.", "Harold Evans.", "Radigad Ponfit.", "Polmafi Ferdygris.", "I don't know."),
                    3,
                ),
                Question(
                    "What is the name of the boatman who helps the Myreque?",
                    listOf("Geof Paddleman.", "Cyreg Paddlebone.", "Gyrec Paddlehorn.", "Cyreg Paddlehorn.", "I don't know."),
                    3,
                ),
                Question(
                    "What vampyre family rules over Morytania?",
                    listOf("Drunken.", "Drakan.", "Draynor.", "Praymore.", "I don't know."),
                    1,
                ),
            )

        /** Members with a third, post-hellhound section in the transcript. */
        private val AFTER_HELLHOUND = setOf("Radigad Ponfit", "Polmafi Ferdygris", "Ivan Strom")

        /** Authored arrival tiles (Ryan-test). */
        private val MORTTON = CoordGrid(3522, 3286, 0)
        private val HOLLOWS = CoordGrid(3499, 3380, 0)
        private val TUNNELS = CoordGrid(3488, 9816, 0)
        private val HIDEOUT = CoordGrid(3507, 9834, 0)
        private val HELLHOUND_SPOT = CoordGrid(3510, 9840, 0)
        private val CANIFIS = CoordGrid(3493, 3470, 0)

        val IN_SEARCH_OF_THE_MYREQUE =
            QuestDef(
                name = "In Search of the Myreque",
                varp = { MyqVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "600 Attack XP",
                        "600 Defence XP",
                        "600 Strength XP",
                        "600 Hitpoints XP",
                        "600 Crafting XP",
                        "Access to the Hollows",
                    ),
                scrollObj = { MyqObjs.longsword },
                listRow = "quest_insearchofthemyreque",
            )
    }
}

// Every cache ref In Search of the Myreque uses (re-check after cache updates).

internal object MyqNpcs : NpcReferences() {
    val vanstrom = find("route_vanstrom_klause_sitting")
    val cyreg = find("route_cyreg_paddlehorn")
    /** Child of route_curpile_fyod (route_hideout_npcs). */
    val curpile = find("route_curpile_fyod_child")
    /** Child of route_veliaf_hurtz_parent. */
    val veliaf = find("route_veliaf_hurtz")
    val harold = find("route_harold_evans_vis")
    val radigad = find("route_radigad_ponfit")
    val polmafi = find("route_polmafi_ferdygris")
    val sani = find("route_sani_piliu_vis")
    val ivan = find("route_ivan_strom")
    val stranger = find("canafis_stranger")
    val hellhound = find("skeleton_hellhound")
    val members
        get() =
            listOf(
                harold to "Harold Evans",
                radigad to "Radigad Ponfit",
                polmafi to "Polmafi Ferdygris",
                sani to "Sani Piliu",
                ivan to "Ivan Strom",
            )
}

internal object MyqLocs : LocReferences() {
    val boat_mortton = find("route_rowboat_mortton")
    val boat_hollows = find("route_rowboat_hollows")
    /** The doors in the hill behind Curpile's tree (3509-3510,3447). */
    val hideout_doors = listOf(find("freedomfighterentrancel"), find("freedomfighterentrancer"))
    val tunnel = find("route_cavewalltunnel")
    val escape = find("route_cavewalltunnel_escape")
}

internal object MyqObjs : ObjReferences() {
    val longsword = find("steel_longsword")
    val sword = find("steel_sword")
    val mace = find("steel_mace")
    val warhammer = find("steel_warhammer")
    val dagger = find("steel_dagger")
    val plank = find("woodplank")
    val coins = find("coins")
    val druid_pouch = find("druid_pouch")
    val druid_pouch_empty = find("druid_pouch_empty")
    val ring_of_charos = find("ring_of_charos_unlocked")
}

internal object MyqVarps : VarpReferences() {
    val quest = find("routequest")
}
