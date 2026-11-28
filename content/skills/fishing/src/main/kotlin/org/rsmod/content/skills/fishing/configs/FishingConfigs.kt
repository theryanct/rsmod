package org.rsmod.content.skills.fishing.configs

import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences

internal typealias fishing_objs = FishingObjs

internal typealias fishing_seqs = FishingSeqs

internal object FishingObjs : ObjReferences() {
    // Tools and consumables.
    val net = find("net")
    val big_net = find("big_net")
    val fishing_rod = find("fishing_rod")
    val fly_fishing_rod = find("fly_fishing_rod")
    val lobster_pot = find("lobster_pot")
    val harpoon = find("harpoon")
    val dragon_harpoon = find("dragon_harpoon")
    val infernal_harpoon = find("infernal_harpoon")
    val crystal_harpoon = find("crystal_harpoon")
    val fishing_bait = find("fishing_bait")
    val feather = find("feather")

    // Barbarian fishing (rod + feathers / fish offcuts / roe / caviar as bait).
    val barbarian_rod = find("brut_fishing_rod")
    val fish_offcuts = find("brut_fish_cuts")
    val roe = find("brut_roe")
    val caviar = find("brut_caviar")
    val leaping_trout = find("brut_spawning_trout")
    val leaping_salmon = find("brut_spawning_salmon")
    val leaping_sturgeon = find("brut_sturgeon")
    val knife = find("knife")

    // Karambwan fishing (a vessel loaded with karambwanji; raw karambwanji is the bait).
    val karambwan_vessel_loaded = find("tbwt_karambwan_vessel_loaded_with_karambwanji")
    val raw_karambwanji = find("tbwt_raw_karambwanji")
    val raw_karambwan = find("tbwt_raw_karambwan")

    // Catches.
    val raw_shrimps = find("raw_shrimp")
    val raw_anchovies = find("raw_anchovies")
    val raw_sardine = find("raw_sardine")
    val raw_herring = find("raw_herring")
    val raw_trout = find("raw_trout")
    val raw_salmon = find("raw_salmon")
    val raw_pike = find("raw_pike")
    val raw_tuna = find("raw_tuna")
    val raw_swordfish = find("raw_swordfish")
    val raw_lobster = find("raw_lobster")
    val raw_mackerel = find("raw_mackerel")
    val raw_cod = find("raw_cod")
    val raw_bass = find("raw_bass")
    val raw_shark = find("raw_shark")
    val raw_monkfish = find("raw_monkfish")

    // Special spots (eels, dark crab, anglerfish, frog spawn): tools, baits and catches.
    val oily_fishing_rod = find("oily_fishing_rod")
    val ice_gloves = find("ice_gloves")
    val smiths_gloves_i = find("smithing_uniform_gloves_ice")
    val sandworms = find("piscarilius_sandworms")
    val dark_fishing_bait = find("wilderness_fishing_bait")
    val frog_spawn = find("giant_frogspawn")
    val raw_slimy_eel = find("mort_slimey_eel")
    val raw_cave_eel = find("raw_cave_eel")
    val raw_lava_eel = find("raw_lava_eel")
    val infernal_eel = find("infernal_eel")
    val sacred_eel = find("snakeboss_eel")
    val raw_anglerfish = find("raw_anglerfish")
    val raw_dark_crab = find("raw_dark_crab")
}

internal object FishingSeqs : SeqReferences() {
    val human_smallnet = find("human_smallnet")
    val human_largenet = find("human_largenet")
    val human_fishing_casting = find("human_fishing_casting")
    val human_harpoon = find("human_harpoon")
    val human_lobster = find("human_lobster")
    val hand_fishing = find("brut_player_hand_fishing_start")
    val knife_use = find("brut_human_knifeuse")
}

/**
 * Barbarian Training progress (cache varbits in `brut_base_1`, which holds only these brut bits).
 * Lesson states: 0 untaught, 1 taught by Otto, 2 task done, 3 reported to Otto.
 */
internal object BarbarianVarBits : VarBitReferences() {
    /** Barehanded (harpoon) fishing lesson. */
    val fishing_s = find("brut_fishing_s")
    /** Barbarian rod fishing lesson. */
    val fishing_r = find("brut_fishing_r")
}
