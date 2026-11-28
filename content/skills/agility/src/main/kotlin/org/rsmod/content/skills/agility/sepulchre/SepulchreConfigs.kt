package org.rsmod.content.skills.agility.sepulchre

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid

/** One floor (Wiki "Hallowed Sepulchre": current requirements, after the 12 Aug 2026 update). */
internal class FloorDef(
    val floor: Int,
    val agility: Int,
    val xp: Double,
    val petChance: Int,
    /** Agility xp per skill-challenge crossing (Wiki per-floor "both ways" figure / 2). */
    val crossingXp: Double,
    /** `hallowed_stairs_return` locs (static cache coords); one is picked at random. */
    val entrances: List<CoordGrid>,
    /** Rough middle of the safe zone; destinations are snapped towards it. Derived. */
    val centre: CoordGrid,
    /** Safe-zone box (timer pauses). Derived from the obelisk/stairs locs, unverified. */
    val safeX: IntRange,
    val safeZ: IntRange,
    val marks: IntRange,
    /** Coffin loot tier chances in percent: low, mid (high is the rest). */
    val lowPct: Int,
    val midPct: Int,
    val lockpickChance: Int,
    val clueTier: String,
    val clueChance: Int,
)

/** A pillar crossing: the `hallowed_ranged_grapple` loc spans 9 tiles from [origin]. */
internal class PillarSite(val origin: CoordGrid, val alongX: Boolean, val bonusXp: Double = 0.0) {
    val low: CoordGrid
        get() = if (alongX) origin.translate(-1, 0) else origin.translate(0, -1)

    val high: CoordGrid
        get() =
            if (alongX) {
                origin.translate(SepulchreTuning.PILLAR_SPAN, 0)
            } else {
                origin.translate(0, SepulchreTuning.PILLAR_SPAN)
            }
}

/** A bridge crossing between two end tiles (the panel tiles outside the broken ends). */
internal class BridgeSite(val a: CoordGrid, val b: CoordGrid)

/**
 * Static template coordinates (rev-243 cache, ~/code/rsmod-sepulchre-notes.md). OSRS instances the
 * floors; single-player v1 uses the template squares directly. Every arrival tile is derived from
 * loc coords and snapped to the nearest walkable tile at runtime (unverified, needs a capture).
 */
internal object SepulchreMap {
    val DARKMEYER_OUTSIDE = CoordGrid(3654, 3383, 0)
    val LOBBY_ARRIVAL = CoordGrid(2400, 5970, 0)
    /** Notes: "around 2400,5975,0", unverified. */
    val LOBBY_RETURN = CoordGrid(2400, 5975, 0)

    /** Map squares (x shl 8 or z) of the five floors. Lobby square 37_93 is not one of them. */
    val FLOOR_SQUARES: Set<Int> =
        buildSet {
            for (x in 34..36) for (z in 92..94) add((x shl 8) or z)
            add((38 shl 8) or 93)
            add((39 shl 8) or 93)
            add((37 shl 8) or 90)
            add((37 shl 8) or 91)
            add((39 shl 8) or 90)
            add((39 shl 8) or 91)
            add((35 shl 8) or 91)
        }

    fun onFloors(coords: CoordGrid): Boolean = ((coords.x shr 6) shl 8 or (coords.z shr 6)) in FLOOR_SQUARES

