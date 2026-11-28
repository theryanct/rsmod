package org.rsmod.content.interfaces.bank.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.invs
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.worn.WornUnequipOp
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.interfaces.bank.BankTab
import org.rsmod.content.interfaces.bank.bankCapacity
import org.rsmod.events.EventBus
import org.rsmod.game.inv.Inventory
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Bank deposit boxes (interface bank_depositbox, 192). Every loc named like a deposit box with a
 * "Deposit" op opens it (skipping locs another script already binds). Items go to the bank's main
 * tab, like the bank's own deposit-inventory button does.
 *
 * Inventory item ops follow the client's cs2 172: op1 = the selected quantity button (varbit
 * depositbox_mode: 1/5/10/X/All; X uses varp depositbox_requestedquantity), op2..op6 =
 * Deposit-1/5/10/X/All, op10 = Examine. Not done yet: depositing single worn slots and the
 * looting bag button.
 */
class DepositBoxScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val locTypes: LocTypeList,
    private val invTypes: InvTypeList,
    private val objTypes: ObjTypeList,
) : PluginScript() {
    private val ProtectedAccess.bank: Inventory
        get() = player.invMap.getOrPut(invTypes[invs.bank])

    override fun ScriptContext.startup() {
        var bound = 0
        for (type in locTypes.values) {
            if (!type.name.isDepositBoxName()) {
                continue
            }
            val op = type.op.indexOfFirst { it?.trim().equals("deposit", ignoreCase = true) } + 1
            if (op > 0 && bindLocOp(type, op)) {
                bound++
            }
        }
        logger.info { "DepositBox: bound $bound deposit box loc types." }

        onIfModalButton(DepositBoxComponents.inventory) { invOp(it.comsub, it.op) }
        onIfModalButton(DepositBoxComponents.deposit_inv) { depositAll(inv, worn = false) }
        onIfModalButton(DepositBoxComponents.deposit_worn) { depositAll(worn, worn = true) }
        onIfModalButton(DepositBoxComponents.quantity_1) { setMode(MODE_1) }
        onIfModalButton(DepositBoxComponents.quantity_5) { setMode(MODE_5) }
        onIfModalButton(DepositBoxComponents.quantity_10) { setMode(MODE_10) }
        onIfModalButton(DepositBoxComponents.quantity_all) { setMode(MODE_ALL) }
        onIfModalButton(DepositBoxComponents.quantity_x) {
            if (it.op == IfButtonOp.Op2) {
                val amount = countDialog()
                if (amount > 0) {
                    VarPlayerIntMapSetter.set(player, DepositBoxVarps.requested, amount)
                }
            }
            setMode(MODE_X)
        }
    }

    private fun String.isDepositBoxName(): Boolean {
        val name = lowercase()
        return name == "bank deposit box" || name == "deposit box" || name == "bank deposit chest"
    }

    private fun ScriptContext.bindLocOp(type: UnpackedLocType, op: Int): Boolean {
        val event =
            when (op) {
                1 -> LocEvents.Op1::class.java
                2 -> LocEvents.Op2::class.java
                3 -> LocEvents.Op3::class.java
                4 -> LocEvents.Op4::class.java
                5 -> LocEvents.Op5::class.java
                else -> return false
            }
        if (eventBus.contains(event, type.id)) {
            return false
        }
        when (op) {
            1 -> onOpLoc1(type) { open() }
            2 -> onOpLoc2(type) { open() }
            3 -> onOpLoc3(type) { open() }
            4 -> onOpLoc4(type) { open() }
            5 -> onOpLoc5(type) { open() }
        }
        return true
    }

    private fun ProtectedAccess.open() {
        ifOpenMainModal(DepositBoxInterfaces.box)
        ifSetEvents(
            DepositBoxComponents.inventory,
            0 until inv.size,
            IfEvent.Op1,
            IfEvent.Op2,
            IfEvent.Op3,
            IfEvent.Op4,
            IfEvent.Op5,
            IfEvent.Op6,
            IfEvent.Op10,
        )
    }

    private fun ProtectedAccess.setMode(mode: Int) {
        VarPlayerIntMapSetter.set(player, DepositBoxVarbits.mode, mode)
    }

    private suspend fun ProtectedAccess.invOp(slot: Int, op: IfButtonOp) {
        if (op == IfButtonOp.Op10) {
            objExamine(inv, slot)
            return
        }
        val count =
            when (op) {
                IfButtonOp.Op1 ->
                    when (vars[DepositBoxVarbits.mode]) {
                        MODE_5 -> 5
                        MODE_10 -> 10
                        MODE_X -> maxOf(1, vars[DepositBoxVarps.requested])
                        MODE_ALL -> Int.MAX_VALUE
                        else -> 1
                    }
                IfButtonOp.Op2 -> 1
                IfButtonOp.Op3 -> 5
                IfButtonOp.Op4 -> 10
                IfButtonOp.Op5 -> {
                    val amount = countDialog()
                    if (amount > 0) {
                        VarPlayerIntMapSetter.set(player, DepositBoxVarps.requested, amount)
                    }
                    amount
                }
                IfButtonOp.Op6 -> Int.MAX_VALUE
                else -> return
            }
        if (count > 0) {
            depositObj(slot, count)
        }
    }

    /** Deposits up to [count] of the obj in inventory [slot] (all its slots if unstackable). */
    private fun ProtectedAccess.depositObj(slot: Int, count: Int) {
        val obj = inv[slot] ?: return
        val type = objTypes[obj]
        if (type.param(params.no_bank) != 0) {
            mes("A magical force prevents you from banking this item!")
            return
        }
        val bankId = objTypes.uncert(type).id
        val placeholder = objTypes.placeholder(objTypes.uncert(type)).id
        val startLast = bank.lastOccupiedSlot()
        var left = count
        val slots = listOf(slot) + inv.indices.filter { it != slot && inv[it]?.id == obj.id }
        for (from in slots) {
            if (left <= 0) {
                break
            }
            val here = inv[from] ?: continue
            val existing = bank.indexOfFirst { it?.id == bankId }
            val held = bank.indexOfFirst { it?.id == placeholder }
            if (existing == -1 && held != -1) {
                bank[held] = null
            }
            val target = existing.takeIf { it != -1 } ?: held.takeIf { it != -1 }
            if (target == null && bank.occupiedSpace() >= bankCapacity) {
                mes("Your bank is too full to deposit any more items.")
                break
            }
            val amount = minOf(left, here.count)
            val result =
                invMoveFromSlot(
                    from = inv,
                    into = bank,
                    fromSlot = from,
                    count = amount,
                    intoSlot = target,
                    strict = false,
                    uncert = true,
                )
            if (result.failure) {
                break
            }
            left -= amount
        }
        growMainTab(startLast)
    }

    private fun ProtectedAccess.depositAll(from: Inventory, worn: Boolean) {
        if (from.isEmpty()) {
            mes("You have nothing to deposit.")
            return
        }
        val unbankable =
            from.indices
                .filter { from[it]?.let { obj -> objTypes[obj].param(params.no_bank) != 0 } == true }
                .toHashSet()
        val before = if (worn) Wearpos.entries.associateWith { from[it.slot] } else emptyMap()
        val startLast = bank.lastOccupiedSlot()
        val result =
            invMoveInv(
                from = from,
                into = bank,
                untransform = true,
                intoStartSlot = BankTab.Main.firstSlot(this),
                intoCapacity = bankCapacity,
                keepSlots = unbankable,
            )
        if (result.failure && bank.occupiedSpace() >= bankCapacity) {
            mes("Your bank cannot hold your items.")
        } else if (unbankable.isNotEmpty()) {
            mes("Some of your items cannot be stored in the bank.")
        }
        growMainTab(startLast)
        for ((wearpos, old) in before) {
            if (old != null && old != from[wearpos.slot]) {
                WornUnequipOp.notifyWornUnequip(player, wearpos, objTypes[old], eventBus)
            }
        }
    }

    /** New bank slots past the old end belong to the main tab (the last tab). */
    private fun ProtectedAccess.growMainTab(startLast: Int) {
        val grown = bank.lastOccupiedSlot() - startLast
        if (grown > 0) {
            BankTab.Main.increaseSize(this, grown)
        }
    }

    private companion object {
        const val MODE_1 = 0
        const val MODE_5 = 1
        const val MODE_10 = 2
        const val MODE_X = 3
        const val MODE_ALL = 4
    }
}

internal object DepositBoxInterfaces : InterfaceReferences() {
    val box = find("bank_depositbox")
}

internal object DepositBoxComponents : ComponentReferences() {
    val inventory = find("bank_depositbox:inventory")
    val deposit_inv = find("bank_depositbox:deposit_inv")
    val deposit_worn = find("bank_depositbox:deposit_worn")
    val quantity_1 = find("bank_depositbox:1")
    val quantity_5 = find("bank_depositbox:5")
    val quantity_10 = find("bank_depositbox:10")
    val quantity_x = find("bank_depositbox:x")
    val quantity_all = find("bank_depositbox:all")
}

internal object DepositBoxVarbits : VarBitReferences() {
    /** Selected quantity button: 0..4 = 1 / 5 / 10 / X / All (cs2 172). */
    val mode = find("depositbox_mode")
}

internal object DepositBoxVarps : VarpReferences() {
    /** The X amount. */
    val requested = find("depositbox_requestedquantity")
}
