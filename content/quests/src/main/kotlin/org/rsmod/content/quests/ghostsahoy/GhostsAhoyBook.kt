package org.rsmod.content.quests.ghostsahoy

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onApLoc1
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Ghosts Ahoy, the Book of Haricanto:
 * - The crone's toy boat: Repair it (silk, needle and thread, knife), then dye its flag's top
 *   half, skull emblem and bottom half to match the shipwreck's tattered flag (Search the mast
 *   while the wind is low; each part is red, blue or yellow, rolled per player). The old cabin
 *   boy on the shipwreck trades it for his chest key.
 * - Three map scraps: the captain's office chest (chest key), the chest at the end of the rocky
 *   island's stepping rocks (Jump-To; falls hurt 1-3), and the lower-deck chest a giant lobster
 *   guards (it vanishes after two minutes). Use one on another to make the map.
 * - The ghost captain sails to Dragontooth Island for 25 ecto-tokens (500 for unlimited trips);
 *   the map's Follow walks paces from the X, and the book is dug up at 3803-3804, 3529-3530.
 */
class GhostsAhoyBook
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val lobsters = HashMap<Long, Npc>()

    override fun ScriptContext.startup() {
        onOpHeld1(GaObjs.toy_boat) { repairBoat() }
        for (tool in listOf(GaObjs.silk, GaObjs.needle, GaObjs.thread, GaObjs.knife)) {
            onOpHeldU(GaObjs.toy_boat, tool) { repairBoat() }
        }
        onOpHeld1(GaObjs.toy_boat_repaired) { inspectBoat() }
        for ((index, dye) in GaObjs.dyes.withIndex()) {
            onOpHeldU(GaObjs.toy_boat_repaired, dye) { dyeBoat(dye, index + 1) }
        }

        onNpcOpLabel(npcTypes, GaNpcs.old_man, "Talk-to") { talkOldMan(it) }
        onLocOpLabel(locTypes, GaLocs.captain_skull, "Talk-To") {
            mesbox(
                "The pirate captain ignores you and continues to stare lifelessly at nothing, " +
                    "as he has clearly been dead for some time."
            )
        }
        onLocOpLabel(locTypes, GaLocs.mast, "Search") { searchMast() }
        onLocOpLabel(locTypes, GaLocs.chest_locked, "Open") { openChest(it, locked = true) }
        onLocOpLabel(locTypes, GaLocs.chest_closed, "Open") { openChest(it, locked = false) }
        onLocOpLabel(locTypes, GaLocs.chest_open, "Search") { mes("The chest is empty.") }
        onOpLocU(GaLocs.chest_locked, GaObjs.chest_key) { openChest(it.loc, locked = false) }
        onLocOpLabel(locTypes, GaLocs.gangplank_on, "Cross") { telejump(ROCKS_SIDE) }
        onLocOpLabel(locTypes, GaLocs.gangplank_off, "Cross") { telejump(DECK_SIDE) }
        onLocOpLabel(locTypes, GaLocs.rock, "Jump-To") { jumpRock(it) }
        // The rocks are two tiles apart over water: jump from within reach, not adjacent.
        onApLoc1(GaLocs.rock) { if (isWithinApRange(it.loc, distance = ROCK_REACH)) jumpRock(it.loc) }
        onEvent<NpcKilledEvent> { lobsterKilled(this) }

        for ((i, scrap) in GaObjs.scraps.withIndex()) {
            for (other in GaObjs.scraps.drop(i + 1)) {
                onOpHeldU(scrap, other) { assembleMap() }
            }
        }
        onOpHeld2(GaObjs.map) { followMap() }
        QuestDigSpots.register { digBook() }

        onNpcOpLabel(npcTypes, GaNpcs.captain_dock, "Talk-to") { talkCaptain(it, toIsland = true) }
        onNpcOpLabel(npcTypes, GaNpcs.captain_dock, "Travel") { talkCaptain(it, toIsland = true) }
        onNpcOpLabel(npcTypes, GaNpcs.captain_island, "Talk-to") { talkCaptain(it, toIsland = false) }
    }

    /* The flag colours, in ahoy_progress */

    private fun ProtectedAccess.progress(): Int = player.vars[GaVarps.progress]

    private fun ProtectedAccess.setProgress(value: Int) {
        VarPlayerIntMapSetter.set(player, GaVarps.progress, value)
    }

    /** The shipwreck flag's colour (1 red, 2 blue, 3 yellow) for [part] (0 top, 1 skull, 2 bottom). */
    private fun ProtectedAccess.target(part: Int): Int {
        if (progress() and TARGET_MASK == 0) {
            var rolled = 0
            for (p in 0 until PARTS) rolled = rolled or (random.of(1, 3) shl (p * 2))
            setProgress(progress() or rolled)
        }
        return (progress() shr (part * 2)) and 3
    }

    /** The toy boat flag's colour for [part]: 0 white, else dye index 1-6. */
    private fun ProtectedAccess.boatColour(part: Int): Int =
        (progress() shr (BOAT_SHIFT + part * 3)) and 7

    private fun ProtectedAccess.setBoatColour(part: Int, colour: Int) {
        val shift = BOAT_SHIFT + part * 3
        setProgress((progress() and (7 shl shift).inv()) or (colour shl shift))
    }

    private fun ProtectedAccess.boatMatches(): Boolean =
        (0 until PARTS).all { boatColour(it) == target(it) }

    /* The toy boat */

    private suspend fun ProtectedAccess.repairBoat() {
        val ready =
            carries(GaObjs.silk) &&
                carries(GaObjs.needle) &&
                carries(GaObjs.thread) &&
                carries(GaObjs.knife)
        if (!ready) {
            mesbox(
                "You need some silk to replace the flag, something to sew it to the boat, and " +
                    "something to cut the flag to the right size."
            )
            return
        }
        invDel(inv, GaObjs.silk)
        invDel(inv, GaObjs.thread)
        invDel(inv, GaObjs.toy_boat)
        invAdd(inv, GaObjs.toy_boat_repaired)
        for (part in 0 until PARTS) setBoatColour(part, 0)
        mesbox("You replace the toy boat's missing flag.")
    }

    private suspend fun ProtectedAccess.inspectBoat() {
        val (top, skull, bottom) = (0 until PARTS).map { COLOURS[boatColour(it)] }
        mesbox(
            "The top of the flag is $top. The skull emblem is $skull. The bottom of the flag is $bottom.",
        )
    }

    private suspend fun ProtectedAccess.dyeBoat(dye: ObjType, colour: Int) {
        val part =
            choice3(
                "Top half",
                0,
                "Bottom half",
                2,
                "Skull emblem",
                1,
                title = "Which part of the flag do you want to dye?",
            )
        invDel(inv, dye)
        setBoatColour(part, colour)
        val name = listOf("top of the flag", "skull emblem", "bottom of the flag")[part]
        mesbox("You dye the $name ${COLOURS[colour]}.")
    }

    /* The shipwreck */

    private suspend fun ProtectedAccess.talkOldMan(npc: Npc) {
        val boat = bit(GaVarBits.toyboat)
        if (boat >= GhostsAhoy.BOAT_RETURNED) {
            if (!carries(GaObjs.chest_key) && progress() and OFFICE_SCRAP == 0) {
                gaRun(npc, "Talking to the Old man after losing the key")
                invAdd(inv, GaObjs.chest_key)
                return
            }
            gaRun(npc, "Talking to the Old man again")
            return
        }
        val broken = carries(GaObjs.toy_boat)
        val repaired = carries(GaObjs.toy_boat_repaired)
        val correct = repaired && boatMatches()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the model ship is broken:" to { broken && !repaired },
                        "If the model ship is fixed but not recoloured/coloured wrong:" to {
                            repaired && !correct
                        },
                        "If the model ship is fixed and correctly recoloured:" to { correct },
                    ),
                rewrites = { if (it == "Is this your toy boat?" && !broken && !repaired) "" else it },
            )
        runQuestSection(npc, dialogue, "Talking to the Old man", hooks, GaNpcs.speakers)
        if (correct && carries(GaObjs.toy_boat_repaired)) {
            invDel(inv, GaObjs.toy_boat_repaired)
            invAdd(inv, GaObjs.chest_key)
            setBit(GaVarBits.toyboat, GhostsAhoy.BOAT_RETURNED)
        }
    }

    private suspend fun ProtectedAccess.searchMast() {
        val high = random.of(0, 1) == 1
        setBit(GaVarBits.windspeed, if (high) 1 else 0)
        if (high) {
            mesbox(
                "You can see a tattered flag blowing in the wind. The wind is blowing too hard to make out any details.",
            )
            return
        }
        val part = random.of(0, PARTS - 1)
        val name = listOf("top half", "skull emblem", "bottom half")[part]
        mesbox(
            "You can see a tattered flag blowing in the wind. The $name is coloured ${COLOURS[target(part)]}.",
        )
    }

    private suspend fun ProtectedAccess.openChest(chest: BoundLocInfo, locked: Boolean) {
        val at = chest.coords
        when (at) {
            OFFICE_CHEST -> {
                if (locked && !carries(GaObjs.chest_key)) {
                    mes("The chest is locked.")
                    return
                }
                if (locked) mesbox("You unlock the chest.")
                scrap(OFFICE_SCRAP, GaObjs.scraps[0])
            }
            ISLAND_CHEST -> scrap(ISLAND_SCRAP, GaObjs.scraps[1])
            LOBSTER_CHEST -> {
                if (bit(GaVarBits.killed_lobster) == 0) {
                    spawnLobster(player, at)
                    return
                }
                scrap(LOBSTER_SCRAP, GaObjs.scraps[2])
            }
            else -> mes(if (locked) "The chest is locked." else "The chest is empty.")
        }
    }

    private suspend fun ProtectedAccess.scrap(bitMask: Int, scrap: ObjType) {
        val held =
            carries(scrap) ||
                carries(GaObjs.map) ||
                carries(GaObjs.book) ||
                bit(GaVarBits.given_book) != 0
        if (held) {
            mes("The chest is empty.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough room to take anything from the chest.")
            return
        }
        invAdd(inv, scrap)
        setProgress(progress() or bitMask)
        mesbox("You find a piece of a map inside the chest.")
    }

    private fun spawnLobster(player: Player, chest: CoordGrid) {
        val uid = player.uid.packed
        val existing = lobsters[uid]
        if (existing != null && existing.isSlotAssigned) return
        player.mes("A giant lobster appears!")
        val lobster = Npc(npcTypes[GaNpcs.lobster], chest.translate(0, -1))
        npcRepo.add(lobster, LOBSTER_TICKS)
        lobsters[uid] = lobster
        lobster.opPlayer2(player, aiInteractions)
    }

    private fun lobsterKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != GaNpcs.lobster.id) return
        val player = event.hero
        if (lobsters[player.uid.packed] !== event.npc) return
        lobsters.remove(player.uid.packed)
        VarPlayerIntMapSetter.set(player, GaVarBits.killed_lobster, 1)
        player.mes("You killed the giant lobster. Now you can search the chest.")
    }

    private suspend fun ProtectedAccess.jumpRock(rock: BoundLocInfo) {
        anim(GaSeqs.jump)
        delay(1)
        if (random.of(1, FALL_ODDS) == 1) {
            mes("You slip and fall onto the rock!")
            player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(1, 3))
            return
        }
        telejump(rock.coords)
    }

    private suspend fun ProtectedAccess.assembleMap() {
        if (GaObjs.scraps.any { !carries(it) }) {
            mesbox("You don't have all the pieces of the map yet.")
            return
        }
        for (scrap in GaObjs.scraps) invDel(inv, scrap)
        invAdd(inv, GaObjs.map)
        mesbox("You piece the three map scraps together to form a complete map.")
    }

    /* Dragontooth Island */

    private suspend fun ProtectedAccess.talkCaptain(npc: Npc, toIsland: Boolean) {
        if (!wearingGhostspeak()) {
            startDialogue(npc) { chatNpc(neutral, "Woooo wooo wooooo woooo") }
            return
        }
        val dest = if (toIsland) ISLAND_LANDING else DOCK_LANDING
        if (progress() and UNLIMITED != 0) {
            startDialogue(npc) { chatNpc(happy, "All aboard, then!") }
            sailTo(
                dest,
                fare = 0,
                message = "The ghost captain rows you across.",
                collision = collision,
            )
            return
        }
        val tokens = invTotal(inv, GaObjs.ecto_token)
        val where = if (toIsland) "Dragontooth Island" else "Port Phasmatys"
        startDialogue(npc) {
            chatNpc(neutral, "Would you like to visit $where? It's 25 ecto-tokens a trip.")
        }
        val choice =
                choice3(
                    "Okay, here's 25 ectotokens. Let's go.",
                    1,
                    "If I pay 500 ectotokens, can I get unlimited trips?",
                    2,
                    "Not now, thanks.",
                    0,
                )
        when {
            choice == 2 && tokens >= UNLIMITED_PRICE -> {
                invDel(inv, GaObjs.ecto_token, UNLIMITED_PRICE)
                setProgress(progress() or UNLIMITED)
            }
            choice == 1 && tokens >= TRIP_PRICE -> invDel(inv, GaObjs.ecto_token, TRIP_PRICE)
            choice == 0 -> return
            else -> {
                mes("You don't have enough ecto-tokens.")
                return
            }
        }
        sailTo(
            dest,
            fare = 0,
            message = "The ghost captain rows you across.",
            collision = collision,
        )
    }

    private suspend fun ProtectedAccess.followMap() {
        val c = player.coords
        if (c.level != 0 || c.x !in ISLAND_X || c.z !in ISLAND_Z) {
            mesbox("This map is for use elsewhere.")
            return
        }
        val dir =
            choice4(
                "North",
                0,
                "East",
                1,
                "South",
                2,
                "West",
                3,
                title = "Which direction do you wish to walk?",
            )
        val paces =
            choice4(
                "1 pace",
                1,
                "2 paces",
                2,
                "4 paces",
                4,
                "10 paces",
                10,
                title = "How far do you wish to walk?",
            )
        val (dx, dz) = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)[dir]
        walk(player.coords.translate(dx * paces, dz * paces))
    }

    private suspend fun ProtectedAccess.digBook(): Boolean {
        val c = player.coords
        if (c.level != 0 || c.x !in BOOK_X || c.z !in BOOK_Z) return false
        val done = carries(GaObjs.book) || bit(GaVarBits.given_book) != 0
        if (gaState() < GhostsAhoy.REMEMBERED || done) {
            return false
        }
        if (!carries(GaObjs.map) || inv.isFull()) return false
        invAdd(inv, GaObjs.book)
        mesbox("You unearth the Book of Haricanto.")
        return true
    }

    private companion object {
        const val PARTS = 3
        const val TARGET_MASK = 0x3F
        const val BOAT_SHIFT = 6
        const val UNLIMITED = 1 shl 22
        const val OFFICE_SCRAP = 1 shl 23
        const val ISLAND_SCRAP = 1 shl 24
        const val LOBSTER_SCRAP = 1 shl 25
        const val TRIP_PRICE = 25
        const val UNLIMITED_PRICE = 500
        const val LOBSTER_TICKS = 200
        const val FALL_ODDS = 5
        const val ROCK_REACH = 2

        val COLOURS = listOf("white", "red", "blue", "yellow", "orange", "green", "purple")

        val OFFICE_CHEST = CoordGrid(3619, 3545, 1)
        val ISLAND_CHEST = CoordGrid(3606, 3564, 1)
        val LOBSTER_CHEST = CoordGrid(3618, 3542, 0)
        val ROCKS_SIDE = CoordGrid(3605, 3549, 1)
        val DECK_SIDE = CoordGrid(3605, 3545, 1)
        val ISLAND_LANDING = CoordGrid(3791, 3558, 0)
        val DOCK_LANDING = CoordGrid(3703, 3487, 0)
        val ISLAND_X = 3770..3830
        val ISLAND_Z = 3515..3575
        val BOOK_X = 3803..3804
        val BOOK_Z = 3529..3530
    }
}
