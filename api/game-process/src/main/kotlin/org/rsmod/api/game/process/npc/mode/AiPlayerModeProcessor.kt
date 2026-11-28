package org.rsmod.api.game.process.npc.mode

import jakarta.inject.Inject
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.interact.InteractionPlayer

public class AiPlayerModeProcessor
@Inject
constructor(private val playerList: PlayerList, private val interactions: AiPlayerInteractions) {
    public fun processOp(npc: Npc, op: InteractionOp) {
        val target = npc.resolveTarget()
        if (target == null) {
            npc.resetMode()
            return
        }
        npc.clearIdleCycles()
        npc.facePlayer(target)
        // Ranged and magic attackers (attack range > 1) fight from a distance: their combat op is
        // approached (ap) within their attack range instead of walked up to.
        if (op == InteractionOp.Op2 && npc.visType.attackRange > 1) {
            interactions.interactAp(npc, target, op)
            return
        }
        interactions.interactOp(npc, target, op)
    }

    public fun processAp(npc: Npc, op: InteractionOp) {
        val target = npc.resolveTarget()
        if (target == null) {
            npc.resetMode()
            return
        }
        npc.clearIdleCycles()
        npc.facePlayer(target)
        interactions.interactAp(npc, target, op)
    }

    private fun Npc.resolveTarget(): Player? {
        val interaction = this.interaction ?: return null
        if (interaction !is InteractionPlayer) {
            return null
        }
        return interaction.uid.resolve(playerList)
    }
}