    val FLOORS: List<FloorDef> =
        listOf(
            FloorDef(
                floor = 1,
                agility = 52,
                xp = 575.0,
                petChance = 35_000,
                crossingXp = 50.0,
                entrances =
                    listOf(
                        CoordGrid(2232, 5959, 2),
                        CoordGrid(2292, 5947, 2),
                        CoordGrid(2252, 6019, 2),
                        CoordGrid(2310, 6010, 2),
                    ),
                centre = CoordGrid(2272, 5984, 1),
                safeX = 2266..2278,
                safeZ = 5982..5986,
                marks = 1..1,
                lowPct = 60,
                midPct = 35,
                lockpickChance = 200,
                clueTier = "easy",
                clueChance = 50,
            ),
            FloorDef(
                floor = 2,
                agility = 62,
                xp = 925.0,
                petChance = 16_000,
                crossingXp = 50.0,
                entrances =
                    listOf(
                        CoordGrid(2525, 5983, 2),
                        CoordGrid(2527, 5981, 2),
                        CoordGrid(2527, 5986, 2),
                        CoordGrid(2530, 5983, 2),
                    ),
                centre = CoordGrid(2528, 5984, 1),
                safeX = 2523..2533,
                safeZ = 5979..5989,
                marks = 2..3,
                lowPct = 30,
                midPct = 60,
                lockpickChance = 120,
                clueTier = "medium",
                clueChance = 50,
            ),
            FloorDef(
                floor = 3,
                agility = 72,
                xp = 1_600.0,
                petChance = 8_000,
                crossingXp = 75.0,
                entrances = listOf(CoordGrid(2397, 5855, 2), CoordGrid(2402, 5855, 2)),
                centre = CoordGrid(2400, 5859, 1),
                safeX = 2397..2403,
                safeZ = 5857..5861,
                marks = 3..5,
                lowPct = 15,
                midPct = 65,
                lockpickChance = 90,
                clueTier = "medium",
                clueChance = 25,
            ),
            FloorDef(
                floor = 4,
                agility = 77,
                xp = 2_875.0,
                petChance = 4_000,
                crossingXp = 75.0,
                entrances = listOf(CoordGrid(2527, 5853, 2), CoordGrid(2527, 5858, 2)),
                centre = CoordGrid(2528, 5853, 1),
                safeX = 2525..2531,
                safeZ = 5851..5856,
                marks = 3..6,
                lowPct = 0,
                midPct = 60,
                lockpickChance = 60,
                clueTier = "hard",
                clueChance = 50,
            ),
            FloorDef(
                floor = 5,
                agility = 87,
                xp = 5_725.0,
                petChance = 2_000,
                crossingXp = 80.0,
                entrances = listOf(CoordGrid(2271, 5885, 2)),
                centre = CoordGrid(2272, 5858, 0),
                safeX = 2269..2275,
                safeZ = 5845..5860,
                marks = 4..6,
                lowPct = 0,
                midPct = 20,
                lockpickChance = 40,
                clueTier = "elite",
                clueChance = 60,
            ),
        )

    /** Grapple locs (rotation 0/2 span z, 1/3 span x). Floor 2 north pillars: Wiki +10 xp total. */
    val PILLARS: List<PillarSite> =
        listOf(
            PillarSite(CoordGrid(2282, 5974, 1), alongX = false),
            PillarSite(CoordGrid(2513, 6004, 2), alongX = true, bonusXp = 5.0),
            PillarSite(CoordGrid(2535, 6004, 2), alongX = true, bonusXp = 5.0),
            PillarSite(CoordGrid(2388, 5867, 2), alongX = true),
            PillarSite(CoordGrid(2404, 5868, 2), alongX = true),
            PillarSite(CoordGrid(2284, 5835, 1), alongX = true),
        )

    val BRIDGES: List<BridgeSite> =
        listOf(
            BridgeSite(CoordGrid(2262, 5975, 1), CoordGrid(2262, 5981, 1)),
            BridgeSite(CoordGrid(2553, 5843, 2), CoordGrid(2553, 5849, 2)),
            BridgeSite(CoordGrid(2244, 5858, 0), CoordGrid(2244, 5864, 0)),
        )
}

/** Tunables. Wiki values are marked; "authored" means the Wiki is silent. */
internal object SepulchreTuning {
    const val LOOP_TICKS = 1
    /** Wiki: 2:00 per floor, carried over. Internal unit: game ticks (200 = 2:00). */
    const val FLOOR_TICKS = 200
    /** Wiki: a hallowed token adds 1:00. */
    const val TOKEN_TICKS = 100
    /**
     * Ticks per `hallowed_time_remaining` unit. The overlay's unit is unverified (notes: probably
     * ticks); if the HUD shows the wrong time, change only this.
     */
    const val HUD_TICKS_PER_UNIT = 1
    /** The stairs/final-barrier multilocs lock at 0 and 1, so keep 2+ while time remains. */
    const val HUD_MIN_LIVE = 2
    const val VARBIT_13_MAX = 8191
    const val MAX_TICKS = 8000

