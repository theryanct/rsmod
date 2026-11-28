package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType

/**
 * Sigmund the Merchant's trial (Wiki): his exotic flower comes at the end of a chain of thirteen
 * favours. Asking in order - the sailor, Olaf, Yrsa, Brundt, Sigli, Skulgrimen, the fisherman,
 * Swensen, Peer, Thorvald, Manni, Thora, Askeladden - each names what they want; Askeladden's
 * promissory note (5,000 coins) then buys Thora's cocktail, which buys Manni's token, and so back
 * along the chain to the sailor's flower for Sigmund. Asking someone out of turn gets the Wiki's
 * "no idea" reply about whatever you are looking for. Losing an item: buy a new note and start
 * the hand-backs again.
 *
 * Simplified: the "no idea" replies reuse each npc's flower/bowstring line with the sought item
 * swapped in (the Wiki records those two rounds only); Swensen's is authored.
 */
internal class FtSigmund(private val ft: FtContext) {
    /**
     * One favour. [ask] is the section where they name their price, [again] their reminder (or the
     * ask again), [give] the hand-over, [after] their line once paid; [seeking] is what you ask
     * them for when they are next; [filler] their out-of-turn reply.
     */
    enum class Link(
        val ask: String,
        val again: String?,
        val give: String,
        val after: String,
        val filler: String?,
        val seeking: String,
        val wants: () -> ObjType?,
        val gives: () -> ObjType,
    ) {
        Sailor(
            "Talking to the Sailor",
            null,
            "Giving the ballad to the Sailor",
            "Talking to the Sailor again",
            "Talking to the Sailor (2)",
            "a rare flower from across the sea",
            { FtObjs.ballad },
            { FtObjs.flower },
        ),
        Olaf(
            "Talking to Olaf the Bard",
            null,
            "Giving the boots to Olaf",
            "Talking to Olaf again",
            "Talking to Olaf",
            "a love ballad",
            { FtObjs.boots },
            { FtObjs.ballad },
        ),
        Yrsa(
            "Talking to Yrsa (2)",
            "Talking to Yrsa again",
            "Giving the fiscal statement to Yrsa",
            "Talking to Yrsa again (2)",
            "Talking to Yrsa",
            "some custom sturdy boots",
            { FtObjs.fiscal_statement },
            { FtObjs.boots },
        ),
        Brundt(
            "Talking to Brundt (2)",
            null,
            "Giving the map to Brundt",
            "Talking to Brundt again",
            "Talking to Brundt",
            "a guarantee of a reduction on sales taxes",
            { FtObjs.hunting_map },
            { FtObjs.fiscal_statement },
        ),
        Sigli(
            "Finding Sigli's bowstring",
            null,
            "Giving the bowstring to Sigli",
            "Talking to Sigli again (2)",
            "Talking to Sigli",
            "a map to unspoiled hunting grounds",
            { FtObjs.bowstring },
            { FtObjs.hunting_map },
        ),
        Skulgrimen(
            "Talking to Skulgrimen (2)",
            "Talking to Skulgrimen again",
            "Giving the fish to Skulgrimen",
            "Talking to Skulgrimen again (2)",
            "Talking to Skulgrimen",
            "a finely balanced custom bowstring",
            { FtObjs.fish },
            { FtObjs.bowstring },
        ),
        Fisherman(
            "Talking to the Fisherman (3)",
            "Talking to the Fisherman again",
            "Giving the Fisherman the map",
            "Talking to the Fisherman again (2)",
            "Talking to the Fisherman",
            "an exotic and extremely rare fish",
            { FtObjs.sea_map },
            { FtObjs.fish },
        ),
        Swensen(
            "Talking to Swensen the Navigator",
            "Talking to Swensen again",
            "Giving Swensen the forecast",
            "Talking to Swensen again (2)",
            null,
            "a map of deep sea fishing spots",
            { FtObjs.forecast },
            { FtObjs.sea_map },
        ),
        Peer(
            "Peer the Seer's favour",
            "Talking to Peer the Seer again",
            "Giving Peer the Seer the bodyguard contract",
            "Talking to Peer the Seer again (2)",
            "Talking to Peer the Seer",
            "a weather forecast from the Fremennik Seer",
            { FtObjs.contract },
            { FtObjs.forecast },
        ),
        Thorvald(
            "Convincing Thorvald to be a bodyguard",
            "Talking to Thorvald again",
            "Giving Thorvald his token",
            "Talking to Thorvald again (2)",
            "Talking to Thorvald",
            "a brave and powerful warrior to act as a bodyguard",
            { FtObjs.token },
            { FtObjs.contract },
        ),
        Manni(
            "Talking to Manni the Reveller",
            "Talking to Manni again",
            "Giving Manni his legendary cocktail",
            "Talking to Manni again (2)",
            "Talking to Manni",
            "a token to allow a seat at the champions table",
            { FtObjs.cocktail },
            { FtObjs.token },
        ),
        Thora(
            "Talking to Thora (2)",
            "Talking to Thora again",
            "Getting Thora's legendary cocktail",
            "Talking to Thora again (2)",
            "Talking to Thora",
            "the longhall barkeeps' legendary cocktail",
            { FtObjs.promissory_note },
            { FtObjs.cocktail },
        ),
        Askeladden(
            "Talking to Askeladden (4)",
            null,
            "Talking to Askeladden (4)",
            "Talking to Askeladden again (2)",
            "Talking to Askeladden (2)",
            "a written promise from Askeladden to stay out of the Longhall",
            { null },
            { FtObjs.promissory_note },
        );

        /** ft_sigmund bits 4-16. */
        val givenMask: Int
            get() = 1 shl (4 + ordinal)
    }

