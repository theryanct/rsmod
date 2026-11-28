package org.rsmod.content.skills.fishing.tempoross

import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.map.CoordGrid

/**
 * Static cove coordinates (rev-243 cache; see ~/code/rsmod-tempoross-notes.md). Fishing spot, pool
 * and boss tiles are Wiki pins + 128 on z (NPCs aren't in the cache) and need an in-game check.
 */
internal object TemporossMap {
    /** Map square (47,46): x 3008-3071, z 2944-3007. */
    const val SW_X = 3008
    const val SW_Z = 2944
    const val SQUARE_ZONE_X = 47 * 8
    const val SQUARE_ZONE_Z = 46 * 8

    /** West ship deck, by the tool crates (ship decks are bridge tiles = plane 0). Authored guess. */
    val ARRIVAL = CoordGrid(3035, 2977, 0)
    val HOST_WEST = CoordGrid(3035, 2980, 0)
    val BOSS = CoordGrid(3043, 2973, 0)
    val SPOTS_NORTH = listOf(CoordGrid(3048, 3002, 0), CoordGrid(3051, 2995, 0), CoordGrid(3037, 2996, 0))
    val SPOTS_SOUTH = listOf(CoordGrid(3036, 2963, 0), CoordGrid(3048, 2957, 0), CoordGrid(3046, 2954, 0))
    val POOLS = listOf(CoordGrid(3046, 2981, 0), CoordGrid(3046, 2971, 0))
    val CRATES =
        listOf(CoordGrid(3038, 2978, 0), CoordGrid(3038, 2976, 0), CoordGrid(3056, 2978, 0), CoordGrid(3056, 2976, 0))

    /**
     * Tether sites in `tempoross_tethered` order (1 N totem, 2 W mast, 3 E mast, 4 S totem). Masts
     * and cannons are bridge locs stored on plane 1; the lookup tries plane 0 then 1.
     */
    val TETHER_SITES =
        listOf(CoordGrid(3044, 2995, 0), CoordGrid(3036, 2977, 1), CoordGrid(3058, 2977, 1), CoordGrid(3043, 2961, 0))
    val CANNONS =
        listOf(CoordGrid(3038, 2979, 1), CoordGrid(3038, 2974, 1), CoordGrid(3054, 2979, 1), CoordGrid(3054, 2974, 1))
    /** Wiki: standing between a ship's two ammo crates, the wave can't push you (items still go). */
    val SHELTERS = listOf(CoordGrid(3038, 2977, 0), CoordGrid(3056, 2977, 0))
    /** Boss centre: waves push players away from it; fires west/east of it count as ship fires on the HUD. */
    val CENTRE = CoordGrid(3047, 2977, 0)
    /** HUD fire areas (authored split): north island at/above this z, south island at/below the other. */
    const val NORTH_Z = 2985
    const val SOUTH_Z = 2969

    /** Unkah pier by the Ferry (lobby side) and the Al Kharid ferry. */
    val UNKAH_DOCK = CoordGrid(3147, 2841, 0)
    val AL_KHARID_DOCK = CoordGrid(3271, 3145, 0)
}

/**
 * Tunables. Values marked Wiki come from the notes; everything else is authored for solo play and
 * meant to be adjusted after Ryan's first games.
 */
internal object TemporossTuning {
    const val FISHING_LEVEL = 35
    const val LOOP_TICKS = 2

    // Wiki (x10 internal): solo energy ~2650 (17 cooked fish ~4%), essence 2500, fish 100/150, scatter <=120.
    const val ENERGY_MAX = 2650
    const val ESSENCE_MAX = 2500
    const val RAW_DAMAGE = 100
    const val COOKED_DAMAGE = 150
    const val SCATTER_DAMAGE = 120
    const val SCATTER_TICKS = 4
    const val MAX_CYCLES = 3

    // Authored: storm fills in ~4.5 minutes of energy phase (x100 units), 4x when enraged.
    const val STORM_MAX = 35000
    const val STORM_RATE = 155
    const val STORM_ENRAGED = 620
    const val HUD_STORM_MAX = 350