    const val PILLAR_SPAN = 9
    const val LAND_RADIUS = 4
    /** Authored: ticks off the floor squares before a run is dropped (covers stair transitions). */
    const val OFF_FLOOR_TICKS = 3

    // Wiki skill-challenge requirements and xp.
    const val RANGED_LEVEL = 62
    const val CONSTRUCTION_LEVEL = 56
    const val PRAYER_LEVEL = 54
    const val PRAYER_XP = 200.0
    const val DUST = 2
    const val MAGIC_LEVEL = 7
    const val MAGIC_XP = 200.0
    /** Authored: the enchant cast is reduced to one cosmic rune (every enchant spell uses one). */
    const val CONJURE_COSMICS = 1

    // Wiki coffins.
    const val COFFIN_THIEVING = 66
    const val COFFIN_XP = 200.0
    const val GRAND_THIEVING = 84
    const val GRAND_THIEVING_ALT = 66
    const val GRAND_AGILITY_ALT = 92
    val GRAND_MARKS = 8..10
    const val GRAND_RING_CHANCE = 200
    const val GRAND_CLUE_CHANCE = 30
    const val PAGE_CHANCE = 10
    /** Authored: failure poison strength (the Wiki only says the trap poisons you). */
    const val FAIL_POISON = 3

    // Wiki success rates (low/high out of 256).
    const val NO_PICK_LOW = -60
    const val NO_PICK_HIGH = 190
    const val PICK_LOW = -40
    const val PICK_HIGH = 210
    const val STRANGE_LOW = -20
    const val STRANGE_HIGH = 230

    // Wiki obelisk god-item effects (percent of run energy, hp).
    const val MINOR_GOD_DRAIN = 25
    const val MINOR_GOD_HP = 1
    const val ZAMORAK_HP = 3

    // Wiki sack: 5 low, 1 mid, 2 high rolls.
    const val SACK_LOW = 5
    const val SACK_MID = 1
    const val SACK_HIGH = 2

    const val SHOP_MAX_COUNT = 1000
}

internal object SepLocs : LocReferences() {
    val ow_entrance = find("sepulchre_ow_entrance")
    val lobby_exit = find("sepulchre_lobby_exit_3x3")
    val minigame_start = find("hallowed_minigame_start")
    val stairs_return = find("hallowed_stairs_return")
    val obelisk = find("hallowed_exit")
    val progress_stairs = find("hallowed_progress_stairs")
    val stairs_floor = listOf(
        find("hallowed_stairs_floor1"),
        find("hallowed_stairs_floor2"),
        find("hallowed_stairs_floor3"),
        find("hallowed_stairs_floor4"),
    )
    val drops =
        listOf(
            find("hallowed_floor_1_northpath_drop"),
            find("hallowed_floor_1_eastpath_stairs"),
            find("hallowed_floor_1_southpath_stairs"),
            find("hallowed_floor_1_westpath_drop"),
            find("hallowed_floor_2_northpath_stairs"),
            find("hallowed_floor_2_eastpath_stairs"),
            find("hallowed_floor_2_southpath_stairs"),
            find("hallowed_floor_2_westpath_drop"),
            find("hallowed_floor_3_eastpath_drop"),
            find("hallowed_floor_3_westpath_drop"),
            find("hallowed_floor_4_northpath_drop"),
            find("hallowed_floor_4_southpath_stairs"),
            find("hallowed_floor_5_drop_1"),
            find("hallowed_floor_5_drop_2"),
        )
    val platforms =
        listOf(
            find("hallowed_path_end_jumpover_01_north"),
            find("hallowed_path_end_jumpover_01_east"),
            find("hallowed_path_end_jumpover_01_south"),
            find("hallowed_path_end_jumpover_01_west"),
            find("hallowed_path_end_jumpover_02"),
        )
    val gate = find("hallowed_path_end_gate")
    val stepping_stones = listOf(find("hallowed_floor_2_steppingstone"), find("hallowed_floor_5_steppingstone"))

