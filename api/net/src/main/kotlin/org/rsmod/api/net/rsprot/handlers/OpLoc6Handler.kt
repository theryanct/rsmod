package org.rsmod.api.net.rsprot.handlers

import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.locs.OpLoc6V1
import net.rsprot.protocol.game.incoming.locs.OpLoc6V2
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.mes
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList

class OpLoc6Handler @Inject constructor(private val locTypes: LocTypeList) :
    MessageHandler<OpLoc6V1> {
    override fun handle(player: Player, message: OpLoc6V1): Unit = examine(player, message.id)

    internal fun examine(player: Player, id: Int) {
        val type = locTypes[id] ?: return
        player.mes(type.desc, ChatType.LocExamine)
    }
}

/** Rev 243 variant with int loc ids. */
class OpLoc6V2Handler @Inject constructor(private val delegate: OpLoc6Handler) :
    MessageHandler<OpLoc6V2> {
    override fun handle(player: Player, message: OpLoc6V2): Unit =
        delegate.examine(player, message.id)
}
