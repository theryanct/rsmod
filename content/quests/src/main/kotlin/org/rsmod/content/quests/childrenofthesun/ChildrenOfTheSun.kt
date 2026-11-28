package org.rsmod.content.quests.childrenofthesun

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Children of the Sun (OSRS Wiki). Quest varbit `vmq1` (bits 0-6 of `vmq1_primary`), end state 24;
 * the cache multis fix the stages: Noah and Alina show at 0-6, Sergeant Tobyn's square variant from
 * 6, the bag-carrying "guard" (and the bandits' hideout) at 8, the roof cell (Tobyn, Itzla, the
 * captured bandit) from 16. Each of the ten square guards reads `vmq1_guard_N`: 1 Mark, 2 Unmark.
 * - 2 Noah or Alina (Varrock Square) talk about the delegation from Varlamore; a guard hurries off
 *   with a big bag. 8 tailing him to south-east Varrock without being seen or losing him; 10 his
 *   meeting with the bandits overheard. 12 Sergeant Tobyn: point out the four bandits disguised as
 *   guards (hands on hips; no helmet; leaning, arms crossed; a mace). 16 correct: Tobyn takes you to
 *   the cell on the palace roof; Prince Itzla interrogates the bandit (22, Itzla leaves); 24 Tobyn.
 *   Reward: 1 QP, access to Varlamore (Regulus Cento outside Varrock's east gate).
 *
 * Simplified: the cutscenes are their dialogue; the tail checks distance only (the guard spots you
 * within 4 tiles whenever he stops to look round, and loses you past 16); which four guards are the
 * bandits comes from their cache models (no weapon/helmet, the odd idle stances).
 */
class ChildrenOfTheSun
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcRepo: NpcRepository,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ChildrenOfTheSun::class.java, "children_of_the_sun") }

    /** Players tailing the bag guard: their guard and route progress. */
    private val tails = HashMap<Int, Tail>()

    private class Tail(val guard: Npc) {
        var waypoint = 0
        var lookTicks = 0
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Noah" to CotsNpcs.noah_vis,
            "Alina" to CotsNpcs.alina_vis,
            "Sergeant Tobyn" to CotsNpcs.sergeant_vis,
            "Guard" to CotsNpcs.bag_guard,
            "Prince Itzla" to CotsNpcs.itzla_vis,
            "Itzla" to CotsNpcs.itzla_vis,
        )
    }

    override fun ScriptContext.startup() {
        // The quest npcs are multinpcs whose base types have no ops of their own.
        onOpNpc1(CotsNpcs.noah) { talkNoah(it.npc) }
        onOpNpc1(CotsNpcs.alina) { talkNoah(it.npc) }
        onOpNpc1(CotsNpcs.sergeant) { talkSergeant(it.npc) }
        onOpNpc1(CotsNpcs.sergeant_roof) { talkRoof(it.npc) }
        onOpNpc1(CotsNpcs.itzla) { talkRoof(it.npc) }
        for ((index, guard) in CotsNpcs.guards.withIndex()) onOpNpc1(guard) { markGuard(index) }
        onPlayerSoftTimer(CotsTimers.tail) { tailTick(player) }
        onPlayerLogout { endTail(player) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(CHILDREN_OF_THE_SUN)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(CHILDREN_OF_THE_SUN, value)
    }

    /* Noah and Alina: the delegation and the bag */

    private suspend fun ProtectedAccess.talkNoah(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                var tail = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is on a free-to-play world:" to { false },
                                "If the player is on a member's world:" to { true },
                            ),
                        options = mapOf("Yes." to { start = true }),
                        actions = mapOf("(Player begins tailing the guard.)" to { tail = true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Noah or Alina", hooks, speakers)
                if (start) setState(STARTED)
                if (tail) beginTail()
            }
            in STARTED until TAILING -> {
                var tail = false
                val hooks = SimpleQuestHooks(actions = mapOf("(Player begins tailing the guard.)" to { tail = true }))
                runQuestSection(npc, dialogue, "Talking to Noah or Alina after starting to tail the guard", hooks, speakers)
                if (tail) beginTail()
            }
            TAILING -> mes("You should keep after that guard.") // Authored (not Wiki).
            else -> {
                val hooks = SimpleQuestHooks(conditions = mapOf("If the player is on a member's world:" to { true }))
                runQuestSection(npc, dialogue, "After starting the quest", hooks, speakers)
            }
        }
    }

    /* Tailing the "guard" (a soft timer: the player keeps moving freely) */

    private fun ProtectedAccess.beginTail() {
        endTail(player)
        val guard = Npc(npcTypes[CotsNpcs.bag_guard], ROUTE.first())
        npcRepo.add(guard, TAIL_TICKS)
        val tail = Tail(guard)
        tails[player.uid.packed] = tail
        advance(tail)
        setState(TAILING)
        mes("The guard heads off with his bag. Follow him without being seen.") // Authored (not Wiki).
        player.softTimer(CotsTimers.tail, 1)
    }

    private fun endTail(player: Player) {
        val tail = tails.remove(player.uid.packed) ?: return
        player.clearSoftTimer(CotsTimers.tail)
        npcRepo.del(tail.guard, 1)
    }

    private fun tailTick(player: Player) {
        val tail = tails[player.uid.packed] ?: return
        if (player.questState(CHILDREN_OF_THE_SUN) != TAILING) return endTail(player)
        val guard = tail.guard
        val distance = guard.coords.chebyshevDistance(player.coords)
        if (guard.coords.level != player.coords.level || distance > LOSE_RANGE) {
            failTail(player, spotted = false)
            return
        }
        if (tail.lookTicks > 0) {
            tail.lookTicks--
            if (distance <= SPOT_RANGE) {
                failTail(player, spotted = true)
                return
            }
            if (tail.lookTicks == 0) advance(tail)
        } else if (guard.coords.chebyshevDistance(ROUTE[tail.waypoint]) <= 1) {
            if (tail.waypoint == ROUTE.lastIndex) {
                finishTail(player)
                return
            }
            tail.lookTicks = LOOK_TICKS
            guard.say("...") // He stops and looks round.
        }
        player.softTimer(CotsTimers.tail, 1)
    }

    private fun advance(tail: Tail) {
        tail.waypoint++
        tail.guard.walk(ROUTE[tail.waypoint])
    }

    private fun failTail(player: Player, spotted: Boolean) {
        endTail(player)
        player.setQuestState(CHILDREN_OF_THE_SUN, STARTED)
        protectedAccess.launch(player) {
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player is spotted by the guard:" to { spotted },
                            "If the player is too far from the guard:" to { !spotted },
                        )
                )
            runQuestSection(null, dialogue, "Tailing the \"guard\"", hooks, speakers)
            mes("Talk to Noah or Alina to try again.") // Authored (not Wiki).
        }
    }

    private fun finishTail(player: Player) {
        endTail(player)
        player.setQuestState(CHILDREN_OF_THE_SUN, OVERHEARD)
        protectedAccess.launch(player) { runQuestSection(null, dialogue, "Successfully following the \"guard\"") }
    }

    /* Sergeant Tobyn and the disguised bandits */

    private suspend fun ProtectedAccess.talkSergeant(npc: Npc) {
        when (state) {
            TAILING -> runQuestSection(npc, dialogue, "Talking to Sergeant Tobyn after starting to tail the guard")
            OVERHEARD -> {
                runQuestSection(npc, dialogue, "Talking to Sergeant Tobyn")
                for (guard in CotsVarBits.guards) VarPlayerIntMapSetter.set(player, guard, UNMARKED)
                setState(MARKING)
            }
            MARKING ->
                if (marked().size < BANDITS.size) {
                    runQuestSection(npc, dialogue, "Talking to Sergeant Tobyn again")
                } else {
                    reportBandits(npc)
                }
            in ROOF..COMPLETE -> runQuestSection(npc, dialogue, "Talking to Sergeant Tobyn after turning in the guards")
            else -> runQuestSection(npc, dialogue, "Talking to Sergeant Tobyn after starting to tail the guard")
        }
    }

    private fun ProtectedAccess.marked(): List<Int> =
        CotsVarBits.guards.indices.filter { player.vars[CotsVarBits.guards[it]] == MARKED }

    private suspend fun ProtectedAccess.reportBandits(npc: Npc) {
        val correct = marked().toSet() == BANDITS
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player incorrectly marked any guards:" to { !correct },
                        "If the player correctly marked all four bandits:" to { correct },
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Sergeant Tobyn after marking the imposters", hooks, speakers)
        if (!correct) return
        for (guard in CotsVarBits.guards) VarPlayerIntMapSetter.set(player, guard, DONE)
        setState(ROOF)
        telejump(ROOF_CELL)
    }

    private suspend fun ProtectedAccess.markGuard(index: Int) {
        if (state != MARKING) return
        val varbit = CotsVarBits.guards[index]
        if (player.vars[varbit] == MARKED) {
            VarPlayerIntMapSetter.set(player, varbit, UNMARKED)
            runQuestSection(null, dialogue, "Unmarking a guard")
            return
        }
        val full = marked().size >= BANDITS.size
        val hooks = SimpleQuestHooks(conditions = mapOf("If four guards are already marked:" to { full }))
        runQuestSection(null, dialogue, "Marking a guard", hooks)
        if (!full) VarPlayerIntMapSetter.set(player, varbit, MARKED)
    }

    /* The palace roof */

    private suspend fun ProtectedAccess.talkRoof(npc: Npc) {
        when (state) {
            ROOF -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "Before the release of Varlamore:" to { false },
                                "After the release of Varlamore:" to { true },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to the Prince or Sergeant", hooks, speakers)
                setState(ITZLA_LEFT)
            }
            ITZLA_LEFT -> {
                runQuestSection(npc, dialogue, "Talking again after Itzla departs", speakers = speakers)
                completeQuest(CHILDREN_OF_THE_SUN)
            }
            else -> runQuestSection(npc, dialogue, "Talking again after Itzla departs", speakers = speakers)
        }
    }

    companion object {
        const val STARTED = 2
        const val TAILING = 8
        const val OVERHEARD = 10
        const val MARKING = 12
        const val ROOF = 16
        const val ITZLA_LEFT = 22
        const val COMPLETE = 24

        private const val UNMARKED = 1
        private const val MARKED = 2
        private const val DONE = 3

        /** Guards 1 (hands on hips), 3 (mace), 4 (leaning) and 8 (no helmet), as list indices. */
        private val BANDITS = setOf(0, 2, 3, 7)

        private const val SPOT_RANGE = 4
        private const val LOSE_RANGE = 16
        private const val LOOK_TICKS = 4
        private const val TAIL_TICKS = 400

        /** The bag guard's route: Varrock Square to the hideout door south of Aubury's. */
        private val ROUTE =
            listOf(
                CoordGrid(3211, 3434, 0),
                CoordGrid(3222, 3428, 0),
                CoordGrid(3236, 3428, 0),
                CoordGrid(3240, 3416, 0),
                CoordGrid(3246, 3405, 0),
                CoordGrid(3253, 3401, 0),
                CoordGrid(3258, 3400, 0),
            )
        private val ROOF_CELL = CoordGrid(3208, 3471, 2)

        val CHILDREN_OF_THE_SUN =
            QuestDef(
                name = "Children of the Sun",
                varbit = { CotsVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("Access to Varlamore"),
                scrollObj = { CotsObjs.scroll },
                listRow = "quest_childrenofthesun",
            )
    }
}