    private val byNpc: Map<Int, Link> by lazy {
        buildMap {
            put(FtNpcs.sailor.id, Link.Sailor)
            put(FtNpcs.olaf.id, Link.Olaf)
            put(FtNpcs.yrsa.id, Link.Yrsa)
            put(FtNpcs.brundt.id, Link.Brundt)
            put(FtNpcs.sigli.id, Link.Sigli)
            put(FtNpcs.skulgrimen.id, Link.Skulgrimen)
            put(FtNpcs.fisherman.id, Link.Fisherman)
            put(FtNpcs.swensen.id, Link.Swensen)
            put(FtNpcs.peer.id, Link.Peer)
            put(FtNpcs.thorvald.id, Link.Thorvald)
            put(FtNpcs.manni.id, Link.Manni)
            put(FtNpcs.thora.id, Link.Thora)
            for (askeladden in FtNpcs.askeladdens) put(askeladden.id, Link.Askeladden)
        }
    }

    fun linkFor(npc: NpcType): Link? = byNpc[npc.id]

    /* Sigmund */

    suspend fun ProtectedAccess.talkSigmund(npc: Npc) {
        when {
            !player.accepted(Trial.Sigmund) -> {
                var accepted = false
                runQuestSection(npc, ft.dialogue, "Sigmund the Merchant's small \"favour\"", SimpleQuestHooks(options = mapOf("Yes" to { accepted = true })))
                if (accepted) player.accept(Trial.Sigmund)
            }
            player.voted(Trial.Sigmund) -> runQuestSection(npc, ft.dialogue, "Subsequent dialogue with Sigmund")
            carries(FtObjs.flower) -> {
                runQuestSection(npc, ft.dialogue, "Giving the flowers to Sigmund")
                invDel(inv, FtObjs.flower)
                player.giveVote(Trial.Sigmund)
                mes("Congratulations! You have completed the Merchant's trial!") // Authored (not Wiki).
            }
            else -> runQuestSection(npc, ft.dialogue, "Talking to Sigmund again")
        }
    }

