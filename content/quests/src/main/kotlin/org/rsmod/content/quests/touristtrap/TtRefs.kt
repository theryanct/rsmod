package org.rsmod.content.quests.touristtrap

import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences

// Every cache ref The Tourist Trap uses (re-check after cache updates).

internal object TtNpcs : NpcReferences() {
    /** Irena's multinpcs (`tourtrap_qip_irena_state` 0 sad, 1 happy). */
    val irena = find("irena")
    val irena_happy = find("tourtrap_qip_irena_happy")
    /** Ana: `tourtrap_qip_ana_multi` (in the mine until she's in the barrel) and the Shantay one. */
    val ana = find("ana")
    val ana_multi = find("tourtrap_qip_ana_multi")
    val ana_shanty = find("tourtrap_qip_ana_shanty_multi")
    val captain = find("desertminingcaptain")
    val mercenaries =
        listOf(
            find("tourtrap_qip_desert_mining_merc_1"),
            find("tourtrap_qip_desert_mining_merc_2"),
            find("tourtrap_qip_desert_mining_merc_3"),
            find("tourtrap_qip_desert_mining_merc_4"),
        )
    /** The camp's guards (surface and mine); [cave_guards] want the pineapple. */
    val guards =
        listOf(
            find("tourtrap_qip_desert_mining_guard_1"),
            find("tourtrap_qip_desert_mining_guard_2"),
            find("tourtrap_qip_desert_mining_guard_3"),
            find("tourtrap_qip_desert_mining_guard_4"),
            find("tourtrap_qip_desert_mining_guard_5"),
            find("tourtrap_qip_desert_mining_guard_6"),
            find("tourtrap_qip_rowdy_desert_mining_guard_1"),
        )
    val cave_guards =
        listOf(
            find("tourtrap_qip_desert_mining_guard_still_1"),
            find("tourtrap_qip_desert_mining_guard_still_2"),
            find("tourtrap_qip_desert_mining_guard_still_3"),
        )
    /** The winch guard (named "Mercenary" in the cache). */
    val winch_guard = find("tourtrap_qip_still_desert_mining_guard")
    /** The prison-mine gate guard. */
    val prison_guard = find("rowdymineguard")
    /** The quest's male slave by the mine doors (`tourtrap_qip_mineslave` 0 chained, 1 escaping). */
    val mine_slave = find("tourtrap_qip_mineslave_clothes_multi")
    val male_slave = find("mining_slave_male")
    val escaped_slave = find("escaped_slave")
    val slaves =
        listOf(
            find("mining_slave_female"),
            find("tourtrap_qip_slave_male_1"),
            find("tourtrap_qip_mining_slave_male_2"),
            find("tourtrap_qip_mining_slave_female_1"),
            find("tourtrap_qip_mining_slave_female_2"),
        )
    val rowdy_slave = find("slave_rowdy")
    /** Captain Siad (the sailing multinpc `capt_siad`, shown as `capt_siad_vis`). */
    val siad = find("capt_siad")
    val siad_vis = find("capt_siad_vis")
    val al_shabim = find("al_shabim")
    val nomad = find("bedabin")
    val nomad_guard = find("bedabin_guard")
    val driver = find("mining_cart_driver")
}

internal object TtObjs : ObjReferences() {
    val metal_key = find("metal_key")
    val cell_key = find("thcelldoorkey")
    val wrought_key = find("thgoodminekey")
    val barrel = find("thminebarrel_empty")
    val ana_barrel = find("thanainabarrel")
    val slave_shirt = find("slave_shirt")
    val slave_robe = find("slave_robe")
    val slave_boots = find("slave_boots")
    val desert_shirt = find("desert_shirt")
    val desert_robe = find("desert_robe")
    val desert_boots = find("desert_boots")
    val plans = find("thcaptplans")
    val bedabin_key = find("thbedobinkey")
    val dart_tip = find("thprotodarttip")
    val dart = find("thprotodart")
    val tenti = find("tentipineapple")
    val punish_rock = find("thpunishrock")
    val pineapple = find("pineapple")
    val pineapple_ring = find("pineapple_ring")
    val pineapple_chunks = find("pineapple_chunks")
    val bronze_bar = find("bronze_bar")
    val feather = find("feather")
    val hammer = find("hammer")
    val bronze_dart = find("bronze_dart")
    val coins = find("coins")
    val lockpick = find("lockpick")
    val waterskin_empty = find("water_skin0")
    val waterskins = listOf(find("water_skin1"), find("water_skin2"), find("water_skin3"), find("water_skin4"))
}

