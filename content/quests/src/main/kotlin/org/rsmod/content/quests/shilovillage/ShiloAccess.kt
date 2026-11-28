package org.rsmod.content.quests.shilovillage

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpcU
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.COMPLETE
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.SHILO_VILLAGE
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shilo Village after the quest: the gates (the outer metal gate behind the broken cart and the
 * inner wooden gate, both barred until the quest is done; the cache multiloc turns the cart into
 * "Climb over" at 15), Mosol's walk in (in [ShiloVillage]), the cart between Vigroy (by the Shilo
 * bank) and Hajedy (south of the Brimhaven docks), and Yanni Salika buying the quest's antiques.
 * The bank (generic Banker by its Bank op), the furnace (Smelting binds the cache's lit furnace,
 * shown at 15) and the gem rocks (Mining) need nothing here.
 *
 * Simplified: the cart's Talk-to fare is ours (Wiki: 10-200 coins in proportion to the coins
 * carried; we charge a fiftieth of them within those bounds) and the Karamja elite diary's free
 * rides aren't checked; Yohnus's furnace fee isn't charged; Yanni's black prism and the items he
 * might sell (Wiki transcript missing) aren't offered.
 */
class ShiloAccess @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    private val vigroyDialogue by lazy { QuestDialogue.load(ShiloVillage::class.java, "vigroy") }
    private val hajedyDialogue by lazy { QuestDialogue.load(ShiloVillage::class.java, "hajedy") }
    private val yanniDialogue by lazy { QuestDialogue.load(ShiloVillage::class.java, "yanni_salika") }

    override fun ScriptContext.startup() {
        // The village gates and the broken cart (DoorFallback would otherwise swing them open).
        for (gate in SvLocs.village_gates) onLocOpLabel(locTypes, gate, "Open") { openGate(it) }
        onLocOpLabel(locTypes, SvLocs.broken_cart, "Search") {
            mes("The cart has been wedged firmly across the path into the village.") // Authored (not Wiki).
        }
        onLocOpLabel(locTypes, SvLocs.broken_cart_open, "Climb over") { climbCart() }

        // The cart: Vigroy (Talk-to 1, Pay-fare 3), Hajedy (same) and both carts (Board 2, Pay-fare 3).
        if (!isNpcOpBound(SvNpcs.vigroy, 1)) onOpNpc1(SvNpcs.vigroy) { talkDriver(it.npc, toBrimhaven = true) }
        if (!isNpcOpBound(SvNpcs.vigroy, 3)) onOpNpc3(SvNpcs.vigroy) { payFare(toBrimhaven = true) }
        if (!isNpcOpBound(SvNpcs.hajedy, 1)) onOpNpc1(SvNpcs.hajedy) { talkDriver(it.npc, toBrimhaven = false) }
        if (!isNpcOpBound(SvNpcs.hajedy, 3)) onOpNpc3(SvNpcs.hajedy) { payFare(toBrimhaven = false) }
        if (!isLocOpBound(SvLocs.shilo_cart, 2)) onOpLoc2(SvLocs.shilo_cart) { payFare(toBrimhaven = true) }
        if (!isLocOpBound(SvLocs.shilo_cart, 3)) onOpLoc3(SvLocs.shilo_cart) { payFare(toBrimhaven = true) }
        if (!isLocOpBound(SvLocs.brimhaven_cart, 2)) onOpLoc2(SvLocs.brimhaven_cart) { payFare(toBrimhaven = false) }
        if (!isLocOpBound(SvLocs.brimhaven_cart, 3)) onOpLoc3(SvLocs.brimhaven_cart) { payFare(toBrimhaven = false) }

        // Yanni Salika.
        if (!isNpcOpBound(SvNpcs.yanni, 1)) onOpNpc1(SvNpcs.yanni) { talkYanni(it.npc) }
        onOpNpcU(SvNpcs.yanni) { sellToYanni(it.npc, it.objType) }
    }

    private val ProtectedAccess.done: Boolean
        get() = player.questState(SHILO_VILLAGE) >= COMPLETE

    /* Gates */

    private suspend fun ProtectedAccess.openGate(gate: BoundLocInfo) {
        if (!done) {
            // Authored (not Wiki): Mosol's people keep the undead penned in.
            mes("The gate has been barred shut to keep the undead inside the village.")
            return
        }
        passThroughWall(gate)
    }

    private fun ProtectedAccess.climbCart() {
        telejump(if (player.coords.x >= CART_EAST.x) CART_WEST else CART_EAST)
    }

    /* The cart */

    private fun ProtectedAccess.fare(): Int = (invTotal(inv, SvObjs.coins) / FARE_DIVISOR).coerceIn(MIN_FARE, MAX_FARE)

    private suspend fun ProtectedAccess.talkDriver(npc: Npc, toBrimhaven: Boolean) {
        val dialogue = if (toBrimhaven) vigroyDialogue else hajedyDialogue
        if (!done) {
            val section = if (toBrimhaven) "Talking to Vigroy before completing Shilo Village" else "Before Shilo Village"
            svSection(dialogue, npc, section)
            return
        }
        val fare = fare()
        val afford = carries(SvObjs.coins, fare)
        var ride = false
        val yes = if (toBrimhaven) "Yes please, I'd like to go to Brimhaven." else "Yes please, I'd like to go to Shilo Village."
        val hooks =
            SvHooks(
                this,
                conditions =
                    mapOf(
                        "If the player has enough coins:" to { afford },
                        "If the player doesn't have enough coins:" to { !afford },
                    ),
                options = mapOf(yes to { ride = afford }),
                rewrites = { it.replace("[amount]", fare.toString()) },
            )
        svSection(dialogue, npc, "If the player hasn't completed the Elite Karamja Diary", hooks)
        if (ride && carries(SvObjs.coins, fare)) {
            invDel(inv, SvObjs.coins, fare)
            telejump(if (toBrimhaven) BRIMHAVEN_CART else SHILO_CART)
        }
    }

    private fun ProtectedAccess.payFare(toBrimhaven: Boolean) {
        if (!done) {
            mes("The cart isn't running while the undead plague Shilo Village.") // Authored (not Wiki).
            return
        }
        if (!carries(SvObjs.coins, MAX_FARE)) {
            mes("You need $MAX_FARE coins to pay the fare.") // Authored (Wiki: Pay-fare is always 200).
            return
        }
        invDel(inv, SvObjs.coins, MAX_FARE)
        telejump(if (toBrimhaven) BRIMHAVEN_CART else SHILO_CART)
        mes("You feel tired from the journey, but at least you didn't have to walk all that distance.") // Wiki {{mes}}
    }

    /* Yanni Salika */

    private class Antique(val obj: () -> ObjType, val price: Int, val quote: String, val section: String)

    private val antiques =
        listOf(
            Antique({ SvObjs.bone_key }, 100, "I'll give you 100 coins for the Bone Key...", "Bone key"),
            Antique({ SvObjs.plaque }, 100, "I'll give you 100 coins for the Stone Plaque...", "Stone-plaque"),
            Antique({ SvObjs.tattered_scroll }, 100, "I'll give you 100 coins for the tattered scroll...", "Tattered scroll"),
            Antique({ SvObjs.crumpled_scroll }, 100, "I'll give you 100 coins for the crumpled scroll...", "Crumpled scroll"),
            Antique({ SvObjs.crystal }, 500, "I'll give you 500 coins for your locating crystal...", "Locating crystal"),
            Antique({ SvObjs.dead_beads }, 1000, "I'll give you 1000 coins for your 'Beads of the Dead'...", "Beads of the Dead"),
            // Not in the quote list of the transcript, but Yanni buys it (Wiki: 100 coins).
            Antique({ SvObjs.notes }, 100, "", "Bervirius notes"),
        )

    private suspend fun ProtectedAccess.talkYanni(npc: Npc) {
        val held = antiques.filter { it.quote.isNotEmpty() && carries(it.obj()) }
        var quote = false
        var forSale = false
        val beads = svOwns(SvObjs.dead_beads)
        val hooks =
            SvHooks(
                this,
                // The transcript's quote conditions all apply at once; they're shown below instead.
                conditions =
                    mapOf(
                        "If the player doesn't have an item Yanni can buy:" to { held.isEmpty() },
                        "If the player already has the Beads of the Dead in their inventory or bank:" to { beads },
                    ),
                options =
                    mapOf(
                        "Yes please!" to { quote = held.isNotEmpty() },
                        "Do you have any interesting items for sale?" to { forSale = !beads },
                    ),
            )
        svSection(yanniDialogue, npc, "Standard dialogue", hooks)
        if (quote) {
            startDialogue(npc) {
                for (antique in held) chatNpc(neutral, antique.quote)
                if (held.size == 1) {
                    chatNpc(neutral, "And that's the only item I am interested in. If you want to sell me that item, simply show it to me.")
                } else {
                    chatNpc(neutral, "Those are the items I am interested in Bwana. If you want to sell me those items, simply show them to me.")
                }
            }
        } else if (forSale) {
            // Authored (not Wiki: that transcript branch is missing).
            startDialogue(npc) { chatNpc(neutral, "Sorry Bwana, I have nothing for sale at the moment.") }
        }
    }

    private suspend fun ProtectedAccess.sellToYanni(npc: Npc, obj: ObjType) {
        val antique = antiques.firstOrNull { it.obj().id == obj.id }
        if (antique == null) {
            startDialogue(npc) { chatNpc(neutral, "Sorry Bwana, I'm not interested in that.") } // Authored.
            return
        }
        if (!carries(obj)) return
        svSection(yanniDialogue, npc, antique.section)
        invDel(inv, obj)
        invAdd(inv, SvObjs.coins, antique.price)
    }

    private companion object {
        const val FARE_DIVISOR = 50
        const val MIN_FARE = 10
        const val MAX_FARE = 200

        /* Positions from the rev 243 map. */
        /** Either side of the broken cart (2877-2879, 2951-2953) at the village's east gate. */
        val CART_EAST = CoordGrid(2880, 2952, 0)
        val CART_WEST = CoordGrid(2876, 2952, 0)
        /** By the travel carts: Shilo (2831,2952, by Vigroy) and Brimhaven (2777,3211, by Hajedy). */
        val SHILO_CART = CoordGrid(2834, 2950, 0)
        val BRIMHAVEN_CART = CoordGrid(2780, 3213, 0)
    }
}
