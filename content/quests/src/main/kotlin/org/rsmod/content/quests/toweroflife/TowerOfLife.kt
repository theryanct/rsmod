package org.rsmod.content.quests.toweroflife

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tower of Life (OSRS Wiki, Transcript:Tower of Life). Cache quest varbit `tol_prog`, end state
 * 18 (dbrow quest_toweroflife). The alchemists' multinpcs are hidden at 1/5/7/9/10/13/15, so the
 * stages here only rest on visible values:
 * - 2 Effigy sends you to Bonafido. 3 Bonafido wants a builder's outfit: hard hat ('Black-eye''s
 *   quiz), shirt (a beer for 'The Guns'), trousers (the plants around the tower), boots
 *   (pickpocket 'No fingers' after asking him). 4 passed Bonafido's builder test.
 * - 6 inside the tower (enter wearing the outfit): search the crates for materials and fix the
 *   pressure machine (floor 1), pipe machine (floor 2) and cage (top floor). 8 all three fixed.
 * - 11 told Effigy; climbing to the top floor shows the Homunculus' creation: 12. Effigy asks you
 *   to deal with it: 14. Straighten out its mind (seven answers one way, logic or magic): 16.
 *   Tell Effigy: the Homunculus scares the alchemists off, 17. Meet it in the basement: 18,
 *   1,000 Construction, 500 Crafting and 500 Thieving xp, 2 QP.
 * - Authored (not Wiki): the three machine puzzles and the Homunculus meter are menu-driven
 *   stand-ins for the cache interfaces (tol_pressure_machine / tol_pipe_machine / tol_cage_puzzle
 *   / tol_homonculus_overlay), using the Wiki solutions; crates hand out the materials you still
 *   need at random; builder lines outside the transcript; the plant with the trousers is random.
 * - Not built: Creature Creation (the Symbol of life altars in the basement).
 */
