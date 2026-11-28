package org.rsmod.content.skills.smithing.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.skills.smithing.configs.SmithingSeqs
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Cannonballs (OSRS Wiki "Cannonball"): use a steel bar (or the ammo mould) on a furnace with the
 * ammo mould carried; 35 Smithing, 4 cannonballs and 25.6 xp per bar, one bar every
 * [TICKS_PER_BAR] ticks, quantity from the skillmulti chatbox. With the QuestGates toggle on,
 * Dwarf Cannon must be complete (client quest varp `mcannon` >= [DWARF_CANNON_COMPLETE]).
 * Granite cannonballs are not included yet.
 */
class Cannonballs
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    override fun ScriptContext.startup() {
        // Same furnaces Smelting binds: any loc named "...furnace" with a Smelt op.
        for (type in locTypes.values) {
            if (!type.name.contains("furnace", ignoreCase = true)) continue
            val exact = type.name.equals("Furnace", ignoreCase = true)
            val smelts =
                type.op.any { it.equals("Smelt", ignoreCase = true) || (exact && it.equals("Use", true)) }
            if (!smelts) continue
            onOpLocU(type, CannonballObjs.steel_bar) { choose() }
            onOpLocU(type, CannonballObjs.ammo_mould) { choose() }
        }
        onPlayerQueueWithArgs<Int>(CannonballQueues.make) { make(it.args) }
    }

    private suspend fun ProtectedAccess.choose() {
        if (!canMake()) return
        val bars = invTotal(inv, CannonballObjs.steel_bar)
        val ball = CannonballObjs.cannonball
        val choice =
            skillMulti(
                verb = SkillMultiVerb.MAKE,
                title = "How many bars do you want to make into cannonballs?",
                products = listOf(SkillMultiProduct(ball, objTypes[ball].name, available = true)),
                maxQuantity = bars.coerceIn(1, MAX_MAKE),
            )
        make(choice.quantity)
    }

    private fun ProtectedAccess.canMake(): Boolean {
        if (player.isFeatureOn(Feature.QuestGates) && vars[CannonballVarps.dwarf_cannon] < DWARF_CANNON_COMPLETE) {
            mes("You need to complete Dwarf Cannon to make cannonballs.")
            return false
        }
        if (player.smithingLvl < LEVEL) {
            mes("You need a Smithing level of $LEVEL to make cannonballs.")
            return false
        }
        if (CannonballObjs.ammo_mould !in inv) {
            mes("You need an ammo mould to make cannonballs.")
            return false
        }
        if (CannonballObjs.steel_bar !in inv) {
            mes("You need steel bars to make cannonballs.")
            return false
        }
        return true
    }

    private fun ProtectedAccess.make(remaining: Int) {
        if (!canMake()) return
        anim(SmithingSeqs.furnace)
        spam("You heat the steel bar into a liquid state.")
        invDel(inv, CannonballObjs.steel_bar, 1)
        invAdd(inv, CannonballObjs.cannonball, BALLS_PER_BAR)
        statAdvance(stats.smithing, XP_PER_BAR * xpMods.get(player, stats.smithing))
        spam("You remove the cannonballs from the mould.")
        if (remaining > 1 && CannonballObjs.steel_bar in inv) {
            weakQueue(CannonballQueues.make, TICKS_PER_BAR, remaining - 1)
        }
    }

    private companion object {
        const val LEVEL = 35
        const val BALLS_PER_BAR = 4
        const val XP_PER_BAR = 25.6
        const val TICKS_PER_BAR = 5
        const val MAX_MAKE = 28
        /** Dwarf Cannon quest varp value once the quest is complete. */
        const val DWARF_CANNON_COMPLETE = 11
    }
}

internal object CannonballObjs : ObjReferences() {
    val steel_bar = find("steel_bar")
    val ammo_mould = find("ammo_mould")
    val cannonball = find("mcannonball")
}

internal object CannonballQueues : QueueReferences() {
    /** Custom queue (65504, skilling range). */
    val make = find("smithing_cannonballs")
}

internal object CannonballVarps : VarpReferences() {
    /** Client varp 0: Dwarf Cannon quest progress. */
    val dwarf_cannon = find("mcannon")
}
