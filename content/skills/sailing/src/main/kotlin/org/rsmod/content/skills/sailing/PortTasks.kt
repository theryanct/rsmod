package org.rsmod.content.skills.sailing

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseSailingLvl
import org.rsmod.api.player.ui.ifOpenMainModal
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.sailing.SailingData.Courier
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sailing courier tasks (port tasks, task type 0), travel-agnostic: the player takes crates from
 * the cargo port's ledger table and hands them in at the destination's ledger table however they
 * got there (charter ship, on foot; a boat's cargo hold once boats exist).
 *
 * - Notice board ("Inspect") opens the rev 243 `port_task_board` (941) with up to 8 of that port's
 *   tasks; clicking a notice closes it and confirms the task in a dialogue (the board's own
 *   `port_task_info` popup answers through `resume_countdialog`, which a suspended script would
 *   block further notice clicks on, so it is not used yet).
 * - Ledger table: "Take-cargo" gives one crate of an active task whose cargo is here; holding a
 *   crate for this port flips the table (multivar `sailing_carrying_cargo`) to "Deposit-cargo".
 * - Task state lives in the client's `port_task_slot_N_*` varbits (tasks 1/7/28/56/84 levels).
 *
 * Data: content/skills/sailing/.../sailing.tsv (tools/skills/sailing_to_tsv.py). XP is the Wiki's
 * (the cache's xp_reward column is server-only). Not yet: coin/reward bags, shark paint, bounty
 * tasks, cancelling through a port master or the captain's log (the board does it instead).
 */
class PortTasks
@Inject
constructor(
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    private val byId = SailingData.couriers.associateBy { it.taskId }
    private val byBoard = SailingData.couriers.groupBy { it.board }

    /** Tasks shown on the board the player last opened, in notice order. */
    private val shown = WeakHashMap<Player, List<Courier>>()

    override fun ScriptContext.startup() {
        for ((port, loc) in SailingLocs.boards) {
            onOpLoc1(loc) { openBoard(port) }
        }
        for ((port, loc) in SailingLocs.ledgers) {
            onOpLoc1(loc) { ledger(port, deposit = true) }
            onOpLoc2(loc) { ledger(port, deposit = false) }
            onOpLoc3(loc) { ledger(port, deposit = false) }
        }
        onIfModalButton(SailingComponents.board_container) { selectNotice(it.comsub) }
        onIfModalButton(SailingComponents.board_close) { ifClose() }
        logger.info {
            "Sailing: ${SailingData.couriers.size} courier tasks, ${SailingLocs.boards.size} boards, " +
                "${SailingLocs.ledgers.size} ledger tables."
        }
    }

    /* Notice board ---------------------------------------------------------------------------- */

    private fun ProtectedAccess.openBoard(port: String) {
        if (port == PORT_SARIM && vars[SailingVarBits.chart_board_port_sarim] == 0) {
            // Sea charting: "Look at the port task board in Port Sarim" (generic chart, 35 xp).
            vars[SailingVarBits.chart_board_port_sarim] = 1
            statAdvance(stats.sailing, CHART_XP * xpMods.get(player, stats.sailing))
            mes("You make a note of the port task board in your charts.")
        }
        val active = activeTaskIds()
        val level = player.baseSailingLvl
        val pool = byBoard[port].orEmpty().filter { it.taskId !in active }
        val (open, locked) = pool.partition { it.level <= level }
        val notices =
            (open.sortedByDescending { it.level } + locked.sortedBy { it.level }).take(BOARD_NOTICES)
        if (notices.isEmpty()) {
            mes("There are no tasks on this board for you right now.")
            return
        }
        shown[player] = notices
        player.ifOpenMainModal(SailingInterfaces.port_task_board, eventBus)
        player.ifSetEvents(SailingComponents.board_container, 0 until BOARD_NOTICES * CCS_PER_NOTICE, IfEvent.Op1)
        val rows = notices.map { SailingDbRows.tasks.getValue(it.taskId).id }
        runClientScript(PORT_TASK_BOARD_INIT, *Array(BOARD_NOTICES) { rows.getOrElse(it) { -1 } })
    }

    /**
     * `port_task_draw_notice` creates six ccs per notice on the board (model, graphic, text,
     * model, rectangle, model), so notice `n` owns comsubs `6n..6n+5`; only the first has "Select".
     */
    private suspend fun ProtectedAccess.selectNotice(comsub: Int) {
        val task = shown[player]?.getOrNull(comsub / CCS_PER_NOTICE) ?: return
        ifClose()
        val slot = slotOf(task.taskId)
        if (slot != null) {
            val cancel =
                choice2("Cancel this task.", true, "Keep it.", false, title = task.name)
            if (cancel) {
                clearSlot(slot)
                updateCarrying()
                mes("You cancel the task: ${task.name}.")
            }
            return
        }
        if (player.baseSailingLvl < task.level) {
            mes("You need a Sailing level of ${task.level} to take on this task.")
            return
        }
        val free = freeSlot()
        if (free == null) {
            mes("You can't take on any more port tasks right now.")
            return
        }
        val crates = if (task.count > 1) "${task.count} crates" else "a crate"
        mesbox(
            "${task.name}: collect $crates of cargo at ${portName(task.cargoPort)} and deliver " +
                "${if (task.count > 1) "them" else "it"} to ${portName(task.destination)}."
        )
        val accept = choice2("Accept task.", true, "Not now.", false, title = task.name)
        if (!accept) {
            return
        }
        vars[SailingVarBits.slot_ids[free]] = task.taskId
        vars[SailingVarBits.slot_taken[free]] = 0
        vars[SailingVarBits.slot_delivered[free]] = 0
        mes("You accept the task: ${task.name}.")
    }

    /* Ledger table ---------------------------------------------------------------------------- */

    private suspend fun ProtectedAccess.ledger(port: String, deposit: Boolean) {
        // Quest cargo (e.g. Pandemonium's crate of ship parts) goes first.
        if (SailingLedgerHooks.handle(this, port)) {
            updateCarrying()
            return
        }
        if (deposit && deposit(port)) {
            updateCarrying()
            return
        }
        take(port)
        updateCarrying()
    }

    /** Hands in held crates for tasks ending here; returns `false` when there was nothing to give. */
    private fun ProtectedAccess.deposit(port: String): Boolean {
        var any = false
        for ((slot, task) in activeSlots()) {
            if (task.destination != port) {
                continue
            }
            val crate = SailingObjs.crates.getValue(task.crate)
            val delivered = vars[SailingVarBits.slot_delivered[slot]]
            val give = minOf(invTotal(inv, crate), task.count - delivered)
            if (give <= 0) {
                continue
            }
            any = true
            invDel(inv, crate, give)
            val total = delivered + give
            if (total < task.count) {
                vars[SailingVarBits.slot_delivered[slot]] = total
                mes("You deliver some of the cargo. ${task.count - total} more to go.")
                continue
            }
            clearSlot(slot)
            statAdvance(stats.sailing, task.xp * xpMods.get(player, stats.sailing))
            mes("You complete the task: ${task.name}.")
        }
        return any
    }

    private fun ProtectedAccess.take(port: String) {
        for ((slot, task) in activeSlots()) {
            if (task.cargoPort != port) {
                continue
            }
            val crate = SailingObjs.crates.getValue(task.crate)
            val delivered = vars[SailingVarBits.slot_delivered[slot]]
            // Lost or banked crates can be taken again, but never more than the task still needs.
            val held = invTotal(inv, crate) + invTotal(bank, crate)
            if (delivered + held >= task.count) {
                continue
            }
            if (inv.freeSpace() < 1) {
                mes("You need a free inventory space to carry the cargo.")
                return
            }
            invAdd(inv, crate)
            vars[SailingVarBits.slot_taken[slot]] = minOf(task.count, vars[SailingVarBits.slot_taken[slot]] + 1)
            mes("You take ${objTypes[crate].name.lowercase()} for the task: ${task.name}.")
            return
        }
        mes("There's no cargo here for any of your tasks.")
    }

    /** Ledger tables show "Deposit-cargo" while the player holds any courier crate. */
    private fun ProtectedAccess.updateCarrying() {
        val carrying =
            activeSlots().any { (_, task) -> invTotal(inv, SailingObjs.crates.getValue(task.crate)) > 0 } ||
                SailingLedgerHooks.carrying(this)
        vars[SailingVarBits.carrying_cargo] = if (carrying) 1 else 0
    }

    /* Slots ----------------------------------------------------------------------------------- */

    private fun ProtectedAccess.slotCount(): Int = SLOT_LEVELS.count { player.baseSailingLvl >= it }

    private fun ProtectedAccess.activeSlots(): List<Pair<Int, Courier>> =
        (0 until SLOTS).mapNotNull { slot -> byId[vars[SailingVarBits.slot_ids[slot]]]?.let { slot to it } }

    private fun ProtectedAccess.activeTaskIds(): Set<Int> = activeSlots().map { it.second.taskId }.toSet()

    private fun ProtectedAccess.slotOf(taskId: Int): Int? =
        (0 until SLOTS).firstOrNull { vars[SailingVarBits.slot_ids[it]] == taskId }

    private fun ProtectedAccess.freeSlot(): Int? =
        (0 until slotCount()).firstOrNull { vars[SailingVarBits.slot_ids[it]] == 0 }

    private fun ProtectedAccess.clearSlot(slot: Int) {
        vars[SailingVarBits.slot_ids[slot]] = 0
        vars[SailingVarBits.slot_taken[slot]] = 0
        vars[SailingVarBits.slot_delivered[slot]] = 0
    }

    private fun portName(key: String): String =
        key.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .replace("The ", "the ")
            .replaceFirstChar { it.uppercase() }

    private companion object {
        const val PORT_SARIM = "port_sarim"

        /** `[clientscript,port_task_board_init](dbrow x8)`. */
        const val PORT_TASK_BOARD_INIT = 8912
        const val BOARD_NOTICES = 8
        const val CCS_PER_NOTICE = 6

        const val SLOTS = 5
        /** Wiki "Port tasks": active task slots at Sailing 1, 7, 28, 56 and 84. */
        val SLOT_LEVELS = listOf(1, 7, 28, 56, 84)

        /** Wiki "Sea charting": generic (captain's log) charts give 35 xp. */
        const val CHART_XP = 35.0
    }
}