class TowerOfLife
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TowerOfLife::class.java, "tower_of_life") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Effigy" to TolNpcs.effigy,
            "Bonafido" to TolNpcs.bonafido,
            "Transmute" to TolNpcs.transmute,
            "Currency" to TolNpcs.currency,
            "Homunculus" to TolNpcs.homunculus_caged,
            "Black-eye" to TolNpcs.black_eye,
            "The Guns" to TolNpcs.the_guns,
            "Gummy" to TolNpcs.gummy,
            "No fingers" to TolNpcs.no_fingers,
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, TolNpcs.effigy, TOWER_OF_LIFE, wants = { true }) { talkEffigy(it) }
            register(npcTypes, TolNpcs.transmute, TOWER_OF_LIFE, wants = { true }) { talkTransmute(it) }
            register(npcTypes, TolNpcs.currency, TOWER_OF_LIFE, wants = { true }) { talkCurrency(it) }
            register(npcTypes, TolNpcs.bonafido, TOWER_OF_LIFE, wants = { true }) { talkBonafido(it) }
            register(npcTypes, TolNpcs.black_eye, TOWER_OF_LIFE, wants = { true }) { talkBlackEye(it) }
            register(npcTypes, TolNpcs.the_guns, TOWER_OF_LIFE, wants = { true }) { talkTheGuns(it) }
            register(npcTypes, TolNpcs.gummy, TOWER_OF_LIFE, wants = { true }) { talkGummy(it) }
            register(npcTypes, TolNpcs.no_fingers, TOWER_OF_LIFE, wants = { true }) { talkNoFingers(it) }
            register(npcTypes, TolNpcs.homunculus_caged, TOWER_OF_LIFE, wants = { true }) { talkCagedHomunculus(it) }
            register(npcTypes, TolNpcs.homunculus_free, TOWER_OF_LIFE, wants = { true }) { talkBasementHomunculus(it) }
        }
        onOpNpcU(TolNpcs.the_guns, objs.beer) { talkTheGuns(it.npc) }
        onLocOpLabel(locTypes, TolLocs.plant, "Search") { searchPlant() }
        onLocOpLabel(locTypes, TolLocs.door, "Open") { useDoor(it) }
        onLocOpLabel(locTypes, TolLocs.stairs_up, "Climb-up") { climb(1) }
        onLocOpLabel(locTypes, TolLocs.stairs_down, "Climb-down") { climb(-1) }
        // These ladder types are reused elsewhere (Meiyerditch); outside the tower they just
        // change level, as the generic ladders do.
        onLocOpLabel(locTypes, TolLocs.ladder_up, "Climb-up") { climbLadderUp() }
        onLocOpLabel(locTypes, TolLocs.ladder_down, "Climb-down") { climb(-1) }
        for (crate in TolLocs.crates) {
            onLocOpLabel(locTypes, crate, "Search") { searchCrate() }
        }
        onLocOpLabel(locTypes, TolLocs.pressure_machine, "Fix") { fixPressureMachine() }
        onLocOpLabel(locTypes, TolLocs.pipe_machine, "Fix") { fixPipeMachine() }
        onLocOpLabel(locTypes, TolLocs.cage, "Fix") { fixCage() }
        onLocOpLabel(locTypes, TolLocs.trapdoor, "Open") { openTrapdoor() }
        onLocOpLabel(locTypes, TolLocs.trapdoor_open, "Climb-down") { enterBasement() }
        onLocOpLabel(locTypes, TolLocs.trapdoor_open, "Close") { setVarbit(TolVarbits.trapdoor_open, 0) }
        OpInterceptors.register(TolClaims())
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TOWER_OF_LIFE)

    private fun ProtectedAccess.setState(value: Int) = player.setQuestState(TOWER_OF_LIFE, value)

    private fun ProtectedAccess.varbit(type: VarBitType): Int = vars[type]

    private fun ProtectedAccess.setVarbit(type: VarBitType, value: Int) =
        VarPlayerIntMapSetter.set(player, type, value)

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: QuestDialogueHooks = SimpleQuestHooks()) =
        runQuestSection(npc, dialogue, title, hooks, speakers)

    private fun ProtectedAccess.owns(obj: ObjType): Boolean = carries(obj) || invTotal(worn, obj) > 0

    private fun ProtectedAccess.wearingOutfit(): Boolean = TolObjs.outfit.all { invTotal(worn, it) > 0 }

    /* The alchemists */

    private suspend fun ProtectedAccess.talkEffigy(npc: Npc) {
        when (state) {
            0 ->
                if (statBase(stats.construction) < CONSTRUCTION_REQUIRED) {
                    // Authored (not Wiki): the dbrow's Construction 10 requirement.
                    authoredEffigy(npc, "You don't look like you know one end of a hammer from the other. Come back when you've some Construction experience.")
                    mes("You need level $CONSTRUCTION_REQUIRED Construction to start Tower of Life.")
                } else {
                    section(
                        npc,
                        "The builders on strike",
                        SimpleQuestHooks(options = mapOf("Yes." to { setState(STARTED) })),
                    )
                }
            STARTED,
            OUTFIT -> authoredEffigy(npc, "Have you spoken to Bonafido yet? He's the head builder, over by the tower.")
            BUILDER -> section(npc, "Talking to Effigy again")
            INSIDE -> section(npc, "Talking to Effigy after entering the Tower")
            FIXED -> {
                section(npc, "Returning to the alchemists")
                setState(TOLD_EFFIGY)
            }
            TOLD_EFFIGY -> authoredEffigy(npc, "To the top of the tower! Hurry, you'll miss it!")
            CREATED -> {
                section(npc, "Effigy")
                setState(SENT_TO_HOMUNCULUS)
            }
            SENT_TO_HOMUNCULUS -> section(npc, "Talking to Effigy again (2)")
            MIND_FIXED -> {
                section(npc, "Effigy (2)")
                setVarbit(TolVarbits.homunculus_present, 1)
                setState(SCARED)
            }
            else -> authoredEffigy(npc, "Please, just leave us be. We've learnt our lesson.")
        }
    }

    /** Authored (not Wiki): Effigy between transcript conversations. */
    private suspend fun ProtectedAccess.authoredEffigy(npc: Npc, text: String) =
        startDialogue(npc) { chatNpc(neutral, text) }

    private suspend fun ProtectedAccess.talkTransmute(npc: Npc) {
        val title =
            when (state) {
                in 0 until BUILDER -> "Talking to 'Transmute' The Alchemist before starting"
                BUILDER -> "Talking to 'Transmute' The Alchemist after starting"
                CREATED -> "'Transmute' the Alchemist"
                SENT_TO_HOMUNCULUS -> "Talking to 'Transmute' the Alchemist again"
                MIND_FIXED -> "'Transmute' the Alchemist (2)"
                else -> "Talking to 'Transmute' the Alchemist after entering the Tower"
            }
        section(npc, title)
    }

    private suspend fun ProtectedAccess.talkCurrency(npc: Npc) {
        val title =
            when (state) {
                in 0 until BUILDER -> "Talking to 'Currency' the Alchemist before starting"
                BUILDER -> "Talking to 'Currency' the Alchemist"
                CREATED -> "'Currency' the Alchemist"
                SENT_TO_HOMUNCULUS -> "Talking to 'Currency' the Alchemist again"
                MIND_FIXED -> "'Currency' the Alchemist (2)"
                else -> "Talking to 'Currency' the Alchemist after entering the Tower"
            }
        section(npc, title)
    }

    /* The builders */

    private suspend fun ProtectedAccess.talkBonafido(npc: Npc) {
        when (state) {
            0 -> authoredBuilder(npc, "We're on strike, mate. Take it up with the alchemists.")
            STARTED -> {
                section(npc, "Speaking with Bonafido, the head builder")
                setState(OUTFIT)
            }
            OUTFIT ->
                section(
                    npc,
                    "Talking to Bonafido again with the full outfit",
                    SimpleQuestHooks(
                        conditions = mapOf("If not wearing the full outfit:" to { !wearingOutfit() }),
                        options = mapOf("Carry on, it'll fix itself" to { setState(BUILDER) }),
                    ),
                )
            BUILDER -> section(npc, "Talking to Bonafido again")
            INSIDE,
            FIXED -> section(npc, "Talking to Bonafido after entering the Tower")
            TOLD_EFFIGY -> section(npc, "Talking to Bonafido")
            CREATED,
            SENT_TO_HOMUNCULUS -> section(npc, "Talking to Bonafido again (2)")
            else -> section(npc, "Talking to Bonafido again (3)")
        }
    }

    /** Authored (not Wiki): builders before the quest or with nothing left to say. */
    private suspend fun ProtectedAccess.authoredBuilder(npc: Npc, text: String) =
        startDialogue(npc) { chatNpc(neutral, text) }

    private suspend fun ProtectedAccess.talkBlackEye(npc: Npc) {
        when {
            state < OUTFIT -> authoredBuilder(npc, "Can't you see we're on a break?")
            owns(TolObjs.hard_hat) || state >= BUILDER -> section(npc, "Talking to 'Black-eye' again")
            else ->
                section(
                    npc,
                    "Obtaining Hard hat",
                    SimpleQuestHooks(options = mapOf("10 clay pieces" to { giveOutfitPiece(TolObjs.hard_hat) })),
                )
        }
    }

    private suspend fun ProtectedAccess.talkTheGuns(npc: Npc) {
        when {
            state < OUTFIT -> authoredBuilder(npc, "Hurrmph... on strike... urrgh.")
            owns(TolObjs.shirt) || state >= BUILDER ->
                if (carries(objs.beer)) {
                    section(
                        npc,
                        "Attempting to give other beverages",
                        SimpleQuestHooks(conditions = mapOf("If the player attempts to give a beer again:" to { true })),
                    )
                } else {
                    section(npc, "Talking to 'The Guns' again")
                }
            else -> {
                val hasBeer = carries(objs.beer)
                section(
                    npc,
                    "Obtaining Builder's shirt",
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have a beer:" to { !hasBeer },
                                "If the player has a beer in their inventory:" to { hasBeer },
                            ),
                    ),
                )
                if (hasBeer && carries(objs.beer)) {
                    invDel(inv, objs.beer, 1)
                    giveOutfitPiece(TolObjs.shirt)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.talkGummy(npc: Npc) {
        if (state < OUTFIT || state >= BUILDER || owns(TolObjs.trousers)) {
            authoredBuilder(npc, "Leave me be, I'm busy.")
            return
        }
        section(npc, "Obtaining Builder's trousers")
    }

    private suspend fun ProtectedAccess.talkNoFingers(npc: Npc) {
        when {
            state < OUTFIT -> authoredBuilder(npc, "No work today. We're on strike.")
            owns(TolObjs.boots) || state >= BUILDER -> section(npc, "Talking to 'No fingers' again")
            else -> {
                section(npc, "Obtaining Builder's boots")
                setVarbit(TolVarbits.no_fingers_asked, 1)
            }
        }
    }

    private fun ProtectedAccess.giveOutfitPiece(obj: ObjType) {
        if (invAdd(inv, obj, 1).failure) {
            mes("You don't have enough inventory space.")
        }
    }

    private suspend fun ProtectedAccess.searchPlant() {
        if (state != OUTFIT || owns(TolObjs.trousers)) {
            mes("You search the plant but find nothing of interest.")
            return
        }
        val found = random.randomBoolean(PLANT_CHANCE)
        if (!found) {
            // Authored (not Wiki): the transcript's empty "doesn't have the trousers" branch.
            mes("You search the plant but find nothing.")
            return
        }
        if (inv.isFull()) {
            mes("You find some trousers, but you don't have room to take them.")
            return
        }
        section(null, "Searching near the tower", SimpleQuestHooks(conditions = mapOf("If the plant has the trousers:" to { true })))
        invAdd(inv, TolObjs.trousers, 1)
    }

    /** Pickpocketing 'No fingers' once he has refused you gives his boots. */
    private inner class TolClaims : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op3 &&
                npc.id == TolNpcs.no_fingers.id &&
                player.questState(TOWER_OF_LIFE) == OUTFIT &&
                player.inv.none { it?.id == TolObjs.boots.id } &&
                player.worn.none { it?.id == TolObjs.boots.id }

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            if (varbit(TolVarbits.no_fingers_asked) == 0) {
                mes("You should talk to him first.")
                return
            }
            if (inv.isFull()) {
                mes("You don't have enough inventory space.")
                return
            }
            if (!statRandom(stats.thieving, THIEVING_LOW, THIEVING_HIGH, 0)) {
                mes("You fail to pick 'No fingers' pocket.")
                return
            }
            invAdd(inv, TolObjs.boots, 1)
            // Authored (not Wiki): the transcript's "Stealing the boots" section is empty.
            mes("You steal a pair of builder's boots.")
        }
    }

    /* Into the tower */

    private suspend fun ProtectedAccess.useDoor(door: BoundLocInfo) {
        val inside = player.coords.z < door.coords.z
        if (!inside && state < COMPLETE && (state < BUILDER || !wearingOutfit())) {
            // Authored (not Wiki).
            mes("Only builders are allowed in the tower.")
            return
        }
        passThroughWall(door)
        if (!inside && state == BUILDER) {
            setState(INSIDE)
            section(null, "Entering the Tower of Life")
        }
    }

    private suspend fun ProtectedAccess.climb(levels: Int) {
        anim(seqs.human_reachforladder)
        delay(1)
        telejump(player.coords.translateLevel(levels))
        if (player.coords.level == TOP_FLOOR && inTower() && state == TOLD_EFFIGY) {
            delay(1)
            section(null, "Going to the top of the tower")
            setState(CREATED)
        }
    }

    private fun ProtectedAccess.inTower(): Boolean =
        player.coords.x in TOWER_X && player.coords.z in TOWER_Z

    private suspend fun ProtectedAccess.climbLadderUp() {
        if (player.coords.z >= BASEMENT_MIN_Z && player.coords.x in BASEMENT_X) {
            anim(seqs.human_reachforladder)
            delay(1)
            telejump(TRAPDOOR_TOP)
            return
        }
        climb(1)
    }

    private fun ProtectedAccess.openTrapdoor() {
        if (state < SCARED) {
            // Authored (not Wiki).
            mes("The trapdoor won't budge.")
            return
        }
        setVarbit(TolVarbits.trapdoor_open, 1)
    }

    private suspend fun ProtectedAccess.enterBasement() {
        anim(seqs.human_reachforladder)
        delay(1)
        telejump(BASEMENT)
    }

    /* Materials and machines */

    private suspend fun ProtectedAccess.searchCrate() {
        val needed = TolObjs.materials.filter { (obj, count, done) -> !done(this) && invTotal(inv, obj) < count }
        val found = needed.isNotEmpty() && state >= INSIDE && !random.randomBoolean(CRATE_FAIL_CHANCE)
        section(
            null,
            "Searching the crates for materials",
            SimpleQuestHooks(
                conditions = mapOf("Failing to find materials:" to { !found }),
                actions =
                    mapOf(
                        "(Player performs the \"No\" emote)" to { anim(seqs.emote_no) },
                        "(Player performs the \"Yes\" emote)" to { if (found) anim(seqs.emote_yes) },
                    ),
            ),
        )
        if (!found) {
            mes("You find nothing useful.")
            return
        }
        val material = random.pick(needed)
        if (invAdd(inv, material.obj, 1).failure) {
            mes("You don't have enough inventory space.")
            return
        }
        mes("You find some ${material.label}.")
    }

    /**
     * Runs a machine's "Construct it?" section: [needs] are taken on Yes. Returns whether the
     * machine was built (and calibration should start).
     */
    private suspend fun ProtectedAccess.construct(title: String, needs: List<Material>, built: TolMachine): Boolean {
        val missing = needs.filter { invTotal(inv, it.obj) < it.count }
        var constructed = false
        section(
            null,
            title,
            object : QuestDialogueHooks {
                override fun condition(text: String): Boolean = text == "If missing materials:" && missing.isNotEmpty()

                override suspend fun Dialogue.onAction(tag: String) {
                    if (tag.startsWith("(The player builds")) {
                        for (need in needs) access.invDel(access.inv, need.obj, need.count)
                        built.markBuilt(access)
                        constructed = true
                    }
                }

                override fun rewrite(text: String): String =
                    text.replace("[materials]", missing.joinToString(", ") { "${it.count} ${it.label}" })
            },
        )
        return constructed
    }

    private suspend fun ProtectedAccess.fixPressureMachine() {
        if (state < INSIDE) return
        if (varbit(TolVarbits.pressure_prog) >= MACHINE_WORKING) {
            mes("The machine is working.")
            return
        }
        if (varbit(TolVarbits.pressure_prog) == 0 &&
            !construct("Fixing the pressure machine", TolObjs.pressureNeeds, TolMachine.Pressure)
        ) {
            return
        }
        if (calibratePressure()) machineWorking(TolVarbits.pressure_prog)
    }

    private suspend fun ProtectedAccess.fixPipeMachine() {
        if (state < INSIDE) return
        if (varbit(TolVarbits.pipe_prog) >= MACHINE_WORKING) {
            mes("The machine is working.")
            return
        }
        if (varbit(TolVarbits.pipe_prog) == 0 &&
            !construct("Fixing the pipe system", TolObjs.pipeNeeds, TolMachine.Pipe)
        ) {
            return
        }
        if (connectPipes()) machineWorking(TolVarbits.pipe_prog)
    }

    private suspend fun ProtectedAccess.fixCage() {
        if (state < INSIDE) return
        if (varbit(TolVarbits.cage_state) == 1) return
        if (varbit(TolVarbits.cage_prog) == 0 && varbit(TolVarbits.cage_built) == 0 &&
            !construct("Fixing the strange cage", TolObjs.cageNeeds, TolMachine.Cage)
        ) {
            return
        }
        if (!buildCageSides()) return
        setVarbit(TolVarbits.cage_state, 1)
        setVarbit(TolVarbits.cage_prog, MACHINE_WORKING)
        startDialogue { mesbox("The cage is complete!") }
        checkAllFixed()
    }

    private suspend fun ProtectedAccess.machineWorking(progress: VarBitType) {
        setVarbit(progress, MACHINE_WORKING)
        startDialogue { mesbox("The machine is working!") }
        checkAllFixed()
    }

    private suspend fun ProtectedAccess.checkAllFixed() {
        val done =
            varbit(TolVarbits.pressure_prog) >= MACHINE_WORKING &&
                varbit(TolVarbits.pipe_prog) >= MACHINE_WORKING &&
                varbit(TolVarbits.cage_state) == 1
        if (done && state == INSIDE) {
            setState(FIXED)
            startDialogue { mesbox("The tower should be in working order now! Best go and tell Effigy!") }
        }
    }

    /**
     * Authored (not Wiki) stand-in for the pressure machine interface. Each pipe's hole is
     * blocked by its valve at the Wiki position with the levers set as the Wiki solution has them
     * (left lever down for pipes 2-4, right lever down for pipe 4); then turning it right fills it.
     */
    private suspend fun ProtectedAccess.calibratePressure(): Boolean {
        val valves = IntArray(PIPES)
        var leftDown = false
        var rightDown = false
        var solved = false
        startDialogue {
            while (!solved) {
                val action =
                    choice5(
                        "Turn a valve left", 0, "Turn a valve right", 1,
                        leverLabel(leftDown, "left"), 2,
                        leverLabel(rightDown, "right"), 3,
                        "Leave the machine", 4,
                        title = pressureStatus(valves, leftDown, rightDown),
                    )
                when (action) {
                    0, 1 -> {
                        val pipe = choice4("Valve 1", 0, "Valve 2", 1, "Valve 3", 2, "Valve 4", 3, title = "Which valve?")
                        turnValve(pipe, if (action == 1) 1 else -1, valves, leftDown, rightDown)
                    }
                    2 -> leftDown = !leftDown
                    3 -> rightDown = !rightDown
                    else -> return@startDialogue
                }
                solved = (0 until PIPES).all { access.vars[TolVarbits.pressure_levels[it]] >= FULL }
            }
        }
        return solved
    }

    private fun leverLabel(down: Boolean, side: String): String =
        if (down) "Lift the $side lever" else "Pull down the $side lever"

    private fun leverState(down: Boolean): String = if (down) "down" else "up"

    private fun Dialogue.pressureStatus(valves: IntArray, leftDown: Boolean, rightDown: Boolean): String {
        val pipes =
            (0 until PIPES).joinToString(" ") {
                val level = access.vars[TolVarbits.pressure_levels[it]]
                when {
                    level >= FULL -> "${it + 1}:full"
                    access.vars[TolVarbits.pressure_solved[it]] == 1 -> "${it + 1}:filling"
                    else -> "${it + 1}:${valves[it]}"
                }
            }
        return "Pipes $pipes | levers ${leverState(leftDown)}/${leverState(rightDown)}"
    }

    private fun Dialogue.turnValve(pipe: Int, step: Int, valves: IntArray, leftDown: Boolean, rightDown: Boolean) {
        val solvedVar = TolVarbits.pressure_solved[pipe]
        val levelVar = TolVarbits.pressure_levels[pipe]
        if (access.vars[solvedVar] == 1) {
            val level = (access.vars[levelVar] + step).coerceIn(0, FULL)
            VarPlayerIntMapSetter.set(player, levelVar, level)
            access.mes(if (level >= FULL) "Pipe ${pipe + 1} is full of water." else "The water level in pipe ${pipe + 1} changes.")
            return
        }
        valves[pipe] = (valves[pipe] + step).coerceIn(-VALVE_RANGE, VALVE_RANGE)
        val target = PRESSURE_SOLUTION[pipe]
        if (valves[pipe] == target.position && leftDown == target.leftDown && rightDown == target.rightDown) {
            VarPlayerIntMapSetter.set(player, solvedVar, 1)
            access.mes("Pipe ${pipe + 1}: The leak is blocked!")
        } else {
            access.mes("Water sprays from the hole in pipe ${pipe + 1}.")
        }
    }

    /**
     * Authored (not Wiki) stand-in for the pipe machine interface: fit the five pipe pieces in
     * order, then turn the last one until it lines up with the outlet.
     */
    private suspend fun ProtectedAccess.connectPipes(): Boolean {
        var connected = false
        startDialogue {
            while (!connected) {
                val fitted = TolVarbits.pipe_pieces.count { access.vars[it] == 1 }
                val rotation = access.vars[TolVarbits.pipe5_rot]
                val action =
                    choice3(
                        "Fit the next pipe piece", 0, "Turn the end piece", 1, "Leave the machine", 2,
                        title = "Pieces fitted: $fitted/${TolVarbits.pipe_pieces.size}, end piece turned $rotation",
                    )
                when (action) {
                    0 ->
                        if (fitted < TolVarbits.pipe_pieces.size) {
                            VarPlayerIntMapSetter.set(player, TolVarbits.pipe_pieces[fitted], 1)
                            access.mes("You fit pipe piece ${fitted + 1}.")
                        } else {
                            access.mes("All the pieces are fitted.")
                        }
                    1 -> {
                        val next = (rotation + 1) % PIPE_ROTATIONS
                        VarPlayerIntMapSetter.set(player, TolVarbits.pipe5_rot, next)
                        access.mes("You turn the end piece.")
                    }
                    else -> return@startDialogue
                }
                connected =
                    TolVarbits.pipe_pieces.all { access.vars[it] == 1 } &&
                        access.vars[TolVarbits.pipe5_rot] == PIPE_SOLVED_ROTATION
            }
        }
        return connected
    }

    /**
     * Authored (not Wiki) stand-in for the cage puzzle interface: each of the four sides takes
     * three bars of the Wiki's orientations and sizes (in any order).
     */
    private suspend fun ProtectedAccess.buildCageSides(): Boolean {
        var complete = false
        startDialogue {
            while (true) {
                val side = access.vars[TolVarbits.cage_prog]
                if (side >= CAGE_SOLUTION.size) {
                    complete = true
                    return@startDialogue
                }
                val bars = ArrayList<Bar>()
                while (bars.size < BARS_PER_SIDE) {
                    val vertical =
                        choice3(
                            "Horizontal bar", 0, "Vertical bar", 1, "Leave the cage", 2,
                            title = "Side ${side + 1}, bar ${bars.size + 1} of $BARS_PER_SIDE",
                        )
                    if (vertical == 2) return@startDialogue
                    val size = choice3("Size 2", 2, "Size 3", 3, "Size 4", 4, title = "Which size?")
                    bars += Bar(vertical == 1, size)
                }
                if (bars.sortedWith(BAR_ORDER) == CAGE_SOLUTION[side].sortedWith(BAR_ORDER)) {
                    // The last side's completion comes from the caller.
                    if (side + 1 < CAGE_SOLUTION.size) {
                        VarPlayerIntMapSetter.set(player, TolVarbits.cage_prog, side + 1)
                    } else {
                        complete = true
                        return@startDialogue
                    }
                    mesbox("You fix the part of the cage!")
                } else {
                    mesbox("The bars don't fit together. You take them back out.")
                }
            }
        }
        return complete
    }

    /* The Homunculus */

    private suspend fun ProtectedAccess.talkCagedHomunculus(npc: Npc) {
        when (state) {
            CREATED -> section(npc, "Leaving and talking to the Homunculus before helping it")
            SENT_TO_HOMUNCULUS -> {
                var fixed = false
                var magic = false
                section(
                    npc,
                    "Returning to it",
                    object : QuestDialogueHooks {
                        override fun condition(text: String): Boolean = text == "When the Homunculus' mind is fixed:" && fixed

                        override suspend fun Dialogue.onAction(tag: String) {
                            if (tag.startsWith("Choosing any one of the three options")) {
                                val result = homunculusQuestions()
                                fixed = result != 0
                                magic = result > 0
                            }
                        }

                        override fun rewrite(text: String): String =
                            if (!magic) text.replace("root for magic", "root for logic") else text
                    },
                )
                if (fixed) setState(MIND_FIXED)
            }
            MIND_FIXED -> section(npc, "Talking to the Homunculus again")
            else -> authoredHomunculus(npc, "Me rest now.")
        }
    }

    /** Authored (not Wiki): the Homunculus outside the transcript's conversations. */
    private suspend fun ProtectedAccess.authoredHomunculus(npc: Npc, text: String) =
        startDialogue(npc) { chatNpc(neutral, text) }

    /**
     * The question sets loop in order; magic answers push the meter one way, logic the other,
     * and a neutral answer drifts it back toward the middle. Returns +1 (magic) or -1 (logic)
     * once the meter is full; the Wiki answers fill it in seven.
     */
    private suspend fun Dialogue.homunculusQuestions(): Int {
        var meter = 0
        var set = 0
        while (meter > -METER_FULL && meter < METER_FULL) {
            val question = HOMUNCULUS_SETS[set % HOMUNCULUS_SETS.size]
            val picked =
                choice3(
                    question.options[0].first, 0, question.options[1].first, 1, question.options[2].first, 2,
                    title = question.question,
                )
            meter =
                when (question.options[picked].second) {
                    Lean.Magic -> meter + 1
                    Lean.Logic -> meter - 1
                    Lean.Neutral -> meter - Integer.signum(meter)
                }
            access.mes("The Homunculus' mind leans ${meterText(meter)}.")
            set++
        }
        return Integer.signum(meter)
    }

    private fun meterText(meter: Int): String =
        when {
            meter > 0 -> "towards magic ($meter/$METER_FULL)"
            meter < 0 -> "towards logic (${-meter}/$METER_FULL)"
            else -> "nowhere in particular"
        }

    private suspend fun ProtectedAccess.talkBasementHomunculus(npc: Npc) {
        if (state < SCARED) {
            authoredHomunculus(npc, "Leeet mwwe free.")
            return
        }
        section(npc, "Talking to the Homunculus in the basement")
        if (state == SCARED) {
            statAdvance(stats.construction, CONSTRUCTION_XP * xpMods.get(player, stats.construction))
            statAdvance(stats.crafting, OTHER_XP * xpMods.get(player, stats.crafting))
            statAdvance(stats.thieving, OTHER_XP * xpMods.get(player, stats.thieving))
            completeQuest(TOWER_OF_LIFE)
        }
    }

    companion object {
        private const val STARTED = 2
        private const val OUTFIT = 3
        private const val BUILDER = 4
        private const val INSIDE = 6
        private const val FIXED = 8
        private const val TOLD_EFFIGY = 11
        private const val CREATED = 12
        private const val SENT_TO_HOMUNCULUS = 14
        private const val MIND_FIXED = 16
        private const val SCARED = 17
        private const val COMPLETE = 18

        private const val CONSTRUCTION_REQUIRED = 10
        private const val CONSTRUCTION_XP = 1000.0
        private const val OTHER_XP = 500.0
        private const val THIEVING_LOW = 150
        private const val THIEVING_HIGH = 240
        private const val PLANT_CHANCE = 4
        private const val CRATE_FAIL_CHANCE = 4

        internal const val MACHINE_WORKING = 2
        private const val PIPES = 4
        private const val FULL = 3
        private const val VALVE_RANGE = 3
        private const val PIPE_ROTATIONS = 4
        private const val PIPE_SOLVED_ROTATION = 2
        private const val BARS_PER_SIDE = 3
        private const val METER_FULL = 7
        private const val TOP_FLOOR = 3

        private class Valve(val position: Int, val leftDown: Boolean, val rightDown: Boolean)

        /** Wiki solution: pipe 1 left x2; 2 left lever, left x2; 3 left lever, right 1; 4 both levers, right 2. */
        private val PRESSURE_SOLUTION =
            listOf(Valve(-2, false, false), Valve(-2, true, false), Valve(1, true, false), Valve(2, true, true))

        private data class Bar(val vertical: Boolean, val size: Int)

        private val BAR_ORDER = compareBy<Bar>({ it.vertical }, { it.size })

        private fun h(size: Int) = Bar(false, size)

        private fun v(size: Int) = Bar(true, size)

        private val CAGE_SOLUTION =
            listOf(
                listOf(h(2), h(3), v(2)),
                listOf(h(2), v(2), v(4)),
                listOf(h(4), v(2), v(3)),
                listOf(h(2), h(2), v(2)),
            )

        private enum class Lean {
            Magic,
            Logic,
            Neutral,
        }

        private class QuestionSet(val question: String, val options: List<Pair<String, Lean>>)

        private fun set(q: String, a: Pair<String, Lean>, b: Pair<String, Lean>, c: Pair<String, Lean>) =
            QuestionSet(q, listOf(a, b, c))

        /** The transcript's sets (it has no third); leanings read from the answers' wording. */
        private val HOMUNCULUS_SETS =
            listOf(
                set(
                    "How can I make fire appear at my fingertips?",
                    "Get some logs and a tinderbox." to Lean.Logic,
                    "With the aid of 5 fire runes." to Lean.Magic,
                    "That's impossible! No one can do that!" to Lean.Neutral,
                ),
                set(
                    "How do dragons breathe fire?",
                    "Not too sure, I've never seen it happen." to Lean.Neutral,
                    "With the help of the magical dragonstones!" to Lean.Magic,
                    "By ignition of gas in their belly as they exhale." to Lean.Logic,
                ),
                set(
                    "What do people do, day-in, day-out?",
                    "Runecraft, enchant jewellery, perform alchemy." to Lean.Magic,
                    "Eat, sleep, nothing that exciting." to Lean.Neutral,
                    "Fletching, Crafting, Smithing." to Lean.Logic,
                ),
                set(
                    "What could I do with unwanted bones?",
                    "Bury them." to Lean.Logic,
                    "I'd like to think you wouldn't be carrying bones around." to Lean.Neutral,
                    "Turn them into bananas or peaches!" to Lean.Magic,
                ),
                set(
                    "What's the best way to travel?",
                    "I'm not really much of a traveller, sorry." to Lean.Neutral,
                    "Run, run as fast as you can." to Lean.Logic,
                    "Depends where you are headed, but teleport spells are a safe bet." to Lean.Magic,
                ),
                set(
                    "I hear people whisper of potions...",
                    "Yes, you can make magic potions to boost your skills." to Lean.Magic,
                    "People mix together ingredients in vials. The nutrients will help you." to Lean.Logic,
                    "Yes, liquid-filled vials. Big deal." to Lean.Neutral,
                ),
                set(
                    "How are runes created?",
                    "By harnessing the power of the gods!" to Lean.Magic,
                    "Never seen one personally." to Lean.Neutral,
                    "Take some essence to an altar and use a talisman." to Lean.Logic,
                ),
                set(
                    "Can people move things with the power of the mind?",
                    "Perhaps. I've never seen it myself, though." to Lean.Neutral,
                    "Yep, you can use the Telekinetic Grab spell." to Lean.Magic,
                    "Sure. Use your brain to tell someone to move it!" to Lean.Logic,
                ),
                set(
                    "How can I possibly exist?",
                    "Through the power of alchemy." to Lean.Magic,
                    "It's beyond me!" to Lean.Neutral,
                    "It's a simple case of combining materials." to Lean.Logic,
                ),
                set(
                    "Explain those flashing sparks that scared the alchemists.",
                    "You have special powers - no surprise seeing how you were created." to Lean.Magic,
                    "Coincidence - there is a lot of loose metal around." to Lean.Logic,
                    "Yeah, they were cool! Nice one." to Lean.Neutral,
                ),
                set(
                    "I want to learn more - what should I study?",
                    "Try some Mining followed by Smithing." to Lean.Logic,
                    "How about Magic and Runecrafting?" to Lean.Magic,
                    "That's up to you; depends on what you find interesting." to Lean.Neutral,
                ),
                set(
                    "If I can reach out far enough I can grab the sun.",
                    "Don't be silly! You'd get burnt!" to Lean.Neutral,
                    "Can't see why not, anything is possible." to Lean.Magic,
                    "Well the sun is not actually there, it's where it used to be!" to Lean.Logic,
                ),
                set(
                    "Am I a creature of magic or a creature of logic?",
                    "Everything has a reason, even if you don't know what it is." to Lean.Logic,
                    "Probably a bit of both." to Lean.Neutral,
                    "Your very existense speaks of mystical forces." to Lean.Magic,
                ),
                set(
                    "Which do you believe in?",
                    "Magic." to Lean.Magic,
                    "I'm too laid back to really care, mate." to Lean.Neutral,
                    "Logic." to Lean.Logic,
                ),
            )

        private val TOWER_X = 2642..2656
        private val TOWER_Z = 3211..3225
        private val BASEMENT_X = 3000..3080
        private const val BASEMENT_MIN_Z = 4340
        private val TRAPDOOR_TOP = CoordGrid(2649, 3213, 0)
        private val BASEMENT = CoordGrid(3038, 4376, 0)

        val TOWER_OF_LIFE =
            QuestDef(
                name = "Tower of Life",
                varbit = { TolVarbits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "1,000 Construction XP",
                        "500 Crafting XP",
                        "500 Thieving XP",
                        "The builder's outfit",
                    ),
                scrollObj = { TolObjs.hard_hat },
                listRow = "quest_toweroflife",
            )
    }
}

