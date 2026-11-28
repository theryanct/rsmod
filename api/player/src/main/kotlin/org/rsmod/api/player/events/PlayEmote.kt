package org.rsmod.api.player.events

import org.rsmod.events.UnboundEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.type.seq.SeqType

/** Published when [player] performs an emote ([seq] is its first animation). Clue emote steps. */
public data class PlayEmote(val player: Player, val seq: SeqType) : UnboundEvent

/**
 * Published when [player] picks an emote from the emotes tab; [slot] is its index in the client's
 * emote list (enum `emote_names`), which is how clue scroll emote steps name emotes.
 */
public data class EmoteSelected(val player: Player, val slot: Int) : UnboundEvent