    // Authored: a cannon fires one fish every 2 loop ticks (4 game ticks); essence phase lasts 40 s.
    const val FIRE_INTERVAL = 2
    const val ESSENCE_PHASE_TICKS = 66

    // Wiki catch rate 170/256 -> 255/256 (regular harpoon).
    const val CATCH_LOW = 170
    const val CATCH_HIGH = 255
    const val COOK_TICKS = 3
    const val COOK_XP = 10.0

    // Wiki points.
    const val POINTS_CATCH = 5
    const val POINTS_COOK = 10
    const val POINTS_DEPOSIT_RAW = 20
    const val POINTS_DEPOSIT_COOKED = 65
    const val POINTS_SCATTER = 55
    const val POINTS_CAP = 32000
    const val PERMIT_POINTS = 2000
    const val PERMIT_STEP = 700
    const val PERMIT_CAP = 8000

    // Attacks (Wiki: none at or below 10% energy, none in the essence phase). All timings below are
    // authored (the Wiki gives none) and in game ticks; keep them even (the loop runs every 2 ticks).
    const val ATTACK_MIN_ENERGY_PCT = 10
    const val WAVE_FIRST = 90
    const val WAVE_INTERVAL = 110
    const val WAVE_WARN = 18
    const val WAVE_LENGTH = 6
    /** cs2 4068/4069 arg: overlay fade length in client cycles (~30 per game tick). Authored. */
    const val WAVE_FADE = 60
    const val WAVE_START_SCRIPT = 4068
    const val WAVE_END_SCRIPT = 4069
    const val WAVE_PUSH = 2
    const val WAVE_LOSS = 5
    // Wiki (Mod Ash): each tether site 15-25% to break per wave, scaling with storm intensity.
    const val TETHER_BREAK_MIN = 15
    const val TETHER_BREAK_MAX = 25

    const val LIGHTNING_FIRST = 50
    const val LIGHTNING_INTERVAL = 80
    const val LIGHTNING_WARN = 6
    const val LIGHTNING_STRIKES = 2
    const val LIGHTNING_RADIUS = 6
    // Wiki: a fire takes one losable item every 2 ticks (= every loop) and spreads at most twice.
    const val FIRE_MAX_SPREADS = 2
    const val FIRE_SPREAD_TICKS = 20
    const val FIRE_CAP = 16

    const val TORRENT_FIRST = 70
    const val TORRENT_INTERVAL = 90
    const val TORRENT_WARN = 6
    const val TORRENT_DISABLE = 16

    // Double ("leaping fish") spot: Wiki 50% bonus fish on a successful roll; timings authored.
    const val DOUBLE_SPOT_FIRST = 40
    const val DOUBLE_SPOT_INTERVAL = 50
    const val DOUBLE_SPOT_LENGTH = 40
    const val DOUBLE_SPOT_BONUS = 50

    // Wiki points / xp. Repair xp: 4 x Construction level (live needs a house; no house check here).
    const val POINTS_WAVE = 10
    const val POINTS_REPAIR = 40
    const val POINTS_DOUSE = 40
    const val REPAIR_XP_PER_LEVEL = 4

    const val END_DELAY = 30
    const val NPC_LIFETIME = 60000
    const val HUD_SCRIPT = 4074
}

internal object TemporossLocs : LocReferences() {
    val lobby_ladder = find("tempoross_lobby_ladder")
    val shrine = find("tempoross_shrine_fire")
    val crate_harpoon = find("tempoross_crate_harpoon")
    val crate_rope = find("tempoross_crate_rope")
    val crate_hammer = find("tempoross_crate_hammer")
    val crate_bucket = find("tempoross_crate_bucket")