/** A machine whose construction is tracked in a progress varbit (or the cage's built flag). */
internal enum class TolMachine {
    Pressure,
    Pipe,
    Cage;

    fun markBuilt(access: ProtectedAccess) {
        val progress =
            when (this) {
                Pressure -> TolVarbits.pressure_prog
                Pipe -> TolVarbits.pipe_prog
                Cage -> TolVarbits.cage_built
            }
        VarPlayerIntMapSetter.set(access.player, progress, 1)
    }
}

internal class Material(val obj: ObjType, val count: Int, val label: String, val done: ProtectedAccess.() -> Boolean) {
    operator fun component1() = obj

    operator fun component2() = count

    operator fun component3() = done
}

internal object TolNpcs : NpcReferences() {
    val effigy = find("tol_npc_efergy01")
    val transmute = find("tol_npc_mage01")
    val currency = find("tol_npc_mage02")
    val bonafido = find("tol_npc_barry01")
    val black_eye = find("tol_npc_builder01")
    val no_fingers = find("tol_npc_builder02")
    val gummy = find("tol_npc_builder03")
    val the_guns = find("tol_npc_builder04")
    val homunculus_caged = find("tol_homonculus_cage_broken")
    val homunculus_free = find("tol_homonculus_nocage")
}

internal object TolLocs : LocReferences() {
    val door = find("tol_tower_wall_door")
    val stairs_up = find("tol_stairs01")
    val stairs_down = find("tol_gapfill01")
    val ladder_up = find("area_sanguine_ghetto_ladder_up")
    val ladder_down = find("area_sanguine_ghetto_ladder_down")
    val plant = find("tol_plant4")
    val crates =
        listOf(
            find("tol_crate01"),
            find("tol_crate02"),
            find("tol_crate03"),
            find("tol_crate04"),
            find("tol_crate05"),
            find("tol_crate06"),
            find("tol_crate07"),
            find("tol_crate08"),
            find("tol_crate09"),
            find("tol_crate10"),
        )
    val pressure_machine = find("tol_pressure_machine01")
    /** Visible child of tol_pipe_machine_multi (tol_pipe_prog 0-1). */
    val pipe_machine = find("tol_pipe_machine01")
    /** Visible child of tol_cage_multi (tol_cage_state 0). */
    val cage = find("tol_cage_broken")
    /** Children of tol_trapdoor_multi (tol_trapdoor_open). */
    val trapdoor = find("tol_trapdoor01")
    val trapdoor_open = find("tol_trapdoor_open")
}

