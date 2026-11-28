package org.rsmod.content.quests.eaglespeak

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onApNpc1
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
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
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Eagles' Peak (OSRS Wiki). Quest varbit `eaglepeak_quest` (varp `eaglepeak`), cache db end state
 * 40. The stages are the ones the cache's multinpcs/multilocs switch on (camp messy at 5-20, the
 * cliff opens at 15, the nest Nickolaus shouts at 15 / talks at 20 / is disguised at 25, the camp
 * Nickolaus returns at 25, the eyrie eagles offer Quick-travel and the zoo ferret appears at 40):
 * - 5 Charlie (Ardougne Zoo, Hunter 27, boostable) asks you to find Nickolaus.
 * - 10 the bird book from the camp's scattered books drops the metal feather.
 * - 15 the metal feather opens the rocky outcrop on the summit; inside, shout to Nickolaus across
 *   the chasm (`eaglepeak_nickolaus_chat` 1 after the first meeting).
 * - 20 he asks for two eagle disguises: Asyff (Varrock fancy dress) makes them from 10 eagle
 *   feathers (the giant feather piles), swamp tar, yellow dye and 50 coins (`nickolaus_chat` 2
 *   once she has named the materials).
 * - The stone door needs the three metal feathers: bronze (net trap room: take, then all four
 *   winches, `puzzle3_*`), silver (Hunter tracking room: pedestal, two rocks, the opening and the
 *   kebbit, `puzzle2_tracking` 1-5) and golden (metal bird room, `puzzle1_*`). The door is
 *   `eagledoor_status` (bronze bit 1, golden bit 2) plus `puzzle2_tracking` 6 (silver).
 * - 25 wearing a disguise you walk past the guard eagle and give Nickolaus the other one.
 * - 30 at the camp Nickolaus catches a ferret with you; 40 Charlie takes it: 2 QP, 2,500 Hunter XP
 *   (cache db stat_xp_awarded hunter 25000 tenths; Wiki 2,500), a box trap and the eagle transport.
 *
 * Golden feather room (rails, feeders, gate walls and levers from the rev 243 map): five metal
 * birds (`mechbird1..5`, 0 = start end) block the corridors at their rail ends and travel to a fed
 * feeder; five stone gates sit in the gate-wall gaps, each closed until its levers (`gate1..4`, the
 * levers' own multivar) have been pulled an odd number of times. The corridor bird leaves the
 * feather corridor only when the north-arm gate is open and the corner bird has gone west.
 *
 * Simplified: the gold puzzle's lever/gate wiring and bird starts are ours, fitted so the Wiki's
 * 11-step solution works (the original's exact rules aren't documented); the shout / ferret
 * cutscenes are plain dialogue; the silver trail is the cache's multilocs (no extra decals); the
 * Feldip vine grows at once instead of after ~40 minutes; Asyff's single costume (5 feathers) and
 * the remote eyries' entrances (desert boulder = a 45 Strength check) are authored; box traps
 * stay usable before the quest (main has no Hunter quest gate); the zoo ferret multinpc isn't
 * spawned, the kebbit's decline-combat-xp choice and Nickolaus's crawl npc are unused, the
 * Rellekka eyrie's 35 Agility climb isn't checked, and the puzzle rooms' spawned locs re-sync only
 * on entering their tunnel.
 */
class EaglesPeak
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
    private val dialogue by lazy { QuestDialogue.load(EaglesPeak::class.java, "eagles_peak") }

    /** The kebbit spawned for each player (single player, but keyed for safety). */
    private val kebbits = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, EpNpcs.charlie, EAGLES_PEAK, wants = { true }) { talkCharlie(it) }
            register(npcTypes, EpNpcs.asyff, EAGLES_PEAK, wants = { true }) { talkAsyff(it) }
        }

        // Nickolaus: the nest and camp multinpcs (events carry the base id; the child must be bound
        // too for the op to resolve).
        for (type in EpNpcs.nickolaus_all) {
            onOpNpc1(type) { talkNickolaus(it.npc) }
            onApNpc1(type) { apNickolaus(it.npc) }
        }
        onOpNpc1(EpNpcs.eagle_guard) { walkPastEagle() }

        // The camp and the cliff.
        onLocOpLabel(locTypes, EpLocs.campfire_messy, "Inspect") {
            mes("A few logs are still burning so it can't have been unattended long.")
        }
        onLocOpLabel(locTypes, EpLocs.books_messy, "Inspect") { inspectBooks() }
        onLocOpLabel(locTypes, EpLocs.backpack_messy, "Inspect") {
            mes("The camping equipment has been scattered about.") // Authored (not Wiki).
        }
        onHeldOpLabel(objTypes, EpObjs.bird_book, "Read") { readBirdBook() }
        onLocOpLabel(locTypes, EpLocs.outcrop, "Inspect") { runQuestSection(null, dialogue, "Interacting with the Rocky outcrop") }
        onOpLocU(EpLocs.outcrop) { featherOnOutcrop(it.objType) }
        onOpLocU(EpLocs.outcrop_open) {
            if (it.objType.id == EpObjs.metal_feather.id) {
                mes("The cliff face has already opened to reveal a tunnel heading into the mountain.")
            } else {
                mes("Nothing interesting happens.")
            }
        }
        onLocOpLabel(locTypes, EpLocs.outcrop_open, "Enter") { telejump(CAVE_INSIDE) }
        onLocOpLabel(locTypes, EpLocs.cave_exit, "Exit") { telejump(CAVE_OUTSIDE) }

        // The cave: tunnels, feathers, the eagle door.
        onLocOpLabel(locTypes, EpLocs.puzzle1_entrance, "Enter") { enterPuzzle(EpVarBits.entered_puzzle1, GOLD_ARRIVAL) { syncGold() } }
        onLocOpLabel(locTypes, EpLocs.puzzle1_exit, "Enter") { leavePuzzle(GOLD_RETURN) }
        onLocOpLabel(locTypes, EpLocs.puzzle2_entrance, "Enter") { enterPuzzle(EpVarBits.entered_puzzle2, SILVER_ARRIVAL) {} }
        onLocOpLabel(locTypes, EpLocs.puzzle2_exit, "Enter") { leavePuzzle(SILVER_RETURN) }
        onLocOpLabel(locTypes, EpLocs.puzzle3_entrance, "Enter") { enterPuzzle(EpVarBits.entered_puzzle3, BRONZE_ARRIVAL) { syncBronze() } }
        onLocOpLabel(locTypes, EpLocs.puzzle3_exit, "Enter") { leavePuzzle(BRONZE_RETURN) }
        onLocOpLabel(locTypes, EpLocs.feather_pile, "Take") { takeFeather() }
        for (door in EpLocs.doors) {
            onLocOpLabel(locTypes, door, "Open") { openDoor(it) }
            onOpLocU(door) { featherOnDoor(it.objType) }
        }

        // Bronze feather: the net trap.
        onLocOpLabel(locTypes, EpLocs.net_trap_inactive, "Take-from") { springNetTrap() }
        for ((index, winch) in EpLocs.winches.withIndex()) {
            onLocOpLabel(locTypes, winch, "Operate") { operateWinch(index) }
        }
        onLocOpLabel(locTypes, EpLocs.pedestal_bronze, "Take-from") {
            takeMetalFeather(EpObjs.bronze_feather, doorHasBronze(), "bronze")
        }

        // Silver feather: tracking the kebbit.
        onLocOpLabel(locTypes, EpLocs.pedestal_silver, "Inspect") { inspectSilverPedestal() }
        onLocOpLabel(locTypes, EpLocs.trail_rock1, "Inspect") { inspectRock1() }
        onLocOpLabel(locTypes, EpLocs.trail_rock2, "Inspect") { inspectRock2() }
        for (dummy in EpLocs.trail_dummies) {
            onLocOpLabel(locTypes, dummy, "Inspect") { mes(PLAIN_ROCKS) }
        }
        onLocOpLabel(locTypes, EpLocs.kebbit_opening, "Inspect") { inspectOpening() }
        onOpNpc3(EpNpcs.kebbit) { threatenKebbit(it.npc) }
        onEvent<NpcKilledEvent> { kebbitKilled(this) }

        // Golden feather: the metal birds.
        onLocOpLabel(locTypes, EpLocs.birdseed_holder, "Take-from") { takeBirdseed() }
        for ((index, feeder) in EpLocs.feeders.withIndex()) {
            onOpLocU(feeder) { useOnFeeder(index, it.objType) }
        }
        onLocOpLabel(locTypes, EpLocs.lever_down, "Pull-down") { pullLever(it) }
        onLocOpLabel(locTypes, EpLocs.lever_up, "Push-up") { pullLever(it) }
        onLocOpLabel(locTypes, EpLocs.reset_lever, "Pull") { pullResetLever() }
        onLocOpLabel(locTypes, EpLocs.pedestal_gold, "Take-from") {
            takeMetalFeather(EpObjs.golden_feather, doorHasGold(), "golden")
        }

        // The eagle transport.
        for (eagle in Eyrie.entries) {
            onOpNpc1(eagle.base()) { quickTravel(eagle) }
            onOpNpc1(eagle.travel()) { quickTravel(eagle) }
        }
        onLocOpLabel(locTypes, EpLocs.polar_cave_in, "Enter") { telejump(Eyrie.Polar.inside) }
        onLocOpLabel(locTypes, EpLocs.polar_cave_out, "Exit") { telejump(Eyrie.Polar.outside) }
        onLocOpLabel(locTypes, EpLocs.desert_cave_in, "Enter") { enterDesertEyrie() }
        onLocOpLabel(locTypes, EpLocs.desert_cave_out, "Exit") { telejump(Eyrie.Desert.outside) }
        onLocOpLabel(locTypes, EpLocs.jungle_cave_in, "Enter") { enterJungleEyrie() }
        onLocOpLabel(locTypes, EpLocs.jungle_cave_out, "Exit") { telejump(Eyrie.Jungle.outside) }
        for (vine in EpLocs.vines) onLocOpLabel(locTypes, vine, "Climb") { enterJungleEyrie() }
        onOpLocU(EpLocs.vines.first()) { plantCane(it.objType) }
    }

    /* State */

    private val ProtectedAccess.state: Int
        get() = player.questState(EAGLES_PEAK)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(EAGLES_PEAK, value)
    }

    private fun ProtectedAccess.raiseState(value: Int) {
        if (state < value) setState(value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.owns(obj: ObjType): Boolean =
        carries(obj) || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

    private fun ProtectedAccess.wearsDisguise(): Boolean =
        invTotal(worn, EpObjs.eagle_cape) > 0 && invTotal(worn, EpObjs.fake_beak) > 0

    private fun ProtectedAccess.carriesDisguise(): Boolean =
        carries(EpObjs.eagle_cape) && carries(EpObjs.fake_beak)

    private fun ProtectedAccess.raiseChat(value: Int) {
        if (bit(EpVarBits.nickolaus_chat) < value) setBit(EpVarBits.nickolaus_chat, value)
    }

    /* Charlie */

    private suspend fun ProtectedAccess.talkCharlie(npc: Npc) {
        when {
            state == 0 -> {
                var start = false
                val skilled = stat(stats.hunter) >= HUNTER_LEVEL
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is below level 27 in Hunter:" to { !skilled },
                                "If the player is level 27 or above in Hunter:" to { skilled },
                            ),
                        options = mapOf("Sure. Any idea where I should start looking?" to { start = true }),
                    )
                runQuestSection(npc, dialogue, "Before starting Eagle's Peak", hooks)
                if (start && state == 0) setState(STARTED)
            }
            state < FERRET -> runQuestSection(npc, dialogue, "Talking to Charlie again")
            state < COMPLETE && carries(EpObjs.ferret) -> {
                runQuestSection(npc, dialogue, "Finishing up")
                if (!carries(EpObjs.ferret)) return
                invDel(inv, EpObjs.ferret)
                statAdvance(stats.hunter, HUNTER_XP * xpMods.get(player, stats.hunter))
                invAddOrDrop(objRepo, EpObjs.box_trap)
                completeQuest(EAGLES_PEAK)
            }
            state < COMPLETE -> runQuestSection(npc, dialogue, "Charlie waiting for the ferret (authored)")
            else -> runQuestSection(npc, dialogue, "Charlie after the quest (authored)")
        }
    }

    /* Asyff */

    private suspend fun ProtectedAccess.talkAsyff(npc: Npc) {
        val haveMaterials =
            carries(EpObjs.eagle_feather, COSTUME_FEATHERS) &&
                carries(EpObjs.swamp_tar) &&
                carries(EpObjs.yellow_dye)
        when {
            state < HELPING -> runQuestSection(npc, dialogue, "Asyff (authored)")
            state == HELPING && !owns(EpObjs.eagle_cape) -> {
                if (bit(EpVarBits.nickolaus_chat) < CHAT_ASKED_ASYFF) {
                    val hooks =
                        SimpleQuestHooks(
                            options =
                                mapOf(
                                    "Well, specifically I'm after a couple of bird costumes." to {
                                        raiseChat(CHAT_ASKED_ASYFF)
                                    }
                                )
                        )
                    runQuestSection(npc, dialogue, "Getting a bird costume", hooks)
                } else if (!haveMaterials) {
                    runQuestSection(npc, dialogue, "Returning to Asyff without the materials")
                } else {
                    val rich = carries(EpObjs.coins, COSTUME_PRICE)
                    var make = false
                    val hooks =
                        SimpleQuestHooks(
                            conditions =
                                mapOf(
                                    "If the player doesn't have 50 coins:" to { !rich },
                                    "If the player has 50 coins:" to { rich },
                                ),
                            options = mapOf("Okay, here are the materials. Eagle me up." to { make = rich }),
                        )
                    runQuestSection(npc, dialogue, "Returning to Asyff with the materials", hooks)
                    if (make) makeCostumes(COSTUME_FEATHERS, COSTUME_PRICE, 2)
                }
            }
            haveMaterials && carries(EpObjs.coins, SPARE_COSTUME_PRICE) && !owns(EpObjs.eagle_cape) -> {
                runQuestSection(npc, dialogue, "Asyff makes another costume (authored)")
                makeCostumes(SPARE_COSTUME_FEATHERS, SPARE_COSTUME_PRICE, 1)
            }
            else -> runQuestSection(npc, dialogue, "Asking for another costume")
        }
    }

    private fun ProtectedAccess.makeCostumes(feathers: Int, price: Int, sets: Int) {
        if (!carries(EpObjs.eagle_feather, feathers) || !carries(EpObjs.swamp_tar) ||
            !carries(EpObjs.yellow_dye) || !carries(EpObjs.coins, price)
        ) {
            return
        }
        invDel(inv, EpObjs.eagle_feather, feathers)
        invDel(inv, EpObjs.swamp_tar)
        invDel(inv, EpObjs.yellow_dye)
        invDel(inv, EpObjs.coins, price)
        invAddOrDrop(objRepo, EpObjs.eagle_cape, sets)
        invAddOrDrop(objRepo, EpObjs.fake_beak, sets)
    }

    /* The camp and the cliff */

    private suspend fun ProtectedAccess.inspectBooks() {
        when {
            state == STARTED && !owns(EpObjs.bird_book) -> {
                runQuestSection(null, dialogue, "Picking up the bird book")
                invAddOrDrop(objRepo, EpObjs.bird_book)
            }
            state == FEATHER && !owns(EpObjs.metal_feather) -> {
                runQuestSection(null, dialogue, "Reclaiming a lost feather")
                invAddOrDrop(objRepo, EpObjs.metal_feather)
            }
            else -> mes("These books look like they've been kicked over by someone or something.")
        }
    }

    private suspend fun ProtectedAccess.readBirdBook() {
        if (state == STARTED) {
            runQuestSection(null, dialogue, "Opening the bird book")
            invAddOrDrop(objRepo, EpObjs.metal_feather)
            setState(FEATHER)
            return
        }
        mes("The book is full of sketches and notes on all manner of birds.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.featherOnOutcrop(used: UnpackedObjType) {
        if (used.id != EpObjs.metal_feather.id) {
            mes("Nothing interesting happens.")
            return
        }
        if (state < FEATHER) return
        runQuestSection(null, dialogue, "Using the Metal feather on the Rocky outcrop")
        invDel(inv, EpObjs.metal_feather)
        raiseState(CAVE_OPEN)
    }

    /* Nickolaus */

    private suspend fun ProtectedAccess.apNickolaus(npc: Npc) {
        val range = if (npc.id == EpNpcs.nickolaus.id) SHOUT_RANGE else TALK_RANGE
        if (!isWithinApRange(npc, range)) return
        talkNickolaus(npc)
    }

    private suspend fun ProtectedAccess.talkNickolaus(npc: Npc) {
        if (npc.id == EpNpcs.nickolaus_campsite.id) {
            talkNickolausCamp(npc)
            return
        }
        when (state) {
            CAVE_OPEN -> {
                var helping = false
                val base = SimpleQuestHooks(options = mapOf("Could I help at all?" to { helping = true }))
                val hooks = NickolausHooks(dialogue, base)
                if (bit(EpVarBits.nickolaus_chat) == 0) {
                    runQuestSection(npc, dialogue, MEETING, hooks)
                    raiseChat(CHAT_MET)
                } else {
                    runQuestSection(npc, dialogue, "Shouting at Nickolaus", hooks)
                }
                if (helping) raiseState(HELPING)
            }
            HELPING -> nickolausDisguise(npc)
            DISGUISED -> runQuestSection(npc, dialogue, "Talking to Nickolaus again")
            else -> {}
        }
    }

    private suspend fun ProtectedAccess.nickolausDisguise(npc: Npc) {
        val near = player.coords.inBox(NEST)
        val wearing = wearsDisguise()
        val spare = carriesDisguise()
        when {
            near -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is wearing the eagle disguise:" to { wearing },
                                "If the player has a disguise for Nickolaus:" to { spare },
                                "If the player does not have a disguise for Nickolaus:" to { !spare },
                            ),
                        boxes =
                            mapOf(
                                "You give Nickolaus a fake beak and an eagle feather cape." to {
                                    if (carriesDisguise()) {
                                        invDel(inv, EpObjs.eagle_cape)
                                        invDel(inv, EpObjs.fake_beak)
                                        raiseState(DISGUISED)
                                    }
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "With eagle outfit", hooks)
            }
            wearing -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has a disguise for Nickolaus:" to { spare },
                                "If the player does not have a disguise for Nickolaus:" to { !spare },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Nickolaus while wearing the eagle outfit", hooks)
            }
            spare || carries(EpObjs.eagle_cape) ->
                runQuestSection(npc, dialogue, "Talking to Nickolaus while not wearing the eagle outfit")
            else -> runQuestSection(npc, dialogue, "Visiting Nickolaus after agreeing to help him")
        }
    }

    private suspend fun ProtectedAccess.talkNickolausCamp(npc: Npc) {
        if (state < DISGUISED) {
            runQuestSection(npc, dialogue, "Nickolaus at the camp before the quest (authored)")
            return
        }
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "That sounds good to me." to {
                            if (state < COMPLETE && !carries(EpObjs.ferret)) {
                                invAddOrDrop(objRepo, EpObjs.ferret)
                                raiseState(FERRET)
                            }
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Nickolaus by the camp", hooks)
    }

    private suspend fun ProtectedAccess.walkPastEagle() {
        val north = player.coords.z >= NEST.z0
        if (!wearsDisguise() && !north) {
            // Authored (not Wiki): the guard eagle strikes at anyone undisguised.
            mes("The eagle lunges at you with its razor-sharp beak!")
            player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(1, 3))
            return
        }
        mes("You edge cautiously past the eagle; it doesn't seem to object.")
        telejump(if (north) LEDGE_SIDE else NEST_SIDE)
    }

    /* The cave */

    private suspend fun ProtectedAccess.enterPuzzle(
        entered: VarBitType,
        dest: CoordGrid,
        sync: ProtectedAccess.() -> Unit,
    ) {
        mes("You enter the tunnel.")
        setBit(entered, 1)
        telejump(dest)
        sync()
    }

    private fun ProtectedAccess.leavePuzzle(dest: CoordGrid) {
        mes("You enter the tunnel.")
        telejump(dest)
    }

    private fun ProtectedAccess.takeFeather() {
        if (inv.isFull() && !carries(EpObjs.eagle_feather)) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, EpObjs.eagle_feather)
    }

    private fun ProtectedAccess.doorHasBronze(): Boolean = bit(EpVarBits.door_bronze) == 1

    private fun ProtectedAccess.doorHasGold(): Boolean = bit(EpVarBits.door_gold) == 1

    private fun ProtectedAccess.doorHasSilver(): Boolean = bit(EpVarBits.tracking) == TRACK_DOOR

    private fun ProtectedAccess.doorOpen(): Boolean = doorHasBronze() && doorHasGold() && doorHasSilver()

    private suspend fun ProtectedAccess.openDoor(door: BoundLocInfo) {
        if (!doorOpen()) {
            runQuestSection(null, dialogue, "Interacting with the Stone door")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.featherOnDoor(used: UnpackedObjType) {
        when (used.id) {
            EpObjs.metal_feather.id -> runQuestSection(null, dialogue, "Using the metal feather on the door")
            EpObjs.eagle_feather.id -> runQuestSection(null, dialogue, "Using an eagle feather on the door")
            EpObjs.bronze_feather.id, EpObjs.silver_feather.id, EpObjs.golden_feather.id -> {
                if (doorOpen()) {
                    runQuestSection(null, dialogue, "Using a feather on the door after it is unlocked")
                    return
                }
                val feather = EpObjs.metal_feathers.first { it.id == used.id }
                invDel(inv, feather)
                when (used.id) {
                    EpObjs.bronze_feather.id -> setBit(EpVarBits.door_bronze, 1)
                    EpObjs.golden_feather.id -> setBit(EpVarBits.door_gold, 1)
                    else -> setBit(EpVarBits.tracking, TRACK_DOOR)
                }
                val section =
                    if (doorOpen()) {
                        "When using the last remaining feather on the door"
                    } else {
                        "When using any of the metal feathers on the door"
                    }
                runQuestSection(null, dialogue, section)
            }
            else -> mes("Nothing interesting happens.")
        }
    }

    private fun ProtectedAccess.takeMetalFeather(feather: ObjType, inDoor: Boolean, name: String) {
        if (owns(feather) || inDoor) {
            mes("I already have a $name feather like that.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, feather)
    }

    /* Bronze feather */

    private fun ProtectedAccess.springNetTrap() {
        if (bit(EpVarBits.net_trap) == 1) return
        mes("As you reach for the bronze feather, the netting on the ground shoots upwards, snatching the pedestal out of your reach.")
        setBit(EpVarBits.net_trap, 1)
        syncBronze()
    }

    private fun ProtectedAccess.operateWinch(index: Int) {
        if (bit(EpVarBits.net_trap) == 0) {
            mes("The winch won't budge.") // Authored (Wiki: {{tmissing}}).
            return
        }
        val winch = EpVarBits.winches[index]
        if (bit(winch) == 1) {
            mes("The winch doesn't seem to turn any further.")
            return
        }
        setBit(winch, 1)
        mes("You wind the winch until it won't go any further.")
        if (bit(EpVarBits.all_winches) == ALL_WINCHES) {
            mes("The net trap gives way and the pedestal drops back to the ground.") // Authored (not Wiki).
            syncBronze()
        }
    }

    /** The trap pedestal for the varbits: untouched, netted, or lowered with the feather. */
    private fun ProtectedAccess.syncBronze() {
        val netted = bit(EpVarBits.net_trap) == 1
        val lowered = bit(EpVarBits.all_winches) == ALL_WINCHES
        val trap = if (!netted) EpLocs.net_trap_inactive else null
        val centre =
            when {
                lowered -> EpLocs.pedestal_bronze
                netted -> EpLocs.net_trap_active
                else -> null
            }
        setLoc(NET_TRAP_SW, trap, listOf(EpLocs.net_trap_inactive), LocShape.CentrepieceStraight, LocAngle.West)
        setLoc(
            NET_TRAP_CENTRE,
            centre,
            listOf(EpLocs.pedestal_bronze, EpLocs.net_trap_active),
            LocShape.CentrepieceStraight,
            LocAngle.West,
        )
    }

    /* Silver feather */

    private val ProtectedAccess.tracking: Int
        get() = bit(EpVarBits.tracking)

    private suspend fun ProtectedAccess.inspectSilverPedestal() {
        if (tracking > 0) {
            runQuestSection(null, dialogue, "Inspecting the stone pedestal again")
            return
        }
        val skilled = stat(stats.hunter) >= HUNTER_LEVEL
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is below level 27 in Hunter:" to { !skilled },
                        "If the player is level 27 or above in Hunter:" to { skilled },
                    )
            )
        runQuestSection(null, dialogue, "When inspecting the stone pedestal", hooks)
        if (skilled) setBit(EpVarBits.tracking, TRACK_PEDESTAL)
    }

    private suspend fun ProtectedAccess.inspectRock1() {
        when {
            tracking == TRACK_PEDESTAL -> {
                runQuestSection(null, dialogue, "When inspecting the first rock")
                setBit(EpVarBits.tracking, TRACK_ROCK1)
            }
            tracking > TRACK_PEDESTAL ->
                mes("Apart from the unusual tracks you found earlier, there is little else to find amongst these rocks.")
            else -> mes(PLAIN_ROCKS)
        }
    }

    private suspend fun ProtectedAccess.inspectRock2() {
        when {
            tracking == TRACK_ROCK1 -> {
                runQuestSection(null, dialogue, "When inspecting the second rock")
                setBit(EpVarBits.tracking, TRACK_ROCK2)
            }
            tracking > TRACK_ROCK1 ->
                mes("Apart from the unusual tracks you found earlier, there is little else to find amongst these rocks.")
            else -> mes(PLAIN_ROCKS)
        }
    }

    private suspend fun ProtectedAccess.inspectOpening() {
        val t = tracking
        when {
            t < TRACK_ROCK2 -> mes("It's a narrow opening in the rock. You can't see anything inside.") // Authored (not Wiki).
            t <= TRACK_OPENING -> {
                val existing = kebbits[player.uid.packed]
                if (existing != null && existing.isSlotAssigned) return
                mes("You peer down the tunnel for signs of the mysterious creature.")
                say("Er...")
                delay(1)
                setBit(EpVarBits.tracking, TRACK_OPENING)
                val spot = if (player.coords.x == KEBBIT_SPOT.x) KEBBIT_SPOT.translateX(2) else KEBBIT_SPOT
                val kebbit = Npc(npcTypes[EpNpcs.kebbit], spot)
                npcRepo.add(kebbit, KEBBIT_TICKS)
                kebbits[player.uid.packed] = kebbit
                kebbit.opPlayer2(player, aiInteractions)
            }
            owns(EpObjs.silver_feather) || t == TRACK_DOOR ->
                mes("This tunnel seems to get thinner quite quickly. I doubt a human could fit down it.")
            else -> {
                runQuestSection(null, dialogue, "Reclaiming the silver feather if it has been lost")
                invAddOrDrop(objRepo, EpObjs.silver_feather)
            }
        }
    }

    private suspend fun ProtectedAccess.threatenKebbit(npc: Npc) {
        var taunt = false
        val hooks = SimpleQuestHooks(options = mapOf("Taunt the kebbit." to { taunt = true }))
        runQuestSection(npc, dialogue, "Threatening the Kebbit", hooks)
        if (!taunt || !npc.isSlotAssigned) return
        val at = npc.coords
        kebbits.remove(player.uid.packed)
        npcRepo.del(npc, Int.MAX_VALUE)
        dropSilverFeather(player, at)
    }

    private fun kebbitKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != EpNpcs.kebbit.id) return
        kebbits.remove(event.hero.uid.packed)
        dropSilverFeather(event.hero, event.npc.coords)
    }

    private fun dropSilverFeather(player: Player, at: CoordGrid) {
        if (player.vars[EpVarBits.tracking] != TRACK_OPENING) return
        VarPlayerIntMapSetter.set(player, EpVarBits.tracking, TRACK_FEATHER)
        objRepo.add(EpObjs.silver_feather, at, FEATHER_DROP_TICKS, receiver = player)
    }

    /* Golden feather */

    private fun ProtectedAccess.takeBirdseed() {
        if (inv.isFull() && !carries(EpObjs.bird_seed)) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, EpObjs.bird_seed)
        mes("You take a handful of the birdseed-like substance from the holder.")
    }

    private fun ProtectedAccess.leverDown(index: Int): Boolean = bit(EpVarBits.levers[index]) == 1

    /** Stone gate [gate] (0-4) is open when its levers have been pulled an odd number of times. */
    private fun ProtectedAccess.gateOpen(gate: Int): Boolean =
        GATE_LEVERS[gate].count { leverDown(it) } % 2 == 1

    private fun ProtectedAccess.birdMoved(bird: Int): Boolean = bit(EpVarBits.birds[bird]) == 1

    private fun ProtectedAccess.pullLever(lever: BoundLocInfo) {
        val index = EpLocs.levers.indexOfFirst { it.id == lever.id }
        if (index < 0) return
        val varbit = EpVarBits.levers[index]
        setBit(varbit, 1 - bit(varbit))
        mes("As you move the lever you hear a low grinding elsewhere in the caves.")
        syncGold()
    }

    private fun ProtectedAccess.useOnFeeder(feeder: Int, used: UnpackedObjType) {
        if (used.id != EpObjs.bird_seed.id) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, EpObjs.bird_seed)
        val moved = feedBird(feeder)
        if (!moved) {
            mes("Putting feed into this feeder seems to have no effect.")
            return
        }
        mes("With a whirr, a metal bird slides along its rail towards the feeder.") // Authored (not Wiki).
        syncGold()
    }

    /**
     * Moves the bird whose rail ends at [feeder] there, if it isn't there already, no closed gate
     * crosses its rail and (for the shared corner) the corner is free. Returns whether one moved.
     */
    private fun ProtectedAccess.feedBird(feeder: Int): Boolean {
        val cornerFree = birdMoved(BIRD_CORNER) && !birdMoved(BIRD_CORRIDOR)
        fun move(bird: Int, moved: Boolean): Boolean {
            if (birdMoved(bird) == moved) return false
            val gate = BIRD_GATE[bird]
            if (gate != null && !gateOpen(gate)) return false
            setBit(EpVarBits.birds[bird], if (moved) 1 else 0)
            return true
        }
        return when (feeder) {
            FEEDER_1 -> move(BIRD_NW, true)
            FEEDER_1A -> move(BIRD_NW, false)
            FEEDER_2A -> move(BIRD_CORNER, true)
            FEEDER_2B -> move(BIRD_CORRIDOR, false)
            FEEDER_2 ->
                (cornerFree && move(BIRD_CORRIDOR, true)) ||
                    (cornerFree && move(BIRD_CORNER, false))
            FEEDER_3 -> move(BIRD_EAST, true)
            FEEDER_3A -> move(BIRD_EAST, false)
            FEEDER_4 -> move(BIRD_SE, true)
            FEEDER_4A -> move(BIRD_SE, false)
            else -> false
        }
    }

    private suspend fun ProtectedAccess.pullResetLever() {
        var pull = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes, pull the reset lever." to { pull = true }))
        runQuestSection(null, dialogue, "Using the Reset lever", hooks)
        if (!pull) return
        if (bit(EpVarBits.puzzle1_reset) == 0) {
            mes("You pull the lever, but nothing much seems to happen.")
            return
        }
        setBit(EpVarBits.puzzle1_reset, 0)
        mes("As you move the lever you hear a low grinding elsewhere in the caves.")
        syncGold()
    }

    /** Spawns the stone gates and metal birds for the varbits (the map has none of them). */
    private fun ProtectedAccess.syncGold() {
        for ((gate, tiles) in GATE_TILES.withIndex()) {
            val open = gateOpen(gate)
            val angle = if (gate == GATE_NORTH_ARM) LocAngle.South else LocAngle.West
            for (tile in tiles) {
                setLoc(
                    tile,
                    if (open) EpLocs.gate_wing_open else EpLocs.gate_wing,
                    listOf(EpLocs.gate_wing, EpLocs.gate_wing_open),
                    LocShape.WallStraight,
                    angle,
                )
            }
        }
        // The corner is an end of two rails: work out every occupied end first, then set each
        // distinct tile once.
        val occupied = BIRD_ENDS.withIndex().map { (bird, ends) -> ends[if (birdMoved(bird)) 1 else 0] }.toSet()
        for (end in BIRD_ENDS.flatten().toSet()) {
            setLoc(
                end,
                if (end in occupied) EpLocs.metal_bird else null,
                listOf(EpLocs.metal_bird),
                LocShape.CentrepieceStraight,
                LocAngle.West,
            )
        }
    }

    private fun setLoc(at: CoordGrid, want: LocType?, all: List<LocType>, shape: LocShape, angle: LocAngle) {
        for (type in all) {
            if (type == want) continue
            val existing = locRepo.findExact(at, type) ?: continue
            locRepo.del(existing, Int.MAX_VALUE)
        }
        if (want != null && locRepo.findExact(at, want) == null) {
            locRepo.add(at, want, Int.MAX_VALUE, angle, shape)
        }
    }

    /* The eagle transport */

    private suspend fun ProtectedAccess.quickTravel(eyrie: Eyrie) {
        if (state < COMPLETE) {
            runQuestSection(null, dialogue, "Attempting to travel on a giant eagle")
            return
        }
        if (!carries(EpObjs.rope)) {
            mes("You need a rope to lasso the eagle.") // Authored (not Wiki).
            return
        }
        val atPeak = player.coords.level == PEAK_LEVEL && player.coords.x < PEAK_MAX_X
        mes("You lasso the eagle with your rope and climb onto its back.") // Authored (not Wiki).
        delay(2)
        telejump(if (atPeak) eyrie.landing else eyrie.peakLanding)
    }

    private fun ProtectedAccess.enterDesertEyrie() {
        if (bit(EpVarBits.unblocked_desert) == 0) {
            if (stat(stats.strength) < DESERT_STRENGTH) {
                mes("You need a Strength level of $DESERT_STRENGTH to push the boulder aside.") // Authored (not Wiki).
                return
            }
            mes("You push the boulder out of the way.") // Authored (not Wiki).
            setBit(EpVarBits.unblocked_desert, 1)
        }
        telejump(Eyrie.Desert.inside)
    }

    private fun ProtectedAccess.enterJungleEyrie() {
        if (bit(EpVarBits.jungle_vine) < VINE_TALL) {
            mes("The vine isn't strong enough to climb yet.") // Authored (not Wiki).
            return
        }
        setBit(EpVarBits.unblocked_jungle, 1)
        telejump(Eyrie.Jungle.inside)
    }

    private fun ProtectedAccess.plantCane(used: UnpackedObjType) {
        val cane = EpObjs.vine_props.firstOrNull { it.id == used.id }
        if (cane == null) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, cane)
        setBit(EpVarBits.jungle_vine, VINE_TALL)
        mes("You push the cane into the ground beside the young vine for it to climb.") // Authored (not Wiki).
        mes("Those vines should be worth checking on now.")
    }

    /* Helpers */

    /**
     * Nickolaus's hooks: "below" (rescue -> "Aren't giant eagles mythical?") and the shout menu's
     * "replay" run the first meeting's top-level answer for the option just picked.
     */
    private class NickolausHooks(private val dialogue: QuestDialogue, private val base: SimpleQuestHooks) :
        QuestDialogueHooks {
        private var last: String? = null

        override fun condition(text: String): Boolean = base.condition(text)

        override suspend fun Dialogue.onOption(label: String) {
            last = label
            with(base) { onOption(label) }
        }

        override suspend fun Dialogue.onBox(text: String): Boolean = with(base) { onBox(text) }

        override suspend fun Dialogue.onAction(tag: String) {
            val label =
                when (tag) {
                    "below" -> MYTHICAL
                    "replay" -> REPLAY[last] ?: last
                    else -> null
                }
            if (label == null) {
                with(base) { onAction(tag) }
                return
            }
            val nodes =
                dialogue.section(MEETING).firstOrNull { it is Node.Option && it.label == label }?.children ?: return
            QuestDialogueRunner(this@NickolausHooks).run(this, nodes)
        }

        override fun rewrite(text: String): String = base.rewrite(text)
    }

    private class Box(val x0: Int, val z0: Int, val x1: Int, val z1: Int, val level: Int)

    private fun CoordGrid.inBox(box: Box): Boolean =
        level == box.level && x in box.x0..box.x1 && z in box.z0..box.z1

    /** The eyries: the peak eagle that flies there, its landing spot, the way in and out. */
    private enum class Eyrie(
        val landing: CoordGrid,
        val peakLanding: CoordGrid,
        val inside: CoordGrid,
        val outside: CoordGrid,
    ) {
        Polar(CoordGrid(2726, 10215, 0), CoordGrid(2018, 4957, 3), CoordGrid(2708, 10205, 0), CoordGrid(2744, 3830, 1)),
        Desert(CoordGrid(3421, 9569, 0), CoordGrid(2019, 4957, 3), CoordGrid(3420, 9563, 0), CoordGrid(3405, 3159, 0)),
        Jungle(CoordGrid(2515, 9320, 0), CoordGrid(2021, 4957, 3), CoordGrid(2526, 9325, 0), CoordGrid(2514, 2927, 0));

        fun base(): NpcType =
            when (this) {
                Polar -> EpNpcs.eagle_polar
                Desert -> EpNpcs.eagle_desert
                Jungle -> EpNpcs.eagle_jungle
            }

        fun travel(): NpcType =
            when (this) {
                Polar -> EpNpcs.eagle_topolar
                Desert -> EpNpcs.eagle_todesert
                Jungle -> EpNpcs.eagle_tojungle
            }
    }

    companion object {
        const val STARTED = 5
        const val FEATHER = 10
        const val CAVE_OPEN = 15
        const val HELPING = 20
        const val DISGUISED = 25
        const val FERRET = 30
        const val COMPLETE = 40

        private const val CHAT_MET = 1
        private const val CHAT_ASKED_ASYFF = 2

        private const val TRACK_PEDESTAL = 1
        private const val TRACK_ROCK1 = 2
        private const val TRACK_ROCK2 = 3
        private const val TRACK_OPENING = 4
        private const val TRACK_FEATHER = 5
        private const val TRACK_DOOR = 6

        private const val ALL_WINCHES = 15
        private const val VINE_TALL = 3

        private const val HUNTER_LEVEL = 27
        private const val HUNTER_XP = 2500.0 // Cache db stat_xp_awarded hunter 25000 (tenths); Wiki 2,500.
        private const val DESERT_STRENGTH = 45
        private const val COSTUME_FEATHERS = 10
        private const val COSTUME_PRICE = 50
        private const val SPARE_COSTUME_FEATHERS = 5
        private const val SPARE_COSTUME_PRICE = 25
        private const val SHOUT_RANGE = 12
        private const val TALK_RANGE = 2
        private const val KEBBIT_TICKS = 300
        private const val FEATHER_DROP_TICKS = 200
        private const val PEAK_LEVEL = 3
        private const val PEAK_MAX_X = 2100

        private const val MEETING = "Approaching Nickolaus across the chasm"
        private const val MYTHICAL = "Aren't giant eagles mythical?"
        private val REPLAY = mapOf("I've come to rescue you." to "Oh, but I'd come to rescue you.")
        private const val PLAIN_ROCKS = "Closer inspection reveals the rocks to be hard, grey and generally just rock-like."

        /* Positions from the rev 243 map (tools/agility/maplocs.py) and its tile flags. */
        /** In front of the summit outcrop (2328,3494) and inside by the cave exit (1993,4983). */
        private val CAVE_OUTSIDE = CoordGrid(2327, 3496, 0)
        private val CAVE_INSIDE = CoordGrid(1995, 4983, 3)
        /** The nest (Nickolaus at 2006,4959) and the ledge either side of the guard eagle (2007,4954). */
        private val NEST = Box(2004, 4957, 2009, 4961, 3)
        private val NEST_SIDE = CoordGrid(2007, 4957, 3)
        private val LEDGE_SIDE = CoordGrid(2007, 4953, 3)
        /** Tunnel mouths: cave side (west/east of the tunnel locs) and puzzle-room side. */
        private val GOLD_RETURN = CoordGrid(2022, 4982, 3)
        private val GOLD_ARRIVAL = CoordGrid(1957, 4908, 2)
        private val SILVER_RETURN = CoordGrid(1987, 4972, 3)
        private val SILVER_ARRIVAL = CoordGrid(1947, 4868, 2)
        private val BRONZE_RETURN = CoordGrid(1987, 4949, 3)
        private val BRONZE_ARRIVAL = CoordGrid(1974, 4908, 2)
        /** The net trap (3x3) in the middle of the winch room and its centre tile. */
        private val NET_TRAP_SW = CoordGrid(1973, 4913, 2)
        private val NET_TRAP_CENTRE = CoordGrid(1974, 4914, 2)
        /** In front of the kebbit's opening (1971,4886). */
        private val KEBBIT_SPOT = CoordGrid(1970, 4885, 2)

        /* The golden feather room. Feeders in EpLocs.feeders order. */
        private const val FEEDER_1 = 0
        private const val FEEDER_1A = 1
        private const val FEEDER_2 = 2
        private const val FEEDER_2A = 3
        private const val FEEDER_2B = 4
        private const val FEEDER_3 = 5
        private const val FEEDER_3A = 6
        private const val FEEDER_4 = 7
        private const val FEEDER_4A = 8

        /** Birds (`mechbird1..5`): NW rail, corner (west arm), corridor (north arm), east, south-east. */
        private const val BIRD_NW = 0
        private const val BIRD_CORNER = 1
        private const val BIRD_CORRIDOR = 2
        private const val BIRD_EAST = 3
        private const val BIRD_SE = 4

        /** Each bird's rail ends (2x2 south-west tiles): start end, then the other. */
        private val BIRD_ENDS =
            listOf(
                listOf(CoordGrid(1931, 4914, 2), CoordGrid(1943, 4914, 2)),
                listOf(CoordGrid(1945, 4898, 2), CoordGrid(1935, 4898, 2)),
                listOf(CoordGrid(1945, 4906, 2), CoordGrid(1945, 4898, 2)),
                listOf(CoordGrid(1963, 4900, 2), CoordGrid(1963, 4894, 2)),
                listOf(CoordGrid(1971, 4890, 2), CoordGrid(1967, 4890, 2)),
            )

        /** Gates: south (west arm), east, north arm, north-west inner, north-west outer. */
        private const val GATE_SOUTH = 0
        private const val GATE_NORTH_ARM = 2
        private val GATE_TILES =
            listOf(
                listOf(CoordGrid(1938, 4898, 2), CoordGrid(1938, 4899, 2)),
                listOf(CoordGrid(1954, 4900, 2), CoordGrid(1954, 4901, 2)),
                listOf(CoordGrid(1945, 4901, 2), CoordGrid(1946, 4901, 2)),
                listOf(CoordGrid(1929, 4914, 2), CoordGrid(1929, 4915, 2)),
                listOf(CoordGrid(1927, 4914, 2), CoordGrid(1927, 4915, 2)),
            )

        /**
         * The levers (0 south-west, 1 north-west, 2 centre, 3 east) toggling each gate, after the
         * Wiki's table: centre -> east + north-west, east -> north-west, south-west -> north-west +
         * south, north-west -> the corridor (north arm).
         */
        private val GATE_LEVERS =
            listOf(listOf(0), listOf(2), listOf(1), listOf(2, 3), listOf(0))

        /** The gate a bird's travel crosses (the corner and corridor birds' rails). */
        private val BIRD_GATE = listOf(null, GATE_SOUTH, GATE_NORTH_ARM, null, null)

        val EAGLES_PEAK =
            QuestDef(
                name = "Eagles' Peak",
                varbit = { EpVarBits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("2,500 Hunter XP", "A box trap", "Access to the eagle transport system"),
                scrollObj = { EpObjs.ferret },
            )
    }
}

