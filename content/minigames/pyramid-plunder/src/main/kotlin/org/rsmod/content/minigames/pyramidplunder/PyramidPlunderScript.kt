package org.rsmod.content.minigames.pyramidplunder

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.baseThievingLvl
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Pyramid Plunder (Jalsavrah, Sophanem), solo. Data: OSRS Wiki pages + the cache (see
 * ~/code/rsmod-pyramidplunder-notes.md, skilling's research).
 *
 * - Search the pyramid doors outside (one is correct per player, re-rolled every 25 minutes;
 *   wrong ones lead to an abandoned antechamber). The guardian mummy in the lobby starts a run
 *   (Talk-to or Start-minigame; 21 Thieving): 500 ticks (5:00) to plunder up to 8 rooms (room r
 *   needs 21 + 10(r-1) Thieving), then out the north door.
 * - Each room: pass the speartrap, search urns (or check for snakes, then charm and search), the
 *   Grand Gold Chest (a fail lets out a scarab swarm), the sarcophagus (Strength; may hold a
 *   mummy), and pick the tomb doors: one per room leads on (re-rolled per run; the 4th try always
 *   works). xp / loot / sceptre odds are the Wiki's; tick timings are authored.
 * - Vars: room state varbits on varps ntk_urn / ntk_var2 / ntk_var_temp, always written as
 *   varbits (never the shared varps). The HUD (ntk_overlay) reads ntk_player_timer_count.
 * - Not yet: Rocky (pet), the sceptre recharge dialogue, poison on snake bites / swarms.
 */
class PyramidPlunderScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val npcRepo: NpcRepository,
    private val npcTypes: NpcTypeList,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private class Room(
        val entry: CoordGrid,
        /** Speartrap passage: vertical (x fixed) or horizontal (z fixed), the fixed line, lo..hi. */
        val trapVertical: Boolean,
        val trapLine: Int,
        val trapLo: Int,
        val trapHi: Int,
    )

    private val rooms =
        listOf(
            Room(CoordGrid(1927, 4477), true, 1927, 4472, 4473),
            Room(CoordGrid(1954, 4477), true, 1954, 4473, 4474),
            Room(CoordGrid(1977, 4471), true, 1976, 4465, 4466),
            Room(CoordGrid(1927, 4453), false, 4452, 1930, 1931),
            Room(CoordGrid(1965, 4444), false, 4444, 1961, 1962),
            Room(CoordGrid(1927, 4424), true, 1926, 4427, 4428),
            Room(CoordGrid(1943, 4421), true, 1944, 4424, 4425),
            Room(CoordGrid(1974, 4420), true, 1974, 4423, 4424),
        )

    /** One player's run. */
    private class Run {
        var tick = 0
        var room = 1
        var correctDoor = 1
        var wrongTries = 0
        val spawned = ArrayList<Npc>()
    }

    private val runs = WeakHashMap<Player, Run>()

    /** Correct outside door per player (1..4 = N, E, S, W) and when it re-rolls. */
    private val entrances = WeakHashMap<Player, Pair<Int, Int>>()

    private val urnBases by lazy { PpLocs.urn_bases.mapIndexed { i, t -> t.id to i + 1 }.toMap() }
    private val doorBases by lazy { PpLocs.door_bases.mapIndexed { i, t -> t.id to i + 1 }.toMap() }
    private val pyramidDoorBases by lazy {
        PpLocs.pyramid_doors.mapIndexed { i, t -> t.id to i + 1 }.toMap()
    }

    override fun ScriptContext.startup() {
        val mummy = PpNpcs.guardian_mummy
        if (!isNpcOpBound(mummy, 1)) onOpNpc1(mummy) { talkMummy(it.npc) }
        if (!isNpcOpBound(mummy, 3)) onOpNpc3(mummy) { startRun() }
        for (door in PpLocs.pyramid_door_children) bind(door, 1) { searchPyramidDoor(it) }
        bind(PpLocs.speartrap, 1) { passTrap() }
        for (urn in PpLocs.urn_closed) {
            bind(urn, 1) { searchUrn(it, charmed = false) }
            bind(urn, 2) { checkUrn(it) }
        }
        for (urn in PpLocs.urn_snake) bind(urn, 3) { charmUrn(it) }
        for (urn in PpLocs.urn_charmed) bind(urn, 1) { searchUrn(it, charmed = true) }
        bind(PpLocs.chest_closed, 1) { searchChest() }
        bind(PpLocs.sarcophagus_closed, 1) { openSarcophagus() }
        bind(PpLocs.sarcophagus_open, 1) { searchSarcophagus() }
        bind(PpLocs.tomb_door, 1) { pickDoor(it) }
        bind(PpLocs.tomb_exit, 1) { leave(timeout = false) }
        bind(PpLocs.tomb_exit, 2) { leave(timeout = false) }
        bind(PpLocs.antechamber_exit, 1) { telejump(OUTSIDE) }
        onPlayerSoftTimer(PpTimers.tick) { player.runTick() }
        onPlayerLogout { player.endRun() }
    }

    private fun ScriptContext.bind(type: LocType, op: Int, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (isLocOpBound(type, op)) {
            return
        }
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
        }
    }

    /* Outside and the lobby */

    private suspend fun ProtectedAccess.searchPyramidDoor(loc: BoundLocInfo) {
        val side = pyramidDoorBases[loc.id] ?: return
        val (correct, until) = entrances[player] ?: (0 to 0)
        val now = currentMinute()
        val entrance =
            if (correct == 0 || now >= until) {
                (random.of(4) + 1).also { entrances[player] = it to now + ENTRANCE_MINUTES }
            } else {
                correct
            }
        anim(PpSeqs.search)
        delay(2)
        if (!statRandom(stats.thieving, 160, 250, 0)) {
            mes("You're shoved back by an unseen force.")
            queueHit(1, HitType.Typeless, random.of(1, 4))
            return
        }
        xp(stats.thieving, PYRAMID_DOOR_XP)
        if (side == entrance) {
            if (player.baseThievingLvl < MIN_THIEVING) {
                mes("You need a Thieving level of $MIN_THIEVING to enter the tombs.")
                return
            }
            telejump(LOBBY)
        } else {
            telejump(ABANDONED[side - 1])
            mes("This antechamber has been plundered long ago.")
        }
    }

    private suspend fun ProtectedAccess.talkMummy(npc: Npc) {
        val first = vars[PpVarbits.played_before] == 0
        var go = false
        startDialogue(npc) {
            if (first) {
                // Authored lines (paraphrasing the Wiki transcript).
                chatNpc(
                    neutral,
                    "Mortal! You dare enter the tomb of the pharaohs? Its treasures are " +
                        "guarded, but you may try your luck - for five minutes.",
                )
            }
            chatNpc(neutral, "Do you wish to enter the tombs?")
            go = choice2("That sounds like fun.", true, "Not right now.", false)
        }
        if (go) startRun()
    }

    private fun ProtectedAccess.startRun() {
        if (player.baseThievingLvl < MIN_THIEVING) {
            mes("You need a Thieving level of $MIN_THIEVING to plunder the tombs.")
            return
        }
        player.endRun()
        val run = Run()
        run.correctDoor = random.of(4) + 1
        runs[player] = run
        setVarbit(PpVarbits.played_before, 1)
        player.softTimer(PpTimers.tick, 1)
        player.ifOpenOverlay(PpInterfaces.overlay, components.toplevel_target_overlay_hud, eventBus)
        enterRoom(run, 1)
    }

    private fun ProtectedAccess.enterRoom(run: Run, room: Int) {
        run.room = room
        run.correctDoor = random.of(4) + 1
        run.wrongTries = 0
        despawn(run)
        resetRoomVars()
        setVarbit(PpVarbits.room_number, room)
        setVarbit(PpVarbits.thieving_required, MIN_THIEVING + (room - 1) * 10)
        setVarbit(PpVarbits.current_room_level, room)
        telejump(rooms[room - 1].entry)
        mes("Room $room of 8.")
    }

    /** Room varbits back to 0 (urns, chest, sarcophagus, doors, trap). Never the whole varps. */
    private fun ProtectedAccess.resetRoomVars() {
        for (varbit in PpVarbits.urns) setVarbit(varbit, 0)
        for (varbit in PpVarbits.doors) setVarbit(varbit, 0)
        setVarbit(PpVarbits.chest_state, 0)
        setVarbit(PpVarbits.sarcophagus_state, 0)
        setVarbit(PpVarbits.sarcophagus_push, 0)
        setVarbit(PpVarbits.trap_active, 0)
    }

    private fun ProtectedAccess.setVarbit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun Player.runTick() {
        val run = runs[this] ?: return clearSoftTimer(PpTimers.tick)
        run.tick++
        if (run.tick % HUD_TICKS == 0 || run.tick >= RUN_TICKS) {
            VarPlayerIntMapSetter.set(this, PpVarbits.timer, run.tick.coerceAtMost(RUN_TICKS))
        }
        if (run.tick >= RUN_TICKS) {
            mes("Your time in the tombs is up!")
            endRun()
            // Out through the north door (Wiki).
            protectedAccess.launchLenient(this) { telejump(OUTSIDE) }
        }
    }

    private fun ProtectedAccess.leave(timeout: Boolean) {
        player.endRun()
        telejump(OUTSIDE)
        if (!timeout) mes("You leave the tombs.")
    }

    private fun Player.endRun() {
        val run = runs.remove(this) ?: return
        clearSoftTimer(PpTimers.tick)
        despawn(run)
        ifCloseOverlay(PpInterfaces.overlay, eventBus)
        for (varbit in PpVarbits.urns + PpVarbits.doors) VarPlayerIntMapSetter.set(this, varbit, 0)
        VarPlayerIntMapSetter.set(this, PpVarbits.timer, 0)
        VarPlayerIntMapSetter.set(this, PpVarbits.room_number, 0)
    }

    private fun despawn(run: Run) {
        for (npc in run.spawned) {
            if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
        }
        run.spawned.clear()
    }

    /* Speartrap */

    private suspend fun ProtectedAccess.passTrap() {
        val run = runs[player] ?: return
        val room = rooms[run.room - 1]
        anim(PpSeqs.search)
        delay(2)
        if (!statRandom(stats.thieving, 160, 250, 0)) {
            mes("You trigger the trap and get speared!")
            queueHit(1, HitType.Typeless, random.of(1, 4))
            return
        }
        xp(stats.thieving, TRAP_XP)
        setVarbit(PpVarbits.trap_active, 1)
        // Cross the 2-tile passage to the far side from where you stand.
        val here = player.coords
        val dest =
            if (room.trapVertical) {
                val z = if (here.z > room.trapHi) room.trapLo - 1 else room.trapHi + 1
                CoordGrid(room.trapLine, z, 0)
            } else {
                val x = if (here.x > room.trapHi) room.trapLo - 1 else room.trapHi + 1
                CoordGrid(x, room.trapLine, 0)
            }
        telejump(dest)
    }

    /* Urns */

    private suspend fun ProtectedAccess.searchUrn(loc: BoundLocInfo, charmed: Boolean) {
        val run = runs[player] ?: return
        val index = urnBases[loc.id] ?: return
        anim(PpSeqs.urn)
        delay(3)
        val (low, high) = if (charmed) 160 to 250 else 100 to 180
        val r = run.room - 1
        if (!statRandom(stats.thieving, low - r, high - r, 0)) {
            mes("You've been bitten by a snake!")
            queueHit(1, HitType.Typeless, random.of(1, 4))
            return
        }
        setVarbit(PpVarbits.urns[index - 1], 1)
        val xp = if (charmed) URN_SEARCH_XP[r] * 2 / 3 else URN_SEARCH_XP[r]
        xp(stats.thieving, xp.toDouble())
        loot(urnLoot(run.room))
    }

    private suspend fun ProtectedAccess.checkUrn(loc: BoundLocInfo) {
        val run = runs[player] ?: return
        val index = urnBases[loc.id] ?: return
        anim(PpSeqs.urn)
        delay(2)
        xp(stats.thieving, URN_CHECK_XP[run.room - 1].toDouble())
        setVarbit(PpVarbits.urns[index - 1], 2)
        mes("You check the urn and disturb a snake!")
    }

    private fun ProtectedAccess.charmUrn(loc: BoundLocInfo) {
        val index = urnBases[loc.id] ?: return
        setVarbit(PpVarbits.urns[index - 1], 3)
        mes("You charm the snake.")
    }

    /** The urn pool by room (Wiki availability; weights authored: the Wiki's are incomplete). */
    private fun urnLoot(room: Int): ObjType {
        val pool = ArrayList<ObjType>()
        if (room <= 2) pool += PpObjs.ivory_comb
        if (room <= 1) pool += PpObjs.scarab_pottery
        if (room <= 3) pool += PpObjs.statuette_pottery
        if (room <= 5) pool += PpObjs.seal_stone
        if (room <= 7) pool += PpObjs.scarab_stone
        pool += PpObjs.statuette_stone
        if (room >= 3) pool += PpObjs.seal_gold
        if (room >= 5) pool += PpObjs.scarab_gold
        if (room >= 7) pool += PpObjs.statuette_gold
        return pool[random.of(pool.size)]
    }

    /* Grand Gold Chest */

    private suspend fun ProtectedAccess.searchChest() {
        val run = runs[player] ?: return
        anim(PpSeqs.search)
        delay(2)
        setVarbit(PpVarbits.chest_state, 1)
        val r = run.room - 1
        if (statRandom(stats.thieving, 130, 220, 0)) {
            xp(stats.thieving, CHEST_XP[r].toDouble())
        } else {
            mes("A swarm of scarabs pours out of the chest!")
            spawnHostile(run, PpNpcs.scarab_swarm)
        }
        loot(rollLoot(CHEST_LOOT[r]))
        rollSceptre(run)
    }

    /* Sarcophagus */

    private suspend fun ProtectedAccess.openSarcophagus() {
        val run = runs[player] ?: return
        val r = run.room - 1
        repeat(SARCOPHAGUS_TRIES) {
            anim(PpSeqs.push)
            delay(3)
            if (statRandom(stats.strength, 80 - (r + 1), 270 - (r + 1), 0)) {
                setVarbit(PpVarbits.sarcophagus_state, 1)
                xp(stats.strength, SARCOPHAGUS_XP[r].toDouble())
                if (random.of(9) < run.room) {
                    mes("A mummy rises from the sarcophagus!")
                    spawnHostile(run, PpNpcs.mummies[random.of(PpNpcs.mummies.size)])
                }
                return
            }
        }
        mes("You fail to move the lid. Try again.")
    }

    private fun ProtectedAccess.searchSarcophagus() {
        val run = runs[player] ?: return
        setVarbit(PpVarbits.sarcophagus_state, 2)
        loot(rollLoot(SARCOPHAGUS_LOOT[run.room - 1]))
        rollSceptre(run)
    }

    /* Tomb doors */

    private suspend fun ProtectedAccess.pickDoor(loc: BoundLocInfo) {
        val run = runs[player] ?: return
        val door = doorBases[loc.id] ?: return
        anim(PpSeqs.search)
        delay(3)
        val lockpick = invTotal(inv, PpObjs.lockpick) > 0
        val (low, high) = if (lockpick) 160 to 250 else 130 to 220
        if (!statRandom(stats.thieving, low, high, 0)) {
            mes("You fail to pick the lock.")
            return
        }
        val xp = DOOR_XP[(run.room - 1).coerceAtMost(DOOR_XP.size - 1)]
        xp(stats.thieving, if (lockpick) xp / 2.0 else xp.toDouble())
        setVarbit(PpVarbits.doors[door - 1], 1)
        // Room 8: any door leads out (Wiki); elsewhere one door per room (4th try always works).
        val correct = run.room >= rooms.size || door == run.correctDoor || run.wrongTries >= 3
        if (!correct) {
            run.wrongTries++
            mes("The door leads to a dead end.")
            return
        }
        if (run.room >= rooms.size) {
            mes("You've plundered every room!")
            leave(timeout = false)
            return
        }
        val next = run.room + 1
        val needed = MIN_THIEVING + (next - 1) * 10
        if (player.baseThievingLvl < needed) {
            mes("You need a Thieving level of $needed to enter the next room.")
            return
        }
        enterRoom(run, next)
    }

    /* Loot, sceptre, hostiles */

    private fun rollLoot(table: List<Pair<ObjType, Int>>): ObjType {
        var roll = random.of(table.sumOf { it.second })
        for ((obj, weight) in table) {
            if (roll < weight) return obj
            roll -= weight
        }
        return table.last().first
    }

    private fun ProtectedAccess.loot(obj: ObjType) {
        if (invAdd(inv, obj, 1).failure) {
            objRepo.add(obj, coords, GROUND_TICKS, player)
        }
    }

    private fun ProtectedAccess.rollSceptre(run: Run) {
        if (random.of(SCEPTRE_ODDS[run.room - 1]) != 0) {
            return
        }
        mes("You find a Pharaoh's sceptre!")
        player.endRun()
        telejump(OUTSIDE)
        if (invAdd(inv, PpObjs.pharaohs_sceptre, 1).failure) {
            objRepo.add(PpObjs.pharaohs_sceptre, OUTSIDE, GROUND_TICKS, player)
        }
    }

    private fun ProtectedAccess.spawnHostile(run: Run, type: NpcType) {
        val npc = Npc(npcTypes[type], coords.translate(1, 0))
        npcRepo.add(npc, HOSTILE_TICKS)
        npc.opPlayer2(player, aiInteractions)
        run.spawned += npc
    }

    private fun ProtectedAccess.xp(stat: StatType, amount: Double) {
        statAdvance(stat, amount * xpMods.get(player, stat))
    }

    private fun currentMinute(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    private companion object {
        const val MIN_THIEVING = 21
        const val RUN_TICKS = 500
        const val HUD_TICKS = 5
        const val ENTRANCE_MINUTES = 25
        const val PYRAMID_DOOR_XP = 20.0
        const val TRAP_XP = 10.0
        const val SARCOPHAGUS_TRIES = 4
        const val HOSTILE_TICKS = 300
        const val GROUND_TICKS = 200

        val LOBBY = CoordGrid(1934, 4420, 3)
        /** Outside the north door (Wiki: leaving always puts you there). */
        val OUTSIDE = CoordGrid(3289, 2801, 0)
        /** Wrong pyramid doors: the abandoned antechambers (N, E, S, W order is a guess). */
        val ABANDONED =
            listOf(
                CoordGrid(1934, 4450, 2),
                CoordGrid(1968, 4450, 3),
                CoordGrid(1968, 4420, 2),
                CoordGrid(1934, 4450, 2),
            )

        val URN_SEARCH_XP = intArrayOf(60, 90, 150, 215, 300, 450, 675, 825)
        val URN_CHECK_XP = intArrayOf(20, 30, 50, 70, 100, 150, 225, 275)
        val CHEST_XP = intArrayOf(40, 60, 100, 140, 200, 300, 450, 550)
        val SARCOPHAGUS_XP = intArrayOf(20, 30, 50, 70, 100, 150, 225, 275)
        val DOOR_XP = intArrayOf(40, 60, 100, 140, 200, 300, 450)
        val SCEPTRE_ODDS = intArrayOf(4200, 2800, 1600, 950, 800, 750, 650, 650)

        // Wiki loot tables (weights in quarters).
        val CHEST_LOOT: List<List<Pair<ObjType, Int>>> by lazy {
            listOf(
                listOf(PpObjs.seal_stone to 1, PpObjs.scarab_stone to 2, PpObjs.statuette_stone to 1),
                listOf(PpObjs.scarab_stone to 2, PpObjs.statuette_stone to 2),
                listOf(PpObjs.scarab_stone to 1, PpObjs.statuette_stone to 2, PpObjs.seal_gold to 1),
                listOf(PpObjs.statuette_stone to 2, PpObjs.seal_gold to 2),
                listOf(PpObjs.statuette_stone to 1, PpObjs.seal_gold to 2, PpObjs.scarab_gold to 1),
                listOf(PpObjs.seal_gold to 2, PpObjs.scarab_gold to 2),
                listOf(PpObjs.seal_gold to 1, PpObjs.scarab_gold to 2, PpObjs.statuette_gold to 1),
                listOf(PpObjs.scarab_gold to 2, PpObjs.statuette_gold to 2),
            )
        }
        val SARCOPHAGUS_LOOT: List<List<Pair<ObjType, Int>>> by lazy {
            listOf(
                listOf(PpObjs.scarab_pottery to 1, PpObjs.statuette_pottery to 2, PpObjs.seal_stone to 1),
                listOf(PpObjs.statuette_pottery to 2, PpObjs.seal_stone to 2),
                listOf(PpObjs.statuette_pottery to 1, PpObjs.seal_stone to 2, PpObjs.scarab_stone to 1),
                listOf(PpObjs.seal_stone to 2, PpObjs.scarab_stone to 2),
                listOf(PpObjs.seal_stone to 1, PpObjs.scarab_stone to 2, PpObjs.statuette_stone to 1),
                listOf(PpObjs.scarab_stone to 2, PpObjs.statuette_stone to 2),
                listOf(PpObjs.scarab_stone to 1, PpObjs.statuette_stone to 2, PpObjs.seal_gold to 1),
                listOf(PpObjs.statuette_stone to 2, PpObjs.seal_gold to 2),
            )
        }
    }
}

