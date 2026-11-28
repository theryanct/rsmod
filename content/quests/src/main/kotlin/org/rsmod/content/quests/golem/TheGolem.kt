package org.rsmod.content.quests.golem

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
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
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Golem (OSRS Wiki, Transcript:The Golem). Cache quest varbit `golem_a`, end state 10 (dbrow
 * quest_golem; the throne-room portal multiloc opens at 5+). Stages here:
 * - 1 the broken golem in Uzer asks for repairs: four soft clay (`golem_clay` counts them). 2 the
 *   golem asks you to open the portal to the demon's lair. 3 Elissa (Digsite) sends you to
 *   Varmen's notes in the Exam Centre bookcase. 4 the notes say the statuette went to the Varrock
 *   Museum: talk to Curator Haig Halen, pickpocket his display cabinet key, take the statuette.
 * - Place it in the empty alcove under Uzer and turn all four statues the same way (left): 5,
 *   the portal opens. Entering it: 6 (the demon is long dead). 7 the golem still won't believe it.
 *   Make black dye (mushroom + vial + pestle), a phoenix quill (Desert Phoenix feather + dye),
 *   write YOUR TASK IS DONE on papyrus, open the golem's head with the strange implement and put
 *   the program in: 10, 1,000 Thieving and Crafting xp, 1 QP.
 * - Authored (not Wiki): the 243 museum has no golem display case, so the key opens any of the
 *   upstairs timeline display cases; the statue solution (all left) is authored.
 */