// Every cache ref Eagles' Peak uses (re-check after cache updates).

internal object EpNpcs : NpcReferences() {
    val charlie = find("eaglepeak_zookeeper_charlie")
    val asyff = find("tailorp")
    val nickolaus = find("eaglepeak_nickolaus")
    val nickolaus_campsite = find("eaglepeak_nickolaus_campsite")
    /** Both multinpcs and every child (the camp one resolves to `_normal` too). */
    val nickolaus_all =
        listOf(
            nickolaus,
            nickolaus_campsite,
            find("eaglepeak_nickolaus_shout"),
            find("eaglepeak_nickolaus_normal"),
            find("eaglepeak_nickolaus_disguise"),
        )
    val eagle_guard = find("eaglepeak_eagle_guard")
    val eagle_polar = find("eaglepeak_eagle_polar")
    val eagle_desert = find("eaglepeak_eagle_desert")
    val eagle_jungle = find("eaglepeak_eagle_jungle")
    val eagle_topolar = find("eaglepeak_eagle_topolar")
    val eagle_todesert = find("eaglepeak_eagle_todesert")
    val eagle_tojungle = find("eaglepeak_eagle_tojungle")
    val kebbit = find("eaglepeak_uber_kebbit")
}

internal object EpObjs : ObjReferences() {
    val bird_book = find("hunting_book_of_birds")
    val metal_feather = find("eaglepeak_metal_feather")
    val golden_feather = find("eaglepeak_crystal_feather1")
    val silver_feather = find("eaglepeak_crystal_feather2")
    val bronze_feather = find("eaglepeak_crystal_feather3")
    val metal_feathers = listOf(bronze_feather, silver_feather, golden_feather)
    val bird_seed = find("eaglepeak_bird_seed")
    val eagle_feather = find("hunting_eagle_feather")
    val eagle_cape = find("hunting_eagle_cape")
    val fake_beak = find("hunting_fake_beak")
    val ferret = find("hunting_ferret")
    val box_trap = find("hunting_box_trap")
    val swamp_tar = find("swamp_tar")
    val yellow_dye = find("yellowdye")
    val coins = find("coins")
    val rope = find("rope")
    /** What the Feldip young vine can climb: teasing stick, garden cane, thatching spar. */
    val vine_props = listOf(find("hunting_teasing_stick"), find("fenk_cane"), find("thatching_spar_light"))
}

