package org.rsmod.content.skills.cooking.scripts

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeldU
import org.rsmod.content.skills.cooking.configs.cooking_objs
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Pot of flour + water (bucket or jug) -> bread dough. OSRS shows a dough-type menu (bread,
 * pastry, pizza base, pitta); only bread dough exists in-game here so far, so it is made directly.
 */
class Dough : PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeldU(cooking_objs.pot_flour, cooking_objs.bucket_water) {
            makeBreadDough(cooking_objs.bucket_water, cooking_objs.bucket_empty)
        }
        onOpHeldU(cooking_objs.pot_flour, cooking_objs.jug_water) {
            makeBreadDough(cooking_objs.jug_water, cooking_objs.jug_empty)
        }
    }

    private fun ProtectedAccess.makeBreadDough(water: ObjType, emptyWater: ObjType) {
        // Two items in, three out (dough, empty pot, empty container).
        if (inv.freeSpace() < 1) {
            mes("You don't have enough inventory space to do that.")
            return
        }
        if (invDel(inv, cooking_objs.pot_flour, count = 1).failure) {
            return
        }
        if (invDel(inv, water, count = 1).failure) {
            invAdd(inv, cooking_objs.pot_flour)
            return
        }
        invAdd(inv, cooking_objs.bread_dough)
        invAdd(inv, cooking_objs.pot_empty)
        invAdd(inv, emptyWater)
        mes("You mix the water and flour to make some bread dough.")
    }
}