class TheGolem
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TheGolem::class.java, "the_golem") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Broken clay golem" to GolemNpcs.broken, "Clay golem" to GolemNpcs.fixed)
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (golem in GolemNpcs.golems) {
                register(npcTypes, golem, THE_GOLEM, wants = { true }) { talkGolem(it) }
            }
            register(npcTypes, GolemNpcs.elissa, THE_GOLEM, wants = { state in 2 until 10 }) { talkElissa(it) }
            register(npcTypes, GolemNpcs.curator, THE_GOLEM, wants = { state == NOTES && !retrieved() }) {
                talkCurator(it)
            }
        }
        for (golem in GolemNpcs.golems) {
            onOpNpcU(golem, GolemObjs.soft_clay) { useClay(it.npc) }
            onOpNpcU(golem, GolemObjs.implement) { openHead(it.npc) }
            onOpNpcU(golem, GolemObjs.program) { useProgram(it.npc) }
        }
        onHeldOpLabel(objTypes, GolemObjs.letter, "Read") { readLetter() }
        onHeldOpLabel(objTypes, GolemObjs.notes, "Read") { readNotes() }
        onLocOpLabel(locTypes, GolemLocs.bookcase, "Search") { searchBookcase() }
        onLocOpLabel(locTypes, GolemLocs.mushrooms, "Pick") { pickMushroom() }
        onLocOpLabel(locTypes, GolemLocs.stairs_top, "Climb-down") { telejump(UNDER_UZER) }
        onLocOpLabel(locTypes, GolemLocs.stairs_base, "Climb-up") { telejump(UZER) }
        // The throne-room door is a multiloc on the quest varbit: locked below 5, open from 5.
        onLocOpLabel(locTypes, GolemLocs.door_locked, "Open") { mes("The door won't budge.") }
        onLocOpLabel(locTypes, GolemLocs.door_open, "Enter") { enterPortal() }
        onLocOpLabel(locTypes, GolemLocs.demon_portal, "Enter") { telejump(PORTAL_BACK) }
        // The alcoves are multilocs (statuette facing right / left, or empty); the base loc says which.
        for (child in GolemLocs.statue_children) {
            onLocOpLabel(locTypes, child, "Turn") { turnStatue(GolemLocs.statues.indexOfFirst { s -> s.id == it.id }) }
        }
        onOpLocU(GolemLocs.alcove_empty, GolemObjs.statuette) { placeStatuette(EMPTY_ALCOVE) }
        for (statue in GolemLocs.statues) {
            onOpLocU(statue, GolemObjs.statuette) { placeStatuette(GolemLocs.statues.indexOfFirst { s -> s.id == statue.id }) }
        }
        if (!isNpcOpBound(GolemNpcs.phoenix, 3)) {
            onOpNpc3(GolemNpcs.phoenix) { grabFeather() }
        }
        for (case in GolemLocs.display_cases) {
            onOpLocU(case, GolemObjs.case_key) { openCase() }
        }
        onOpHeldU(GolemObjs.pestle, GolemObjs.mushroom) { crushMushroom() }
        onOpHeldU(GolemObjs.feather, GolemObjs.ink) { makePen() }
        onOpHeldU(GolemObjs.pen, GolemObjs.papyrus) { writeProgram() }
        OpInterceptors.register(GolemClaims())
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(THE_GOLEM)

    private fun ProtectedAccess.setState(value: Int) = player.setQuestState(THE_GOLEM, value)

    private fun ProtectedAccess.varbit(type: VarBitType): Int = vars[type]

    private fun ProtectedAccess.setVarbit(type: VarBitType, value: Int) =
        VarPlayerIntMapSetter.set(player, type, value)

    private fun ProtectedAccess.retrieved(): Boolean = varbit(GolemVarbits.retrieved_statuette) == 1

    /* The golem */

    private suspend fun ProtectedAccess.talkGolem(npc: Npc) {
        when {
            state == 0 ->
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to the golem to start the quest",
                    SimpleQuestHooks(options = mapOf("Yes" to { setState(STARTED) })),
                    speakers,
                )
            state == STARTED -> runQuestSection(npc, dialogue, "Talking to the Golem after starting the quest", speakers = speakers)
            state in REPAIRED until PORTAL_OPEN ->
                runQuestSection(npc, dialogue, "Talking to the Golem again after learning about the demon", speakers = speakers)
            state == PORTAL_OPEN -> runQuestSection(npc, dialogue, "Talking to the golem after opening the portal", speakers = speakers)
            state == THRONE_SEEN -> {
                runQuestSection(npc, dialogue, "Talking to the golem after entering the throne of the demon", speakers = speakers)
                setState(NEEDS_PROGRAM)
            }
            state in NEEDS_PROGRAM until COMPLETE ->
                runQuestSection(npc, dialogue, "Talking to the golem again after being told the demon must be defeated", speakers = speakers)
            else -> runQuestSection(npc, dialogue, "Talking to the Golem", speakers = speakers)
        }
    }

    private suspend fun ProtectedAccess.useClay(npc: Npc) {
        if (state != STARTED) {
            runQuestSection(npc, dialogue, "Before starting the quest", speakers = speakers)
            return
        }
        invDel(inv, GolemObjs.soft_clay, 1)
        val clay = varbit(GolemVarbits.clay) + 1
        setVarbit(GolemVarbits.clay, clay)
        val section =
            when (clay) {
                1 -> "After using the first soft clay"
                2 -> "After using the second soft clay"
                3 -> "After using the third soft clay"
                else -> "After using the fourth soft clay"
            }
        runQuestSection(npc, dialogue, section, speakers = speakers)
        if (clay >= CLAY_NEEDED) {
            setState(REPAIRED)
        }
    }

    /* Elissa, the notes and the museum */

    private fun ProtectedAccess.readLetter() {
        // Authored (not Wiki): the letter's text isn't in the transcript.
        mes("The letter is addressed to Elissa at the Digsite. It speaks of a golem left in Uzer.")
    }

    private suspend fun ProtectedAccess.talkElissa(npc: Npc) {
        when {
            state == REPAIRED ->
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Elissa in the Digsite",
                    SimpleQuestHooks(
                        options = mapOf("I found a letter in the desert with your name on." to { setState(ELISSA) }),
                    ),
                )
            state == ELISSA -> runQuestSection(npc, dialogue, "Talking to Elissa again after learning where the notes are")
            else -> runQuestSection(npc, dialogue, "Talking to Elissa after reading Varmen's notes")
        }
    }

    private fun ProtectedAccess.searchBookcase() {
        if (state < ELISSA || carries(GolemObjs.notes)) {
            mes("You search the bookcase but find nothing of interest.")
            return
        }
        if (invAdd(inv, GolemObjs.notes, 1).failure) {
            mes("You find some notes but have no room to take them.")
            return
        }
        mes("You find Varmen's notes.")
    }

    private fun ProtectedAccess.readNotes() {
        // Authored summary (not Wiki): the notes' full text isn't in the transcript.
        mes("Varmen writes of a clay statuette taken from Uzer to the Varrock Museum.")
        if (state == ELISSA) setState(NOTES)
    }

    private suspend fun ProtectedAccess.talkCurator(npc: Npc) {
        runQuestSection(npc, dialogue, "Talking to Curator Haig in the Varrock Museum")
    }

    /** Pickpocketing the curator during the quest gives the display cabinet key. */
    private inner class GolemClaims : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op3 &&
                (npc.id == GolemNpcs.curator.id || npc.visType.id == GolemNpcs.curator.id) &&
                player.questState(THE_GOLEM) == NOTES &&
                player.inv.none { it?.id == GolemObjs.case_key.id }

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            if (!statRandom(stats.thieving, THIEVING_LOW, THIEVING_HIGH, 0)) {
                mes("You fail to pick the curator's pocket.")
                return
            }
            invAdd(inv, GolemObjs.case_key, 1)
            runQuestSection(npc, dialogue, "Pickpocketing Curator Haig")
            statAdvance(stats.thieving, PICKPOCKET_XP * xpMods.get(player, stats.thieving))
        }
    }

    private suspend fun ProtectedAccess.openCase() {
        if (state != NOTES || retrieved()) {
            mes("There's nothing in this case you need.")
            return
        }
        if (inv.isFull()) {
            mes("You need a free inventory space.")
            return
        }
        invDel(inv, GolemObjs.case_key, 1)
        invAdd(inv, GolemObjs.statuette, 1)
        setVarbit(GolemVarbits.retrieved_statuette, 1)
        runQuestSection(null, dialogue, "Opening the display case")
    }

    /* Under Uzer: statues and the portal */

    private suspend fun ProtectedAccess.placeStatuette(index: Int) {
        if (varbit(GolemVarbits.statues[index]) != MISSING || index != EMPTY_ALCOVE) {
            runQuestSection(null, dialogue, "Using statuette on alcove")
            return
        }
        invDel(inv, GolemObjs.statuette, 1)
        setVarbit(GolemVarbits.statues[index], FACING_RIGHT)
        mes("You place the statuette in the alcove.")
    }

    private suspend fun ProtectedAccess.turnStatue(index: Int) {
        if (index < 0) return
        if (state >= PORTAL_OPEN) {
            runQuestSection(null, dialogue, "Trying to turn statuettes after opening the door")
            return
        }
        val current = varbit(GolemVarbits.statues[index])
        if (index == EMPTY_ALCOVE && current == MISSING) {
            mes("There's no statuette here to turn.")
            return
        }
        val next = if (index == EMPTY_ALCOVE) (if (current == FACING_LEFT_D) FACING_RIGHT_D else FACING_LEFT_D) else 1 - current
        setVarbit(GolemVarbits.statues[index], next)
        if (allLeft() && state == NOTES && retrieved()) {
            setState(PORTAL_OPEN)
            runQuestSection(null, dialogue, "Unlocking the door with statuettes")
        }
    }

    private fun ProtectedAccess.allLeft(): Boolean =
        (0 until 3).all { varbit(GolemVarbits.statues[it]) == FACING_LEFT_ABC } &&
            varbit(GolemVarbits.statues[EMPTY_ALCOVE]) == FACING_LEFT_D

    private suspend fun ProtectedAccess.enterPortal() {
        if (state < PORTAL_OPEN) {
            mes("The portal is sealed.")
            return
        }
        if (state == PORTAL_OPEN) {
            setState(THRONE_SEEN)
        }
        runQuestSection(null, dialogue, "Entering the portal")
        telejump(THRONE_ROOM)
    }

    /* The program */

    private suspend fun ProtectedAccess.grabFeather() {
        if (carries(GolemObjs.feather) || carries(GolemObjs.pen)) {
            runQuestSection(null, dialogue, "Taking the phoenix feather", SimpleQuestHooks(defaultCondition = { it.contains("already") }), speakers = mapOf("Desert Phoenix" to GolemNpcs.phoenix))
            return
        }
        val success = statRandom(stats.thieving, THIEVING_LOW, THIEVING_HIGH, 0)
        runQuestSection(
            null,
            dialogue,
            "Taking the phoenix feather",
            SimpleQuestHooks(conditions = mapOf("If the player succeeds:" to { success }, "If the player fails:" to { !success })),
        )
        if (success) invAdd(inv, GolemObjs.feather, 1)
    }

    private suspend fun ProtectedAccess.pickMushroom() {
        if (inv.isFull()) {
            mes("You need a free inventory space.")
            return
        }
        invAdd(inv, GolemObjs.mushroom, 1)
        runQuestSection(null, dialogue, "Picking a black mushroom")
    }

    private suspend fun ProtectedAccess.crushMushroom() {
        if (!carries(GolemObjs.vial)) {
            invDel(inv, GolemObjs.mushroom, 1)
            runQuestSection(null, dialogue, "Crushing the mushroom without having an empty vial")
            return
        }
        invDel(inv, GolemObjs.mushroom, 1)
        invDel(inv, GolemObjs.vial, 1)
        invAdd(inv, GolemObjs.ink, 1)
        runQuestSection(null, dialogue, "Crushing the mushroom with a pestle and mortar")
    }

    private suspend fun ProtectedAccess.makePen() {
        invDel(inv, GolemObjs.feather, 1)
        invDel(inv, GolemObjs.ink, 1)
        invAdd(inv, GolemObjs.pen, 1)
        runQuestSection(null, dialogue, "Using a phoenix feather on the black dye")
    }

    private suspend fun ProtectedAccess.writeProgram() {
        if (state < NEEDS_PROGRAM) {
            runQuestSection(null, dialogue, "Using the phoenix quill on the papyrus", SimpleQuestHooks(defaultCondition = { true }))
            return
        }
        invDel(inv, GolemObjs.papyrus, 1)
        invAdd(inv, GolemObjs.program, 1)
        runQuestSection(null, dialogue, "Using the phoenix quill on the papyrus")
    }

    private suspend fun ProtectedAccess.openHead(npc: Npc) {
        if (state < NEEDS_PROGRAM) {
            runQuestSection(npc, dialogue, "Using the strange implement on the Golem without the new instructions", speakers = speakers)
            return
        }
        setVarbit(GolemVarbits.head_open, 1)
        mes("You open the golem's head with the strange implement.")
    }

    private suspend fun ProtectedAccess.useProgram(npc: Npc) {
        if (varbit(GolemVarbits.head_open) == 0) {
            runQuestSection(npc, dialogue, "Using program on golem before using the strange implement", speakers = speakers)
            return
        }
        invDel(inv, GolemObjs.program, 1)
        runQuestSection(npc, dialogue, "After using the strange implement on the golem in Uzer and adding the new instructions", speakers = speakers)
        setVarbit(GolemVarbits.head_open, 0)
        statAdvance(stats.thieving, REWARD_XP * xpMods.get(player, stats.thieving))
        statAdvance(stats.crafting, REWARD_XP * xpMods.get(player, stats.crafting))
        completeQuest(THE_GOLEM)
    }

    companion object {
        private const val STARTED = 1
        private const val REPAIRED = 2
        private const val ELISSA = 3
        private const val NOTES = 4
        private const val PORTAL_OPEN = 5
        private const val THRONE_SEEN = 6
        private const val NEEDS_PROGRAM = 7
        private const val COMPLETE = 10
        private const val CLAY_NEEDED = 4
        private const val REWARD_XP = 1000.0
        private const val PICKPOCKET_XP = 0.0
        private const val THIEVING_LOW = 150
        private const val THIEVING_HIGH = 240

        // Statue varbits (cache multilocs): a-c 0 = facing right, 1 = facing left; d 0 = missing,
        // 1 = left, 2 = right. The empty alcove is d; the door opens with all facing left (authored).
        private const val EMPTY_ALCOVE = 3
        private const val MISSING = 0
        private const val FACING_RIGHT = 2
        private const val FACING_LEFT_ABC = 1
        private const val FACING_LEFT_D = 1
        private const val FACING_RIGHT_D = 2

        private val UZER = CoordGrid(3492, 3091, 0)
        private val UNDER_UZER = CoordGrid(2722, 4885, 0)
        private val THRONE_ROOM = CoordGrid(2719, 4885, 2)
        private val PORTAL_BACK = CoordGrid(2720, 4910, 0)

        val THE_GOLEM =
            QuestDef(
                name = "The Golem",
                varbit = { GolemVarbits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,000 Thieving XP", "1,000 Crafting XP", "Access to the Carpet rides to Uzer"),
                scrollObj = { GolemObjs.statuette },
                listRow = "quest_golem",
            )
    }
}

