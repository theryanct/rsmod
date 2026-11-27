package org.rsmod.content.skills.fletching

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fletchingLvl
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.fletching.FletchingData.Kind
import org.rsmod.content.skills.fletching.FletchingData.Recipe
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fletching from OSRS Wiki recipe data (levels, xp, ticks):
 * - knife on logs -> arrow shafts, unstrung bows, crossbow stocks, shields (menu of products);
 * - bow string on an unstrung bow -> strung bow;
 * - sets: arrow shafts + feathers, headless arrows + arrowtips, dart tips + feathers, unfinished
 *   bolts + feathers - up to one set (15 or 10) per action, partial sets allowed.
 *
 * Quantity uses the menu dialog (1/5/10/X/All) like smelting until a shared skillmulti (make-X)
 * helper exists. One action per recipe tick count from a weak queue, so moving or clicking
 * something else stops it.
 */
class Fletching
@Inject
constructor(private val objTypes: ObjTypeList, private val xpMods: XpModifiers) : PluginScript() {
    override fun ScriptContext.startup() {
        val byPair = LinkedHashMap<Pair<String, String>, MutableList<Recipe>>()
        for (recipe in FletchingData.recipes) {
            val (a, b) = recipe.trigger()
            val key = if (a <= b) a to b else b to a
            byPair.getOrPut(key) { mutableListOf() }.add(recipe)
        }
        for ((key, recipes) in byPair) {
            onOpHeldU(obj(key.first), obj(key.second)) { selectRecipe(recipes) }
        }
        onPlayerQueueWithArgs<FletchJob>(FletchingQueues.fletching) { fletch(it.args) }
    }

    /** The two inventory objs used on each other to start [this] recipe. */
    private fun Recipe.trigger(): Pair<String, String> =
        when (kind) {
            Kind.Knife -> KNIFE to materials[0].obj
            Kind.String,
            Kind.Combine -> materials[0].obj to materials[1].obj
        }

    private fun obj(name: String): ObjType =
        if (name == KNIFE) FletchingObjs.knife else FletchingObjs.byName.getValue(name)

    private suspend fun ProtectedAccess.selectRecipe(recipes: List<Recipe>) {
        val recipe =
            if (recipes.size == 1) {
                recipes.single()
            } else {
                val names = recipes.map { productName(it) }
                recipes[menu("What would you like to make?", hotkeys = true, choices = names)]
            }
        if (player.fletchingLvl < recipe.level) {
            mes("You need a Fletching level of ${recipe.level} to make ${productName(recipe)}.")
            return
        }
        val max = maxActions(recipe)
        if (max <= 0) {
            mes("You don't have the materials to make ${productName(recipe)}.")
            return
        }
        val quantity = selectQuantity(max) ?: return
        fletch(FletchJob(recipe, quantity))
    }

    private suspend fun ProtectedAccess.selectQuantity(max: Int): Int? {
        if (max <= 1) {
            return 1
        }
        val options = listOf("1", "5", "10", "X", "All")
        return when (menu("How many would you like to make?", hotkeys = true, choices = options)) {
            0 -> 1
            1 -> 5
            2 -> 10
            3 -> countDialog().takeIf { it > 0 }
            else -> max
        }
    }

    private fun ProtectedAccess.fletch(job: FletchJob) {
        val recipe = job.recipe
        if (player.fletchingLvl < recipe.level) {
            mes("You need a Fletching level of ${recipe.level} to make ${productName(recipe)}.")
            return
        }
        if (recipe.kind == Kind.Knife && invTotal(inv, FletchingObjs.knife) <= 0) {
            mes("You need a knife to fletch logs.")
            return
        }
        val units = unitsPerAction(recipe)
        if (units <= 0) {
            mes("You don't have the materials to make ${productName(recipe)}.")
            return
        }

        val seqName = recipe.anim
        if (seqName != null) {
            anim(FletchingSeqs.byName.getValue(seqName))
        }
        val product = FletchingObjs.byName.getValue(recipe.product)
        val xp: Double
        val made: Int
        if (recipe.kind == Kind.Combine) {
            // A set uses `count` of each material; partial sets use `units` of each.
            val setSize = recipe.materials[0].count
            for (material in recipe.materials) {
                invDel(inv, obj(material.obj), units)
            }
            made = recipe.productCount * units / setSize
            xp = recipe.xp * units / setSize
        } else {
            for (material in recipe.materials) {
                invDel(inv, obj(material.obj), material.count)
            }
            made = recipe.productCount
            xp = recipe.xp
        }
        invAdd(inv, product, made)
        statAdvance(stats.fletching, xp * xpMods.get(player, stats.fletching))
        spam("You make ${if (made > 1) "$made x " else ""}${productName(recipe)}.")

        val remaining = job.remaining - 1
        if (remaining > 0 && unitsPerAction(recipe) > 0) {
            weakQueue(FletchingQueues.fletching, recipe.ticks, FletchJob(recipe, remaining))
        }
    }

    /** Materials for one action: combine recipes make up to a full set, others need everything. */
    private fun ProtectedAccess.unitsPerAction(recipe: Recipe): Int {
        if (recipe.kind == Kind.Combine) {
            val setSize = recipe.materials[0].count
            return recipe.materials.minOf { invTotal(inv, obj(it.obj)) }.let { min(it, setSize) }
        }
        val enough = recipe.materials.all { invTotal(inv, obj(it.obj)) >= it.count }
        return if (enough) 1 else 0
    }

    private fun ProtectedAccess.maxActions(recipe: Recipe): Int {
        if (recipe.kind == Kind.Combine) {
            val setSize = recipe.materials[0].count
            val units = recipe.materials.minOf { invTotal(inv, obj(it.obj)) }
            return (units + setSize - 1) / setSize
        }
        return recipe.materials.minOf { invTotal(inv, obj(it.obj)) / it.count }
    }

    private fun productName(recipe: Recipe): String =
        objTypes[FletchingObjs.byName.getValue(recipe.product)].name

    private data class FletchJob(val recipe: Recipe, val remaining: Int)

    private companion object {
        const val KNIFE = "knife"
    }
}
