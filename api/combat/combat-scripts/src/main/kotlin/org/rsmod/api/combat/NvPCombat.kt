package org.rsmod.api.combat

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.npc.attackRate
import org.rsmod.api.combat.commons.player.combatPlayDefendAnim
import org.rsmod.api.combat.commons.player.queueCombatRetaliate
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.formulas.MaxHitFormulae
import org.rsmod.api.combat.npc.attackingPlayer
import org.rsmod.api.combat.npc.lastAttack
import org.rsmod.api.combat.player.aggressiveNpc
import org.rsmod.api.combat.player.lastCombat
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.projanims
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.events.NpcAttackHitEvent
import org.rsmod.api.npc.isInCombat
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.spot.SpotanimType

internal class NvPCombat
@Inject
constructor(
    private val accuracy: AccuracyFormulae,
    private val maxHits: MaxHitFormulae,
    private val objTypes: ObjTypeList,
    private val eventBus: EventBus,
    private val worldRepo: WorldRepository,
) {
    fun attack(access: StandardNpcAccess, target: Player, attack: CombatAttack.NpcMelee) {
        access.attackMelee(target, attack)
    }

    fun attack(access: StandardNpcAccess, target: Player, attack: NpcRangedMagicData.Attack) {
        access.attackRangedMagic(target, attack)
    }

    /**
     * Ranged / magic npc attack (Wiki style, max hit and speed from [NpcRangedMagicData]): OSRS
     * npc ranged or magic accuracy roll vs the player's defence, a projectile from the npc to the
     * player (an arrow, or a strike/bolt/blast by max hit for casters), and a hit delayed by
     * distance like player ranged/magic. Npcs without a cache attack anim use the human bow/cast
     * anim.
     */
    private fun StandardNpcAccess.attackRangedMagic(
        target: Player,
        attack: NpcRangedMagicData.Attack,
    ) {
        if (!canAttack(target)) {
            resetMode()
            return
        }
        if (actionDelay > mapClock) {
            return
        }
        if (!npc.isInCombat()) {
            resetMode()
            return
        }
        actionDelay = mapClock + attack.speed

        val magic = attack.style == NpcRangedMagicData.Style.Magic
        val fallbackAnim = if (magic) seqs.human_caststrike else seqs.human_bow
        anim(npc.visType.paramOrNull(params.attack_anim) ?: fallbackAnim)
        npc.visType.paramOrNull(params.attack_sound)?.let(target::soundSynth)

        val (travel, impact) = projectileFor(attack)
        val proj = worldRepo.projAnim(npc, target, travel, projectileType(magic))
        val (serverDelay, clientDelay) = proj.durations

        val successfulHit =
            if (magic) {
                accuracy.rollMagicAccuracy(npc, target, random)
            } else {
                accuracy.rollRangedAccuracy(npc, target, random)
            }
        val damage = if (successfulHit) random.of(0..attack.maxHit) else 0
        if (successfulHit && impact != null) {
            target.spotanim(impact, delay = clientDelay, height = 124)
        }

        setAttackVars(target)
        target.queueCombatRetaliate(npc)
        val type = if (magic) HitType.Magic else HitType.Ranged
        target.queueHit(npc, maxOf(1, serverDelay), type, damage)
        target.combatPlayDefendAnim(objTypes)
        eventBus.publish(NpcAttackHitEvent(npc, target, type, damage))
    }

    private fun projectileType(magic: Boolean) =
        if (magic) projanims.magic_spell else projanims.arrow

    /** Travel / impact gfx: arrows for archers, a strike/bolt/blast by max hit for casters. */
    private fun projectileFor(
        attack: NpcRangedMagicData.Attack
    ): Pair<SpotanimType, SpotanimType?> {
        if (attack.style == NpcRangedMagicData.Style.Ranged) {
            return NpcProjectileSpots.arrow to null
        }
        val spells = NpcProjectileSpots.spells
        val index = ((attack.maxHit - 1).coerceAtLeast(0) / 2).coerceAtMost(spells.size - 1)
        return spells[index]
    }

    private fun StandardNpcAccess.attackMelee(target: Player, attack: CombatAttack.NpcMelee) {
        if (!canAttack(target)) {
            resetMode()
            return
        }

        // Note: We do not need to explicitly call `opplayer2` because npcs will automatically
        // repeat their last interaction until it is canceled (e.g., by changing their `npcmode`).
        if (actionDelay > mapClock) {
            return
        }

        if (!npc.isInCombat()) {
            resetMode()
            return
        }

        val attackRate = npc.attackRate()
        actionDelay = mapClock + attackRate

        val attackAnim = npc.visType.param(params.attack_anim)
        val attackSound = npc.visType.paramOrNull(params.attack_sound)

        anim(attackAnim)
        attackSound?.let(target::soundSynth)

        val successfulHit = accuracy.rollMeleeAccuracy(npc, target, attack.type, random)

        val damage =
            if (successfulHit) {
                val maxHit = maxHits.getMeleeMaxHit(npc, target, attack.type)
                random.of(0..maxHit)
            } else {
                0
            }

        setAttackVars(target)

        // Note: Retaliation must be queued _before_ the hit. If queued after, every hit would
        // trigger the "speed-up" death mechanic, since the hit queues would no longer be the
        // last entries in the queue list at the time of processing.
        target.queueCombatRetaliate(npc)

        target.queueHit(npc, 1, HitType.Melee, damage)
        target.combatPlayDefendAnim(objTypes)
        eventBus.publish(NpcAttackHitEvent(npc, target, HitType.Melee, damage))
    }

    private fun canAttack(target: Player): Boolean {
        return target.isValidTarget()
    }

    private fun StandardNpcAccess.setAttackVars(target: Player) {
        npc.lastAttack = mapClock
        npc.attackingPlayer = target.uid
        target.lastCombat = mapClock
        target.aggressiveNpc = npc.uid
    }
}

/** Projectile gfx for npc ranged/magic attacks. */
internal object NpcProjectileSpots : SpotanimReferences() {
    val arrow = find("bronze_arrow_travel")

    /** By caster max hit, 2 per step: strikes, then bolts, then fire blast. */
    val spells: List<Pair<SpotanimType, SpotanimType?>> =
        listOf("windstrike", "waterstrike", "earthstrike", "firestrike")
            .plus(listOf("windbolt", "waterbolt", "earthbolt", "firebolt"))
            .plus("fireblast")
            .map { find("${it}_travel") to find("${it}_impact") }
}
