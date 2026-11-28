package org.rsmod.content.other.commands

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import org.rsmod.api.player.output.MiscOutput
import org.rsmod.api.player.output.mes
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.game.GameLifecycle
import org.rsmod.game.GameUpdate
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Lets the devops tooling drive the live server through files in the control dir
 * (`-Drsmod.control.dir`, default `~/code`), polled every [POLL_TICKS] cycles on the game thread:
 * - `rsmod-server.update` (optional body: seconds, default 120): starts the real "System update
 *   in: m:ss" countdown + a game-message broadcast. At 0 the existing update flow logs everyone out
 *   (saving them); once nobody is left the process exits so the server loop rebuilds/restarts.
 *   An empty or `0` body cancels a running countdown.
 * - `rsmod-server.cmd` (one word): allow-listed, read-only dumps only. `status` writes
 *   `.data/logs/server-status.txt`.
 * Each file is deleted once read.
 */
class ServerControlFiles
@Inject
constructor(
    private val update: GameUpdate,
    private val playerList: PlayerList,
    private val npcList: NpcList,
) : PluginScript() {
    private val logger = InlineLogger()
    private val dir: Path =
        Path.of(System.getProperty("rsmod.control.dir", System.getProperty("user.home") + "/code"))
    private var tick = 0
    private var exitArmed = false
    private var updatingTicks = 0

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.LateCycle> { process() }
    }

    private fun process() {
        if (exitArmed) checkExit()
        if (++tick % POLL_TICKS != 0) return
        readAndDelete(UPDATE_FILE)?.let(::handleUpdate)
        readAndDelete(CMD_FILE)?.let(::handleCommand)
    }

    private fun handleUpdate(body: String) {
        val seconds = body.trim().ifEmpty { "$DEFAULT_SECONDS" }.toIntOrNull() ?: DEFAULT_SECONDS
        if (seconds <= 0) {
            if (update.state != GameUpdate.State.Updating) update.clear()
            exitArmed = false
            logger.info { "Update countdown cancelled via $UPDATE_FILE." }
            return
        }
        val cycles = (seconds * 1000 / CYCLE_MILLIS).coerceIn(1, 65535)
        update.startCountdown(cycles)
        exitArmed = true
        val text = "Server restarting in ${formatDuration(seconds)} for an update."
        for (player in playerList) {
            MiscOutput.updateRebootTimer(player, cycles)
            player.mes(text)
        }
        logger.info { "Update countdown started via $UPDATE_FILE: ${seconds}s ($cycles cycles)." }
    }

    private fun checkExit() {
        if (update.state != GameUpdate.State.Updating) return
        // Give logouts (and their saves) time to complete before exiting.
        if (playerList.any() && ++updatingTicks < MAX_LOGOUT_WAIT_TICKS) return
        exitArmed = false
        logger.info { "Update countdown finished; exiting for the server loop to restart." }
        SafeServiceExit.terminate()
    }

    private fun handleCommand(body: String) {
        when (val cmd = body.trim().lowercase()) {
            "status" -> writeStatus()
            else -> logger.warn { "Ignoring $CMD_FILE command not on the allow-list: '$cmd'." }
        }
    }

    private fun writeStatus() {
        val text =
            "players=${playerList.count()}\nnpcs=${npcList.count()}\nupdate=${update.state}\ntick=$tick\n"
        val out = Path.of(".data", "logs", "server-status.txt")
        runCatching { Files.writeString(out, text) }
            .onFailure { logger.error(it) { "Failed writing $out." } }
    }

    private fun readAndDelete(name: String): String? {
        val path = dir.resolve(name)
        if (!Files.exists(path)) return null
        return runCatching { Files.readString(path).also { Files.delete(path) } }
            .onFailure { logger.error(it) { "Failed reading control file $path." } }
            .getOrNull()
    }

    private fun formatDuration(seconds: Int): String =
        if (seconds % 60 == 0) {
            val m = seconds / 60
            "$m minute${if (m == 1) "" else "s"}"
        } else {
            "$seconds seconds"
        }

    private companion object {
        const val UPDATE_FILE = "rsmod-server.update"
        const val CMD_FILE = "rsmod-server.cmd"
        const val DEFAULT_SECONDS = 120
        const val CYCLE_MILLIS = 600
        const val POLL_TICKS = 5
        const val MAX_LOGOUT_WAIT_TICKS = 50
    }
}
