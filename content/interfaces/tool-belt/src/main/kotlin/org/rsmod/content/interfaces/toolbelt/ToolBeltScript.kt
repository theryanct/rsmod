package org.rsmod.content.interfaces.toolbelt

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.invs
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.events.ToolBeltOpenRequest
import org.rsmod.api.player.output.UpdateInventory
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * The tool belt (Ryan, 2026-11-28): a persistent store for tools that every skill treats as
 * carried (`api/player/tools`: hasTool / bestTool). Opened with right-click "Tool belt" on the
 * inventory tab button or `::toolbelt`.
 *
 * - Clicking a tool in the side panel ("Belt") or "Fill" puts tools on the belt; one per group
 *   (`tool_belt.tsv`), and belting a better tier hands the old one back.
 * - Clicking a belted tool (any Withdraw op) or "Empty" takes tools back into the inventory.
 * - Materials get tool ops ([ToolMaterialEdits]) so knife/tinderbox/chisel/needle/pipe/pestle
 *   work from the belt too.
 */
class ToolBeltScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val invTypes: InvTypeList,
    private val names: NameMapping,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    // Lazy, not built in startup(): other scripts may inject their own (never started) instance.
    private val tools by lazy { ToolBeltTools.load(names.objs) }

    override fun ScriptContext.startup() {
        logger.info { "Tool belt: ${tools.byObj.size} tools." }
        if (tools.missing.isNotEmpty()) {
            logger.warn { "Tool belt: unknown objs in tool_belt.tsv: ${tools.missing}" }
        }

        onEvent<ToolBeltOpenRequest> { player.launchBelt() }
        onCommand("toolbelt") {
            modLevel = modlevels.admin
            desc = "Open your tool belt"
            cheat { player.launchBelt() }
        }

        val comps = ToolBeltComponents
        onIfModalButton(comps.side_items) {
            when (it.op) {
                IfButtonOp.Op1 -> deposit(it.comsub, quiet = false)
                IfButtonOp.Op10 -> examine(inv[it.comsub])
                else -> Unit
            }
        }
        onIfModalButton(comps.items) {
            when (it.op) {
                IfButtonOp.Op10 -> examine(belt()[it.comsub])
                else -> withdraw(it.comsub, quiet = false)
            }
        }
        onIfModalButton(comps.fill) { fill() }
        onIfModalButton(comps.empty) { empty() }
        for (button in comps.quantity_buttons) {
            onIfModalButton(button) {}
        }
    }

    private fun Player.launchBelt() {
        protectedAccess.launch(this, busyText = "You're busy right now.") { openBelt() }
    }

    private fun ProtectedAccess.belt(): Inventory = inv(invs.toolbelt)

    private fun ProtectedAccess.openBelt() {
        val comps = ToolBeltComponents
        ifOpenMainSidePair(ToolBeltInterfaces.main, ToolBeltInterfaces.side)
        // The frame's onload titles it "Tackle Box"; redraw the border with our title.
        runClientScript(STEELBORDER, comps.frame.packed, "Tool belt")
        sendBelt()
        ifSetEvents(
            comps.items,
            0 until belt().size,
            IfEvent.Op1,
            IfEvent.Op2,
            IfEvent.Op3,
            IfEvent.Op4,
            IfEvent.Op5,
            IfEvent.Op10,
        )
        interfaceInvInit(
            inv = inv,
            target = comps.side_items,
            objRowCount = 4,
            objColCount = 7,
            op1 = "Belt<col=ff9040>",
        )
        ifSetEvents(comps.side_items, 0 until inv.size, IfEvent.Op1, IfEvent.Op10)
    }

    /** The window draws inv `tackle_box`: send the belt's contents under that id. */
    private fun ProtectedAccess.sendBelt() {
        val belt = belt()
        val display = Inventory.create(invTypes[ToolBeltInvs.display])
        for (slot in display.indices) {
            display[slot] = belt[slot]
        }
        UpdateInventory.updateInvFull(player, display)
    }

    /** @return `true` if the obj in [slot] went onto the belt. */
    private fun ProtectedAccess.deposit(slot: Int, quiet: Boolean): Boolean {
        val obj = inv[slot] ?: return false
        val tool = tools.byObj[obj.id]
        if (tool == null) {
            if (!quiet) {
                mes("That isn't a tool you can put on your tool belt.")
            }
            return false
        }
        val belt = belt()
        val name = objTypes[obj].name
        val existingSlot =
            belt.indices.firstOrNull { slot ->
                belt[slot]?.let { tools.byObj[it.id] }?.group == tool.group
            }
        if (existingSlot != null) {
            val existing = belt.getValue(existingSlot)
            val existingRank = tools.byObj.getValue(existing.id).rank
            if (existingRank >= tool.rank) {
                if (!quiet) {
                    val owned = objTypes[existing].name
                    mes("Your tool belt already has ${article(owned)}, which is at least as good.")
                }
                return false
            }
            // Better tier: swap it in and hand the old one back in the same slot.
            belt[existingSlot] = InvObj(obj)
            inv[slot] = InvObj(existing)
            val old = objTypes[existing].name.lowercase()
            mes("You swap the $old on your tool belt for the ${name.lowercase()}.")
            sendBelt()
            return true
        }
        val free = belt.indices.firstOrNull { belt[it] == null }
        if (free == null) {
            if (!quiet) {
                mes("Your tool belt is full.")
            }
            return false
        }
        belt[free] = InvObj(obj)
        inv[slot] = null
        if (!quiet) {
            mes("You put the ${name.lowercase()} on your tool belt.")
        }
        sendBelt()
        return true
    }

    /** @return `true` if the tool in belt [slot] went back into the inventory. */
    private fun ProtectedAccess.withdraw(slot: Int, quiet: Boolean): Boolean {
        val belt = belt()
        val obj = belt[slot] ?: return false
        if (invAdd(inv, obj).failure) {
            if (!quiet) {
                mes("You don't have enough inventory space.")
            }
            return false
        }
        belt[slot] = null
        sendBelt()
        return true
    }

    private fun ProtectedAccess.fill() {
        val moved = inv.indices.count { deposit(it, quiet = true) }
        mes(
            if (moved == 0) "You have no tools to put on your tool belt."
            else "You put $moved ${if (moved == 1) "tool" else "tools"} on your tool belt."
        )
    }

    private fun ProtectedAccess.empty() {
        val belt = belt()
        val held = belt.indices.filter { belt[it] != null }
        val moved = held.count { withdraw(it, quiet = true) }
        if (moved < held.size) {
            mes("You don't have enough inventory space for all of your tools.")
        }
    }

    private fun ProtectedAccess.examine(obj: InvObj?) {
        obj ?: return
        mes(objTypes[obj].desc)
    }

    private fun article(name: String): String {
        val lower = name.lowercase()
        return if (lower.first() in "aeiou") "an $lower" else "a $lower"
    }

    private companion object {
        /** Client script `steelborder(component, title)`. */
        private const val STEELBORDER = 227
    }
}
