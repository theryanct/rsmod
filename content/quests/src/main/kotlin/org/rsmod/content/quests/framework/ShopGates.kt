package org.rsmod.content.quests.framework

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType

/**
 * Quest unlocks for generic shops (e.g. Uglug Nar's opens once Zogre Flesh Eaters' balm is sold):
 * a quest registers a gate for the shopkeeper, and the generic shop's "Trade" asks it first. The
 * gate shows its own refusal and returns `false` to keep the shop shut. Checked at click time, so
 * plugin startup order doesn't matter.
 */
public object ShopGates {
    private val gates = HashMap<Int, suspend ProtectedAccess.(Npc) -> Boolean>()

    public fun register(npc: NpcType, gate: suspend ProtectedAccess.(Npc) -> Boolean) {
        check(npc.id !in gates) { "ShopGates: two gates for npc ${npc.id}" }
        gates[npc.id] = gate
    }

    public suspend fun ProtectedAccess.shopAllowed(npc: Npc): Boolean =
        gates[npc.type.id]?.invoke(this, npc) ?: true
}
