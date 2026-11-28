package org.rsmod.api.combat

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.npc.attackRate
import org.rsmod.api.combat.commons.player.combatPlayDefendAnim
import org.rsmod.api.combat.commons.player.queueCombatRetaliate
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.combat.npc.attackingPlayer
import org.rsmod.api.combat.npc.lastAttack
import org.rsmod.api.combat.player.aggressiveNpc
import org.rsmod.api.combat.player.lastCombat
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.npc.events.NpcAttackHitEvent
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjTypeList

/**
 * Dragonfire (OSRS Wiki "Dragonfire"), opt-in per npc through `dragonfire.tsv` (npc -> family).
 * `NvPCombatScript` asks [shouldBreathe] before its melee attack (chance 1 in the family's
 * `chance`; the wiki gives 1/6 for chromatics, the others are estimates). A breath is a typeless
 * hit whose max depends on the target's protection:
 *
 * | family    | none | anti-dragon shield | Protect from Magic | shield + PfM |
 * |-----------|------|--------------------|--------------------|--------------|
 * | chromatic |  50  |  5                 | 10                 | 5            |
 * | metallic  |  50  |  5                 | (no effect) 50     | 5            |
 * | elvarg    |  70  | 10                 | 55                 | 7            |
 *
 * The anti-dragon shield, dragonfire shield and dragonfire ward all count as "shield". The
 * target's Magic defence vs the npc's Magic accuracy only picks the message ("You manage to
 * resist some of the dragon fire!" / "You're horribly burnt by the dragon fire!"), as on the
 * wiki. Elvarg's fire also drains 10% of the target's prayer.
 *
 * Not done: antifire potions (shield + antifire = full block; antifire alone caps chromatic fire at
 * 35, super antifire blocks it) - add an [antifire] source when herblore has them; long-range
 * breath (metallic dragons / Elvarg breathe from melee distance here, like chromatics).
 */
internal class Dragonfire
@Inject
constructor(
    private val accuracy: AccuracyFormulae,
    private val objTypes: ObjTypeList,
    private val eventBus: EventBus,
) {
    private val table: Map<Int, Family> by lazy {
        DragonfireNpcs.byName.entries.associate { (name, type) ->
            type.id to DragonfireData.families.getValue(name)
        }
    }

    /** Whether this attack (in melee range) should be a breath rather than a melee hit. */
    fun shouldBreathe(access: StandardNpcAccess): Boolean {
        val family = table[access.npc.visType.id] ?: return false
        return access.random.of(1, family.chance) == 1
    }

    fun breathe(access: StandardNpcAccess, target: Player) {
        val family = table[access.npc.visType.id] ?: return
        with(access) {
            if (actionDelay > mapClock) return
            actionDelay = mapClock + npc.attackRate()

            anim(DragonfireSeqs.breath)
            spotanim(DragonfireSpots.breath, height = BREATH_HEIGHT)

            val shield = DragonfireObjs.shields.any { invTotalWorn(target, it.id) > 0 }
            val magicPrayer = target.vars[varbits.protect_from_magic] == 1
            val max = family.maxHit(shield, magicPrayer, antifire(target))
            val damage = random.of(0..max)
            val resisted = accuracy.rollMagicAccuracy(npc, target, random)
            target.mes(
                if (resisted) {
                    "You manage to resist some of the dragon fire!"
                } else {
                    "You're horribly burnt by the dragon fire!"
                }
            )
            if (family.drainsPrayer) {
                target.statSub(stats.prayer, constant = 0, percent = PRAYER_DRAIN_PERCENT)
            }

            npc.lastAttack = mapClock
            npc.attackingPlayer = target.uid
            target.lastCombat = mapClock
            target.aggressiveNpc = npc.uid
            target.queueCombatRetaliate(npc)
            target.queueHit(npc, 1, HitType.Typeless, damage)
            target.combatPlayDefendAnim(objTypes)
            eventBus.publish(NpcAttackHitEvent(npc, target, HitType.Typeless, damage))
        }
    }

    /** Antifire potion protection; none exist yet (herblore follow-up). */
    private fun antifire(@Suppress("UNUSED_PARAMETER") target: Player): Antifire = Antifire.None

    private fun invTotalWorn(player: Player, obj: Int): Int =
        player.worn.objs.sumOf { if (it?.id == obj) it.count else 0 }

    enum class Antifire {
        None,
        Regular,
        Super,
    }

    class Family(
        val unprotected: Int,
        val shield: Int,
        val magicPrayer: Int?,
        val shieldAndPrayer: Int,
        val chance: Int,
        val drainsPrayer: Boolean = false,
    ) {
        fun maxHit(shield: Boolean, prayer: Boolean, antifire: Antifire): Int {
            val usePrayer = prayer && magicPrayer != null
            val base =
                when {
                    shield && usePrayer -> shieldAndPrayer
                    shield -> this.shield
                    usePrayer -> checkNotNull(magicPrayer)
                    else -> unprotected
                }
            return when (antifire) {
                Antifire.None -> base
                Antifire.Super -> 0
                Antifire.Regular -> if (shield) 0 else base * ANTIFIRE_PERCENT / 100
            }
        }
    }

    private companion object {
        const val BREATH_HEIGHT = 92
        const val PRAYER_DRAIN_PERCENT = 10
        const val ANTIFIRE_PERCENT = 70
    }
}

/** dragonfire.tsv, loaded once. */
internal object DragonfireData {
    private val CHROMATIC =
        Dragonfire.Family(
            unprotected = 50,
            shield = 5,
            magicPrayer = 10,
            shieldAndPrayer = 5,
            chance = 6,
        )
    private val METALLIC =
        Dragonfire.Family(
            unprotected = 50,
            shield = 5,
            magicPrayer = null,
            shieldAndPrayer = 5,
            chance = 4,
        )
    private val ELVARG =
        Dragonfire.Family(
            unprotected = 70,
            shield = 10,
            magicPrayer = 55,
            shieldAndPrayer = 7,
            chance = 3,
            drainsPrayer = true,
        )

    val families: Map<String, Dragonfire.Family> by lazy {
        val stream =
            checkNotNull(Dragonfire::class.java.getResourceAsStream("scripts/dragonfire.tsv")) {
                "dragonfire.tsv missing"
            }
        stream.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
                .associate { (npc, family) ->
                    npc to
                        when (family) {
                            "chromatic" -> CHROMATIC
                            "metallic" -> METALLIC
                            "elvarg" -> ELVARG
                            else -> error("dragonfire.tsv: unknown family '$family' for $npc")
                        }
                }
        }
    }
}

internal object DragonfireNpcs : NpcReferences() {
    val byName: Map<String, NpcType> = DragonfireData.families.keys.associateWith { find(it) }
}

internal object DragonfireObjs : ObjReferences() {
    val shields =
        listOf(
            find("antidragonbreathshield"),
            find("dragonfire_shield"),
            find("dragonfire_shield_uncharged"),
            find("dragonfire_ward"),
            find("dragonfire_ward_uncharged"),
        )
}

internal object DragonfireSeqs : SeqReferences() {
    val breath = find("dragon_firebreath_attack")
}

internal object DragonfireSpots : SpotanimReferences() {
    val breath = find("firebreath_attack")
}
