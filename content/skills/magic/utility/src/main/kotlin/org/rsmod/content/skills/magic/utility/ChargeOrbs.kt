package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.CastResult
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLocT
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.spot.SpotanimType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Charge Water/Earth/Fire/Air Orb (OSRS Wiki): cast the spell on its obelisk with an unpowered orb
 * in the inventory; runes, level and xp come from the spell registry. One orb per cast (OSRS
 * offers a Make-X after the first cast; not yet).
 */
class ChargeOrbs
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private class Orb(
        val obelisk: LocType,
        val button: ComponentType,
        val spell: ObjType,
        val orb: ObjType,
        val spot: SpotanimType,
        val name: String,
    )

    private val orbs by lazy {
        listOf(
            Orb(OrbLocs.water, OrbComponents.water, OrbObjs.spell_water, OrbObjs.water_orb, OrbSpots.water, "water"),
            Orb(OrbLocs.earth, OrbComponents.earth, OrbObjs.spell_earth, OrbObjs.earth_orb, OrbSpots.earth, "earth"),
            Orb(OrbLocs.fire, OrbComponents.fire, OrbObjs.spell_fire, OrbObjs.fire_orb, OrbSpots.fire, "fire"),
            Orb(OrbLocs.air, OrbComponents.air, OrbObjs.spell_air, OrbObjs.air_orb, OrbSpots.air, "air"),
        )
    }

    override fun ScriptContext.startup() {
        for (orb in orbs) {
            onOpLocT(orb.obelisk, orb.button) { charge(orb) }
            // Casting a charge spell on the wrong obelisk.
            for (other in orbs) {
                if (other !== orb) onOpLocT(other.obelisk, orb.button) { mes("You can't cast that spell on this obelisk.") }
            }
        }
    }

    private suspend fun ProtectedAccess.charge(orb: Orb) {
        if (OrbObjs.unpowered !in inv) {
            mes("You need an unpowered orb to charge.")
            return
        }
        val spell = spells.getObjSpell(orb.spell) ?: return
        if (runes.attemptCast(player, spell) is CastResult.Failure) return
        anim(OrbSeqs.cast)
        spotanim(orb.spot, height = 92)
        invDel(inv, OrbObjs.unpowered)
        invAdd(inv, orb.orb)
        statAdvance(stats.magic, spell.castXp * xpMods.get(player, stats.magic))
        mes("You charge the orb with the power of ${orb.name}.")
        delay(6)
    }
}

internal object OrbLocs : LocReferences() {
    val water = find("obelisk_water")
    val earth = find("obelisk_earth")
    val fire = find("obelisk_fire")
    val air = find("obelisk_air")
}

internal object OrbComponents : ComponentReferences() {
    val water = find("magic_spellbook:charge_water_orb")
    val earth = find("magic_spellbook:charge_earth_orb")
    val fire = find("magic_spellbook:charge_fire_orb")
    val air = find("magic_spellbook:charge_air_orb")
}

internal object OrbObjs : ObjReferences() {
    val unpowered = find("stafforb")
    val water_orb = find("water_orb")
    val earth_orb = find("earth_orb")
    val fire_orb = find("fire_orb")
    val air_orb = find("air_orb")
    val spell_water = find("56_charge_water_orb")
    val spell_earth = find("60_charge_earth_orb")
    val spell_fire = find("63_charge_fire_orb")
    val spell_air = find("66_charge_air_orb")
}

internal object OrbSeqs : SeqReferences() {
    val cast = find("human_castchargeorb")
}

internal object OrbSpots : SpotanimReferences() {
    val water = find("chargewaterorb_casting")
    val earth = find("chargeearthorb_casting")
    val fire = find("chargefireorb_casting")
    val air = find("chargewindorb_casting")
}
