package org.rsmod.content.skills.smithing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.smithing.configs.SmithingQueues
import org.rsmod.content.skills.smithing.configs.SmithingSeqs
import org.rsmod.content.skills.smithing.configs.smithing_objs
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Furnace smelting for bronze, iron and steel bars (OSRS Wiki levels/xp; iron has a 50% chance to
 * fail at a regular furnace). One bar every [SMELT_TICKS] ticks from a weak queue, so walking away
 * or clicking something else stops it.
 *
 * TODO: OSRS uses the skillmulti (make-X) chatbox here; this uses the menu dialog until a shared
 *   skillmulti helper exists.
 */
class Smelting
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    override fun ScriptContext.startup() {
        // Bind every loc named "Furnace" by the slot of its "Smelt" op (op2 on standard furnaces;
        // tutorial furnaces use "Use"), so new or regional furnaces work without a list.
        var bound = 0
        for (type in locTypes.values) {
            if (!type.name.contains("furnace", ignoreCase = true)) {
                continue
            }
            // "Use" only for plain "Furnace" (tutorial furnaces); other furnace-named locs (e.g.
            // blast furnace parts) must have a real "Smelt" op.
            val exact = type.name.equals("Furnace", ignoreCase = true)
            val slot =
                type.op.indexOfFirst {
                    it.equals("Smelt", ignoreCase = true) || (exact && it.equals("Use", true))
                }
            if (slot == -1) {
                continue
            }
            onOpLocSlot(type, slot + 1) { selectBar() }
            onOpLocU(type) { selectBar() }
            bound++
        }
        logger.info { "Smelting: bound $bound furnace locs." }
        onPlayerQueueWithArgs<SmeltJob>(SmithingQueues.smelting) { smelt(it.args) }
    }

    private suspend fun ProtectedAccess.selectBar() {
        val available = Bar.entries.filter { hasOres(it) }
        if (available.isEmpty()) {
            mes("You don't have any ores to smelt.")
            return
        }
        val bar =
            if (available.size == 1) {
                available.single()
            } else {
                val names = available.map { objTypes[it.bar()].name }
                available[menu("What would you like to smelt?", hotkeys = true, choices = names)]
            }
        val quantity = selectQuantity(maxCount(bar)) ?: return
        smelt(SmeltJob(bar, quantity))
    }

    private suspend fun ProtectedAccess.selectQuantity(max: Int): Int? {
        if (max <= 1) {
            return 1
        }
        val options = listOf("1", "5", "10", "X", "All")
        return when (menu("How many would you like to smelt?", hotkeys = true, choices = options)) {
            0 -> 1
            1 -> 5
            2 -> 10
            3 -> countDialog().takeIf { it > 0 }
            else -> max
        }
    }

    private fun ProtectedAccess.smelt(job: SmeltJob) {
        val bar = job.bar
        if (player.smithingLvl < bar.level) {
            mes("You need a Smithing level of ${bar.level} to smelt ${bar.metal}.")
            return
        }
        if (!hasOres(bar)) {
            mes(bar.missingMessage)
            return
        }

        anim(SmithingSeqs.furnace)
        mes(bar.smeltMessage)
        for ((ore, count) in bar.ores()) {
            invDel(inv, ore, count)
        }

        if (bar.failChance > 0 && random.of(1, 100) <= bar.failChance) {
            mes("The ore is too impure and you fail to refine it.")
        } else {
            invAdd(inv, bar.bar())
            statAdvance(stats.smithing, bar.xp * xpMods.get(player, stats.smithing))
            spam("You retrieve a bar of ${bar.metal}.")
        }

        val remaining = job.remaining - 1
        if (remaining > 0 && hasOres(bar)) {
            weakQueue(SmithingQueues.smelting, SMELT_TICKS, SmeltJob(bar, remaining))
        }
    }

    private fun ProtectedAccess.hasOres(bar: Bar): Boolean =
        bar.ores().all { (ore, count) -> invTotal(inv, ore) >= count }

    private fun ProtectedAccess.maxCount(bar: Bar): Int =
        bar.ores().minOf { (ore, count) -> invTotal(inv, ore) / count }

    private data class SmeltJob(val bar: Bar, val remaining: Int)

    private enum class Bar(
        val metal: String,
        val level: Int,
        val xp: Double,
        val failChance: Int,
        val smeltMessage: String,
        val missingMessage: String,
        val bar: () -> ObjType,
        val ores: () -> List<Pair<ObjType, Int>>,
    ) {
        Bronze(
            metal = "bronze",
            level = 1,
            xp = 6.25,
            failChance = 0,
            smeltMessage = "You smelt the copper and tin together in the furnace.",
            missingMessage = "You need one copper ore and one tin ore to make a bronze bar.",
            bar = { smithing_objs.bronze_bar },
            ores = { listOf(smithing_objs.copper_ore to 1, smithing_objs.tin_ore to 1) },
        ),
        Iron(
            metal = "iron",
            level = 15,
            xp = 12.5,
            failChance = 50,
            smeltMessage = "You smelt the iron in the furnace.",
            missingMessage = "You don't have any iron ore to smelt.",
            bar = { smithing_objs.iron_bar },
            ores = { listOf(smithing_objs.iron_ore to 1) },
        ),
        Steel(
            metal = "steel",
            level = 30,
            xp = 17.5,
            failChance = 0,
            smeltMessage = "You place the iron and two heaps of coal into the furnace.",
            missingMessage = "You need one iron ore and two coal to make a steel bar.",
            bar = { smithing_objs.steel_bar },
            ores = { listOf(smithing_objs.iron_ore to 1, smithing_objs.coal to 2) },
        ),
    }

    private companion object {
        private const val SMELT_TICKS = 5
    }
}

/** Binds [action] to op [slot] (1-5) of [type]. */
internal fun ScriptContext.onOpLocSlot(
    type: LocType,
    slot: Int,
    action: suspend ProtectedAccess.() -> Unit,
) {
    when (slot) {
        1 -> onOpLoc1(type) { action() }
        2 -> onOpLoc2(type) { action() }
        3 -> onOpLoc3(type) { action() }
        4 -> onOpLoc4(type) { action() }
        5 -> onOpLoc5(type) { action() }
    }
}
