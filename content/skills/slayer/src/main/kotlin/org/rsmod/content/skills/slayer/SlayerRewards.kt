package org.rsmod.content.skills.slayer

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.varp.VarpType
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Slayer rewards, phase 1 (rev 243 interface 426 `slayer_rewards`): every master's "Rewards" op
 * opens it. The client builds all four tabs itself from the cache; the server handles:
 * - **Points**: the client reads the cache varbit `slayer_points` (17 bits); our points live in the
 *   server varp [SlayerVarps.points], so it is mirrored on open and whenever points change.
 * - **Buy**: the shop in client enums 840 (items) / 841 (quantity per purchase) / 842 (price), ops
 *   2-5 = Buy 1/5/10/50 on `buy_items` (comsub = item index).
 * - **Tasks > Cancel**: the confirm cc the client creates at index 66 + 1 on `confirm_button`;
 *   30 points (Turael's free reset stays in his dialogue).
 *
 * - **Unlock / Extend** (phase 2): the slayer_unlock dbrows (slayer_unlocks.tsv): confirming an
 *   unlock (cc index = its bit) pays its cost and sets the bit in slayer_rewards_unlocks/1/2;
 *   confirming an owned one disables it (no refund). "Extend everything" buys every missing
 *   extension. Bought "longer" unlocks make new tasks use the Wiki extended amounts.
 *
 * Not yet: blocking/storing tasks, and the other unlocks' effects (Gargoyle Smasher, Bigger and
 * Badder, slayer helm imbues, ...): their bits are bought and shown, the effect is separate.
 */
