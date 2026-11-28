package org.rsmod.content.quests.elementalworkshop2

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.elementalworkshop1.ElementalWorkshopI.Companion.ELEMENTAL_WORKSHOP_I
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Elemental Workshop II (OSRS Wiki). It continues Elemental Workshop I's quest varbit
 * `elementalworkshop` (2 = EW1 done, 11 = EW2 done). The machinery state lives in the cache's
 * `elemental_quest_2_*` varbits, so the multilocs (hatch, cogs, fan, extractor gun, piping) and the
 * jig cart multinpc show each player's own state.
 * - Search the Exam Centre bookcase: the beaten book and a scroll. Read the scroll.
 * - Search the machinery in EW1's water room for the key; use it on the hatch; climb down.
 * - The schematic crate: crane and lever schematics. Read both.
 * - An elemental bar on the workbench upstairs (crane schematic): the crane claw; fit it.
 * - Catwalk: the junction box; the pipe (from a crate) on the broken piping.
 * - Cogs (from crates) on the fan pins: small high, medium low, large left.
 * - Bar on the jig cart; crane: raise (grab), rotate, lower and raise (lava), rotate, lower
 *   (release), raise. Cart lever: the press (flatten), the water tank (cool), the fan (dry).
 * - Take the primed bar; the mind room: bar in the extractor gun, operate the hat (-20 Magic),
 *   take the primed mind bar. Workbench with a hammer and the beaten book: the mind helmet.
 *
 * Simplified (Authored, not Wiki): the crane and the cart don't animate or move (the crane is a
 * single shared loc, so its state is told in messages); the junction box is a stand-in that wires
 * the pipes as the lever schematic shows; which crate holds which part is fixed.
 */
