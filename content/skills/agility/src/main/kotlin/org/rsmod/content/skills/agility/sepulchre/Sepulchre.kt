package org.rsmod.content.skills.agility.sepulchre

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.ClueDrops
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.poison.applyPoison
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.player.stat.rangedLvl
import org.rsmod.api.player.stat.thievingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpHeldLabel
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.seq.SeqType
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Hallowed Sepulchre (OSRS Wiki, current rules after the Aug 2026 update), single player v1 on the
 * static template floors (no instancing; see ~/code/rsmod-sepulchre-notes.md):
 * - Darkmeyer Mausoleum Door -> lobby; lobby stairs (52 Agility) start a run on floor 1.
 * - Timer: 2:00 per floor, carried over, +1:00 per hallowed token; paused in the safe centre. At 0
 *   the next-floor stairs, coffins and unbuilt challenges lock, but the floor can still be finished
 *   for xp (Wiki). `hallowed_time_remaining` stays >= 2 while time remains (stairs/barrier multilocs).
 * - Floor chain: path drops/stairs (one plane down), end platforms / floor-4 gate / stepping stones,
 *   next-floor stairs (62/72/77/87 Agility), obelisk Exit/Quick-exit (floor xp + pet roll) and
 *   Activate (god-item run energy). Floor xp 575/925/1,600/2,875/5,725 on stairs down or exit.
 * - Skill challenges gate the coffins: pillar grapple, bridge (Construction), brazier + holy barrier
 *   (Prayer), portal (Magic). Crossings give Agility xp on the first crossing and the return.
 * - Coffins (66 Thieving, 200 xp, lockpick rates, marks, tier tables, lockpick/page/clue) and the
 *   Grand Hallowed Coffin (84 Thieving, or 66 Thieving + 92 Agility). Hallowed sack, tokens.
 * - Mysterious Stranger: talk + the Mysterious Hallowed Goods menu, paid in hallowed marks.
 * Destinations are derived from loc coords and snapped to walkable tiles (unverified). Logging out on
 * a floor puts the player back in the lobby. Not yet: moving traps (fire/sword/bolt/lightning/
 * strange tiles), ghosts, adventurers, tome pages, tool storage, noticeboards, personal bests,
 * nail/grapple/spell failures, Sins of the Father entry check.
 */