// Every cache ref Children of the Sun uses (re-check after cache updates).

internal object CotsNpcs : NpcReferences() {
    val noah = find("vmq1_noah")
    val alina = find("vmq1_alina")
    val noah_vis = find("vmq1_noah_vis")
    val alina_vis = find("vmq1_alina_vis")
    val sergeant = find("vmq1_guard_sergeant")
    val sergeant_roof = find("vmq1_guard_sergeant_roof")
    val sergeant_vis = find("vmq1_guard_sergeant_vis")
    val itzla = find("vmq1_itzla")
    val itzla_vis = find("vmq1_itzla_vis")
    val bag_guard = find("vmq1_bag_guard")
    val guards =
        listOf(
            find("vmq1_guard_1"),
            find("vmq1_guard_2"),
            find("vmq1_guard_3"),
            find("vmq1_guard_4"),
            find("vmq1_guard_5"),
            find("vmq1_guard_6"),
            find("vmq1_guard_7"),
            find("vmq1_guard_8"),
            find("vmq1_guard_9"),
            find("vmq1_guard_10"),
        )
}

internal object CotsObjs : ObjReferences() {
    val scroll = find("coins")
}

internal object CotsVarBits : VarBitReferences() {
    val quest = find("vmq1")
    val guards: List<VarBitType> =
        listOf(
            find("vmq1_guard_1"),
            find("vmq1_guard_2"),
            find("vmq1_guard_3"),
            find("vmq1_guard_4"),
            find("vmq1_guard_5"),
            find("vmq1_guard_6"),
            find("vmq1_guard_7"),
            find("vmq1_guard_8"),
            find("vmq1_guard_9"),
            find("vmq1_guard_10"),
        )
}

internal object CotsTimers : TimerReferences() {
    /** Custom timer (65705): one tick of tailing the bag guard. */
    val tail = find("cots_tail")
}
