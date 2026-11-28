package org.rsmod.content.interfaces.ge

import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.content.interfaces.ge.configs.GeInterfaces
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.GeHistoryEntry

/**
 * Grand Exchange trade history (rev 243 `ge_history`): `ge_history_init` (1644), one
 * `ge_history_addline(index, obj, sell, count, gold, tax)` (1645) per trade, newest first, then
 * `ge_history_finish` (1646). There is no tax on this server.
 */
internal fun ProtectedAccess.openGeHistory() {
    ifOpenMain(GeInterfaces.history)
    player.runClientScript(HISTORY_INIT)
    for ((index, entry) in player.geHistory.withIndex()) {
        player.runClientScript(
            HISTORY_ADDLINE,
            index,
            entry.obj,
            if (entry.sell) 1 else 0,
            entry.count,
            entry.gold,
            0L,
        )
    }
    player.runClientScript(HISTORY_FINISH)
}

/** Records a completed trade, keeping the newest [GeHistoryEntry.MAX_ENTRIES]. */
internal fun Player.recordGeTrade(entry: GeHistoryEntry) {
    geHistory.addFirst(entry)
    while (geHistory.size > GeHistoryEntry.MAX_ENTRIES) {
        geHistory.removeLast()
    }
}

private const val HISTORY_INIT = 1644
private const val HISTORY_ADDLINE = 1645
private const val HISTORY_FINISH = 1646
