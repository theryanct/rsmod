package org.rsmod.api.player.social

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.privateMessageEcho
import org.rsmod.api.player.output.privateMessageFrom
import org.rsmod.api.player.output.sendFriendList
import org.rsmod.api.player.output.sendFriendUpdate
import org.rsmod.api.player.output.sendIgnoreAdded
import org.rsmod.api.player.output.sendIgnoreList
import org.rsmod.api.player.output.sendIgnoreRemoved
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

/**
 * Friend and ignore lists for this single local world. Lists are stored by display name on the
 * [Player] and persisted by the account social pipeline. Private messages are delivered only to
 * players online on this world.
 */
@Singleton
public class PlayerSocial @Inject constructor(private val playerList: PlayerList) {
    private var privateMessageCounter = 0

    /** Sends both lists on login and tells online friends-of-[player] that they came online. */
    public fun onLogin(player: Player) {
        player.sendFriendList(::isOnline)
        player.sendIgnoreList()
        notifyStatus(player, online = true)
    }

    /** Tells online players who have [player] as a friend that they went offline. */
    public fun onLogout(player: Player) {
        notifyStatus(player, online = false)
    }

    public fun addFriend(player: Player, input: String) {
        val name = input.trim()
        when {
            name.isEmpty() -> return
            name.equals(player.displayName, ignoreCase = true) ->
                player.mes("You can't add yourself to your own friend list.")
            player.friendList.containsName(name) ->
                player.mes("$name is already on your friend list.")
            player.ignoreList.containsName(name) ->
                player.mes("Please remove $name from your ignore list first.")
            player.friendList.size >= MAX_FRIENDS ->
                player.mes("Your friend list is full. Max of $MAX_FRIENDS users.")
            else -> {
                val displayName = findOnline(name)?.displayName ?: name
                player.friendList += displayName
                player.sendFriendUpdate(displayName, isOnline(displayName), added = true)
            }
        }
    }

    public fun removeFriend(player: Player, input: String) {
        player.friendList.removeName(input.trim())
    }

    public fun addIgnore(player: Player, input: String) {
        val name = input.trim()
        when {
            name.isEmpty() -> return
            name.equals(player.displayName, ignoreCase = true) ->
                player.mes("You can't add yourself to your own ignore list.")
            player.ignoreList.containsName(name) ->
                player.mes("$name is already on your ignore list.")
            player.friendList.containsName(name) ->
                player.mes("Please remove $name from your friend list first.")
            player.ignoreList.size >= MAX_IGNORES ->
                player.mes("Your ignore list is full. Max of $MAX_IGNORES users.")
            else -> {
                val displayName = findOnline(name)?.displayName ?: name
                player.ignoreList += displayName
                player.sendIgnoreAdded(displayName)
            }
        }
    }

    public fun removeIgnore(player: Player, input: String) {
        val removed = player.ignoreList.removeName(input.trim()) ?: return
        player.sendIgnoreRemoved(removed)
    }

    public fun sendPrivateMessage(sender: Player, recipientName: String, message: String) {
        val recipient = findOnline(recipientName.trim())
        if (recipient == null || recipient.ignoreList.containsName(sender.displayName)) {
            sender.mes("That player is currently offline.")
            return
        }
        recipient.privateMessageFrom(sender.displayName, ++privateMessageCounter, message)
        sender.privateMessageEcho(recipient.displayName, message)
    }

    private fun notifyStatus(player: Player, online: Boolean) {
        for (other in playerList) {
            if (other !== player && other.friendList.containsName(player.displayName)) {
                other.sendFriendUpdate(player.displayName, online, added = false)
            }
        }
    }

    private fun isOnline(name: String): Boolean = findOnline(name) != null

    private fun findOnline(name: String): Player? =
        playerList.firstOrNull { it.displayName.equals(name, ignoreCase = true) }

    private fun List<String>.containsName(name: String): Boolean =
        any { it.equals(name, ignoreCase = true) }

    private fun MutableList<String>.removeName(name: String): String? {
        val index = indexOfFirst { it.equals(name, ignoreCase = true) }
        return if (index == -1) null else removeAt(index)
    }

    private companion object {
        private const val MAX_FRIENDS = 400
        private const val MAX_IGNORES = 400
    }
}