    /** Tether bases (multilocs on varbit `tempoross_tethered`), in varbit order 1-4. */
    val sites =
        listOf(
            find("tempoross_totem_north"),
            find("tempoross_mast_bottom_west"),
            find("tempoross_mast_bottom_east"),
            find("tempoross_totem_south"),
        )
    val sites_broken =
        listOf(
            find("tempoross_totem_north_broken"),
            find("tempoross_mast_bottom_west_broken"),
            find("tempoross_mast_bottom_east_broken"),
            find("tempoross_totem_south_broken"),
        )
    val totem_tethered = find("tempoross_totem_tethered")
    val totem_untethered = find("tempoross_totem_untethered")
    val mast_tethered = find("tempoross_mast_bottom_tethered")
    val mast_untethered = find("tempoross_mast_bottom_untethered")
    val water_pump = find("tempoross_water_pump")
    val water_pump_dock = find("tempoross_water_pump_dock")
    val cannons = (1..4).map { find("tempoross_cannon_$it") }
    val cannons_disabled = (1..4).map { find("tempoross_cannon_${it}_disabled") }
    val fire = find("tempoross_fire_visuals")
}

internal object TemporossNpcs : NpcReferences() {
    val ferry_to_lobby = find("tempoross_ferry_to_lobby_npc")
    val ferry_from_lobby = find("tempoross_ferry_from_lobby_npc")
    val spot_north = find("tempoross_harpoonfish_fishingspot_north")
    val spot_south = find("tempoross_harpoonfish_fishingspot_south")
    val pool_active = find("tempoross_p2_fishingspot")
    val pool_inactive = find("tempoross_p2_fishingspot_inactive")
    val boss_ready = find("tempoross_boss_ready")
    val boss_whirlpool = find("tempoross_boss_whirlpool")
    val boss_enraged = find("tempoross_boss_enraged")
    val boss_unengaged = find("tempoross_boss_unengaged")
    val crates = (1..4).map { find("tempoross_npc_crate_ammunition_$it") }
    val hosts = listOf(find("tempoross_instance_host_w"), find("tempoross_instance_host_e"))
    val spot_special = find("tempoross_harpoonfish_fishingspot_special")
    val fire = find("tempoross_fire_hitbox")
    val cloud = find("tempoross_lightning_cloud")
}

internal object TemporossObjs : ObjReferences() {
    val raw_harpoonfish = find("tempoross_raw_harpoonfish")
    val cooked_harpoonfish = find("tempoross_harpoonfish")
    val harpoon = find("harpoon")
    val rope = find("rope")
    val hammer = find("hammer")
    val bucket = find("bucket_empty")
    val harpoons = listOf(harpoon, find("dragon_harpoon"), find("infernal_harpoon"), find("crystal_harpoon"))
    val bucket_water = find("bucket_water")
    val crystallised_harpoonfish = find("tempoross_crystallised_harpoonfish")
    val hammers = listOf(hammer, find("imcando_hammer"), find("imcando_hammer_offhand"))
    val spirit_angler =
        listOf(find("spirit_angler_hat"), find("spirit_angler_top"), find("spirit_angler_legs"), find("spirit_angler_boots"))
    /** Wiki: the only items a wave or fire can take. */
    val losable =
        listOf(hammer, rope, bucket, bucket_water, raw_harpoonfish, cooked_harpoonfish, crystallised_harpoonfish)
}

internal object TemporossSeqs : SeqReferences() {
    val harpoon = find("human_harpoon")
    val cook = find("human_cooking")
    val fill = find("human_pickuptable")
    val tether = find("tempoross_tether")
    val untether = find("tempoross_untether")
    val repair = find("human_poh_build")
}

internal object TemporossInterfaces : InterfaceReferences() {
    val hud = find("tempoross_hud")
}

internal object TemporossVarBits : VarBitReferences() {
    val current_points = find("tempoross_current_points")
    val reward_permits = find("tempoross_rewardpermits")
    /** 0 none, 1 north totem, 2 west mast, 3 east mast, 4 south totem (drives the tether multilocs). */
    val tethered = find("tempoross_tethered")
}

/** Skilling symbol range (65540). Saved per character: the authoritative permit count. */
object TemporossVarpBuilds : VarpBuilder() {
    init {
        build("tempoross_permits")
    }
}

internal object TemporossVarps : VarpReferences() {
    val permits = find("tempoross_permits")
    val kills = find("total_tempoross_kills")
}

internal object TemporossTimers : TimerReferences() {
    /** Custom timer (65504, skilling range). */
    val tick = find("tempoross_tick")
}
