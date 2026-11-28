package org.rsmod.content.skills.smithing.configs

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.enums.EnumReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.obj.ObjType

internal typealias smithing_objs = SmithingObjs

internal typealias smithing_components = SmithingComponents

internal object SmithingObjs : ObjReferences() {
    val hammer = find("hammer")

    val copper_ore = find("copper_ore")
    val tin_ore = find("tin_ore")
    val iron_ore = find("iron_ore")
    val coal = find("coal")
    val blurite_ore = find("blurite_ore")
    val silver_ore = find("silver_ore")
    val gold_ore = find("gold_ore")
    val mithril_ore = find("mithril_ore")
    val adamantite_ore = find("adamantite_ore")
    val runite_ore = find("runite_ore")

    val bronze_bar = find("bronze_bar")
    val iron_bar = find("iron_bar")
    val steel_bar = find("steel_bar")
    val blurite_bar = find("blurite_bar")
    val silver_bar = find("silver_bar")
    val gold_bar = find("gold_bar")
    val mithril_bar = find("mithril_bar")
    val adamantite_bar = find("adamantite_bar")
    val runite_bar = find("runite_bar")

    val bronze_dagger = find("bronze_dagger")
    val bronze_sword = find("bronze_sword")
    val bronze_scimitar = find("bronze_scimitar")
    val bronze_longsword = find("bronze_longsword")
    val bronze_2h_sword = find("bronze_2h_sword")
    val bronze_axe = find("bronze_axe")
    val bronze_mace = find("bronze_mace")
    val bronze_warhammer = find("bronze_warhammer")
    val bronze_battleaxe = find("bronze_battleaxe")
    val bronze_chainbody = find("bronze_chainbody")
    val bronze_platelegs = find("bronze_platelegs")
    val bronze_plateskirt = find("bronze_plateskirt")
    val bronze_platebody = find("bronze_platebody")
    val bronze_med_helm = find("bronze_med_helm")
    val bronze_full_helm = find("bronze_full_helm")
    val bronze_sq_shield = find("bronze_sq_shield")
    val bronze_kiteshield = find("bronze_kiteshield")

    val iron_dagger = find("iron_dagger")
    val iron_sword = find("iron_sword")
    val iron_scimitar = find("iron_scimitar")
    val iron_longsword = find("iron_longsword")
    val iron_2h_sword = find("iron_2h_sword")
    val iron_axe = find("iron_axe")
    val iron_mace = find("iron_mace")
    val iron_warhammer = find("iron_warhammer")
    val iron_battleaxe = find("iron_battleaxe")
    val iron_chainbody = find("iron_chainbody")
    val iron_platelegs = find("iron_platelegs")
    val iron_plateskirt = find("iron_plateskirt")
    val iron_platebody = find("iron_platebody")
    val iron_med_helm = find("iron_med_helm")
    val iron_full_helm = find("iron_full_helm")
    val iron_sq_shield = find("iron_sq_shield")
    val iron_kiteshield = find("iron_kiteshield")

    val steel_dagger = find("steel_dagger")
    val steel_sword = find("steel_sword")
    val steel_scimitar = find("steel_scimitar")
    val steel_longsword = find("steel_longsword")
    val steel_2h_sword = find("steel_2h_sword")
    val steel_axe = find("steel_axe")
    val steel_mace = find("steel_mace")
    val steel_warhammer = find("steel_warhammer")
    val steel_battleaxe = find("steel_battleaxe")
    val steel_chainbody = find("steel_chainbody")
    val steel_platelegs = find("steel_platelegs")
    val steel_plateskirt = find("steel_plateskirt")
    val steel_platebody = find("steel_platebody")
    val steel_med_helm = find("steel_med_helm")
    val steel_full_helm = find("steel_full_helm")
    val steel_sq_shield = find("steel_sq_shield")
    val steel_kiteshield = find("steel_kiteshield")
}

internal object SmithingInterfaces : InterfaceReferences() {
    val smithing = find("smithing")
}

internal object SmithingComponents : ComponentReferences() {
    val make_1 = find("smithing:make_1")
    val make_5 = find("smithing:make_5")
    val make_10 = find("smithing:make_10")
    val make_x = find("smithing:make_x")
    val make_all = find("smithing:make_all")

    val dagger = find("smithing:dagger")
    val sword = find("smithing:sword")
    val scimitar = find("smithing:scimitar")
    val longsword = find("smithing:longsword")
    val twohand = find("smithing:2h")
    val axe = find("smithing:axe")
    val mace = find("smithing:mace")
    val warhammer = find("smithing:warhammer")
    val battleaxe = find("smithing:battleaxe")
    val chainbody = find("smithing:chainbody")
    val platelegs = find("smithing:platelegs")
    val plateskirt = find("smithing:plateskirt")
    val platebody = find("smithing:platebody")
    val medhelm = find("smithing:medhelm")
    val fullhelm = find("smithing:fullhelm")
    val squareshield = find("smithing:squareshield")
    val kiteshield = find("smithing:kiteshield")
}

internal object SmithingVarps : VarpReferences() {
    /** Make-X quantity shared by the skillmain buttons (1, 5, 10, 28 = all, or a custom X). */
    val makex = find("makexcrafting")
}

internal object SmithingVarBits : VarBitReferences() {
    /** Index into [SmithingEnums.bar_types] for the bar the smithing interface shows. */
    val bar_type = find("smithing_bar_type")
}

internal object SmithingEnums : EnumReferences() {
    val quantity = find<ObjType, Int>("smithing_product_to_quantity")
    val bars_required = find<ObjType, Int>("smithing_product_to_bars_required")
    val level_required = find<ObjType, Int>("smithing_product_to_requirement")
    val bar_types = find<Int, ObjType>("smithing_bar_types")
}

internal object SmithingSeqs : SeqReferences() {
    val smithing = find("human_smithing")
    val furnace = find("human_furnace")
}

internal object SmithingQueues : QueueReferences() {
    val smelting = find("smelting")
    val smithing = find("smithing")
}
