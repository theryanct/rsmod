package org.rsmod.content.skills.magic.spell.attacks.standard

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isFailure
import org.rsmod.api.config.refs.categories
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.projanims
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.spells.attack.SpellAttack
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackMap
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.seq.SeqType
import org.rsmod.game.type.spot.SpotanimType
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.synth.SynthType

/**
 * Standard spellbook curses: Confuse (attack), Weaken (strength) and Curse (defence), each
 * lowering the target's stat by 5% (OSRS Wiki). As in OSRS the spell can't be cast while the
 * stat is already lowered ("Your foe's ... has already been weakened."), which is checked before
 * any runes are used. A successful cast gives only the spell's base xp; a splash plays the
 * splash effect.
 */
class CurseSpells @Inject constructor(private val objTypes: ObjTypeList) : SpellAttackMap {
    override fun SpellAttackRepository.register(manager: SpellAttackManager) {
        register(
            spell = objs.spell_confuse,
            attack =
                CurseSpellAttack(
                    manager = manager,
                    objTypes = objTypes,
                    staffAnim = CurseSeqs.confuse_staff,
                    unarmedAnim = CurseSeqs.confuse,
                    launch = CurseSpots.confuse_casting,
                    travel = CurseSpots.confuse_travel,
                    impact = CurseSpots.confuse_impact,
                    castSound = synths.confuse_cast_and_fire,
                    hitSound = synths.confuse_hit,
                    stat = Drained.Attack,
                ),
        )
        register(
            spell = objs.spell_weaken,
            attack =
                CurseSpellAttack(
                    manager = manager,
                    objTypes = objTypes,
                    staffAnim = CurseSeqs.weaken_staff,
                    unarmedAnim = CurseSeqs.weaken,
                    launch = CurseSpots.weaken_casting,
                    travel = CurseSpots.weaken_travel,
                    impact = CurseSpots.weaken_impact,
                    castSound = synths.weaken_all,
                    hitSound = synths.weaken_all,
                    stat = Drained.Strength,
                ),
        )
        register(
            spell = objs.spell_curse,
            attack =
                CurseSpellAttack(
                    manager = manager,
                    objTypes = objTypes,
                    staffAnim = CurseSeqs.curse_staff,
                    unarmedAnim = CurseSeqs.curse,
                    launch = CurseSpots.curse_casting,
                    travel = CurseSpots.curse_travel,
                    impact = CurseSpots.curse_impact,
                    castSound = synths.curse_cast_and_fire,
                    hitSound = synths.curse_hit,
                    stat = Drained.Defence,
                ),
        )
    }

    private enum class Drained(val noun: String) {
        Attack("attack"),
        Strength("strength"),
        Defence("defence"),
    }

    private class CurseSpellAttack(
        private val manager: SpellAttackManager,
        private val objTypes: ObjTypeList,
        private val staffAnim: SeqType,
        private val unarmedAnim: SeqType,
        private val launch: SpotanimType,
        private val travel: SpotanimType,
        private val impact: SpotanimType,
        private val castSound: SynthType,
        private val hitSound: SynthType,
        private val stat: Drained,
    ) : SpellAttack {
        override suspend fun ProtectedAccess.attack(target: Npc, attack: CombatAttack.Spell) {
            if (target.current() < target.base()) {
                alreadyLowered()
                return
            }
            if (cast(target, attack)) {
                target.lower()
            }
        }

        override suspend fun ProtectedAccess.attack(target: Player, attack: CombatAttack.Spell) {
            val type = stat.type()
            if (target.stat(type) < target.statBase(type)) {
                alreadyLowered()
                return
            }
            if (cast(target, attack)) {
                target.statSub(type, constant = 0, percent = DRAIN_PERCENT)
            }
        }

        private fun ProtectedAccess.alreadyLowered() {
            mes("Your foe's ${stat.noun} has already been weakened.")
            manager.stopCombat(this)
        }

        /** Casts the spell at [target]; `true` when it lands (not a splash or a failed cast). */
        private fun ProtectedAccess.cast(
            target: PathingEntity,
            attack: CombatAttack.Spell,
        ): Boolean {
            val castResult = manager.attemptCast(this, attack)
            if (castResult.isFailure()) {
                return false
            }
            val weapon = objTypes.getOrNull(attack.weapon)
            val staff = weapon != null && weapon.isCategoryType(categories.staff)
            anim(if (staff) staffAnim else unarmedAnim)
            spotanim(launch, height = 92)

            val proj = manager.spawnProjectile(this, target, travel, projanims.magic_spell)
            val (serverDelay, clientDelay) = proj.durations
            if (manager.rollSplash(this, target, attack, castResult)) {
                manager.playSplashFx(this, target, clientDelay, castSound, soundRadius = 8)
                manager.queueSplashHit(this, target, attack.spell.obj, clientDelay, serverDelay)
                return false
            }
            manager.playHitFx(
                source = this,
                target = target,
                clientDelay = clientDelay,
                castSound = castSound,
                soundRadius = 8,
                hitSpot = impact,
                hitSpotHeight = 124,
                hitSound = hitSound,
            )
            return true
        }

        private fun Drained.type(): StatType =
            when (this) {
                Drained.Attack -> stats.attack
                Drained.Strength -> stats.strength
                Drained.Defence -> stats.defence
            }

        private fun Npc.current(): Int =
            when (stat) {
                Drained.Attack -> attackLvl
                Drained.Strength -> strengthLvl
                Drained.Defence -> defenceLvl
            }

        private fun Npc.base(): Int =
            when (stat) {
                Drained.Attack -> baseAttackLvl
                Drained.Strength -> baseStrengthLvl
                Drained.Defence -> baseDefenceLvl
            }

        private fun Npc.lower() {
            val lowered = current() - maxOf(1, base() * DRAIN_PERCENT / 100)
            val value = maxOf(0, lowered)
            when (stat) {
                Drained.Attack -> attackLvl = value
                Drained.Strength -> strengthLvl = value
                Drained.Defence -> defenceLvl = value
            }
        }
    }

    private companion object {
        const val DRAIN_PERCENT = 5
    }
}

internal object CurseSeqs : SeqReferences() {
    val confuse = find("human_castconfuse")
    val confuse_staff = find("human_castconfuse_staff")
    val weaken = find("human_castweaken")
    val weaken_staff = find("human_castweaken_staff")
    val curse = find("human_castcurse")
    val curse_staff = find("human_castcurse_staff")
}

internal object CurseSpots : SpotanimReferences() {
    val confuse_casting = find("confuse_casting")
    val confuse_travel = find("confuse_travel")
    val confuse_impact = find("confuse_impact")
    val weaken_casting = find("weaken_casting")
    val weaken_travel = find("weaken_travel")
    val weaken_impact = find("weaken_impact")
    val curse_casting = find("curse_casting")
    val curse_travel = find("curse_travel")
    val curse_impact = find("curse_impact")
}
