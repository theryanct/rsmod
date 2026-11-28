package org.rsmod.api.player.poison

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.varp.VarpType

/**
 * Player poison, kept in the client's `poison` varp (which also turns the hitpoints orb green):
 * - `> 0`: poisoned with that severity; each poison hit deals `(severity + 4) / 5` damage and
 *   lowers the severity by one, so damage N repeats five times before dropping to N - 1.
 * - `< 0`: immune for that many more [POISON_HIT_INTERVAL]-tick periods (antipoisons).
 * - `0`: neither.
 *
 * The periodic hits and immunity countdown run from the poison content script's timer.
 */
public object PoisonVarps : VarpReferences() {
    public val poison: VarpType = find("poison")
}

/** Ticks between poison hits (18 seconds, as in OSRS). */
public const val POISON_HIT_INTERVAL: Int = 30

public val Player.poisonValue: Int
    get() = vars[PoisonVarps.poison]

public val Player.isPoisoned: Boolean
    get() = poisonValue > 0

public val Player.isPoisonImmune: Boolean
    get() = poisonValue < 0

/** Damage of the next poison hit (0 when not poisoned). */
public val Player.poisonDamage: Int
    get() = if (isPoisoned) (poisonValue + 4) / 5 else 0

/**
 * Poisons the player starting at [damage] per hit, unless they are immune or already poisoned
 * at least that strongly. Returns `true` if the poison took hold (or got stronger).
 */
public fun Player.applyPoison(damage: Int): Boolean {
    val current = poisonValue
    val severity = damage * 5
    if (current < 0 || severity <= current) {
        return false
    }
    setPoisonValue(severity)
    return true
}

/** Cures poison and grants immunity for [immunityTicks] (rounded down to whole periods). */
public fun Player.curePoison(immunityTicks: Int = 0) {
    setPoisonValue(-(immunityTicks / POISON_HIT_INTERVAL))
}

public fun Player.setPoisonValue(value: Int) {
    VarPlayerIntMapSetter.set(this, PoisonVarps.poison, value)
}
