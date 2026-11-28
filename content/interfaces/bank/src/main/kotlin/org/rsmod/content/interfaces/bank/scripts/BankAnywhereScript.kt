package org.rsmod.content.interfaces.bank.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.advanced.onIfMoveSub
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.interfaces.bank.configs.BankAnywhereComponents
import org.rsmod.content.interfaces.bank.openBank
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * "Bank anywhere" feature (default ON): right-click "Bank" on the inventory tab button (added to
 * the cache by [org.rsmod.content.interfaces.bank.configs.BankAnywhereEdits]) or `::bank` opens
 * the bank wherever the player is.
 */
class BankAnywhereScript @Inject constructor(private val eventBus: EventBus) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { player.enableStoneOps() }
        // The gameframe re-sends the stone's events (op1 only) when it moves the inventory panel.
        onIfMoveSub(components.toplevel_target_side3) { player.enableStoneOps() }

        for (stone in BankAnywhereComponents.inventory_stones) {
            onIfOverlayButton(stone) {
                if (op == IfButtonOp.Op2) {
                    player.bankAnywhere()
                }
            }
        }

        onCommand("bank") {
            modLevel = modlevels.admin
            desc = "Open your bank (Bank anywhere feature)"
            cheat { player.bankAnywhere() }
        }
    }

    private fun Player.enableStoneOps() {
        for (stone in BankAnywhereComponents.inventory_stones) {
            ifSetEvents(stone, -1..-1, IfEvent.Op1, IfEvent.Op2)
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
