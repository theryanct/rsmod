package org.rsmod.content.skills.fletching

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fletchingLvl
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.script.onOpHeldLabel
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
        // "Fletch" on logs (tool-belt material op): the knife may be carried or on the belt.
        for ((key, recipes) in byPair) {
            val material =
                when (KNIFE) {
                    key.first -> key.second
                    key.second -> key.first
                    else -> continue
                }
            onOpHeldLabel(objTypes, obj(material), "Fletch") { fletchWithKnife(recipes) }
        }
        onPlayerQueueWithArgs<FletchJob>(FletchingQueues.fletching) { fletch(it.args) }
    }

    /** The two inventory objs used on each other to start [this] recipe. */
    private fun Recipe.trigger(): Pair<String, String> =
        when (kind) {
            Kind.Knife -> KNIFE to materials[0].obj
            Kind.Chisel -> CHISEL to materials[0].obj
            Kind.String,
            Kind.Hammer,
            Kind.Combine -> materials[0].obj to materials[1].obj
        }

    private fun obj(name: String): ObjType =
        when (name) {
            KNIFE -> FletchingObjs.knife
            CHISEL -> FletchingObjs.chisel
            else -> FletchingObjs.byName.getValue(name)
        }

    /**
     * OSRS skillmulti chatbox for the recipes that share this item-on-item pair (e.g. knife on
     * logs). Recipes the player can't make yet (level or materials) are greyed; the quantity comes
     * from the 1/5/10/X/All buttons and, for arrows/darts/bolts, counts sets.
     */
    private suspend fun ProtectedAccess.fletchWithKnife(recipes: List<Recipe>) {
        if (!player.hasTool(FletchingObjs.knife)) {
            mes("You need a knife to fletch logs.")
            return
        }
        selectRecipe(recipes)
    }

    private suspend fun ProtectedAccess.selectRecipe(recipes: List<Recipe>) {
        val shown = recipes.take(MAX_PRODUCTS)
        val products =
            shown.map { recipe ->
                val product = FletchingObjs.byName.getValue(recipe.product)
                val available = player.fletchingLvl >= recipe.level && maxActions(recipe) > 0
                SkillMultiProduct(product, productName(recipe), available)
            }
        val max = shown.maxOf { maxActions(it) }.coerceAtLeast(1)
        val choice =
            skillMulti(
                verb = verbFor(shown.first()),
                title = titleFor(shown.first()),
                products = products,
                maxQuantity = max,
            )
        val recipe = shown[choice.index]
        if (player.fletchingLvl < recipe.level) {
            mes("You need a Fletching level of ${recipe.level} to make ${productName(recipe)}.")
            return
        }
        if (maxActions(recipe) <= 0) {
            mes("You don't have the materials to make ${productName(recipe)}.")
            return
        }
        fletch(FletchJob(recipe, choice.quantity))
    }

    private fun verbFor(recipe: Recipe): Int =
        when (recipe.kind) {
            Kind.Knife,
            Kind.Chisel,
            Kind.Hammer -> SkillMultiVerb.MAKE
            Kind.String -> SkillMultiVerb.STRING
            Kind.Combine -> SkillMultiVerb.MAKE_SETS
        }

    private fun titleFor(recipe: Recipe): String =
        when (recipe.kind) {
            Kind.String -> "How many would you like to string?"
            Kind.Combine -> "How many sets would you like to make?"
            Kind.Knife -> "What would you like to make?"
            Kind.Hammer,
            Kind.Chisel -> "How many would you like to make?"
        }

    private fun ProtectedAccess.fletch(job: FletchJob) {
        val recipe = job.recipe
        if (player.fletchingLvl < recipe.level) {
            mes("You need a Fletching level of ${recipe.level} to make ${productName(recipe)}.")
            return
        }
        if (recipe.kind == Kind.Knife && !player.hasTool(FletchingObjs.knife)) {
            mes("You need a knife to fletch logs.")
            return
        }
        if (recipe.kind == Kind.Hammer && invTotal(inv, FletchingObjs.hammer) <= 0) {
            mes("You need a hammer to attach the limbs.")
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
        const val CHISEL = "chisel"

        /** Skillmulti item slots named in the rev 233 interface (18 after the 243 cutover). */
        const val MAX_PRODUCTS = 10
    }
}
