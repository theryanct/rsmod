package org.rsmod.content.skills.fishing.tempoross

import kotlin.random.Random
import org.rsmod.annotations.InternalApi
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.invtx.invDel
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.output.spam
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.baseConstructionLvl
import org.rsmod.api.player.stat.baseFishingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.loc.LocRepository
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
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.map.collision.isZoneValid
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
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Tempoross (OSRS Wiki "Tempoross"), solo. Core loop + attacks (branches 1-2 of 3):
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
 * - Attacks (only while energy > 10% and surfaced; timings authored in [TemporossTuning]):
 *   colossal wave (warning, then the hit: tethered = safe +10, else pushed and losable items lost,
 *   no push between the ammo crates; tether sites may break, Repair with a hammer +40; all fires
 *   go out), lightning (clouds, then 2x2 fires that take a losable item every loop, spread twice,
 *   Douse with a bucket of water +40; pumps fill buckets), torrent (a cannon is electrified and
 *   stops firing for a while). Tether = rope or full spirit angler; clears on moving / wave end.
 * - Double fishing spot: one spot periodically turns into the leaping-fish spot (50% bonus fish);
 *   it reverts after a while or when Tempoross submerges.
 * Not yet: the torrent's stun on a player loading that cannon, the live 15 s pause after dropping
 * to 10% energy, drifting clouds.
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
    private val locRepo: LocRepository,
    private val collision: CollisionFlagMap,
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
        bindNpc(TemporossNpcs.spot_special, "Harpoon") {
            harpoon(it, spotOp(TemporossNpcs.spot_special, "Harpoon"), double = true)
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

        // Tether ops live on the multiloc variants; the event still carries the base (site) loc.
        for (variant in listOf(TemporossLocs.totem_untethered, TemporossLocs.mast_untethered)) {
            bindLoc(variant, "Tether") { tether(it) }
        }
        for (variant in listOf(TemporossLocs.totem_tethered, TemporossLocs.mast_tethered)) {
            bindLoc(variant, "Untether") { untether() }
        }
        for (broken in TemporossLocs.sites_broken) bindLoc(broken, "Repair") { repair(it) }
        bindLoc(TemporossLocs.water_pump, "Use") { pump() }
        bindLoc(TemporossLocs.water_pump_dock, "Fill-bucket") { pump() }
        bindNpc(TemporossNpcs.fire, "Douse") { douse(it) }

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

    private class Spot(var npc: Npc, val type: NpcType, val tile: CoordGrid)

    private class Fire(val coords: CoordGrid, val loc: LocInfo, val npc: Npc) {
        var spreads = 0
        var spreadIn = TemporossTuning.FIRE_SPREAD_TICKS
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
        val spots = ArrayList<Spot>()

        // Attacks: countdowns in game ticks (-1 = nothing pending).
        var waveIn = TemporossTuning.WAVE_FIRST
        var waveHitIn = -1
        var waveEndIn = -1
        var lightningIn = TemporossTuning.LIGHTNING_FIRST
        var strikeIn = -1
        val clouds = ArrayList<Npc>()
        val strikes = ArrayList<CoordGrid>()
        var torrentIn = TemporossTuning.TORRENT_FIRST
        var torrentHitIn = -1
        var torrentTarget = -1
        var doubleIn = TemporossTuning.DOUBLE_SPOT_FIRST
        var doubleLeft = 0
        var special: Spot? = null

        /** Tether sites (index = varbit value - 1) and cannons (index = crate index); null if not found. */
        val siteLocs = arrayOfNulls<LocInfo>(4)
        val broken = BooleanArray(4)
        val cannonLocs = arrayOfNulls<LocInfo>(4)
        val cannonDisabled = IntArray(4)
        var tetherTile: CoordGrid? = null
        val fires = ArrayList<Fire>()

        fun at(static: CoordGrid): CoordGrid =
            region.southWest.translate(static.x - TemporossMap.SW_X, static.z - TemporossMap.SW_Z, static.level)

        fun inside(coords: CoordGrid): Boolean =
            coords.x in region.southWest.x..region.northEast.x && coords.z in region.southWest.z..region.northEast.z

        fun addPoints(amount: Int) {
            points = (points + amount).coerceAtMost(TemporossTuning.POINTS_CAP)
        }
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

    private fun addNpc(game: Game, type: NpcType, coords: CoordGrid): Npc {
        val npc = Npc(npcTypes[type], coords)
        npcRepo.add(npc, TemporossTuning.NPC_LIFETIME)
        game.npcs += npc
        return npc
    }

    private fun spawn(game: Game) {
        fun add(type: NpcType, static: CoordGrid): Npc = addNpc(game, type, game.at(static))
        for (tile in TemporossMap.SPOTS_NORTH) {
            game.spots += Spot(add(TemporossNpcs.spot_north, tile), TemporossNpcs.spot_north, game.at(tile))
        }
        for (tile in TemporossMap.SPOTS_SOUTH) {
            game.spots += Spot(add(TemporossNpcs.spot_south, tile), TemporossNpcs.spot_south, game.at(tile))
        }
        for ((index, tile) in TemporossMap.CRATES.withIndex()) {
            game.crates += Crate(add(TemporossNpcs.crates[index], tile))
        }
        game.boss = add(TemporossNpcs.boss_ready, TemporossMap.BOSS)
        for (tile in TemporossMap.POOLS) game.pools += add(TemporossNpcs.pool_inactive, tile)
        add(TemporossNpcs.hosts.first(), TemporossMap.HOST_WEST)
        for (i in 0 until 4) {
            game.siteLocs[i] = findLoc(game, TemporossMap.TETHER_SITES[i], TemporossLocs.sites[i])
            game.cannonLocs[i] = findLoc(game, TemporossMap.CANNONS[i], TemporossLocs.cannons[i])
        }
    }

    /** Bridge locs (masts, cannons) may sit on plane 0 or 1 in the instance; try both. */
    private fun findLoc(game: Game, static: CoordGrid, type: LocType): LocInfo? {
        val tile = game.at(static)
        for (level in listOf(0, 1)) {
            val loc = locRepo.findExact(tile.copy(level = level), type)
            if (loc != null) return loc
        }
        return null
    }

    /** Swaps a loc in place (no auto-revert; the game swaps it back itself). */
    private fun swapLoc(from: LocInfo, into: LocType): LocInfo =
        locRepo.add(from.coords, into, Int.MAX_VALUE, from.angle, from.shape)

    private fun despawn(npc: Npc, game: Game) {
        runCatching { npcRepo.del(npc, Int.MAX_VALUE) }
        game.npcs.remove(npc)
    }

    private fun cleanup(player: Player) {
        val game = games.remove(player.uid.packed) ?: return
        for (npc in game.npcs.toList()) despawn(npc, game)
        // Put changed locs back so a reused region slot starts clean.
        for (fire in game.fires) runCatching { locRepo.del(fire.loc, Int.MAX_VALUE) }
        game.fires.clear()
        for (i in 0 until 4) {
            val site = game.siteLocs[i]
            if (site != null && game.broken[i]) runCatching { swapLoc(site, TemporossLocs.sites[i]) }
            val cannon = game.cannonLocs[i]
            if (cannon != null && game.cannonDisabled[i] > 0) runCatching { swapLoc(cannon, TemporossLocs.cannons[i]) }
        }
        clearTether(player, game)
        player.clearSoftTimer(TemporossTimers.tick)
    }

    /** Logout/login inside a game: drop it and put the player back on the Unkah dock. */
    private fun abandon(player: Player) {
        val inCove = games.containsKey(player.uid.packed)
        cleanup(player)
        if (player.vars[TemporossVarBits.tethered] != 0) VarPlayerIntMapSetter.set(player, TemporossVarBits.tethered, 0)
        // Only move players we own; other instances (e.g. a house) handle their own players.
        if (inCove && RegionRegistry.inWorkingArea(player.coords)) player.coords = TemporossMap.UNKAH_DOCK
    }

    private fun ProtectedAccess.forfeit() {
        val game = player.game() ?: return
        game.over = true
        mes("You abandon the fight against Tempoross.")
        leave(player)
    }

    @OptIn(InternalApi::class)
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
        attacks(player, game)
        burn(player, game)
        doubleSpot(game)
        updateHud(player, game)
    }

    private fun fireCannons(player: Player, game: Game) {
        if (game.ticks % (TemporossTuning.FIRE_INTERVAL * TemporossTuning.LOOP_TICKS) != 0) return
        for ((index, crate) in game.crates.withIndex()) {
            if (crate.total == 0 || game.energy <= 0 || game.cannonDisabled[index] > 0) continue
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
        val bonus = if (Random.nextInt(TemporossTuning.PERMIT_STEP) < extra % TemporossTuning.PERMIT_STEP) 1 else 0
        return 1 + extra / TemporossTuning.PERMIT_STEP + bonus
    }

    private fun updateHud(player: Player, game: Game) {
        // cs2 4074 (rev 243): essence flag, points, energy, essence, energy max, essence max, storm
        // (out of 350), args 7-10 = 1 hides mast_line_1-4 (used here for broken tether sites, in
        // site order), args 11-14 = 1 shows fire_1-4 (authored areas: N island, W ship, E ship, S island).
        val storm = game.storm * TemporossTuning.HUD_STORM_MAX / TemporossTuning.STORM_MAX
        val fireAreas = IntArray(4)
        for (fire in game.fires) fireAreas[fireArea(game, fire.coords)] = 1
        val broken = IntArray(4) { if (game.broken[it]) 1 else 0 }
        player.runClientScript(
            TemporossTuning.HUD_SCRIPT,
            if (game.submerged) 1 else 0,
            game.points,
            game.energy,
            game.essence,
            TemporossTuning.ENERGY_MAX,
            TemporossTuning.ESSENCE_MAX,
            storm,
            broken[0], broken[1], broken[2], broken[3],
            fireAreas[0], fireAreas[1], fireAreas[2], fireAreas[3],
        )
        VarPlayerIntMapSetter.set(player, TemporossVarBits.current_points, game.points.coerceAtMost(TemporossTuning.POINTS_CAP))
    }

    private fun fireArea(game: Game, coords: CoordGrid): Int {
        val centre = game.at(TemporossMap.CENTRE)
        val staticZ = coords.z - centre.z + TemporossMap.CENTRE.z
        return when {
            staticZ >= TemporossMap.NORTH_Z -> 0
            staticZ <= TemporossMap.SOUTH_Z -> 3
            coords.x < centre.x -> 1
            else -> 2
        }
    }

    /* Attacks */

    private fun attacksActive(game: Game): Boolean =
        !game.over && !game.submerged &&
            game.energy * 100 > TemporossTuning.ENERGY_MAX * TemporossTuning.ATTACK_MIN_ENERGY_PCT

    private fun attacks(player: Player, game: Game) {
        restoreCannons(game)
        val tile = game.tetherTile
        if (tile != null && player.coords != tile) clearTether(player, game)
        val active = attacksActive(game)
        wave(player, game, active)
        lightning(player, game, active)
        torrent(player, game, active)
    }

    private fun clearTether(player: Player, game: Game) {
        game.tetherTile = null
        if (player.vars[TemporossVarBits.tethered] != 0) VarPlayerIntMapSetter.set(player, TemporossVarBits.tethered, 0)
    }

    private fun isTethered(player: Player, game: Game): Boolean =
        player.vars[TemporossVarBits.tethered] != 0 && game.tetherTile == player.coords

    private fun wave(player: Player, game: Game, active: Boolean) {
        val step = TemporossTuning.LOOP_TICKS
        if (game.waveEndIn >= 0) {
            game.waveEndIn -= step
            if (game.waveEndIn <= 0) {
                game.waveEndIn = -1
                game.waveIn = TemporossTuning.WAVE_INTERVAL
                player.runClientScript(TemporossTuning.WAVE_END_SCRIPT, TemporossTuning.WAVE_FADE)
                clearTether(player, game)
            }
            return
        }
        if (game.waveHitIn >= 0) {
            if (!active) {
                // Submerged / low energy before it landed: the wave never comes.
                game.waveHitIn = -1
                game.waveIn = TemporossTuning.WAVE_INTERVAL
                return
            }
            game.waveHitIn -= step
            if (game.waveHitIn <= 0) {
                game.waveHitIn = -1
                game.waveEndIn = TemporossTuning.WAVE_LENGTH
                player.runClientScript(TemporossTuning.WAVE_START_SCRIPT, TemporossTuning.WAVE_FADE)
                waveHit(player, game)
            }
            return
        }
        if (!active) return
        game.waveIn -= step
        if (game.waveIn <= 0) {
            game.waveHitIn = TemporossTuning.WAVE_WARN
            player.mes("A colossal wave closes in...")
        }
    }

    private fun waveHit(player: Player, game: Game) {
        if (isTethered(player, game)) {
            game.addPoints(TemporossTuning.POINTS_WAVE)
            // Authored line.
            player.mes("You hold on tight as the wave crashes over you.")
        } else {
            val sheltered = TemporossMap.SHELTERS.any { game.at(it).x == player.coords.x && game.at(it).z == player.coords.z }
            if (!sheltered) push(player, game)
            val lost = loseItems(player, TemporossTuning.WAVE_LOSS)
            // Authored lines.
            player.mes(if (lost > 0) "The wave washes away some of your supplies!" else "The wave crashes over you.")
        }
        // Wiki (Mod Ash): each site rolls independently, 15% at no storm up to 25% at full storm.
        val span = TemporossTuning.TETHER_BREAK_MAX - TemporossTuning.TETHER_BREAK_MIN
        val chance = TemporossTuning.TETHER_BREAK_MIN + span * game.storm / TemporossTuning.STORM_MAX
        var damaged = false
        for (i in 0 until 4) {
            val site = game.siteLocs[i] ?: continue
            if (game.broken[i] || Random.nextInt(100) >= chance) continue
            game.siteLocs[i] = swapLoc(site, TemporossLocs.sites_broken[i])
            game.broken[i] = true
            damaged = true
        }
        if (damaged) player.mes("The wave damages a tether point.")
        if (game.fires.isNotEmpty()) {
            for (fire in game.fires.toList()) removeFire(game, fire)
            player.spam("The wave puts out the fires.")
        }
    }

    /** Wiki: pushed a few tiles. Tries away from Tempoross first, then any open direction. */
    private fun push(player: Player, game: Game) {
        val from = player.coords
        val centre = game.at(TemporossMap.CENTRE)
        val away = Pair(Integer.signum(from.x - centre.x), Integer.signum(from.z - centre.z))
        val first = if (away in PUSH_DIRS) listOf(away) else emptyList()
        for ((dx, dz) in first + PUSH_DIRS.filter { it != away }.shuffled()) {
            var dest = from
            var stopped = false
            repeat(TemporossTuning.WAVE_PUSH) {
                val next = dest.translate(dx, dz)
                if (!walkable(game, next)) stopped = true
                if (!stopped) dest = next
            }
            if (dest != from) {
                PathingEntityCommon.teleport(player, collision, dest)
                return
            }
        }
    }

    private fun walkable(game: Game, tile: CoordGrid): Boolean =
        game.inside(tile) && collision.isZoneValid(tile) &&
            (collision[tile.x, tile.z, tile.level] and BLOCKED) == 0

    /** Removes up to [count] random losable items; returns how many went. */
    private fun loseItems(player: Player, count: Int): Int {
        var lost = 0
        repeat(count) {
            val held = TemporossObjs.losable.filter { it in player.inv }
            if (held.isEmpty()) return lost
            player.invDel(player.inv, held.random())
            lost++
        }
        return lost
    }

    private fun lightning(player: Player, game: Game, active: Boolean) {
        val step = TemporossTuning.LOOP_TICKS
        if (game.strikeIn >= 0) {
            if (!active) {
                clearClouds(game)
                game.lightningIn = TemporossTuning.LIGHTNING_INTERVAL
                return
            }
            game.strikeIn -= step
            if (game.strikeIn <= 0) {
                val targets = game.strikes.toList()
                clearClouds(game)
                game.lightningIn = TemporossTuning.LIGHTNING_INTERVAL
                for (target in targets) spawnFire(game, target)
            }
            return
        }
        if (!active) return
        game.lightningIn -= step
        if (game.lightningIn > 0) return
        repeat(TemporossTuning.LIGHTNING_STRIKES) {
            val target = pickStrike(game, player.coords) ?: return@repeat
            game.strikes += target
            // Cloud (size 4) centred over the 2x2 fire it will start.
            game.clouds += addNpc(game, TemporossNpcs.cloud, target.translate(-1, -1))
        }
        game.strikeIn = TemporossTuning.LIGHTNING_WARN
        player.mes("A strong wind blows as clouds roll in...")
    }

    private fun clearClouds(game: Game) {
        for (cloud in game.clouds) despawn(cloud, game)
        game.clouds.clear()
        game.strikes.clear()
        game.strikeIn = -1
    }

    private fun pickStrike(game: Game, around: CoordGrid): CoordGrid? {
        val r = TemporossTuning.LIGHTNING_RADIUS
        repeat(STRIKE_ATTEMPTS) {
            val tile = around.translate(Random.nextInt(-r, r + 1), Random.nextInt(-r, r + 1))
            if (fireFits(game, tile) && game.strikes.none { overlaps(it, tile) }) return tile
        }
        return null
    }

    private fun overlaps(a: CoordGrid, b: CoordGrid): Boolean =
        a.level == b.level && kotlin.math.abs(a.x - b.x) < 2 && kotlin.math.abs(a.z - b.z) < 2

    private fun fireFits(game: Game, sw: CoordGrid): Boolean =
        game.fires.size < TemporossTuning.FIRE_CAP && game.fires.none { overlaps(it.coords, sw) } &&
            (0..1).all { dx -> (0..1).all { dz -> walkable(game, sw.translate(dx, dz)) } }

    private fun spawnFire(game: Game, sw: CoordGrid): Boolean {
        if (!fireFits(game, sw)) return false
        val loc = locRepo.add(sw, TemporossLocs.fire, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
        val npc = addNpc(game, TemporossNpcs.fire, sw)
        game.fires += Fire(sw, loc, npc)
        return true
    }

    private fun removeFire(game: Game, fire: Fire) {
        runCatching { locRepo.del(fire.loc, Int.MAX_VALUE) }
        despawn(fire.npc, game)
        game.fires.remove(fire)
    }

    /** Fires: spread (Wiki: at most twice each) and take a losable item every 2 ticks from a player inside. */
    private fun burn(player: Player, game: Game) {
        for (fire in game.fires.toList()) {
            if (fire.spreads >= TemporossTuning.FIRE_MAX_SPREADS) continue
            fire.spreadIn -= TemporossTuning.LOOP_TICKS
            if (fire.spreadIn > 0) continue
            fire.spreadIn = TemporossTuning.FIRE_SPREAD_TICKS
            for ((dx, dz) in SPREAD_DIRS.shuffled()) {
                if (spawnFire(game, fire.coords.translate(dx * 2, dz * 2))) {
                    fire.spreads++
                    break
                }
            }
        }
        val c = player.coords
        val burning =
            game.fires.any { c.level == it.coords.level && c.x - it.coords.x in 0..1 && c.z - it.coords.z in 0..1 }
        if (burning && loseItems(player, 1) > 0) player.spam("The fire burns away some of your supplies!")
    }

    private fun torrent(player: Player, game: Game, active: Boolean) {
        val step = TemporossTuning.LOOP_TICKS
        if (game.torrentHitIn >= 0) {
            if (!active) {
                game.torrentHitIn = -1
                game.torrentIn = TemporossTuning.TORRENT_INTERVAL
                return
            }
            game.torrentHitIn -= step
            if (game.torrentHitIn > 0) return
            game.torrentHitIn = -1
            game.torrentIn = TemporossTuning.TORRENT_INTERVAL
            val index = game.torrentTarget
            val cannon = game.cannonLocs.getOrNull(index) ?: return
            if (game.cannonDisabled[index] > 0) return
            game.cannonLocs[index] = swapLoc(cannon, TemporossLocs.cannons_disabled[index])
            game.cannonDisabled[index] = TemporossTuning.TORRENT_DISABLE
            // Authored line.
            player.spam("The torrent electrifies a cannon!")
            return
        }
        if (!active) return
        game.torrentIn -= step
        if (game.torrentIn > 0) return
        val ready = (0 until 4).filter { game.cannonLocs[it] != null && game.cannonDisabled[it] == 0 }
        if (ready.isEmpty()) {
            game.torrentIn = TemporossTuning.TORRENT_INTERVAL
            return
        }
        // Solo: aim at a loaded cannon when there is one.
        val loaded = ready.filter { (game.crates.getOrNull(it)?.total ?: 0) > 0 }
        game.torrentTarget = (loaded.ifEmpty { ready }).random()
        game.torrentHitIn = TemporossTuning.TORRENT_WARN
        player.mes("A charged torrent of water surges towards a nearby cannon...")
    }

    private fun restoreCannons(game: Game) {
        for (i in 0 until 4) {
            if (game.cannonDisabled[i] <= 0) continue
            game.cannonDisabled[i] -= TemporossTuning.LOOP_TICKS
            if (game.cannonDisabled[i] > 0) continue
            game.cannonDisabled[i] = 0
            val cannon = game.cannonLocs[i] ?: continue
            game.cannonLocs[i] = swapLoc(cannon, TemporossLocs.cannons[i])
        }
    }

    /* Double fishing spot */

    private fun doubleSpot(game: Game) {
        if (game.over) return
        if (game.submerged) {
            // Wiki: the leaping fish vanish when Tempoross submerges.
            clearSpecial(game)
            game.doubleIn = TemporossTuning.DOUBLE_SPOT_FIRST
            return
        }
        if (game.special != null) {
            game.doubleLeft -= TemporossTuning.LOOP_TICKS
            if (game.doubleLeft <= 0) {
                clearSpecial(game)
                game.doubleIn = TemporossTuning.DOUBLE_SPOT_INTERVAL
            }
            return
        }
        game.doubleIn -= TemporossTuning.LOOP_TICKS
        if (game.doubleIn > 0) return
        val spot = game.spots.randomOrNull() ?: return
        despawn(spot.npc, game)
        spot.npc = addNpc(game, TemporossNpcs.spot_special, spot.tile)
        game.special = spot
        game.doubleLeft = TemporossTuning.DOUBLE_SPOT_LENGTH
    }

    private fun clearSpecial(game: Game) {
        val spot = game.special ?: return
        game.special = null
        despawn(spot.npc, game)
        spot.npc = addNpc(game, spot.type, spot.tile)
    }

    /* Actions */

    private fun ProtectedAccess.addPoints(amount: Int) {
        player.game()?.addPoints(amount)
    }

    /** Wiki harpoonfish xp: inner value 450..1000 by base Fishing level (35-69 / 70-99 bands). */
    private fun innerXp(level: Int): Int =
        if (level < 70) 450 + 550 * (level.coerceAtLeast(35) - 35) / 64 else 890 + 110 * (level.coerceAtMost(99) - 70) / 29

    private fun ProtectedAccess.hasHarpoon(): Boolean {
        val held = player.righthand
        return TemporossObjs.harpoons.any { it in inv || (held != null && objTypes[it].id == held.id) }
    }

    private fun ProtectedAccess.harpoon(spot: Npc, op: Int, double: Boolean = false) {
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
                catchFish()
                spam("You catch a harpoonfish.")
                // Wiki (Mod Squid): leaping-fish spots roll a flat 50% for an extra fish on success.
                if (double && !inv.isFull() && Random.nextInt(100) < TemporossTuning.DOUBLE_SPOT_BONUS) {
                    catchFish()
                    spam("You catch an extra harpoonfish.")
                }
                if (inv.isFull()) return
            }
        }
        reopNpc(spot, op)
    }

    private fun ProtectedAccess.catchFish() {
        invAdd(inv, TemporossObjs.raw_harpoonfish)
        addPoints(TemporossTuning.POINTS_CATCH)
        val xp = innerXp(player.baseFishingLvl) / 100.0
        statAdvance(stats.fishing, xp * xpMods.get(player, stats.fishing))
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

    /* Tethers, repairs, fires */

    private fun ProtectedAccess.tether(loc: BoundLocInfo) {
        val game = player.game() ?: return
        if (game.over) return
        val site = TemporossLocs.sites.indexOfFirst { loc.isType(it) }
        if (site < 0) return
        if (game.broken[site]) {
            mes("Looks like it needs fixing before it can be tethered to.")
            return
        }
        val outfit = TemporossObjs.spirit_angler.all { it in worn }
        if (TemporossObjs.rope !in inv && !outfit) {
            // Authored line.
            mes("You need a rope to tether yourself.")
            return
        }
        VarPlayerIntMapSetter.set(player, TemporossVarBits.tethered, site + 1)
        game.tetherTile = player.coords
        anim(TemporossSeqs.tether)
        spam("You tether yourself.")
    }

    private fun ProtectedAccess.untether() {
        val game = player.game() ?: return
        clearTether(player, game)
        anim(TemporossSeqs.untether)
    }

    private fun ProtectedAccess.repair(loc: BoundLocInfo) {
        val game = player.game() ?: return
        if (game.over) return
        val site = TemporossLocs.sites_broken.indexOfFirst { loc.isType(it) }
        if (site < 0 || !game.broken[site]) return
        if (TemporossObjs.hammers.none { it in inv || it in worn }) {
            mes("You need a hammer to repair this.")
            return
        }
        val broken = game.siteLocs[site] ?: return
        anim(TemporossSeqs.repair)
        game.siteLocs[site] = swapLoc(broken, TemporossLocs.sites[site])
        game.broken[site] = false
        addPoints(TemporossTuning.POINTS_REPAIR)
        val xp = (TemporossTuning.REPAIR_XP_PER_LEVEL * player.baseConstructionLvl).toDouble()
        statAdvance(stats.construction, xp * xpMods.get(player, stats.construction))
        spam("You repair the damage.")
    }

    private fun ProtectedAccess.pump() {
        if (player.game() == null) return
        val empty = invTotal(inv, TemporossObjs.bucket)
        if (empty == 0) {
            mes("You need an empty bucket to fill.")
            return
        }
        invDel(inv, TemporossObjs.bucket, empty)
        invAdd(inv, TemporossObjs.bucket_water, empty)
        anim(TemporossSeqs.fill)
        spam("You fill the bucket${if (empty == 1) "" else "s"} with water.")
    }

    private fun ProtectedAccess.douse(npc: Npc) {
        val game = player.game() ?: return
        val fire = game.fires.firstOrNull { it.npc === npc } ?: return
        if (TemporossObjs.bucket_water !in inv) {
            mes("You need a bucket of water to douse the fire.")
            return
        }
        invDel(inv, TemporossObjs.bucket_water)
        invAdd(inv, TemporossObjs.bucket)
        anim(TemporossSeqs.fill)
        removeFire(game, fire)
        addPoints(TemporossTuning.POINTS_DOUSE)
        spam("You douse the fire.")
    }

    private companion object {
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
        val SPREAD_DIRS = listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))
        const val STRIKE_ATTEMPTS = 20
        val PUSH_DIRS = (-1..1).flatMap { dx -> (-1..1).map { dz -> Pair(dx, dz) } }.filter { it != Pair(0, 0) }
    }
}
