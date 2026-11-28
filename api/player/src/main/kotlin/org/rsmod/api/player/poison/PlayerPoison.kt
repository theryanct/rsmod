package org.rsmod.api.player.poison

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.timer.TimerType
import org.rsmod.game.type.varp.VarpType

/**
 * Player poison and venom, kept in the client's `poison` varp. The encoding is the OSRS one the
 * client scripts read (`orbs_update_health`, `buff_bar_get_value`):
 * - `>= 1_000_000` ([VENOM_BASE]): envenomed (dark green orb). The next venom hit deals
 *   `(value - VENOM_BASE + 3) * 2`, capped at 20: 6, 8, 10 ... 20; each hit raises the value by one.
 * - `1 until VENOM_BASE`: poisoned with that severity; each poison hit deals `(severity + 4) / 5`
 *   and lowers the severity by one, so damage N repeats five times before dropping to N - 1.
 * - `-1 .. -38`: immune to poison for that many more [POISON_HIT_INTERVAL]-tick periods.
 * - `< -38` ([VENOM_IMMUNITY_OFFSET]): also immune to venom for `-(value + 38)` periods; the poison
 *   immunity is the full `-value` periods (anti-venoms, and antidote++'s short venom immunity).
 * - `0`: neither.
 *
 * The periodic hits and immunity countdown run on [PoisonTimers.tick] (the generic-poison content
 * script). Every setter here arms it when the value leaves 0; the script clears it at 0.
 *
 * A worn, charged serpentine helm makes the player immune to both (Wiki "Serpentine helm").
 */
public object PoisonVarps : VarpReferences() {
    public val poison: VarpType = find("poison")
}

public object PoisonTimers : TimerReferences() {
    public val tick: TimerType = find("poison_tick")
}

internal object PoisonObjs : ObjReferences() {
    val serpentine_helm_charged: ObjType = find("serpentine_helm_charged")
}

/** Ticks between poison and venom hits (18 seconds, as in OSRS). */
public const val POISON_HIT_INTERVAL: Int = 30

/** Values at or above this are venom (client `orbs_update_health`). */
public const val VENOM_BASE: Int = 1_000_000

/** Immunity values below `-VENOM_IMMUNITY_OFFSET` also protect from venom (`buff_bar_get_value`). */
public const val VENOM_IMMUNITY_OFFSET: Int = 38

public const val VENOM_MAX_DAMAGE: Int = 20

public val Player.poisonValue: Int
    get() = vars[PoisonVarps.poison]

public val Player.isVenomed: Boolean
    get() = poisonValue >= VENOM_BASE

public val Player.isPoisoned: Boolean
    get() = poisonValue in 1 until VENOM_BASE

public val Player.isPoisonImmune: Boolean
    get() = poisonValue < 0 || hasSerpentineProtection

public val Player.isVenomImmune: Boolean
    get() = poisonValue < -VENOM_IMMUNITY_OFFSET || hasSerpentineProtection

/** A worn charged serpentine helm blocks new poison and venom (it does not cure either). */
public val Player.hasSerpentineProtection: Boolean
    get() = worn[Wearpos.Hat.slot].isType(PoisonObjs.serpentine_helm_charged)

/** Damage of the next poison hit (0 when not poisoned). */
public val Player.poisonDamage: Int
    get() = if (isPoisoned) (poisonValue + 4) / 5 else 0

/** Damage of the next venom hit (0 when not envenomed). */
public val Player.venomDamage: Int
    get() =
        if (isVenomed) minOf(VENOM_MAX_DAMAGE, (poisonValue - VENOM_BASE + 3) * 2) else 0

/**
 * Poisons the player starting at [damage] per hit, unless they are immune, envenomed or already
 * poisoned at least that strongly. Returns `true` if the poison took hold (or got stronger).
 */
public fun Player.applyPoison(damage: Int): Boolean {
    val current = poisonValue
    val severity = damage * 5
    if (current >= VENOM_BASE || isPoisonImmune || severity <= current) {
        return false
    }
    setPoisonValue(severity)
    return true
}

/**
 * Envenoms the player (first hit 6, rising by 2 to 20). Venom replaces poison and ignores
 * poison-only immunity (antipoison / superantipoison); only venom immunity or a charged serpentine
 * helm stops it. Returns `false` if immune or already envenomed. Sources (Zulrah, Vorkath...) call
 * this.
 */
public fun Player.applyVenom(): Boolean {
    if (isVenomed || isVenomImmune) {
        return false
    }
    setPoisonValue(VENOM_BASE)
    return true
}

/**
 * Antipoison-style cure (Wiki "Venom": any poison cure turns venom into poison):
 * - envenomed: venom becomes poison at the venom's current damage, no immunity;
 * - otherwise: cures poison and grants immunity for [immunityTicks] (rounded down to whole
 *   periods). Existing longer immunity is kept ("antipoison no longer decreases immunity").
 *
 * Immunity of 39+ periods (antidote++: 12 minutes = 40) also grants the short venom immunity.
 */
public fun Player.curePoison(immunityTicks: Int = 0) {
    if (isVenomed) {
        setPoisonValue(venomDamage * 5)
        return
    }
    setImmunity(immunityTicks / POISON_HIT_INTERVAL)
}

/**
 * Cures venom and poison and grants venom immunity for [immunityTicks] (rounded down to whole
 * periods), plus poison immunity for 38 periods more (anti-venom: 3 periods = 36-54 s venom, ~12
 * minutes poison). Existing longer immunity is kept.
 */
public fun Player.cureVenom(immunityTicks: Int = 0) {
    setImmunity(VENOM_IMMUNITY_OFFSET + immunityTicks / POISON_HIT_INTERVAL)
}

private fun Player.setImmunity(periods: Int) {
    val current = poisonValue
    val value = -periods
    if (current < 0 && current <= value) {
        return
    }
    setPoisonValue(value)
}

public fun Player.setPoisonValue(value: Int) {
    val previous = poisonValue
    VarPlayerIntMapSetter.set(this, PoisonVarps.poison, value)
    if (previous == 0 && value != 0) {
        softTimer(PoisonTimers.tick, POISON_HIT_INTERVAL)
    }
}