internal object PpLocs : LocReferences() {
    val speartrap = find("ntk_speartrap_inmotion")
    val chest_closed = find("ntk_golden_chest_closed")
    val sarcophagus_closed = find("ntk_sarcophagus")
    val sarcophagus_open = find("ntk_sarcophagus_open")
    val tomb_door = find("ntk_tomb_door_noanim")
    val tomb_exit = find("ntk_tomb_door_exit")
    val antechamber_exit = find("ntk_antechamber_exit")
    val urn_closed = listOf(find("ntk_urn1_closed"), find("ntk_urn2_closed"), find("ntk_urn3_closed"))
    val urn_snake = listOf(find("ntk_urn1_snake"), find("ntk_urn2_snake"), find("ntk_urn3_snake"))
    val urn_charmed =
        listOf(find("ntk_urn1_snake_charmed"), find("ntk_urn2_snake_charmed"), find("ntk_urn3_snake_charmed"))
    /** Urn multilocs 1..15 (the base loc tells which urn varbit to use). */
    val urn_bases =
        listOf(
            find("ntk_urn_type1_multi_1"),
            find("ntk_urn_type1_multi_2"),
            find("ntk_urn_type1_multi_3"),
            find("ntk_urn_type1_multi_4"),
            find("ntk_urn_type1_multi_5"),
            find("ntk_urn_type2_multi_6"),
            find("ntk_urn_type2_multi_7"),
            find("ntk_urn_type2_multi_8"),
            find("ntk_urn_type2_multi_9"),
            find("ntk_urn_type2_multi_10"),
            find("ntk_urn_type3_multi_11"),
            find("ntk_urn_type3_multi_12"),
            find("ntk_urn_type3_multi_13"),
            find("ntk_urn_type3_multi_14"),
            find("ntk_urn_type3_multi_15"),
        )
    val door_bases =
        listOf(find("ntk_tomb_door1"), find("ntk_tomb_door2"), find("ntk_tomb_door3"), find("ntk_tomb_door4"))
    val pyramid_doors =
        listOf(
            find("ntk_pyramid_door_north_multi"),
            find("ntk_pyramid_door_east_multi"),
            find("ntk_pyramid_door_south_multi"),
            find("ntk_pyramid_door_west_multi"),
        )
    val pyramid_door_children =
        listOf(
            find("ntk_pyramid_door_north_noanim"),
            find("ntk_pyramid_door_east_noanim"),
            find("ntk_pyramid_door_south_noanim"),
            find("ntk_pyramid_door_west_noanim"),
        )
}

