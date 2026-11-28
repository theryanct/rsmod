package org.rsmod.content.skills.fishing.tempoross

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.baseFishingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.region.Region
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tempoross (OSRS Wiki "Tempoross"), solo. Core loop (branch 1 of 3):
 * - Ruins of Unkah: the rope ladder (Climb / Quick-climb / Solo-start) starts a solo game in a
 *   private copy of the cove (static map square 47,46; all coords in [TemporossMap] are static
 *   cache coords translated into the instance). Ferrymen Sathwood / Nathwood carry players between
 *   Al Kharid and Unkah (spawned by world).
 * - Energy phase: harpoon raw harpoonfish at the north/south spots, cook them at a shrine, Fill an
 *   ammunition crate; the crate's cannon fires one fish every [TemporossTuning.FIRE_INTERVAL]
 *   loop ticks (raw 10, cooked 15 energy; x10 internal). Storm intensity climbs; full = defeat.
 * - Energy 0: Tempoross submerges, the spirit pools light up; harpooning one scatters up to 12
 *   essence (x10) every 4 ticks. After [TemporossTuning.ESSENCE_PHASE_TICKS] it resurfaces at full
 *   energy (max 3 cycles, then enraged storm). Essence 0 = victory.
 * - Points (Wiki table) and permits (1 at 2000 points, +1 per 700) on a win; permits are saved in
 *   custom varp `tempoross_permits` and mirrored to the cache varbit for the reward pool's look.
 * Not yet (branches 2-3): waves/tethers, lightning/fires, torrents, double spot, reward pool.
 */
