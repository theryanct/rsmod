package org.rsmod.api.account.character.social

import org.rsmod.api.account.character.CharacterDataStage

public data class CharacterSocialData(val friends: List<String>, val ignores: List<String>) :
    CharacterDataStage.Segment
