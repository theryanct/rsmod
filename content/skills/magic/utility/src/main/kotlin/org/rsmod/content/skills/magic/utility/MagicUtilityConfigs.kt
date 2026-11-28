package org.rsmod.content.skills.magic.utility

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.synth.SynthReferences

internal object MagicUtilityComponents : ComponentReferences() {
    val varrock_teleport = find("magic_spellbook:varrock_teleport")
    val lumbridge_teleport = find("magic_spellbook:lumbridge_teleport")
    val falador_teleport = find("magic_spellbook:falador_teleport")
    val camelot_teleport = find("magic_spellbook:camelot_teleport")
    val ardougne_teleport = find("magic_spellbook:ardougne_teleport")
    val watchtower_teleport = find("magic_spellbook:watchtower_teleport")
    val low_alchemy = find("magic_spellbook:low_alchemy")
    val high_alchemy = find("magic_spellbook:high_alchemy")
    val superheat = find("magic_spellbook:superheat")
    val bones_bananas = find("magic_spellbook:bones_bananas")
    val enchant_1 = find("magic_spellbook:enchant_1")
    val enchant_2 = find("magic_spellbook:enchant_2")
    val enchant_3 = find("magic_spellbook:enchant_3")
    val enchant_4 = find("magic_spellbook:enchant_4")
    val enchant_5 = find("magic_spellbook:enchant_5")
    val enchant_6 = find("magic_spellbook:enchant_6")
    val enchant_7 = find("magic_spellbook:enchant_7")
    val telegrab = find("magic_spellbook:telegrab")
}

/** Spell objs (cache spell definitions: level, runes, cast xp). */
internal object MagicUtilitySpells : ObjReferences() {
    val varrock_teleport = find("25_varrock_teleport")
    val lumbridge_teleport = find("31_lumbridge_teleport")
    val falador_teleport = find("37_falador_teleport")
    val camelot_teleport = find("45_camelot_teleport")
    val ardougne_teleport = find("51_ardougne_teleport")
    val watchtower_teleport = find("58_watchtower_teleport")
    val low_alchemy = find("21_low_alchemy")
    val high_alchemy = find("55_high_alchemy")
    val superheat = find("43_superheat_item")
    val bones_bananas = find("15_bones_bananas")
    val enchant_1 = find("07_enchant_amulet_lvl1")
    val enchant_2 = find("27_enchant_amulet_lvl2")
    val enchant_3 = find("49_enchant_amulet_lvl3")
    val enchant_4 = find("57_enchant_amulet_lvl4")
    val enchant_5 = find("68_enchant_amulet_lvl5")
    val enchant_6 = find("87_enchant_amulet_lvl6")
    val enchant_7 = find("93_enchant_amulet_lvl7")
    val telegrab = find("33_tele_grab")
}

internal object MagicUtilityObjs : ObjReferences() {
    val coins = find("coins")
    val bones = find("bones")
    val banana = find("banana")
    val coal = find("coal")

    val copper_ore = find("copper_ore")
    val tin_ore = find("tin_ore")
    val iron_ore = find("iron_ore")
    val silver_ore = find("silver_ore")
    val gold_ore = find("gold_ore")
    val mithril_ore = find("mithril_ore")
    val adamantite_ore = find("adamantite_ore")
    val runite_ore = find("runite_ore")
    val bronze_bar = find("bronze_bar")
    val iron_bar = find("iron_bar")
    val silver_bar = find("silver_bar")
    val steel_bar = find("steel_bar")
    val gold_bar = find("gold_bar")
    val mithril_bar = find("mithril_bar")
    val adamantite_bar = find("adamantite_bar")
    val runite_bar = find("runite_bar")

    val sapphire_ring = find("sapphire_ring")
    val ring_of_recoil = find("ring_of_recoil")
    val sapphire_necklace = find("sapphire_necklace")
    val games_necklace_8 = find("necklace_of_minigames_8")
    val sapphire_amulet = find("strung_sapphire_amulet")
    val amulet_of_magic = find("amulet_of_magic")
    val sapphire_bracelet = find("jewl_sapphire_bracelet")
    val bracelet_of_clay = find("jewl_bracelet_of_clay")
    val emerald_ring = find("emerald_ring")
    val ring_of_dueling_8 = find("ring_of_dueling_8")
    val emerald_necklace = find("emerald_necklace")
    val binding_necklace = find("magic_emerald_necklace")
    val emerald_amulet = find("strung_emerald_amulet")
    val amulet_of_defence = find("amulet_of_defence")
    val emerald_bracelet = find("jewl_emerald_bracelet")
    val castle_wars_bracelet_3 = find("jewl_castlewars_bracelet3")

