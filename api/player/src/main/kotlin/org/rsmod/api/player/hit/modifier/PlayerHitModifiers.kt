package org.rsmod.api.player.hit.modifier

import java.util.concurrent.CopyOnWriteArrayList
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder

/**
 * Content hooks for **incoming** hits on players, run by [StandardPlayerHitModifier] after the
 * standard modifications (protection prayers). Empty by default (no-op).
 *
 * Register from a plugin's `startup()`; a modifier may scale or zero [HitBuilder.damage]. For a hit
 * from an npc, [HitBuilder.sourceUid] is that npc's `uid.packed` and [HitBuilder.isFromNpc] is set.
 *
 * ```
 * PlayerHitModifiers.register { target ->
 *     if (isFromNpc && sourceUid == count.uid.packed && garlic in target.inv) damage /= 2
 * }
 * ```
 *
 * The npc-side equivalent is the keyed `NpcHitEvents.Modify` event (`onModifyNpcHit`).
 */
public object PlayerHitModifiers {
    private val modifiers = CopyOnWriteArrayList<HitBuilder.(Player) -> Unit>()

    public fun register(modifier: HitBuilder.(target: Player) -> Unit) {
        modifiers += modifier
    }

    internal fun apply(hit: HitBuilder, target: Player) {
        for (modifier in modifiers) {
            hit.modifier(target)
        }
    }
}