internal object PpNpcs : NpcReferences() {
    val guardian_mummy = find("ntk_mummy_guardian")
    val scarab_swarm = find("ntk_scarab_swarm")
    val mummies =
        listOf(find("ntk_mummy_1"), find("ntk_mummy_2"), find("ntk_mummy_3"), find("ntk_mummy_4"), find("ntk_mummy_5"))
}

internal object PpObjs : ObjReferences() {
    val ivory_comb = find("ntk_ivory_comb")
    val scarab_gold = find("ntk_scarab_gold")
    val scarab_stone = find("ntk_scarab_stone")
    val scarab_pottery = find("ntk_scarab_pottery")
    val statuette_gold = find("ntk_statuette_gold")
    val statuette_pottery = find("ntk_statuette_pottery")
    val statuette_stone = find("ntk_statuette_stone")
    val seal_gold = find("ntk_seal_gold")
    val seal_stone = find("ntk_seal_stone")
    val pharaohs_sceptre = find("pharaohs_sceptre")
    val lockpick = find("lockpick")
}

internal object PpSeqs : SeqReferences() {
    val urn = find("ntk_urn_anim")
    val search = find("ntk_tomb_search")
    val push = find("ntk_tomb_push_loop")
}

internal object PpInterfaces : InterfaceReferences() {
    val overlay = find("ntk_overlay")
}

internal object PpTimers : TimerReferences() {
    /** Custom (ui range 65302): the 5-minute run clock. */
    val tick = find("ntk_plunder_tick")
}

/** Cache varbits (all on the PP varps; written one varbit at a time). */
internal object PpVarbits : VarBitReferences() {
    val urns =
        (1..15).map { find("ntk_urn${it}_state") }
    val doors = (1..4).map { find("ntk_door${it}_state") }
    val chest_state = find("ntk_golden_chest_state")
    val sarcophagus_state = find("ntk_sarcophagus_state")
    val sarcophagus_push = find("ntk_sarcophagus_push")
    val trap_active = find("ntk_trap_active")
    val current_room_level = find("ntk_current_room_level")
    val played_before = find("ntk_played_before")
    val timer = find("ntk_player_timer_count")
    val thieving_required = find("ntk_thieving_required")
    val room_number = find("ntk_room_number")
}

/** The guardian mummy in the lobby (the world spawns leave Pyramid Plunder npcs to this module). */
internal object PpNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<PpNpcSpawns>("npcs.toml")
    }
}