    // Coffins: bases (multilocs) and the unlooted variants that carry the op.
    val coffin_a = find("hallowed_reward_coffin_a")
    val coffin_b = find("hallowed_reward_coffin_b")
    val coffin_unlooted = find("hallowed_reward_coffin_unlooted")
    val f5_grapple_coffin = find("hallowed_f5_grapple_coffin")
    val f5_portal_coffin = find("hallowed_f5_portal_coffin")
    val f5_bridge_coffin = find("hallowed_f5_bridge_coffin")
    val f5_grapple_unlooted = find("hallowed_f5_grapple_coffin_unlooted")
    val f5_portal_unlooted = find("hallowed_f5_portal_coffin_unlooted")
    val f5_bridge_unlooted = find("hallowed_f5_bridge_coffin_unlooted")
    val grand_coffin = find("hallowed_final_chest")
    val grand_unlooted = find("hallowed_final_chest_unlooted")
    val grand_barrier_open = find("hallowed_final_chest_barrier_inactive")

    // Skill challenges (variants carry the ops).
    val pillar_ready = find("hallowed_treasure_ranged_pillar_ready")
    val brazier_ready = find("hallowed_treasure_prayer_ready")
    val brazier_ready_mirror = find("hallowed_treasure_prayer_ready_mirror")
    val barrier_final = find("hallowed_treasure_prayer_barrier_final")
    val bridge_ready = find("hallowed_treasure_construction_ready")
    val bridge_pool_ready = find("hallowed_treasure_construction_pool_ready")
    val bridge_final = find("hallowed_treasure_construction_final")
    val bridge_pool_final = find("hallowed_treasure_construction_pool_final")
    val portal_ready = find("hallowed_treasure_magic_ready")
    val portal_final = find("hallowed_treasure_magic_final")
}

internal object SepNpcs : NpcReferences() {
    val stranger = find("hallowed_lobby_npc_3op")
}

internal object SepObjs : ObjReferences() {
    val mark = find("hallowed_mark")
    val token = find("hallowed_token")
    val sack = find("hallowed_sack")
    val shard = find("hallowed_teleport")
    val grapple = find("hallowed_grapple")
    val focus = find("hallowed_focus")
    val symbol = find("hallowed_symbol")
    val hammer_hallowed = find("hallowed_hammer")
    val ring = find("hallowed_ring")
    val dark_dye = find("dark_dye")
    val dark_acorn = find("dark_acorn")
    val ring_of_endurance = find("ring_of_endurance_uncharged")
    val lockpick = find("lockpick")
    val strange_lockpick = find("strange_old_lockpick")
    val strange_lockpick_full = find("strange_old_lockpick_full")
    val pet = find("skillpetagility")
    val pages =
        listOf(
            find("hallowed_floor1_page"),
            find("hallowed_floor2_page"),
            find("hallowed_floor3_page"),
            find("hallowed_floor4_page"),
            find("hallowed_floor5_page"),
        )

    val mith_grapple = find("xbows_grapple_tip_bolt_mithril_rope")
    val hammer = find("hammer")
    val saw = find("poh_saw")
    val vampire_dust = find("vampire_dust")
    val cosmic = find("cosmicrune")

    /** Wiki bridge tiers, best first: planks, nails, Construction xp. */
    val bridgeTiers: List<Triple<ObjType, ObjType, Double>> =
        listOf(
            Triple(find("plank_mahogany"), find("nails_rune"), 700.0),
            Triple(find("plank_teak"), find("nails_adamant"), 450.0),
            Triple(find("plank_oak"), find("nails_mithril"), 300.0),
            Triple(find("woodplank"), find("nails"), 150.0),
        )

