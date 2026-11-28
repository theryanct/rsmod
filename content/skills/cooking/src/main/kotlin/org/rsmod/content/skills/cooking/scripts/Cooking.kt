package org.rsmod.content.skills.cooking.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.cookingLvl
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.cooking.configs.cooking_objs
import org.rsmod.content.skills.cooking.configs.cooking_seqs
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

// Not yet: make-x (skillmulti) dialog (cooks the whole inventory instead), Lumbridge castle range
// bonus (needs Cook's Assistant), Hosidius kitchen, cooking gauntlets, wines/pies/pizzas/etc.
class Cooking
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var ranges = 0
        var fires = 0
        for (type in locTypes.values) {
            val heat = type.heatSource() ?: continue
            for (recipe in recipes) {
                if (recipe.rangeOnly && heat == HeatSource.Fire) {
                    onOpLocU(type, recipe.raw) { mes("This can only be cooked on a range.") }
                    continue
                }
                onOpLocU(type, recipe.raw) { cookAll(it.loc, it.type, heat, recipe) }
            }
            if (heat == HeatSource.Range) ranges++ else fires++
        }
        logger.info { "Cooking enabled on $ranges range and $fires fire loc types." }
    }

    private fun UnpackedLocType.heatSource(): HeatSource? =
        when (name.lowercase()) {
            "range",
            "cooking range",
            "stove" -> HeatSource.Range
            "fire" -> HeatSource.Fire
            else -> null
        }

    private suspend fun ProtectedAccess.cookAll(
        loc: BoundLocInfo,
        locType: UnpackedLocType,
        heat: HeatSource,
        recipe: Recipe,
    ) {
        if (player.cookingLvl < recipe.level) {
            mes("You need a Cooking level of ${recipe.level} to cook this.")
            return
        }
        // OSRS skillmulti "How many would you like to cook?" when there is more than one to cook.
        val held = invTotal(inv, recipe.raw)
        var remaining =
            if (held > 1) {
                val cooked = objTypes[recipe.cooked]
                skillMulti(
                        verb = SkillMultiVerb.COOK,
                        title = "How many would you like to cook?",
                        products = listOf(SkillMultiProduct(cooked, cooked.name, available = true)),
                        maxQuantity = held,
                    )
                    .quantity
            } else {
                1
            }
        // Cook one item every 4 ticks until the chosen amount is done, the raw food runs out, the
        // fire burns out, or the player does something else (which cancels this script).
        while (remaining > 0 && recipe.raw in inv) {
            remaining--
            if (locRepo.findExact(loc.coords, locType) == null) {
                return
            }
            faceSquare(loc.coords)
            anim(if (heat == HeatSource.Range) cooking_seqs.human_cooking else cooking_seqs.human_firecooking)
            delay(COOK_TICKS)
            if (recipe.raw !in inv) {
                return
            }
            val success = statRandom(stats.cooking, recipe.low, recipe.high, invisibleLvls)
            val product = if (success) recipe.cooked else recipe.burnt
            val removed = invDel(inv, recipe.raw, count = 1)
            if (removed.failure) {
                return
            }
            invAdd(inv, product)
            if (success) {
                statAdvance(stats.cooking, recipe.xp * xpMods.get(player, stats.cooking))
                spam(recipe.successMessage)
            } else {
                spam(recipe.burnMessage)
            }
        }
    }

    private enum class HeatSource {
        Range,
        Fire,
    }

    private class Recipe(
        val raw: ObjType,
        val cooked: ObjType,
        val burnt: ObjType,
        val level: Int,
        val xp: Double,
        /** OSRS Wiki skilling-success curve (fire/range). */
        val low: Int,
        val high: Int,
        val successMessage: String,
        val burnMessage: String,
        val rangeOnly: Boolean = false,
    )

    private companion object {
        const val COOK_TICKS = 4

        val recipes =
            listOf(
                Recipe(
                    cooking_objs.raw_shrimps, cooking_objs.shrimps, cooking_objs.burnt_shrimp,
                    level = 1, xp = 30.0, low = 128, high = 512,
                    successMessage = "You successfully cook some shrimps.",
                    burnMessage = "You accidentally burn the shrimps.",
                ),
                Recipe(
                    cooking_objs.raw_anchovies, cooking_objs.anchovies, cooking_objs.burnt_anchovies,
                    level = 1, xp = 30.0, low = 128, high = 512,
                    successMessage = "You successfully cook some anchovies.",
                    burnMessage = "You accidentally burn the anchovies.",
                ),
                Recipe(
                    cooking_objs.raw_sardine, cooking_objs.sardine, cooking_objs.burnt_sardine,
                    level = 1, xp = 40.0, low = 118, high = 492,
                    successMessage = "You successfully cook a sardine.",
                    burnMessage = "You accidentally burn the sardine.",
                ),
                Recipe(
                    cooking_objs.raw_herring, cooking_objs.herring, cooking_objs.burnt_herring,
                    level = 5, xp = 50.0, low = 108, high = 472,
                    successMessage = "You successfully cook a herring.",
                    burnMessage = "You accidentally burn the herring.",
                ),
                Recipe(
                    cooking_objs.bread_dough, cooking_objs.bread, cooking_objs.burnt_bread,
                    level = 1, xp = 40.0, low = 118, high = 492,
                    successMessage = "You remove the bread from the oven.",
                    burnMessage = "You accidentally burn the bread.",
                    rangeOnly = true,
                ),
            )
    }
}
