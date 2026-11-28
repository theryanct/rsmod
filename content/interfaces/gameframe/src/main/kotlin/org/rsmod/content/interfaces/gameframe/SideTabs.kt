package org.rsmod.content.interfaces.gameframe

import org.rsmod.api.player.ui.ifSetObj
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.interf.InterfaceType
import org.rsmod.game.type.obj.ObjType

/**
 * Our own side panels in place of unused social tabs (Ryan, 2026-11-28). [StandardOverlays]
 * opens each panel on its side target instead of the original interface; [send] puts the panel's
 * icon on the tab stone of the current toplevel, after every `if_opentop` (login and gameframe
 * changes), since each toplevel has its own `iconN` graphic.
 *
 * - side9 (Friends) -> the tool belt (`interfaces.tool_belt_side`, drawn by
 *   content/interfaces/tool-belt).
 *
 * The icon is an obj sprite on the stone's graphic component (IF_SETOBJECT); the stone's tooltip
 * still reads the original tab's name (client-side).
 */
internal object SideTabs {
    private class Tab(val icons: List<ComponentType>, val icon: ObjType)

    private val tabs = listOf(Tab(SideTabComponents.icon9, SideTabObjs.tool_belt_icon))

    fun send(player: Player, topLevel: InterfaceType) {
        for (tab in tabs) {
            val icon = tab.icons.firstOrNull { it.packed ushr 16 == topLevel.id } ?: continue
            player.ifSetObj(icon, tab.icon, 1)
        }
    }
}

internal object SideTabComponents : ComponentReferences() {
    /** side9's stone icon in each toplevel (fixed, resizable, resizable side-panels). */
    val icon9 =
        listOf(
            find("toplevel:icon9"),
            find("toplevel_osrs_stretch:icon9"),
            find("toplevel_pre_eoc:icon9"),
        )
}

internal object SideTabObjs : ObjReferences() {
    val tool_belt_icon = find("castlewars_toolkit")
}
