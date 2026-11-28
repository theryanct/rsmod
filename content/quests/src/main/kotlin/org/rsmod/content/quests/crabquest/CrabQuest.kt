package org.rsmod.content.quests.crabquest

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.events.EmoteSelected
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseFishingLvl
import org.rsmod.api.player.stat.baseSailingLvl
import org.rsmod.api.player.stat.baseWoodcuttingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
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
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Crab Quest (OSRS Wiki). Quest varbit `crab_main` (bits 0-5 of `crab`), end state 63; the rest is
 * the cache's own `crab` / `crab_secondary` varbits (shells handed in, the instrument's name,
 * flotsam fished, crabs found and recruited, the shy crab, the commotion and penguin).
 * Requirements (cache dbrow): Sailing 40, Fishing 30 and Pandemonium. Sail to Dognose Island
 * (Sailing voyages, level 40 mooring) and drink from the strange bottle on the beach.
 * - 5 the dream: a crab on the beach needs an instrument to become a ceremonial leader. Nine shells
 *   lie around the island (seven good ones, `crab_seashell_1-7`, and two bad ones he throws back).
 * - 25 after the seventh shell he wants a string instrument: take the big fishing net from the
 *   beach, net the flotsam three times (bowstring, barrel, plank; `crab_floatsam_hidden`) and put
 *   them together. 45 the instrument handed over and named (`crab_bassname`), the crab practises.
 * - Investigate the island's 14 trees, palms, bushes and plant for crabs (the scared ones come out
 *   once you've sat down beside them for a while; the magic tree's crab takes longer). Eleven of
 *   them join the practice (`crab_bandmate_N` found, `crab_recruited_bandmate_N` practising), one
 *   is a penguin in disguise (after the commotion, `crab_told_about_commotion`), one is the main
 *   crab's shy crush (`crab_shy`, then by the rusty chest). 50 six leaders; 55 all of them.
 * - Back with the main crab: the crab rave and the ceremony; 63 1 QP, 10,000 Sailing XP, 3,000
 *   Fishing XP and the shell collection (unpacks into seven playable shells; lost ones are found
 *   again at the bottle).
 *
 * Simplified: OSRS plays the dream in an instance; here it is the real Dognose Island, so the
 * quest's trees are swapped in for a while each time you drink (drink again after a restart), the
 * shells and net are your own ground items, and the "can't board / teleport / drop while
 * dreaming" blocks are not applied. Crabs come out of a scared tree when you investigate it again
 * after sitting beside it (not on a timer while seated). The rave and ceremony are their
 * dialogue without the cutscene choreography; the shells' notes are messages, not sounds.
 */
