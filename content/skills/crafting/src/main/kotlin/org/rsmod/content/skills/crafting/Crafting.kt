package org.rsmod.content.skills.crafting

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.crafting.CraftingData.Recipe
import org.rsmod.content.skills.crafting.CraftingData.Station
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Crafting from OSRS Wiki recipe data (levels, xp, ticks), all through the skillmulti chatbox:
 * - stations: spinning wheel, loom, potter's wheel, pottery oven (click the op or use a material
 *   on the loc), furnace jewellery with a mould and molten glass (use a bar, mould, sand or soda
 *   ash on the furnace; ores still go to Smelting's generic furnace handler);
 * - held: needle on leather, chisel on uncut gems, glassblowing pipe on molten glass, ball of wool
 *   on unstrung amulets, water on clay, orbs on battlestaves; the needle also sews dragonhide and
 *   snakeskin.
 *
 * Locs are bound by name and op label from the cache, so every spinning wheel/furnace works
 * without an id list. One action per recipe tick count from a weak queue, so moving or clicking
 * something else stops it.
 */
class Crafting
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    /** Items sewn since the last reel of thread was used up (OSRS: one reel per five items). */
    private val threadUses = WeakHashMap<Player, Int>()

    override fun ScriptContext.startup() {
        bindHeld()
        bindStations()
        onPlayerQueueWithArgs<CraftJob>(CraftingQueues.crafting) { craft(it.args) }
    }

    private fun ScriptContext.bindHeld() {
        // Keyed by the unordered pair: onOpHeldU fires both ways and throws on a reversed duplicate.
        val byPair = LinkedHashMap<Pair<Int, Int>, MutableList<Recipe>>()
        val objs = HashMap<Pair<Int, Int>, Pair<ObjType, ObjType>>()
        for (recipe in CraftingData.recipes) {
            if (recipe.station !in HELD) {
                continue
            }
            val tool = obj(checkNotNull(recipe.tool))
            val material = obj(recipe.materials.first().obj)
            val key = minOf(tool.id, material.id) to maxOf(tool.id, material.id)
            objs.putIfAbsent(key, tool to material)
            byPair.getOrPut(key) { mutableListOf() }.add(recipe)
        }
        for ((key, recipes) in byPair) {
            val (first, second) = objs.getValue(key)
            onOpHeldU(first, second) { select(recipes, recipes.first().station) }
        }
        logger.info { "Crafting: bound ${byPair.size} item-on-item pairs." }
    }

    private fun ScriptContext.bindStations() {
        val counts = HashMap<Station, Int>()
        for (type in locTypes.values) {
            val station = stationFor(type) ?: continue
            val recipes = CraftingData.recipes.filter { it.station == station }
            val slot = type.op.indexOfFirst { label -> STATION_OPS[station]?.any { it.equals(label, true) } == true }
            if (slot != -1) {
                bindOp(type, slot + 1) { openStation(station, recipes, used = null) }
            }
            for (used in usedOn(recipes)) {
                onOpLocU(type, used) { openStation(station, recipes, used) }
            }
            counts[station] = (counts[station] ?: 0) + 1
        }
        logger.info { "Crafting: bound stations $counts." }
    }

    /** Station for a loc, matched by name (and, for furnaces, Smelting's "Smelt"/"Use" rule). */
    private fun stationFor(type: UnpackedLocType): Station? {
        val name = type.name.trim()
        return when {
            name.equals("Spinning wheel", true) -> Station.Spin
            name.equals("Loom", true) -> Station.Loom
            name.equals("Potter's wheel", true) -> Station.Wheel
            name.equals("Pottery oven", true) -> Station.Oven
            name.contains("furnace", true) -> {
                val exact = name.equals("Furnace", true)
                val smelt =
                    type.op.any { it.equals("Smelt", true) || (exact && it.equals("Use", true)) }
                if (smelt) Station.Furnace else null
            }
            else -> null
        }
    }

    /** Objs that open a station's menu when used on it: materials, plus moulds for furnaces. */
    private fun usedOn(recipes: List<Recipe>): Set<ObjType> = buildSet {
        for (recipe in recipes) {
            recipe.materials.forEach { add(obj(it.obj)) }
            recipe.tool?.let { add(obj(it)) }
        }
    }

    private fun ScriptContext.bindOp(
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

    /**
     * Station menu: every recipe of the station (or those using [used]); furnaces only list
     * jewellery for moulds the player carries, and drop gem pieces for gems they don't have when
     * the list is longer than the chatbox.
     */
    private suspend fun ProtectedAccess.openStation(station: Station, all: List<Recipe>, used: ObjType?) {
        var recipes = all
        if (used != null) {
            recipes = recipes.filter { r -> objOrNull(r.tool) == used || r.materials.any { obj(it.obj) == used } }
        }
        if (station == Station.Furnace) {
            recipes = recipes.filter { it.tool == null || invTotal(inv, obj(it.tool)) > 0 }
            if (recipes.isEmpty()) {
                mes("You need a mould to craft jewellery.")
                return
            }
            if (recipes.size > MAX_PRODUCTS) {
                recipes = recipes.filter { r -> r.materials.all { invTotal(inv, obj(it.obj)) > 0 } }
            }
        }
        if (recipes.isEmpty()) {
            mes("Nothing interesting happens.")
            return
        }
        select(recipes, station)
    }

    /**
     * OSRS skillmulti chatbox for [recipes]. Products the player can't make yet (level or
     * materials) are greyed; the quantity comes from the 1/5/10/X/All buttons. Several recipes for
     * one product (crossbow string from sinew or roots) show once, using the variant in hand.
     */
    private suspend fun ProtectedAccess.select(recipes: List<Recipe>, station: Station) {
        val shown =
            recipes
                .groupBy { it.product }
                .values
                .map { variants -> variants.firstOrNull { maxActions(it) > 0 } ?: variants.first() }
                .sortedBy { it.level }
                .take(MAX_PRODUCTS)
        val products =
            shown.map { recipe ->
                val available = player.craftingLvl >= recipe.level && maxActions(recipe) > 0
                SkillMultiProduct(obj(recipe.product), productName(recipe), available)
            }
        val max = shown.maxOf { maxActions(it) }.coerceAtLeast(1)
        val choice =
            skillMulti(
                verb = verbFor(station),
                title = titleFor(station),
                products = products,
                maxQuantity = max,
            )
        craft(CraftJob(shown[choice.index], choice.quantity))
    }

    private fun verbFor(station: Station): Int =
        when (station) {
            Station.Spin -> SkillMultiVerb.SPIN
            Station.Chisel -> SkillMultiVerb.CUT
            Station.Stringing -> SkillMultiVerb.STRING
            else -> SkillMultiVerb.MAKE
        }

    private fun titleFor(station: Station): String =
        when (station) {
            Station.Spin -> "What would you like to spin?"
            Station.Chisel -> "How many would you like to cut?"
            Station.Stringing -> "How many would you like to string?"
            Station.Oven -> "What would you like to fire in the oven?"
            else -> "What would you like to make?"
        }

    private fun ProtectedAccess.craft(job: CraftJob) {
        val recipe = job.recipe
        val name = productName(recipe)
        if (player.craftingLvl < recipe.level) {
            mes("You need a Crafting level of ${recipe.level} to make $name.")
            return
        }
        val tool = recipe.tool
        if (tool != null && recipe.station !in CONSUMED_TOOL && invTotal(inv, obj(tool)) <= 0) {
            mes("You need ${article(objTypes[obj(tool)].name)} to make $name.")
            return
        }
        if (recipe.station == Station.Needle && invTotal(inv, CraftingObjs.thread) <= 0) {
            mes("You need some thread to make $name.")
            return
        }
        if (maxActions(recipe) <= 0) {
            mes("You don't have the materials to make $name.")
            return
        }

        recipe.anim?.let { anim(CraftingSeqs.byName.getValue(it)) }
        for (material in recipe.materials) {
            invDel(inv, obj(material.obj), material.count)
        }
        if (recipe.station in CONSUMED_TOOL) {
            invDel(inv, obj(checkNotNull(tool)), 1)
        }
        recipe.leftover?.let { invAdd(inv, obj(it), 1) }
        invAdd(inv, obj(recipe.product), recipe.productCount)
        if (recipe.station == Station.Needle) {
            useThread()
        }
        if (recipe.xp > 0) {
            statAdvance(stats.crafting, recipe.xp * xpMods.get(player, stats.crafting))
        }
        spam(messageFor(recipe, name))

        val remaining = job.remaining - 1
        if (remaining > 0 && maxActions(recipe) > 0) {
            weakQueue(CraftingQueues.crafting, recipe.ticks, CraftJob(recipe, remaining))
        }
    }

    private fun ProtectedAccess.useThread() {
        val uses = (threadUses[player] ?: 0) + 1
        if (uses >= THREAD_USES) {
            invDel(inv, CraftingObjs.thread, 1)
            mes("You use up one of your reels of thread.")
            threadUses[player] = 0
        } else {
            threadUses[player] = uses
        }
    }

    private fun messageFor(recipe: Recipe, name: String): String =
        when (recipe.station) {
            Station.Chisel -> "You cut the ${name.lowercase()}."
            Station.Spin -> "You spin the ${materialName(recipe)} into ${article(name)}."
            Station.Oven -> "You remove the ${name.lowercase()} from the oven."
            Station.Combine ->
                if (recipe.materials.first().obj == "clay") {
                    "You mix the clay and water. You now have some soft, workable clay."
                } else {
                    "You attach the orb to the battlestaff."
                }
            else -> "You make ${if (recipe.productCount > 1) "${recipe.productCount} x $name" else article(name)}."
        }

    /** Whole actions the inventory allows (materials, plus a consumed tool: water, wool). */
    private fun ProtectedAccess.maxActions(recipe: Recipe): Int {
        var max = recipe.materials.minOf { invTotal(inv, obj(it.obj)) / it.count }
        if (recipe.station in CONSUMED_TOOL) {
            max = minOf(max, invTotal(inv, obj(checkNotNull(recipe.tool))))
        }
        return max
    }

    private fun objOrNull(name: String?): ObjType? = name?.let { CraftingObjs.byName.getValue(it) }

    private fun obj(name: String): ObjType = CraftingObjs.byName.getValue(name)

    private fun productName(recipe: Recipe): String = objTypes[obj(recipe.product)].name

    private fun materialName(recipe: Recipe): String =
        objTypes[obj(recipe.materials.first().obj)].name.lowercase()

    private fun article(name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith("s") && !lower.endsWith("ss")) {
            return lower
        }
        return if (lower.first() in "aeiou") "an $lower" else "a $lower"
    }

    private data class CraftJob(val recipe: Recipe, val remaining: Int)

    private companion object {
        /** Skillmulti item slots in the rev 243 interface (`a`-`r`). */
        const val MAX_PRODUCTS = 18

        const val THREAD_USES = 5

        /** Stations whose "tool" is used up: water container (soft clay), orb, ball of wool. */
        val CONSUMED_TOOL = setOf(Station.Combine, Station.Stringing)

        val HELD =
            setOf(Station.Needle, Station.Chisel, Station.Pipe, Station.Stringing, Station.Combine)

        val STATION_OPS =
            mapOf(
                Station.Spin to listOf("Spin"),
                Station.Loom to listOf("Weave"),
                Station.Wheel to listOf("Form"),
                Station.Oven to listOf("Fire"),
            )
    }
}
