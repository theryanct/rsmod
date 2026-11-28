package org.rsmod.content.other.commands

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.account.saver.AccountSavingService
import org.rsmod.api.account.saver.request.AccountSaveCallback
import org.rsmod.api.account.saver.request.AccountSaveRequest
import org.rsmod.api.account.saver.request.AccountSaveResponse
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Periodic autosave: every [INTERVAL_TICKS] (5 min) each online player is queued on the existing
 * single-threaded [AccountSavingService] (the same repository + pipelines a logout uses, one
 * SQLite transaction). Without this the server only saved on logout, so a crash or hard kill lost
 * the whole session.
 *
 * The save reads the live player on the save thread. A save that races an in-progress inventory
 * move can store a slightly inconsistent snapshot; in-memory state stays correct and the next
 * autosave/logout overwrites it, which is far better than losing the session. The callback is a
 * no-op (unlike [org.rsmod.api.account.AccountManager.save], which also logs the player out).
 * Toggle: -Drsmod.autosave=false.
 */
class PlayerAutoSave
@Inject
constructor(private val saver: AccountSavingService, private val playerList: PlayerList) :
    PluginScript() {
    private val logger = InlineLogger()
    private var tick = 0

    override fun ScriptContext.startup() {
        if (!ENABLED) return
        onEvent<GameLifecycle.LateCycle> { process() }
    }

    private fun process() {
        if (++tick % INTERVAL_TICKS != 0) return
        var queued = 0
        for (player in playerList) {
            if (player.loggingOut || player.pendingLogout || player.pendingShutdown) continue
            saver.queue(
                AccountSaveRequest(
                    player.accountId,
                    player.characterId,
                    player,
                    AccountSaveCallback { response ->
                    if (response !is AccountSaveResponse.Success) {
                        logger.error { "Autosave failed for '${player.username}': $response" }
                    }
                    },
                )
            )
            queued++
        }
        if (queued > 0) logger.info { "Autosave queued for $queued player(s)." }
    }

    private companion object {
        /** 5 minutes at 600 ms per cycle. */
        const val INTERVAL_TICKS = 500
        val ENABLED: Boolean = System.getProperty("rsmod.autosave", "true").toBoolean()
    }
}
