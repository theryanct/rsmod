package org.rsmod.api.script

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList

/**
 * Binds held obj [obj]'s inventory op labelled [label] (e.g. "Fletch", added by an ObjEditor iop
 * edit) unless another script already handles that op slot. Returns `false` when the obj has no
 * such op or the slot is taken, so callers can share materials without colliding.
 */
public fun ScriptContext.onOpHeldLabel(
    objTypes: ObjTypeList,
    obj: ObjType,
    label: String,
    action: suspend ProtectedAccess.() -> Unit,
): Boolean {
    val op = objTypes[obj].iop.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
    if (op !in 1..5 || isHeldOpBound(obj, op)) {
        return false
    }
    when (op) {
        1 -> onOpHeld1(obj) { action() }
        2 -> onOpHeld2(obj) { action() }
        3 -> onOpHeld3(obj) { action() }
        4 -> onOpHeld4(obj) { action() }
        else -> onOpHeld5(obj) { action() }
    }
    return true
}
