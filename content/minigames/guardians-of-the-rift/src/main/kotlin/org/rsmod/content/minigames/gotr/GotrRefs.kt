package org.rsmod.content.minigames.gotr

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences

internal object GotrLocs : LocReferences() {
    /** The temple's way back out to the Wizards' Tower basement. */
    val temple_exit = find("gotr_entry")
    val barrier = find("gotr_barrier")
    val workbench = find("gotr_workbench")
    val rubble_top = find("gotr_agility_shortcut_top")
    val rubble_bottom = find("gotr_agility_shortcut_bottom")
    /** Spawned as the huge-remains portal (in the temple) and its way back (in the west area). */
    val huge_portal = find("gotr_agility_portal_top")

    val parts = find("gotr_essence_tier_1")
    val parts_b = find("gotr_essence_tier_1b")
    val remains = find("gotr_essence_tier_1_large")
    val remains_b = find("gotr_essence_tier_1_large_b")
    val large_remains = find("gotr_essence_tier_2")
    val huge_remains = find("gotr_essence_tier_3")

    val portal_air = find("gotr_portal_air")
    val portal_water = find("gotr_portal_water")
    val portal_earth = find("gotr_portal_earth")
    val portal_fire = find("gotr_portal_fire")
    val portal_mind = find("gotr_portal_mind")
    val portal_body = find("gotr_portal_body")
    val portal_cosmic = find("gotr_portal_cosmic")
    val portal_chaos = find("gotr_portal_chaos")
    val portal_nature = find("gotr_portal_nature")
    val portal_law = find("gotr_portal_law")
    val portal_death = find("gotr_portal_death")
    val portal_blood = find("gotr_portal_blood")

    val uncharged_cells = find("gotr_uncharged_cells")
    val weak_cells = find("gotr_weak_cells")
    val tile_inactive = find("gotr_cell_tile_inactive")
    val tile_broken = find("gotr_cell_tile_broken_repairable")
    val tile_tiers =
        listOf(
            find("gotr_cell_tile_tier1"),
            find("gotr_cell_tile_tier2"),
            find("gotr_cell_tile_tier3"),
            find("gotr_cell_tile_tier4"),
        )
    val pile_east = find("gotr_guardian_spawn_east")
    val pile_west = find("gotr_guardian_spawn_west")
}

internal object GotrNpcs : NpcReferences() {
    val great_guardian = find("gotr_great_guardian")
    val leech = find("gotr_abyssal_leech")
    val walker = find("gotr_abyssal_walker")
    val pyramid = find("gotr_abyssal_pyramid")
    /** Rift guardians by tier (weak..overcharged). */
    val elemental_guardians =
        listOf(
            find("gotr_guardian_air"),
            find("gotr_guardian_water"),
            find("gotr_guardian_earth"),
            find("gotr_guardian_fire"),
        )
    val catalytic_guardians =
        listOf(
            find("gotr_guardian_mind"),
            find("gotr_guardian_chaos"),
            find("gotr_guardian_death"),
            find("gotr_guardian_blood"),
        )
}

internal object GotrObjs : ObjReferences() {
    val fragment = find("gotr_guardian_fragment")
    val essence = find("gotr_guardian_essence")
    val stone_elemental = find("gotr_guardian_stone_elemental")
    val stone_catalytic = find("gotr_guardian_stone_catalytic")
    val stone_polyelemental = find("gotr_guardian_stone_polyelemental")
    val stone_polycatalytic = find("gotr_guardian_stone_polycatalytic")
    val cell_uncharged = find("gotr_cell_uncharged")
    /** Charged cells by tier (weak..overcharged). */
    val cells =
        listOf(
            find("gotr_cell_tier1"),
            find("gotr_cell_tier2"),
            find("gotr_cell_tier3"),
            find("gotr_cell_tier4"),
        )
    val chisel = find("chisel")
    /** Raiments of the Eye by slot (any colour counts). */
    val raiments =
        listOf(
            listOf(find("hat_of_the_eye"), find("hat_of_the_eye_red"), find("hat_of_the_eye_green"), find("hat_of_the_eye_blue")),
            listOf(
                find("robe_top_of_the_eye"),
                find("robe_top_of_the_eye_red"),
                find("robe_top_of_the_eye_green"),
                find("robe_top_of_the_eye_blue"),
            ),
            listOf(
                find("robe_bottom_of_the_eye"),
                find("robe_bottom_of_the_eye_red"),
                find("robe_bottom_of_the_eye_green"),
                find("robe_bottom_of_the_eye_blue"),
            ),
            listOf(find("boots_of_the_eye")),
        )
    val talismans =
        listOf(
            find("gotr_portal_talisman_air"),
            find("gotr_portal_talisman_water"),
            find("gotr_portal_talisman_earth"),
            find("gotr_portal_talisman_fire"),
            find("gotr_portal_talisman_mind"),
            find("gotr_portal_talisman_body"),
            find("gotr_portal_talisman_cosmic"),
            find("gotr_portal_talisman_chaos"),
            find("gotr_portal_talisman_nature"),
            find("gotr_portal_talisman_law"),
            find("gotr_portal_talisman_death"),
            find("gotr_portal_talisman_blood"),
        )
}

internal object GotrInterfaces : InterfaceReferences() {
    val hud = find("gotr_hud")
}

internal object GotrSeqs : SeqReferences() {
    val workbench = find("gotr_chisel_essence")
    val chisel_guardian = find("gotr_chisel_guardian")
    val leech_attack = find("gotr_abyssal_leech_attack")
    val leech_death = find("gotr_abyssal_leech_death")
    val walker_attack = find("gotr_abyssal_walker_attack")
    val walker_death = find("gotr_abyssal_walker_death")
    val pyramid_attack = find("gotr_abyssal_pyramid_attack")
    val pyramid_death = find("gotr_abyssal_pyramid_death")
}



internal object GotrQueues : QueueReferences() {
    /** Custom (ui range 65301/65302): mining / workbench loops. */
    val mine = find("gotr_mine")
    val workbench = find("gotr_workbench")
}

internal object GotrTimers : TimerReferences() {
    /** Custom (ui range 65301): the per-player game clock. */
    val tick = find("gotr_tick")
}

internal object GotrVarbits : VarBitReferences() {
    /** Toggle-talisman per guardian (1 = stop receiving), in guardian order. */
    val talisman_toggles =
        listOf("air", "water", "earth", "fire", "mind", "body", "cosmic", "chaos", "nature", "law", "death", "blood")
            .map { find("gotr_talisman_toggle_$it") }
}

/** Custom (ui range 65330/65331): unspent elemental / catalytic reward points. */
internal object GotrVarps : VarpReferences() {
    val points_elemental = find("gotr_points_elemental")
    val points_catalytic = find("gotr_points_catalytic")
}

internal object GotrVarpBuilder : VarpBuilder() {
    init {
        build("gotr_points_elemental")
        build("gotr_points_catalytic")
    }
}

/** The Great Guardian and the apprentices (OSRS Wiki map pins). */
internal object GotrNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<GotrNpcSpawns>("npcs.toml")
    }
}
