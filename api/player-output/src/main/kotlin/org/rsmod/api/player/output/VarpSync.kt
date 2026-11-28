package org.rsmod.api.player.output

import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.game.outgoing.varp.VarpLong
import net.rsprot.protocol.game.outgoing.varp.VarpSmall
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.type.varp.VarpType

public object VarpSync {
    /** Calling this function directly will bypass [VarpType.transmit] conditions. */
    public fun writeVarp(player: Player, varp: VarpType, value: Int) {
        writeVarp(player.client, varp, value)
    }

    /**
     * Writes a 64-bit client varp (rev 243 long varps such as `ge_newoffer_price`): the client keeps
     * these apart from int varps, so [writeVarp] would not reach them.
     */
    public fun writeVarpLong(player: Player, varp: VarpType, value: Long) {
        player.client.write(VarpLong(varp.id, value))
    }

    /** Calling this function directly will bypass [VarpType.transmit] conditions. */
    public fun writeVarp(client: Client<Any, Any>, varp: VarpType, value: Int) {
        val message =
            if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                VarpSmall(varp.id, value)
            } else {
                VarpLarge(varp.id, value)
            }
        client.write(message)
    }
}