internal object TolObjs : ObjReferences() {
    val hard_hat = find("tol_player_construction_hardhat")
    val shirt = find("tol_player_construction_shirt")
    val trousers = find("tol_player_construction_trousers")
    val boots = find("tol_player_construction_boots")
    val outfit = listOf(hard_hat, shirt, trousers, boots)
    val wheel = find("tol_wheel")
    val metal_sheet = find("tol_metal_sheet")
    val ball = find("tol_pressure_ball")
    val pipe = find("tol_pipe")
    val ring = find("tol_ring")
    val rivets = find("tol_rivets")
    val bar = find("tol_bar")
    val glue = find("tol_glue")

    private val pressureDone: ProtectedAccess.() -> Boolean = { vars[TolVarbits.pressure_prog] > 0 }
    private val pipeDone: ProtectedAccess.() -> Boolean = { vars[TolVarbits.pipe_prog] > 0 }
    private val cageDone: ProtectedAccess.() -> Boolean = {
        vars[TolVarbits.cage_built] > 0 || vars[TolVarbits.cage_state] > 0
    }

    val pressureNeeds by lazy {
        listOf(
            Material(ball, 4, "coloured balls", pressureDone),
            Material(metal_sheet, 3, "metal sheets", pressureDone),
            Material(wheel, 4, "valve wheels", pressureDone),
        )
    }
    val pipeNeeds by lazy {
        listOf(
            Material(rivets, 6, "rivets", pipeDone),
            Material(pipe, 4, "pipes", pipeDone),
            Material(ring, 5, "pipe rings", pipeDone),
        )
    }
    val cageNeeds by lazy {
        listOf(Material(bar, 5, "metal bars", cageDone), Material(glue, 4, "bottles of binding fluid", cageDone))
    }
    val materials by lazy { pressureNeeds + pipeNeeds + cageNeeds }
}

internal object TolVarbits : VarBitReferences() {
    val quest = find("tol_prog")
    val pressure_prog = find("tol_pres_prog")
    val pipe_prog = find("tol_pipe_prog")
    val cage_prog = find("tol_cage_prog")
    val cage_state = find("tol_cage_state")
    /** The cage was constructed (its sides still need bars); cache bit reused for it. */
    val cage_built = find("tol_examined_cage_mach")
    val pipe_pieces =
        listOf(
            find("tol_pipe_piece1_active"),
            find("tol_pipe_piece2_active"),
            find("tol_pipe_piece3_active"),
            find("tol_pipe_piece4_active"),
            find("tol_pipe_piece5_active"),
        )
    val pipe5_rot = find("tol_pipe5_rot")
    val pressure_solved =
        listOf(find("tol_pres_solved1"), find("tol_pres_solved2"), find("tol_pres_solved3"), find("tol_pres_solved4"))
    val pressure_levels =
        listOf(find("tol_pres1_level"), find("tol_pres2_level"), find("tol_pres3_level"), find("tol_pres4_level"))
    val homunculus_present = find("tol_homonc_pres")
    val trapdoor_open = find("tol_trapdoor_open")
    val no_fingers_asked = find("tol_nofingers_asked")
}
