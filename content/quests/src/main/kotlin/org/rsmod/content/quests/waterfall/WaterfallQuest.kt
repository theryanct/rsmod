package org.rsmod.content.quests.waterfall

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Waterfall Quest (OSRS Wiki). `waterfall_quest` varp (cache db end state 10); the steps in
 * between are ours: 1 Almera's worry, 2 found Hudon on the island (the raft), 3 washed down to
 * Gerald (or asked him) and sent to Hadley, 4 read the Book on Baxtorian, 5 entered Glarial's tomb
 * with her pebble (from Golrie, under the Tree Gnome Village), 6 inside the waterfall (Glarial's
 * amulet worn), 10 complete (Glarial's urn in the chalice: 13,750 Attack and Strength xp,
 * 2 diamonds, 2 gold bars, 40 mithril seeds, 1 QP).
 *
 * Server varp `waterfall_pillars` (65778): bits 0-17 are the air / water / earth rune on each of
 * the six pillars, bit 18 Glarial's amulet on her statue. Every way of falling in the river
 * (swimming, the dead tree without a rope, the barrel, the flooded ledge or chalice room) washes
 * the player ashore by Gerald.
 *
 * Not done: the tomb's and dungeon's monsters (moss guardians, fire giants, ...) unless the world
 * spawns them; the chalice's rising floor is not animated.
 */