class CrabQuest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locRepo: LocRepository,
    private val collision: CollisionFlagMap,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(CrabQuest::class.java, "crab_quest") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Crab" to CrabNpcs.band, "'Crab'" to CrabNpcs.penguin)
    }

    /** Practising band crabs per player (bandmate index -> npc) and back (npc -> index). */
    private val band = HashMap<Int, HashMap<Int, Npc>>()
    private val bandmateOf = HashMap<Npc, Int>()
    private val shyCrab = HashMap<Int, Npc>()

    /** Where (spot index) and when (map clock) the player last sat down beside a scared crab. */
    private val sat = HashMap<Int, Pair<Int, Int>>()

    override fun ScriptContext.startup() {
        if (!isLocOpBound(CrabLocs.bottle, 1)) onOpLoc1(CrabLocs.bottle) { bottle() }
        with(QuestTalk) {
            for (crab in CrabNpcs.mains) register(npcTypes, crab, CRAB_QUEST, wants = { true }) { talkMain(it) }
            register(npcTypes, CrabNpcs.band, CRAB_QUEST, wants = { true }) { talkBand(it) }
        }
        for (flotsam in CrabNpcs.flotsam) {
            if (!isNpcOpBound(flotsam, 1)) onOpNpc1(flotsam) { netFlotsam() }
        }
        for (type in Spot.investigateTypes()) {
            if (!isLocOpBound(type, 1)) onOpLoc1(type) { investigate(it.loc) }
        }
        onEvent<EmoteSelected> { if (slot == SIT_DOWN_SLOT) sitDown(player) }

        // The instrument: any two of the three flotsam pieces.
        onOpHeldU(CrabObjs.plank, CrabObjs.bowstring) { combine() }
        onOpHeldU(CrabObjs.barrel, CrabObjs.bowstring) { combine() }
        onOpHeldU(CrabObjs.plank, CrabObjs.barrel) { combine() }

        // Shells: the quest's and the reward set.
        for (shell in CrabObjs.shells + CrabObjs.bad_shells) onOpHeld1(shell) { mes("You hold the shell to your ear and hear the sea.") }
        onOpHeld1(CrabObjs.container) { unpack() }
        for ((index, shell) in CrabObjs.rewards.withIndex()) {
            onOpHeld1(shell) { mes("The shell plays a soft ${NOTES[index]}.") }
            onOpHeld4(shell) { collapse() }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(CRAB_QUEST)

    /** The transcript's {{mes}} lines are game messages, not message boxes. */
    private fun ProtectedAccess.mesHooks(base: QuestDialogueHooks = SimpleQuestHooks()): QuestDialogueHooks =
        MesHooks(this, base, MES_LINES)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(CRAB_QUEST, value)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Boolean = player.vars[varbit] != 0

    private fun ProtectedAccess.setVar(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.recruited(): Int = CrabVarBits.recruited.count { flag(it) }

    /* The bottle */

    private suspend fun ProtectedAccess.bottle() {
        when (state) {
            0 -> start()
            in STARTED until COMPLETE -> {
                runQuestSection(null, dialogue, "Drinking from the bottle again to go back to the dream", mesHooks())
                enterDream()
            }
            else -> reminisce()
        }
    }

    private suspend fun ProtectedAccess.start() {
        val pandemonium = player.vars[CrabVarBits.pandemonium] >= PANDEMONIUM_COMPLETE
        if (player.baseSailingLvl < SAILING_REQ || player.baseFishingLvl < FISHING_REQ || !pandemonium) {
            mes("You need level $SAILING_REQ Sailing, level $FISHING_REQ Fishing and Pandemonium completed to drink from this bottle.")
            return
        }
        var started = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has NPC attack options enabled:" to { true },
                        "If the player has NPC attack options disabled:" to { false },
                    ),
                options = mapOf("Yes." to { started = true }),
                boxes =
                    mapOf(
                        "...and immediately black out." to
                            {
                                setState(STARTED)
                                false
                            }
                    ),
            )
        runQuestSection(null, dialogue, "Drinking from bottle", mesHooks(hooks), speakers)
        if (started) enterDream()
    }

    private suspend fun ProtectedAccess.reminisce() {
        val nodes = dialogue.section("Reminiscing at the bottle")
        startDialogue { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes.take(1)) }
        val owned =
            carries(CrabObjs.container) || invTotal(bank, CrabObjs.container) > 0 ||
                CrabObjs.rewards.any { carries(it) || invTotal(bank, it) > 0 }
        if (!owned) {
            startDialogue { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes.drop(2).take(1)) }
            invAddOrDrop(objRepo, CrabObjs.container)
        }
    }

    /**
     * The dream's island: the quest's trees (Investigate) over the real ones, the shells and net
     * still to collect, and the crabs already practising.
     */
    private fun ProtectedAccess.enterDream() {
        for (spot in Spot.entries) {
            val loc = locRepo.findExact(spot.coords, spot.real) ?: continue
            locRepo.change(loc, spot.crab, DREAM_TICKS)
        }
        if (state < INSTRUMENT) {
            for ((index, shell) in CrabObjs.shells.withIndex()) {
                if (!flag(CrabVarBits.seashells[index]) && !carries(shell)) placeObj(shell, SHELL_SPOTS[index])
            }
            for ((index, shell) in CrabObjs.bad_shells.withIndex()) {
                if (!carries(shell)) placeObj(shell, SHELL_SPOTS[CrabObjs.shells.size + index])
            }
        }
        if (state < PRACTISING && !carries(CrabObjs.net)) placeObj(CrabObjs.net, NET_SPOT)
        if (state >= PRACTISING) {
            for (index in CrabVarBits.recruited.indices) {
                if (flag(CrabVarBits.recruited[index])) spawnBandmate(index)
            }
            if (player.vars[CrabVarBits.shy] == SHY_AT_CHEST) spawnShyCrab()
        }
    }

    private fun ProtectedAccess.placeObj(obj: ObjType, pin: CoordGrid) {
        val coords = collision.nearestWalkable(pin, 3)
        if (objRepo.findAll(coords).any { it.type == obj.id }) return
        objRepo.add(obj, coords, DREAM_TICKS, player)
    }

    /* The main crab */

    private suspend fun ProtectedAccess.talkMain(npc: Npc) {
        if (shyCrab[player.uid.packed] === npc) {
            talkShy(npc)
            return
        }
        when (state) {
            STARTED -> handInShells(npc)
            INSTRUMENT -> handInInstrument(npc)
            in PRACTISING until COMPLETE -> talkPractising(npc)
            COMPLETE ->
                // Authored (not Wiki): the crab after the ceremony.
                startDialogue(npc) { chatNpc(happy, "You again! Please don't dance near the nests.") }
            else -> mes("The crab ignores you.")
        }
    }

    private suspend fun ProtectedAccess.handInShells(npc: Npc) {
        val goodHeld = CrabObjs.shells.withIndex().filter { carries(it.value) }
        val newGood = goodHeld.filter { !flag(CrabVarBits.seashells[it.index]) }
        val badHeld = CrabObjs.bad_shells.filter { carries(it) }
        val total = goodHeld.size + badHeld.size
        val runner = QuestDialogueRunner(handInHooks(total, newGood.isEmpty() && badHeld.isEmpty()), speakers)
        startDialogue(npc) {
            runner.run(this, dialogue.section("Handing in seashells"))
            if (newGood.isEmpty() && badHeld.isEmpty()) return@startDialogue
            for (bad in badHeld) {
                access.invDel(access.inv, bad)
                runner.run(this, dialogue.section("Bad shell"))
            }
            for ((index, shell) in newGood) {
                access.invDel(access.inv, shell)
                access.setVar(CrabVarBits.seashells[index], 1)
                runner.run(this, dialogue.section(SHELL_BOXES[index]))
                val count = CrabVarBits.seashells.count { access.flag(it) }
                runner.run(this, dialogue.section(CORRECT_SHELLS[count - 1]))
            }
        }
        if (CrabVarBits.seashells.all { flag(it) }) setState(INSTRUMENT)
    }

    private fun ProtectedAccess.handInHooks(total: Int, nothingNew: Boolean): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player does not have any seashells:" to { total == 0 },
                    "If the player has one seashell:" to { total == 1 },
                    "If the player has multiple seashells:" to { total > 1 },
                    "If the player has already turned in that seashell:" to { nothingNew },
                    "If the player has already turned in those seashells:" to { nothingNew },
                    "If the player has a regular lyre in their inventory:" to { carries(CrabObjs.lyre) },
                    "If the player has an enchanted lyre in their inventory:" to {
                        !carries(CrabObjs.lyre) && CrabObjs.enchanted_lyres.any { carries(it) }
                    },
                )
        )

    private suspend fun ProtectedAccess.handInInstrument(npc: Npc) {
        val hasBass = carries(CrabObjs.bass)
        val hasAll = carries(CrabObjs.plank) && carries(CrabObjs.barrel) && carries(CrabObjs.bowstring)
        val fished = player.vars[CrabVarBits.flotsam] >= FLOTSAM_PIECES
        var given = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't fished all 3 materials yet:" to { !hasBass && !hasAll && !fished },
                        "If the player has all 3 materials:" to { !hasBass && hasAll },
                        "If the player fished all 3 materials but no longer has them:" to { !hasBass && !hasAll && fished },
                        "If the player dropped the instrument:" to { false },
                        "If the player has the instrument:" to { hasBass },
                    ),
                options =
                    mapOf(
                        "A disaster." to { setVar(CrabVarBits.bassname, NAME_DISASTER) },
                        "A bass." to { setVar(CrabVarBits.bassname, NAME_BASS) },
                        // Also the label of "How do you play it?"'s last answer: name only once.
                        "I don't actually know." to {
                            if (player.vars[CrabVarBits.bassname] == 0) setVar(CrabVarBits.bassname, NAME_BANJO)
                        },
                    ),
                boxes =
                    mapOf(
                        "You give the instrument to the crab." to
                            {
                                invDel(inv, CrabObjs.bass)
                                given = true
                                false
                            }
                    ),
            )
        runQuestSection(npc, dialogue, "Handing in the instrument", hooks, speakers)
        if (!hasBass && !hasAll && fished) setVar(CrabVarBits.flotsam, 0) // "Yep, there it is!"
        if (given) setState(PRACTISING)
    }

    private suspend fun ProtectedAccess.talkPractising(npc: Npc) {
        val count = recruited()
        when {
            count >= LEADERS_NEEDED -> ceremony(npc)
            flag(CrabVarBits.recruited[GIRL]) && player.vars[CrabVarBits.shy] == 0 -> {
                runQuestSection(npc, dialogue, "Returning to the main crab before finding the main crab's crush", speakers = penguinOnly())
                setVar(CrabVarBits.told_about_girl, 1)
            }
            else -> runQuestSection(npc, dialogue, "Talking to the crab again", speakers = penguinOnly())
        }
    }

    private fun penguinOnly(): Map<String, NpcType> = mapOf("'Crab'" to CrabNpcs.penguin)

    private suspend fun ProtectedAccess.ceremony(npc: Npc) {
        val all = recruited() == CrabVarBits.recruited.size
        var wait = all
        var rewarded = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If every crab has joined practice:" to { all },
                        "If not every crab has joined practice:" to { !all },
                        "If the player has unlocked the Crab dance emote:" to { false },
                        "If the player has not unlocked the Crab dance emote:" to { false },
                    ),
                options = mapOf("Wait for leaders to turn up." to { wait = true }),
                boxes =
                    mapOf(
                        "As you wake up, you find some familiar-looking seashells..." to
                            {
                                invAddOrDrop(objRepo, CrabObjs.container)
                                rewarded = true
                                false
                            }
                    ),
            )
        startDialogue(npc) {
            val runner = QuestDialogueRunner(mesHooks(hooks), penguinOnly())
            if (!all) runner.run(this, dialogue.section("Talking to the main crab"))
            if (!wait) return@startDialogue
            access.setState(CEREMONY)
            runner.run(this, dialogue.section("Talking to the main crab (2)"))
            runner.run(this, dialogue.section("Crab rave"))
            runner.run(this, dialogue.section("The ceremony"))
        }
        if (state != CEREMONY) return
        if (!rewarded) invAddOrDrop(objRepo, CrabObjs.container)
        statAdvance(stats.sailing, SAILING_XP * xpMods.get(player, stats.sailing))
        statAdvance(stats.fishing, FISHING_XP * xpMods.get(player, stats.fishing))
        setVar(CrabVarBits.partial_complete, 1)
        setVar(CrabVarBits.total_complete, 1)
        despawnBand()
        completeQuest(CRAB_QUEST)
    }

    /* The flotsam and the instrument */

    private suspend fun ProtectedAccess.netFlotsam() {
        if (state != INSTRUMENT) {
            runQuestSection(null, dialogue, "Interacting with the flotsam")
            return
        }
        val fished = player.vars[CrabVarBits.flotsam]
        val section = dialogue.section("Fishing up flotsam")
        val conds = section.takeWhile { it is Node.Cond }
        val boxes = section.drop(conds.size)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have a big fishing net:" to { !carries(CrabObjs.net) },
                        "If the player has no inventory space:" to { inv.isFull() && fished < FLOTSAM_PIECES },
                    )
            )
        val runner = QuestDialogueRunner(mesHooks(hooks), speakers)
        if (!carries(CrabObjs.net) || (inv.isFull() && fished < FLOTSAM_PIECES)) {
            startDialogue { runner.run(this, conds) }
            return
        }
        if (fished >= FLOTSAM_PIECES) {
            startDialogue { runner.run(this, listOf(boxes.last())) }
            return
        }
        // "You cast out your net..." then the next piece: bowstring, barrel, plank.
        startDialogue { runner.run(this, listOf(boxes[0], boxes[1 + fished])) }
        invAdd(inv, FLOTSAM_ORDER[fished])
        setVar(CrabVarBits.flotsam, fished + 1)
    }

    private suspend fun ProtectedAccess.combine() {
        if (!(carries(CrabObjs.plank) && carries(CrabObjs.barrel) && carries(CrabObjs.bowstring))) {
            runQuestSection(null, dialogue, "Using other items on the instrument materials", mesHooks())
            return
        }
        invDel(inv, CrabObjs.plank)
        invDel(inv, CrabObjs.barrel)
        invDel(inv, CrabObjs.bowstring)
        invAdd(inv, CrabObjs.bass)
        runQuestSection(null, dialogue, "Combining the flotsam")
    }

    /* Crab hunting */

    private enum class Spot(
        val coords: CoordGrid,
        val real: LocType,
        val crab: LocType,
        val investigate: String,
        /** Sitting section for a crab too scared to come out, else `null`. */
        val sit: String?,
        /** Bandmate index (0-10) for a crab that joins the practice, else `null`. */
        val bandmate: Int?,
        val practice: String?,
        val sitTicks: Int = SIT_TICKS,
    ) {
        MagicTree(CoordGrid(3043, 2640, 0), CrabLocs.magictree, CrabLocs.crab_magictree, "Investigating the magic tree",
            "Sitting by the magic tree", 0, "Talking to the crab at practice", sitTicks = MAGIC_SIT_TICKS),
        PalmSouth(CoordGrid(3042, 2637, 0), CrabLocs.palm1, CrabLocs.crab_palm1, "Investigating the palm south of the magic tree",
            "Sitting by the palm", 1, "Talking to the crab at practice (2)"),
        PalmSouthWest(CoordGrid(3038, 2637, 0), CrabLocs.palm1, CrabLocs.crab_palm1,
            "Investigating the palm south-west of the magic tree", null, null, null),
        TreeNorthWest(CoordGrid(3036, 2644, 0), CrabLocs.tree1, CrabLocs.crab_tree1, "Investigating the tree north-west of the magic tree",
            "Sitting by the palm (2)", 2, "Talking to the crab at practice (3)"),
        WesternPalm(CoordGrid(3037, 2647, 0), CrabLocs.palm2, CrabLocs.crab_palm2, "Investigating the western-most palm",
            null, 3, "Talking to the crab at practise"),
        PalmEast(CoordGrid(3039, 2647, 0), CrabLocs.palm3, CrabLocs.crab_palm3,
            "Investigating the palm directly east of the western-most palm", null, 4, "Talking to the crab at practise (2)"),
        PalmNorthEast(CoordGrid(3040, 2649, 0), CrabLocs.palm1, CrabLocs.crab_palm1,
            "Investigating the palm directly north-east of the western-most palm", null, GIRL, "Talking to the crab at practise (3)"),
        NorthernPalm(CoordGrid(3048, 2658, 0), CrabLocs.palm1, CrabLocs.crab_palm1, "Investigating the northern-most palm",
            null, 6, "Talking to the crab at practice (4)"),
        PalmNorth(CoordGrid(3044, 2653, 0), CrabLocs.palm3, CrabLocs.crab_palm3,
            "Investigating the palm directly north of the magic tree", null, null, null),
        NorthWestTree(CoordGrid(3046, 2656, 0), CrabLocs.tree1, CrabLocs.crab_tree1, "Investigating the north-western most tree",
            "Sitting by the tree", 7, "Talking to the crab at practise (4)"),
        NorthEastTree(CoordGrid(3050, 2657, 0), CrabLocs.tree2, CrabLocs.crab_tree2, "Investigating the north-eastern most tree",
            "Sitting by the tree (2)", null, null),
        Plant(CoordGrid(3054, 2656, 0), CrabLocs.plant, CrabLocs.crab_plant, "Investigating the plant",
            null, 8, "Talking to the crab at practise (5)"),
        WesternBush(CoordGrid(3049, 2635, 0), CrabLocs.bush, CrabLocs.crab_bush, "Investigating the western bush",
            null, 9, "Talking to the crab at practise (6)"),
        EasternBush(CoordGrid(3057, 2640, 0), CrabLocs.bush, CrabLocs.crab_bush, "Investigating the eastern bush",
            null, 10, "Talking to the crab at practise (7)");

        companion object {
            fun at(coords: CoordGrid): Spot? = entries.firstOrNull { it.coords == coords }

            fun investigateTypes(): Set<LocType> = entries.map { it.crab }.toSet()
        }
    }

    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) {
        val spot = Spot.at(loc.coords) ?: return
        if (state < PRACTISING || state == COMPLETE) {
            // Authored (not Wiki): nothing hides here outside the crab hunt.
            mes("You find nothing of interest.")
            return
        }
        val bandmate = spot.bandmate
        if (bandmate != null && flag(CrabVarBits.found[bandmate])) {
            // The Wiki's "has left to practise" box (its template text didn't survive the export).
            mes("The crab from this ${if (spot.sit == null) "spot" else "tree"} has left to practise.")
            return
        }
        if (spot == Spot.PalmNorth && player.vars[CrabVarBits.shy] != 0) {
            mes("The shy crab has scuttled away.")
            return
        }
        if (spot == Spot.NorthernPalm && !flag(CrabVarBits.told_about_commotion)) {
            mes("You find nothing of interest.")
            return
        }
        val sit = spot.sit
        if (sit != null) {
            val (where, since) = sat[player.uid.packed] ?: (-1 to 0)
            if (where != spot.ordinal || mapClock - since < spot.sitTicks) {
                runQuestSection(null, dialogue, spot.investigate)
                return
            }
            sat.remove(player.uid.packed)
            meetCrab(spot, sit)
            return
        }
        meetCrab(spot, spot.investigate)
    }

    private suspend fun ProtectedAccess.meetCrab(spot: Spot, section: String) {
        val bandmate = spot.bandmate
        val crab = Npc(npcTypes[if (spot == Spot.NorthernPalm) CrabNpcs.penguin else CrabNpcs.band], crabTile(spot))
        npcRepo.add(crab, MEET_TICKS)
        val extra = ArrayList<Npc>()
        var joined = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has 75 Woodcutting or higher:" to { player.baseWoodcuttingLvl >= MAGIC_WOODCUTTING },
                        "If the player doesn't have at least 75 Woodcutting:" to { player.baseWoodcuttingLvl < MAGIC_WOODCUTTING },
                        "If the player has talked to the crab that suggests the player could be their kid:" to { flag(CrabVarBits.found[OLD]) },
                        "If the player has already talked to the magic tree crab:" to { flag(CrabVarBits.found[MAGIC]) },
                        "If the player hasn't already talked to the magic tree crab:" to { !flag(CrabVarBits.found[MAGIC]) },
                        "If the player has talked to the crab nicknamed \"Dipper\":" to { flag(CrabVarBits.found[DIPPER]) },
                    ),
                boxes =
                    mapOf(
                        "The crab scutters away to practise." to { joined = true; false },
                        "The crab serenely scutters away to practise." to { joined = true; false },
                        "The crab despondently throws its claws in the air, then scutters away to join the practice." to
                            { joined = true; false },
                        "The crab scutters away." to { joined = true; false },
                        "A crab appears!" to
                            {
                                if (spot == Spot.NorthernPalm && extra.isEmpty()) {
                                    // The penguin is chased off and the real crab comes out.
                                    val real = Npc(npcTypes[CrabNpcs.band], crab.coords.translate(0, 1))
                                    npcRepo.add(real, MEET_TICKS)
                                    extra += real
                                    setVar(CrabVarBits.kicked_penguin, 1)
                                }
                                false
                            },
                    ),
            )
        startDialogue(crab) { QuestDialogueRunner(mesHooks(hooks), speakers).run(this, dialogue.section(section)) }
        if (!joined) return
        for (npc in extra + crab) remove(npc)
        if (spot == Spot.PalmNorth) {
            setVar(CrabVarBits.shy, SHY_AT_CHEST)
            spawnShyCrab()
            return
        }
        if (bandmate == null) return
        setVar(CrabVarBits.found[bandmate], 1)
        setVar(CrabVarBits.recruited[bandmate], 1)
        spawnBandmate(bandmate)
        val count = recruited()
        when {
            count == CrabVarBits.recruited.size -> {
                runQuestSection(null, dialogue, "After finding every crab to join practise")
                setState(ALL_LEADERS)
            }
            count == LEADERS_NEEDED -> {
                runQuestSection(null, dialogue, "After finding all six leaders to join practise")
                setState(ENOUGH_LEADERS)
            }
        }
    }

    /** Removes a quest crab unless its lifetime already ran out. */
    private fun remove(npc: Npc) {
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
    }

    private fun crabTile(spot: Spot): CoordGrid = collision.nearestWalkable(spot.coords.translate(-1, 0), 2)

    private fun sitDown(player: Player) {
        val clock = player.currentMapClock
        val spot =
            Spot.entries
                .filter { it.sit != null }
                .minByOrNull { it.coords.chebyshevDistance(player.coords) }
                ?.takeIf { it.coords.chebyshevDistance(player.coords) <= SIT_RANGE }
                ?: return
        if (player.questState(CRAB_QUEST) !in PRACTISING until COMPLETE) return
        sat[player.uid.packed] = spot.ordinal to clock
        player.mes("You sit down quietly beside the ${if (spot == Spot.MagicTree) "magic tree" else "tree"} and wait...")
    }

    private fun ProtectedAccess.spawnBandmate(index: Int) {
        val mine = band.getOrPut(player.uid.packed) { HashMap() }
        val existing = mine[index]
        if (existing != null && existing.isSlotAssigned) return
        val tile = collision.nearestWalkable(PRACTICE.translate(BAND_OFFSETS[index].first, BAND_OFFSETS[index].second), 2)
        val crab = Npc(npcTypes[CrabNpcs.band], tile)
        npcRepo.add(crab, DREAM_TICKS)
        existing?.let { bandmateOf.remove(it) }
        mine[index] = crab
        bandmateOf[crab] = index
    }

    private fun ProtectedAccess.spawnShyCrab() {
        val existing = shyCrab[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) return
        val crab = Npc(npcTypes[CrabNpcs.shy], collision.nearestWalkable(CHEST_SPOT, 2))
        npcRepo.add(crab, DREAM_TICKS)
        shyCrab[player.uid.packed] = crab
    }

    private fun ProtectedAccess.despawnBand() {
        band.remove(player.uid.packed)?.values?.forEach { crab ->
            bandmateOf.remove(crab)
            remove(crab)
        }
        shyCrab.remove(player.uid.packed)?.let { remove(it) }
    }

    private suspend fun ProtectedAccess.talkBand(npc: Npc) {
        val index = bandmateOf[npc] ?: return
        val spot = Spot.entries.first { it.bandmate == index }
        val practice = spot.practice ?: return
        val girl = flag(CrabVarBits.recruited[GIRL])
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the crab playing coconut is not present:" to { !flag(CrabVarBits.recruited[DEEP]) },
                        "If the crab playing coconut is present:" to { flag(CrabVarBits.recruited[DEEP]) },
                        "If the crab at the red shell is present:" to { girl },
                        "First time if the crab on the red shell is present:" to { girl },
                        "Subsequent times or if the crab on the red shell isn't present:" to { !girl },
                    )
            )
        runQuestSection(npc, dialogue, practice, hooks, penguinOnly())
        if (index == GIRL) setVar(CrabVarBits.told_about_commotion, 1)
    }

    private suspend fun ProtectedAccess.talkShy(npc: Npc) {
        val name = player.vars[CrabVarBits.bassname]
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player said the instrument was a disaster:" to { name == NAME_DISASTER },
                        "If the player said the instrument was a bass:" to { name == NAME_BASS },
                        "If the player said they did not know the name of the instrument:" to { name == NAME_BANJO },
                    )
            )
        runQuestSection(npc, dialogue, "Talking to the crab next to the chest", hooks, penguinOnly())
    }

    /* The shell collection */

    private fun ProtectedAccess.unpack() {
        if (inv.freeSpace() < CrabObjs.rewards.size - 1) {
            mes("You need ${CrabObjs.rewards.size - 1} free inventory spaces to unpack the shells.")
            return
        }
        invDel(inv, CrabObjs.container)
        for (shell in CrabObjs.rewards) invAdd(inv, shell)
        mes("You unpack the shell collection.")
    }

    private fun ProtectedAccess.collapse() {
        if (!CrabObjs.rewards.all { carries(it) }) {
            mes("You need all seven shells to pack them back into the collection.")
            return
        }
        for (shell in CrabObjs.rewards) invDel(inv, shell)
        invAdd(inv, CrabObjs.container)
        mes("You pack the shells back into the collection.")
    }

    companion object {
        const val STARTED = 5
        const val INSTRUMENT = 25
        const val PRACTISING = 45
        const val ENOUGH_LEADERS = 50
        const val ALL_LEADERS = 55
        const val CEREMONY = 60
        const val COMPLETE = 63

        private const val SAILING_REQ = 40
        private const val FISHING_REQ = 30
        /** `quest_pandemonium` endstate; the cache lists it as Crab Quest's requirement. */
        private const val PANDEMONIUM_COMPLETE = 50
        private const val SAILING_XP = 10_000.0
        private const val FISHING_XP = 3_000.0
        private const val MAGIC_WOODCUTTING = 75

        private const val LEADERS_NEEDED = 6
        private const val FLOTSAM_PIECES = 3
        private const val NAME_DISASTER = 1
        private const val NAME_BASS = 2
        private const val NAME_BANJO = 3
        private const val SHY_AT_CHEST = 1

        /** Bandmate indices referenced by other crabs' lines. */
        private const val MAGIC = 0
        private const val GIRL = 5
        private const val DEEP = 7
        private const val OLD = 8
        private const val DIPPER = 10

        /** Emote list slot of "Sit down" (enum `emote_names`). */
        private const val SIT_DOWN_SLOT = 54
        private const val SIT_RANGE = 3
        private const val SIT_TICKS = 8
        private const val MAGIC_SIT_TICKS = 25

        private const val DREAM_TICKS = 6000
        private const val MEET_TICKS = 500

        /** Every {{mes}} in the Wiki transcript (shown in the chatbox, not as a box). */
        private val MES_LINES =
            setOf(
                "Against your better judgement, you pick up the bottle and take a sip...",
                "...and immediately black out.",
                "The headache from drinking from the bottle stops you from remembering how to teleport. Drinking from the bottle again may help with this.",
                "Your headache seems to have cleared a little. You remember where you moored your boat.",
                "You wake up with a headache.",
                "You're not even sure any of this is real. You probably can't drop your items here, even if you wanted to.",
                "You cast out your net...",
                "You catch a sea-soaked bowstring.",
                "You catch a small battered barrel.",
                "You catch a weathered rosewood plank.",
                "To make a string instrument, you'll also need a sound box to amplify the sound and a frame or fretboard to tense the string.",
                "And now, you wait...",
                "If lost, you can collect your shell collection from the bottle, here, on Dognose Island.",
            )

        private val NOTES = listOf("C", "D", "E", "F", "G", "A", "A sharp")

        private val SHELL_BOXES =
            listOf(
                "Large rose shell",
                "Black and white shell",
                "Vivid orange shell",
                "Bright yellow shell",
                "Exotic looking shell",
                "Unassuming cream shell",
                "Tiny white shell",
            )

        private val CORRECT_SHELLS =
            listOf(
                "First correct shell",
                "Second correct shell",
                "Third correct shell",
                "Fourth correct shell",
                "Fifth correct shell",
                "Sixth correct shell",
                "Last correct shell",
            )

        private val FLOTSAM_ORDER: List<ObjType> by lazy { listOf(CrabObjs.bowstring, CrabObjs.barrel, CrabObjs.plank) }

        /** Authored: shells around Dognose Island's beach (seven good, then the two bad ones). */
        private val SHELL_SPOTS =
            listOf(
                CoordGrid(3058, 2636, 0),
                CoordGrid(3052, 2633, 0),
                CoordGrid(3045, 2634, 0),
                CoordGrid(3039, 2640, 0),
                CoordGrid(3038, 2652, 0),
                CoordGrid(3043, 2657, 0),
                CoordGrid(3053, 2653, 0),
                CoordGrid(3059, 2646, 0),
                CoordGrid(3062, 2641, 0),
            )

        /** Authored: the big fishing net, north of the crab on the beach. */
        private val NET_SPOT = CoordGrid(3054, 2643, 0)

        /** Authored: the practice circle on the beach and the band around it. */
        private val PRACTICE = CoordGrid(3052, 2638, 0)
        private val BAND_OFFSETS =
            listOf(-2 to 1, -1 to 2, 0 to 2, 1 to 2, 2 to 1, 2 to 0, 2 to -1, -2 to 0, -2 to -1, -1 to -2, 1 to -2)

        /** Authored: the shy crab watches from beside the rusty chest (3054,2650). */
        private val CHEST_SPOT = CoordGrid(3055, 2649, 0)

        val CRAB_QUEST =
            QuestDef(
                name = "Crab Quest",
                varbit = { CrabVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("10,000 Sailing XP", "3,000 Fishing XP", "The shell collection"),
                scrollObj = { CrabObjs.container },
            )
    }
}

