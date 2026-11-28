package org.rsmod.api.player.features

import java.util.concurrent.CopyOnWriteArrayList
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Player

/**
 * The companion roster for UI (the Companions side tab, content/interfaces/companions-tab): the
 * Companions content module installs the [Backend] at startup, like [CompanionPerks]. Until then
 * (or without that module) the roster is empty and the actions do nothing.
 */
public object CompanionRoster {
    /**
     * @param affinity affinity points; [nextTierAt] is the points for the next tier (null at max).
     * @param out the follower is with the player now (false while knocked out or not active).
     * @param perk one line describing the current perk at the current tier.
     */
    public data class Entry(
        val key: String,
        val name: String,
        val unlocked: Boolean,
        val unlockHint: String,
        val active: Boolean,
        val out: Boolean,
        val affinity: Int,
        val tier: Int,
        val nextTierAt: Int?,
        val perk: String,
    )

    public interface Backend {
        public fun entries(player: Player): List<Entry>

        /** Summons [key] (dismissing any other); sends its own messages. */
        public fun summon(access: ProtectedAccess, key: String)

        public fun dismiss(access: ProtectedAccess)

        /** Brings the active follower back to the player (e.g. when it got stuck). */
        public fun recall(access: ProtectedAccess)
    }

    @Volatile private var backend: Backend? = null
    private val listeners = CopyOnWriteArrayList<(Player) -> Unit>()

    public fun install(backend: Backend) {
        this.backend = backend
    }

    public fun entries(player: Player): List<Entry> = backend?.entries(player).orEmpty()

    public fun active(player: Player): Entry? = entries(player).firstOrNull { it.active }

    public fun summon(access: ProtectedAccess, key: String) {
        backend?.summon(access, key)
    }

    public fun dismiss(access: ProtectedAccess) {
        backend?.dismiss(access)
    }

    public fun recall(access: ProtectedAccess) {
        backend?.recall(access)
    }

    /** UI registers here to redraw when [player]'s roster changes. */
    public fun onChanged(listener: (Player) -> Unit) {
        listeners += listener
    }

    /** Called by the backend after a change (active companion, out / knocked out, affinity). */
    public fun changed(player: Player) {
        for (listener in listeners) listener(player)
    }
}