internal object EpLocs : LocReferences() {
    val campfire_messy = find("eaglepeak_campfire_messy")
    val books_messy = find("eaglepeak_books_messy")
    val backpack_messy = find("eaglepeak_backpack_equip_messy")
    val outcrop = find("eaglepeak_new_cave_entrance")
    val outcrop_open = find("eaglepeak_new_cave_entrance_open")
    val cave_exit = find("eaglepeak_human_exitmid")
    val puzzle1_entrance = find("eaglepeak_puzzle1_entrancemid")
    val puzzle1_exit = find("eaglepeak_puzzle1_exitmid")
    val puzzle2_entrance = find("eaglepeak_puzzle2_entrancemid")
    val puzzle2_exit = find("eaglepeak_puzzle2_exitmid")
    val puzzle3_entrance = find("eaglepeak_puzzle3_entrancemid")
    val puzzle3_exit = find("eaglepeak_puzzle3_exitmid")
    val feather_pile = find("eaglepeak_feather_pile")
    /** The eagle door's two halves as the multilocs show them (silver / bronze + golden). */
    val doors =
        listOf(
            find("eaglepeak_gate_0_feather"),
            find("eaglepeak_gate_1_feather"),
            find("eaglepeak_gate_0_feather_mirror"),
            find("eaglepeak_gate_1_feather_mirror"),
            find("eaglepeak_gate_2_feather_mirror"),
        )
    val net_trap_inactive = find("eaglepeak_net_trap_inactive")
    val net_trap_active = find("eaglepeak_net_trap_active")
    val pedestal_bronze = find("eaglepeak_dungeon_pedestal_puzzle3")
    val winches =
        listOf(
            find("eaglepeak_winch1"),
            find("eaglepeak_winch2"),
            find("eaglepeak_winch3"),
            find("eaglepeak_winch4"),
        )
    val pedestal_silver = find("eaglepeak_dungeon_pedestal_puzzle2")
    val trail_rock1 = find("eaglepeak_hunting_trail_spawn1")
    val trail_rock2 = find("eaglepeak_hunting_trail_spawn2")
    val trail_dummies =
        listOf(
            find("eaglepeak_hunting_trail_spawn_dummy"),
            find("eaglepeak_hunting_trail_spawn_dummy2"),
            find("eaglepeak_hunting_trail_spawn_dummy3"),
        )
    val kebbit_opening = find("eaglepeak_kebbit_cavemid")
    val birdseed_holder = find("eaglepeak_birdseed_dispenser")
    /** Feeders 1, 1a, 2, 2a, 2b, 3, 3a, 4, 4a (see EaglesPeak.FEEDER_*). */
    val feeders =
        listOf(
            find("eaglepeak_bird_feeder1"),
            find("eaglepeak_bird_feeder1a"),
            find("eaglepeak_bird_feeder2"),
            find("eaglepeak_bird_feeder2a"),
            find("eaglepeak_bird_feeder2b"),
            find("eaglepeak_bird_feeder3"),
            find("eaglepeak_bird_feeder3a"),
            find("eaglepeak_bird_feeder4"),
            find("eaglepeak_bird_feeder4a"),
        )
    /** The four lever multilocs (south-west, north-west, centre, east) and what they show. */
    val levers =
        listOf(
            find("eaglepeak_puzzle1_lever1"),
            find("eaglepeak_puzzle1_lever2"),
            find("eaglepeak_puzzle1_lever3"),
            find("eaglepeak_puzzle1_lever4"),
        )
    val lever_down = find("eaglepeak_eagle_lever1")
    val lever_up = find("eaglepeak_eagle_lever2")
    val reset_lever = find("eaglepeak_reset_lever")
    val pedestal_gold = find("eaglepeak_dungeon_pedestal_puzzle1")
    val gate_wing = find("eaglepeak_eagle_gate_wing")
    val gate_wing_open = find("eaglepeak_eagle_gate_wing_open")
    val metal_bird = find("eaglepeak_mechanical_bird")
    val polar_cave_in = find("eaglepeak_cavein_ice")
    val polar_cave_out = find("eaglepeak_ice_cave_entrance")
    val desert_cave_in = find("eaglepeak_cavein_desert")
    val desert_cave_out = find("eaglepeak_desert_cave_exit")
    val jungle_cave_in = find("eaglepeak_cavein_jungle")
    val jungle_cave_out = find("eaglepeak_jungle_cave_entrance")
    /** The Feldip vine multiloc's children (`eaglepeak_jungle_vine` 0-3); the young one takes a cane. */
    val vines =
        listOf(
            find("eagles_peak_vine_small"),
            find("eagles_peak_vine_small_w_cane"),
            find("eagles_peak_vine_medium"),
            find("eagles_peak_vine_tall"),
        )
}