// Every cache ref Crab Quest uses (re-check after cache updates).

internal object CrabNpcs : NpcReferences() {
    /* Talk-to children of crab_maincrab / crab_maincrab_practising (spawned) and crab_shycrab. */
    val mains =
        listOf(
            find("crab_maincrab_start"),
            find("crab_maincrab_blushing"),
            find("crab_maincrab_start_nomove"),
            find("crab_maincrab_end"),
        )
    val band = find("crab_bandcrab")
    val shy = find("crab_shycrab")
    val penguin = find("crab_penguin")
    val flotsam = listOf(find("crab_flotsam_1"), find("crab_flotsam_2"), find("crab_flotsam_3"))
}

internal object CrabLocs : LocReferences() {
    val bottle = find("crab_bottle_multi")
    val magictree = find("magictree")
    val palm1 = find("palm_update01")
    val palm2 = find("palm_update02")
    val palm3 = find("palm_update03")
    val tree1 = find("avium_tree_1")
    val tree2 = find("avium_tree_2")
    val plant = find("groundcover_plant4_withered")
    val bush = find("enakh_leaflessbush")
    val crab_magictree = find("crab_magictree")
    val crab_palm1 = find("crab_palm_update01")
    val crab_palm2 = find("crab_palm_update02")
    val crab_palm3 = find("crab_palm_update03")
    val crab_tree1 = find("crab_avium_tree_1")
    val crab_tree2 = find("crab_avium_tree_2")
    val crab_plant = find("crab_groundcover_plant4_withered")
    val crab_bush = find("crab_enakh_leaflessbush")
}