    /* The chain ("Ask about the Merchant's trial") */

    suspend fun ProtectedAccess.merchant(npc: Npc) {
        val link = byNpc[npc.type.id] ?: return
        val asked = player.ftField(FtVarps.sigmund, 0, 4)
        when {
            link == Link.Askeladden && asked >= Link.Askeladden.ordinal -> askeladden(npc, asked)
            given(link) -> chain(npc, link.after)
            link.wants()?.let { carries(it) } == true -> handOver(npc, link)
            link.ordinal < asked -> chain(npc, link.again ?: link.ask)
            link.ordinal == asked -> {
                chain(npc, link.ask)
                player.ftSetField(FtVarps.sigmund, 0, 4, asked + 1)
            }
            else -> outOfTurn(npc, link, Link.entries[asked].seeking)
        }
    }

    private fun ProtectedAccess.given(link: Link): Boolean = player.ftHas(FtVarps.sigmund, link.givenMask)

    private suspend fun ProtectedAccess.chain(npc: Npc, section: String, hooks: SimpleQuestHooks = SimpleQuestHooks()) {
        with(ft) { branch(npc, section, MERCHANT_OPTION, hooks) }
    }

    private suspend fun ProtectedAccess.handOver(npc: Npc, link: Link) {
        val wanted = link.wants() ?: return
        chain(npc, link.give)
        if (!carries(wanted)) return
        invReplace(inv, wanted, 1, link.gives())
        player.ftSet(FtVarps.sigmund, link.givenMask)
    }

    private suspend fun ProtectedAccess.outOfTurn(npc: Npc, link: Link, seeking: String) {
        val filler = link.filler
        if (filler == null) {
            // Authored lines (not Wiki): Swensen has no recorded out-of-turn reply.
            startDialogue(npc) {
                chatPlayer(neutral, "I don't suppose you have any idea where I could find $seeking, do you?")
                chatNpc(neutral, "I keep maps and charts, outerlander, not that. Try asking around the town.")
            }
            return
        }
        val hooks =
            SimpleQuestHooks(
                rewrites = {
                    it.replace("a rare flower from across the sea", seeking).replace("a finely balanced custom bowstring", seeking)
                }
            )
        chain(npc, filler, hooks)
    }

    /** Askeladden sells the promissory note (again, if the hand-backs were lost). */
    private suspend fun ProtectedAccess.askeladden(npc: Npc, asked: Int) {
        val chainItems = Link.entries.mapNotNull { it.wants() } + FtObjs.flower
        val holdingAny = chainItems.any { carries(it) }
        val canBuy = !carries(FtObjs.promissory_note) && (!given(Link.Thora) || !holdingAny)
        if (asked > Link.Askeladden.ordinal && !canBuy) {
            chain(npc, Link.Askeladden.after)
            return
        }
        val coins = carries(FtObjs.coins, NOTE_PRICE)
        val room = inv.freeSpace() > 0
        var buy = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have 5,000 gp:" to { !coins },
                        "If the player has 5,000 gp:" to { coins },
                        "If the player's inventory is full:" to { !room },
                        "If the player has inventory space:" to { room },
                    ),
                options = mapOf("Yes" to { buy = coins && room }),
            )
        chain(npc, Link.Askeladden.ask, hooks)
        if (!buy) return
        invDel(inv, FtObjs.coins, NOTE_PRICE)
        invAdd(inv, FtObjs.promissory_note)
        // A new note restarts the hand-backs.
        var bits = player.ftGet(FtVarps.sigmund)
        for (link in Link.entries) bits = bits and link.givenMask.inv()
        VarPlayerIntMapSetter.set(player, FtVarps.sigmund, bits)
        player.ftSetField(FtVarps.sigmund, 0, 4, Link.entries.size)
    }

    private companion object {
        const val NOTE_PRICE = 5000
        const val MERCHANT_OPTION = "Ask about the Merchant's trial"
    }
}
