package org.rsmod.content.quests.touristtrap

import jakarta.inject.Inject
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerTimer
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_BARREL
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_CARRIED
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_CART
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_LIFT
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_ON_CART
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_SURFACE
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_TALKED_SHIFT
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ANA_TOP
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.DRIVER_OK
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ESCAPED
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.GATE_KEY
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.GUARD_FED
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.PINEAPPLE_ASKED
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.ROBES
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.SLAVE_CHAINS
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.SLAVE_UNLOCKED
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.TOURIST_TRAP
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * The Tourist Trap inside the Desert Mining Camp and its mine (see [TouristTrap] for the stages):
 * the gate and the guards' rules, the cell and its window, the male slave, the mine doors and the
 * mine cave, the barrels, both mine carts, Ana, the winch bucket and the winch, the wooden cart and
 * its driver, the punishment mine (15 rocks to get out) and the wrought iron key's gate.
 *
 * The guards' rules (Wiki): no weapons or armour inside the camp (jewellery, ammo and desert or
 * slave clothes are fine), full slave robes in the mine; caught, you go to the cell (surface) or the
 * punishment mine (below), and the gate and cell keys may be confiscated. Leaving by the gate in
 * slave clothes, or with Ana, gets you caught too.
 *
 * Simplified: the guards check every [CHECK_TICKS] ticks (1 in [CHECK_ODDS]) instead of by sight;
 * the mine cart, the winch and the camp cart are telejumps (no cutscenes); the cell escape's rock
 * and cliff are straight jumps; any full barrel holds Ana after the cart ride (Wiki: the one by
 * the cart); teleporting with Ana isn't blocked; the punishment rocks need any pickaxe and take a
 * fixed 4 ticks each; the "second time" barrel conversation is the first one again.
 */
