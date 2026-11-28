package org.rsmod.api.player.interact

import java.util.concurrent.CopyOnWriteArrayList
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.CoordGrid

/**
 * Lets a system take over an npc or loc op for one player without binding the npc/loc itself
 * (a second `onOpNpc1` for an npc that already has a script throws at startup). E.g. a clue step
 * "Talk to Hans" claims Hans's Talk-to only while the player holds that step; everyone else gets
 * Hans's normal script.
 *
 * A claimed op counts as having an op trigger (so the player walks up even if nothing else is
 * bound). Npc claims also run from the ap step within [NPC_AP_RANGE] tiles (shopkeepers behind
 * counters); loc claims suppress the ap trigger and run once adjacent.
 */
public object OpInterceptors {
    public interface NpcOp {
        /** Must be cheap and side-effect free: it is asked on click and again when triggering. */
        public fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean

        public suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp)

        /** Shown by `::unboundops` for the ops [auditCovers] reports. */
        public val auditName: String
            get() = javaClass.simpleName

        /**
         * For `::unboundops` (no player): `true` if this may claim [op] (1-5) on an npc of [type]
         * at [coords], so the audit lists it as covered instead of unbound. Default: never.
         */
        public fun auditCovers(type: UnpackedNpcType, coords: CoordGrid, op: Int): Boolean = false
    }

    public interface LocOp {
        public fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean

        public suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp)

        /** Shown by `::unboundops` for the ops [auditCovers] reports. */
        public val auditName: String
            get() = javaClass.simpleName

        /**
         * For `::unboundops` (no player): `true` if this may claim [op] (1-5) on a loc of [type]
         * with [shape] at [coords] when nothing else binds it. Default: never.
         */
        public fun auditCovers(type: UnpackedLocType, shape: Int, coords: CoordGrid, op: Int): Boolean =
            false
    }

    /** How close a claimed npc op runs from (the GE clerk / counter npc distance). */
    public const val NPC_AP_RANGE: Int = 2

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

    /** `::unboundops`: the name of the first interceptor that may claim this npc op, or null. */
    public fun auditNpc(type: UnpackedNpcType, coords: CoordGrid, op: Int): String? =
        npcOps.firstOrNull { it.auditCovers(type, coords, op) }?.auditName

    /** `::unboundops`: the name of the first interceptor that may claim this loc op, or null. */
    public fun auditLoc(type: UnpackedLocType, shape: Int, coords: CoordGrid, op: Int): String? =
        locOps.firstOrNull { it.auditCovers(type, shape, coords, op) }?.auditName
}
