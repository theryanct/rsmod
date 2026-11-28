package org.rsmod.api.script

import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.ScriptContext

/*
 * "Is this op already handled?" checks for scripts that bind ops in bulk from a cache scan or a
 * data table (skilling rocks, stalls, altars, ...). Bulk binders skip an op another script (e.g. a
 * quest) has already registered for that exact type, instead of throwing "already registered" at
 * boot. Only helps when the specific script registers first; specific scripts should guard too.
 */

/** True when loc [type]'s [op] (1-5) already has a handler. Out-of-range ops count as bound. */
public fun ScriptContext.isLocOpBound(type: LocType, op: Int): Boolean {
    val event =
        when (op) {
            1 -> LocEvents.Op1::class.java
            2 -> LocEvents.Op2::class.java
            3 -> LocEvents.Op3::class.java
            4 -> LocEvents.Op4::class.java
            5 -> LocEvents.Op5::class.java
            else -> return true
        }
    return eventBus.contains(event, type.id)
}

/** True when npc [type]'s [op] (1-5) already has a handler. Out-of-range ops count as bound. */
public fun ScriptContext.isNpcOpBound(type: NpcType, op: Int): Boolean {
    val event =
        when (op) {
            1 -> NpcEvents.Op1::class.java
            2 -> NpcEvents.Op2::class.java
            3 -> NpcEvents.Op3::class.java
            4 -> NpcEvents.Op4::class.java
            5 -> NpcEvents.Op5::class.java
            else -> return true
        }
    return eventBus.contains(event, type.id)
}

/** True when held obj [type]'s inventory [op] (1-5) already has a handler. */
public fun ScriptContext.isHeldOpBound(type: ObjType, op: Int): Boolean {
    val event =
        when (op) {
            1 -> HeldObjEvents.Op1::class.java
            2 -> HeldObjEvents.Op2::class.java
            3 -> HeldObjEvents.Op3::class.java
            4 -> HeldObjEvents.Op4::class.java
            5 -> HeldObjEvents.Op5::class.java
            else -> return true
        }
    return eventBus.contains(event, type.id)
}
