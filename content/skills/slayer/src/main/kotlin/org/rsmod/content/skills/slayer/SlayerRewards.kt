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
 * Not yet (phase 2): unlock/extend purchases (slayer_unlock dbrows + the slayer_rewards_unlocks
 * bitfields), blocking tasks, storing tasks.
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
        when (comsub - CONFIRM_BASE) {
            CANCEL -> cancelTask()
            else -> mes("That option isn't available yet.")
        }
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
        const val CONFIRM_MAX = 100
        const val CANCEL_COST = 30
    }
}

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

internal object SlayerRewardObjs : ObjReferences() {
    val slayer_ring = find("slayer_ring_8")
    val broad_bolts = find("slayer_broad_bolt")
    val broad_arrows = find("slayer_broad_arrows")
    val herb_sack = find("slayer_herb_sack")
    val rune_pouch = find("bh_rune_pouch")
    val looting_bag = find("looting_bag")
}
