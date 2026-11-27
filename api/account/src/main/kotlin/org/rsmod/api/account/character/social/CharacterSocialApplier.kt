package org.rsmod.api.account.character.social

import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.game.entity.Player

public class CharacterSocialApplier @Inject constructor() :
    CharacterDataStage.Applier<CharacterSocialData> {
    override fun apply(player: Player, data: CharacterSocialData) {
        player.friendList.clear()
        player.friendList += data.friends
        player.ignoreList.clear()
        player.ignoreList += data.ignores
    }
}
