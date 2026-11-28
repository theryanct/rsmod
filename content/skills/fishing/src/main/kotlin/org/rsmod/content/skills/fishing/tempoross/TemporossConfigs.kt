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
}

internal object TemporossObjs : ObjReferences() {
    val raw_harpoonfish = find("tempoross_raw_harpoonfish")
    val cooked_harpoonfish = find("tempoross_harpoonfish")
    val harpoon = find("harpoon")
    val rope = find("rope")
    val hammer = find("hammer")
    val bucket = find("bucket_empty")
    val harpoons = listOf(harpoon, find("dragon_harpoon"), find("infernal_harpoon"), find("crystal_harpoon"))
}

internal object TemporossSeqs : SeqReferences() {
    val harpoon = find("human_harpoon")
    val cook = find("human_cooking")
    val fill = find("human_pickuptable")
}

internal object TemporossInterfaces : InterfaceReferences() {
    val hud = find("tempoross_hud")
}

internal object TemporossVarBits : VarBitReferences() {
    val current_points = find("tempoross_current_points")
    val reward_permits = find("tempoross_rewardpermits")
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
