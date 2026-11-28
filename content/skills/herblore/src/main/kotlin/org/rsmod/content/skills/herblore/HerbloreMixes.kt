package org.rsmod.content.skills.herblore

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.herbloreLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mixes that don't fit the unf + secondary model (OSRS Wiki):
 * - Super combat potion (90, 150 xp): torstol + super attack(4) + super strength(4) + super
 *   defence(4) -> super combat(4).
 * - Stamina potion (77, 25.5 xp per dose): one amylase crystal per dose of a super energy.
 * Drinking: super combat in [HerbloreData.drinks]; stamina via [Stamina].
 */
class HerbloreMixes @Inject constructor(private val xpMods: XpModifiers) : PluginScript() {
    override fun ScriptContext.startup() {
        for (herb in listOf(MixObjs.torstol, MixObjs.torstol_unf)) {
            for (potion in listOf(MixObjs.super_attack4, MixObjs.super_strength4, MixObjs.super_defence4)) {
                onOpHeldU(herb, potion) { superCombat(herb) }
            }
        }
        for ((index, energy) in MixObjs.super_energy.withIndex()) {
            val doses = 4 - index
            onOpHeldU(MixObjs.amylase, energy) { stamina(doses, energy, MixObjs.stamina[index]) }
        }
        onPlayerSoftTimer(StaminaTimers.tick) { Stamina.tick(player) }
    }

    private fun ProtectedAccess.superCombat(herb: ObjType) {
        if (player.herbloreLvl < SUPER_COMBAT_LEVEL) {
            mes("You need a Herblore level of $SUPER_COMBAT_LEVEL to make a super combat potion.")
            return
        }
        val parts = listOf(herb, MixObjs.super_attack4, MixObjs.super_strength4, MixObjs.super_defence4)
        if (parts.any { it !in inv }) {
            mes("You need a torstol and four-dose super attack, super strength and super defence potions.")
            return
        }
        // Wiki Recipe: the four ingredients become one super combat(4), no vials returned.
        for (part in parts) invDel(inv, part)
        invAdd(inv, MixObjs.super_combat4)
        anim(HerbloreSeqs.mix)
        statAdvance(stats.herblore, SUPER_COMBAT_XP * xpMods.get(player, stats.herblore))
        mes("You mix the ingredients to make a super combat potion.")
    }

    private fun ProtectedAccess.stamina(doses: Int, energy: ObjType, product: ObjType) {
        if (player.herbloreLvl < STAMINA_LEVEL) {
            mes("You need a Herblore level of $STAMINA_LEVEL to make a stamina potion.")
            return
        }
        if (invTotal(inv, MixObjs.amylase) < doses) {
            mes("You need $doses amylase crystals to make this.")
            return
        }
        invDel(inv, MixObjs.amylase, doses)
        invDel(inv, energy)
        invAdd(inv, product)
        anim(HerbloreSeqs.mix)
        statAdvance(stats.herblore, STAMINA_XP_PER_DOSE * doses * xpMods.get(player, stats.herblore))
        mes("You mix the amylase into the potion.")
    }

    private companion object {
        const val SUPER_COMBAT_LEVEL = 90
        const val SUPER_COMBAT_XP = 150.0
        const val STAMINA_LEVEL = 77
        const val STAMINA_XP_PER_DOSE = 25.5
    }
}

/**
 * Stamina effect (Wiki "Stamina potion"): 20% run energy and 70% less run drain for 2 minutes;
 * another dose resets the timer to 2 minutes (no stacking). Uses the cache varbits the client timer and PlayerRunUpdateProcessor read
 * (`stamina_active`, `stamina_duration`, counted in [UNIT_TICKS]-tick units).
 */
internal object Stamina {
    const val UNIT_TICKS = 10
    private const val UNITS_PER_DOSE = 20

    fun drink(player: Player) {
        VarPlayerIntMapSetter.set(player, varbits.stamina_duration, UNITS_PER_DOSE)
        VarPlayerIntMapSetter.set(player, varbits.stamina_active, 1)
        player.softTimer(StaminaTimers.tick, UNIT_TICKS)
    }

    fun tick(player: Player) {
        val left = player.vars[varbits.stamina_duration] - 1
        if (left <= 0) {
            VarPlayerIntMapSetter.set(player, varbits.stamina_duration, 0)
            VarPlayerIntMapSetter.set(player, varbits.stamina_active, 0)
            player.mes("<col=8f4808>Your stamina potion has expired.</col>")
            player.clearSoftTimer(StaminaTimers.tick)
            return
        }
        VarPlayerIntMapSetter.set(player, varbits.stamina_duration, left)
        if (left == 3) player.mes("<col=8f4808>Your stamina potion is about to expire.</col>")
    }
}

internal object MixObjs : ObjReferences() {
    val torstol = find("torstol")
    val torstol_unf = find("torstolvial")
    val super_attack4 = find("4dose2attack")
    val super_strength4 = find("4dose2strength")
    val super_defence4 = find("4dose2defense")
    val super_combat4 = find("4dose2combat")
    val amylase = find("amylase")
    val super_energy = (4 downTo 1).map { find("${it}dose2energy") }
    val stamina = (4 downTo 1).map { find("${it}dosestamina") }
}

internal object StaminaTimers : TimerReferences() {
    /** Custom timer (65503, skilling range). */
    val tick = find("stamina_tick")
}
