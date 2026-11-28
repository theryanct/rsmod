package org.rsmod.content.other.mapclock

import jakarta.inject.Inject
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.config.refs.varps
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private fun currentMinute(): Int = (System.currentTimeMillis() / 60_000L).toInt()

class MapClockScript @Inject constructor(private val clock: MapClock) : PluginScript() {
    private var Player.playtime by intVarp(varps.playtime)
    private var Player.mapClock by intVarp(varps.map_clock)
    private var Player.msPastMinute by intVarBit(varbits.date_ms_past_minute)
    private var Player.secsPastMinute by intVarBit(varbits.date_secs_past_minute)
    private var Player.dateMinutes by intVarp(MapClockVarps.date_minutes)

    override fun ScriptContext.startup() {
        onPlayerLogin { player.initClockTimer() }
        onPlayerSoftTimer(clock_timers.map_clock) { player.incrementClock() }
    }

    private fun Player.initClockTimer() {
        softTimer(clock_timers.map_clock, 1)
        // Never sent before: the client then saw minute 0, so minute-based timers looked active.
        dateMinutes = currentMinute()
        resyncVar(MapClockVarps.date_minutes)
    }

    private fun Player.incrementClock() {
        val now = System.currentTimeMillis()
        msPastMinute = (now % 1000).toInt()
        secsPastMinute = ((now / 1000) % 60).toInt()
        mapClock = clock.cycle
        playtime++
        dateMinutes = currentMinute()
    }
}