class ElementalWorkshopII
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onLocOpLabel(locTypes, Ew2Locs.bookcase, "Search") { searchBookcase() }
        onOpHeld1(Ew2Objs.beaten_book) { readBeatenBook() }
        onOpHeld1(Ew2Objs.scroll) { readScroll() }
        onOpHeld1(Ew2Objs.crane_schematic) { readCraneSchematic() }
        onOpHeld1(Ew2Objs.lever_schematic) { readLeverSchematic() }

        onLocOpLabel(locTypes, Ew2Locs.machinery, "Search") { searchMachinery() }
        onOpLocU(Ew2Locs.hatch_closed, Ew2Objs.key) { unlockHatch() }
        onLocOpLabel(locTypes, Ew2Locs.hatch_closed, "Open") {
            mes("The hatch is locked.") // Authored (not Wiki).
        }
        onLocOpLabel(locTypes, Ew2Locs.hatch_open, "Climb-down") { telejump(WORKSHOP_ARRIVE) }
        onLocOpLabel(locTypes, Ew2Locs.stairs_up, "Climb-up") { telejump(EW1_ARRIVE) }
        onLocOpLabel(locTypes, Ew2Locs.gantry_stairs, "Climb-up") { gantry(it, up = true) }
        onLocOpLabel(locTypes, Ew2Locs.gantry_stairs_top, "Climb-down") { gantry(it, up = false) }
        onLocOpLabel(locTypes, Ew2Locs.stairwell, "Climb-down") { telejump(MIND_ROOM_ARRIVE) }
        onLocOpLabel(locTypes, Ew2Locs.mind_stairs, "Climb-up") { telejump(STAIRWELL_ARRIVE) }
        for (door in Ew2Locs.mind_doors) onLocOpLabel(locTypes, door, "Open") { passThroughWall(it) }

        onLocOpLabel(locTypes, Ew2Locs.schematic_crate, "Take-from") { takeSchematics() }
        for ((i, crate) in Ew2Locs.crates.withIndex()) {
            onLocOpLabel(locTypes, crate, "Search") { searchCrate(i + 1) }
        }

        onOpLocU(Ew2Locs.crane, Ew2Objs.claw) { fitClaw() }
        onLocOpLabel(locTypes, Ew2Locs.crane_lift_lever, "Pull") { liftCrane() }
        onLocOpLabel(locTypes, Ew2Locs.crane_rotate_lever, "Pull") { rotateCrane() }
        onOpNpcU(Ew2Npcs.cart_empty, Ew2Objs.bar) { placeBar() }
        for (cart in Ew2Npcs.cart_full) onNpcOpLabel(npcTypes, cart, "Take-from") { takeFromCart() }
        onLocOpLabel(locTypes, Ew2Locs.cart_lever, "Pull") { moveCart() }

        onLocOpLabel(locTypes, Ew2Locs.junction_box, "Open") { openJunctionBox() }
        onLocOpLabel(locTypes, Ew2Locs.press_lever, "Pull") { pullPressLever() }

        onOpLocU(Ew2Locs.broken_piping, Ew2Objs.pipe) { repairPiping() }
        onLocOpLabel(locTypes, Ew2Locs.water_lever, "Pull") { pullWaterLever() }
        onLocOpLabel(locTypes, Ew2Locs.corkscrew, "Turn") { turnCorkscrew() }
        onLocOpLabel(locTypes, Ew2Locs.fill_valve, "Turn") { turnValve(fill = true) }
        onLocOpLabel(locTypes, Ew2Locs.drain_valve, "Turn") { turnValve(fill = false) }

        for ((pin, varbit) in Ew2Locs.pins.zip(Ew2VarBits.cogs)) {
            for ((size, cog) in Ew2Objs.cogs.withIndex()) {
                onOpLocU(pin, cog) { placeCog(varbit, size + 1) }
            }
        }
        for ((pin, cogs) in Ew2Locs.pinCogs.withIndex()) {
            for (cog in cogs) onLocOpLabel(locTypes, cog, "Take") { takeCog(Ew2VarBits.cogs[pin]) }
        }
        onLocOpLabel(locTypes, Ew2Locs.air_lever, "Pull") { pullAirLever() }

        onOpLocU(Ew2Locs.gun_empty, Ew2Objs.primed_bar) { placePrimedBar() }
        onLocOpLabel(locTypes, Ew2Locs.gun_bar, "Take-from") { takeFromGun(mind = false) }
        onLocOpLabel(locTypes, Ew2Locs.gun_mind_bar, "Take-from") { takeFromGun(mind = true) }
        onLocOpLabel(locTypes, Ew2Locs.extractor_hat, "Operate") { operateHat() }
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(ELEMENTAL_WORKSHOP_II)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(ELEMENTAL_WORKSHOP_II, to)
    }

    private suspend fun ProtectedAccess.section(title: String, full: Boolean = false) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player has a full inventory:" to { full }, "Otherwise:" to { !full })
            )
        runQuestSection(null, dialogue, title, hooks)
    }

    /* Starting out */

    private suspend fun ProtectedAccess.searchBookcase() {
        if (stage >= STARTED) {
            if (!carries(Ew2Objs.beaten_book) && !player.isQuestComplete(ELEMENTAL_WORKSHOP_II)) {
                invAddOrDrop(objRepo, Ew2Objs.beaten_book)
                mes("You find an old-looking book.")
            } else {
                // Authored line (not Wiki).
                mes("You search the bookcase but find nothing else of interest.")
            }
            return
        }
        if (
            !player.isQuestComplete(ELEMENTAL_WORKSHOP_I) ||
                stat(stats.smithing) < SMITHING_REQUIRED ||
                stat(stats.magic) < MAGIC_REQUIRED
        ) {
            // Authored line (not Wiki): the quest's requirements.
            mes(
                "You need to have completed Elemental Workshop I, and have $SMITHING_REQUIRED " +
                    "Smithing and $MAGIC_REQUIRED Magic, to start this quest."
            )
            return
        }
        val full = inv.freeSpace() < 2
        var start = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player has a full inventory:" to { full }, "Otherwise:" to { !full }),
                options = mapOf("Yes." to { start = !full }),
            )
        runQuestSection(null, dialogue, "Searching the Bookcase", hooks)
        if (start) {
            invAdd(inv, Ew2Objs.beaten_book)
            invAdd(inv, Ew2Objs.scroll)
            advance(STARTED)
        }
    }

    private suspend fun ProtectedAccess.readBeatenBook() {
        mes("The book has two parts: an introduction and an instruction section.")
        mes("You flip the book open to the introduction and start reading.")
        // Authored (not Wiki): the book's text, condensed.
        mesbox(
            "The elemental ore can be primed by heat, pressure, water and air, and a primed bar " +
                "takes on the power of the mind. The instructions describe how to work a primed " +
                "mind bar into a helmet."
        )
    }

    private suspend fun ProtectedAccess.readScroll() {
        // Authored (not Wiki): the scroll's hint, condensed.
        mesbox(
            "The slip of paper is covered in notes. One of them mentions a key, hidden in the " +
                "third pipe of the machinery in the workshop's water room."
        )
        if (stage >= STARTED) setFlag(Ew2VarBits.hide_key, 1)
    }

    private suspend fun ProtectedAccess.readCraneSchematic() {
        section("Reading the crane schematic")
        setFlag(Ew2VarBits.box_state, flag(Ew2VarBits.box_state) or READ_CRANE)
    }

    private suspend fun ProtectedAccess.readLeverSchematic() {
        // Authored (not Wiki): the lever schematic's text.
        mesbox(
            "The schematic shows how the press is driven by steam from the junction box on the " +
                "catwalk, and how the levers and cogs of the fan should be set."
        )
        setFlag(Ew2VarBits.box_state, flag(Ew2VarBits.box_state) or READ_LEVER)
    }

    /* Into the workshop */

    private suspend fun ProtectedAccess.searchMachinery() {
        if (carries(Ew2Objs.key) || flag(Ew2VarBits.hatch) == 1) {
            mes("You search the machinery but find nothing else.") // Authored (not Wiki).
            return
        }
        val full = inv.isFull()
        section("Searching Machinery for the key", full)
        if (!full) {
            invAdd(inv, Ew2Objs.key)
            advance(KEY)
        }
    }

    private suspend fun ProtectedAccess.unlockHatch() {
        section("Unlocking the hatch")
        setFlag(Ew2VarBits.hatch, 1)
        advance(HATCH)
    }

    private fun ProtectedAccess.gantry(loc: BoundLocInfo, up: Boolean) {
        val west = loc.coords.x < GANTRY_SPLIT_X
        telejump(
            when {
                up && west -> GANTRY_WEST_TOP
                up -> GANTRY_EAST_TOP
                west -> GANTRY_WEST_BOTTOM
                else -> GANTRY_EAST_BOTTOM
            }
        )
    }

    /* Schematics and crates */

    private suspend fun ProtectedAccess.takeSchematics() {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Crane schematic" to { invAddOrDrop(objRepo, Ew2Objs.crane_schematic) },
                        "Lever schematic" to { invAddOrDrop(objRepo, Ew2Objs.lever_schematic) },
                    )
            )
        runQuestSection(null, dialogue, "Schematic crate", hooks)
    }

    private fun ProtectedAccess.searchCrate(box: Int) {
        val read = flag(Ew2VarBits.box_state) and (READ_CRANE or READ_LEVER)
        val part = CRATE_PARTS[box]
        val needed =
            part != null &&
                read == READ_CRANE or READ_LEVER &&
                !carries(part.obj) &&
                when (part) {
                    Part.PIPE -> flag(Ew2VarBits.water_state) == 0
                    else -> Ew2VarBits.cogs.none { flag(it) == part.size }
                }
        if (!needed || part == null) {
            mes("It's empty.")
            return
        }
        invAddOrDrop(objRepo, part.obj)
        mes(part.found)
    }

    /* The crane */

    private suspend fun ProtectedAccess.fitClaw() {
        if (flag(Ew2VarBits.fire_state) != CRANE_BROKEN) {
            mes("The crane already has a working claw.") // Authored (not Wiki).
            return
        }
        if (flag(Ew2VarBits.fire_pos) and CRANE_DOWN == 0) {
            mes("You can't reach the claw while the crane is raised.") // Authored (not Wiki).
            return
        }
        invDel(inv, Ew2Objs.claw)
        section("Repairing the claw")
        setFlag(Ew2VarBits.fire_state, CRANE_EMPTY)
        advance(CLAW)
    }

    private fun ProtectedAccess.liftCrane() {
        val pos = flag(Ew2VarBits.fire_pos) xor CRANE_DOWN
        setFlag(Ew2VarBits.fire_pos, pos)
        val down = pos and CRANE_DOWN != 0
        val overLava = pos and CRANE_OVER_LAVA != 0
        val crane = flag(Ew2VarBits.fire_state)
        val cartHere = flag(Ew2VarBits.jig_pos) == AT_CRANE
        val jig = flag(Ew2VarBits.jig_state)
        // Authored lines (not Wiki): the crane's movements.
        mes(if (down) "You pull the lever and the crane lowers." else "You pull the lever and the crane rises.")
        when {
            down && overLava && crane == CRANE_COOL_BAR -> {
                setFlag(Ew2VarBits.fire_state, CRANE_HOT_BAR)
                mes("The claw dips the elemental bar into the lava. It glows orange.")
            }
            down && !overLava && crane >= CRANE_COOL_BAR && cartHere && jig == JIG_EMPTY -> {
                setFlag(Ew2VarBits.fire_state, CRANE_EMPTY)
                setFlag(Ew2VarBits.jig_state, if (crane == CRANE_HOT_BAR) JIG_HOT_BAR else JIG_BAR)
                mes("The claw releases the elemental bar onto the jig cart.")
            }
            !down && !overLava && crane == CRANE_EMPTY && cartHere && jig == JIG_BAR -> {
                setFlag(Ew2VarBits.fire_state, CRANE_COOL_BAR)
                setFlag(Ew2VarBits.jig_state, JIG_EMPTY)
                mes("The claw lifts the elemental bar off the jig cart.")
            }
        }
    }

    private suspend fun ProtectedAccess.rotateCrane() {
        val pos = flag(Ew2VarBits.fire_pos)
        if (pos and CRANE_DOWN != 0) {
            section("Rotating the claw while the arm is down")
            return
        }
        val turned = pos xor CRANE_OVER_LAVA
        setFlag(Ew2VarBits.fire_pos, turned)
        // Authored lines (not Wiki).
        mes(
            if (turned and CRANE_OVER_LAVA != 0) "The crane swings round over the lava."
            else "The crane swings round over the track."
        )
    }

    /* The jig cart */

    private fun ProtectedAccess.placeBar() {
        if (flag(Ew2VarBits.jig_pos) != AT_CRANE) {
            mes("The jig cart should be under the crane first.") // Authored (not Wiki).
            return
        }
        invDel(inv, Ew2Objs.bar)
        setFlag(Ew2VarBits.jig_state, JIG_BAR)
        mes("You place the elemental bar on the jig cart.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.takeFromCart() {
        when (flag(Ew2VarBits.jig_state)) {
            JIG_BAR -> {
                setFlag(Ew2VarBits.jig_state, JIG_EMPTY)
                invAddOrDrop(objRepo, Ew2Objs.bar)
                mes("You take the elemental bar from the jig cart.") // Authored (not Wiki).
            }
            JIG_PRIMED -> {
                setFlag(Ew2VarBits.jig_state, JIG_EMPTY)
                invAddOrDrop(objRepo, Ew2Objs.primed_bar)
                section("Taking the primed bar")
                advance(PRIMED)
            }
            JIG_HOT_BAR,
            JIG_FLAT_HOT -> mes("The bar is far too hot to touch.") // Authored (not Wiki).
            else -> mes("The bar is still wet. It needs drying first.") // Authored (not Wiki).
        }
    }

    private fun ProtectedAccess.moveCart() {
        if (flag(Ew2VarBits.fire_pos) == CRANE_DOWN) {
            // Authored line (not Wiki): the claw is down over the track.
            mes("The crane's claw is in the way of the cart.")
            return
        }
        val next = (flag(Ew2VarBits.jig_pos) + 1) % STATIONS
        setFlag(Ew2VarBits.jig_pos, next)
        // Authored line (not Wiki).
        mes("You pull the lever and the jig cart rolls along the track to the ${STATION_NAMES[next]}.")
        if (next == AT_FAN) dryBar()
    }

    /* The press */

    private suspend fun ProtectedAccess.openJunctionBox() {
        if (flag(Ew2VarBits.earth_fixed) == 1) {
            mes("The pipes are already connected.") // Authored (not Wiki).
            return
        }
        if (flag(Ew2VarBits.box_state) and READ_LEVER == 0) {
            // Authored line (not Wiki): the stand-in needs the lever schematic.
            mes("You don't know how these pipes should be connected. Maybe a schematic would help.")
            return
        }
        // Stand-in (Authored, not Wiki): the pipes are wired as the lever schematic shows.
        mes("You open the junction box and connect the pipe ends as the lever schematic shows.")
        setFlag(Ew2VarBits.earth_pipe_1, 1)
        setFlag(Ew2VarBits.earth_pipe_2, 2)
        setFlag(Ew2VarBits.earth_pipe_3, 3)
        setFlag(Ew2VarBits.earth_fixed, 1)
        section("Connecting the junction box pipes")
    }

    private suspend fun ProtectedAccess.pullPressLever() {
        val jig = flag(Ew2VarBits.jig_state)
        when {
            flag(Ew2VarBits.earth_pipe_all) == 0 -> section("Puling the press lever while it is broken")
            flag(Ew2VarBits.earth_fixed) == 0 ->
                section("Pulling the press lever with the junction pipes not correctly connected")
            flag(Ew2VarBits.jig_pos) != AT_PRESS -> section("Pulling the press lever at the wrong time")
            jig == JIG_HOT_BAR -> {
                setFlag(Ew2VarBits.jig_state, JIG_FLAT_HOT)
                section("Flattening an elemental bar after heating it")
            }
            jig == JIG_BAR -> {
                // Authored line (not Wiki): a cold bar won't flatten.
                mes("You pull the lever and the press responds, but the cold bar doesn't give.")
            }
            else -> section("Pulling the press lever without a bar in position")
        }
    }

    /* The water tank */

    private suspend fun ProtectedAccess.repairPiping() {
        if (flag(Ew2VarBits.water_state) == 1) return
        invDel(inv, Ew2Objs.pipe)
        setFlag(Ew2VarBits.water_state, 1)
        section("Repairing piping")
    }

    private suspend fun ProtectedAccess.pullWaterLever() {
        val door = flag(Ew2VarBits.water_door)
        if (door and DOOR_OPEN == 0 && flag(Ew2VarBits.water_level) == 1) {
            section("Pulling the lever while the tank is full")
            return
        }
        if (door and RACK_OUT != 0) {
            mes("You should wind the rack back in first.") // Authored (not Wiki).
            return
        }
        setFlag(Ew2VarBits.water_door, door xor DOOR_OPEN)
        // Authored lines (not Wiki).
        mes(
            if (door and DOOR_OPEN == 0) "You pull the lever and the tank door slides open."
            else "You pull the lever and the tank door slides shut."
        )
    }

    private suspend fun ProtectedAccess.turnCorkscrew() {
        val door = flag(Ew2VarBits.water_door)
        if (door and DOOR_OPEN == 0) {
            section("Turning the corkscrew while the door is closer")
            return
        }
        val extending = door and RACK_OUT == 0
        var next = door xor RACK_OUT
        val cartHere = flag(Ew2VarBits.jig_pos) == AT_TANK
        val jig = flag(Ew2VarBits.jig_state)
        // Authored lines (not Wiki): the rack carries the bar between the cart and the tank.
        if (!extending) {
            mes("You turn the corkscrew and the rack winds back in.")
        } else if (door and BAR_IN_TANK != 0 && cartHere && jig == JIG_EMPTY) {
            next = next and (BAR_IN_TANK or BAR_COOLED).inv()
            val cooled = door and BAR_COOLED != 0
            setFlag(Ew2VarBits.jig_state, if (cooled) JIG_FLAT else JIG_FLAT_HOT)
            mes("You turn the corkscrew and the rack returns the bar to the jig cart.")
        } else if (door and BAR_IN_TANK == 0 && cartHere && jig == JIG_FLAT_HOT) {
            next = next or BAR_IN_TANK
            setFlag(Ew2VarBits.jig_state, JIG_EMPTY)
            mes("You turn the corkscrew and the rack lifts the bar from the cart into the tank.")
        } else {
            mes("You turn the corkscrew and the rack extends.")
        }
        setFlag(Ew2VarBits.water_door, next)
    }

    private suspend fun ProtectedAccess.turnValve(fill: Boolean) {
        val valve = if (fill) Ew2VarBits.valve_fill else Ew2VarBits.valve_drain
        if (fill && flag(Ew2VarBits.water_state) == 0) {
            section("Turning the water valve before repairing the pipe")
            return
        }
        val opening = flag(valve) == 0
        if (fill && opening && flag(Ew2VarBits.water_door) and DOOR_OPEN != 0) {
            mes("You should close the tank door first.") // Authored (not Wiki).
            return
        }
        setFlag(valve, if (opening) 1 else 0)
        section(if (opening) "Opening the water valve" else "Closing the water valve")
        if (opening) return
        if (fill) {
            setFlag(Ew2VarBits.water_level, 1)
            val door = flag(Ew2VarBits.water_door)
            if (door and BAR_IN_TANK != 0) setFlag(Ew2VarBits.water_door, door or BAR_COOLED)
            mes("The tank fills with water.") // Authored (not Wiki).
        } else if (flag(Ew2VarBits.water_level) == 1) {
            setFlag(Ew2VarBits.water_level, 0)
            mes("The tank drains.") // Authored (not Wiki).
        }
    }

    /* The fan */

    private suspend fun ProtectedAccess.placeCog(pin: VarBitType, size: Int) {
        if (flag(pin) != 0) {
            mes("There's already a cog on that pin.") // Authored (not Wiki).
            return
        }
        invDel(inv, Ew2Objs.cogs[size - 1])
        setFlag(pin, size)
        section(COG_SECTIONS[size - 1])
    }

    private suspend fun ProtectedAccess.takeCog(pin: VarBitType) {
        if (flag(Ew2VarBits.fan_state) != FAN_OFF) {
            section("Trying to remove a cog while the fan is on")
            return
        }
        val size = flag(pin)
        if (size == 0) return
        setFlag(pin, 0)
        invAddOrDrop(objRepo, Ew2Objs.cogs[size - 1])
    }

    private suspend fun ProtectedAccess.pullAirLever() {
        if (flag(Ew2VarBits.fan_state) != FAN_OFF) {
            setFlag(Ew2VarBits.fan_state, FAN_OFF)
            section("Turning off the fan")
            return
        }
        val cogs = Ew2VarBits.cogs.map { flag(it) }
        if (cogs.any { it == 0 }) {
            // Authored line (not Wiki): the drive has no cogs to turn.
            mes("You pull the lever but nothing happens. The fan's drive is missing some cogs.")
            return
        }
        if (cogs != COG_SOLUTION) {
            setFlag(Ew2VarBits.fan_state, FAN_REVERSE)
            section("Trying to turn on the fan with the cogs placed incorrectly")
            return
        }
        setFlag(Ew2VarBits.fan_state, FAN_BLOW)
        section("Turning on the fan")
        dryBar()
    }

    private fun ProtectedAccess.dryBar() {
        if (
            flag(Ew2VarBits.fan_state) == FAN_BLOW &&
                flag(Ew2VarBits.jig_pos) == AT_FAN &&
                flag(Ew2VarBits.jig_state) == JIG_FLAT
        ) {
            setFlag(Ew2VarBits.jig_state, JIG_PRIMED)
            mes("The fan blows the bar dry. It turns white.") // Authored (not Wiki).
        }
    }

    /* The mind room */

    private suspend fun ProtectedAccess.placePrimedBar() {
        invDel(inv, Ew2Objs.primed_bar)
        setFlag(Ew2VarBits.mind_jig, GUN_BAR)
        section("Placing the primed bar")
    }

    private suspend fun ProtectedAccess.takeFromGun(mind: Boolean) {
        setFlag(Ew2VarBits.mind_jig, GUN_EMPTY)
        if (mind) {
            invAddOrDrop(objRepo, Ew2Objs.mind_bar)
            section("Taking the mind bar")
            advance(MIND_BAR)
        } else {
            invAddOrDrop(objRepo, Ew2Objs.primed_bar)
            mes("You take the primed bar.")
        }
    }

    private fun ProtectedAccess.operateHat() {
        if (flag(Ew2VarBits.mind_jig) != GUN_BAR) {
            // Authored line (not Wiki).
            mes("You put on the hat, but nothing happens. The gun has nothing to fire at.")
            return
        }
        statSub(stats.magic, MAGIC_DRAIN, 0)
        setFlag(Ew2VarBits.mind_jig, GUN_MIND_BAR)
        mes("You feel magically weakened.")
    }

    private val dialogue: QuestDialogue
        get() = Ew2Dialogue.dialogue

    companion object {
        const val STARTED = 3
        const val KEY = 4
        const val HATCH = 5
        const val CLAW = 6
        const val PRIMED = 7
        const val MIND_BAR = 8
        const val COMPLETE = 11

        private const val SMITHING_REQUIRED = 30
        private const val MAGIC_REQUIRED = 20
        private const val MAGIC_DRAIN = 20

        /** `elemental_quest_2_box_state` bits: the two schematics have been read. */
        const val READ_CRANE = 1
        const val READ_LEVER = 2

        /** `fire_state`: the crane's claw. */
        private const val CRANE_BROKEN = 0
        private const val CRANE_EMPTY = 1
        private const val CRANE_COOL_BAR = 2
        private const val CRANE_HOT_BAR = 3

        /** `fire_pos` bits. */
        private const val CRANE_OVER_LAVA = 1
        private const val CRANE_DOWN = 2

        /** `jig_state`: the cache's jig cart multinpc children, in order. */
        private const val JIG_EMPTY = 0
        private const val JIG_BAR = 1
        private const val JIG_HOT_BAR = 2
        private const val JIG_FLAT_HOT = 3
        private const val JIG_FLAT = 4
        private const val JIG_PRIMED = 5

        /** `jig_pos`: the cart's station. */
        private const val AT_CRANE = 0
        private const val AT_PRESS = 1
        private const val AT_TANK = 2
        private const val AT_FAN = 3
        private const val STATIONS = 4
        private val STATION_NAMES = listOf("crane", "press", "water tank", "fan")

        /** `water_door` bits. */
        private const val DOOR_OPEN = 1
        private const val RACK_OUT = 2
        private const val BAR_IN_TANK = 4
        private const val BAR_COOLED = 8

        /** `air_fan_state`: the fan blade multiloc children. */
        private const val FAN_OFF = 0
        private const val FAN_BLOW = 1
        private const val FAN_REVERSE = 2

        /** Cog sizes on the high, low and left pins (Wiki: small, medium, large). */
        private val COG_SOLUTION = listOf(1, 2, 3)
        private val COG_SECTIONS = listOf("Small", "Medium", "Large")

        /** `mind_jig`: the extractor gun multiloc children. */
        private const val GUN_EMPTY = 0
        private const val GUN_BAR = 1
        private const val GUN_MIND_BAR = 2

        /** Authored (not Wiki): which crate (elemental_workshop_2_box_N) holds which part. */
        private val CRATE_PARTS = mapOf(4 to Part.MEDIUM, 6 to Part.LARGE, 7 to Part.PIPE, 8 to Part.SMALL)

        /** Authored arrival tiles (Ryan-test): next to each staircase. */
        private val WORKSHOP_ARRIVE = CoordGrid(1954, 5156, 2)
        private val EW1_ARRIVE = CoordGrid(2720, 9891, 0)
        private const val GANTRY_SPLIT_X = 1954
        private val GANTRY_WEST_TOP = CoordGrid(1948, 5149, 3)
        private val GANTRY_WEST_BOTTOM = CoordGrid(1950, 5150, 2)
        private val GANTRY_EAST_TOP = CoordGrid(1959, 5159, 3)
        private val GANTRY_EAST_BOTTOM = CoordGrid(1957, 5159, 2)
        private val MIND_ROOM_ARRIVE = CoordGrid(1949, 5159, 0)
        private val STAIRWELL_ARRIVE = CoordGrid(1949, 5157, 2)

        val ELEMENTAL_WORKSHOP_II =
            QuestDef(
                name = "Elemental Workshop II",
                varbit = { Ew2VarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("7,500 Crafting XP", "7,500 Smithing XP", "Mind helmets"),
                scrollObj = { Ew2Objs.mind_helm },
                listRow = "quest_elementalworkshop2",
            )
    }

    private enum class Part(val size: Int, val found: String) {
        SMALL(1, "You find a small cog."),
        MEDIUM(2, "You find a medium-sized cog."),
        LARGE(3, "You find a big cog."),
        PIPE(0, "You find a pipe.");

        val obj: ObjType
            get() =
                when (this) {
                    SMALL -> Ew2Objs.small_cog
                    MEDIUM -> Ew2Objs.medium_cog
                    LARGE -> Ew2Objs.large_cog
                    PIPE -> Ew2Objs.pipe
                }
    }
}

internal object Ew2Dialogue {
    val dialogue by lazy { QuestDialogue.load(ElementalWorkshopII::class.java, "elemental_workshop_2") }
}

/**
 * The EW1 workbench, for Elemental Workshop II: the crane claw (an elemental bar with the crane
 * schematic) and the mind helmet / mind shield (a primed mind bar). Elemental Workshop I's
 * workbench handler calls [handle] first, so the bench keeps one handler per op.
 */
class Ew2Workbench @Inject constructor(private val xpMods: XpModifiers) {
    private fun ProtectedAccess.stage(): Int =
        player.questState(ElementalWorkshopII.ELEMENTAL_WORKSHOP_II)

    /** Returns `true` when Elemental Workshop II handled the workbench. */
    suspend fun ProtectedAccess.handle(): Boolean {
        val dialogue = Ew2Dialogue.dialogue
        if (carries(Ew2Objs.mind_bar)) {
            val section =
                when {
                    !carries(Ew2Objs.hammer) -> "Using the workbench without a hammer"
                    !carries(Ew2Objs.beaten_book) && !carries(Ew2Objs.slashed_book) ->
                        "Using the workbench without the beaten book"
                    else -> null
                }
            if (section != null) {
                runQuestSection(null, dialogue, section)
                return true
            }
            invDel(inv, Ew2Objs.mind_bar)
            if (!carries(Ew2Objs.beaten_book)) {
                invAdd(inv, Ew2Objs.mind_shield)
                // Authored line (not Wiki): the slashed book's instructions make a shield.
                mes("Following the instructions in the book, you make a mind shield.")
                return true
            }
            invAdd(inv, Ew2Objs.mind_helm)
            val finishing =
                stage() >= ElementalWorkshopII.MIND_BAR &&
                    !player.isQuestComplete(ElementalWorkshopII.ELEMENTAL_WORKSHOP_II)
            if (!finishing) {
                mes("Following the instructions in the book, you make an elemental mind helm.")
                return true
            }
            runQuestSection(null, dialogue, "Using the workbench with the proper items")
            statAdvance(stats.crafting, REWARD_XP * xpMods.get(player, stats.crafting))
            statAdvance(stats.smithing, REWARD_XP * xpMods.get(player, stats.smithing))
            completeQuest(ElementalWorkshopII.ELEMENTAL_WORKSHOP_II)
            return true
        }
        val wantsClaw =
            stage() >= ElementalWorkshopII.HATCH &&
                stage() < ElementalWorkshopII.CLAW &&
                carries(Ew2Objs.bar) &&
                carries(Ew2Objs.crane_schematic) &&
                !carries(Ew2Objs.claw)
        if (!wantsClaw) return false
        var claw = false
        var helm = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("An elemental claw." to { claw = true }, "An elemental helm." to { helm = true })
            )
        runQuestSection(null, dialogue, "Making an elemental claw", hooks)
        if (claw) {
            invDel(inv, Ew2Objs.bar)
            invAdd(inv, Ew2Objs.claw)
        } else if (helm) {
            // Authored line (not Wiki): the helm needs the beaten book's instructions.
            mes("You don't have the instructions to make an elemental helm.")
        }
        return true
    }

    private companion object {
        const val REWARD_XP = 7500.0
    }
}

