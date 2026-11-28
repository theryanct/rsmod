package org.rsmod.content.skills.sailing

import org.rsmod.api.player.protect.ProtectedAccess

/**
 * Lets other modules (quests) use the dock ledger tables, which [PortTasks] binds: a hook is
 * asked first on every ledger op and returns `true` when it handled it; [carrying] keeps the
 * tables on "Deposit-cargo" while the player holds that hook's cargo.
 *
 * Ports are the sailing.tsv port keys (`port_sarim`, `the_pandemonium`, ...).
 */
public object SailingLedgerHooks {
    public class Hook(
        public val handle: suspend ProtectedAccess.(port: String) -> Boolean,
        public val carrying: ProtectedAccess.() -> Boolean,
    )

    private val hooks = ArrayList<Hook>()

    public fun register(hook: Hook) {
        hooks += hook
    }

    internal suspend fun handle(access: ProtectedAccess, port: String): Boolean =
        hooks.any { hook -> hook.handle(access, port) }

    internal fun carrying(access: ProtectedAccess): Boolean = hooks.any { hook -> hook.carrying(access) }
}
