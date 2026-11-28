package org.rsmod.content.quests.ruffsituation

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
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.gertrudescat.GertrudesCat.Companion.GERTRUDES_CAT
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
 * A Ruff Situation (OSRS Wiki). The quest varbit is the cache's `dogquest` (0-120); its multinpcs
 * put the stray dog, the guard, the goblins and the puppies where the stage needs them, and its
 * multilocs light up the den clues at 25. The den clues are `dogq_stray_den_1-3`.
 * - Talia (dog shelter south-west of Edgeville Monastery) starts it; Gertrude points you to the
 *   stray dog barking at a guard south of the west Varrock bank.
 * - The dog's den (west of Thessalia's): inspect the newspaper, box and bedding; she leads you
 *   north of the Cooks' Guild, where Picklenose drops a puppy (pick it up).
 * - The goblins (west of the Cooks' Guild) want a guard dog: grain on fur with a needle and thread
 *   makes a stuffed dog; they trade the second puppy for it.
 * - The dog finds the third puppy at the outlaw camp: kill both outlaws; pick it up; all three
 *   puppies to Talia. 1 QP, 1,000 Crafting xp.
 *
 * Simplified (Authored, not Wiki): the dog doesn't follow you; she waits at each next spot (the
 * cache's multinpcs) and you interact with her there. No pets-out check. The spawn spots are best
 * guesses (Ryan-test).
 */
class RuffSituation
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(RuffSituation::class.java, "a_ruff_situation") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Stray dog" to npcTypes[RuffNpcs.dog],
            "Stray Dog" to npcTypes[RuffNpcs.dog],
            "Stray puppy" to npcTypes[RuffNpcs.puppies[0]],
            "Picklenose" to npcTypes[RuffNpcs.picklenose],
            "Toetaller" to npcTypes[RuffNpcs.toetaller],
            "Guard" to npcTypes[RuffNpcs.guard],
            "Varrock Guard" to npcTypes[RuffNpcs.guard],
            "Talia" to npcTypes[RuffNpcs.talia],
            "Chase" to npcTypes[RuffNpcs.chase],
            "Outlaw" to npcTypes[RuffNpcs.outlaws[0]],
            "Gertrude" to npcTypes[RuffNpcs.gertrude],
            "Banker" to npcTypes[RuffNpcs.banker],
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, RuffNpcs.talia, A_RUFF_SITUATION, wants = { true }) { talkTalia(it) }
            register(npcTypes, RuffNpcs.chase, A_RUFF_SITUATION, wants = { true }) { talkChase(it) }
            for (gertrude in listOf(RuffNpcs.gertrude, RuffNpcs.gertrude_post)) {
                register(npcTypes, gertrude, A_RUFF_SITUATION, wants = { stage in STARTED until DEN }) {
                    talkGertrude(it)
                }
            }
            register(npcTypes, RuffNpcs.guard, A_RUFF_SITUATION, wants = { true }) { talkGuard(it) }
            register(npcTypes, RuffNpcs.picklenose, A_RUFF_SITUATION, wants = { true }) { talkGoblins(it) }
            register(npcTypes, RuffNpcs.toetaller, A_RUFF_SITUATION, wants = { stage in GOBLINS until PUPPY_TWO }) {
                talkGoblins(it)
            }
        }
        onNpcOpLabel(npcTypes, RuffNpcs.dog, "Interact") { interactDog(it) }
        for ((i, puppy) in RuffNpcs.puppies.withIndex()) {
            onNpcOpLabel(npcTypes, puppy, "Pick-up") { pickUpPuppy(it, i) }
        }
        for ((loc, varbit) in RuffLocs.den_clues.zip(RuffVarBits.den_clues)) {
            onLocOpLabel(locTypes, loc.first, "Inspect") { inspectDen(loc.second, varbit) }
        }
        onOpHeldU(RuffObjs.grain, RuffObjs.fur) { craftStuffedDog() }
        onEvent<NpcKilledEvent> { outlawKilled(this) }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(A_RUFF_SITUATION)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(A_RUFF_SITUATION, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: SimpleQuestHooks = SimpleQuestHooks()) {
        runQuestSection(npc, dialogue, title, hooks, speakers)
    }

    private fun ProtectedAccess.puppyCount(): Int = RuffObjs.puppies.count { carries(it) }

    /* The shelter */

    private suspend fun ProtectedAccess.talkTalia(npc: Npc) {
        when {
            stage == 0 -> {
                val reqs = stat(stats.crafting) >= CRAFTING_REQUIRED
                var start = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not meet the quest's requirements:" to { !reqs },
                                "If the player meets the quest's requirements:" to { reqs },
                            ),
                        options = mapOf("Yes." to { start = reqs }),
                    )
                val title = if (flag(RuffVarBits.talia_intro) == 1) "Talking to Talia again" else "Talking to Talia"
                section(npc, title, hooks)
                setFlag(RuffVarBits.talia_intro, 1)
                if (start) advance(STARTED)
            }
            stage >= COMPLETE -> section(npc, "Post-quest dialogue")
            stage >= PUPPY_THREE && puppyCount() == RuffObjs.puppies.size -> {
                section(npc, "Back at the shelter")
                for (puppy in RuffObjs.puppies) invDel(inv, puppy)
                statAdvance(stats.crafting, REWARD_XP * xpMods.get(player, stats.crafting))
                completeQuest(A_RUFF_SITUATION)
            }
            stage >= PUPPY_TWO -> section(npc, "Talking to Talia again with two puppies")
            else -> section(npc, "Talking to Talia after starting the quest")
        }
    }

    private suspend fun ProtectedAccess.talkChase(npc: Npc) {
        section(npc, if (stage == 0) "Talking to Chase" else "Talking to Chase after starting the quest")
    }

    private suspend fun ProtectedAccess.talkGertrude(npc: Npc) {
        if (stage >= STRAY) {
            section(npc, "Talking to Gertrude again")
            return
        }
        val cat = player.isQuestComplete(GERTRUDES_CAT)
        var asked = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If Gertrude's Cat has been completed:" to { cat },
                        "If Gertrude's Cat hasn't been completed:" to { !cat },
                    ),
                options = mapOf("Ask about lost dogs." to { asked = true }),
            )
        section(npc, "Gertrude", hooks)
        if (asked) advance(STRAY)
    }

    /* The stray dog */

    private suspend fun ProtectedAccess.talkGuard(npc: Npc) {
        when {
            stage == STRAY -> {
                section(npc, "Talking to the guard or interacting with the stray dog")
                advance(DEN)
            }
            stage in DEN..DEN_DONE -> section(npc, "Talking to the guard again")
            stage in GOBLINS until PUPPY_TWO -> section(npc, "Talking to the guard while the stray dog is with the goblins")
            else -> section(npc, "Talking to the guard")
        }
    }

    private suspend fun ProtectedAccess.interactDog(npc: Npc) {
        when {
            stage == STRAY -> {
                section(npc, "Talking to the guard or interacting with the stray dog")
                advance(DEN)
            }
            stage == DEN && RuffVarBits.den_clues.all { flag(it) == 1 } -> {
                section(npc, "After inspecting all three objects")
                advance(DEN_DONE)
            }
            stage == DEN -> section(npc, "Interacting with the dog (2)")
            stage == DEN_DONE -> {
                val full = inv.isFull()
                section(npc, "Cutscene", SimpleQuestHooks(conditions = mapOf("If the player has no inventory space:" to { full })))
                advance(GOBLINS)
            }
            stage in GOBLINS until PUPPY_TWO -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have a follower:" to { true },
                                "If the player currently has a follower:" to { false },
                            )
                    )
                section(npc, "After the cutscene", hooks)
            }
            stage == PUPPY_TWO -> {
                section(npc, "Looking for the third puppy")
                advance(OUTLAWS)
            }
            stage == OUTLAWS -> {
                section(npc, "Cutscene (2)")
                advance(FIGHTING)
                spawnOutlaws(player)
            }
            stage == FIGHTING -> section(npc, "Periodically, while fighting")
            stage >= RESCUED && stage < COMPLETE -> {
                val full = inv.isFull()
                val all = puppyCount() == RuffObjs.puppies.size
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have inventory space:" to { full },
                                "If the player doesn't have all the puppies with them:" to { !all },
                                "If the player has all the puppies with them:" to { all },
                            )
                    )
                section(npc, "After killing the outlaws", hooks)
            }
            else -> section(npc, "Interacting with the stray dog")
        }
    }

    private suspend fun ProtectedAccess.inspectDen(title: String, varbit: VarBitType) {
        section(null, title)
        if (stage == DEN) setFlag(varbit, 1)
    }

    /* The puppies and the goblins */

    private suspend fun ProtectedAccess.pickUpPuppy(npc: Npc, index: Int) {
        val obj = RuffObjs.puppies[index]
        if (carries(obj)) return
        if (index == 1 && stage < PUPPY_TWO_FREE) {
            talkGoblins(npc)
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space to pick up the stray puppy.")
            return
        }
        invAdd(inv, obj)
        // Authored line (not Wiki).
        mes("You pick up the stray puppy.")
        when (index) {
            0 -> advance(PUPPY_ONE)
            1 -> advance(PUPPY_TWO)
            else -> advance(PUPPY_THREE)
        }
    }

    private suspend fun ProtectedAccess.talkGoblins(npc: Npc) {
        when {
            stage < GOBLINS -> section(npc, "Interacting with the goblins without the stray dog")
            stage < GOBLINS_MET -> {
                section(npc, "Goblins")
                advance(GOBLINS_MET)
            }
            stage < PUPPY_TWO_FREE && carries(RuffObjs.stuffed_dog) -> {
                val full = inv.isFull()
                val hooks =
                    SimpleQuestHooks(conditions = mapOf("If the player doesn't have inventory space:" to { full }))
                section(npc, "Talking to the goblins with the stray dog", hooks)
                invDel(inv, RuffObjs.stuffed_dog)
                advance(PUPPY_TWO_FREE)
            }
            stage < PUPPY_TWO_FREE -> {
                val grain = carries(RuffObjs.grain)
                val fur = carries(RuffObjs.fur)
                val needle = carries(RuffObjs.needle) || carries(RuffObjs.costume_needle)
                val thread = carries(RuffObjs.thread) || carries(RuffObjs.costume_needle)
                val all = grain && fur && needle && thread
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "Human got grain:" to { grain },
                                "Human not got grain:" to { !grain },
                                "Human got fur:" to { fur },
                                "Human not got fur:" to { !fur },
                                "Human got needle or costume needle:" to { needle },
                                "Human not got needle or costume needle:" to { !needle },
                                "Human got thread or costume needle:" to { thread },
                                "Human not got thread or costume needle:" to { !thread },
                                "If the player is missing one or more of the items:" to { !all },
                                "If the player has all the items:" to { all },
                            )
                    )
                section(npc, "Talking to the goblins again or trying to pick up the stray puppy without the stuffed dog", hooks)
            }
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): after the trade.
                    chatNpc(happy, "Big dog guard village now. Human did good.")
                }
        }
    }

    private suspend fun ProtectedAccess.craftStuffedDog() {
        val needle = carries(RuffObjs.needle) || carries(RuffObjs.costume_needle)
        val thread = carries(RuffObjs.thread) || carries(RuffObjs.costume_needle)
        when {
            owns(RuffObjs.stuffed_dog) || stage >= PUPPY_TWO_FREE ->
                section(null, "Trying to craft another stuffed dog")
            !needle || !thread ->
                section(null, "Trying to make the stuffed dog with one or more items missing")
            else -> {
                var make = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { make = true }))
                section(null, "Crafting the stuffed dog", hooks)
                if (make) {
                    invDel(inv, RuffObjs.grain)
                    invDel(inv, RuffObjs.fur)
                    if (!carries(RuffObjs.costume_needle)) invDel(inv, RuffObjs.thread)
                    invAdd(inv, RuffObjs.stuffed_dog)
                }
            }
        }
    }

    private fun ProtectedAccess.owns(obj: ObjType): Boolean = carries(obj)

    /* The outlaws */

    private fun spawnOutlaws(player: Player) {
        for ((type, spot) in RuffNpcs.outlaws.zip(OUTLAW_SPOTS)) {
            val outlaw = Npc(npcTypes[type], spot)
            npcRepo.add(outlaw, OUTLAW_TICKS)
            outlaw.opPlayer2(player, aiInteractions)
        }
    }

    private fun outlawKilled(event: NpcKilledEvent) {
        if (RuffNpcs.outlaws.none { it.id == event.npc.type.id }) return
        val player = event.hero
        if (player.questState(A_RUFF_SITUATION) != FIGHTING) return
        // dogq_stray_dog_hunt is one bit: set on the first outlaw, so the second ends the fight.
        if (player.vars[RuffVarBits.hunt] == 0) {
            VarPlayerIntMapSetter.set(player, RuffVarBits.hunt, 1)
        } else {
            player.setQuestState(A_RUFF_SITUATION, RESCUED)
            // Authored line (not Wiki): the puppy is free.
            player.mes("The outlaws are beaten. Pick up the puppy, then talk to the stray dog.")
        }
    }

    companion object {
        const val STARTED = 5
        const val STRAY = 15
        const val DEN = 25
        const val DEN_DONE = 30
        const val GOBLINS = 45
        const val PUPPY_ONE = 46
        const val GOBLINS_MET = 50
        const val PUPPY_TWO_FREE = 66
        const val PUPPY_TWO = 70
        const val OUTLAWS = 85
        const val FIGHTING = 90
        const val RESCUED = 100
        const val PUPPY_THREE = 105
        const val COMPLETE = 120

        private const val CRAFTING_REQUIRED = 15
        private const val REWARD_XP = 1000.0
        private const val OUTLAW_TICKS = 500

        /** Authored (Ryan-test): the outlaw camp's entrance, north of the Cooks' Guild. */
        private val OUTLAW_SPOTS = listOf(CoordGrid(3138, 3470, 0), CoordGrid(3141, 3471, 0))

        val A_RUFF_SITUATION =
            QuestDef(
                name = "A Ruff Situation",
                varbit = { RuffVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,000 Crafting XP", "The dog shelter"),
                scrollObj = { RuffObjs.stuffed_dog },
                listRow = "quest_ruffsituation",
            )
    }
}

