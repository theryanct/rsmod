package org.rsmod.content.interfaces.bank.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.events.ToolBeltOpenRequest
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.content.interfaces.bank.configs.BankAnywhereComponents
import org.rsmod.content.interfaces.bank.openBank
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * "Bank anywhere" feature (default ON): right-click "Bank" on the inventory tab button (added to
 * the cache by [org.rsmod.content.interfaces.bank.configs.BankAnywhereEdits]) or `::bank` opens
 * the bank wherever the player is. Op3 "Sort" on the same button sorts the inventory; op4
 * "Tool belt" opens the tool belt.
 */
class BankAnywhereScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val sortScript: BankSortScript,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    override fun ScriptContext.startup() {
        // No if_setevents needed: BankAnywhereEdits sets op2's transmit flag in the cache, which is
        // what both the client and the server check for static (comsub -1) component ops.
        for (stone in BankAnywhereComponents.inventory_stones) {
            onIfOverlayButton(stone) {
                when (op) {
                    IfButtonOp.Op2 -> player.bankAnywhere()
                    IfButtonOp.Op3 -> player.sortInventory()
                    IfButtonOp.Op4 -> eventBus.publish(ToolBeltOpenRequest(player))
                    else -> Unit
                }
            }
        }

        onCommand("bank") {
            modLevel = modlevels.admin
            desc = "Open your bank (Bank anywhere feature)"
            cheat { player.bankAnywhere() }
        }
    }

    private fun Player.sortInventory() {
        protectedAccess.launch(this, busyText = "You're busy right now.") {
            with(sortScript) { sortInv() }
        }
    }

    private fun Player.bankAnywhere() {
        if (!isFeatureOn(Feature.BankAnywhere)) {
            mes("Bank anywhere is turned off (::features).")
            return
        }
        if (isBusy) {
            mes("You're busy right now.")
            return
        }
        openBank(eventBus)
    }
}
