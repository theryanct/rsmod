package org.rsmod.content.other.windmill

import jakarta.inject.Inject
import org.rsmod.api.player.output.GameplayLog
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onApLoc1
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Windmills (OSRS Wiki "Hopper", "Hopper controls", "Flour bin"): grain goes in the hopper on the
 * top floor ("Fill", or use grain on it), the hopper controls ("Operate") send it down to the flour
 * bin, and the bin ("Empty", or use a pot on it) fills empty pots. The bin holds up to 30 pots and
 * is shared between windmills: cache varbit `mill_flour` (the count) and `mill_showflour` (the
 * `millbase` multiloc shows flour while it is set). Whether the hopper holds grain is server varp
 * `mill_hopper` (the cache keeps no var for it).
 *
 * Lines are authored (the Wiki has no transcript for the mill); OSRS's own wording may differ.
 */
class FlourMillScript @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for (hopper in MillLocs.hoppers) {
            bindOp1(hopper) { fillHopper() }
            onOpLocU(hopper, MillObjs.grain) { fillHopper() }
        }
        for (controls in MillLocs.controls) bindOp1(controls) { operate() }
        for (bin in MillLocs.bins) {
            bindOp1(bin) { emptyBin() }
            onOpLocU(bin, MillObjs.pot_empty) { emptyBin() }
        }
    }

    /**
     * Binds op1 only when the loc has one (each type's first op is Fill / Operate / Empty), from
     * up to [REACH] tiles away too: the Lumbridge hopper controls sit where the pathfinder can't
     * reach an adjacent tile ("I can't reach that"), so the op also runs from approach range.
     */
    private fun ScriptContext.bindOp1(type: LocType, action: suspend ProtectedAccess.() -> Unit) {
        if (locTypes[type].op.getOrNull(0).isNullOrEmpty()) return
        onOpLoc1(type) { action() }
        onApLoc1(type) { if (isWithinApRange(it.loc, REACH)) action() }
    }

    private val ProtectedAccess.flour: Int
        get() = player.vars[MillVarBits.flour]

    private fun ProtectedAccess.setFlour(count: Int) {
        VarPlayerIntMapSetter.set(player, MillVarBits.flour, count)
        VarPlayerIntMapSetter.set(player, MillVarBits.show_flour, if (count > 0) 1 else 0)
    }

    private val ProtectedAccess.hopperLoaded: Boolean
        get() = player.vars[MillVarps.hopper] != 0

    private fun ProtectedAccess.trace(step: String) {
        GameplayLog.input(player) { "mill $step hopper=${player.vars[MillVarps.hopper]} flour=$flour" }
    }

    private suspend fun ProtectedAccess.fillHopper() {
        trace("fill")
        if (hopperLoaded) {
            mes("There is already grain in the hopper.")
            return
        }
        if (invTotal(inv, MillObjs.grain) == 0) {
            mes("You need some grain to put in the hopper.")
            return
        }
        anim(MillSeqs.fill)
        delay(1)
        invDel(inv, MillObjs.grain)
        VarPlayerIntMapSetter.set(player, MillVarps.hopper, 1)
        mes("You put the grain in the hopper.")
    }

    private suspend fun ProtectedAccess.operate() {
        trace("operate")
        anim(MillSeqs.operate)
        delay(1)
        if (!hopperLoaded) {
            mes("You operate the empty hopper. Nothing interesting happens.")
            return
        }
        if (flour >= MAX_FLOUR) {
            mes("The flour bin downstairs is full, I should empty it first.")
            return
        }
        VarPlayerIntMapSetter.set(player, MillVarps.hopper, 0)
        setFlour(flour + 1)
        mes("You operate the hopper. The grain slides down the chute.")
    }

    private fun ProtectedAccess.emptyBin() {
        trace("empty")
        if (flour <= 0) {
            mes("The flour bin is already empty. You need to place wheat in the hopper upstairs first.")
            return
        }
        if (invTotal(inv, MillObjs.pot_empty) == 0) {
            mes("I need an empty pot to hold the flour in.")
            return
        }
        invReplace(inv, MillObjs.pot_empty, 1, MillObjs.pot_flour)
        setFlour(flour - 1)
        mes(if (flour == 0) "You fill a pot with the last of the flour in the bin." else "You fill a pot with flour from the bin.")
    }

    private companion object {
        const val MAX_FLOUR = 30
        const val REACH = 2
    }
}

internal object MillLocs : LocReferences() {
    val hoppers: List<LocType> =
        listOf(
            find("hopper"),
            find("hopper1"),
            find("hopper3"),
            find("hopper4"),
            find("fai_varrock_windmill_hopper"),
            find("prif_gwenith_windmill_hopper"),
            find("fortis_windmill_hopper"),
        )
    val controls: List<LocType> =
        listOf(
            find("hopperlevers1"),
            find("hopperlevers3"),
            find("hopperlevers4"),
            find("hopperlevers6"),
            find("fai_varrock_windmill_levers"),
            find("prif_gwenith_windmill_levers"),
            find("fortis_windmill_levers"),
            find("aldarin_windmill_levers"),
        )
    /** Visible flour-bin children (ops key on the child shown, not the `millbase` multiloc). */
    val bins: List<LocType> =
        listOf(
            find("millbase_flour"),
            find("millbase_empty"),
            find("fai_varrock_windmill_base_flour"),
            find("prif_gwenith_windmill_base_flour"),
            find("fortis_windmill_base_flour"),
        )
}

internal object MillObjs : ObjReferences() {
    val grain = find("grain")
    val pot_empty = find("pot_empty")
    val pot_flour = find("pot_flour")
}

internal object MillVarBits : VarBitReferences() {
    val flour = find("mill_flour")
    val show_flour = find("mill_showflour")
}

internal object MillVarps : VarpReferences() {
    /** Server varp (65800): 1 while the hopper holds grain. */
    val hopper = find("mill_hopper")
}

internal object MillVarpBuilds : VarpBuilder() {
    init {
        build("mill_hopper")
    }
}

internal object MillSeqs : org.rsmod.api.type.refs.seq.SeqReferences() {
    val fill = find("human_pickupfloor")
    val operate = find("human_leverdown")
}
