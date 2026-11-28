package org.rsmod.content.skills.smithing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.script.isLocOpBound
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
 * Furnace smelting for every standard bar (OSRS Wiki levels/xp; iron has a 50% chance to fail at
 * a regular furnace). One bar every [SMELT_TICKS] ticks from a weak queue, so walking away
 * or clicking something else stops it.
 *
 * Bar and quantity are picked in the OSRS skillmulti chatbox ([ProtectedAccess.skillMulti]).
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
        // tutorial furnaces use "Use"), so new or regional furnaces work without a list. Forges
        // (Lava/Clay/Sacred forge) share the furnace cache category instead of the name.
        var bound = 0
        for (type in locTypes.values) {
            val furnace = type.name.contains("furnace", ignoreCase = true)
            if (!furnace && type.category != FURNACE_CATEGORY) {
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

    /**
     * OSRS skillmulti "What would you like to smelt?": every furnace bar is listed in Wiki level
     * order; bars the player has the ores for show normally, the rest greyed. The quantity comes
     * from the chatbox's 1/5/10/X/All buttons.
     */
    private suspend fun ProtectedAccess.selectBar() {
        val products =
            Bar.entries.map { bar ->
                val obj = bar.bar()
                SkillMultiProduct(obj, objTypes[obj].name, available = hasOres(bar) && player.smithingLvl >= bar.level)
            }
        if (products.none { it.available }) {
            mes("You don't have any ores to smelt.")
            return
        }
        val max = Bar.entries.maxOf { maxCount(it) }.coerceIn(1, MAX_SMELT)
        val choice =
            skillMulti(
                verb = SkillMultiVerb.SMELT,
                title = "What would you like to smelt?",
                products = products,
                maxQuantity = max,
            )
        smelt(SmeltJob(Bar.entries[choice.index], choice.quantity))
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
        Blurite(
            metal = "blurite",
            level = 13,
            xp = 8.0,
            failChance = 0,
            smeltMessage = "You place the blurite ore into the furnace.",
            missingMessage = "You don't have any blurite ore to smelt.",
            bar = { smithing_objs.blurite_bar },
            ores = { listOf(smithing_objs.blurite_ore to 1) },
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
        Silver(
            metal = "silver",
            level = 20,
            xp = 13.7,
            failChance = 0,
            smeltMessage = "You place a lump of silver in the furnace.",
            missingMessage = "You don't have any silver ore to smelt.",
            bar = { smithing_objs.silver_bar },
            ores = { listOf(smithing_objs.silver_ore to 1) },
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
        Gold(
            metal = "gold",
            level = 40,
            xp = 22.5,
            failChance = 0,
            smeltMessage = "You place a lump of gold in the furnace.",
            missingMessage = "You don't have any gold ore to smelt.",
            bar = { smithing_objs.gold_bar },
            ores = { listOf(smithing_objs.gold_ore to 1) },
        ),
        Mithril(
            metal = "mithril",
            level = 50,
            xp = 30.0,
            failChance = 0,
            smeltMessage = "You place the mithril and four heaps of coal into the furnace.",
            missingMessage = "You need one mithril ore and four coal to make a mithril bar.",
            bar = { smithing_objs.mithril_bar },
            ores = { listOf(smithing_objs.mithril_ore to 1, smithing_objs.coal to 4) },
        ),
        Adamantite(
            metal = "adamantite",
            level = 70,
            xp = 37.5,
            failChance = 0,
            smeltMessage = "You place the adamantite and six heaps of coal into the furnace.",
            missingMessage = "You need one adamantite ore and six coal to make an adamantite bar.",
            bar = { smithing_objs.adamantite_bar },
            ores = { listOf(smithing_objs.adamantite_ore to 1, smithing_objs.coal to 6) },
        ),
        Runite(
            metal = "runite",
            level = 85,
            xp = 50.0,
            failChance = 0,
            smeltMessage = "You place the runite and eight heaps of coal into the furnace.",
            missingMessage = "You need one runite ore and eight coal to make a runite bar.",
            bar = { smithing_objs.runite_bar },
            ores = { listOf(smithing_objs.runite_ore to 1, smithing_objs.coal to 8) },
        ),
    }

    private companion object {
        private const val SMELT_TICKS = 5
        private const val MAX_SMELT = 28

        /** Cache `category_215`: furnaces and forges. */
        private const val FURNACE_CATEGORY = 215
    }
}

/** Binds [action] to op [slot] (1-5) of [type]. */
internal fun ScriptContext.onOpLocSlot(
    type: LocType,
    slot: Int,
    action: suspend ProtectedAccess.() -> Unit,
) {
    when (slot) {
        1 -> if (!isLocOpBound(type, 1)) onOpLoc1(type) { action() }
        2 -> if (!isLocOpBound(type, 2)) onOpLoc2(type) { action() }
        3 -> if (!isLocOpBound(type, 3)) onOpLoc3(type) { action() }
        4 -> if (!isLocOpBound(type, 4)) onOpLoc4(type) { action() }
        5 -> if (!isLocOpBound(type, 5)) onOpLoc5(type) { action() }
    }
}
