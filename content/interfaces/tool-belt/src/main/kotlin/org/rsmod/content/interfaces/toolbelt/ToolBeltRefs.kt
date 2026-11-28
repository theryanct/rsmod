package org.rsmod.content.interfaces.toolbelt

import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.obj.ObjReferences

/**
 * The belt reuses rev 243's tackle box window (its onload draws a grid from inv `tackle_box` and
 * binds Fill/Empty), retitled with steelborder, plus an otherwise unused plain inventory side
 * panel (`tournament_supplies_side`, the same shape as `shopside`) drawn by interface_inv_init.
 */
internal object ToolBeltInterfaces : InterfaceReferences() {
    val main = find("tackle_box_main")
    val side = find("tournament_supplies_side")
}

internal object ToolBeltComponents : ComponentReferences() {
    val frame = find("tackle_box_main:frame")
    val items = find("tackle_box_main:items")
    val fill = find("tackle_box_main:fill")
    val empty = find("tackle_box_main:empty")
    val side_items = find("tournament_supplies_side:items")
}

internal object ToolBeltInvs : InvReferences() {
    /** The client inv the tackle box window draws; the belt is sent under this id. */
    val display = find("tackle_box")
}

/** Materials that get a tool op so a belted tool can be used on them (no click on the tool). */
internal object ToolMaterialObjs : ObjReferences() {
    val logs =
        listOf(
            find("logs"),
            find("achey_tree_logs"),
            find("oak_logs"),
            find("willow_logs"),
            find("teak_logs"),
            find("maple_logs"),
            find("mahogany_logs"),
            find("yew_logs"),
            find("magic_logs"),
            find("redwood_logs"),
        )
    val arctic_pine_log = find("arctic_pine_log")
    val uncut_gems =
        listOf(
            find("uncut_opal"),
            find("uncut_jade"),
            find("uncut_red_topaz"),
            find("uncut_sapphire"),
            find("uncut_emerald"),
            find("uncut_ruby"),
            find("uncut_diamond"),
            find("uncut_dragonstone"),
            find("uncut_onyx"),
            find("uncut_zenyte"),
        )
    val leathers =
        listOf(
            find("leather"),
            find("hard_leather"),
            find("dragon_leather"),
            find("dragon_leather_blue"),
            find("dragon_leather_red"),
            find("dragon_leather_black"),
            find("village_snake_skin"),
        )
    val molten_glass = find("molten_glass")
    val grind_sources =
        listOf(find("unicorn_horn"), find("blue_dragon_scale"), find("desert_goat_horn"))
}

/**
 * Tool ops on materials (Ryan, 2026-11-28): the skills bind them by label (`onOpHeldLabel`), so a
 * tool carried OR on the belt works. Every edited obj had no inventory ops in the 243 cache: an
 * iop edit replaces the whole iop array, so only edit objs whose ops are all listed here, and keep
 * every edit to one obj in this one editor. Edited objs must not have hashed `find` refs.
 */
internal object ToolMaterialEdits : ObjEditor() {
    init {
        for (log in ToolMaterialObjs.logs) {
            edit(log) {
                iop2 = "Fletch"
                iop3 = "Light"
            }
        }
        edit(ToolMaterialObjs.arctic_pine_log) { iop3 = "Light" }
        for (gem in ToolMaterialObjs.uncut_gems) {
            edit(gem) { iop2 = "Cut" }
        }
        for (leather in ToolMaterialObjs.leathers) {
            edit(leather) { iop2 = "Craft" }
        }
        edit(ToolMaterialObjs.molten_glass) { iop2 = "Blow" }
        for (source in ToolMaterialObjs.grind_sources) {
            edit(source) { iop2 = "Grind" }
        }
    }
}
