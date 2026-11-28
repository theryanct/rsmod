package org.rsmod.content.quests.coldwar

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences

// Every cache ref Cold War and the Penguin Agility Course use (re-check after cache updates).

internal object CwNpcs : NpcReferences() {
    /** Zoo Larry is also the child of the Lumbridge multinpc (`peng_multi_larry` 1). */
    val larries =
        listOf(find("peng_larry_zoo"), find("peng_larry_rell"), find("peng_larry_ice"), find("peng_multi_larry_lumb"))
    val zoo_penguin = find("peng_zoo")
    val sheep = find("sheep_shearer_the_thing")
    val fred = find("fred_the_farmer")
    /** The avalanche agent (`peng_multi_kgp` 1), the booth agent (0-1) and their child. */
    val kgps = listOf(find("peng_multi_kgp"), find("peng_multi_kgp_booth"), find("peng_kgp"))
    val kgp = find("peng_kgp")
    val debrief = find("peng_kgp_tie")
    val noodles = listOf(find("peng_noodle_multi"), find("peng_noodle"))
    val ping = find("peng_ping")
    val pong = find("peng_pong")
    val pax = find("peng_pescaling_cutscene")
    val commander = find("peng_commander")
    val instructor = find("peng_agility_instructor")
    val cutscene_penguin = find("peng_generic_cutscene")
    /** What the player turns into inside the clockwork suit. */
    val suit_transmog = find("peng_suit_0op")
    val icelords =
        listOf(
            find("peng_icelord_warrior01"),
            find("peng_icelord_warrior02"),
            find("peng_icelord_warrior03"),
            find("peng_icelord_warrior04"),
        )
    val icelord_ids: Set<Int> by lazy { icelords.map { it.id }.toSet() }
}

internal object CwLocs : LocReferences() {
    /** Rellekka's boat (multiloc on `my2arm_status`; 0-34 shows the Travel child) and the iceberg's. */
    val boats =
        listOf(find("peng_boat_rell"), find("peng_boat_ice"), find("peng_boat_3ops"), find("peng_row_boat_clickzone"))
    val hides =
        listOf(
            find("peng_observer_cabin_multiloc"),
            find("peng_observer_cabin_notbuild"),
            find("peng_observer_cabin_built"),
            find("peng_observer_cabin_built_snowy"),
            find("peng_observer_cabin_blownup"),
        )
    val enclosure_door = find("peng_ardougne_enclosure_door")
    val avalanches = listOf(find("peng_aval_l"), find("peng_aval_r"))
    val base_exit = find("peng_base_double_door_mid")
    val base_doors = listOf(find("peng_base_door"), find("peng_base_door_agility"), find("peng_base_door_bard"))
    val agility_exit = find("peng_base_double_door_mid_agility")
    val cliff_door = find("peng_cliffwall_door_mid")
    val control_panel = find("peng_base_booth_front")
    val door_button = find("peng_base_door_button")
    val blast_door = find("peng_base_blast_door")
    val pen_door = find("peng_icelord_pen_door")
    val icelord_door = find("peng_base_door_icelords")
    val chasm = find("peng_base_chasm")
    val cracks = listOf(find("peng_multi_crack"), find("peng_crack01"))

    /* The Penguin Agility Course. */
    val steps = find("peng_agility_steps01")
    val first_stone = find("peng_agility_crushcourse_stepstone01")
    val stone_zones = (0..7).map { find("peng_jump_stone_clickzone_0$it") } + find("peng_jump_stone_clickzone_01_5")
    val icicles = find("peng_iciclepillar_clickzone")
    val ice = (1..9).map { find("peng_agility_slippery_glitters0$it") }
    val gate = find("peng_agility_fencing_door")
}

internal object CwObjs : ObjReferences() {
    val oak_plank = find("plank_oak")
    val nails = find("nails")
    val hammer = find("hammer")
    val spade = find("spade")
    val book = find("peng_book")
    val suit = find("peng_suit_unwound")
    val report_zoo = find("peng_report_1")
    val report_lumbridge = find("peng_report_2")
    val report_fake = find("peng_report_3")
    val id_card = find("peng_id")
    val raw_cod = find("raw_cod")
    val ring_of_charos = find("ring_of_charos_unlocked")
    val swamp_tar = find("swamp_tar")
    val feather = find("feather")
    val cowbell = find("peng_cowbell")
    val bongos = find("peng_bongos")
    val mahogany_plank = find("plank_mahogany")
    val leather = find("leather")
}

internal object CwSeqs : SeqReferences() {
    /** In the penguin emote tab's order (components 1-8), which is also the greeting code's 1-8. */
    val emotes =
        listOf(
            find("peng_emote_shiver"),
            find("peng_emote_spin"),
            find("peng_emote_clap"),
            find("peng_emote_bow"),
            find("peng_emote_cheer"),
            find("peng_emote_wave"),
            find("peng_emote_preen"),
            find("peng_emote_flap"),
        )
    val hammer = find("human_hammer_hit")
    val dig = find("human_dig")
    val bongo = find("peng_player_bongo")
    val jump = find("peng_jump")
    val jump_fail = find("peng_jump_fail")
    val icicle_walk = find("peng_icicle_walk")
    val icicle_fail = find("peng_icicle_walk_fail")
    val ice_walk = find("peng_slippery_walk")
    val ice_fail = find("peng_slippery_walk_fail")
    val slide = find("peng_slide_run")
}

internal object CwInterfaces : InterfaceReferences() {
    val peng_emote = find("peng_emote")
    val book = find("peng_clockwork_book_interface")
}

internal object CwComponents : ComponentReferences() {
    val emotes =
        listOf("shiver", "spin", "clap", "bow", "cheer", "wave", "preen", "flap").map {
            find("peng_emote:peng_emote_$it")
        }
}

internal object CwVarBits : VarBitReferences() {
    /** The quest varbit (bits 0-7 of `peng_var`). */
    val quest = find("peng_quest")
    val multi_hide = find("peng_multi_hide")
    val multi_larry = find("peng_multi_larry")
    val multi_kgp = find("peng_multi_kgp")
    val emote_1 = find("peng_emote_1")
    val emote_2 = find("peng_emote_2")
    val emote_3 = find("peng_emote_3")
    val emote_check = find("peng_emote_check")
    val transmog = find("peng_transmog")
    val farmer = find("peng_farmer")
    /** Ours: an Ice Lord killed while caught (a spare cache bit of `peng_var2`). */
    val icelord_killed = find("peng_chat_check")
    /** Ours: the first agility lap done (a spare cache bit of `peng_var2`). */
    val first_lap = find("peng_pong_chat")
}

internal object CwTimers : TimerReferences() {
    /** Custom timer (65525): Larry's spell breaks away from his areas. */
    val suit = find("cold_war_suit")
}
