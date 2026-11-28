package org.rsmod.content.skills.magic.utility

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.obj.ObjReferences
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
}

internal object MagicUtilitySeqs : SeqReferences() {
    val teleport = find("human_castteleport")
    val low_alchemy = find("human_castlowlvlalchemy")
    val high_alchemy = find("human_casthighlvlalchemy")
    val superheat = find("human_castsuperheatitem")
    val bones_bananas = find("human_castbonestobananas")
    val enchant_1 = find("human_enchantamuletlvl1")
    val enchant_2 = find("human_enchantamuletlvl2")
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