// Every cache ref Elemental Workshop II uses (re-check after cache updates).

internal object Ew2Npcs : NpcReferences() {
    val cart_empty = find("elem2_cart_npc_empty")
    /** The cart's children with something on the jig (op1 Take-from). */
    val cart_full =
        listOf(
            find("elem2_cart_npc_bar"),
            find("elem2_cart_npc_bar_hot"),
            find("elem2_cart_npc_flat_hot_bar"),
            find("elem2_cart_npc_flat_bar"),
            find("elem2_cart_npc_flat_bar_dry"),
        )
}

internal object Ew2Locs : LocReferences() {
    /** The Exam Centre bookcase (quest start). */
    val bookcase = find("elemental_workshop_2_bookcase")
    /** The water room machinery (child of elemental_workshop_2_boiler_multi on hide_key). */
    val machinery = find("elemental_workshop_2_boiler_for_key")
    val hatch_closed = find("elem2_stairs_door_close")
    val hatch_open = find("elem2_stairs_door_open")
    val stairs_up = find("elem2_stairs1")
    val gantry_stairs = find("elem_gantry_stairs")
    val gantry_stairs_top = find("elem_gantry_stairs_top")
    val stairwell = find("elem2_stairs_door_open_no_hatch")
    val mind_stairs = find("elem2_stairs2")
    val mind_doors = listOf(find("elem2_door_mind"), find("elem2_door_mindl"))
    val schematic_crate = find("elem2_maintenance_book_crate")
    val crates = (1..8).map { find("elemental_workshop_2_box_$it") }
    /** The one placed crane loc (1952,5143). */
    val crane = find("elem2_crane_lava_down_broken")
    /** Western crane lever: raise / lower. */
    val crane_lift_lever = find("elem2_fire_lever_2")
    /** Eastern crane lever: rotate. */
    val crane_rotate_lever = find("elem2_fire_lever_1")
    /** By the yellow sign: moves the jig cart to the next station. */
    val cart_lever = find("elem2_lever_3way")
    val junction_box = find("elem2_press_junction_box")
    val press_lever = find("elem2_earth_lever_1")
    /** Child of elemental_piping_blue_broken_multi (water_state), on the catwalk. */
    val broken_piping = find("elemental_piping_blue_broken")
    val water_lever = find("elem2_water_lever")
    val corkscrew = find("elem2_corkscrew")
    /** West valve: fills the tank. */
    val fill_valve = find("elem2_valve_1")
    /** East valve: empties the tank. */
    val drain_valve = find("elem2_valve_2")
    val air_lever = find("elem2_air_lever")
    /** The empty pins (children 0 of the pin multilocs): high, low, left. */
    val pins = listOf(find("elem2_wind_pin_high"), find("elem2_wind_pin_low"), find("elem2_wind_pin_left"))
    /** The cogs on each pin (children 1-3), in the same order as [pins]. */
    val pinCogs =
        listOf("high", "low", "left").map { pin ->
            listOf("small", "medium", "large").map { find("elem2_wind_cog_${pin}_$it") }
        }
    val gun_empty = find("elem_extractor_gun_no_bar")
    val gun_bar = find("elem_extractor_gun_bar")
    val gun_mind_bar = find("elem_extractor_gun_bar_mind")
    val extractor_hat = find("elem_extractor_hat")
}