class Tempoross
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val regionRepo: RegionRepository,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val launcher: ProtectedAccessLauncher,
) : PluginScript() {
    private val games = HashMap<Int, Game>()

    override fun ScriptContext.startup() {
        // Lobby ladder: every op starts a solo game (team games are not built).
        val ladder = TemporossLocs.lobby_ladder
        for (label in listOf("Climb", "Quick-climb", "Solo-start")) bindLoc(ladder, label) { start() }
        bindNpc(TemporossNpcs.ferry_to_lobby, "Ferry") { telejump(TemporossMap.UNKAH_DOCK) }
        bindNpc(TemporossNpcs.ferry_from_lobby, "Ferry") { telejump(TemporossMap.AL_KHARID_DOCK) }

        for (spot in listOf(TemporossNpcs.spot_north, TemporossNpcs.spot_south)) {
            bindNpc(spot, "Harpoon") { harpoon(it, spotOp(spot, "Harpoon")) }
        }
        bindNpc(TemporossNpcs.pool_active, "Harpoon") { harpoonPool(it, spotOp(TemporossNpcs.pool_active, "Harpoon")) }
        for (crate in TemporossNpcs.crates) {
            bindNpc(crate, "Fill") { fillCrate(it) }
            bindNpc(crate, "Check-ammo") { checkCrate(it) }
        }
        bindLoc(TemporossLocs.shrine, "Cook-at") { cook(it, locOp(TemporossLocs.shrine, "Cook-at")) }
        bindLoc(TemporossLocs.crate_harpoon, "Take") { takeTool(TemporossObjs.harpoon) }
        bindLoc(TemporossLocs.crate_rope, "Take") { takeTool(TemporossObjs.rope) }
        bindLoc(TemporossLocs.crate_hammer, "Take") { takeTool(TemporossObjs.hammer) }
        bindLoc(TemporossLocs.crate_bucket, "Take") { takeTool(TemporossObjs.bucket) }
        for (host in TemporossNpcs.hosts) bindNpc(host, "Forfeit") { forfeit() }

        onPlayerSoftTimer(TemporossTimers.tick) { tick(player) }
        onPlayerLogout { abandon(player) }
        onPlayerLogin { abandon(player) }
    }

    /* Binding helpers (ops located by label; skip ops another script already owns). */

    private fun locOp(type: LocType, label: String): Int =
        locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1

    private fun spotOp(type: NpcType, label: String): Int =
        npcTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1

    private fun ScriptContext.bindLoc(type: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locOp(type, label)
        if (op <= 0 || isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
        }
    }

    private fun ScriptContext.bindNpc(type: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = spotOp(type, label)
        if (op <= 0 || isNpcOpBound(type, op)) return
        when (op) {
            1 -> onOpNpc1(type) { action(it.npc) }
            2 -> onOpNpc2(type) { action(it.npc) }
            3 -> onOpNpc3(type) { action(it.npc) }
            4 -> onOpNpc4(type) { action(it.npc) }
        }
    }

    /* Game state */

    private class Crate(val npc: Npc) {
        var raw = 0
        var cooked = 0
        val total: Int
            get() = raw + cooked
    }

    private class Game(val region: Region) {
        var energy = TemporossTuning.ENERGY_MAX
        var essence = TemporossTuning.ESSENCE_MAX
        var storm = 0
        var cycle = 1
        var submerged = false
        var phaseTicks = 0
        var ticks = 0
        var points = 0
        var over = false
        var endTicks = -1
        var won = false
        val npcs = ArrayList<Npc>()
        val crates = ArrayList<Crate>()
        var boss: Npc? = null
        val pools = ArrayList<Npc>()

        fun at(static: CoordGrid): CoordGrid =
            region.southWest.translate(static.x - TemporossMap.SW_X, static.z - TemporossMap.SW_Z, static.level)
    }

    private fun Player.game(): Game? = games[uid.packed]

    /* Start / end */

    private fun template(): RegionTemplate =
        RegionTemplate.create {
            for (x in 0 until 8) {
                for (z in 0 until 8) {
                    for (level in 0..2) {
                        this[x, z, level] = ZoneKey(TemporossMap.SQUARE_ZONE_X + x, TemporossMap.SQUARE_ZONE_Z + z, level)
                    }
                }
            }
        }

    private fun ProtectedAccess.start() {
        if (player.baseFishingLvl < TemporossTuning.FISHING_LEVEL) {
            mes("You need a Fishing level of ${TemporossTuning.FISHING_LEVEL} to help fight Tempoross.")
            return
        }
        if (player.game() != null) return
        val region = regionRepo.add(template())
        if (region == null) {
            mes("The ship can't set sail right now. Try again in a few seconds.")
            return
        }
        val game = Game(region)
        games[player.uid.packed] = game
        spawn(game)
        telejump(game.at(TemporossMap.ARRIVAL))
        ifOpenOverlay(TemporossInterfaces.hud)
        player.softTimer(TemporossTimers.tick, TemporossTuning.LOOP_TICKS)
        // Authored (not Wiki): solo start line.
        mes("You set sail for Tempoross Cove. Catch harpoonfish and load the cannons!")
        updateHud(player, game)
    }

    private fun spawn(game: Game) {
        fun add(type: NpcType, static: CoordGrid): Npc {
            val npc = Npc(npcTypes[type], game.at(static))
            npcRepo.add(npc, TemporossTuning.NPC_LIFETIME)
            game.npcs += npc
            return npc
        }
        for (tile in TemporossMap.SPOTS_NORTH) add(TemporossNpcs.spot_north, tile)
        for (tile in TemporossMap.SPOTS_SOUTH) add(TemporossNpcs.spot_south, tile)
        for ((index, tile) in TemporossMap.CRATES.withIndex()) {
            game.crates += Crate(add(TemporossNpcs.crates[index], tile))
        }
        game.boss = add(TemporossNpcs.boss_ready, TemporossMap.BOSS)
        for (tile in TemporossMap.POOLS) game.pools += add(TemporossNpcs.pool_inactive, tile)
        add(TemporossNpcs.hosts.first(), TemporossMap.HOST_WEST)
    }

    private fun despawn(npc: Npc, game: Game) {
        runCatching { npcRepo.del(npc, Int.MAX_VALUE) }
        game.npcs.remove(npc)
    }

    private fun cleanup(player: Player) {
        val game = games.remove(player.uid.packed) ?: return
        for (npc in game.npcs.toList()) despawn(npc, game)
        player.clearSoftTimer(TemporossTimers.tick)
    }

    /** Logout/login inside a game: drop it and put the player back on the Unkah dock. */
    private fun abandon(player: Player) {
        val inCove = games.containsKey(player.uid.packed)
        cleanup(player)
        // Only move players we own; other instances (e.g. a house) handle their own players.
        if (inCove && RegionRegistry.inWorkingArea(player.coords)) player.coords = TemporossMap.UNKAH_DOCK
    }

    private fun ProtectedAccess.forfeit() {
        val game = player.game() ?: return
        game.over = true
        mes("You abandon the fight against Tempoross.")
        leave(player)
    }

    private fun leave(player: Player) {
        cleanup(player)
        launcher.launchLenient(player) {
            ifCloseSub(TemporossInterfaces.hud)
            telejump(TemporossMap.UNKAH_DOCK)
        }
    }

    /* Loop */

    private fun tick(player: Player) {
        val game = player.game()
        if (game == null) {
            player.clearSoftTimer(TemporossTimers.tick)
            return
        }
        game.ticks += TemporossTuning.LOOP_TICKS
        if (game.over) {
            game.endTicks -= TemporossTuning.LOOP_TICKS
            if (game.endTicks <= 0) leave(player)
            return
        }
        if (!game.submerged) {
            val rate = if (game.cycle > TemporossTuning.MAX_CYCLES) TemporossTuning.STORM_ENRAGED else TemporossTuning.STORM_RATE
            game.storm += rate
            fireCannons(player, game)
            if (game.storm >= TemporossTuning.STORM_MAX) {
                lose(player, game)
                return
            }
            if (game.energy <= 0) submerge(player, game)
        } else {
            game.phaseTicks += TemporossTuning.LOOP_TICKS
            if (game.phaseTicks >= TemporossTuning.ESSENCE_PHASE_TICKS) resurface(player, game)
        }
        updateHud(player, game)
    }

    private fun fireCannons(player: Player, game: Game) {
        if (game.ticks % (TemporossTuning.FIRE_INTERVAL * TemporossTuning.LOOP_TICKS) != 0) return
        for (crate in game.crates) {
            if (crate.total == 0 || game.energy <= 0) continue
            if (crate.cooked > 0) {
                crate.cooked--
                game.energy -= TemporossTuning.COOKED_DAMAGE
            } else {
                crate.raw--
                game.energy -= TemporossTuning.RAW_DAMAGE
            }
        }
        game.energy = game.energy.coerceAtLeast(0)
    }

    private fun submerge(player: Player, game: Game) {
        game.submerged = true
        game.phaseTicks = 0
        game.storm = 0
        swapBoss(game, TemporossNpcs.boss_whirlpool)
        swapPools(game, TemporossNpcs.pool_active)
        player.mes("Tempoross is vulnerable! Harpoon the spirit pools!")
    }

    private fun resurface(player: Player, game: Game) {
        game.submerged = false
        game.cycle++
        game.energy = TemporossTuning.ENERGY_MAX
        swapBoss(game, if (game.cycle > TemporossTuning.MAX_CYCLES) TemporossNpcs.boss_enraged else TemporossNpcs.boss_ready)
        swapPools(game, TemporossNpcs.pool_inactive)
        if (game.cycle > TemporossTuning.MAX_CYCLES) {
            player.mes("Tempoross has gathered too much energy. The storm is out of control!")
        } else {
            player.mes("Tempoross rises from the depths, its energy restored.")
        }
    }

    private fun swapBoss(game: Game, type: NpcType) {
        val old = game.boss ?: return
        val coords = old.coords
        despawn(old, game)
        val npc = Npc(npcTypes[type], coords)
        npcRepo.add(npc, TemporossTuning.NPC_LIFETIME)
        game.npcs += npc
        game.boss = npc
    }

    private fun swapPools(game: Game, type: NpcType) {
        val tiles = game.pools.map { it.coords }
        for (pool in game.pools) despawn(pool, game)
        game.pools.clear()
        for (tile in tiles) {
            val npc = Npc(npcTypes[type], tile)
            npcRepo.add(npc, TemporossTuning.NPC_LIFETIME)
            game.npcs += npc
            game.pools += npc
        }
    }

    private fun win(player: Player, game: Game) {
        game.over = true
        game.won = true
        game.endTicks = TemporossTuning.END_DELAY
        swapBoss(game, TemporossNpcs.boss_unengaged)
        val permits = permitsFor(game.points)
        player.mes("Tempoross has been subdued! You scored ${game.points} points.")
        if (permits > 0) {
            val stored = (player.vars[TemporossVarps.permits] + permits).coerceAtMost(TemporossTuning.PERMIT_CAP)
            VarPlayerIntMapSetter.set(player, TemporossVarps.permits, stored)
            VarPlayerIntMapSetter.set(player, TemporossVarBits.reward_permits, stored)
            player.mes("You receive $permits reward permit${if (permits == 1) "" else "s"} (you now have $stored).")
        } else {
            player.mes("You needed at least ${TemporossTuning.PERMIT_POINTS} points to earn a reward permit.")
        }
        VarPlayerIntMapSetter.set(player, TemporossVarps.kills, player.vars[TemporossVarps.kills] + 1)
    }

    private fun lose(player: Player, game: Game) {
        game.over = true
        game.endTicks = TemporossTuning.END_DELAY
        player.mes("The storm overwhelms the cove. You are washed back to the Ruins of Unkah.")
    }

    /** Wiki: 1 permit at 2,000 points, +1 per 700 more; the remainder gives a linear chance of one more. */
    private fun permitsFor(points: Int): Int {
        if (points < TemporossTuning.PERMIT_POINTS) return 0
        val extra = points - TemporossTuning.PERMIT_POINTS
        val bonus = if (kotlin.random.Random.nextInt(TemporossTuning.PERMIT_STEP) < extra % TemporossTuning.PERMIT_STEP) 1 else 0
        return 1 + extra / TemporossTuning.PERMIT_STEP + bonus
    }

    private fun updateHud(player: Player, game: Game) {
        // cs2 4074 (rev 243): essence flag, points, energy, essence, energy max, essence max, storm
        // (out of 350), mast lines 1-4 hidden, fires 1-4 shown.
        val storm = game.storm * TemporossTuning.HUD_STORM_MAX / TemporossTuning.STORM_MAX
        player.runClientScript(
            TemporossTuning.HUD_SCRIPT,
            if (game.submerged) 1 else 0,
            game.points,
            game.energy,
            game.essence,
            TemporossTuning.ENERGY_MAX,
            TemporossTuning.ESSENCE_MAX,
            storm,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        VarPlayerIntMapSetter.set(player, TemporossVarBits.current_points, game.points.coerceAtMost(TemporossTuning.POINTS_CAP))
    }

    /* Actions */

    private fun ProtectedAccess.addPoints(amount: Int) {
        val game = player.game() ?: return
        game.points = (game.points + amount).coerceAtMost(TemporossTuning.POINTS_CAP)
    }

    /** Wiki harpoonfish xp: inner value 450..1000 by base Fishing level (35-69 / 70-99 bands). */
    private fun innerXp(level: Int): Int =
        if (level < 70) 450 + 550 * (level.coerceAtLeast(35) - 35) / 64 else 890 + 110 * (level.coerceAtMost(99) - 70) / 29

    private fun ProtectedAccess.hasHarpoon(): Boolean {
        val held = player.righthand
        return TemporossObjs.harpoons.any { it in inv || (held != null && objTypes[it].id == held.id) }
    }

    private fun ProtectedAccess.harpoon(spot: Npc, op: Int) {
        val game = player.game() ?: return
        if (game.over || game.submerged) return
        if (!hasHarpoon()) {
            mes("You need a harpoon to catch harpoonfish. There are some in the crates on the ship.")
            return
        }
        if (inv.isFull()) {
            mes("You can't carry any more fish.")
            return
        }
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 5
            anim(TemporossSeqs.harpoon)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + 4
            spam("You start harpooning fish.")
        } else if (actionDelay == mapClock) {
            actionDelay = mapClock + 4
            if (statRandom(stats.fishing, TemporossTuning.CATCH_LOW, TemporossTuning.CATCH_HIGH, invisibleLvls)) {
                invAdd(inv, TemporossObjs.raw_harpoonfish)
                addPoints(TemporossTuning.POINTS_CATCH)
                val xp = innerXp(player.baseFishingLvl) / 100.0
                statAdvance(stats.fishing, xp * xpMods.get(player, stats.fishing))
                spam("You catch a harpoonfish.")
                if (inv.isFull()) return
            }
        }
        reopNpc(spot, op)
    }

    private fun ProtectedAccess.reopNpc(npc: Npc, op: Int) {
        when (op) {
            1 -> opNpc1(npc)
            2 -> opNpc2(npc)
            3 -> opNpc3(npc)
            4 -> opNpc4(npc)
        }
    }

    private fun ProtectedAccess.cook(shrine: BoundLocInfo, op: Int) {
        val game = player.game() ?: return
        if (game.over) return
        if (TemporossObjs.raw_harpoonfish !in inv) {
            mes("You have no raw harpoonfish to cook.")
            return
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + TemporossTuning.COOK_TICKS
            anim(TemporossSeqs.cook)
        } else if (actionDelay == mapClock) {
            actionDelay = mapClock + TemporossTuning.COOK_TICKS
            invDel(inv, TemporossObjs.raw_harpoonfish)
            invAdd(inv, TemporossObjs.cooked_harpoonfish)
            addPoints(TemporossTuning.POINTS_COOK)
            statAdvance(stats.cooking, TemporossTuning.COOK_XP * xpMods.get(player, stats.cooking))
            anim(TemporossSeqs.cook)
            if (TemporossObjs.raw_harpoonfish !in inv) return
        }
        when (op) {
            1 -> opLoc1(shrine)
            2 -> opLoc2(shrine)
            3 -> opLoc3(shrine)
        }
    }

    private fun ProtectedAccess.fillCrate(npc: Npc) {
        val game = player.game() ?: return
        if (game.over) return
        val crate = game.crates.firstOrNull { it.npc === npc } ?: return
        val raw = invTotal(inv, TemporossObjs.raw_harpoonfish)
        val cooked = invTotal(inv, TemporossObjs.cooked_harpoonfish)
        if (raw + cooked == 0) {
            mes("You have no harpoonfish to load.")
            return
        }
        invDel(inv, TemporossObjs.raw_harpoonfish, raw)
        invDel(inv, TemporossObjs.cooked_harpoonfish, cooked)
        crate.raw += raw
        crate.cooked += cooked
        addPoints(raw * TemporossTuning.POINTS_DEPOSIT_RAW + cooked * TemporossTuning.POINTS_DEPOSIT_COOKED)
        val xp = innerXp(player.baseFishingLvl) / 10.0 * (raw + cooked)
        statAdvance(stats.fishing, xp * xpMods.get(player, stats.fishing))
        anim(TemporossSeqs.fill)
        mes("You load ${raw + cooked} harpoonfish into the crate.")
    }

    private fun ProtectedAccess.checkCrate(npc: Npc) {
        val crate = player.game()?.crates?.firstOrNull { it.npc === npc } ?: return
        mes("The crate holds ${crate.cooked} cooked and ${crate.raw} raw harpoonfish.")
    }

    private fun ProtectedAccess.harpoonPool(pool: Npc, op: Int) {
        val game = player.game() ?: return
        if (game.over || !game.submerged) return
        if (!hasHarpoon()) {
            mes("You need a harpoon to strike at the spirit pool.")
            return
        }
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 5
            anim(TemporossSeqs.harpoon)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + TemporossTuning.SCATTER_TICKS
        } else if (actionDelay == mapClock) {
            actionDelay = mapClock + TemporossTuning.SCATTER_TICKS
            game.essence = (game.essence - TemporossTuning.SCATTER_DAMAGE).coerceAtLeast(0)
            addPoints(TemporossTuning.POINTS_SCATTER)
            val xp = innerXp(player.baseFishingLvl) * 11 / 100.0
            statAdvance(stats.fishing, xp * xpMods.get(player, stats.fishing))
            spam("The glowing fish scatter, shedding their magical scales.")
            if (game.essence <= 0) {
                win(player, game)
                return
            }
        }
        reopNpc(pool, op)
    }

    private fun ProtectedAccess.takeTool(tool: ObjType) {
        if (player.game() == null) return
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        invAdd(inv, tool)
    }

}
