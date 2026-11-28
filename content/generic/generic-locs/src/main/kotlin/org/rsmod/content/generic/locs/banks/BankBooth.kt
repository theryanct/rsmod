package org.rsmod.content.generic.locs.banks

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.content
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class BankBooth @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onOpLoc2(content.bank_booth) { openBank() }
        bindBanksByOp()
    }

    /**
     * Only the two `aide_bankbooth` types carry the `bank_booth` content group, so booths elsewhere
     * (Varrock, Falador, Edgeville, ...) never opened. Bind every loc with a "Bank" op, and the
     * "Use" op of bank chests, by label. Ops already bound by another script are left alone.
     */
    private fun ScriptContext.bindBanksByOp() {
        var booths = 0
        var chests = 0
        for (type in locTypes.values) {
            if (type.isContentType(content.bank_booth)) {
                continue
            }
            val ops = type.op.map { it?.trim()?.lowercase() }
            val bank = ops.indexOf("bank") + 1
            if (bank > 0) {
                if (bindLocOp(type, bank)) booths++
                continue
            }
            val use = ops.indexOf("use") + 1
            if (use > 0 && type.name.contains("bank chest", ignoreCase = true)) {
                if (bindLocOp(type, use)) chests++
            }
        }
        logger.info { "BankBooth: bound $booths booth and $chests bank chest loc types." }
    }

    private fun ScriptContext.bindLocOp(type: UnpackedLocType, op: Int): Boolean {
        val bound =
            when (op) {
                1 -> LocEvents.Op1::class.java
                2 -> LocEvents.Op2::class.java
                3 -> LocEvents.Op3::class.java
                4 -> LocEvents.Op4::class.java
                5 -> LocEvents.Op5::class.java
                else -> return false
            }
        if (eventBus.contains(bound, type.id)) {
            return false
        }
        when (op) {
            1 -> onOpLoc1(type) { openBank() }
            2 -> onOpLoc2(type) { openBank() }
            3 -> onOpLoc3(type) { openBank() }
            4 -> onOpLoc4(type) { openBank() }
            5 -> onOpLoc5(type) { openBank() }
        }
        return true
    }

    private fun ProtectedAccess.openBank() {
        ifOpenMainSidePair(main = interfaces.bank_main, side = interfaces.bank_side)
    }
}