internal object CrabObjs : ObjReferences() {
    val shells =
        listOf(
            find("crab_shell_1"),
            find("crab_shell_2"),
            find("crab_shell_3"),
            find("crab_shell_4"),
            find("crab_shell_5"),
            find("crab_shell_6"),
            find("crab_shell_7"),
        )
    val bad_shells = listOf(find("crab_shell_bad1"), find("crab_shell_bad2"))
    val net = find("big_net")
    val plank = find("crab_plank")
    val barrel = find("crab_barrel")
    val bowstring = find("crab_bowstring")
    val bass = find("crab_bass")
    val container = find("crab_shell_container")
    val rewards =
        listOf(
            find("crab_shell_reward_1"),
            find("crab_shell_reward_2"),
            find("crab_shell_reward_3"),
            find("crab_shell_reward_4"),
            find("crab_shell_reward_5"),
            find("crab_shell_reward_6"),
            find("crab_shell_reward_7"),
        )
    val lyre = find("viking_strung_lyre")
    val enchanted_lyres =
        listOf(
            find("viking_enchanted_strung_lyre"),
            find("magic_strung_lyre"),
            find("magic_strung_lyre_2"),
            find("magic_strung_lyre_3"),
            find("magic_strung_lyre_4"),
            find("magic_strung_lyre_5"),
        )
}