internal object TtLocs : LocReferences() {
    val gates = listOf(find("miningcampgateclosedl"), find("miningcampgateclosedr"))
    val mine_doors = listOf(find("thttmineentrancel"), find("thttmineentrancer"))
    val mine_exits = listOf(find("thttmineexitl"), find("thttmineexitr"))
    val caves = listOf(find("thminecavel"), find("thminecaver"))
    val chest = find("captain_siads_chest_closed")
    val bookcase = find("capt_siad_bookcase")
    val table = find("tourtrap_qip_desert_table")
    val cell_door = find("capt_siad_cell_door")
    /** `tourtrap_qip_cell_bars_multi` children (`tourtrap_qip_cell_wall` 0 / 1). */
    val bars = find("tourtrap_qip_cell_bar_wall_bars")
    val bars_bent = find("tourtrap_qip_cell_bar_wall_bars_bent")
    val climb_rock = find("tourtrap_qip_climb_rock_1")
    val cliff_up = find("tourtrap_qip_clifftop_climbup")
    val cliff_down = find("tourtrap_qip_clifftop_climbdown")
    val barrel_full = find("thminebarrel_full")
    val barrel_empty = find("thminebarrel_empty")
    val mine_cart = find("touristtrap_minecart")
    val winch_bucket = find("tourtrap_qip_ropepullthingy2")
    val winch = find("tourtrap_qip_ropepullthingy")
    /** `tourtrap_qip_anabarrel_winchside_multi` child (`tourtrap_qip_ana_winchside_barrel` 1). */
    val winch_barrel = find("thminebarrel_ana_winchside")
    /** `tourtrap_qip_multi_flatback_cart` children (`tourtrap_qip_flatback_cart_ana` 0 / 1). */
    val cart = find("tourtrap_qip_cart_normal")
    val cart_ana = find("tourtrap_qip_cart_ana")
    val camel = find("tourtrap_qip_cart_camel")
    val prison_gates = listOf(find("undergroundcellgatel"), find("undergroundcellgater"))
    val nice_gates = listOf(find("undergroundniceminel"), find("undergroundniceminer"))
    val punish_rocks = find("punishrocks")
    val tent_door = find("bedabin_tentdoor")
    val anvil = find("experimental_anvil")
    val footprints =
        listOf(
            find("tourtrap_qip_footprints_1"),
            find("tourtrap_qip_footprints_2"),
            find("tourtrap_qip_footprints_3"),
            find("tourtrap_qip_footprints_4"),
            find("tourtrap_qip_footprints_5"),
            find("tourtrap_qip_footprints_diagonal_1"),
            find("tourtrap_qip_footprints_diagonal_2"),
            find("tourtrap_qip_footprints_diagonal_3"),
            find("tourtrap_qip_footprints_diagonal_4"),
        )
    val scarf_cactus = find("tourtrap_qip_cactus_scarf")
}

internal object TtSeqs : SeqReferences() {
    val smithing = find("human_smithing")
    val fletch_dart = find("human_fletching_add_dart_feathers_bronze")
}

internal object TtVarps : VarpReferences() {
    /** The cache quest varp (`desertrescue`, 197; db end state 30). */
    val quest = find("desertrescue")
    /**
     * Server varp (65970): bit 0 offered to catch Al Zaba Bhasim, 1 Al Shabim said he doesn't exist,
     * 2-3 times thrown out by the mercenaries, 4-6 the bet (0 none, 1-4 = 5/10/15/20 gold), 7 talked
     * to Ana, 8-9 Irena's first reward skill + 1 (kept for the second pick's message only).
     */
    val progress = find("tourist_trap_progress")
}

internal object TtVarpBuilds : VarpBuilder() {
    init {
        build("tourist_trap_progress")
    }
}

internal object TtVarBits : VarBitReferences() {
    val cell_wall = find("tourtrap_qip_cell_wall")
    val mine_slave = find("tourtrap_qip_mineslave")
    val ana_state = find("tourtrap_qip_ana_state")
    val irena_state = find("tourtrap_qip_irena_state")
    val winch_barrel = find("tourtrap_qip_ana_winchside_barrel")
    val cart_ana = find("tourtrap_qip_flatback_cart_ana")
    val ana_shanty = find("tourtrap_ana_visible_shanty_pass")
}

internal object TtTimers : TimerReferences() {
    /** Custom timer (65570): the guards' look-over while the player is in the camp or its mine. */
    val camp = find("tourist_trap_camp")
}