internal object Ew2Objs : ObjReferences() {
    val beaten_book = find("elemental_workshop_helm_book")
    val slashed_book = find("elemental_workshop_shield_book_slashed")
    val scroll = find("elemental_workshop_2_note")
    val crane_schematic = find("elemental_workshop_claw_book")
    val lever_schematic = find("elemental_workshop_lever_book")
    val claw = find("elem_broken_finger")
    val key = find("elemental_workshop_2_key")
    val pipe = find("elem2_spare_pipe")
    val large_cog = find("elem2_biggear")
    val medium_cog = find("elem2_medgear")
    val small_cog = find("elem2_smallgear")
    /** Small, medium, large (cog sizes 1-3). */
    val cogs = listOf(small_cog, medium_cog, large_cog)
    val bar = find("elemental_workshop_bar")
    val primed_bar = find("elem_primed_bar")
    val mind_bar = find("elem_mind_bar")
    val mind_helm = find("elem_mind_helm")
    val mind_shield = find("elemental_mind_shield")
    val hammer = find("hammer")
}

internal object Ew2VarBits : VarBitReferences() {
    val quest = find("elementalworkshop")
    val hide_key = find("elemental_quest_2_hide_key")
    val hatch = find("elemental_quest_2_hatch")
    val jig_pos = find("elemental_quest_2_jig_pos")
    val jig_state = find("elemental_quest_2_jig_state")
    val fire_state = find("elemental_quest_2_fire_state")
    val fire_pos = find("elemental_quest_2_fire_pos")
    val earth_pipe_1 = find("elemental_quest_2_earth_pipe_1_state")
    val earth_pipe_2 = find("elemental_quest_2_earth_pipe_2_state")
    val earth_pipe_3 = find("elemental_quest_2_earth_pipe_3_state")
    val earth_pipe_all = find("elemental_quest_2_earth_pipe_state_all")
    val earth_fixed = find("elemental_quest_2_earth_fixed")
    val water_state = find("elemental_quest_2_water_state")
    val valve_fill = find("elemental_quest_2_water_valve_1")
    val valve_drain = find("elemental_quest_2_water_valve_2")
    val water_door = find("elemental_quest_2_water_door")
    val water_level = find("elemental_quest_2_water_level")
    /** Cog sizes on the high, low and left pins (the pin multilocs read these). */
    val cogs =
        listOf(
            find("elemental_quest_2_air_cog1"),
            find("elemental_quest_2_air_cog2"),
            find("elemental_quest_2_air_cog3"),
        )
    val fan_state = find("elemental_quest_2_air_fan_state")
    val mind_jig = find("elemental_quest_2_mind_jig")
    val box_state = find("elemental_quest_2_box_state")
}
