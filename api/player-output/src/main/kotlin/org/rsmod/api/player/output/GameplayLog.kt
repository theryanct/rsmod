package org.rsmod.api.player.output

import com.github.michaelbull.logging.InlineLogger
import java.util.WeakHashMap
import org.rsmod.game.entity.Player

/**
 * Game-wide diagnostics, logged under the `gameplay` logger: `server/logging` routes it to the
 * rotating file `.data/logs/gameplay.log`. Turn it off by setting that logger's level to `OFF` in
 * `logback.xml`. Messages are built lazily, so a disabled logger costs almost nothing.
 *
 * Lines are `KIND key=value ...`; `tools/logs/summarize.py` groups them.
 */
public object GameplayLog {
    private val logger = InlineLogger("gameplay")

    /** The last player input we saw (button, op), so fallbacks can say what was clicked. */
    private val lastInput = WeakHashMap<Player, String>()

    public fun input(player: Player, description: () -> String) {
        if (logger.isInfoEnabled) {
            lastInput[player] = description()
        }
    }

    /** "Nothing interesting happens": the missing-content detector. */
    public fun nothingInteresting(player: Player, target: String? = null) {
        logger.info {
            "NOTHING player=${player.username} at=${player.coords} " +
                "target=${target ?: lastInput[player] ?: "unknown"}"
        }
    }

    public fun unhandled(player: Player, what: () -> String) {
        logger.info { "UNHANDLED player=${player.username} ${what()}" }
    }

    public fun dialog(player: Player, what: () -> String) {
        logger.info { "DIALOG player=${player.username} ${what()}" }
    }

    public fun modal(player: Player, what: () -> String) {
        logger.info { "MODAL player=${player.username} ${what()}" }
    }

    public fun refused(player: Player, what: () -> String) {
        logger.info { "REFUSED player=${player.username} ${what()} last=${lastInput[player]}" }
    }

    public fun session(player: Player, what: () -> String) {
        logger.info { "SESSION player=${player.username} at=${player.coords} ${what()}" }
    }
}