internal object GolemNpcs : NpcReferences() {
    val broken = find("golem_broken_golem")
    val partial = find("golem_partially_broken_golem")
    val fixed = find("golem_fixed_golem")
    /** The Uzer golem multinpc and its states (the base binds too, in case the vis lookup misses). */
    val golems = listOf(find("golem_golem"), broken, partial, fixed)
    val elissa = find("golem_elissa")
    val curator = find("curator")
    val phoenix = find("golem_phoenix")
}

internal object GolemLocs : LocReferences() {
    val bookcase = find("golem_bookcase")
    val mushrooms = find("golem_black_mushrooms")
    val stairs_top = find("golem_insidestairs_top")
    val stairs_base = find("golem_insidestairs_base")
    val door_locked = find("golem_demon_door")
    val door_open = find("golem_demon_door_always_open")
    val statue_children = listOf(find("golem_statuette_facing_right"), find("golem_statuette_facing_left"))
    val alcove_empty = find("golem_statuette_missing")
    val demon_portal = find("golem_demon_portal")
    val statues =
        listOf(find("golem_statuettea"), find("golem_statuetteb"), find("golem_statuettec"), find("golem_statuetted"))
    /** The 243 museum's upstairs display cases (the golem case itself isn't on the map). */
    val display_cases =
        listOf(
            find("vm_timeline_map_of_runescape"),
            find("vm_timeline_three_god_symbols"),
            find("vm_timeline_random_stuff"),
            find("vm_timeline_druid_stone_circle"),
            find("vm_timeline_world_map"),
            find("vm_timeline_star_chart"),
        )
}

internal object GolemObjs : ObjReferences() {
    val soft_clay = find("softclay")
    val letter = find("golem_letter")
    val notes = find("golem_notes")
    val case_key = find("golem_statuettekey")
    val statuette = find("golem_statuette")
    val implement = find("golem_golemkey")
    val mushroom = find("golem_mushroom")
    val feather = find("golem_phoenixfeather")
    val ink = find("golem_ink")
    val pen = find("golem_pen")
    val program = find("golem_program")
    val papyrus = find("papyrus")
    val vial = find("vial_empty")
    val pestle = find("pestle_and_mortar")
}

internal object GolemVarbits : VarBitReferences() {
    val quest = find("golem_a")
    val clay = find("golem_clay")
    val retrieved_statuette = find("golem_retrieved_statuette")
    val head_open = find("golem_head_open")
    val statues =
        listOf(
            find("golem_statuettestatusa"),
            find("golem_statuettestatusb"),
            find("golem_statuettestatusc"),
            find("golem_statuettestatusd"),
        )
}