class SlayerRewards
@Inject
constructor(private val npcTypes: NpcTypeList, private val objRepo: ObjRepository) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var bound = 0
        for (master in SlayerData.masters) {
            val npc = SlayerNpcs.byName.getValue(master.npc)
            val op = npcTypes[npc].op.indexOfFirst { it.equals("Rewards", ignoreCase = true) } + 1
            if (op > 0) {
                bindOp(npc, op)
                bound++
            }
        }
        onIfModalButton(SlayerRewardComponents.buy_items) { buy(it.comsub, it.op) }
        onIfModalButton(SlayerRewardComponents.confirm_button) { confirm(it.comsub) }
        onPlayerLogin { player.syncSlayerPointsDisplay() }
        logger.info { "Slayer: Rewards op bound on $bound masters." }
    }

    private fun ScriptContext.bindOp(npc: NpcType, op: Int) {
        when (op) {
            1 -> onOpNpc1(npc) { open() }
            2 -> onOpNpc2(npc) { open() }
            3 -> onOpNpc3(npc) { open() }
            4 -> onOpNpc4(npc) { open() }
            5 -> onOpNpc5(npc) { open() }
        }
    }

    private fun ProtectedAccess.open() {
        player.syncSlayerPointsDisplay()
        ifOpenMainModal(SlayerRewardInterfaces.slayer_rewards)
        player.ifSetEvents(SlayerRewardComponents.buy_items, 0 until SHOP.size, IfEvent.Op2, IfEvent.Op3, IfEvent.Op4, IfEvent.Op5)
        player.ifSetEvents(SlayerRewardComponents.confirm_button, 0..CONFIRM_MAX, IfEvent.Op1)
    }

    private fun ProtectedAccess.buy(index: Int, op: IfButtonOp) {
        val item = SHOP.getOrNull(index) ?: return
        val purchases =
            when (op) {
                IfButtonOp.Op2 -> 1
                IfButtonOp.Op3 -> 5
                IfButtonOp.Op4 -> 10
                IfButtonOp.Op5 -> 50
                else -> return
            }
        val points = vars[SlayerVarps.points]
        val affordable = minOf(purchases, points / item.price)
        if (affordable <= 0) {
            mes("You need ${item.price} Slayer reward points to buy that.")
            return
        }
        val obj = item.obj()
        val count = affordable * item.quantity
        vars[SlayerVarps.points] = points - affordable * item.price
        invAddOrDrop(objRepo, obj, count)
        player.syncSlayerPointsDisplay()
        mes("You spend ${affordable * item.price} points.")
    }

    private fun ProtectedAccess.confirm(comsub: Int) {
        // The confirm cc index is the unlock bit (0-66) for Unlock/Extend, or 66 + action for the
        // Tasks tab (1 cancel, 2 block) and 66 + 9 for "Extend everything".
        if (comsub in UNLOCK_BITS) {
            toggleUnlock(comsub)
            return
        }
        when (comsub - CONFIRM_BASE) {
            CANCEL -> cancelTask()
            BLOCK -> blockTask()
            EXTEND_ALL -> extendAll()
            STORE -> storeTask()
            UNSTORE -> unstoreTask()
            else -> mes("That option isn't available yet.")
        }
    }

    private fun ProtectedAccess.toggleUnlock(bit: Int) {
        val unlock = unlocksByBit[bit] ?: return
        if (player.hasSlayerUnlock(bit)) {
            // OSRS "Disable": turn it off; points are not refunded.
            player.setSlayerUnlock(bit, false)
            mes("${unlock.name} has been disabled.")
            return
        }
        val points = vars[SlayerVarps.points]
        if (points < unlock.cost) {
            mes("You need ${unlock.cost} Slayer reward points to unlock ${unlock.name}.")
            return
        }
        vars[SlayerVarps.points] = points - unlock.cost
        player.setSlayerUnlock(bit, true)
        player.syncSlayerPointsDisplay()
        mes("You unlock ${unlock.name}.")
    }

    private fun ProtectedAccess.extendAll() {
        val missing = SlayerData.unlocks.filter { it.extends.isNotEmpty() && !player.hasSlayerUnlock(it.bit) }
        if (missing.isEmpty()) {
            mes("You have already unlocked every task extension.")
            return
        }
        val total = missing.sumOf { it.cost }
        val points = vars[SlayerVarps.points]
        if (points < total) {
            mes("You need $total Slayer reward points to unlock every task extension.")
            return
        }
        vars[SlayerVarps.points] = points - total
        missing.forEach { player.setSlayerUnlock(it.bit, true) }
        player.syncSlayerPointsDisplay()
        mes("You unlock ${missing.size} task extensions for $total points.")
    }

    /** Block the current task's category for every master (100 points, 6 slots); cancels it. */
    private fun ProtectedAccess.blockTask() {
        val task = vars[SlayerVarps.task]
        if (task == 0 || vars[SlayerVarps.count] <= 0) {
            mes("You don't have a Slayer task to block.")
            return
        }
        val slot = SlayerBlockVarps.blocks.firstOrNull { vars[it] == 0 }
        if (slot == null) {
            mes("You can't block any more tasks; all $SLAYER_BLOCK_SLOTS block slots are in use.")
            return
        }
        val points = vars[SlayerVarps.points]
        if (points < BLOCK_COST) {
            mes("You need $BLOCK_COST Slayer reward points to block your task.")
            return
        }
        vars[SlayerVarps.points] = points - BLOCK_COST
        vars[slot] = task
        clearTask()
        player.syncSlayerPointsDisplay()
        mes("Your task has been cancelled and blocked from being assigned again.")
    }

    /** Task Storage (unlock bit 51): park the current task; unstore it later. */
    private fun ProtectedAccess.storeTask() {
        if (!player.hasSlayerUnlock(TASK_STORAGE_BIT)) {
            mes("You need to unlock Task Storage first.")
            return
        }
        if (vars[SlayerVarps.task] == 0 || vars[SlayerVarps.count] <= 0) {
            mes("You don't have a Slayer task to store.")
            return
        }
        if (vars[SlayerBlockVarps.stored_task] != 0) {
            mes("You already have a task stored.")
            return
        }
        vars[SlayerBlockVarps.stored_task] = vars[SlayerVarps.task]
        vars[SlayerBlockVarps.stored_count] = vars[SlayerVarps.count]
        vars[SlayerBlockVarps.stored_master] = vars[SlayerVarps.master]
        clearTask()
        mes("Your task has been stored.")
    }

    private fun ProtectedAccess.unstoreTask() {
        if (vars[SlayerBlockVarps.stored_task] == 0) {
            mes("You don't have a stored task.")
            return
        }
        if (vars[SlayerVarps.task] != 0 && vars[SlayerVarps.count] > 0) {
            mes("You need to finish or cancel your current task first.")
            return
        }
        vars[SlayerVarps.task] = vars[SlayerBlockVarps.stored_task]
        vars[SlayerVarps.count] = vars[SlayerBlockVarps.stored_count]
        vars[SlayerVarps.master] = vars[SlayerBlockVarps.stored_master]
        vars[SlayerBlockVarps.stored_task] = 0
        vars[SlayerBlockVarps.stored_count] = 0
        vars[SlayerBlockVarps.stored_master] = 0
        mes("Your stored task is now your active task.")
    }

    private fun ProtectedAccess.clearTask() {
        vars[SlayerVarps.task] = 0
        vars[SlayerVarps.count] = 0
        vars[SlayerVarps.master] = 0
    }

    private fun ProtectedAccess.cancelTask() {
        if (vars[SlayerVarps.task] == 0 || vars[SlayerVarps.count] <= 0) {
            mes("You don't have a Slayer task to cancel.")
            return
        }
        val points = vars[SlayerVarps.points]
        if (points < CANCEL_COST) {
            mes("You need $CANCEL_COST Slayer reward points to cancel your task.")
            return
        }
        vars[SlayerVarps.points] = points - CANCEL_COST
        vars[SlayerVarps.task] = 0
        vars[SlayerVarps.count] = 0
        vars[SlayerVarps.master] = 0
        player.syncSlayerPointsDisplay()
        mes("Your Slayer task has been cancelled.")
    }

    /** Wiki / client enum 840-842 Slayer reward shop: item, quantity per purchase, price. */
    private class ShopItem(val obj: () -> ObjType, val quantity: Int, val price: Int)

    private companion object {
        val SHOP =
            listOf(
                ShopItem({ SlayerRewardObjs.slayer_ring }, 1, 75),
                ShopItem({ SlayerRewardObjs.broad_bolts }, 250, 35),
                ShopItem({ SlayerRewardObjs.broad_arrows }, 250, 35),
                ShopItem({ SlayerRewardObjs.herb_sack }, 1, 750),
                ShopItem({ SlayerRewardObjs.rune_pouch }, 1, 750),
                ShopItem({ SlayerRewardObjs.looting_bag }, 1, 10),
            )

        /** slayer_rewards_tasks_canceltask: confirm cc index = 66 + action (1 cancel, 2 block). */
        const val CONFIRM_BASE = 66
        const val CANCEL = 1
        const val BLOCK = 2
        const val EXTEND_ALL = 9
        const val STORE = 10
        const val UNSTORE = 11
        const val BLOCK_COST = 100
        /** slayer_unlock "Task Storage". */
        const val TASK_STORAGE_BIT = 51
        val UNLOCK_BITS = 0 until CONFIRM_BASE + 1

        val unlocksByBit: Map<Int, SlayerData.Unlock> by lazy { SlayerData.unlocks.associateBy { it.bit } }
        const val CONFIRM_MAX = 100
        const val CANCEL_COST = 30
    }
}