class WaterfallQuest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(WaterfallQuest::class.java, "waterfall_quest")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Hudon" to WfNpcs.hudon, "Golrie" to WfNpcs.golrie, "Gerald" to WfNpcs.gerald)
    }

    private val fixes =
        SimpleQuestHooks(
            rewrites = {
                it.replace("itMissing period", "it.")
                    .replace("waterfallno comma or other punctuation", "waterfall.")
                    .replace("[sir./lady./.]", "[sir/lady].")
            }
        )

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, WfNpcs.almera, "Talk-to") { talkAlmera(it) }
        onNpcOpLabel(npcTypes, WfNpcs.hudon, "Talk-to") { talkHudon(it) }
        onNpcOpLabel(npcTypes, WfNpcs.gerald, "Talk-to") { talkGerald(it) }
        onNpcOpLabel(npcTypes, WfNpcs.hadley, "Talk-to") { talkHadley(it) }
        onNpcOpLabel(npcTypes, WfNpcs.golrie, "Talk-to") { talkGolrie(it) }

        // Baxtorian Falls
        onLocOpLabel(locTypes, WfLocs.raft, "Board") { boardRaft() }
        onLocOpLabel(locTypes, WfLocs.swim_point, "Swim") { swim() }
        onLocOpLabel(locTypes, WfLocs.rock, "Swim to") { swim() }
        onLocOpLabel(locTypes, WfLocs.rock_roped, "Swim to") { swim() }
        onOpLocU(WfLocs.rock, WfObjs.rope) { ropeOnRock(it.loc) }
        onLocOpLabel(locTypes, WfLocs.dead_tree, "Climb") { climbTree() }
        onOpLocU(WfLocs.dead_tree, WfObjs.rope) { ropeOnTree() }
        onLocOpLabel(locTypes, WfLocs.barrel, "Get in") { barrel() }
        onLocOpLabel(locTypes, WfLocs.ledge_door, "Open") { ledgeDoor() }

        // Hadley's house
        onLocOpLabel(locTypes, WfLocs.bookcase, "Search") { searchBookcase() }
        onHeldOpLabel(objTypes, WfObjs.book, "Read") { readBook() }

        // Under the Tree Gnome Village
        onLocOpLabel(locTypes, WfLocs.golrie_gate, "Open") { golrieGate(it) }
        onLocOpLabel(locTypes, WfLocs.golrie_crate, "Search") {
            searchCrate(WfObjs.golrie_key, "Searching the crate with the key")
        }

        // Glarial's tomb
        onOpLocU(WfLocs.tombstone, WfObjs.pebble) { useTombstone() }
        onLocOpLabel(locTypes, WfLocs.tombstone, "Read") {
            mes("There is an indent the size of a pebble in the stone's centre.")
        }
        onLocOpLabel(locTypes, WfLocs.chest_closed, "Open") {
            locRepo.change(it, WfLocs.chest_open, CHEST_TICKS)
        }
        onLocOpLabel(locTypes, WfLocs.chest_open, "Shut") {
            locRepo.change(it, WfLocs.chest_closed, CHEST_TICKS)
        }
        onLocOpLabel(locTypes, WfLocs.chest_open, "Search") { searchChest() }
        onLocOpLabel(locTypes, WfLocs.tomb, "Search") { searchTomb() }

        // The waterfall dungeon
        onLocOpLabel(locTypes, WfLocs.exit_door, "Open") {
            runQuestSection(null, dialogue, "Leaving the Waterfall dungeon")
            telejump(LEDGE)
        }
        onLocOpLabel(locTypes, WfLocs.dungeon_crate, "Search") {
            searchCrate(WfObjs.baxtorian_key, "Searching the crate in the Waterfall dungeon")
        }
        onLocOpLabel(locTypes, WfLocs.tomb_door, "Open") { tombDoor(it) }
        for (pillar in WfLocs.pillars) {
            for ((index, rune) in WfObjs.runes.withIndex()) {
                onOpLocU(pillar, rune) { runeOnPillar(it.loc, index) }
            }
        }
        onOpLocU(WfLocs.statue, WfObjs.amulet) { amuletOnStatue() }
        onOpLocU(WfLocs.chalice, WfObjs.urn) { urnOnChalice() }
        onLocOpLabel(locTypes, WfLocs.chalice, "Take treasure") { takeTreasure() }
        onLocOpLabel(locTypes, WfLocs.chalice_ash, "Take treasure") { takeTreasure() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(WATERFALL_QUEST)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(WATERFALL_QUEST, value)
    }

    private fun ProtectedAccess.setVar(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private var ProtectedAccess.pillars: Int
        get() = player.vars[WfVarps.pillars]
        set(value) {
            VarPlayerIntMapSetter.set(player, WfVarps.pillars, value)
        }

    private fun ProtectedAccess.hasAmulet(): Boolean =
        carries(WfObjs.amulet) || invTotal(worn, WfObjs.amulet) > 0

    /** Swept over the falls and washed ashore by Gerald. */
    private suspend fun ProtectedAccess.washedAshore() {
        telejump(RIVERBANK)
        if (state == HUDON) {
            runQuestSection(null, dialogue, GERALD_WASHED, speakers = speakers)
            setVar(WfVarBits.gerald_chat, 1)
            setState(GERALD)
        }
    }

    /* Almera, Hudon, Gerald and Hadley */

    private suspend fun ProtectedAccess.talkAlmera(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "With a combat level lower than 25:" to {
                            player.appearance.combatLevel < RECOMMENDED_COMBAT
                        }
                    ),
                options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                rewrites = { fixes.rewrite(it) },
            )
        val section =
            when (state) {
                0 -> "Talking to Almera"
                STARTED -> "Talking to Almera again"
                HUDON,
                GERALD -> "Talking to Almera after talking to Hudon"
                BOOK_READ -> "Talking to Almera after reading the book"
                else -> "Talking to Almera after visiting Glarial's tomb"
            }
        runQuestSection(npc, dialogue, section, hooks)
    }

    private suspend fun ProtectedAccess.talkHudon(npc: Npc) {
        if (player.coords.z > ISLAND_NORTH_EDGE) {
            runQuestSection(null, dialogue, "Talking to Hudon while standing near the house")
            return
        }
        val section =
            when (state) {
                in 0..GERALD -> "Talking to Hudon again"
                BOOK_READ -> "Talking to Hudon after reading the book"
                TOMB -> "Talking to Hudon after visiting Glarial's tomb"
                else -> "Talking to Hudon after discovering the chalice"
            }
        runQuestSection(npc, dialogue, section, fixes)
    }

    private suspend fun ProtectedAccess.talkGerald(npc: Npc) {
        if (state == HUDON) {
            runQuestSection(npc, dialogue, GERALD_BEFORE)
            setVar(WfVarBits.gerald_chat, 1)
            setState(GERALD)
            return
        }
        runQuestSection(npc, dialogue, "Talking to Gerald")
    }

    private suspend fun ProtectedAccess.talkHadley(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        HADLEY_NO_BOOK to { !carries(WfObjs.book) },
                        HADLEY_BOOK to { carries(WfObjs.book) },
                    ),
                rewrites = { fixes.rewrite(it) },
            )
        runQuestSection(npc, dialogue, "Talking to Hadley", hooks)
        setVar(WfVarBits.hadley_chat, 1)
    }

    /* The river */

    private suspend fun ProtectedAccess.boardRaft() {
        if (state == 0) {
            runQuestSection(null, dialogue, "Boarding raft before starting quest")
            return
        }
        if (state == STARTED) {
            telejump(ISLAND)
            runQuestSection(null, dialogue, "Using the raft", speakers = speakers)
            setState(HUDON)
            return
        }
        startDialogue {
            mesbox("You board the small raft and push off down stream...")
            mesbox("...you crash into a small island.")
        }
        telejump(ISLAND)
    }

    private suspend fun ProtectedAccess.swim() {
        runQuestSection(null, dialogue, "Swimming to the rock")
        washedAshore()
    }

    private suspend fun ProtectedAccess.ropeOnRock(rock: BoundLocInfo) {
        locRepo.change(rock, WfLocs.rock_roped, ROPE_TICKS)
        mes("You tie the rope to the rock and pull yourself across.")
        telejump(ROCK)
    }

    private suspend fun ProtectedAccess.climbTree() {
        runQuestSection(null, dialogue, "Climbing the Dead Tree")
        washedAshore()
    }

    private suspend fun ProtectedAccess.ropeOnTree() {
        runQuestSection(null, dialogue, "Using rope on the Dead Tree")
        telejump(LEDGE)
    }

    private suspend fun ProtectedAccess.barrel() {
        runQuestSection(null, dialogue, "Climbing in the barrel")
        washedAshore()
    }

    private suspend fun ProtectedAccess.ledgeDoor() {
        val wearing = invTotal(worn, WfObjs.amulet) > 0
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is wearing Glarial's amulet:" to { wearing },
                        "If the player is not wearing Glarial's amulet:" to { !wearing },
                    )
            )
        runQuestSection(null, dialogue, "Opening the door to the Waterfall dungeon", hooks)
        if (!wearing) {
            washedAshore()
            return
        }
        telejump(DUNGEON)
        if (state in BOOK_READ until DUNGEON_IN) setState(DUNGEON_IN)
    }

    /* The book */

    private suspend fun ProtectedAccess.searchBookcase() {
        if (state < GERALD) {
            mes("You don't find anything interesting.")
            return
        }
        val has = carries(WfObjs.book)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have the book:" to { !has },
                        "If the player already has the book:" to { has },
                    )
            )
        runQuestSection(null, dialogue, "Searching the correct bookcase", hooks)
        if (!has) invAddOrDrop(objRepo, WfObjs.book)
    }

    private suspend fun ProtectedAccess.readBook() {
        startDialogue {
            for (page in BOOK_PAGES) mesbox(page)
        }
        if (state == GERALD) setState(BOOK_READ)
    }

    /* Golrie and the pebble */

    private suspend fun ProtectedAccess.golrieGate(gate: BoundLocInfo) {
        val outside = player.coords.z < gate.coords.z + 1
        if (outside && !carries(WfObjs.golrie_key)) {
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player doesn't have the key:" to { true },
                            "If the player has the key:" to { false },
                        )
                )
            runQuestSection(null, dialogue, GOLRIE_LOCKED, hooks, speakers)
            return
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.searchCrate(key: ObjType, section: String) {
        val has = carries(key)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have the key:" to { !has },
                        "If the player does not have the key:" to { !has },
                        "If the player has the key:" to { has },
                        "If the player already has the key:" to { has },
                    )
            )
        runQuestSection(null, dialogue, section, hooks)
        if (!has) invAddOrDrop(objRepo, key)
    }

    private suspend fun ProtectedAccess.talkGolrie(npc: Npc) {
        if (state < BOOK_READ || carries(WfObjs.pebble)) {
            startDialogue(npc) { chatNpc(neutral, "Thanks again for finding my key, traveller.") }
            return
        }
        if (!player.bit(WfVarBits.golrie_chat)) {
            runQuestSection(npc, dialogue, "Talking to Golrie")
            if (carries(WfObjs.golrie_key)) invDel(inv, WfObjs.golrie_key)
            invAddOrDrop(objRepo, WfObjs.pebble)
            setVar(WfVarBits.golrie_chat, 1)
            return
        }
        val full = inv.isFull()
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player has no inventory space:" to { full })
            )
        runQuestSection(npc, dialogue, "Talking to Golrie again without the pebble", hooks)
        if (!full) invAdd(inv, WfObjs.pebble)
    }

    private fun org.rsmod.game.entity.Player.bit(varbit: VarBitType): Boolean = vars[varbit] != 0

    /* Glarial's tomb */

    /**
     * Glarial's tomb turns away weapons, armour, runes, arrows and logs (anything worn but her
     * amulet, or carried wearables / runes / arrows / logs).
     */
    private fun ProtectedAccess.disallowedItems(): Boolean {
        val wornBad = worn.objs.filterNotNull().any { it.id != WfObjs.amulet.id }
        val invBad =
            inv.objs.filterNotNull().any { obj ->
                val type = objTypes[obj]
                val name = type.internalName.orEmpty()
                (type.wearpos1 != -1 && type.id != WfObjs.amulet.id) ||
                    name.endsWith("rune") ||
                    name.contains("arrow") ||
                    name.endsWith("logs")
            }
        return wornBad || invBad
    }

    private suspend fun ProtectedAccess.useTombstone() {
        val bad = disallowedItems()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has disallowed items:" to { bad },
                        "If the player has no disallowed items:" to { !bad },
                    )
            )
        runQuestSection(null, dialogue, "Using Glarial's pebble on Glarial's Tombstone", hooks)
        if (bad) return
        telejump(TOMB_LADDER)
        if (state == BOOK_READ) setState(TOMB)
    }

    private suspend fun ProtectedAccess.searchChest() {
        val has = hasAmulet()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have Glarial's amulet:" to { !has },
                        "If the player already has Glarial's amulet:" to { has },
                    )
            )
        runQuestSection(null, dialogue, "Searching the chest", hooks)
        if (!has) invAddOrDrop(objRepo, WfObjs.amulet)
    }

    private suspend fun ProtectedAccess.searchTomb() {
        val has = carries(WfObjs.urn) || carries(WfObjs.urn_empty)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have Glarial's urn:" to { !has },
                        "If the player already has Glarial's urn:" to { has },
                    )
            )
        runQuestSection(null, dialogue, "Searching Glarial's Tomb", hooks)
        if (!has && state < COMPLETE) invAddOrDrop(objRepo, WfObjs.urn)
    }

    /* Baxtorian's tomb */

    private suspend fun ProtectedAccess.tombDoor(door: BoundLocInfo) {
        if (!carries(WfObjs.baxtorian_key)) {
            mes("The door is locked.")
            return
        }
        passThroughWall(door)
    }

    /** Bit for [rune] (0 air, 1 water, 2 earth) on the pillar at [pillar]. */
    private fun pillarBit(pillar: BoundLocInfo, rune: Int): Int {
        // Each chalice room has two columns of three pillars, the west column on even x and two
        // tiles apart in z, so (side, z / 2 % 3) names the six pillars uniquely within a room.
        val side = pillar.coords.x and 1
        val row = (pillar.coords.z / 2) % 3
        return (side * 3 + row) * 3 + rune
    }

    private suspend fun ProtectedAccess.runeOnPillar(pillar: BoundLocInfo, rune: Int) {
        val bit = 1 shl pillarBit(pillar, rune)
        val done = pillars and bit != 0
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has already placed that rune on that pillar:" to { done },
                        "If the player used an air rune:" to { !done && rune == AIR },
                        "If the player used a water rune:" to { !done && rune == WATER },
                        "If the player used an earth rune:" to { !done && rune == EARTH },
                    )
            )
        runQuestSection(null, dialogue, "Using runes on the pillars", hooks)
        if (done) return
        invDel(inv, WfObjs.runes[rune])
        pillars = pillars or bit
    }

    private suspend fun ProtectedAccess.amuletOnStatue() {
        val ready = pillars and ALL_RUNES == ALL_RUNES
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not yet placed all the runes on the pillars:" to {
                            !ready
                        },
                        "If the player has placed all the runes:" to { ready },
                    )
            )
        runQuestSection(null, dialogue, "Using Glarial's amulet on the Statue of Glarial", hooks)
        if (!ready) {
            washedAshore()
            return
        }
        invDel(inv, WfObjs.amulet)
        pillars = pillars or AMULET_PLACED
    }

    private suspend fun ProtectedAccess.urnOnChalice() {
        if (state >= COMPLETE) {
            runQuestSection(null, dialogue, CHALICE_EMPTY)
            return
        }
        if (pillars and AMULET_PLACED == 0) {
            takeTreasure()
            return
        }
        runQuestSection(null, dialogue, "Using Glarial's urn on the chalice")
        invDel(inv, WfObjs.urn)
        invAddOrDrop(objRepo, WfObjs.diamond, 2)
        invAddOrDrop(objRepo, WfObjs.gold_bar, 2)
        invAddOrDrop(objRepo, WfObjs.mithril_seed, MITHRIL_SEEDS)
        statAdvance(stats.attack, REWARD_XP * xpMods.get(player, stats.attack))
        statAdvance(stats.strength, REWARD_XP * xpMods.get(player, stats.strength))
        completeQuest(WATERFALL_QUEST)
    }

    private suspend fun ProtectedAccess.takeTreasure() {
        if (state >= COMPLETE) {
            runQuestSection(null, dialogue, CHALICE_EMPTY)
            return
        }
        runQuestSection(null, dialogue, "Take treasure from the chalice")
        washedAshore()
    }

    companion object {
        const val STARTED = 1
        const val HUDON = 2
        const val GERALD = 3
        const val BOOK_READ = 4
        const val TOMB = 5
        const val DUNGEON_IN = 6
        const val COMPLETE = 10

        private const val AIR = 0
        private const val WATER = 1
        private const val EARTH = 2
        private const val ALL_RUNES = (1 shl 18) - 1
        private const val AMULET_PLACED = 1 shl 18

        private const val RECOMMENDED_COMBAT = 25
        private const val REWARD_XP = 13750.0
        private const val MITHRIL_SEEDS = 40
        private const val CHEST_TICKS = 200
        private const val ROPE_TICKS = 100
        private const val ISLAND_NORTH_EDGE = 3487

        private val ISLAND = CoordGrid(2512, 3481, 0)
        private val ROCK = CoordGrid(2512, 3467, 0)
        private val LEDGE = CoordGrid(2511, 3463, 0)
        private val RIVERBANK = CoordGrid(2527, 3413, 0)
        private val TOMB_LADDER = CoordGrid(2556, 9843, 0)
        private val DUNGEON = CoordGrid(2575, 9860, 0)

        private const val GERALD_WASHED =
            "Getting washed down the waterfall after talking to Hudon"
        private const val GERALD_BEFORE =
            "Talking to Gerald again before learning about the treasure"
        private const val HADLEY_NO_BOOK =
            "If the player doesn't have the Book on Baxtorian in their inventory:"
        private const val HADLEY_BOOK =
            "If the player has the Book on Baxtorian in their inventory:"
        private const val GOLRIE_LOCKED = "Opening door to Golrie from the outside"
        private const val CHALICE_EMPTY = "Take treasure from the chalice (2)"

        private val BOOK_PAGES =
            listOf(
                "<col=000080>The Missing Relics</col><br>Many artefacts of elven history were " +
                    "lost after the Fourth Age. The greatest loss was the hidden treasure of " +
                    "Baxtorian.",
                "Another great loss was Glarial's pebble, a key which allowed her family to " +
                    "visit her tomb. It is hoped that descendants of the gnome who took it " +
                    "still have it hidden in their cave under the Tree Gnome Village.",
                "<col=000080>The Sonnet of Baxtorian</col><br>Baxtorian returned from war to " +
                    "find his wife taken. After years of searching he returned to the home he " +
                    "had made for Glarial under the Baxtorian Falls, and never returned.",
                "Only he and Glarial had the power to enter the waterfall. It's as if the " +
                    "powers of nature still work to protect him.",
                "<col=000080>The Power of Nature</col><br>Baxtorian had perfected rune lore. " +
                    "It was said that he could use the stones to control water, earth and air.",
            )

        val WATERFALL_QUEST =
            QuestDef(
                name = "Waterfall Quest",
                varp = { WfVarps.waterfall_quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "13,750 Attack XP",
                        "13,750 Strength XP",
                        "2 Diamonds",
                        "2 Gold bars",
                        "40 Mithril seeds",
                    ),
                scrollObj = { WfObjs.urn },
                listRow = "quest_waterfall",
            )
    }
}

