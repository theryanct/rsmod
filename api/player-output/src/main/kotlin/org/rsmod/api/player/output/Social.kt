package org.rsmod.api.player.output

import net.rsprot.protocol.game.outgoing.social.FriendListLoaded
import net.rsprot.protocol.game.outgoing.social.MessagePrivate
import net.rsprot.protocol.game.outgoing.social.MessagePrivateEcho
import net.rsprot.protocol.game.outgoing.social.UpdateFriendList
import net.rsprot.protocol.game.outgoing.social.UpdateIgnoreList
import org.rsmod.game.entity.Player

/** World id and name shown to friends; this is a single local world. */
private const val LOCAL_WORLD_ID = 1
private const val LOCAL_WORLD_NAME = "World 1"

/**
 * Sends the whole friend list. [isOnline] reports whether a friend is logged in on this world. An
 * empty list sends [FriendListLoaded] so the client stops showing "Loading friend list".
 */
public fun Player.sendFriendList(isOnline: (String) -> Boolean) {
    if (friendList.isEmpty()) {
        client.write(FriendListLoaded)
        return
    }
    val entries = friendList.map { name -> friendEntry(name, isOnline(name), added = false) }
    client.write(UpdateFriendList(entries))
}

/** Sends a single friend entry, e.g. after adding a friend or a friend logging in or out. */
public fun Player.sendFriendUpdate(name: String, online: Boolean, added: Boolean) {
    client.write(UpdateFriendList(listOf(friendEntry(name, online, added))))
}

/** Sends the whole ignore list. */
public fun Player.sendIgnoreList() {
    val entries = ignoreList.map { UpdateIgnoreList.AddedIgnoredEntry(it, null, "", false) }
    client.write(UpdateIgnoreList(entries))
}

/** Sends a single newly added ignore entry. */
public fun Player.sendIgnoreAdded(name: String) {
    val entry = UpdateIgnoreList.AddedIgnoredEntry(name, null, "", true)
    client.write(UpdateIgnoreList(listOf(entry)))
}

/** Tells the client [name] is no longer ignored. */
public fun Player.sendIgnoreRemoved(name: String) {
    client.write(UpdateIgnoreList(listOf(UpdateIgnoreList.RemovedIgnoredEntry(name))))
}

/** Shows [message] in [this] player's private chat as sent by [sender]. */
public fun Player.privateMessageFrom(sender: String, counter: Int, message: String) {
    client.write(MessagePrivate(sender, LOCAL_WORLD_ID, counter, 0, message))
}

/** Echoes [message] in [this] player's private chat as sent to [recipient]. */
public fun Player.privateMessageEcho(recipient: String, message: String) {
    client.write(MessagePrivateEcho(recipient, message))
}

private fun friendEntry(name: String, online: Boolean, added: Boolean): UpdateFriendList.Friend =
    if (online) {
        UpdateFriendList.OnlineFriend(
            added,
            name,
            null,
            LOCAL_WORLD_ID,
            0,
            0,
            "",
            LOCAL_WORLD_NAME,
            0,
            0,
        )
    } else {
        UpdateFriendList.OfflineFriend(added, name, null, 0, 0, "")
    }
