package org.rsmod.api.player.dig

import java.util.concurrent.CopyOnWriteArrayList
import org.rsmod.api.player.protect.ProtectedAccess

/**
 * What happens when a player digs (spade "Dig", and any other dig trigger): every registered
 * handler is asked in turn, and the first that returns `true` handled the dig at the player's
 * position. Quests (via `QuestDigSpots`) and clue scrolls register here.
 */
public object DigSpots {
    private val handlers = CopyOnWriteArrayList<suspend ProtectedAccess.() -> Boolean>()

    /** Register from a plugin's `startup()`; runs after the dig animation. */
    public fun register(handler: suspend ProtectedAccess.() -> Boolean) {
        handlers += handler
    }

    /** @return `true` if some handler dug something up here. */
    public suspend fun dig(access: ProtectedAccess): Boolean =
        handlers.any { handler -> handler(access) }
}