// Every cache ref Waterfall Quest uses (re-check after cache updates).

internal object WfNpcs : NpcReferences() {
    val almera = find("almera_waterfall_quest")
    val hudon = find("hudon_waterfall_quest")
    val gerald = find("gerald_waterfall_quest")
    val hadley = find("hadley_waterfall_quest")
    val golrie = find("golrie_waterfall_quest")
}

internal object WfLocs : LocReferences() {
    val raft = find("lograft_waterfall_quest")
    val swim_point = find("waterfall_swim_point")
    val rock = find("crossing_rock_waterfall_quest")
    val rock_roped = find("crossing_rock_roped_waterfall_quest")
    val dead_tree = find("overhanging_tree1_waterfall_quest")
    val barrel = find("barrel_waterfall_quest")
    val ledge_door = find("waterfall_ledge_door")
    val bookcase = find("bookcase_waterfall_quest")
    val golrie_gate = find("golrie_gate_waterfall_quest")
    val golrie_crate = find("golrie_crate_waterfall_quest")
    val tombstone = find("glarials_tombstone_waterfall_quest")
    val chest_closed = find("glarials_chest_closed_waterfall_quest")
    val chest_open = find("glarials_chest_open_waterfall_quest")
    val tomb = find("glarials_tomb_waterfall_quest")
    val exit_door = find("baxtorian_door_waterfall_quest")
    val dungeon_crate = find("baxtorian_crate_waterfall_quest")
    val tomb_door = find("baxtorian_door_2_waterfall_quest")
    val statue = find("statue_queen_waterfall_quest")
    val chalice = find("baxtorian_chalice_waterfall_quest")
    val chalice_ash = find("baxtorian_chalice_waterfall_quest_ash")