    // Enchant 3-7 (OSRS Wiki "Enchant ... Jewellery"): unenchanted -> enchanted.
    val enchant_pairs: Map<Int, List<Pair<ObjType, ObjType>>> =
        mapOf(
            3 to
                listOf(
                    find("ruby_ring") to find("ring_of_forging"),
                    find("ruby_necklace") to find("necklace_of_digsite_5"),
                    find("strung_ruby_amulet") to find("amulet_of_strength"),
                    find("jewl_ruby_bracelet") to find("jewl_bracelet_of_innoculation"),
                ),
            4 to
                listOf(
                    find("diamond_ring") to find("ring_of_life"),
                    find("diamond_necklace") to find("jewl_necklace_of_phoenix"),
                    find("strung_diamond_amulet") to find("amulet_of_power"),
                    find("jewl_diamond_bracelet") to find("jewl_runerunning_bracelet_5"),
                ),
            5 to
                listOf(
                    find("dragonstone_ring") to find("ring_of_wealth"),
                    find("dragonstone_necklace") to find("jewl_necklace_of_skills"),
                    find("strung_dragonstone_amulet") to find("amulet_of_glory"),
                    find("jewl_dragonstone_bracelet") to find("jewl_bracelet_of_combat"),
                ),
            6 to
                listOf(
                    find("onyx_ring") to find("enchanted_onyx_ring"),
                    find("onyx_necklace") to find("jewl_beserker_necklace"),
                    find("strung_onyx_amulet") to find("enchanted_onyx_amulet"),
                    find("jewl_onyx_bracelet") to find("jewl_bracelet_regen"),
                ),
            7 to
                listOf(
                    find("zenyte_ring") to find("zenyte_ring_enchanted"),
                    find("zenyte_necklace") to find("zenyte_necklace_enchanted"),
                    find("zenyte_amulet") to find("zenyte_amulet_enchanted"),
                    find("zenyte_bracelet") to find("zenyte_bracelet_enchanted"),
                ),
        )
}

internal object MagicUtilitySeqs : SeqReferences() {
    val teleport = find("human_castteleport")
    val low_alchemy = find("human_castlowlvlalchemy")
    val high_alchemy = find("human_casthighlvlalchemy")
    val superheat = find("human_castsuperheatitem")
    val bones_bananas = find("human_castbonestobananas")
    val enchant_1 = find("human_enchantamuletlvl1")
    val enchant_2 = find("human_enchantamuletlvl2")
    val enchant_3 = find("human_enchantamuletlvl3")
    val enchant_4 = find("enchant_amulet2_lvl4")
    val enchant_5 = find("enchant_amulet2_lvl5")
    val enchant_6 = find("enchant_amulet2_lvl6")
    val telegrab = find("human_casttelegrab")
}

internal object MagicUtilitySpots : SpotanimReferences() {
    val teleport = find("teleport_casting")
    val low_alchemy = find("lowlvlalchemy_casting")
    val high_alchemy = find("highlvlalchemy_casting")
    val superheat = find("superheatitem_casting")
    val bones_bananas = find("bonestobananas_casting")
    val enchant_1 = find("enchant_amulet_lvl1")
    val enchant_2 = find("enchant_amulet_lvl2")
    val enchant_3 = find("enchant_amulet_lvl3")
    val enchant_4 = find("enchant_amulet2_lvl4")
    val enchant_5 = find("enchant_amulet2_lvl5")
    val enchant_6 = find("enchant_amulet2_lvl6")
    val telegrab_casting = find("telegrab_casting")
    val telegrab_travel = find("telegrab_travel")
    val telegrab_impact = find("telegrab_impact")
}

internal object MagicUtilitySynths : SynthReferences() {
    val teleport = find("teleport_all")
    val low_alchemy = find("low_alchemy")
    val high_alchemy = find("high_alchemy")
    val superheat = find("superheat_all")
    val telegrab = find("telegrab_all")
}