    val coins = find("coins")
    val low =
        listOf(
            Drop(find("monkrobetop"), 1, 1),
            Drop(find("monkrobebottom"), 1, 1),
            Drop(find("blessedstar"), 1, 1),
            Drop(find("airrune"), 500, 750),
            Drop(find("firerune"), 500, 750),
            Drop(find("chaosrune"), 25, 50),
            Drop(find("xbows_crossbow_bolts_mithril"), 50, 150),
            Drop(find("2doseprayerrestore"), 1, 1),
            Drop(find("whitelilly"), 1, 1),
            Drop(coins, 1_500, 3_000),
        )
    val mid =
        listOf(
            Drop(find("adamant_2h_sword"), 1, 1),
            Drop(find("adamant_platebody"), 1, 1),
            Drop(cosmic, 60, 100),
            Drop(find("deathrune"), 60, 100),
            Drop(find("naturerune"), 60, 100),
            Drop(find("xbows_crossbow_bolts_adamantite"), 50, 200),
            Drop(find("monkfish"), 1, 3),
            Drop(find("4doseprayerrestore"), 1, 1),
            Drop(find("unidentified_ranarr"), 1, 2),
            Drop(coins, 7_500, 12_500),
        )
    val high =
        listOf(
            Drop(find("rune_2h_sword"), 1, 1),
            Drop(find("rune_platebody"), 1, 1),
            Drop(find("lawrune"), 150, 250),
            Drop(find("bloodrune"), 150, 250),
            Drop(find("soulrune"), 150, 250),
            Drop(find("xbows_crossbow_bolts_runite"), 100, 300),
            Drop(find("monkfish"), 2, 6),
            Drop(find("sanfew_salve_4_dose"), 1, 2),
            Drop(find("ranarr_seed"), 1, 2),
            Drop(coins, 17_500, 25_000),
        )
}

internal class Drop(val obj: ObjType, val min: Int, val max: Int)

internal object SepSeqs : SeqReferences() {
    val jump = find("human_jump_hurdle")
    val stone = find("human_steppingstonejump")
    val squeeze = find("railing_squeeze")
    val open = find("human_openchest")
    val crossbow = find("human_crossbow")
    val build = find("human_poh_build")
    val sacrifice = find("human_bone_sacrifice")
    val conjure = find("human_castchargeorb")
}

internal object SepInterfaces : InterfaceReferences() {
    val overlay = find("hallowed_overlay")
}

internal object SepVarBits : VarBitReferences() {
    val time_remaining = find("hallowed_time_remaining")
    val time_spent = find("hallowed_time_spent")
    val current_floor = find("hallowed_current_floor")
    val floor_time_spent = find("hallowed_current_floor_time_spent")
    val normal_chest_count = find("hallowed_normal_chest_count")
    val final_chest_count = find("hallowed_final_chest_count")

    // Shared treasure state (reset on every floor change).
    val ranged_state = find("hallowed_ranged_state")
    val prayer_state = find("hallowed_prayer_state")
    val construction_state = find("hallowed_construction_state")
    val magic_state = find("hallowed_magic_state")
    val ashes_offered = find("hallowed_prayer_ashes_offered")
    val spell_failed = find("hallowed_magic_spell_failed")
    val looted = find("hallowed_current_floor_looted")
    val looted_b = find("hallowed_current_floor_looted_b")
    val grapple_type = find("hallowed_grapple_type")
    val final_looted = find("hallowed_final_floor_looted")
    val f5_grapple_looted = find("hallowed_floor5_grapple_looted")
    val f5_portal_looted = find("hallowed_floor5_portal_looted")
    val f5_bridge_looted = find("hallowed_floor5_bridge_looted")

    val treasure =
        listOf(
            ranged_state,
            prayer_state,
            construction_state,
            magic_state,
            ashes_offered,
            spell_failed,
            looted,
            looted_b,
            grapple_type,
            final_looted,
            f5_grapple_looted,
            f5_portal_looted,
            f5_bridge_looted,
        )
}

internal object SepTimers : TimerReferences() {
    /** Custom timer (65506, skilling range). */
    val tick = find("hallowed_tick")
}

/** The Mysterious Stranger, Wiki map (2400, 5986, 0). Nobody else spawns it. */
object SepulchreNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<Sepulchre>("sepulchre_npcs.toml")
    }
}