    /* The stonepillar multiloc (on `sote`) and both its children. */
    val pillars: List<LocType> =
        listOf(
            find("stonepillar_small_waterfall_quest"),
            find("stonepillar_small_waterfall_quest_noop"),
            find("stonepillar_small_waterfall_quest_op"),
        )
}

internal object WfObjs : ObjReferences() {
    val book = find("baxtorian_book_waterfall_quest")
    val golrie_key = find("golrie_key_waterfall_quest")
    val pebble = find("glarials_pebble_waterfall_quest")
    val amulet = find("glarials_amulet_waterfall_quest")
    val urn = find("glarials_urn_full_waterfall_quest")
    val urn_empty = find("glarials_urn_empty_waterfall_quest")
    val baxtorian_key = find("baxtorian_key_waterfall_quest")
    val rope = find("rope")
    val diamond = find("diamond")
    val gold_bar = find("gold_bar")
    val mithril_seed = find("mithril_seed")

    /** Air, water, earth (pillar bit order). */
    val runes: List<ObjType> = listOf(find("airrune"), find("waterrune"), find("earthrune"))
}

internal object WfVarps : VarpReferences() {
    val waterfall_quest = find("waterfall_quest")

    /** Server varp (65778): pillar runes (bits 0-17) and the statue's amulet (bit 18). */
    val pillars = find("waterfall_pillars")
}

internal object WfVarBits : VarBitReferences() {
    val gerald_chat = find("waterfall_gerald_chat")
    val hadley_chat = find("waterfall_hadley_chat")
    val golrie_chat = find("waterfall_golrie_chat")
}

internal object WfVarpBuilds : VarpBuilder() {
    init {
        build("waterfall_pillars")
    }
}
