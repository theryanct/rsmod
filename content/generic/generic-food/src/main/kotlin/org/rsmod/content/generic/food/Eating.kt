package org.rsmod.content.generic.food

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.max
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.content.generic.food.FoodData.Food
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Eating and drinking food (OSRS Wiki "Food/All food" heals, `food.tsv`).
 *
 * - Every food obj with an "Eat" or "Drink" op is bound by label (ops already bound elsewhere
 *   are left alone).
 * - 3-tick eat delay per player; combo food (karambwan) has its own delay so it can be eaten in
 *   the same tick as other food.
 * - Eating pushes back the next attack: the attack/action delay becomes at least `now + 3` (2 for
 *   combo food) and, if an attack was already pending, is extended by that amount.
 * - Multi-bite foods leave their next bite in the same slot (pie -> half pie -> pie dish, pizza
 *   -> 1/2 pizza, cake -> 2/3 cake -> slice), drinks leave their jug/glass/cup/bowl.
 */
class Eating @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    private val logger = InlineLogger()

    /** Map clock of each player's next allowed normal / combo bite, by player uid. */
    private val nextFood = HashMap<Int, Int>()
    private val nextCombo = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        var bound = 0
        for (food in FoodData.foods) {
            val type = FoodObjs.byName.getValue(food.obj)
            val label = if (food.drink) "drink" else "eat"
            val op = objTypes[type].iop.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
            if (op == 0 || eventBus.contains(opEvent(op), type.id)) continue
            when (op) {
                1 -> onOpHeld1(type) { eat(food, it.slot, it.type) }
                2 -> onOpHeld2(type) { eat(food, it.slot, it.type) }
                3 -> onOpHeld3(type) { eat(food, it.slot, it.type) }
                4 -> onOpHeld4(type) { eat(food, it.slot, it.type) }
                5 -> onOpHeld5(type) { eat(food, it.slot, it.type) }
            }
            bound++
        }
        logger.info { "Eating: bound $bound of ${FoodData.foods.size} food objs." }
    }

    private fun opEvent(op: Int): Class<out HeldObjEvents> =
        when (op) {
            1 -> HeldObjEvents.Op1::class.java
            2 -> HeldObjEvents.Op2::class.java
            3 -> HeldObjEvents.Op3::class.java
            4 -> HeldObjEvents.Op4::class.java
            else -> HeldObjEvents.Op5::class.java
        }

    private fun ProtectedAccess.eat(food: Food, slot: Int, type: UnpackedObjType) {
        val uid = player.uid.packed
        val delays = if (food.combo) nextCombo else nextFood
        if (mapClock < (delays[uid] ?: 0)) {
            return
        }
        delays[uid] = mapClock + EAT_DELAY
        val attackDelay = if (food.combo) COMBO_ATTACK_DELAY else EAT_ATTACK_DELAY
        player.actionDelay =
            if (player.actionDelay >= mapClock) {
                player.actionDelay + attackDelay
            } else {
                mapClock + attackDelay
            }

        invDel(inv, type, 1, slot = slot)
        val next = food.next
        if (next != null) {
            invAdd(inv, FoodObjs.byName.getValue(next), slot = slot, strict = false)
        }
        anim(FoodSeqs.eat)
        val before = player.hitpoints
        if (food.heal > 0) {
            statHeal(stats.hitpoints, food.heal, 0)
        }
        val name = type.name.lowercase()
        mes(if (food.drink) "You drink the $name." else "You eat the $name.")
        if (player.hitpoints > max(before, 0) && !food.drink) {
            mes("It heals some health.")
        }
    }

    private companion object {
        const val EAT_DELAY = 3
        const val EAT_ATTACK_DELAY = 3
        const val COMBO_ATTACK_DELAY = 2
    }
}

internal object FoodObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        FoodData.foods
            .flatMap { listOfNotNull(it.obj, it.next) }
            .distinct()
            .associateWith { find(it) }
}

internal object FoodSeqs : SeqReferences() {
    val eat = find("human_eat")
}
