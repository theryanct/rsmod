package org.rsmod.content.skills.smithing.configs

import org.rsmod.api.type.editors.loc.LocEditor
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.content.ContentReferences
import org.rsmod.api.type.refs.enums.EnumReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType

internal typealias smithing_content = SmithingContent

internal typealias smithing_objs = SmithingObjs

internal typealias smithing_components = SmithingComponents

internal object SmithingContent : ContentReferences() {
    val furnace = find("smithing_furnace")
    val anvil = find("smithing_anvil")
}

internal object SmithingLocs : LocReferences() {
    val furnace = find("furnace")
    val furnace2 = find("furnace2")
    val fai_falador_furnace = find("fai_falador_furnace")
    val varrock_diary_furnace = find("varrock_diary_furnace")
    val newbiefurnace = find("newbiefurnace")
    val tut2_furnace = find("tut2_furnace")

    val anvil = find("anvil")
}

/** Marks F2P furnaces and anvils so the smithing scripts can bind to them by content group. */
internal object SmithingStations : LocEditor() {
    init {
        furnace(SmithingLocs.furnace)
        furnace(SmithingLocs.furnace2)
        furnace(SmithingLocs.fai_falador_furnace)
        furnace(SmithingLocs.varrock_diary_furnace)
        furnace(SmithingLocs.newbiefurnace)
        furnace(SmithingLocs.tut2_furnace)

        edit(SmithingLocs.anvil) { contentGroup = smithing_content.anvil }
    }

    private fun furnace(type: LocType) {
        edit(type) { contentGroup = smithing_content.furnace }
    }
}

internal object SmithingObjs : ObjReferences() {
    val hammer = find("hammer")

    val copper_ore = find("copper_ore")
    val tin_ore = find("tin_ore")
    val iron_ore = find("iron_ore")
    val coal = find("coal")

    val bronze_bar = find("bronze_bar")
    val iron_bar = find("iron_bar")
    val steel_bar = find("steel_bar")

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