internal object CrabVarBits : VarBitReferences() {
    val quest = find("crab_main")
    val bassname = find("crab_bassname")
    val seashells =
        listOf(
            find("crab_seashell_1"),
            find("crab_seashell_2"),
            find("crab_seashell_3"),
            find("crab_seashell_4"),
            find("crab_seashell_5"),
            find("crab_seashell_6"),
            find("crab_seashell_7"),
        )
    val flotsam = find("crab_floatsam_hidden")
    val found =
        listOf(
            find("crab_bandmate_1"),
            find("crab_bandmate_2"),
            find("crab_bandmate_3"),
            find("crab_bandmate_4"),
            find("crab_bandmate_5"),
            find("crab_bandmate_6"),
            find("crab_bandmate_7"),
            find("crab_bandmate_8"),
            find("crab_bandmate_9"),
            find("crab_bandmate_10"),
            find("crab_bandmate_11"),
        )
    val recruited =
        listOf(
            find("crab_recruited_bandmate_1"),
            find("crab_recruited_bandmate_2"),
            find("crab_recruited_bandmate_3"),
            find("crab_recruited_bandmate_4"),
            find("crab_recruited_bandmate_5"),
            find("crab_recruited_bandmate_6"),
            find("crab_recruited_bandmate_7"),
            find("crab_recruited_bandmate_8"),
            find("crab_recruited_bandmate_9"),
            find("crab_recruited_bandmate_10"),
            find("crab_recruited_bandmate_11"),
        )
    val shy = find("crab_shy")
    val told_about_commotion = find("crab_told_about_commotion")
    val told_about_girl = find("crab_told_about_girl")
    val kicked_penguin = find("crab_kicked_penguin")
    val partial_complete = find("crab_partial_complete")
    val total_complete = find("crab_total_complete")
    val pandemonium = find("sailing_intro")
}

/** Wraps [base] so the transcript's {{mes}} boxes go to the chatbox (after [base]'s own effects). */
private class MesHooks(
    private val access: ProtectedAccess,
    private val base: QuestDialogueHooks,
    private val lines: Set<String>,
) : QuestDialogueHooks {
    override fun condition(text: String): Boolean = base.condition(text)

    override suspend fun Dialogue.onOption(label: String) {
        with(base) { onOption(label) }
    }

    override suspend fun Dialogue.onBox(text: String): Boolean {
        if (with(base) { onBox(text) }) return true
        if (text !in lines) return false
        access.mes(text)
        return true
    }

    override suspend fun Dialogue.onAction(tag: String) {
        with(base) { onAction(tag) }
    }

    override fun rewrite(text: String): String = base.rewrite(text)
}
