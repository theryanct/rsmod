package org.rsmod.api.player.interact

import java.util.concurrent.CopyOnWriteArrayList
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo

/**
 * Lets a system take over an npc or loc op for one player without binding the npc/loc itself
 * (a second `onOpNpc1` for an npc that already has a script throws at startup). E.g. a clue step
 * "Talk to Hans" claims Hans's Talk-to only while the player holds that step; everyone else gets
 * Hans's normal script.
 *
 * A claimed op counts as having an op trigger (so the player walks up even if nothing else is
 * bound) and suppresses the ap trigger, so the claim runs once the player is adjacent.
 */
public object OpInterceptors {
    public interface NpcOp {
        /** Must be cheap and side-effect free: it is asked on click and again when triggering. */
        public fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean

        public suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp)
    }

    public interface LocOp {
        public fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean

        public suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp)
    }

    private val npcOps = CopyOnWriteArrayList<NpcOp>()
    private val locOps = CopyOnWriteArrayList<LocOp>()

    /** Call from a plugin's `startup()`. */
    public fun register(interceptor: NpcOp) {
        npcOps += interceptor
    }

    /** Call from a plugin's `startup()`. */
    public fun register(interceptor: LocOp) {
        locOps += interceptor
    }

    public fun npc(player: Player, npc: Npc, op: InteractionOp): NpcOp? =
        npcOps.firstOrNull { it.claims(player, npc, op) }

    public fun loc(player: Player, loc: BoundLocInfo, op: InteractionOp): LocOp? =
        locOps.firstOrNull { it.claims(player, loc, op) }
}