/** slayer_reward_unlocked: bit n lives in slayer_rewards_unlocks(n / 32) at bit n % 32. */
internal fun Player.hasSlayerUnlock(bit: Int): Boolean {
    val varp = SlayerUnlockVarps.forBit(bit) ?: return false
    return ((vars[varp] ushr (bit % 32)) and 1) == 1
}

internal fun Player.setSlayerUnlock(bit: Int, on: Boolean) {
    val varp = SlayerUnlockVarps.forBit(bit) ?: return
    val mask = 1 shl (bit % 32)
    val value = if (on) vars[varp] or mask else vars[varp] and mask.inv()
    VarPlayerIntMapSetter.set(this, varp, value)
}

/** True when [categoryId] is in one of the player's block slots. */
internal fun Player.isSlayerTaskBlocked(categoryId: Int): Boolean =
    categoryId != 0 && SlayerBlockVarps.blocks.any { vars[it] == categoryId }

/** True when a bought "longer" unlock extends [category] (a Wiki category key). */
internal fun Player.hasExtendedTask(category: String): Boolean =
    SlayerData.unlocks.any { category in it.extends && hasSlayerUnlock(it.bit) }

/** Mirrors the server-side points into the client's `slayer_points` varbit (17 bits). */
internal fun Player.syncSlayerPointsDisplay() {
    val points = vars[SlayerVarps.points].coerceIn(0, CLIENT_POINTS_MAX)
    VarPlayerIntMapSetter.set(this, SlayerRewardVarBits.client_points, points)
}

private const val CLIENT_POINTS_MAX = (1 shl 17) - 1

internal object SlayerRewardInterfaces : InterfaceReferences() {
    val slayer_rewards = find("slayer_rewards")
}

internal object SlayerRewardComponents : ComponentReferences() {
    val buy_items = find("slayer_rewards:buy_items")
    val confirm_button = find("slayer_rewards:confirm_button")
}

internal object SlayerRewardVarBits : VarBitReferences() {
    /** Client-side points shown in the rewards interface (cache varbit 4068). */
    val client_points = find("slayer_points")
}

internal object SlayerUnlockVarps : VarpReferences() {
    private val unlocks =
        listOf(find("slayer_rewards_unlocks"), find("slayer_rewards_unlocks1"), find("slayer_rewards_unlocks2"))

    fun forBit(bit: Int): VarpType? = if (bit < 0) null else unlocks.getOrNull(bit / 32)
}

internal object SlayerRewardObjs : ObjReferences() {
    val slayer_ring = find("slayer_ring_8")
    val broad_bolts = find("slayer_broad_bolt")
    val broad_arrows = find("slayer_broad_arrows")
    val herb_sack = find("slayer_herb_sack")
    val rune_pouch = find("bh_rune_pouch")
    val looting_bag = find("looting_bag")
}