class TtCamp
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TouristTrap::class.java, "tourist_trap") }

    private val guardIds: Set<Int> by lazy {
        (TtNpcs.guards + TtNpcs.cave_guards + TtNpcs.winch_guard + TtNpcs.prison_guard).map { it.id }.toSet()
    }

    override fun ScriptContext.startup() {
        // The camp gate (both halves) and the guards' look-over.
        for (gate in TtLocs.gates) {
            bindLoc(gate, "Open") { campGate(it) }
            bindLoc(gate, "Search") {
                val inside = player.coords.x > it.coords.x
                ttSection(dialogue, null, if (inside) "Searching the gates while inside the mining camp" else "Searching the gate")
            }
        }
        onPlayerTimer(TtTimers.camp) { campCheck() }
        onPlayerLogin { if (player.coords.inCamp() || player.coords.inMine()) player.timer(TtTimers.camp, CHECK_TICKS) }

        // The cell, its window and the way over the rocks.
        bindLoc(TtLocs.cell_door, "Open") { cellDoor(it) }
        bindLoc(TtLocs.bars, "Bend") {
            player.setTtVarBit(TtVarBits.cell_wall, 1)
            ttSection(dialogue, null, "Bend cell wall window")
        }
        bindLoc(TtLocs.bars_bent, "Escape") { escapeWindow() }
        bindLoc(TtLocs.climb_rock, "Climb") { climbRock() }
        bindLoc(TtLocs.cliff_down, "Climb-down") { jump(CLIFF_FOOT) }
        bindLoc(TtLocs.cliff_up, "Climb-up") { jump(ROCK_FOOT) }

        // The mine.
        for (door in TtLocs.mine_doors) {
            bindLoc(door, "Open") { mineDoor() }
            bindLoc(door, "Watch") { ttSection(dialogue, null, "Watch mine door entrance") }
        }
        for (exit in TtLocs.mine_exits) bindLoc(exit, "Open") { mineExit() }
        for (cave in TtLocs.caves) bindLoc(cave, "Walk through") { mineCave() }
        bindLoc(TtLocs.barrel_full, "Look-in") { ttSection(dialogue, null, "Look in full barrel") }
        bindLoc(TtLocs.barrel_full, "Search") { searchFullBarrel() }
        bindLoc(TtLocs.barrel_empty, "Look in") { ttSection(dialogue, null, "Look in empty barrel") }
        bindLoc(TtLocs.barrel_empty, "Search") { searchEmptyBarrel() }
        bindLoc(TtLocs.mine_cart, "Look at") {
            ttSection(dialogue, null, if (it.coords.x > CART_SPLIT_X) "Look at mine cart in the eastern room" else "Look at mine cart")
        }
        bindLoc(TtLocs.mine_cart, "Search") { rideCart(it) }
        bindLocU(TtLocs.mine_cart, TtObjs.ana_barrel) { anaOnMineCart(it) }
        bindLoc(TtLocs.winch_bucket, "Look at") { ttSection(dialogue, null, "Look at winch bucket") }
        bindLoc(TtLocs.winch_bucket, "Use") { useWinchBucket() }
        bindLocU(TtLocs.winch_bucket, TtObjs.ana_barrel) { anaOnWinch() }
        for (gate in TtLocs.prison_gates) {
            bindLoc(gate, "Open") { prisonGate() }
            bindLoc(gate, "Search") { ttSection(dialogue, null, "Search gate to the prison mine") }
        }
        bindLoc(TtLocs.punish_rocks, "Mine") { minePunishRock() }
        for (gate in TtLocs.nice_gates) bindLoc(gate, "Open") { niceGate(it) }

        // The surface: the winch, the winch-side barrel, the wooden cart and the camel.
        bindLoc(TtLocs.winch, "Look at") { ttSection(dialogue, null, "Look at winch") }
        bindLoc(TtLocs.winch, "Operate") { operateWinch() }
        bindLoc(TtLocs.winch_barrel, "Look-in") { ttSection(dialogue, null, "Look-in barrel containing Ana") }
        bindLoc(TtLocs.winch_barrel, "Search") { searchWinchBarrel() }
        for (cart in listOf(TtLocs.cart, TtLocs.cart_ana)) {
            bindLoc(cart, "Look at") { ttSection(dialogue, null, "Look at wooden cart") }
            bindLoc(cart, "Search") { searchWoodenCart() }
        }
        bindLocU(TtLocs.cart, TtObjs.ana_barrel) { anaOnWoodenCart() }
        bindLoc(TtLocs.camel, "Talk-to") {
            ttSection(dialogue, null, "Talking to the cart camel", TtHooks(this, randomCount = CAMEL_LINES))
        }

        // Ana's trail by the Shantay Pass.
        for (prints in TtLocs.footprints) {
            bindLoc(prints, "Look at") { footprints("Looking at the footprints") }
            bindLoc(prints, "Search") { footprints("Searching the footprints") }
        }
        bindLoc(TtLocs.scarf_cactus, "Inspect") { ttSection(dialogue, null, "Inspecting a cactus with a scarf on it") }

        // The camp's people.
        with(QuestTalk) {
            register(npcTypes, TtNpcs.male_slave, TOURIST_TRAP, wants = { true }) { talkSlave(it) }
            register(npcTypes, TtNpcs.escaped_slave, TOURIST_TRAP, wants = { true }) { talkSlave(it) }
            register(npcTypes, TtNpcs.ana, TOURIST_TRAP, wants = { true }) { talkAna(it) }
            register(npcTypes, TtNpcs.driver, TOURIST_TRAP, wants = { true }) { talkDriver(it) }
            for (guard in TtNpcs.cave_guards) {
                register(npcTypes, guard, TOURIST_TRAP, wants = { true }) { talkCaveGuard(it) }
            }
            register(npcTypes, TtNpcs.winch_guard, TOURIST_TRAP, wants = { true }) { talkWinchGuard(it) }
            register(npcTypes, TtNpcs.prison_guard, TOURIST_TRAP, wants = { true }) {
                ttSection(dialogue, it, "Talking to the guard outside the prison mine")
            }
            for (guard in TtNpcs.guards) {
                // The Wiki's standard guard line.
                default(npcTypes, guard) { startDialogue(it) { chatNpc(neutral, "What are you looking at?") } }
            }
            for (slave in TtNpcs.slaves) default(npcTypes, slave) { slaveOverhead(it) }
            default(npcTypes, TtNpcs.rowdy_slave) { rowdy(it) }
        }
        for (guard in TtNpcs.cave_guards) onOpNpcU(guard) { useOnCaveGuard(it.npc, it.objType.id) }
        onOpNpcU(TtNpcs.ana) { if (it.objType.id == TtObjs.barrel.id) barrelOnAna(it.npc) }
        onHeldOpLabel(objTypes, TtObjs.ana_barrel, "Look") { ttSection(dialogue, null, "Look-at Ana in a barrel") }
        if (!isHeldOpBound(TtObjs.ana_barrel, DROP_OP)) onOpHeld5(TtObjs.ana_barrel) { dropAna() }
    }

    /* Binding helpers (a duplicate bind crashes boot). */

    private fun ScriptContext.bindLoc(loc: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(loc, op)) return
        onLocOpLabel(locTypes, loc, label, action)
    }

    private fun ScriptContext.bindLocU(loc: LocType, obj: ObjType, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (eventBus.contains(LocUEvents.Op::class.java, EventBus.composeLongKey(loc.id, obj.id))) return
        onOpLocU(loc, obj) { action(it.loc) }
    }

    private val ProtectedAccess.state: Int
        get() = player.ttState

    private fun ProtectedAccess.setState(value: Int) = player.setTtState(value)

    private fun ProtectedAccess.jump(target: CoordGrid) = player.jumpTo(collision, target)

    private fun ProtectedAccess.jail(underground: Boolean) {
        player.jail(collision, underground) { random.randomBoolean() }
        player.timer(TtTimers.camp, CHECK_TICKS)
    }

    private fun ProtectedAccess.guardNearby(): Boolean =
        npcRepo.findAll(ZoneKey.from(player.coords), 1).any {
            it.type.id in guardIds && it.coords.chebyshevDistance(player.coords) <= GUARD_RANGE
        }

    /* The gate and the guards */

    private suspend fun ProtectedAccess.campGate(gate: BoundLocInfo) {
        val inside = player.coords.x > gate.coords.x
        if (inside) {
            when {
                carries(TtObjs.ana_barrel) -> {
                    ttSection(dialogue, null, "Trying to exit the gates while holding Ana in a barrel")
                    player.loseAna()
                }
                player.wearsAnySlaveClothes() -> {
                    // Authored (not Wiki): the Wiki only says leaving in slave clothes gets you jailed.
                    mes("Guard: Hey, where do you think you're going in those clothes?")
                    jail(underground = false)
                }
                else -> passThroughWall(gate)
            }
            return
        }
        if (!carries(TtObjs.metal_key)) {
            ttSection(dialogue, null, "Attempting to enter the gate without the key")
            return
        }
        if (player.wearsGear()) {
            passThroughWall(gate)
            var caught = false
            val hooks = TtHooks(this, actions = mapOf("(Continues with Getting jailed dialogue.)" to { caught = true }))
            ttSection(dialogue, null, "Trying to enter the camp with a weapon equipped", hooks)
            if (caught) jail(underground = false)
            return
        }
        ttSection(dialogue, null, "Entering the camp")
        passThroughWall(gate)
        player.timer(TtTimers.camp, CHECK_TICKS)
    }

    /** The guards' periodic look-over (see the class doc). */
    private fun ProtectedAccess.campCheck() {
        val c = player.coords
        if (!c.inCamp() && !c.inMine()) {
            player.clearTimer(TtTimers.camp)
            return
        }
        if (c.inCell() || c.inPrisonMine() || random.of(CHECK_ODDS) != 0) return
        if (c.inCamp() && player.wearsGear()) {
            mes("Guard: Hey - you with the weapon!")
            mes("Guard: You're not allowed in here!")
            mes("Guard: Quick, men, get them!!")
            jail(underground = false)
        } else if (c.inMine() && !player.wearsSlaveRobes()) {
            mes("Guard: Hey, they're interesting clothes!")
            mes("Guard: You're no slave.")
            mes("Guard: What are you doing in here?")
            jail(underground = true)
        }
    }

    /* The cell */

    private suspend fun ProtectedAccess.cellDoor(door: BoundLocInfo) {
        if (player.coords.inCell() && !carries(TtObjs.cell_key)) {
            ttSection(dialogue, null, "Trying to open the prison door")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.escapeWindow() {
        if (carries(TtObjs.ana_barrel)) {
            ttSection(dialogue, null, "Trying to escape through the cell window while holding Ana in a barrel")
            return
        }
        ttSection(dialogue, null, "Escape cell wall window")
        jump(WINDOW_OUTSIDE)
        player.setTtVarBit(TtVarBits.cell_wall, 0)
    }

    private suspend fun ProtectedAccess.climbRock() {
        if (random.of(ROCK_FAIL_ODDS) == 0) {
            ttSection(dialogue, null, "Failing to climb rock after escaping the cell")
            player.queueHit(delay = 0, type = HitType.Typeless, damage = 1)
            return
        }
        jump(CLIFF_TOP)
    }

    /* The male slave by the mine doors */

    private suspend fun ProtectedAccess.talkSlave(npc: Npc) {
        if (npc.type.id != TtNpcs.mine_slave.id) {
            slaveOverhead(npc)
            return
        }
        val robes = ttOwns(TtObjs.slave_shirt) || ttOwns(TtObjs.slave_robe) || ttOwns(TtObjs.slave_boots)
        when {
            state < GATE_KEY -> slaveOverhead(npc)
            state >= ROBES && player.wearsSlaveRobes() -> ttSection(dialogue, npc, "Talking to the male slave while wearing slave clothes")
            state >= ROBES && robes ->
                ttSection(dialogue, npc, "Talking to the escaping slave", TtHooks(this, conditions = mapOf("If the player has the slave clothes:" to { true })))
            state >= ROBES -> {
                player.setTtVarBit(TtVarBits.mine_slave, 0)
                tradeMenu(npc, "Talking to the male slave after losing the slave clothes")
            }
            state == SLAVE_UNLOCKED -> tradeMenu(npc, "Talking to the male slave after he offers to trade")
            state == SLAVE_CHAINS && guardNearby() -> {
                var caught = false
                val hooks = TtHooks(this, actions = mapOf("(Continues with Getting jailed dialogue.)" to { caught = true }))
                ttSection(dialogue, null, "Getting caught by a guard while talking to the slave", hooks)
                if (caught) jail(underground = false)
            }
            state == SLAVE_CHAINS -> ttSection(dialogue, npc, "Talking to the Male slave after he mentions the handcuffs", slaveHooks())
            else -> {
                val hooks =
                    TtHooks(this, options = mapOf("It's funny you should say that..." to { if (state == GATE_KEY) setState(SLAVE_CHAINS) }))
                ttSection(dialogue, npc, "Talking to Male slave by the mine door entrance", hooks)
            }
        }
    }

    /** "Do you have the Desert Clothes yet?": the trade branch of the chains conversation. */
    private suspend fun ProtectedAccess.tradeMenu(npc: Npc, section: String) {
        var trade = false
        ttSection(dialogue, npc, section, TtHooks(this, options = mapOf("Yes, I'll trade." to { trade = true })))
        if (!trade) return
        runQuestBranch(npc, dialogue, "Talking to the Male slave after he mentions the handcuffs", "Yes, I'll trade.", slaveHooks(), ttSpeakers)
    }

    private fun ProtectedAccess.hasClothes(obj: ObjType): Boolean = carries(obj) || invTotal(worn, obj) > 0

    private fun ProtectedAccess.slaveHooks(): TtHooks {
        val robe = !hasClothes(TtObjs.desert_robe)
        val shirt = !hasClothes(TtObjs.desert_shirt)
        val boots = !hasClothes(TtObjs.desert_boots)
        fun missing(r: Boolean, s: Boolean, b: Boolean) = robe == r && shirt == s && boots == b
        val failed = random.of(if (carries(TtObjs.lockpick)) PICK_FAIL_ODDS_LOCKPICK else PICK_FAIL_ODDS) == 0
        return TtHooks(
            this,
            conditions =
                mapOf(
                    "If the player doesn't have a lockpick on them:" to { !carries(TtObjs.lockpick) },
                    "If the player has a lockpick on them:" to { carries(TtObjs.lockpick) },
                    "If the player fails:" to { failed },
                    "If the player is missing the desert robe, desert shirt, and desert boots:" to { missing(true, true, true) },
                    "If the player is missing the desert robe and desert shirt:" to { missing(true, true, false) },
                    "If the player is missing the desert robe and desert boots:" to { missing(true, false, true) },
                    "If the player is missing the desert shirt and desert boots:" to { missing(false, true, true) },
                    "If the player is missing the desert robe:" to { missing(true, false, false) },
                    "If the player is missing the desert boots:" to { missing(false, false, true) },
                    "If the player is missing the desert shirt:" to { missing(false, true, false) },
                ),
            boxes =
                mapOf(
                    "You hear a satisfying 'click' as you tumble the lock mechanism." to {
                        if (state == SLAVE_CHAINS) setState(SLAVE_UNLOCKED)
                        false
                    },
                    "The slave gives you his dirty, flea infested robe." to { swap(TtObjs.desert_robe, TtObjs.slave_robe) },
                    "The slave gives you his muddy, sweat soaked shirt." to { swap(TtObjs.desert_shirt, TtObjs.slave_shirt) },
                    "The slave gives you a smelly pair of decomposing boots." to {
                        val shown = swap(TtObjs.desert_boots, TtObjs.slave_boots)
                        if (state < ROBES) setState(ROBES)
                        player.setTtVarBit(TtVarBits.mine_slave, 1)
                        shown
                    },
                ),
        )
    }

    /** Desert clothes (carried or worn) for a slave piece; `false` so the box still shows. */
    private fun ProtectedAccess.swap(desert: ObjType, slave: ObjType): Boolean {
        if (carries(desert)) invDel(inv, desert) else invDel(worn, desert, strict = false)
        invAddOrDrop(objRepo, slave)
        return false
    }

    private fun ProtectedAccess.slaveOverhead(npc: Npc) {
        val line = if (player.coords.inMine()) random.pick(MINE_SLAVE_LINES) else "Hey leave me alone, can't you see that I'm busy?"
        npc.say(line)
    }

    private suspend fun ProtectedAccess.rowdy(npc: Npc) {
        ttSection(dialogue, npc, "Talking to a rowdy slave")
        npc.opPlayer2(player, aiInteractions)
    }

    /* Into the mine */

    private suspend fun ProtectedAccess.mineDoor() {
        if (!player.wearsSlaveRobes()) {
            ttSection(dialogue, null, "Opening the mine door entrance", TtHooks(this, conditions = mapOf("If the player is not wearing slave clothes:" to { true })))
            return
        }
        ttSection(dialogue, null, "Open mine door entrance")
        jump(MINE_INSIDE)
        player.timer(TtTimers.camp, CHECK_TICKS)
    }

    private suspend fun ProtectedAccess.mineExit() {
        if (carries(TtObjs.ana_barrel)) {
            var caught = false
            val hooks = TtHooks(this, actions = mapOf("(Continues with Getting jailed dialogue.)" to { caught = true }))
            ttSection(dialogue, null, "Trying to leave out the main entrance while holding Ana in a barrel", hooks)
            if (caught) jail(underground = false)
            return
        }
        jump(MINE_OUTSIDE)
    }

    private suspend fun ProtectedAccess.mineCave() {
        val goingIn = player.coords.x < CAVE_MID_X
        if (goingIn && state < GUARD_FED) {
            mes("The guard stops you going into the cavern.") // Authored (not Wiki).
            return
        }
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf("Going in, to the deeper mine:" to { goingIn }, "Coming back out:" to { !goingIn }),
                boxes = mapOf("There is no gate." to { true }),
            )
        ttSection(dialogue, null, "Walk through Mine cave", hooks)
        jump(if (goingIn) CAVE_EAST else CAVE_WEST)
    }

    private suspend fun ProtectedAccess.talkCaveGuard(npc: Npc) {
        if (!player.wearsSlaveRobes()) return // Wiki: they only talk to slaves.
        if (state >= GUARD_FED) {
            ttSection(dialogue, npc, "Talking to the guard in the cave after giving him the tenti pineapple")
            return
        }
        val asked: suspend () -> Unit = { if (state == ROBES) setState(PINEAPPLE_ASKED) }
        val hooks =
            TtHooks(
                this,
                options =
                    mapOf(
                        "Yes sir, we understand each other perfectly." to asked,
                        "How am I going to get some pineapples around here?" to asked,
                    ),
            )
        ttSection(dialogue, npc, "Talking to the guard by the cave opening", hooks)
    }

    private suspend fun ProtectedAccess.useOnCaveGuard(npc: Npc, objId: Int) {
        when (objId) {
            TtObjs.pineapple.id -> ttSection(dialogue, npc, "Using a regular pineapple on the guard in the cave")
            TtObjs.pineapple_ring.id -> ttSection(dialogue, npc, "Using a pineapple ring on the guard in the cave")
            TtObjs.pineapple_chunks.id -> ttSection(dialogue, npc, "Using pineapple chunks on the guard in the cave")
            TtObjs.tenti.id ->
                if (state >= GUARD_FED) {
                    ttSection(dialogue, npc, "Using a second tenti pineapple on the guard in the cave")
                } else if (state >= PINEAPPLE_ASKED) {
                    invDel(inv, TtObjs.tenti)
                    setState(GUARD_FED)
                    ttSection(dialogue, npc, "Using the tenti pineapple on the guard in the cave")
                }
            else -> mes("Nothing interesting happens.")
        }
    }

    private suspend fun ProtectedAccess.talkWinchGuard(npc: Npc) {
        if (random.randomBoolean()) {
            ttSection(dialogue, npc, "Talking to the guard by the winch (dialogue 1)")
            return
        }
        var caught = false
        val hooks = TtHooks(this, actions = mapOf("(Continues with getting jailed dialogue.)" to { caught = true }))
        ttSection(dialogue, npc, "Talking to the guard by the winch (dialogue 2)", hooks)
        if (caught) jail(underground = true)
    }

    /* Barrels and Ana */

    private suspend fun ProtectedAccess.searchFullBarrel() {
        if (state != ANA_CART) {
            ttSection(dialogue, null, "Search full barrel")
            return
        }
        val full = inv.freeSpace() == 0
        ttSection(
            dialogue,
            null,
            "Searching the full barrel by the mining cart after sending Ana",
            TtHooks(this, conditions = mapOf("If the player has no inventory space:" to { full })),
        )
        if (full) return
        invAdd(inv, TtObjs.ana_barrel)
        setState(ANA_CARRIED)
    }

    private suspend fun ProtectedAccess.searchEmptyBarrel() {
        when (state) {
            ANA_CART -> ttSection(dialogue, null, "Searching empty barrels by the winch after sending Ana")
            ANA_LIFT -> ttSection(dialogue, null, "Searching empty barrel by the mine cart")
            else -> {
                val hooks =
                    TtHooks(
                        this,
                        conditions =
                            mapOf(
                                "If the player is holding Ana in a barrel:" to { carries(TtObjs.ana_barrel) },
                                "If the player already has a barrel:" to { carries(TtObjs.barrel) },
                            ),
                        options = mapOf("Yeah, cool!" to { invAddOrDrop(objRepo, TtObjs.barrel) }),
                    )
                ttSection(dialogue, null, "Search empty barrel", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.talkAna(npc: Npc) {
        if (npc.type.id == TtNpcs.ana_shanty.id || state >= ESCAPED) {
            ttSection(dialogue, npc, "Talking to Ana before getting the wrought iron key")
            return
        }
        val again = player.ttBits(ANA_TALKED_SHIFT, 1) == 1
        player.setTtBits(ANA_TALKED_SHIFT, 1, 1)
        var caught = false
        val caughtOut: suspend () -> Unit = { caught = true }
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If the player is holding the key:" to {
                            val held = carries(TtObjs.metal_key)
                            if (held) invDel(inv, TtObjs.metal_key)
                            held
                        }
                    ),
                options =
                    mapOf(
                        "I have a key." to caughtOut,
                        "Huh, these guards are rubbish, it was easy to sneak past them!" to caughtOut,
                    ),
            )
        ttSection(dialogue, npc, if (again) "Talking to Ana again" else "Talking to Ana", hooks)
        if (caught) jail(underground = false)
    }

    private suspend fun ProtectedAccess.barrelOnAna(npc: Npc) {
        if (state != GUARD_FED || !carries(TtObjs.barrel)) return
        ttSection(dialogue, npc, "Using the barrel on Ana")
        invDel(inv, TtObjs.barrel)
        invAdd(inv, TtObjs.ana_barrel)
        player.setTtVarBit(TtVarBits.ana_state, 1)
        setState(ANA_BARREL)
    }

    private suspend fun ProtectedAccess.dropAna() {
        if (state >= ESCAPED) {
            mes("You'd better take Ana back to her mother.") // Authored (not Wiki).
            return
        }
        var caught = false
        val hooks = TtHooks(this, actions = mapOf("(Continues with Getting jailed dialogue.)" to { caught = true }))
        ttSection(dialogue, null, "Dropping Ana in a barrel", hooks)
        player.loseAna()
        if (caught || state >= GATE_KEY) jail(underground = false)
    }

    /* The mine carts */

    private suspend fun ProtectedAccess.rideCart(cart: BoundLocInfo) {
        val east = cart.coords.x > CART_SPLIT_X
        var ride = false
        val failed = random.of(CART_FAIL_ODDS) == 0
        val bang = random.randomBoolean()
        val hurt = if (failed) random.of(1, 4) else 0
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If the player is holding Ana in a barrel:" to { carries(TtObjs.ana_barrel) },
                        "If the player fails:" to { failed },
                        "If the player bangs their head:" to { bang },
                        "If the player falls off:" to { !bang },
                    ),
                options = mapOf("Yes, of course." to { ride = !failed && !carries(TtObjs.ana_barrel) }),
            )
        val section = if (east) "Searching mine cart in the eastern room" else "Searching the mine cart"
        ttSection(dialogue, null, section, hooks)
        if (hurt > 0) player.queueHit(delay = 0, type = HitType.Typeless, damage = hurt)
        if (ride) jump(if (east) CART_WEST_LANDING else CART_EAST_LANDING)
    }

    private suspend fun ProtectedAccess.anaOnMineCart(cart: BoundLocInfo) {
        if (state == ANA_BARREL && cart.coords.x > CART_SPLIT_X) {
            ttSection(dialogue, null, "Using Ana in a barrel on the mine cart")
            invDel(inv, TtObjs.ana_barrel)
            setState(ANA_CART)
            return
        }
        ttSection(dialogue, null, "Using Ana in a barrel on the mine cart again")
    }

    /* The winch */

    private suspend fun ProtectedAccess.useWinchBucket() {
        if (state == ANA_LIFT) {
            ttSection(dialogue, null, "Using the winch after using Ana on it")
            return
        }
        ttSection(dialogue, null, "Using the winch bucket")
    }

    private suspend fun ProtectedAccess.anaOnWinch() {
        if (state != ANA_CARRIED) return
        val hooks =
            TtHooks(
                this,
                boxes =
                    mapOf(
                        "The guard places the barrel carefully on the lift platform." to {
                            invDel(inv, TtObjs.ana_barrel)
                            setState(ANA_LIFT)
                            false
                        }
                    ),
            )
        ttSection(dialogue, null, "Using Ana in a barrel on the winch", hooks)
    }

    private suspend fun ProtectedAccess.operateWinch() {
        if (state == ANA_LIFT) {
            ttSection(dialogue, null, "Operate winch with Ana in it")
            player.setTtVarBit(TtVarBits.winch_barrel, 1)
            setState(ANA_SURFACE)
            return
        }
        ttSection(dialogue, null, "Operate winch after retrieving Ana")
    }

    private suspend fun ProtectedAccess.searchWinchBarrel() {
        if (state != ANA_SURFACE) return
        val full = inv.freeSpace() == 0
        ttSection(
            dialogue,
            null,
            "Search barrel containing Ana",
            TtHooks(this, conditions = mapOf("If the player has no inventory space:" to { full })),
        )
        if (full) return
        invAdd(inv, TtObjs.ana_barrel)
        player.setTtVarBit(TtVarBits.winch_barrel, 0)
        setState(ANA_TOP)
    }

    /* The wooden cart and its driver */

    private suspend fun ProtectedAccess.searchWoodenCart() {
        when {
            state == DRIVER_OK -> {
                var escape = false
                val hooks = TtHooks(this, options = mapOf("Yes, I'll get on." to { escape = true }))
                ttSection(dialogue, null, "Searching the wooden cart after paying the Mine cart driver or convincing him of a prison riot", hooks)
                if (escape) escapeOnCart()
            }
            state == ANA_ON_CART -> ttSection(dialogue, null, "Search wooden cart after putting Ana on the cart")
            carries(TtObjs.ana_barrel) -> ttSection(dialogue, null, "Search wooden cart while holding Ana in a barrel")
            else -> ttSection(dialogue, null, "Look at wooden cart")
        }
    }

    private suspend fun ProtectedAccess.anaOnWoodenCart() {
        if (state != ANA_TOP) return
        ttSection(dialogue, null, "Use Ana in a barrel on the wooden cart")
        invDel(inv, TtObjs.ana_barrel)
        player.setTtVarBit(TtVarBits.cart_ana, 1)
        setState(ANA_ON_CART)
    }

    private fun ProtectedAccess.escapeOnCart() {
        player.setTtVarBit(TtVarBits.cart_ana, 0)
        player.setTtVarBit(TtVarBits.ana_state, 1)
        invAddOrDrop(objRepo, TtObjs.ana_barrel)
        setState(ESCAPED)
        jump(OUTSIDE_GATE)
        player.clearTimer(TtTimers.camp)
    }

    private suspend fun ProtectedAccess.talkDriver(npc: Npc) {
        when {
            state >= ESCAPED -> ttSection(dialogue, npc, "Talking to the Mine cart driver after already escaping once")
            state == DRIVER_OK -> ttSection(dialogue, npc, "Talking to the Mine cart driver after convincing him of a prison riot")
            state == ANA_ON_CART -> {
                var short = false
                var caught = false
                val hooks =
                    TtHooks(
                        this,
                        conditions = mapOf("If the player does not have enough coins:" to { short }),
                        options =
                            mapOf(
                                "You can't leave me here, I'll get killed!" to { setState(DRIVER_OK) },
                                "A hundred it is!" to {
                                    short = invTotal(inv, TtObjs.coins) < BRIBE
                                    if (!short) {
                                        invDel(inv, TtObjs.coins, BRIBE)
                                        setState(DRIVER_OK)
                                    }
                                },
                            ),
                        actions = mapOf("(Continues with getting jailed dialogue.)" to { caught = true }),
                    )
                ttSection(dialogue, npc, "Talking to Mine cart driver", hooks)
                if (caught) jail(underground = false)
            }
            else -> ttSection(dialogue, npc, "Talking to the Mine cart driver")
        }
    }

    /* The punishment mine and the wrought iron key's gate */

    private fun ProtectedAccess.hasPickaxe(): Boolean =
        (inv.objs.asSequence() + worn.objs.asSequence()).any {
            it != null && objTypes[it].name.contains("pickaxe", ignoreCase = true)
        }

    private suspend fun ProtectedAccess.minePunishRock() {
        if (!hasPickaxe()) {
            mes("You need a pickaxe to mine this rock.") // Mining's line.
            return
        }
        if (inv.freeSpace() == 0) {
            mes("Your inventory is too full to hold any more rocks.") // Authored.
            return
        }
        mes("You swing your pick at the rock.")
        delay(PUNISH_TICKS)
        invAdd(inv, TtObjs.punish_rock)
        mes("You manage to mine some rocks.") // Authored.
    }

    private suspend fun ProtectedAccess.prisonGate() {
        if (!player.coords.inPrisonMine()) {
            ttSection(dialogue, null, "Open gate to enter the prison mine")
            return
        }
        val enough = invTotal(inv, TtObjs.punish_rock) >= PUNISH_ROCKS
        ttSection(
            dialogue,
            null,
            "Open gate to leave the prison mine",
            TtHooks(
                this,
                conditions =
                    mapOf("If the player does not have enough rocks:" to { !enough }, "If the player has enough rocks:" to { enough }),
            ),
        )
        if (!enough) return
        invDel(inv, TtObjs.punish_rock, PUNISH_ROCKS)
        jump(PRISON_OUTSIDE)
    }

    private suspend fun ProtectedAccess.niceGate(gate: BoundLocInfo) {
        if (!carries(TtObjs.wrought_key)) {
            mes("This gate seems to be locked.")
            return
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.footprints(section: String) {
        val started = state > 0
        val hooks =
            TtHooks(
                this,
                conditions = mapOf("Before starting the quest:" to { !started }, "During the quest:" to { started }),
            )
        ttSection(dialogue, null, section, hooks)
    }

    private companion object {
        const val CHECK_TICKS = 5
        const val CHECK_ODDS = 3
        const val GUARD_RANGE = 3
        const val ROCK_FAIL_ODDS = 4
        const val PICK_FAIL_ODDS = 3
        const val PICK_FAIL_ODDS_LOCKPICK = 6
        const val CART_FAIL_ODDS = 6
        const val CAMEL_LINES = 3
        const val DROP_OP = 5
        const val BRIBE = 100
        const val PUNISH_ROCKS = 15
        const val PUNISH_TICKS = 4

        /** Rev 243 map: the mine carts at 3303,9417 (barrel room) and 3318,9430 (eastern room). */
        const val CART_SPLIT_X = 3310
        /** The mine cave (`thminecavel/r` at x 3281 and 3283, z 9414-9415). */
        const val CAVE_MID_X = 3282

        val WINDOW_OUTSIDE = CoordGrid(3282, 3035, 0)
        val CLIFF_TOP = CoordGrid(3276, 3038, 1)
        val ROCK_FOOT = CoordGrid(3281, 3036, 0)
        val CLIFF_FOOT = CoordGrid(3271, 3040, 0)
        /** The mine doors (3301-3302,3036) and the mine's exit doors (3278-3279,9426). */
        val MINE_INSIDE = CoordGrid(3279, 9425, 0)
        val MINE_OUTSIDE = CoordGrid(3301, 3035, 0)
        val CAVE_WEST = CoordGrid(3280, 9414, 0)
        val CAVE_EAST = CoordGrid(3284, 9414, 0)
        val CART_WEST_LANDING = CoordGrid(3302, 9417, 0)
        val CART_EAST_LANDING = CoordGrid(3318, 9431, 0)
        val PRISON_OUTSIDE = CoordGrid(3294, 9435, 0)
        /** West of the camp gate (3273,3028-3029). */
        val OUTSIDE_GATE = CoordGrid(3271, 3029, 0)

        val MINE_SLAVE_LINES =
            listOf(
                "Oohh, my arms.",
                "Work, work, work, that's all I ever do.",
                "I feel so weak, I could faint.",
                "I'm so tired, I could sleep standing up.",
                "What I wouldn't give for a good night's rest.",
                "Ooh, my back.",
                "I'm rich in experience, poor in wealth.",
                "I can't think straight I'm so tired.",
                "I'm sick of this place.",
            )
    }
}