internal object EpVarBits : VarBitReferences() {
    val quest = find("eaglepeak_quest")
    val nickolaus_chat = find("eaglepeak_nickolaus_chat")
    val entered_puzzle1 = find("eaglepeak_entered_puzzle1")
    val entered_puzzle2 = find("eaglepeak_entered_puzzle2")
    val entered_puzzle3 = find("eaglepeak_entered_puzzle3")
    val door_bronze = find("eaglepeak_eagledoor_feather1")
    val door_gold = find("eaglepeak_eagledoor_feather3")
    val tracking = find("eaglepeak_puzzle2_tracking")
    val net_trap = find("eaglepeak_puzzle3_nettrap")
    val all_winches = find("eaglepeak_puzzle3_allwinches")
    val winches =
        listOf(
            find("eaglepeak_puzzle3_winch1"),
            find("eaglepeak_puzzle3_winch2"),
            find("eaglepeak_puzzle3_winch3"),
            find("eaglepeak_puzzle3_winch4"),
        )
    /** The levers' own multivars, south-west / north-west / centre / east. */
    val levers =
        listOf(
            find("eaglepeak_puzzle1_gate1"),
            find("eaglepeak_puzzle1_gate2"),
            find("eaglepeak_puzzle1_gate3"),
            find("eaglepeak_puzzle1_gate4"),
        )
    val birds =
        listOf(
            find("eaglepeak_puzzle1_mechbird1"),
            find("eaglepeak_puzzle1_mechbird2"),
            find("eaglepeak_puzzle1_mechbird3"),
            find("eaglepeak_puzzle1_mechbird4"),
            find("eaglepeak_puzzle1_mechbird5"),
        )
    val puzzle1_reset = find("eaglepeak_puzzle1_reset")
    val unblocked_desert = find("eaglepeak_unblocked_desert")
    val unblocked_jungle = find("eaglepeak_unblocked_jungle")
    val jungle_vine = find("eaglepeak_jungle_vine")
}
