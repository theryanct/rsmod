package org.rsmod.api.player.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.type.seq.SeqType

/** Published when [player] performs an emote ([seq] is its first animation). Clue emote steps. */
public data class PlayEmote(val player: Player, val seq: SeqType) : UnboundEvent
