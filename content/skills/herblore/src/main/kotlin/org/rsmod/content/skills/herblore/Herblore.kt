package org.rsmod.content.skills.herblore

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.poison.curePoison
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.herbloreLvl
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpHeldLabel
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.herblore.HerbloreData.Effect
import org.rsmod.events.EventBus
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Herblore PR1 (Druidic Ritual not required):
 * - Clean grimy herbs ("Clean", one per click as in OSRS; Wiki levels/xp).
 * - Unfinished potions (clean herb + vial of water) and finished potions (unfinished potion +
 *   secondary, 3 doses) via the skillmulti "Make" chatbox, one per 2 ticks from a weak queue.
 * - Drinking doses 4 -> 3 -> 2 -> 1 -> empty vial (3-tick delay): Wiki boosts (+3 +10% / super
 *   +5 +15%), restore (+10 +30%), super restore (+8 +25%, incl. prayer), prayer (+7 +25%),
 *   energy (+10% / +20% run), antipoisons cure poison and grant immunity (90 s / 6 min).
 * - Fill empty vials at any sink, fountain, well or water pump (by loc name).
 */
class Herblore
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    /** Map clock of each player's next allowed drink, by player uid. */
    private val nextDrink = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        for (herb in HerbloreData.herbs) {
            onHeldOp(obj(herb.grimy), "clean") { clean(herb) }
        }
        val water = obj(HerbloreData.VIAL_WATER)
        for (unf in HerbloreData.unfinished) {
            val recipe = Recipe(unf.unf, unf.herb, HerbloreData.VIAL_WATER, unf.level, 0.0, 1)
            onOpHeldU(obj(unf.herb), water) { selectMake(recipe) }
        }
        for (potion in HerbloreData.potions) {
            val recipe =
                Recipe(potion.product, potion.unf, potion.secondary, potion.level, potion.xp, 2)
            onOpHeldU(obj(potion.unf), obj(potion.secondary)) { selectMake(recipe) }
        }
        onPlayerQueueWithArgs<MakeJob>(HerbloreQueues.make) { make(it.args) }
        onPlayerSoftTimer(AntifireTimers.tick) { Antifire.tick(player) }
        val pestle = obj(HerbloreData.PESTLE)
        for (grind in HerbloreData.grinds) {
            onOpHeldU(pestle, obj(grind.source)) { grind(grind) }
            // "Grind" (tool-belt material op): pestle and mortar carried or on the belt.
            onOpHeldLabel(objTypes, obj(grind.source), "Grind") {
                if (player.hasTool(pestle)) {
                    grind(grind)
                } else {
                    mes("You need a pestle and mortar to grind that.")
                }
            }
        }
        for (drink in HerbloreData.drinks) {
            for ((index, dose) in drink.doses.withIndex()) {
                val next = drink.doses.getOrNull(index + 1) ?: HerbloreData.VIAL
                val left = drink.doses.size - index - 1
                onHeldOp(obj(dose), "drink") { drink(drink, dose, next, left) }
            }
        }
        bindWaterSources(eventBus)
    }

    private fun obj(name: String): ObjType = HerbloreObjs.byName.getValue(name)

    private fun ScriptContext.onHeldOp(
        type: ObjType,
        label: String,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        when (objTypes[type].iop.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> if (!isHeldOpBound(type, 1)) onOpHeld1(type) { action() }
            2 -> if (!isHeldOpBound(type, 2)) onOpHeld2(type) { action() }
            3 -> if (!isHeldOpBound(type, 3)) onOpHeld3(type) { action() }
            4 -> if (!isHeldOpBound(type, 4)) onOpHeld4(type) { action() }
            5 -> if (!isHeldOpBound(type, 5)) onOpHeld5(type) { action() }
            else -> logger.warn { "Herblore: ${objTypes[type].name} has no \"$label\" op." }
        }
    }

    /* Cleaning */

    /** Herblore needs Druidic Ritual when the QuestGates toggle is on (default off). */
    private fun ProtectedAccess.passesQuestGate(): Boolean {
        if (!player.isFeatureOn(Feature.QuestGates)) return true
        if (player.vars[HerbloreVarps.druidquest] >= DRUIDIC_RITUAL_COMPLETE) return true
        mes("You need to complete Druidic Ritual to use the Herblore skill.")
        return false
    }

    private fun ProtectedAccess.clean(herb: HerbloreData.Herb) {
        if (!passesQuestGate()) return
        if (player.herbloreLvl < herb.level) {
            mes("You need a Herblore level of ${herb.level} to clean this herb.")
            return
        }
        invDel(inv, obj(herb.grimy))
        invAdd(inv, obj(herb.clean))
        statAdvance(stats.herblore, herb.xp * xpMods.get(player, stats.herblore))
        spam("You clean the ${objTypes[obj(herb.clean)].name.lowercase()}.")
    }

    /* Mixing */

    private data class Recipe(
        val product: String,
        val first: String,
        val second: String,
        val level: Int,
        val xp: Double,
        val ticks: Int,
    )

    private data class MakeJob(val recipe: Recipe, val remaining: Int)

    private fun ProtectedAccess.maxMakes(recipe: Recipe): Int =
        min(invTotal(inv, obj(recipe.first)), invTotal(inv, obj(recipe.second)))

    private suspend fun ProtectedAccess.selectMake(recipe: Recipe) {
        if (!passesQuestGate()) return
        val product = obj(recipe.product)
        val name = objTypes[product].name
        val max = maxMakes(recipe).coerceAtLeast(1)
        val available = player.herbloreLvl >= recipe.level
        val choice =
            skillMulti(
                verb = SkillMultiVerb.MAKE,
                title = "How many do you wish to make?",
                products = listOf(SkillMultiProduct(product, name, available)),
                maxQuantity = max,
            )
        make(MakeJob(recipe, choice.quantity))
    }

    private fun ProtectedAccess.make(job: MakeJob) {
        val recipe = job.recipe
        val name = objTypes[obj(recipe.product)].name
        if (player.herbloreLvl < recipe.level) {
            mes("You need a Herblore level of ${recipe.level} to make ${name.lowercase()}.")
            return
        }
        if (maxMakes(recipe) <= 0) {
            return
        }
        anim(HerbloreSeqs.mix)
        invDel(inv, obj(recipe.first))
        invDel(inv, obj(recipe.second))
        invAdd(inv, obj(recipe.product))
        if (recipe.xp > 0) {
            statAdvance(stats.herblore, recipe.xp * xpMods.get(player, stats.herblore))
        }
        spam("You put the ingredients in the vial and make ${name.lowercase()}.")
        val remaining = job.remaining - 1
        if (remaining > 0 && maxMakes(recipe) > 0) {
            weakQueue(HerbloreQueues.make, recipe.ticks, MakeJob(recipe, remaining))
        }
    }

    /* Grinding */

    private fun ProtectedAccess.grind(grind: HerbloreData.Grind) {
        invDel(inv, obj(grind.source))
        invAdd(inv, obj(grind.product))
        anim(HerbloreSeqs.grind)
        mes("You grind the ${objTypes[obj(grind.source)].name.lowercase()} to dust.")
    }

    /* Drinking */

    private suspend fun ProtectedAccess.drink(
        drink: HerbloreData.Drink,
        dose: String,
        next: String,
        left: Int,
    ) {
        val uid = player.uid.packed
        if (mapClock < (nextDrink[uid] ?: 0)) {
            return
        }
        nextDrink[uid] = mapClock + DRINK_DELAY
        invDel(inv, obj(dose))
        invAdd(inv, obj(next))
        anim(HerbloreSeqs.drink)
        mes("You drink some of your ${drink.name}.")
        applyEffect(drink.effect)
        if (left > 0) {
            mes("You have $left dose${if (left > 1) "s" else ""} of potion left.")
        } else {
            mes("You have finished your potion.")
        }
        delay(1)
    }

    private fun ProtectedAccess.applyEffect(effect: Effect) {
        when (effect) {
            is Effect.Boost ->
                for (name in effect.stats) statBoost(stat(name), effect.constant, effect.percent)
            is Effect.Restore ->
                for (name in effect.stats) statHeal(stat(name), effect.constant, effect.percent)
            is Effect.Drain ->
                for (name in effect.stats) statDrain(stat(name), effect.constant, effect.percent)
            is Effect.Energy -> {
                val gain = constants.run_max_energy * effect.percent / 100
                player.runEnergy = min(constants.run_max_energy, player.runEnergy + gain)
            }
            is Effect.Cure -> player.curePoison(effect.immunityTicks)
            is Effect.Multi -> effect.effects.forEach { applyEffect(it) }
            is Effect.Antifire -> Antifire.drink(player, effect.superPotion, effect.minutes)
            Effect.Stamina -> Stamina.drink(player)
        }
    }

    private fun stat(name: String): StatType =
        when (name) {
            "attack" -> stats.attack
            "strength" -> stats.strength
            "defence" -> stats.defence
            "ranged" -> stats.ranged
            "magic" -> stats.magic
            "prayer" -> stats.prayer
            "hitpoints" -> stats.hitpoints
            "agility" -> stats.agility
            "fishing" -> stats.fishing
            else -> error("Unknown potion stat: $name")
        }

    /* Vial filling */

    private fun ScriptContext.bindWaterSources(bus: EventBus) {
        val vial = obj(HerbloreData.VIAL)
        var bound = 0
        for (type in locTypes.values) {
            if (type.name.trim().lowercase() !in HerbloreData.waterSources) continue
            val key = EventBus.composeLongKey(type.id, vial.id)
            if (bus.contains(LocUEvents.Op::class.java, key)) continue
            onOpLocU(type, vial) { fillVials() }
            bound++
        }
        logger.info { "Herblore: vials fill at $bound water source loc types." }
    }

    private fun ProtectedAccess.fillVials() {
        val count = invTotal(inv, obj(HerbloreData.VIAL))
        if (count <= 0) return
        anim(HerbloreSeqs.fill)
        invDel(inv, obj(HerbloreData.VIAL), count)
        invAdd(inv, obj(HerbloreData.VIAL_WATER), count)
        mes(if (count > 1) "You fill the vials with water." else "You fill the vial with water.")
    }

    private companion object {
        const val DRUIDIC_RITUAL_COMPLETE = 4
        const val DRINK_DELAY = 3
    }
}
