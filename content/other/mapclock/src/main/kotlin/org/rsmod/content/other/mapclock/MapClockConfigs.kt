package org.rsmod.content.other.mapclock

import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varp.VarpReferences

typealias clock_timers = MapClockTimers

object MapClockTimers : TimerReferences() {
    val map_clock = find("map_clock")
}

internal object MapClockVarps : VarpReferences() {
    /**
     * Wall-clock minutes the client compares timers against (e.g. the home teleport fade:
     * `%date_minutes - %aide_tele_timer < 30`). Same epoch as the server's minute timers.
     */
    val date_minutes = find("date_minutes")
}
