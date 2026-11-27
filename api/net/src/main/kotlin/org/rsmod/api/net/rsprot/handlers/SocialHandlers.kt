package org.rsmod.api.net.rsprot.handlers

import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.messaging.MessagePrivate
import net.rsprot.protocol.game.incoming.social.FriendListAdd
import net.rsprot.protocol.game.incoming.social.FriendListDel
import net.rsprot.protocol.game.incoming.social.IgnoreListAdd
import net.rsprot.protocol.game.incoming.social.IgnoreListDel
import org.rsmod.api.player.social.PlayerSocial
import org.rsmod.game.entity.Player

class FriendListAddHandler @Inject constructor(private val social: PlayerSocial) :
    MessageHandler<FriendListAdd> {
    override fun handle(player: Player, message: FriendListAdd) {
        social.addFriend(player, message.name)
    }
}

class FriendListDelHandler @Inject constructor(private val social: PlayerSocial) :
    MessageHandler<FriendListDel> {
    override fun handle(player: Player, message: FriendListDel) {
        social.removeFriend(player, message.name)
    }
}

class IgnoreListAddHandler @Inject constructor(private val social: PlayerSocial) :
    MessageHandler<IgnoreListAdd> {
    override fun handle(player: Player, message: IgnoreListAdd) {
        social.addIgnore(player, message.name)
    }
}

class IgnoreListDelHandler @Inject constructor(private val social: PlayerSocial) :
    MessageHandler<IgnoreListDel> {
    override fun handle(player: Player, message: IgnoreListDel) {
        social.removeIgnore(player, message.name)
    }
}

class MessagePrivateHandler @Inject constructor(private val social: PlayerSocial) :
    MessageHandler<MessagePrivate> {
    override fun handle(player: Player, message: MessagePrivate) {
        social.sendPrivateMessage(player, message.name, message.message)
    }
}
