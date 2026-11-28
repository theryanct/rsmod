package org.rsmod.content.skills.runecraft

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseRunecraftingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varp.VarpType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Essence pouches (OSRS Wiki): Fill / Empty / Check. Small 3 (lvl 1), medium 6 (25), large 9 (50),
 * giant 12 (75); colossal (25) holds 8/16/27/40 at base Runecraft 25/50/75/85. A pouch holds one
 * essence type at a time (rune, pure, daeyalt or guardian). Contents are saved in custom varps
 * 65535-65539 as `count | type << 8`. Not yet: degradation and repair.
 */
class EssencePouches @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    private class Pouch(val obj: ObjType, val varp: VarpType, val level: Int, val name: String) {
        fun capacity(rcLevel: Int): Int =
            when (name) {
                "small" -> 3
                "medium" -> 6
                "large" -> 9
                "giant" -> 12
                else ->
                    when {
                        rcLevel >= 85 -> 40
                        rcLevel >= 75 -> 27
                        rcLevel >= 50 -> 16
                        else -> 8
                    }
            }
    }

    private val pouches by lazy {
        listOf(
            Pouch(PouchObjs.small, PouchVarps.small, 1, "small"),
            Pouch(PouchObjs.medium, PouchVarps.medium, 25, "medium"),
            Pouch(PouchObjs.large, PouchVarps.large, 50, "large"),
            Pouch(PouchObjs.giant, PouchVarps.giant, 75, "giant"),
            Pouch(PouchObjs.colossal, PouchVarps.colossal, 25, "colossal"),
        )
    }

    override fun ScriptContext.startup() {
        for (pouch in pouches) {
            bind(pouch.obj, "fill") { fill(pouch) }
            bind(pouch.obj, "empty") { empty(pouch) }
            bind(pouch.obj, "check") { check(pouch) }
        }
    }

    private fun ScriptContext.bind(obj: ObjType, label: String, action: suspend ProtectedAccess.() -> Unit) {
        val op = objTypes[obj].iop.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op <= 0 || isHeldOpBound(obj, op)) return
        when (op) {
            1 -> onOpHeld1(obj) { action() }
            2 -> onOpHeld2(obj) { action() }
            3 -> onOpHeld3(obj) { action() }
            4 -> onOpHeld4(obj) { action() }
            5 -> onOpHeld5(obj) { action() }
        }
    }

    private fun Player.contents(pouch: Pouch): Pair<Int, Int> {
        val raw = vars[pouch.varp]
        return (raw and 0xFF) to (raw ushr 8)
    }

    private fun Player.store(pouch: Pouch, count: Int, type: Int) {
        VarPlayerIntMapSetter.set(this, pouch.varp, if (count <= 0) 0 else count or (type shl 8))
    }

    private fun ProtectedAccess.fill(pouch: Pouch) {
        val rc = player.baseRunecraftingLvl
        if (rc < pouch.level) {
            mes("You need a Runecraft level of ${pouch.level} to use this pouch.")
            return
        }
        val capacity = pouch.capacity(rc)
        val (held, heldType) = player.contents(pouch)
        val space = capacity - held
        if (space <= 0) {
            mes("Your pouch is full.")
            return
        }
        // Keep filling with the stored type; an empty pouch takes the first type carried.
        val index =
            if (held > 0) heldType - 1
            else PouchObjs.essences.indexOfFirst { invTotal(inv, it) > 0 }
        val essence = PouchObjs.essences.getOrNull(index)
        if (essence == null || invTotal(inv, essence) <= 0) {
            mes(if (held > 0) "You can only store one type of essence in each pouch." else "You don't have any essence to put in the pouch.")
            return
        }
        val moved = minOf(space, invTotal(inv, essence))
        invDel(inv, essence, moved)
        player.store(pouch, held + moved, index + 1)
    }

    private fun ProtectedAccess.empty(pouch: Pouch) {
        val (held, type) = player.contents(pouch)
        if (held <= 0) {
            mes("There is no essence in this pouch.")
            return
        }
        val essence = PouchObjs.essences.getOrNull(type - 1) ?: return
        val moved = minOf(held, inv.freeSpace())
        if (moved <= 0) {
            mes("You don't have enough inventory space.")
            return
        }
        invAdd(inv, essence, moved)
        player.store(pouch, held - moved, type)
    }

    private fun ProtectedAccess.check(pouch: Pouch) {
        val (held, type) = player.contents(pouch)
        if (held <= 0) {
            mes("There is no essence in this pouch.")
            return
        }
        val name = objTypes[PouchObjs.essences[type - 1]].name.lowercase()
        mes("There ${if (held == 1) "is" else "are"} $held $name in this pouch.")
    }
}

internal object PouchObjs : ObjReferences() {
    val small = find("rcu_pouch_small")
    val medium = find("rcu_pouch_medium")
    val large = find("rcu_pouch_large")
    val giant = find("rcu_pouch_giant")
    val colossal = find("rcu_pouch_colossal")

    /** Stored type ids are index + 1 (saved: append only). */
    val essences: List<ObjType> =
        listOf(find("blankrune"), find("blankrune_high"), find("blankrune_daeyalt"), find("gotr_guardian_essence"))
}

/** Skilling symbol range (65535-65539). Saved per character. */
object PouchVarpBuilds : VarpBuilder() {
    init {
        build("rc_pouch_small")
        build("rc_pouch_medium")
        build("rc_pouch_large")
        build("rc_pouch_giant")
        build("rc_pouch_colossal")
    }
}

internal object PouchVarps : VarpReferences() {
    val small = find("rc_pouch_small")
    val medium = find("rc_pouch_medium")
    val large = find("rc_pouch_large")
    val giant = find("rc_pouch_giant")
    val colossal = find("rc_pouch_colossal")
}