// Every cache ref A Ruff Situation uses (re-check after cache updates).

internal object RuffNpcs : NpcReferences() {
    val talia = find("dogq_talia")
    /** Child of dogq_chase_multi before the quest's end. */
    val chase = find("dogq_chase")
    /** Gertrude's Cat's Gertrude (both forms). */
    val gertrude = find("gertrude_quest")
    val gertrude_post = find("gertrude_post")
    /** The child of the guard multinpcs. */
    val guard = find("dogq_varrock_guard")
    /** The child every stray dog multinpc shows. */
    val dog = find("dogq_dog_stray")
    val puppies = listOf(find("dogq_puppy_one"), find("dogq_puppy_two"), find("dogq_puppy_three"))
    val picklenose = find("dogq_goblin_picklenose")
    val toetaller = find("dogq_goblin_toetaller")
    val outlaws = listOf(find("dogq_outlaw1"), find("dogq_outlaw2"))
    val banker = find("dogq_banker")
}

internal object RuffLocs : LocReferences() {
    /** The den's clue locs (children of the dogq_den_*_multi at 25) with their sections. */
    val den_clues =
        listOf(
            find("dogq_den_scraps") to "Inspecting the torn newspaper",
            find("dogq_den_box") to "Inspecting the chewed box",
            find("dogq_den_bed") to "Inspecting the rough bedding",
        )
}

internal object RuffObjs : ObjReferences() {
    val puppies =
        listOf(
            find("dogq_puppy_one_object"),
            find("dogq_puppy_two_object"),
            find("dogq_puppy_three_object"),
        )
    val stuffed_dog = find("dogq_stuffed_dog")
    val grain = find("grain")
    val fur = find("fur")
    val needle = find("needle")
    val thread = find("thread")
    val costume_needle = find("costumeneedle")
}

internal object RuffVarBits : VarBitReferences() {
    val quest = find("dogquest")
    val talia_intro = find("dogq_talia_intro")
    val den_clues = listOf(find("dogq_stray_den_1"), find("dogq_stray_den_2"), find("dogq_stray_den_3"))
    /** One outlaw beaten. */
    val hunt = find("dogq_stray_dog_hunt")
}