class Sepulchre
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val collision: CollisionFlagMap,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val launcher: ProtectedAccessLauncher,
) : PluginScript() {
    private val logger = InlineLogger()
    private val runs = HashMap<Long, Run>()
    private var skipped = 0

    private class Run {
        var floor = 1
        var ticksLeft = 0
        var floorTicks = 0
        var totalTicks = 0
        var safe = false
        var xpPaid = false
        var expired = false
        /** Consecutive ticks spent off the floor squares (a death or another teleport ends the run). */
        var offFloors = 0
        /** Crossings per challenge site (xp for the first crossing and the return). */
        val crossings = HashMap<Int, Int>()
    }

    override fun ScriptContext.startup() {
        bindLoc(SepLocs.ow_entrance, "Enter") { enterLobby() }
        bindLoc(SepLocs.lobby_exit, "Climb-up") { exitLobby() }
        bindLoc(SepLocs.minigame_start, "Climb-down") { startRun() }
        bindLoc(SepLocs.stairs_return, "Exit") { leave(pay = false, pet = false) }
        bindLoc(SepLocs.stairs_return, "Quick-exit") { leave(pay = false, pet = false) }
        bindLoc(SepLocs.obelisk, "Activate") { activateObelisk() }
        bindLoc(SepLocs.obelisk, "Exit") { exitObelisk() }
        bindLoc(SepLocs.obelisk, "Quick-exit") { leave(pay = true, pet = true) }
        bindLoc(SepLocs.progress_stairs, "Climb-down") { descend(it) }
        for (drop in SepLocs.drops) {
            val label = if (locOp(drop, "Jump") > 0) "Jump" else "Climb-down"
            bindLoc(drop, label) { dropDown(it) }
        }
        for (platform in SepLocs.platforms) bindLoc(platform, "Jump") { jumpIn(it, SepSeqs.jump) }
        bindLoc(SepLocs.gate, "Squeeze-through") { jumpIn(it, SepSeqs.squeeze) }
        for (stone in SepLocs.stepping_stones) bindLoc(stone, "Jump") { jumpStone(it) }

        for (coffin in listOf(SepLocs.coffin_unlooted, SepLocs.f5_grapple_unlooted, SepLocs.f5_portal_unlooted, SepLocs.f5_bridge_unlooted)) {
            bindLoc(coffin, "Search-for-traps") { searchCoffin(it, grand = false) }
        }
        bindLoc(SepLocs.grand_unlooted, "Search-for-traps") { searchCoffin(it, grand = true) }
        bindLoc(SepLocs.grand_barrier_open, "Pass-through") { passGrandBarrier(it) }

        bindLoc(SepLocs.pillar_ready, "Grapple") { grapple(it) }
        bindLoc(SepLocs.brazier_ready, "Sacrifice") { sacrifice() }
        bindLoc(SepLocs.brazier_ready_mirror, "Sacrifice") { sacrifice() }
        bindLoc(SepLocs.barrier_final, "Pass-through") { crossThin(it, SepSeqs.squeeze) }
        bindLoc(SepLocs.bridge_ready, "Fix") { fixBridge() }
        bindLoc(SepLocs.bridge_pool_ready, "Fix") { fixBridge() }
        bindLoc(SepLocs.bridge_final, "Walk-over") { crossBridge(it) }
        bindLoc(SepLocs.bridge_pool_final, "Walk-over") { crossBridge(it) }
        bindLoc(SepLocs.portal_ready, "Conjure") { conjure() }
        bindLoc(SepLocs.portal_final, "Pass-through") { crossThin(it, SepSeqs.squeeze) }

        bindNpc(SepNpcs.stranger, "Talk-to") { talkTo(it) }
        bindNpc(SepNpcs.stranger, "Trade") { shop(it) }

        if (!onOpHeldLabel(objTypes, SepObjs.token, "Activate") { useToken() }) skipped++
        if (!onOpHeldLabel(objTypes, SepObjs.sack, "Open") { openSack() }) skipped++

        onPlayerSoftTimer(SepTimers.tick) { tick(player) }
        onPlayerLogout { logout(player) }
        onPlayerLogin { login(player) }
        if (skipped > 0) logger.info { "Sepulchre: $skipped op binds skipped (missing label or already bound)." }
    }

    /* Binding helpers (ops located by label; skip ops another script already owns). */

    private fun locOp(type: LocType, label: String): Int =
        locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1

    private fun ScriptContext.bindLoc(type: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locOp(type, label)
        if (op <= 0 || isLocOpBound(type, op)) {
            skipped++
            return
        }
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
            else -> skipped++
        }
    }

    private fun ScriptContext.bindNpc(type: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op <= 0 || isNpcOpBound(type, op)) {
            skipped++
            return
        }
        when (op) {
            1 -> onOpNpc1(type) { action(it.npc) }
            3 -> onOpNpc3(type) { action(it.npc) }
            else -> skipped++
        }
    }

    /* State */

    private fun Player.run(): Run? = runs[uid.packed]

    private fun def(floor: Int): FloorDef = SepulchreMap.FLOORS[floor - 1]

    private fun putVar(player: Player, varbit: VarBitType, value: Int) = VarPlayerIntMapSetter.set(player, varbit, value)

    private fun resetTreasure(player: Player) {
        for (varbit in SepVarBits.treasure) putVar(player, varbit, 0)
    }

    private fun clearRunVars(player: Player) {
        resetTreasure(player)
        putVar(player, SepVarBits.time_remaining, 0)
        putVar(player, SepVarBits.floor_time_spent, 0)
        putVar(player, SepVarBits.current_floor, 0)
    }

    private fun syncHud(player: Player, run: Run) {
        val units = run.ticksLeft / SepulchreTuning.HUD_TICKS_PER_UNIT
        val shown = if (run.ticksLeft > 0) units.coerceIn(SepulchreTuning.HUD_MIN_LIVE, SepulchreTuning.VARBIT_13_MAX) else 0
        putVar(player, SepVarBits.time_remaining, shown)
        putVar(player, SepVarBits.current_floor, run.floor)
        putVar(player, SepVarBits.floor_time_spent, (run.floorTicks / SepulchreTuning.HUD_TICKS_PER_UNIT).coerceAtMost(SepulchreTuning.VARBIT_13_MAX))
        putVar(player, SepVarBits.time_spent, (run.totalTicks / SepulchreTuning.HUD_TICKS_PER_UNIT).coerceAtMost(SepulchreTuning.VARBIT_13_MAX))
    }

    private fun xp(player: Player, amount: Double, stat: StatType): Double = amount * xpMods.get(player, stat)

    /* Movement helpers */

    /** The walkable tile nearest [pin] (rings outward), ties broken towards [toward]. */
    private fun land(pin: CoordGrid, toward: CoordGrid = pin): CoordGrid {
        for (r in 0..SepulchreTuning.LAND_RADIUS) {
            var best: CoordGrid? = null
            var bestDist = Int.MAX_VALUE
            for (dz in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val tile = pin.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) != 0) continue
                    val dist = abs(tile.x - toward.x) + abs(tile.z - toward.z)
                    if (dist < bestDist) {
                        best = tile
                        bestDist = dist
                    }
                }
            }
            if (best != null) return best
        }
        return pin
    }

    /** Footprint size after rotation (angles 1 and 3 swap width and length). */
    private fun sizeX(loc: BoundLocInfo): Int = if (loc.angleId % 2 == 1) loc.length else loc.width

    private fun sizeZ(loc: BoundLocInfo): Int = if (loc.angleId % 2 == 1) loc.width else loc.length

    private fun footprintCentre(loc: BoundLocInfo): CoordGrid = loc.coords.translate(sizeX(loc) / 2, sizeZ(loc) / 2)

    private suspend fun ProtectedAccess.hop(seq: SeqType?, ticks: Int, dest: CoordGrid) {
        if (seq != null) anim(seq)
        delay(ticks)
        telejump(dest)
    }

    private fun far(from: CoordGrid, a: CoordGrid, b: CoordGrid): CoordGrid {
        val da = abs(from.x - a.x) + abs(from.z - a.z)
        val db = abs(from.x - b.x) + abs(from.z - b.z)
        return if (da >= db) a else b
    }

    private fun ProtectedAccess.crossingXp(site: Int, bonus: Double = 0.0) {
        val run = player.run() ?: return
        val done = run.crossings[site] ?: 0
        run.crossings[site] = done + 1
        if (done >= 2) return
        statAdvance(stats.agility, xp(player, def(run.floor).crossingXp + bonus, stats.agility))
    }

    /* Lobby and entry */

    private suspend fun ProtectedAccess.enterLobby() {
        // TODO: Sins of the Father completion check (myq5 values not looked up).
        hop(null, 1, land(SepulchreMap.LOBBY_ARRIVAL))
    }

    private suspend fun ProtectedAccess.exitLobby() {
        hop(null, 1, land(SepulchreMap.DARKMEYER_OUTSIDE))
    }

    private suspend fun ProtectedAccess.startRun() {
        val first = def(1)
        if (player.agilityLvl < first.agility) {
            mes("You need an Agility level of ${first.agility} to enter the Hallowed Sepulchre.")
            return
        }
        if (player.run() != null) return
        val run = Run()
        runs[player.uid.packed] = run
        ifOpenOverlay(SepInterfaces.overlay)
        enterFloor(run, 1)
        player.softTimer(SepTimers.tick, SepulchreTuning.LOOP_TICKS)
        mes("You descend into the Hallowed Sepulchre. Reach the centre of each floor before time runs out.")
    }

    private suspend fun ProtectedAccess.enterFloor(run: Run, floor: Int) {
        val def = def(floor)
        run.floor = floor
        run.safe = false
        run.xpPaid = false
        run.expired = false
        run.floorTicks = 0
        run.crossings.clear()
        run.ticksLeft = (run.ticksLeft + SepulchreTuning.FLOOR_TICKS).coerceAtMost(SepulchreTuning.MAX_TICKS)
        resetTreasure(player)
        putVar(player, SepVarBits.ranged_state, 1)
        putVar(player, SepVarBits.prayer_state, 1)
        putVar(player, SepVarBits.construction_state, 1)
        putVar(player, SepVarBits.magic_state, 1)
        val entrance = def.entrances[random.of(def.entrances.size)]
        syncHud(player, run)
        hop(null, 1, land(entrance.translate(1, 1), def.centre))
    }

    /** Ends the run and returns to the lobby; [pay] gives the floor xp if not already paid. */
    private suspend fun ProtectedAccess.leave(pay: Boolean, pet: Boolean) {
        val run = runs.remove(player.uid.packed)
        if (run != null) {
            if (pay) payFloor(run)
            if (pet) rollPet(run)
            player.clearSoftTimer(SepTimers.tick)
        }
        clearRunVars(player)
        ifCloseSub(SepInterfaces.overlay)
        hop(null, 1, land(SepulchreMap.LOBBY_RETURN))
    }

    private fun ProtectedAccess.payFloor(run: Run) {
        if (run.xpPaid) return
        run.xpPaid = true
        statAdvance(stats.agility, xp(player, def(run.floor).xp, stats.agility))
    }

    private fun ProtectedAccess.rollPet(run: Run) {
        if (random.of(def(run.floor).petChance) != 0) return
        if (SepObjs.pet in inv) return
        invAddOrDrop(objRepo, SepObjs.pet)
        mes("You have a funny feeling like you're being followed.")
    }

    private fun logout(player: Player) {
        val had = runs.remove(player.uid.packed) != null
        player.clearSoftTimer(SepTimers.tick)
        if (had || SepulchreMap.onFloors(player.coords)) player.coords = SepulchreMap.LOBBY_RETURN
    }

    private fun login(player: Player) {
        runs.remove(player.uid.packed)
        if (SepulchreMap.onFloors(player.coords)) {
            player.coords = SepulchreMap.LOBBY_RETURN
            clearRunVars(player)
        }
    }

    /* Loop */

    private fun tick(player: Player) {
        val run = player.run()
        if (run == null) {
            player.clearSoftTimer(SepTimers.tick)
            return
        }
        run.offFloors = if (SepulchreMap.onFloors(player.coords)) 0 else run.offFloors + 1
        if (run.offFloors >= SepulchreTuning.OFF_FLOOR_TICKS) {
            // Died or teleported away: drop the run quietly.
            runs.remove(player.uid.packed)
            player.clearSoftTimer(SepTimers.tick)
            clearRunVars(player)
            launcher.launchLenient(player) { ifCloseSub(SepInterfaces.overlay) }
            return
        }
        val def = def(run.floor)
        val c = player.coords
        if (!run.safe && c.level == def.centre.level && c.x in def.safeX && c.z in def.safeZ) run.safe = true
        if (!run.safe && run.ticksLeft > 0) {
            run.ticksLeft -= SepulchreTuning.LOOP_TICKS
            run.floorTicks += SepulchreTuning.LOOP_TICKS
            run.totalTicks += SepulchreTuning.LOOP_TICKS
            if (run.ticksLeft <= 0) {
                run.ticksLeft = 0
                expire(player, run)
            }
        }
        syncHud(player, run)
    }

    private fun expire(player: Player, run: Run) {
        run.expired = true
        // Wiki: unbuilt challenges can no longer be done; built bridges/portals still cross.
        for (varbit in listOf(SepVarBits.prayer_state, SepVarBits.construction_state, SepVarBits.magic_state)) {
            if (player.vars[varbit] == 1) putVar(player, varbit, 0)
        }
        player.mes("You have run out of time! You can still reach the centre for experience, but the way down is sealed.")
    }

    private fun ProtectedAccess.timeLeft(): Boolean {
        val run = player.run() ?: return false
        if (run.ticksLeft > 0) return true
        mes("You have run out of time.")
        return false
    }

    /* Floor chain */

    private suspend fun ProtectedAccess.dropDown(loc: BoundLocInfo) {
        val run = player.run() ?: return
        val dest = footprintCentre(loc).translate(0, 0, -1)
        hop(SepSeqs.jump, 1, land(dest, def(run.floor).centre))
    }

    /** End platforms and the floor-4 gate: two tiles on towards the floor's centre. */
    private suspend fun ProtectedAccess.jumpIn(loc: BoundLocInfo, seq: SeqType) {
        val run = player.run() ?: return
        val centre = def(run.floor).centre
        val dx = centre.x - loc.x
        val dz = centre.z - loc.z
        val dest =
            if (abs(dx) >= abs(dz)) loc.coords.translate(2 * Integer.signum(dx), 0) else loc.coords.translate(0, 2 * Integer.signum(dz))
        hop(seq, 1, land(dest, centre))
        run.safe = true
    }

    private suspend fun ProtectedAccess.jumpStone(loc: BoundLocInfo) {
        val run = player.run() ?: return
        hop(SepSeqs.stone, 1, loc.coords)
        // Floor 5: the stones are the last obstacle before the Grand coffin's antechamber.
        if (run.floor == 5) run.safe = true
    }

    private suspend fun ProtectedAccess.descend(loc: BoundLocInfo) {
        val run = player.run() ?: return
        val floor = SepLocs.stairs_floor.indexOfFirst { locTypes[it].id == loc.id } + 1
        if (floor != run.floor || floor !in 1..4) return
        if (run.ticksLeft <= 0) {
            mes("The stairs are sealed. You ran out of time.")
            return
        }
        val next = def(floor + 1)
        if (player.agilityLvl < next.agility) {
            mes("You need an Agility level of ${next.agility} to go any deeper.")
            return
        }
        run.safe = true
        payFloor(run)
        enterFloor(run, floor + 1)
        mes("You descend to floor ${floor + 1} of the Hallowed Sepulchre.")
    }

    /* Obelisks */

    private suspend fun ProtectedAccess.exitObelisk() {
        val run = player.run() ?: return
        var go = false
        startDialogue {
            go = choice2("Return to the lobby. (Floor ${run.floor} experience.)", true, "Stay.", false)
        }
        if (go) leave(pay = true, pet = true)
    }

    private fun ProtectedAccess.activateObelisk() {
        val names = player.worn.mapNotNull { obj -> obj?.let { objTypes[it].name } }
        when {
            names.any { it.contains("Saradomin", ignoreCase = true) } -> {
                player.runEnergy = constants.run_max_energy
                mes("The obelisk restores your energy.")
            }
            names.any { it.contains("Zamorak", ignoreCase = true) } -> {
                player.runEnergy = 0
                queueHit(1, HitType.Typeless, SepulchreTuning.ZAMORAK_HP)
                mes("The obelisk drains your energy.")
            }
            names.any { n -> listOf("Armadyl", "Bandos", "Guthix").any { n.contains(it, ignoreCase = true) } } -> {
                val drain = constants.run_max_energy * SepulchreTuning.MINOR_GOD_DRAIN / 100
                player.runEnergy = (player.runEnergy - drain).coerceAtLeast(0)
                queueHit(1, HitType.Typeless, SepulchreTuning.MINOR_GOD_HP)
                mes("The obelisk drains some of your energy.")
            }
            else -> mes("Nothing happens. Perhaps the obelisk favours followers of Saradomin.")
        }
    }

    /* Skill challenges */

    private suspend fun ProtectedAccess.grapple(loc: BoundLocInfo) {
        val site = SepulchreMap.PILLARS.firstOrNull { onLane(it, loc) } ?: return
        if (player.rangedLvl < SepulchreTuning.RANGED_LEVEL) {
            mes("You need a Ranged level of ${SepulchreTuning.RANGED_LEVEL} to grapple across.")
            return
        }
        val hallowed = SepObjs.grapple in inv
        val crossbow = objTypes.getOrNull(player.righthand)?.name?.contains("rossbow", ignoreCase = true) == true
        val quiver = player.worn[Wearpos.Quiver.slot]
        val mith = SepObjs.mith_grapple in inv || (quiver != null && quiver.id == objTypes[SepObjs.mith_grapple].id)
        if (!hallowed && !(crossbow && mith)) {
            mes("You need a crossbow and a mithril grapple, or a hallowed grapple, to cross.")
            return
        }
        putVar(player, SepVarBits.grapple_type, if (hallowed) 1 else 0)
        hop(SepSeqs.crossbow, 2, land(far(player.coords, site.low, site.high)))
        crossingXp(site.origin.packed, site.bonusXp)
    }

    /** The pillar [loc] stands beside [site]'s grapple lane (within one tile of it, along its span). */
    private fun onLane(site: PillarSite, loc: BoundLocInfo): Boolean {
        val o = site.origin
        if (o.level != loc.level) return false
        val span = o.x - 1..o.x + SepulchreTuning.PILLAR_SPAN
        val spanZ = o.z - 1..o.z + SepulchreTuning.PILLAR_SPAN
        return if (site.alongX) abs(loc.z - o.z) <= 1 && loc.x in span else abs(loc.x - o.x) <= 1 && loc.z in spanZ
    }

    private fun ProtectedAccess.sacrifice() {
        if (!timeLeft()) return
        if (player.prayerLvl < SepulchreTuning.PRAYER_LEVEL) {
            mes("You need a Prayer level of ${SepulchreTuning.PRAYER_LEVEL} to light the brazier.")
            return
        }
        val dust = if (SepObjs.symbol in inv) 1 else SepulchreTuning.DUST
        if (invTotal(inv, SepObjs.vampire_dust) < dust) {
            mes("You need $dust vampyre dust to light the brazier.")
            return
        }
        invDel(inv, SepObjs.vampire_dust, dust)
        anim(SepSeqs.sacrifice)
        putVar(player, SepVarBits.ashes_offered, 1)
        putVar(player, SepVarBits.prayer_state, 2)
        statAdvance(stats.prayer, xp(player, SepulchreTuning.PRAYER_XP, stats.prayer))
        mes("The brazier flares up and the holy barrier weakens.")
    }

    private fun ProtectedAccess.fixBridge() {
        if (!timeLeft()) return
        if (player.constructionLvl < SepulchreTuning.CONSTRUCTION_LEVEL) {
            mes("You need a Construction level of ${SepulchreTuning.CONSTRUCTION_LEVEL} to fix the bridge.")
            return
        }
        if ((SepObjs.hammer !in inv && SepObjs.hammer_hallowed !in inv) || SepObjs.saw !in inv) {
            mes("You need a hammer and a saw to fix the bridge.")
            return
        }
        val tier =
            SepObjs.bridgeTiers.firstOrNull { (plank, nails, _) -> invTotal(inv, plank) >= 2 && invTotal(inv, nails) >= 5 }
        if (tier == null) {
            mes("You need 2 planks and 5 matching nails to fix the bridge.")
            return
        }
        invDel(inv, tier.first, 2)
        invDel(inv, tier.second, 5)
        anim(SepSeqs.build)
        putVar(player, SepVarBits.construction_state, 2)
        statAdvance(stats.construction, xp(player, tier.third, stats.construction))
        mes("You fix the bridge.")
    }

    private suspend fun ProtectedAccess.crossBridge(loc: BoundLocInfo) {
        val site =
            SepulchreMap.BRIDGES.firstOrNull { s ->
                s.a.level == loc.level && abs(s.a.x - loc.x) <= 1 && loc.z in minOf(s.a.z, s.b.z)..maxOf(s.a.z, s.b.z)
            } ?: return
        hop(null, 3, land(far(player.coords, site.a, site.b)))
        crossingXp(site.a.packed)
    }

    private fun ProtectedAccess.conjure() {
        if (!timeLeft()) return
        if (player.magicLvl < SepulchreTuning.MAGIC_LEVEL) {
            mes("You need a Magic level of ${SepulchreTuning.MAGIC_LEVEL} to conjure the portal.")
            return
        }
        if (invTotal(inv, SepObjs.cosmic) < SepulchreTuning.CONJURE_COSMICS) {
            mes("You need a cosmic rune to cast an enchantment on the portal frame.")
            return
        }
        invDel(inv, SepObjs.cosmic, SepulchreTuning.CONJURE_COSMICS)
        anim(SepSeqs.conjure)
        putVar(player, SepVarBits.magic_state, 2)
        statAdvance(stats.magic, xp(player, SepulchreTuning.MAGIC_XP, stats.magic))
        mes("The portal frame shimmers into life.")
    }

    /** Through a portal or holy barrier: to the other side of its thin axis. */
    private suspend fun ProtectedAccess.crossThin(loc: BoundLocInfo, seq: SeqType) {
        hop(seq, 1, land(across(player.coords, loc)))
        crossingXp(loc.coords.packed)
    }

    private fun across(from: CoordGrid, loc: BoundLocInfo): CoordGrid {
        val sx = sizeX(loc)
        val sz = sizeZ(loc)
        val alongZ =
            when {
                sx > sz -> true
                sz > sx -> false
                else -> abs(from.z - loc.z) >= abs(from.x - loc.x)
            }
        return if (alongZ) {
            val z = if (from.z < loc.z) loc.z + sz else loc.z - 1
            CoordGrid(from.x.coerceIn(loc.x, loc.x + sx - 1), z, loc.level)
        } else {
            val x = if (from.x < loc.x) loc.x + sx else loc.x - 1
            CoordGrid(x, from.z.coerceIn(loc.z, loc.z + sz - 1), loc.level)
        }
    }

    private suspend fun ProtectedAccess.passGrandBarrier(loc: BoundLocInfo) {
        val run = player.run() ?: return
        hop(SepSeqs.squeeze, 1, land(across(player.coords, loc)))
        // Wiki: unlimited time once past the barrier.
        run.safe = true
    }

    /* Coffins */

    private fun lootedVar(loc: BoundLocInfo): VarBitType? =
        when (loc.id) {
            locTypes[SepLocs.coffin_a].id -> SepVarBits.looted
            locTypes[SepLocs.coffin_b].id -> SepVarBits.looted_b
            locTypes[SepLocs.f5_grapple_coffin].id -> SepVarBits.f5_grapple_looted
            locTypes[SepLocs.f5_portal_coffin].id -> SepVarBits.f5_portal_looted
            locTypes[SepLocs.f5_bridge_coffin].id -> SepVarBits.f5_bridge_looted
            locTypes[SepLocs.grand_coffin].id -> SepVarBits.final_looted
            else -> null
        }

    private suspend fun ProtectedAccess.searchCoffin(loc: BoundLocInfo, grand: Boolean) {
        val run = player.run() ?: return
        val looted = lootedVar(loc) ?: return
        if (player.vars[looted] != 0) return
        if (!grand && !timeLeft()) return
        if (grand) {
            val ok =
                player.thievingLvl >= SepulchreTuning.GRAND_THIEVING ||
                    (player.thievingLvl >= SepulchreTuning.GRAND_THIEVING_ALT && player.agilityLvl >= SepulchreTuning.GRAND_AGILITY_ALT)
            if (!ok) {
                mes(
                    "You need a Thieving level of ${SepulchreTuning.GRAND_THIEVING}, or ${SepulchreTuning.GRAND_THIEVING_ALT} " +
                        "Thieving and ${SepulchreTuning.GRAND_AGILITY_ALT} Agility, to open the Grand Hallowed Coffin.",
                )
                return
            }
        } else if (player.thievingLvl < SepulchreTuning.COFFIN_THIEVING) {
            mes("You need a Thieving level of ${SepulchreTuning.COFFIN_THIEVING} to open this coffin.")
            return
        }
        anim(SepSeqs.open)
        delay(2)
        val (low, high) =
            when {
                SepObjs.strange_lockpick in inv || SepObjs.strange_lockpick_full in inv ->
                    SepulchreTuning.STRANGE_LOW to SepulchreTuning.STRANGE_HIGH
                SepObjs.lockpick in inv -> SepulchreTuning.PICK_LOW to SepulchreTuning.PICK_HIGH
                else -> SepulchreTuning.NO_PICK_LOW to SepulchreTuning.NO_PICK_HIGH
            }
        if (!statRandom(stats.thieving, low, high, invisibleLvls)) {
            player.applyPoison(SepulchreTuning.FAIL_POISON)
            mes("You set off a trap and are poisoned!")
            return
        }
        putVar(player, looted, 1)
        statAdvance(stats.thieving, xp(player, SepulchreTuning.COFFIN_XP, stats.thieving))
        if (grand) {
            putVar(player, SepVarBits.final_chest_count, (player.vars[SepVarBits.final_chest_count] + 1).coerceAtMost(SepulchreTuning.VARBIT_13_MAX))
            grandLoot()
        } else {
            putVar(player, SepVarBits.normal_chest_count, (player.vars[SepVarBits.normal_chest_count] + 1).coerceAtMost(SepulchreTuning.VARBIT_13_MAX))
            coffinLoot(def(run.floor))
        }
        mes("You loot the coffin.")
    }

    private fun ProtectedAccess.give(obj: ObjType, count: Int) {
        if (count > 0) invAddOrDrop(objRepo, obj, count)
    }

    private fun ProtectedAccess.roll(table: List<Drop>) {
        val drop = table[random.of(table.size)]
        give(drop.obj, random.of(drop.min, drop.max))
    }

    private fun ProtectedAccess.clue(tier: String) {
        val obj = ClueDrops.resolver?.resolve(player, tier) ?: return
        give(obj, 1)
    }

    private fun ProtectedAccess.coffinLoot(def: FloorDef) {
        give(SepObjs.mark, random.of(def.marks.first, def.marks.last))
        val tier = random.of(100)
        roll(
            when {
                tier < def.lowPct -> SepObjs.low
                tier < def.lowPct + def.midPct -> SepObjs.mid
                else -> SepObjs.high
            },
        )
        if (random.of(def.lockpickChance) == 0) give(SepObjs.strange_lockpick_full, 1)
        val page = SepObjs.pages[def.floor - 1]
        if (page !in inv && random.of(SepulchreTuning.PAGE_CHANCE) == 0) give(page, 1)
        if (random.of(def.clueChance) == 0) clue(def.clueTier)
    }

    private fun ProtectedAccess.grandLoot() {
        give(SepObjs.mark, random.of(SepulchreTuning.GRAND_MARKS.first, SepulchreTuning.GRAND_MARKS.last))
        roll(SepObjs.high)
        if (random.of(SepulchreTuning.GRAND_RING_CHANCE) == 0) give(SepObjs.ring_of_endurance, 1)
        if (random.of(SepulchreTuning.GRAND_CLUE_CHANCE) == 0) clue("elite")
    }

    /* Held items */

    private fun ProtectedAccess.useToken() {
        val run = player.run()
        if (run == null || run.ticksLeft <= 0) {
            mes("You can only use a hallowed token in the Sepulchre while time remains.")
            return
        }
        invDel(inv, SepObjs.token, 1)
        run.ticksLeft = (run.ticksLeft + SepulchreTuning.TOKEN_TICKS).coerceAtMost(SepulchreTuning.MAX_TICKS)
        syncHud(player, run)
        mes("The token crumbles and you feel time slow around you.")
    }

    private fun ProtectedAccess.openSack() {
        invDel(inv, SepObjs.sack, 1)
        repeat(SepulchreTuning.SACK_LOW) { roll(SepObjs.low) }
        repeat(SepulchreTuning.SACK_MID) { roll(SepObjs.mid) }
        repeat(SepulchreTuning.SACK_HIGH) { roll(SepObjs.high) }
        mes("You empty the hallowed sack.")
    }

    /* Mysterious Stranger */

    private class Ware(val name: String, val obj: ObjType, val marks: Int, val bulk: Boolean = false)

    private val wares by lazy {
        listOf(
            Ware("Hallowed crystal shard", SepObjs.shard, 1, bulk = true),
            Ware("Hallowed token", SepObjs.token, 10, bulk = true),
            Ware("Hallowed grapple", SepObjs.grapple, 100),
            Ware("Hallowed focus", SepObjs.focus, 100),
            Ware("Hallowed symbol", SepObjs.symbol, 100),
            Ware("Hallowed hammer", SepObjs.hammer_hallowed, 100),
            Ware("Hallowed sack", SepObjs.sack, 100),
            Ware("Hallowed ring", SepObjs.ring, 250),
            Ware("Dark dye", SepObjs.dark_dye, 300),
            Ware("Dark acorn", SepObjs.dark_acorn, 3_000),
        )
    }

    private fun label(ware: Ware): String = "${ware.name} - ${"%,d".format(ware.marks)} marks"

    private suspend fun ProtectedAccess.talkTo(npc: Npc) {
        var wants = false
        // Authored (not Wiki): short lines in the Stranger's voice.
        startDialogue(npc) {
            chatNpc(shifty, "Down those stairs lie the Hallowed Sepulchre's five floors. Beat the clock, loot the coffins, and bring me the marks.")
            wants = choice2("Show me your goods.", true, "Maybe later.", false)
        }
        if (wants) shop(npc)
    }

    private suspend fun ProtectedAccess.shop(npc: Npc) {
        var picked: Ware? = null
        startDialogue(npc) {
            val first = choice5(label(wares[0]), 0, label(wares[1]), 1, label(wares[2]), 2, label(wares[3]), 3, "More...", -1)
            picked =
                if (first >= 0) {
                    wares[first]
                } else {
                    val second = choice5(label(wares[4]), 4, label(wares[5]), 5, label(wares[6]), 6, label(wares[7]), 7, "More...", -1)
                    if (second >= 0) {
                        wares[second]
                    } else {
                        wares.getOrNull(choice3(label(wares[8]), 8, label(wares[9]), 9, "Nothing, thanks.", -1))
                    }
                }
        }
        val ware = picked ?: return
        val count = if (ware.bulk) countDialog("How many? (${ware.marks} mark${if (ware.marks == 1) "" else "s"} each)") else 1
        if (count <= 0) return
        val amount = count.coerceAtMost(SepulchreTuning.SHOP_MAX_COUNT)
        val cost = ware.marks * amount
        val marks = invTotal(inv, SepObjs.mark)
        if (marks < cost) {
            mes("You need ${"%,d".format(cost)} hallowed marks for that; you have ${"%,d".format(marks)}.")
            return
        }
        invDel(inv, SepObjs.mark, cost)
        give(ware.obj, amount)
        mes("You trade ${"%,d".format(cost)} hallowed marks for ${if (amount > 1) "$amount x " else ""}${ware.name.lowercase()}.")
    }

    private companion object {
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
    }
}
