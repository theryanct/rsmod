package org.rsmod.content.quests.mountaindaughter

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onApLoc1
import org.rsmod.api.script.onApLoc3
import org.rsmod.api.script.onApLocU
import org.rsmod.api.script.onApNpc2
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mountain Daughter (OSRS Wiki). All state lives in the cache varp `mdaughter_var` (423) through
 * its cache varbits, which the client also reads (`mdaughter_burial_state` shows the mound/cairn,
 * `mdaughter_bear_multi_state` hides the talking Kendal, the quest varbit 50-69 shows the spare
 * Bearhead in the lair). Quest varbit `mdaughter_quest_var`, cache db end state 70; stages are
 * ours:
 * - 10 Hamal lets you search (rope down from the boulder to enter the camp: 10 Agility; mud on the
 *   tall tree, climb it (20 Agility), pole-vault the clump of rocks, plank across the flat stones).
 * - 20 Asleif's voice at the centre pool asks for peace with Rellekka and a new food source:
 *   `mdaughter_relations_var` 1 Hamal names Svidi, 2 Svidi wants a guarantee, 3 Brundt wants the
 *   ancient rock, 5 half a rock mined, 6 Brundt's guarantee, 7 given to Svidi, 8 Hamal told;
 *   `mdaughter_food_var` 1 Hamal asked, 2 Jokul's White Pearl tip, 3 the seed handed to Hamal
 *   (pick the fruit at White Wolf Mountain wearing gloves, eat it for the seed).
 * - 30 both tasks done; 40 Asleif asks you to convince her father (the Kendal's lair opens).
 * - 50 the Kendal killed (Bearhead); 60 Hamal saw the corpse; Ragnar gives Asleif's necklace; bury
 *   both on the centre island (burial state 1), 5 muddy rocks build the cairn (2): 70, 2 QP, 2,000
 *   Prayer and 1,000 Attack XP. The rockslide by the guard can be climbed both ways once Hamal has
 *   sanctioned you (the Wiki's camp access reward).
 *
 * Simplified: The Fremennik Trials isn't built, so every Fremennik-name branch takes the outerlander
 * one; the Kendal fight is plain NPC melee (his cache stats, Wiki: melee only); the White Pearl's
 * eat message is the food module's (food.tsv leaves the seed).
 */
class MountainDaughter
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locRepo: LocRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MountainDaughter::class.java, "mountain_daughter") }

    private val guardSpeakers: Map<String, NpcType> by lazy { mapOf("Guard" to MdNpcs.rockslide_guard) }
    private val rockGuardSpeakers: Map<String, NpcType> by lazy { mapOf("Guard" to MdNpcs.rock_guard) }
    private val kendalSpeakers: Map<String, NpcType> by lazy { mapOf("The Kendal" to MdNpcs.bearman) }

    /** The Kendal fighter spawned for each player (single player, but keyed for safety). */
    private val fighters = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, MdNpcs.hamal, MOUNTAIN_DAUGHTER, wants = { true }) { talkHamal(it) }
            register(npcTypes, MdNpcs.jokul, MOUNTAIN_DAUGHTER, wants = { true }) { talkJokul(it) }
            register(npcTypes, MdNpcs.ragnar, MOUNTAIN_DAUGHTER, wants = { true }) { talkRagnar(it) }
            register(npcTypes, MdNpcs.svidi, MOUNTAIN_DAUGHTER, wants = { true }) { talkSvidi(it) }
            register(npcTypes, MdNpcs.rockslide_guard, MOUNTAIN_DAUGHTER, wants = { true }) {
                runQuestSection(it, dialogue, "Guard by the entrance")
            }
            register(npcTypes, MdNpcs.rock_guard, MOUNTAIN_DAUGHTER, wants = { true }) { talkRockGuard(it) }
            for ((index, dweller) in MdNpcs.dwellers.withIndex()) {
                register(npcTypes, dweller, MOUNTAIN_DAUGHTER, wants = { true }) { talkDweller(it, index) }
            }
            register(npcTypes, MdNpcs.brundt, MOUNTAIN_DAUGHTER, wants = { wantsBrundt() }) { talkBrundt(it) }
            for (bear in MdNpcs.bears) {
                register(npcTypes, bear, MOUNTAIN_DAUGHTER, wants = { true }) { talkKendal(it) }
            }
        }
        for (bear in MdNpcs.bears) {
            onOpNpc2(bear) { attackKendal(it.npc) }
            onApNpc2(bear) { attackKendal(it.npc) }
        }
        onEvent<NpcKilledEvent> { kendalKilled(this) }

        // Entering and leaving the camp.
        onOpLocU(MdLocs.boulder) { ropeOnBoulder(it.objType) }
        onLocOpLabel(locTypes, MdLocs.boulder, "Push") {
            mes("The boulder won't budge. Perhaps you could tie something to it.") // Authored (not Wiki).
        }
        onLocOpLabel(locTypes, MdLocs.rockslide, "Climb-over") { climbRockslide() }
        for (door in MdLocs.tent_doors) onLocOpLabel(locTypes, door, "Open") { passThroughWall(it) }
        for (door in MdLocs.rock_tent_doors) onLocOpLabel(locTypes, door, "Go-through") { rockTentDoor(it) }

        // The shining pool.
        for (mud in MdLocs.mud) onLocOpLabel(locTypes, mud, "Dig-up") { digMud() }
        for (roots in MdLocs.roots) onLocOpLabel(locTypes, roots, "Dig-below") { digMud() }
        onOpLocU(MdLocs.lake_tree) { onTree(it.objType) }
        onLocOpLabel(locTypes, MdLocs.lake_tree, "Climb") { climbTree() }
        onOpLocU(MdLocs.pole_rocks) { onPoleRocks(it.objType) }
        onLocOpLabel(locTypes, MdLocs.pole_rocks, "Jump-across") { fallIn() }
        for (stone in MdLocs.flat_stones) {
            onOpLocU(stone) { onFlatStone(it.objType) }
            onLocOpLabel(locTypes, stone, "Jump-across") { fallIn() }
        }
        // The island obstacles stand in the water with no walkable tile beside them (the clump of
        // rocks is two tiles from either island, the tree's far end two from the first island):
        // they work from a short distance (ap) as well.
        onApLoc3(MdLocs.lake_tree) {
            val reach = if (player.coords.inBox(ISLAND_A)) ISLAND_REACH else 1
            if (isWithinApRange(it.loc, reach)) climbTree()
        }
        onApLocU(MdLocs.pole_rocks) { if (isWithinApRange(it.loc, ISLAND_REACH)) onPoleRocks(it.objType) }
        onApLoc1(MdLocs.pole_rocks) { if (isWithinApRange(it.loc, ISLAND_REACH)) fallIn() }
        for (stone in MdLocs.flat_stones) {
            onApLocU(stone) { if (isWithinApRange(it.loc, ISLAND_REACH)) onFlatStone(it.objType) }
            onApLoc1(stone) { if (isWithinApRange(it.loc, ISLAND_REACH)) fallIn() }
        }
        onLocOpLabel(locTypes, MdLocs.pool_gas, "Listen-to") { listenPool() }
        onLocOpLabel(locTypes, MdLocs.pool_water, "Listen-to") { listenPool() }

        // Making peace and finding food.
        onOpLocU(MdLocs.ancient_rock) { onAncientRock(it.loc, it.objType) }
        onLocOpLabel(locTypes, MdLocs.pearl_bush, "Pick-from") { pickPearl() }
        onOpObj3(MdObjs.pearl_seed) { takeSeed(it.obj) }

        // The Kendal.
        for (tree in MdLocs.blocking_trees) {
            onLocOpLabel(locTypes, tree, "Chop down") {
                mes("This tree is far too sturdy to chop down. Look for a weaker one.") // Authored (not Wiki).
            }
        }
        onLocOpLabel(locTypes, MdLocs.cave_entrance, "Enter") { enterCave() }
        for (exit in MdLocs.cave_exits) onLocOpLabel(locTypes, exit, "Exit") { leaveCave() }
        onOpObj3(MdObjs.corpse) { takeCorpse() }
        onLocOpLabel(locTypes, MdLocs.bear_head, "Take") { takeSpareBearhead() }

        // Laying Asleif to rest.
        onHeldOpLabel(objTypes, MdObjs.corpse, "Bury") { buryCorpse() }
        onOpLocU(MdLocs.burial_mound) { onMound(it.objType) }
    }

    /* State */

    private val ProtectedAccess.state: Int
        get() = player.questState(MOUNTAIN_DAUGHTER)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(MOUNTAIN_DAUGHTER, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private val ProtectedAccess.relations: Int
        get() = bit(MdVarBits.relations)

    private val ProtectedAccess.food: Int
        get() = bit(MdVarBits.food)

    private fun ProtectedAccess.raiseRelations(value: Int) {
        if (relations < value) setBit(MdVarBits.relations, value)
    }

    private fun ProtectedAccess.raiseFood(value: Int) {
        if (food < value) setBit(MdVarBits.food, value)
    }

    private fun ProtectedAccess.owns(obj: ObjType): Boolean =
        carries(obj) || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

    /* Hamal */

    private suspend fun ProtectedAccess.talkHamal(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val hooks = hamalStartHooks { start = true }
                runQuestSection(npc, dialogue, "Hamal's Daughter", hooks)
                if (start && state == 0) setState(STARTED)
            }
            STARTED -> runQuestSection(npc, dialogue, "Talking to Hamal again")
            TASKS -> hamalTasks(npc)
            TASKS_DONE -> runQuestSection(npc, dialogue, "Talking to Hamal after completing both tasks")
            CONVINCE, KENDAL_DEAD -> hamalKendal(npc)
            BURIAL ->
                startDialogue(npc) {
                    // Authored (not Wiki): a reminder of the burial rites.
                    chatNpc(
                        neutral,
                        "Bury her on the island at the centre of the pool, with one of her " +
                            "possessions, and build a cairn of rocks on top. Ragnar may have something of hers.",
                    )
                }
            else -> hamalAfterQuest(npc)
        }
    }

    /** "So what are you doing up here?" after "Actually, I'm a Fremennik." says "below". */
    private fun hamalStartHooks(onStart: () -> Unit): QuestDialogueHooks {
        val upHere =
            findOption(dialogue.section("Hamal's Daughter"), "So what are you doing up here?") {
                it.children.any { child -> child is Node.Line }
            }
        val base = SimpleQuestHooks(options = mapOf("I will search for her!" to { onStart() }))
        return BelowHooks(base) { upHere?.children.orEmpty() }
    }

    private suspend fun ProtectedAccess.hamalTasks(npc: Npc) {
        if (bit(MdVarBits.heard_of_death) == 0) {
            setBit(MdVarBits.heard_of_death, 1)
            val hooks =
                SimpleQuestHooks(
                    options =
                        mapOf(
                            "Why do you hate them so much?" to { raiseRelations(SVIDI_NAMED) },
                            "About your food supplies..." to { raiseFood(FOOD_ASKED) },
                        )
                )
            runQuestSection(npc, dialogue, "Hamal the Chieftain", hooks)
            checkTasksDone()
            return
        }
        var pick = -1
        startDialogue(npc) {
            chatNpc(neutral, "What is it, outerlander?")
            pick = choice3("Never mind.", 0, "About the people of Rellekka...", 1, "About your food supplies...", 2)
        }
        when (pick) {
            1 -> hamalRellekka(npc)
            2 -> hamalFood(npc)
        }
        checkTasksDone()
    }

    private suspend fun ProtectedAccess.hamalRellekka(npc: Npc) {
        val r = relations
        when {
            r < BRUNDT_WANTS_ROCK -> {
                val hooks = SimpleQuestHooks(options = mapOf("Why do you hate them so much?" to { raiseRelations(SVIDI_NAMED) }))
                runQuestBranch(npc, dialogue, "Hamal the Chieftain", "About the people of Rellekka...", hooks)
            }
            r < GUARANTEE ->
                runQuestBranch(npc, dialogue, "Talking to Hamal before removing the rock", "About the people of Rellekka...")
            else -> {
                val given = r >= SVIDI_GUARANTEED
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the safety guarantee hasn't been given to Svidi:" to { !given },
                                "If the safety guarantee has been given to Svidi:" to { given },
                            )
                    )
                runQuestBranch(npc, dialogue, "Returning to Hamal", "About the people of Rellekka...", hooks)
                if (given) {
                    raiseRelations(RELATIONS_DONE)
                    setBit(MdVarBits.hamal_relations_done, 1)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.hamalFood(npc: Npc) {
        when {
            food < FOOD_ASKED -> {
                runQuestBranch(npc, dialogue, "Hamal the Chieftain", "About your food supplies...")
                raiseFood(FOOD_ASKED)
            }
            food < FOOD_DONE && (carries(MdObjs.pearl) || carries(MdObjs.pearl_seed)) -> {
                val seed = carries(MdObjs.pearl_seed)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the white pearl:" to { !seed },
                                "If the player has the white pearl seed:" to { seed },
                            ),
                        actions =
                            mapOf(
                                "Player gives the white pearl seed to Hamal" to {
                                    invDel(inv, MdObjs.pearl_seed)
                                    raiseFood(FOOD_DONE)
                                }
                            ),
                    )
                runQuestBranch(npc, dialogue, "Talking to Hamal after obtaining the white pearl", "About your food supplies...", hooks)
            }
            food < FOOD_DONE -> runQuestSection(npc, dialogue, "Talking to Hamal before obtaining the white pearl")
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): after the seed is planted.
                    chatNpc(neutral, "The White Pearl seed you brought us has taken root, outerlander. We will not go hungry.")
                }
        }
    }

    private fun ProtectedAccess.checkTasksDone() {
        if (state == TASKS && relations >= RELATIONS_DONE && food >= FOOD_DONE) setState(TASKS_DONE)
    }

    private suspend fun ProtectedAccess.hamalKendal(npc: Npc) {
        val killed = state >= KENDAL_DEAD
        if (!killed && bit(MdVarBits.bear_discovery) == 0) {
            runQuestSection(npc, dialogue, "Talking to Hamal before fighting the Kendal")
            return
        }
        var agreed = false
        val corpse = carries(MdObjs.corpse)
        val helmet = invTotal(worn, MdObjs.bearhead) > 0
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't defeated Kendal:" to { !killed },
                        "If the player defeated Kendal but isn't wearing the bear mask:" to { killed && !helmet },
                        "If the player defeated Kendal and is wearing the bear mask:" to { killed && helmet },
                        "If the player doesn't have the corpse:" to { !corpse },
                        "If the player has the corpse:" to { corpse },
                        "If the player defeated Kendal:" to { killed },
                    ),
                options = mapOf("I will." to { agreed = true }),
            )
        if (bit(MdVarBits.heard_of_bear) == 0) {
            setBit(MdVarBits.heard_of_bear, 1)
            runQuestSection(npc, dialogue, "Talking to Hamal about the Kendal for the first time", hooks)
        } else {
            runQuestSection(npc, dialogue, "Talking to Hamal again (2)", hooks)
            if (killed) {
                // "above": the first-time conversation from Hamal's verdict on.
                val rest =
                    dialogue.section("Talking to Hamal about the Kendal for the first time").dropWhile {
                        !(it is Node.Line && it.text.startsWith("As did he."))
                    }
                startDialogue(npc) { QuestDialogueRunner(hooks).run(this, rest) }
            }
        }
        if (agreed && killed && corpse && state == KENDAL_DEAD) {
            setBit(MdVarBits.heard_of_burial, 1)
            setState(BURIAL)
        }
    }

    private suspend fun ProtectedAccess.hamalAfterQuest(npc: Npc) {
        val lost = !owns(MdObjs.bearhead)
        var helmet = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If The Fremennik Trials hasn't been completed the requirements are met:" to { true },
                        "If the player has lost the bearhead:" to { lost },
                        "If the player still has the bearhead:" to { !lost },
                    ),
                options = mapOf("Excuse me, have you seen my helmet?" to { helmet = true }),
            )
        runQuestSection(npc, dialogue, "Talking to Hamal after quest completion", hooks)
        if (helmet && lost) invAddOrDrop(objRepo, MdObjs.bearhead)
    }

    /* Jokul, Ragnar, the guards and the camp dwellers */

    private suspend fun ProtectedAccess.talkJokul(npc: Npc) {
        when {
            state == 0 -> refuseOuterlander(npc)
            state == STARTED -> runQuestSection(npc, dialogue, "Jokul")
            food >= FOOD_DONE -> runQuestSection(npc, dialogue, "Talking to Jokul after giving Hamal the white pearl seed")
            food < FOOD_TIP && state < CONVINCE -> {
                runQuestSection(npc, dialogue, "Talking to Jokul")
                raiseFood(FOOD_TIP)
            }
            carries(MdObjs.pearl) || carries(MdObjs.pearl_seed) ->
                runQuestSection(npc, dialogue, "Talking to Jokul after obtaining the white pearl")
            else -> runQuestSection(npc, dialogue, "Talking to Jokkul again")
        }
    }

    private suspend fun ProtectedAccess.talkRagnar(npc: Npc) {
        when (state) {
            0 -> refuseOuterlander(npc)
            STARTED -> runQuestSection(npc, dialogue, "Ragnar")
            TASKS, TASKS_DONE, CONVINCE -> runQuestSection(npc, dialogue, "Speaking with Ragnar again")
            KENDAL_DEAD -> {
                val corpse = carries(MdObjs.corpse)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have the corpse:" to { !corpse },
                                "If the player has the corpse:" to { corpse },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Ragnar before Hamal", hooks)
            }
            BURIAL ->
                when {
                    bit(MdVarBits.ragnar_necklace) == 0 -> {
                        var given = false
                        val hooks = SimpleQuestHooks(actions = mapOf("" to { given = true }))
                        runQuestSection(npc, dialogue, "Talking to Ragnar again", hooks)
                        setBit(MdVarBits.ragnar_necklace, 1)
                        if (given || !owns(MdObjs.necklace)) invAddOrDrop(objRepo, MdObjs.necklace)
                    }
                    bit(MdVarBits.burial_state) == 0 && !owns(MdObjs.necklace) -> {
                        startDialogue(npc) {
                            // Authored (not Wiki): replacing a lost necklace.
                            chatNpc(neutral, "You lost it? Then take this one. I made two, in case she did not like the first.")
                        }
                        invAddOrDrop(objRepo, MdObjs.necklace)
                    }
                    else ->
                        startDialogue(npc) {
                            chatNpc(neutral, "Give her a proper burial, outerlander. It is all I ask.") // Authored (not Wiki).
                        }
                }
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): after the quest.
                    chatNpc(neutral, "Sometimes I still hear her singing over the water. It does not hurt so much any more.")
                }
        }
    }

    private suspend fun ProtectedAccess.refuseOuterlander(npc: Npc) {
        startDialogue(npc) {
            // Authored (not Wiki): Hamal: "It is also our custom not to talk to outerlanders without my sanction."
            chatNpc(neutral, "I do not speak with outerlanders. Not without the chieftain's leave.")
        }
    }

    private suspend fun ProtectedAccess.talkRockGuard(npc: Npc) {
        startDialogue(npc) {
            // Authored (not Wiki): the ancient rock's guard.
            chatNpc(neutral, "This tent holds our ancient rock, outerlander. Look if you must, but touch nothing.")
        }
    }

    /** The Wiki's five camp dweller replies ("Option 1:" ... "Option 5:"), one per dweller. */
    private suspend fun ProtectedAccess.talkDweller(npc: Npc, typeIndex: Int) {
        val options =
            dialogue.section("Camp dwellers").filter {
                it is Node.Action && it.tag.startsWith("Option ")
            }
        if (options.isEmpty()) return
        val pick = (typeIndex + npc.coords.x + npc.coords.z) % options.size
        val nodes = options[pick].children
        startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, nodes) }
    }

    /* Svidi and Brundt */

    private suspend fun ProtectedAccess.talkSvidi(npc: Npc) {
        val r = relations
        when {
            state < TASKS ->
                startDialogue(npc) {
                    // Authored (not Wiki): before Asleif's tasks.
                    chatNpc(neutral, "I am on important business for my chieftain, outerlander. Leave me be.")
                }
            r < SVIDI_WANTS_GUARANTEE -> {
                var persuaded = false
                val hooks = SimpleQuestHooks(options = mapOf("Can't I persuade you to go in there somehow?" to { persuaded = true }))
                runQuestSection(npc, dialogue, "Svidi", hooks)
                if (persuaded) raiseRelations(SVIDI_WANTS_GUARANTEE)
            }
            r < HALF_ROCK || (r == HALF_ROCK && !carries(MdObjs.half_rock)) -> {
                val brundt = r >= BRUNDT_WANTS_ROCK
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player hasn't talked with Brundt:" to { !brundt },
                                "If the player has talked with Brundt:" to { brundt },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Svidi before receiving the safety guarantee", hooks)
            }
            r == HALF_ROCK -> runQuestSection(npc, dialogue, "Talking to Svidi with half a rock")
            r == GUARANTEE -> {
                val has = carries(MdObjs.guarantee)
                val banked = !has && invTotal(bank, MdObjs.guarantee) > 0
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the safety guarantee is in the bank:" to { banked },
                                "If the safety guarantee was lost:" to { !has && !banked },
                            ),
                        actions =
                            mapOf(
                                "Player gives the safety guarantee to Svidi" to {
                                    invDel(inv, MdObjs.guarantee)
                                    raiseRelations(SVIDI_GUARANTEED)
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Returning to Svidi with the safety guarantee", hooks)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Svidi again")
        }
    }

    private fun ProtectedAccess.wantsBrundt(): Boolean =
        state == TASKS && relations in SVIDI_WANTS_GUARANTEE until RELATIONS_DONE

    private suspend fun ProtectedAccess.talkBrundt(npc: Npc) {
        val r = relations
        when {
            r == SVIDI_WANTS_GUARANTEE -> {
                var asked = false
                val demon =
                    findOption(dialogue.section("Brundt the Chieftain"), "Was it the eternal prison of an immortal demon?") { true }
                val base =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is a Fremennik:" to { false },
                                "If the player is an outlander:" to { true },
                                "If The Fremennik Trials hasn't been completed:" to { true },
                            ),
                        options = mapOf("Ask about the mountain camp." to { asked = true }),
                    )
                // The other two guesses get Brundt's answer to the third ("below").
                val hooks = BelowHooks(base) { demon?.children.orEmpty().drop(1) }
                runQuestSection(npc, dialogue, "Brundt the Chieftain", hooks)
                if (asked) raiseRelations(BRUNDT_WANTS_ROCK)
            }
            r < HALF_ROCK || (r == HALF_ROCK && !carries(MdObjs.half_rock)) ->
                runQuestSection(npc, dialogue, "Talking to Brundt before receiving the rock")
            r == HALF_ROCK -> {
                var gave = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is a Fremennik:" to { false },
                                "If the player is an outlander:" to { true },
                                "If The Fremennik Trials hasn't been completed:" to { true },
                            ),
                        actions =
                            mapOf(
                                "Player gives half a rock to Brundt" to {
                                    invDel(inv, MdObjs.half_rock)
                                    gave = true
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to Brundt the Chieftain after obtaining half a rock", hooks)
                if (gave) {
                    raiseRelations(GUARANTEE)
                    setBit(MdVarBits.brundt_done, 1)
                    invAddOrDrop(objRepo, MdObjs.guarantee)
                }
            }
            r == GUARANTEE -> {
                val lost = !owns(MdObjs.guarantee)
                val hooks = SimpleQuestHooks(conditions = mapOf("If the guarantee was lost:" to { lost }))
                runQuestSection(npc, dialogue, "Talking to Brundt the Chieftain again", hooks)
                if (lost) invAddOrDrop(objRepo, MdObjs.guarantee)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Brundt the Chieftain after delivering the guarantee")
        }
    }

    /* Entering the camp */

    private suspend fun ProtectedAccess.ropeOnBoulder(used: UnpackedObjType) {
        if (used.id != MdObjs.rope.id && used.id != MdObjs.quest_rope.id) {
            mes("Nothing interesting happens.")
            return
        }
        if (stat(stats.agility) < ROPE_AGILITY) {
            mes("You need an Agility level of $ROPE_AGILITY to climb down the cliff.") // Authored (not Wiki).
            return
        }
        mes("You tie the rope around the boulder and prepare to climb down.")
        anim(MdSeqs.absail)
        delay(3)
        telejump(CAMP_LANDING)
        delay(1)
        mes("You pull on the rope and miraculously, it comes loose.")
    }

    private suspend fun ProtectedAccess.climbRockslide() {
        val outside = player.coords.z <= ROCKSLIDE_OUTSIDE.z
        if (outside && state < STARTED) {
            // Authored (not Wiki): the reward is passing the rockslide; before Hamal's leave the guard won't allow it.
            startDialogue {
                chatNpcSpecific("Guard", MdNpcs.rockslide_guard, neutral, "Stay on your side of the rocks, outerlander.")
            }
            return
        }
        if (state in STARTED until COMPLETE) {
            val section = if (outside) "Returning into the camp through the rockslide" else "Leaving the mountain camp"
            runQuestSection(null, dialogue, section, speakers = guardSpeakers)
        }
        mes("You climb onto the rock...")
        anim(MdSeqs.climb_over)
        delay(2)
        telejump(if (outside) ROCKSLIDE_INSIDE else ROCKSLIDE_OUTSIDE)
        mes("...and step down the other side.")
    }

    private suspend fun ProtectedAccess.rockTentDoor(door: BoundLocInfo) {
        val entering = player.coords.z > door.coords.z
        if (!entering && carries(MdObjs.half_rock)) {
            runQuestSection(null, dialogue, "Exiting tent with half a rock", speakers = rockGuardSpeakers)
        }
        passThroughWall(door)
        if (entering) mes("You go into the peculiar looking tent.")
    }

    /* The shining pool */

    private fun ProtectedAccess.digMud() {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, MdObjs.mud)
        mes("You dig up some mud with your bare hands. Yuck, now they're all sticky.")
    }

    private fun ProtectedAccess.onTree(used: UnpackedObjType) {
        if (used.id != MdObjs.mud.id && used.id != MdObjs.swamp_paste.id) {
            mes("Nothing interesting happens.")
            return
        }
        if (bit(MdVarBits.mud) == 1) {
            mes("The bark is already covered in mud.") // Authored (not Wiki).
            return
        }
        invDel(inv, if (used.id == MdObjs.mud.id) MdObjs.mud else MdObjs.swamp_paste)
        setBit(MdVarBits.mud, 1)
        mes("Great, now the tree bar isn't slippery anymore!")
    }

    private suspend fun ProtectedAccess.climbTree() {
        if (player.coords.inBox(ISLAND_A)) {
            // Authored (not Wiki): the way back from the first island.
            mes("You climb back up the tree and drop down onto the shore.")
            anim(MdSeqs.tree_climb)
            delay(3)
            telejump(SHORE)
            return
        }
        if (bit(MdVarBits.mud) == 0) {
            mes("The bark has become slippery with water, you fail to find a suitable hold.")
            mes("Perhaps you could find something to apply to it to make it less slippery.")
            return
        }
        if (stat(stats.agility) < TREE_AGILITY) {
            // Authored (not Wiki): where we apply the quest's 20 Agility requirement.
            mes("You need an Agility level of $TREE_AGILITY to climb the tall tree.")
            return
        }
        mes("You jump up to grab the bark of the tree...")
        anim(MdSeqs.tree_climb)
        delay(2)
        mes("...crawl along the branch...")
        delay(1)
        telejump(ISLAND_A_LANDING)
        mes("...and jump down to the little island!")
    }

    private suspend fun ProtectedAccess.onPoleRocks(used: UnpackedObjType) {
        if (used.id == MdObjs.broken_pole.id) {
            mes("That would be a good idea... if the pole wasn't broken, that is.")
            return
        }
        if (!isVaultPole(used)) {
            fallIn()
            return
        }
        val dest = if (player.coords.z < POLE_ROCKS_Z) ISLAND_B_LANDING else ISLAND_A_LANDING
        mes("You plant your staff into the clump of rocks...")
        anim(MdSeqs.pole_vault)
        delay(2)
        telejump(dest)
        mes("...then successfully jump across!")
    }

    /** A staff or the camp's pole; the Dramen staff counts as a blunt weapon (Wiki, 2019). */
    private fun isVaultPole(obj: UnpackedObjType): Boolean {
        if (obj.id == MdObjs.pole.id || obj.id == MdObjs.pole_vault.id) return true
        val name = obj.name.lowercase()
        return "staff" in name && "dramen" !in name
    }

    private suspend fun ProtectedAccess.onFlatStone(used: UnpackedObjType) {
        if (used.id != MdObjs.plank.id) {
            fallIn()
            return
        }
        if (player.coords.inBox(ISLAND_C)) {
            var back = false
            startDialogue {
                back = choice2("Yes.", true, "No.", false, title = "Do you wish to return to shore?")
            }
            if (!back) return
            mes("With a lot of effort you manage to get back to shore.")
            telejump(SHORE)
            return
        }
        mes("You place the plank on the two flat stones...")
        delay(2)
        telejump(ISLAND_C_LANDING)
        mes("...then successfully walk across!")
    }

    private suspend fun ProtectedAccess.fallIn() {
        mes("You slip and fall into the water!")
        delay(2)
        telejump(SHORE)
        mes("You only just manage to get to shore!")
    }

    private suspend fun ProtectedAccess.listenPool() {
        if (!player.coords.inBox(ISLAND_C) || state == 0) {
            mes("You think you can hear soft singing!")
            mes("The sound seems to be coming from the center of the pool.")
            return
        }
        when (state) {
            STARTED -> {
                var agreed = false
                val hooks = SimpleQuestHooks(options = mapOf("I'll get right on it." to { agreed = true }))
                runQuestSection(null, dialogue, "Listening to the center shining pool", hooks)
                if (agreed && state == STARTED) setState(TASKS)
            }
            TASKS -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player hasn't found a new supply of food yet:" to { food < FOOD_DONE },
                                "If the player hasn't improved relations between the groups yet:" to {
                                    relations < RELATIONS_DONE
                                },
                            )
                    )
                runQuestSection(null, dialogue, "Talking to Asleif again", hooks)
            }
            TASKS_DONE -> {
                runQuestSection(null, dialogue, "Shining Pool")
                setState(CONVINCE)
            }
            CONVINCE -> runQuestSection(null, dialogue, "Talking to Asleif again (2)")
            KENDAL_DEAD -> runQuestSection(null, dialogue, "Talking to Shining pool before Hamal")
            BURIAL ->
                if (bit(MdVarBits.burial_state) == 0) {
                    runQuestSection(null, dialogue, "Listening to the Shining pool before laying Asleif to rest")
                } else {
                    runQuestSection(null, dialogue, "Listening to the Shining pool before building the cairn")
                }
            else -> mes("You think you can hear a soft 'thank you' on the wind.") // Authored (not Wiki).
        }
    }

    /* Making peace: the ancient rock */

    private suspend fun ProtectedAccess.onAncientRock(rock: BoundLocInfo, used: UnpackedObjType) {
        if ("pickaxe" !in used.name.lowercase()) {
            mes("Nothing interesting happens.")
            return
        }
        if (state != TASKS || relations < BRUNDT_WANTS_ROCK) {
            mes("You have no reason to damage the tribe's ancient rock.") // Authored (not Wiki).
            return
        }
        if (owns(MdObjs.half_rock)) {
            mes("While two halves make one whole, it's better to just have half a rock.")
            return
        }
        if (relations >= GUARANTEE) {
            mes("Brundt already has his half of the rock.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        anim(MdSeqs.rock_split)
        locRepo.change(rock, MdLocs.ancient_rock_splitting, ROCK_SPLIT_TICKS)
        runQuestSection(null, dialogue, "Removing the half a rock from the Ancient Rock")
        invAdd(inv, MdObjs.half_rock)
        raiseRelations(HALF_ROCK)
    }

    /* Finding food: the White Pearl */

    private suspend fun ProtectedAccess.pickPearl() {
        when {
            food < FOOD_TIP -> {
                mes("These thorny bushes don't look like anything worth picking.") // Authored (not Wiki).
                return
            }
            food >= FOOD_DONE || carries(MdObjs.pearl_seed) -> {
                mes("You're content with having the seed of the fruit.")
                return
            }
            carries(MdObjs.pearl) -> {
                mes("No need, you already have one of these.")
                return
            }
            !wearsThornGloves() -> {
                mes("Ouch! You just stuck your hand into some very sharp thorns.")
                return
            }
            inv.isFull() -> {
                mes("You don't have enough inventory space.") // Authored (not Wiki).
                return
            }
        }
        val hooks = SimpleQuestHooks(conditions = mapOf("If the player has appropriate gloves on:" to { true }))
        runQuestSection(null, dialogue, "Obtaining the white pearl seed", hooks)
        invAdd(inv, MdObjs.pearl)
    }

    /** Wiki: any gloves except Slayer, Ranger, Moonclan, Lunar, mystic gloves and vambraces. */
    private fun ProtectedAccess.wearsThornGloves(): Boolean {
        val hands = player.worn[Wearpos.Hands.slot] ?: return false
        val name = objTypes[hands].name.lowercase()
        if ("glove" !in name && "gauntlet" !in name) return false
        return EXCLUDED_GLOVES.none { it in name }
    }

    private fun ProtectedAccess.takeSeed(obj: Obj) {
        if (carries(MdObjs.pearl_seed)) {
            mes("One seed should be enough.")
            return
        }
        if (inv.isFull() || !objRepo.del(obj)) return
        invAdd(inv, MdObjs.pearl_seed)
    }

    /* The Kendal */

    private suspend fun ProtectedAccess.enterCave() {
        if (state != CONVINCE && state != KENDAL_DEAD && state != BURIAL) {
            mes("What a terrible stench! No one can live there, so you decide not to go either.")
            return
        }
        mes("You take a very deep breath...")
        delay(1)
        telejump(CAVE_INSIDE)
        resetKendal()
    }

    private suspend fun ProtectedAccess.leaveCave() {
        telejump(CAVE_OUTSIDE)
        mes("You welcome the fresh air.")
        resetKendal()
    }

    /** A fight that timed out (or a fighter that despawned) shows the talking Kendal again. */
    private fun ProtectedAccess.resetKendal() {
        if (state != CONVINCE) return
        val fighter = fighters[player.uid.packed]
        if (fighter != null && fighter.isSlotAssigned) return
        fighters.remove(player.uid.packed)
        setBit(MdVarBits.bear_multi_state, 0)
    }

    private suspend fun ProtectedAccess.talkKendal(npc: Npc) {
        if (state != CONVINCE) {
            // Authored (not Wiki): the Kendal only stirs once Asleif sends you.
            startDialogue(npc) { chatNpc(neutral, "Leave this place, mortal, while you still can!") }
            return
        }
        var fight = false
        val hooks =
            SimpleQuestHooks(
                actions =
                    mapOf(
                        "Conversation will resume from here if aborted" to { setBit(MdVarBits.bear_discovery, 1) },
                        "The Kendal attacks the player" to { fight = true },
                    )
            )
        if (bit(MdVarBits.bear_discovery) == 0) {
            runQuestSection(npc, dialogue, "Talking to him", hooks)
        } else {
            val nodes = resumeKendal()
            startDialogue(npc) { QuestDialogueRunner(hooks).run(this, nodes) }
        }
        if (fight) startFight(npc)
    }

    /** "Conversation will resume from here if aborted": the nodes after that marker. */
    private fun resumeKendal(): List<Node> {
        fun find(nodes: List<Node>): List<Node>? {
            for ((index, node) in nodes.withIndex()) {
                if (node is Node.Action && node.tag == "Conversation will resume from here if aborted") {
                    return nodes.drop(index + 1)
                }
                find(node.children)?.let { return it }
            }
            return null
        }
        return find(dialogue.section("Talking to him")).orEmpty()
    }

    private suspend fun ProtectedAccess.attackKendal(npc: Npc) {
        if (state != CONVINCE) return
        if (bit(MdVarBits.bear_discovery) == 0) {
            runQuestSection(npc, dialogue, "Attacking him before talking to him")
            return
        }
        startFight(npc)
    }

    private fun ProtectedAccess.startFight(npc: Npc) {
        val existing = fighters[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) return
        setBit(MdVarBits.bear_may_attack, 1)
        setBit(MdVarBits.bear_multi_state, 1)
        val fighter = Npc(npcTypes[MdNpcs.bearman_fighter], npc.coords)
        npcRepo.add(fighter, FIGHT_TICKS)
        fighters[player.uid.packed] = fighter
        fighter.opPlayer2(player, aiInteractions)
    }

    private fun kendalKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != MdNpcs.bearman_fighter.id) return
        val player = event.hero
        fighters.remove(player.uid.packed)
        if (player.questState(MOUNTAIN_DAUGHTER) != CONVINCE) return
        player.setQuestState(MOUNTAIN_DAUGHTER, KENDAL_DEAD)
        player.invAddOrDrop(objRepo, MdObjs.bearhead)
        player.mes("You collect the helmet from the corpse of the 'bear' as a trophy.")
    }

    /** The lair's corpse stays on the floor (a lost one can be taken again; the spawn has no respawn). */
    private suspend fun ProtectedAccess.takeCorpse() {
        if (state < KENDAL_DEAD) {
            val section =
                if (bit(MdVarBits.bear_discovery) == 0) {
                    "Trying to pick up the corpse of woman before killing him"
                } else {
                    "Trying to pick up the corpse of woman after talking to him"
                }
            runQuestSection(null, dialogue, section, speakers = kendalSpeakers)
            return
        }
        if (owns(MdObjs.corpse) || bit(MdVarBits.burial_state) > 0 || state >= COMPLETE) {
            mes("This must be someone else's corpse, you already have Asleif's corpse.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, MdObjs.corpse)
    }

    private fun ProtectedAccess.takeSpareBearhead() {
        if (owns(MdObjs.bearhead)) {
            mes("You already have a Bearhead.") // Authored (not Wiki).
            return
        }
        invAddOrDrop(objRepo, MdObjs.bearhead)
        mes("You take the Bearhead.") // Authored (not Wiki).
    }

    /* Laying Asleif to rest */

    private suspend fun ProtectedAccess.buryCorpse() {
        if (state < BURIAL) {
            mes("You'd better bring this back to the chieftain instead.")
            return
        }
        if (!player.coords.inBox(ISLAND_C)) {
            mes("Hamal asked you to bury her on the island at the centre of the pool.") // Authored (not Wiki).
            return
        }
        if (!carries(MdObjs.necklace)) {
            mes("She needs to be buried with some of her possessions, like jewelry.")
            return
        }
        telejump(BURIAL_SPOT)
        delay(1)
        anim(MdSeqs.bury)
        delay(2)
        invDel(inv, MdObjs.corpse)
        invDel(inv, MdObjs.necklace)
        setBit(MdVarBits.burial_state, BURIED)
        val hooks = SimpleQuestHooks(conditions = mapOf("If the player has Asleif's necklace:" to { true }))
        runQuestSection(null, dialogue, "Burying the corpse", hooks)
    }

    private suspend fun ProtectedAccess.onMound(used: UnpackedObjType) {
        if (used.id != MdObjs.muddy_rock.id) {
            mes("Nothing interesting happens.")
            return
        }
        if (state != BURIAL || bit(MdVarBits.burial_state) != BURIED) return
        val have = invTotal(inv, MdObjs.muddy_rock)
        if (have < CAIRN_ROCKS) {
            val more = CAIRN_ROCKS - have
            mes("You don't have enough rocks to build a cairn. You need $more more rock${if (more == 1) "" else "s"}.")
            return
        }
        anim(MdSeqs.place_stone)
        delay(2)
        invDel(inv, MdObjs.muddy_rock, CAIRN_ROCKS)
        setBit(MdVarBits.burial_state, CAIRN)
        mes("You think you can hear a soft 'thank you' as the singing quietens down.")
        statAdvance(stats.prayer, PRAYER_XP * xpMods.get(player, stats.prayer))
        statAdvance(stats.attack, ATTACK_XP * xpMods.get(player, stats.attack))
        completeQuest(MOUNTAIN_DAUGHTER)
    }

    /* Helpers */

    private fun findOption(nodes: List<Node>, label: String, accept: (Node.Option) -> Boolean): Node.Option? {
        for (node in nodes) {
            if (node is Node.Option && node.label == label && accept(node)) return node
            findOption(node.children, label, accept)?.let { return it }
        }
        return null
    }

    /** [base], plus the transcript's "below" action running [below] (the answer further down). */
    private class BelowHooks(private val base: SimpleQuestHooks, private val below: () -> List<Node>) :
        QuestDialogueHooks {
        override fun condition(text: String): Boolean = base.condition(text)

        override suspend fun Dialogue.onOption(label: String) {
            with(base) { onOption(label) }
        }

        override suspend fun Dialogue.onBox(text: String): Boolean = with(base) { onBox(text) }

        override suspend fun Dialogue.onAction(tag: String) {
            if (tag == "below") {
                QuestDialogueRunner(this@BelowHooks).run(this, below())
            } else {
                with(base) { onAction(tag) }
            }
        }

        override fun rewrite(text: String): String = base.rewrite(text)
    }

    private class Box(val x0: Int, val z0: Int, val x1: Int, val z1: Int)

    private fun CoordGrid.inBox(box: Box): Boolean = level == 0 && x in box.x0..box.x1 && z in box.z0..box.z1

    companion object {
        const val STARTED = 10
        const val TASKS = 20
        const val TASKS_DONE = 30
        const val CONVINCE = 40
        const val KENDAL_DEAD = 50
        const val BURIAL = 60
        const val COMPLETE = 70

        private const val SVIDI_NAMED = 1
        private const val SVIDI_WANTS_GUARANTEE = 2
        private const val BRUNDT_WANTS_ROCK = 3
        private const val HALF_ROCK = 5
        private const val GUARANTEE = 6
        private const val SVIDI_GUARANTEED = 7
        private const val RELATIONS_DONE = 8

        private const val FOOD_ASKED = 1
        private const val FOOD_TIP = 2
        private const val FOOD_DONE = 3

        private const val BURIED = 1
        private const val CAIRN = 2
        private const val CAIRN_ROCKS = 5

        private const val ROPE_AGILITY = 10
        private const val TREE_AGILITY = 20
        private const val FIGHT_TICKS = 500
        private const val ROCK_SPLIT_TICKS = 5
        private const val PRAYER_XP = 2000.0 // Cache db stat_xp_awarded prayer 20000 (tenths); Wiki 2,000.
        private const val ATTACK_XP = 1000.0

        private val EXCLUDED_GLOVES = listOf("slayer", "ranger", "moonclan", "lunar", "mystic")

        /* Positions from the rev 243 map (tools/agility/maplocs.py) and its tile flags. */
        /** Below the cliff boulder (2765,3666), on the camp path. */
        private val CAMP_LANDING = CoordGrid(2766, 3663, 0)
        /** Either side of the rockslide (2760,3658, 2x2) by the guard. */
        private val ROCKSLIDE_OUTSIDE = CoordGrid(2760, 3657, 0)
        private val ROCKSLIDE_INSIDE = CoordGrid(2760, 3660, 0)
        /** The lake shore by the tall tree (2772,3679). */
        private val SHORE = CoordGrid(2773, 3678, 0)
        /** The little island past the tree, the second past the clump of rocks (2773,3688), the
         * centre island past the flat stones (2775/2777,3691) with the burial mound (2783,3694). */
        private val ISLAND_A = Box(2771, 3683, 2774, 3687)
        private val ISLAND_C = Box(2777, 3689, 2787, 3697)
        private val ISLAND_A_LANDING = CoordGrid(2773, 3685, 0)
        private val ISLAND_B_LANDING = CoordGrid(2773, 3691, 0)
        private val ISLAND_C_LANDING = CoordGrid(2778, 3691, 0)
        private const val POLE_ROCKS_Z = 3688
        private const val ISLAND_REACH = 3
        private val BURIAL_SPOT = CoordGrid(2782, 3694, 0)
        /** Outside the cave entrance (2809,3703) and inside by its exit (2805,10104). */
        private val CAVE_OUTSIDE = CoordGrid(2808, 3703, 0)
        private val CAVE_INSIDE = CoordGrid(2808, 10104, 0)

        val MOUNTAIN_DAUGHTER =
            QuestDef(
                name = "Mountain Daughter",
                varbit = { MdVarBits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("2,000 Prayer XP", "1,000 Attack XP", "The Bearhead", "Mountain Camp rockslide access"),
                scrollObj = { MdObjs.bearhead },
            )
    }
}

// Every cache ref Mountain Daughter uses (re-check after cache updates).

internal object MdNpcs : NpcReferences() {
    val hamal = find("mdaughter_hamal")
    val jokul = find("mdaughter_jokul")
    val ragnar = find("mdaughter_ragnar")
    val svidi = find("mdaughter_svidi")
    val rockslide_guard = find("mdaughter_rockslide_guard")
    val rock_guard = find("mdaughter_ancientrock_guard")
    val dwellers =
        listOf(
            find("mdaughter_camp_male1"),
            find("mdaughter_camp_male2"),
            find("mdaughter_camp_female1"),
            find("mdaughter_camp_female2"),
            find("mdaughter_camp_female3"),
        )
    /** Brundt is a multinpc in the longhall; his child carries the Talk-to. */
    val brundt = find("viking_brundt_child")
    val bearman = find("mdaughter_bearman")
    /** The lair's multinpc (`mdaughter_bear_multi_state` 0 shows the talking Kendal) and its child. */
    val bears = listOf(find("mdaughter_multi_bear"), bearman)
    val bearman_fighter = find("mdaughter_bearman_fighter")
}

internal object MdLocs : LocReferences() {
    val boulder = find("mdaughter_cliff_boulder")
    val rockslide = find("mdaughter_rockslide")
    val tent_doors = listOf(find("mdaughter_tent_door"), find("mdaughter_tent_doorl"))
    val rock_tent_doors = listOf(find("mdaughter_rocktent_door"), find("mdaughter_rocktent_doorl"))
    val mud = listOf(find("mdaughter_swampbubbles1"))
    val roots = listOf(find("mdaughter_roots_1"), find("mdaughter_roots_2"))
    val lake_tree = find("mdaughter_lake_tree")
    val pole_rocks = find("mdaughter_polerocks")
    val flat_stones = listOf(find("mdaughter_flatstone1"), find("mdaughter_flatstone2"))
    val pool_gas = find("mdaughter_sulphar_gas")
    val pool_water = find("mdaughter_poolwater")
    val ancient_rock = find("mdaughter_ancient_rock")
    val ancient_rock_splitting = find("mdaughter_ancient_rock_splitting")
    val pearl_bush = find("mdaughter_white_pearl_bush")
    val blocking_trees = listOf(find("mdaughter_impassable_tree"), find("mdaughter_impassable_tree2"))
    val cave_entrance = find("mdaughter_caveentrance")
    val cave_exits = listOf(find("mdaughter_caveexit"), find("mdaughter_caveexit_fake"))
    /** The spare Bearhead child of `mdaughter_spare_bear_head` (quest varbit 50-69). */
    val bear_head = find("mdaughter_bear_head")
    /** The burial mound child of `mdaughter_multimound` (burial state 1). */
    val burial_mound = find("mdaughter_burialmound")
}

internal object MdObjs : ObjReferences() {
    val rope = find("rope")
    val quest_rope = find("mdaughter_rope")
    val mud = find("mdaughter_mud")
    val swamp_paste = find("swamppaste")
    val pole = find("mdaughter_stick")
    val pole_vault = find("mdaughter_polevault")
    val broken_pole = find("mdaughter_broken_stick")
    val plank = find("woodplank")
    val guarantee = find("mdaughter_safety_guarantee")
    val pearl = find("mdaughter_white_pearl_fruit")
    val pearl_seed = find("mdaughter_white_pearl_seed")
    val half_rock = find("mdaughter_half_rock")
    val corpse = find("mdaughter_daughter_corpse")
    val necklace = find("mdaughter_necklace")
    val muddy_rock = find("mdaughter_rock")
    val bearhead = find("mdaughter_bear_helmet")
}

internal object MdSeqs : SeqReferences() {
    val absail = find("mdaughter_absail_combi")
    val tree_climb = find("mdaughter_tree_climb_combi")
    val pole_vault = find("mdaughter_pole_vault")
    val bury = find("mdaughter_bury")
    val place_stone = find("mdaughter_place_stone")
    val rock_split = find("ancient_rock_splitting")
    val climb_over = find("human_walk_style")
}

internal object MdVarBits : VarBitReferences() {
    /** The quest varbit (bits 0-7 of `mdaughter_var`); the other bits are the cache's own flags. */
    val quest = find("mdaughter_quest_var")
    val mud = find("mdaughter_mud_var")
    val relations = find("mdaughter_relations_var")
    val food = find("mdaughter_food_var")
    val heard_of_death = find("mdaughter_hamal_heardofdeath")
    val brundt_done = find("mdaughter_brundt_done")
    val hamal_relations_done = find("mdaughter_hamal_relations_done")
    val bear_discovery = find("mdaughter_bear_discovery")
    val bear_may_attack = find("mdaughter_bear_mayattack")
    val bear_multi_state = find("mdaughter_bear_multi_state")
    val heard_of_bear = find("mdaughter_hamal_heardofbear")
    val ragnar_necklace = find("mdaughter_ragnar_gavenecklace")
    val heard_of_burial = find("mdaughter_hamal_heardofburial")
    val burial_state = find("mdaughter_burial_state")
}
