package org.rsmod.content.skills.herblore

import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.entity.Player

/**
 * Antifire protection, kept in the cache varbits the client shows as potion timers
 * (`antifire_potion`, `super_antifire_potion`; 6 bits each in varp `dragonresist`), counted in
 * [UNIT_TICKS]-tick units and ticked down by [AntifireTimers.tick]. Combat's Dragonfire reads the
 * same varbits: regular antifire cuts dragonfire to 70% (0 with a shield), super antifire blocks
 * it. Values per OSRS Wiki: antifire 6 min, extended 12 min, super 3 min, extended super 6 min.
 */
internal object Antifire {
    const val UNIT_TICKS = 30
    private const val TICKS_PER_MINUTE = 100

    fun drink(player: Player, superPotion: Boolean, minutes: Int) {
        val varbit = if (superPotion) AntifireVarBits.super_antifire else AntifireVarBits.antifire
        val units = (minutes * TICKS_PER_MINUTE / UNIT_TICKS).coerceAtMost(MAX_UNITS)
        VarPlayerIntMapSetter.set(player, varbit, maxOf(player.vars[varbit], units))
        player.softTimer(AntifireTimers.tick, UNIT_TICKS)
    }

    /** Every [UNIT_TICKS]: count both protections down, warn at the last unit, stop at zero. */
    fun tick(player: Player) {
        var active = false
        for ((varbit, name) in TIMERS) {
            val left = player.vars[varbit]
            if (left <= 0) continue
            VarPlayerIntMapSetter.set(player, varbit, left - 1)
            when (left - 1) {
                0 -> player.mes("<col=7f007f>Your $name has expired.</col>")
                1 -> {
                    player.mes("<col=7f007f>Your $name is about to expire.</col>")
                    active = true
                }
                else -> active = true
            }
        }
        if (!active) player.clearSoftTimer(AntifireTimers.tick)
    }

    private const val MAX_UNITS = 63

    private val TIMERS by lazy {
        listOf(
            AntifireVarBits.antifire to "antifire potion",
            AntifireVarBits.super_antifire to "super antifire potion",
        )
    }
}

internal object AntifireVarBits : VarBitReferences() {
    val antifire = find("antifire_potion")
    val super_antifire = find("super_antifire_potion")
}

internal object AntifireTimers : TimerReferences() {
    /** Custom timer (65702). */
    val tick = find("antifire_tick")
}
