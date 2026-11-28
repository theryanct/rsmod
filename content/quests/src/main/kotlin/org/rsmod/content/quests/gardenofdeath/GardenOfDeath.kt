package org.rsmod.content.quests.gardenofdeath

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
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
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.interf.InterfaceType
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * The Garden of Death (OSRS Wiki). Progress lives in the cache quest varbit `tgod` (varp
 * `tgod_primary` bits 0-8, db end state 56; `tgod_fully_translated` is bit 9). Our stages follow
 * the cache multilocs: the first garden's searchables open at 13, the Molch Island hole shows at
 * 15, the vines are inspectable to 20, cuttable at 22 and passable from 23, the second garden opens
 * at 27, Xeric's Shrine hole shows at 29, the third garden opens at 37, the Ruins of Morra hole at
 * 39, the fourth garden's note rubble at 43 and the rest at 47.
 * - 2 the tent's journal (Farming 20); 4 read it (the hole below the camp opens to you); 6 the
 *   first stone tablet; 13 read it and take Kasonde's word translations; 15 its words translated.
 * - 22 the note by the vines, 23 vines cut (secateurs), 25 tablet two, 27 read, 29 translated.
 * - 31 tablet three, 37 read, 39 translated; 41 tablet four, 43 read, 47 the third dirty note,
 *   49 translated, 51 the warning note taken from the translated tablet; reading it: 56, 1 QP,
 *   10,000 Farming XP (db stat_xp_awarded 100000 tenths).
 * Words seen and words translated live in server varps `tgod_seen` / `tgod_known` (65990/65991),
 * one bit per word in [WORDS] order. Translating means typing an English meaning ("island").
 *
 * Side content: the Lake Molch boaty (Molch, Molch Island, Battlefront, Shayzien), the four
 * holes and their exits, the vines, the camping equipment's secateurs.
 *
 * Simplified: the word translations scroll and stone tablets are message boxes (translated words
 * shown green) rather than their interfaces; which carving shows which words is authored from the
 * carvings' names (the Wiki only lists the words per garden); arrival tiles are the nearest free
 * tile to each hole, exit and boat.
 */
class GardenOfDeath
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val collision: CollisionFlagMap,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(GardenOfDeath::class.java, "garden_of_death") }

    override fun ScriptContext.startup() {
        // The campsite.
        onLocOpLabel(locTypes, TgodLocs.tent, "Search") { searchTent() }
        onLocOpLabel(locTypes, TgodLocs.camping_equipment, "Search") { searchEquipment() }
        onHeldOpLabel(objTypes, TgodObjs.journal, "Read") { readJournal() }

        // Holes in and out (garden 1 is a plain loc, 2-4 multilocs on tgod).
        onLocOpLabel(locTypes, TgodLocs.garden_1_entry, "Climb-down") { enterGarden(0) }
        for ((index, entry) in TgodLocs.later_entries.withIndex()) onOpLoc1(entry) { enterGarden(index + 1) }
        for ((index, exit) in TgodLocs.exits.withIndex()) onLocOpLabel(locTypes, exit, "Climb-up") { leaveGarden(index) }

        // Stone tablets and their tables.
        for ((index, table) in TgodLocs.tablet_tables.withIndex()) {
            onLocOpLabel(locTypes, table, "Search") { searchTabletTable(index) }
        }
        for ((index, tablet) in TgodObjs.tablets.withIndex()) {
            onHeldOpLabel(objTypes, tablet, "Read") { readTablet(index) }
        }
        onHeldOpLabel(objTypes, TgodObjs.translations, "Read") { readTranslations() }

        // The gardens' searchables (multilocs on tgod: the parent handles every child's Search).
        for (find in FINDS) onOpLoc1(find.loc()) { search(find) }
        for (carving in CARVINGS) onHeldOpLabel(objTypes, carving.obj(), "Inspect") { inspect(carving) }
        onHeldOpLabel(objTypes, TgodObjs.compass, "Inspect") { inspectCompass() }
        for ((obj, pages) in NOTES) onHeldOpLabel(objTypes, obj(), "Read") { startDialogue { for (p in pages) mesbox(p) } }
        onHeldOpLabel(objTypes, TgodObjs.warning_note, "Read") { readWarningNote() }

        // The vines in the second garden (multiloc on tgod: Inspect / Cut / Squeeze-through, Search).
        onOpLoc1(TgodLocs.vines) { vines(it.loc) }
        onOpLoc2(TgodLocs.vines) { vineNote() }

        // The Lake Molch boaty.
        onLocOpLabel(locTypes, TgodLocs.boaty, "Board") { boaty(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(GARDEN_OF_DEATH)

    private fun ProtectedAccess.advance(value: Int) {
        if (state < value) player.setQuestState(GARDEN_OF_DEATH, value)
    }

    private fun ProtectedAccess.seen(): Int = player.vars[TgodVarps.seen]

    private fun ProtectedAccess.known(): Int = player.vars[TgodVarps.known]

    private fun ProtectedAccess.markSeen(words: List<String>) {
        var bits = seen()
        for (word in words) bits = bits or bit(word)
        VarPlayerIntMapSetter.set(player, TgodVarps.seen, bits)
    }

    private fun ProtectedAccess.isKnown(word: String): Boolean =
        word in DEFAULT_KNOWN || (known() and bit(word)) != 0

    /* The campsite */

    private suspend fun ProtectedAccess.searchTent() {
        if (state > 0) {
            if (state < COMPLETE && !carries(TgodObjs.journal) && !inv.isFull()) {
                invAdd(inv, TgodObjs.journal)
                mes("You search the tent and find a journal. You take it.")
            } else {
                mes("You search the tent but find nothing of interest.") // Authored (not Wiki).
            }
            return
        }
        if (statBase(stats.farming) < FARMING_REQ) {
            mes("You need a Farming level of at least $FARMING_REQ to start this quest.") // Authored.
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        var start = false
        runQuestSection(null, dialogue, "Searching the tent", SimpleQuestHooks(options = mapOf("Yes." to { start = true })))
        if (start) {
            invAdd(inv, TgodObjs.journal)
            advance(STARTED)
        }
    }

    private suspend fun ProtectedAccess.searchEquipment() {
        val has = TgodObjs.secateurs.any { carries(it) || invTotal(worn, it) > 0 }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the players does not have secateurs in their inventory:" to { !has },
                        "If the player already has secateurs in their inventory:" to { has },
                    )
            )
        if (!has && inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Searching the camping equipment", hooks)
        if (!has) invAdd(inv, TgodObjs.secateurs.first())
    }

    private suspend fun ProtectedAccess.readJournal() {
        startDialogue { for (page in JOURNAL_PAGES) mesbox(page) }
        if (state == STARTED) advance(JOURNAL)
    }

    /* Holes */

    private suspend fun ProtectedAccess.enterGarden(index: Int) {
        if (index == 0 && state < JOURNAL) {
            runQuestSection(null, dialogue, "Climbing down hole before reading journal")
            return
        }
        if (state < ENTRY_STATES[index]) return
        mes("You climb down the hole.") // Authored (not Wiki).
        delay(1)
        telejump(freeNear(GARDEN_ARRIVALS[index]))
    }

    private suspend fun ProtectedAccess.leaveGarden(index: Int) {
        delay(1)
        telejump(freeNear(SURFACE_ARRIVALS[index]))
    }

    /** The nearest tile to [centre] that isn't blocked (holes, boats and exits sit on blocked tiles). */
    private fun freeNear(centre: CoordGrid): CoordGrid {
        for (radius in 0..FREE_RADIUS) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                    val tile = centre.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) == 0) return tile
                }
            }
        }
        return centre
    }

    /* Stone tablets */

    private suspend fun ProtectedAccess.searchTabletTable(index: Int) {
        val tablet = TgodObjs.tablets[index]
        if (state < TABLE_STATES[index] || carries(tablet)) {
            mes("You search the table but find nothing of interest.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        startDialogue { mesbox("You search the table and find a stone tablet. You take it.") }
        invAdd(inv, tablet)
        advance(TABLET_FOUND[index])
    }

    private suspend fun ProtectedAccess.readTablet(index: Int) {
        val text = TABLETS[index].joinToString(" ") { (word, english) -> render(word, english) }
        startDialogue { mesbox(text) }
        when {
            index == 0 && state < COMPLETE && !carries(TgodObjs.translations) -> {
                var take = false
                runQuestSection(null, dialogue, "Reading the stone tablet", SimpleQuestHooks(options = mapOf("Yes." to { take = true })))
                if (take) {
                    if (inv.isFull()) {
                        mes("You don't have enough inventory space.") // Authored (not Wiki).
                        return
                    }
                    invAdd(inv, TgodObjs.translations)
                    advance(G1_OPEN)
                }
            }
            index == 1 && state == TABLET2 -> advance(G2_OPEN)
            index == 2 && state == TABLET3 -> advance(G3_OPEN)
            index == 3 && state == TABLET4 -> advance(G4_NOTE)
            index == 3 && state >= G4_DONE && state < COMPLETE && !carries(TgodObjs.warning_note) -> warningNote()
        }
        checkProgress()
    }

    /** One tablet word: its English (green) once translated, else the Old One word. */
    private fun ProtectedAccess.render(word: String, english: String): String {
        val key = word.lowercase().trimEnd('.')
        return if (isKnown(key)) "<col=$GREEN>$english</col>" else word
    }

    private suspend fun ProtectedAccess.warningNote() {
        val full = inv.isFull()
        var take = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has no inventory space:" to { full },
                        "If the player has inventory space:" to { !full },
                    ),
                options = mapOf("Yes." to { take = true }),
            )
        runQuestSection(null, dialogue, "Reading the stone tablet after translating it", hooks)
        if (take && !full) {
            invAdd(inv, TgodObjs.warning_note)
            advance(WARNING)
        }
    }

    private suspend fun ProtectedAccess.readWarningNote() {
        startDialogue { for (page in WARNING_PAGES) mesbox(page) }
        if (state in WARNING until COMPLETE) {
            runQuestSection(null, dialogue, "Reading the warning note")
            statAdvance(stats.farming, FARMING_XP * xpMods.get(player, stats.farming))
            completeQuest(GARDEN_OF_DEATH)
        }
    }

    /* Word translations */

    private suspend fun ProtectedAccess.readTranslations() {
        val lines =
            DEFAULT_KNOWN.filter { it != "zema-tal" }.map { "${cap(it)} - ${MEANINGS.getValue(it).joinToString("/")}" } +
                WORDS.filter { (seen() and bit(it)) != 0 || isKnown(it) }
                    .map { if (isKnown(it)) "${cap(it)} - ${MEANINGS.getValue(it).joinToString("/")}" else "${cap(it)} - ???" }
        var attempt = false
        startDialogue {
            mesbox(lines.joinToString("<br>"))
            attempt = choice2("Attempt translation.", true, "Close.", false, title = "Select an option")
        }
        while (attempt) {
            val input = stringDialog("Enter a possible translation for a word you've found:").trim().lowercase()
            val word = WORDS.firstOrNull { input in MEANINGS.getValue(it) }
            val result =
                when {
                    word == null -> "You can't think of any suitable translations for that word."
                    (seen() and bit(word)) == 0 && !isKnown(word) ->
                        "You feel like that word has potential, but you can't think of any suitable " +
                            "translations for it at the moment."
                    isKnown(word) -> "You've already discovered that translation: ${cap(input)} = ${cap(word)}" // Authored.
                    else -> {
                        VarPlayerIntMapSetter.set(player, TgodVarps.known, known() or bit(word))
                        "You've discovered a new translation: ${cap(input)} = ${cap(word)}"
                    }
                }
            startDialogue {
                mesbox(result)
                attempt = choice2("Yes.", true, "No.", false, title = "Attempt another translation?")
            }
            checkProgress()
        }
    }

    /** Moves the quest on once the current garden's words are all translated. */
    private suspend fun ProtectedAccess.checkProgress() {
        for ((index, open) in OPEN_STATES.withIndex()) {
            if (state != open) continue
            if (GARDEN_WORDS[index].all { isKnown(it) }) {
                advance(DONE_STATES[index])
                startDialogue {
                    mesbox("You feel that you've translated enough words to read some of the text on the stone tablet.")
                }
            }
        }
        if (player.vars[TgodVarBits.fully_translated] == 0 && WORDS.all { isKnown(it) }) {
            VarPlayerIntMapSetter.set(player, TgodVarBits.fully_translated, 1)
            runQuestSection(null, dialogue, "Fully translating stone tablets")
        }
    }

    /* The gardens' searchables */

    private suspend fun ProtectedAccess.search(find: Find) {
        if (state < find.opens) return
        val obj = find.obj?.invoke()
        if (obj == null) {
            // A labelled chest or vase: nothing to take, but its label names words.
            markSeen(find.words)
            startDialogue { mesbox(find.text) }
            return
        }
        if (carries(obj)) {
            mes("You search but find nothing else of interest.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        startDialogue { mesbox(find.text) }
        invAdd(inv, obj)
        if (find.advanceTo > 0) advance(find.advanceTo)
    }

    private fun ProtectedAccess.inspect(carving: Carving) {
        markSeen(carving.words)
        ifOpenMainModal(carving.interf())
    }

    private suspend fun ProtectedAccess.inspectCompass() {
        markSeen(listOf("makt", "uitt", "takam", "silam"))
        startDialogue {
            mesbox("Rather than the usual labels, the compass points are instead marked as 'Makt', 'Uitt', 'Takam' and 'Silam'.")
        }
    }

    /* The vines */

    private suspend fun ProtectedAccess.vines(loc: BoundLocInfo) {
        when {
            state < G1_DONE -> return
            state < VINE_NOTE -> vineNote()
            state < VINES_CUT -> {
                if (TgodObjs.secateurs.none { carries(it) || invTotal(worn, it) > 0 }) {
                    mes("You need some secateurs to cut through these vines.") // Authored (not Wiki).
                    return
                }
                startDialogue { mesbox("You cut through the vines.") }
                advance(VINES_CUT)
            }
            else -> passThroughWall(loc)
        }
    }

    private suspend fun ProtectedAccess.vineNote() {
        if (state < G1_DONE) return
        if (carries(TgodObjs.vine_note)) {
            mes("You search the vines but find nothing of interest.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        startDialogue { mesbox("You spot a note on the floor near the vines. You take it.") }
        invAdd(inv, TgodObjs.vine_note)
        advance(VINE_NOTE)
    }

    /* The Lake Molch boaty */

    private suspend fun ProtectedAccess.boaty(loc: BoundLocInfo) {
        val here = DOCKS.indexOfFirst { it.boat.chebyshevDistance(loc.coords) <= 2 }
        val choices = DOCKS.withIndex().filter { it.index != here }
        var picked = -1
        startDialogue {
            picked =
                choice3(
                    choices[0].value.name,
                    choices[0].index,
                    choices[1].value.name,
                    choices[1].index,
                    choices[2].value.name,
                    choices[2].index,
                    title = "Where would you like to go?",
                )
        }
        val dock = DOCKS.getOrNull(picked) ?: return
        mes("You row the boat across the lake.") // Authored (not Wiki).
        delay(2)
        telejump(freeNear(dock.boat))
    }

    private class Dock(val name: String, val boat: CoordGrid)

    /** A searchable: [obj] taken (or, without one, a label naming [words]), from state [opens]. */
    class Find(
        val loc: () -> LocType,
        val opens: Int,
        val text: String,
        val obj: (() -> ObjType)? = null,
        val words: List<String> = emptyList(),
        val advanceTo: Int = 0,
    )

    class Carving(val obj: () -> ObjType, val interf: () -> InterfaceType, val words: List<String>)

    companion object {
        const val STARTED = 2
        const val JOURNAL = 4
        const val TABLET1 = 6
        const val G1_OPEN = 13
        const val G1_DONE = 15
        const val VINE_NOTE = 22
        const val VINES_CUT = 23
        const val TABLET2 = 25
        const val G2_OPEN = 27
        const val G2_DONE = 29
        const val TABLET3 = 31
        const val G3_OPEN = 37
        const val G3_DONE = 39
        const val TABLET4 = 41
        const val G4_NOTE = 43
        const val G4_OPEN = 47
        const val G4_DONE = 49
        const val WARNING = 51
        const val COMPLETE = 56

        private const val FARMING_REQ = 20
        private const val FARMING_XP = 10000.0
        private const val FREE_RADIUS = 4
        private const val GREEN = "00ff00"
        private const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC

        private val ENTRY_STATES = listOf(JOURNAL, G1_DONE, G2_DONE, G3_DONE)
        private val TABLE_STATES = listOf(JOURNAL, VINES_CUT, G2_DONE, G3_DONE)
        private val TABLET_FOUND = listOf(TABLET1, TABLET2, TABLET3, TABLET4)
        private val OPEN_STATES = listOf(G1_OPEN, G2_OPEN, G3_OPEN, G4_OPEN)
        private val DONE_STATES = listOf(G1_DONE, G2_DONE, G3_DONE, G4_DONE)

        /** Beside each garden's exit ladder (1309,9867 / 1374,10033 / 1294,10033 / 1457,9823). */
        private val GARDEN_ARRIVALS =
            listOf(
                CoordGrid(1309, 9869, 0),
                CoordGrid(1374, 10031, 0),
                CoordGrid(1294, 10031, 0),
                CoordGrid(1455, 9823, 0),
            )

        /** Beside each hole: the campsite, Molch Island, Xeric's Shrine, the Ruins of Morra. */
        private val SURFACE_ARRIVALS =
            listOf(
                CoordGrid(1308, 3469, 0),
                CoordGrid(1364, 3639, 0),
                CoordGrid(1314, 3619, 0),
                CoordGrid(1450, 3509, 0),
            )

        private val DOCKS =
            listOf(
                Dock("Molch", CoordGrid(1343, 3645, 0)),
                Dock("Molch Island", CoordGrid(1368, 3640, 0)),
                Dock("Battlefront", CoordGrid(1383, 3662, 0)),
                Dock("Shayzien", CoordGrid(1405, 3611, 0)),
            )

        /** Kasonde's word translations (Wiki "Transcript:Word translations"). */
        private val MEANINGS: Map<String, List<String>> =
            linkedMapOf(
                "osto" to listOf("below", "under", "beneath"),
                "tasol" to listOf("structure", "building"),
                "kamat" to listOf("method", "process"),
                "moki" to listOf("need", "require"),
                "zema-tal" to listOf("zema-tal"),
                "ikam" to listOf("island"),
                "ates" to listOf("lake", "pond", "water"),
                "miki" to listOf("time", "death", "die", "kill"),
                "toka" to listOf("vessel", "container", "holder"),
                "tlane" to listOf("garden", "plant", "tree", "forest", "wood"),
                "makt" to listOf("north"),
                "silam" to listOf("west"),
                "achi" to listOf("poison"),
                "ayak" to listOf("animal", "creature"),
                "olkat" to listOf("body"),
                "kualt" to listOf("feed", "food", "eat"),
                "xali" to listOf("earth", "land", "ground"),
                "ehke" to listOf("air", "wind"),
                "tepat" to listOf("fire", "destroy", "break"),
                "chua" to listOf("create", "build", "make"),
                "kemo" to listOf("yes", "success", "right", "correct"),
                "ami" to listOf("no", "failure", "wrong", "incorrect"),
                "lini" to listOf("move", "transfer", "send", "deliver"),
                "xita" to listOf("arrive", "here"),
                "takam" to listOf("east"),
                "uitt" to listOf("south"),
                "amok" to listOf("little", "small", "few"),
                "siua" to listOf("big", "many", "lots", "plenty"),
                "ralo" to listOf("light", "sun"),
                "rani" to listOf("dark", "moon"),
                "tal" to listOf("life", "soul"),
                "yoka" to listOf("knowledge", "mind"),
                "antil" to listOf("home"),
            )

        /** Known from the start (Kasonde's note already translates them). */
        private val DEFAULT_KNOWN = listOf("osto", "tasol", "kamat", "moki", "zema-tal")

        /** Translatable words, one bit each in tgod_seen / tgod_known. */
        private val WORDS: List<String> = MEANINGS.keys.filter { it !in DEFAULT_KNOWN }

        private fun bit(word: String): Int = 1 shl WORDS.indexOf(word)

        private fun cap(text: String): String = text.replaceFirstChar { it.uppercase() }

        /** The words each garden's translation needs (Wiki walkthrough). */
        private val GARDEN_WORDS =
            listOf(
                listOf("ikam", "ates", "miki", "toka", "tlane", "makt"),
                listOf("silam", "achi", "ayak", "olkat", "kualt", "xali", "ehke", "tepat"),
                listOf("chua", "kemo", "ami", "lini", "xita", "takam", "uitt"),
                listOf("amok", "siua", "ralo", "rani", "tal", "miki", "yoka", "antil"),
            )

        /** The four tablets, word by word with their Wiki translations. */
        private val TABLETS: List<List<Pair<String, String>>> =
            listOf(
                pairs("Amok miki. Tal toka kemo. Lini toka makt. Tal tlane osto ates ikam.",
                    "Little time. Soul vessel success. Send vessel north. Life garden below lake island."),
                pairs("Siua olkat moki. Olkat moki tal. Kualt olkat tal toka. Moki yoka tlane lini kamat. Silam osto achi ates xali.",
                    "Many body needed. Body need life. Feed body soul vessel. Need knowledge garden transfer method. West below poison water land."),
                pairs("Lini kamat kemo. Olkat kemo. Tal toka kemo. Miki xita. Miki achi moki. Miki chua tal. Miki tlane lini. Uitt takam osto tlane tasol.",
                    "Transfer method success. Body success. Soul vessel success. Time arrived. Death poison needed. Death create life. Death garden deliver. South east below forest building."),
                pairs("Miki achi ami. Tlane chua rani miki. Lini antil. Antil tepat. Ami tal. Tal toka ami. Zema-Tal tepat.",
                    "Death poison fail. Plant create dark death. Send home. Home destroyed. No life. Soul vessel fail. Zema-Tal destroyed."),
            )

        private fun pairs(old: String, english: String): List<Pair<String, String>> {
            val a = old.split(" ")
            val b = english.split(" ")
            check(a.size == b.size) { "Tablet word counts differ: $old" }
            return a.zip(b)
        }

        /** Carvings: the words each shows (authored from the carving names; see class doc). */
        val CARVINGS =
            listOf(
                Carving({ TgodObjs.g1_lake_map }, { TgodInterfaces.g1_lake_map }, listOf("ikam", "ates", "makt")),
                Carving({ TgodObjs.g1_plant }, { TgodInterfaces.g1_plant }, listOf("tlane")),
                Carving({ TgodObjs.g2_forest_map }, { TgodInterfaces.g2_forest_map }, listOf("silam", "xali")),
                Carving({ TgodObjs.g2_poison }, { TgodInterfaces.g2_poison }, listOf("ayak", "achi")),
                Carving({ TgodObjs.g2_rune }, { TgodInterfaces.g2_rune }, listOf("ehke", "tepat")),
                Carving({ TgodObjs.g3_bucket }, { TgodInterfaces.g3_bucket }, listOf("kemo", "ami")),
                Carving({ TgodObjs.g3_carving }, { TgodInterfaces.g3_carving }, listOf("chua")),
                Carving({ TgodObjs.g3_package }, { TgodInterfaces.g3_package }, listOf("xita")),
                Carving({ TgodObjs.g3_transfer }, { TgodInterfaces.g3_transfer }, listOf("lini")),
                Carving({ TgodObjs.g4_creature }, { TgodInterfaces.g4_creature }, listOf("tal", "miki")),
                Carving({ TgodObjs.g4_delivery }, { TgodInterfaces.g4_delivery }, listOf("antil")),
                Carving({ TgodObjs.g4_light }, { TgodInterfaces.g4_light }, listOf("ralo", "rani")),
                Carving({ TgodObjs.g4_recycling }, { TgodInterfaces.g4_recycling }, listOf("amok", "siua")),
                Carving({ TgodObjs.g4_rune }, { TgodInterfaces.g4_rune }, listOf("yoka")),
            )

        private const val TABLE_CARVING = "You search the table and find a carving. You take it."
        private const val VASE_CARVING = "You find a carving next to the vase. You take it."
        private const val RUBBLE_CARVING = "You search the rubble and find a carving. You take it."

        /** Every searchable multiloc (Wiki transcript boxes; the poison chest's is authored). */
        val FINDS =
            listOf(
                Find({ TgodLocs.g1_lake_map_table }, G1_OPEN, TABLE_CARVING, { TgodObjs.g1_lake_map }),
                Find({ TgodLocs.g1_plant_table }, G1_OPEN, TABLE_CARVING, { TgodObjs.g1_plant }),
                Find({ TgodLocs.g1_note_vase }, G1_OPEN, "You find a note next to the vase. You take it.", { TgodObjs.hint_note }),
                Find({ TgodLocs.g1_hourglass_chest }, G1_OPEN,
                    "The chest contains some hourglasses. A label on the chest reads 'Miki Toka'.", words = listOf("miki", "toka")),
                Find({ TgodLocs.g2_forest_map_table }, G2_OPEN, TABLE_CARVING, { TgodObjs.g2_forest_map }),
                Find({ TgodLocs.g2_poison_vase }, G2_OPEN, VASE_CARVING, { TgodObjs.g2_poison }),
                Find({ TgodLocs.g2_rune_rubble }, G2_OPEN, RUBBLE_CARVING, { TgodObjs.g2_rune }),
                Find({ TgodLocs.g2_compost_vase }, G2_OPEN,
                    "The vase seems to contain some very old compost. A label on the vase reads 'Tlane Kualt'.", words = listOf("tlane", "kualt")),
                Find({ TgodLocs.g2_rune_chest }, G2_OPEN,
                    "The chest contains some body runes. A label on the chest reads 'Olkat'.", words = listOf("olkat")),
                Find({ TgodLocs.g2_poison_chest }, G2_OPEN,
                    "The chest contains some old poison. A label on the chest reads 'Achi'.", words = listOf("achi")),
                Find({ TgodLocs.g3_bucket_table }, G3_OPEN, TABLE_CARVING, { TgodObjs.g3_bucket }),
                Find({ TgodLocs.g3_carving_table }, G3_OPEN, TABLE_CARVING, { TgodObjs.g3_carving }),
                Find({ TgodLocs.g3_package_vase }, G3_OPEN, VASE_CARVING, { TgodObjs.g3_package }),
                Find({ TgodLocs.g3_transfer_mushroom }, G3_OPEN,
                    "You find a carving next to the mushroom. You take it.", { TgodObjs.g3_transfer }),
                Find({ TgodLocs.g3_compass_chest }, G3_OPEN, "You search the chest and find a compass. You take it.", { TgodObjs.compass }),
                Find({ TgodLocs.g4_creature_table }, G4_OPEN, TABLE_CARVING, { TgodObjs.g4_creature }),
                Find({ TgodLocs.g4_recycling_table }, G4_OPEN, TABLE_CARVING, { TgodObjs.g4_recycling }),
                Find({ TgodLocs.g4_light_chest }, G4_OPEN, "You search the chest and find a carving. You take it.", { TgodObjs.g4_light }),
                Find({ TgodLocs.g4_rune_vase }, G4_OPEN, VASE_CARVING, { TgodObjs.g4_rune }),
                Find({ TgodLocs.g4_delivery_rubble }, G4_OPEN, RUBBLE_CARVING, { TgodObjs.g4_delivery }),
                Find({ TgodLocs.g4_teleport_chest }, G4_OPEN,
                    "The chest contains what look to be home teleport tablets. A label on the chest reads 'Antil'.", words = listOf("antil")),
                Find({ TgodLocs.g4_note_rubble }, G4_NOTE, "You search the rubble and find a note. You take it.",
                    { TgodObjs.strangle_note }, advanceTo = G4_OPEN),
            )

        /** Kasonde's journal (Wiki "Transcript:Kasonde's journal"). */
        private val JOURNAL_PAGES =
            listOf(
                "For many years now, much of my work has been defined by a single question: who were " +
                    "the Old Ones? Find your way into the southern Kingdom of Varlamore, and you'll be " +
                    "presented with a huge range of interpretations. However, here in Kourend, the " +
                    "answer is far more interesting. Why? Because there's no answer at all.",
                "The great Arceuus Library has more books than even an immortal would have time to " +
                    "read, but not a single one mentions the Old Ones. How is it that the all-knowing " +
                    "citizens of Arceuus have no knowledge of this mysterious, forgotten race?",
                "Head into the Kebos Lowlands, and you'll soon find yourself surrounded by lizardman " +
                    "architecture, or at least, that's what the historians of Arceuus would have you " +
                    "believe. It bears more than a few similarities to the Old One ruins found in " +
                    "Varlamore.",
                "My search has been long and arduous, but thanks to a new discovery near Quidamortem, " +
                    "I may finally be close to some answers. It's time to uncover the truth.",
            )

        /** The three dirty notes (Wiki "Transcript:Dirty note"). */
        private val NOTES: List<Pair<() -> ObjType, List<String>>> =
            listOf(
                { TgodObjs.hint_note } to
                    listOf(
                        "This language is fascinating. I've already managed to translate a few words, " +
                            "but it's going to take a lot more to discover anything of use.",
                        "From what I've found so far, it seems that most words are quite generic and " +
                            "often have multiple connected meanings depending on the context. For " +
                            "example, change things slightly and a word for 'sea' might suddenly mean " +
                            "'river' instead. Change things even more, and it could become 'cold'.",
                    ),
                { TgodObjs.vine_note } to
                    listOf(
                        "Vines! Vines everywhere! I'm on the verge of greatness here, but something " +
                            "always gets in the way.",
                        "Upon returning to camp to pick up some secateurs, I found my journal had gone " +
                            "missing. It seems I'm not alone in my search. I'll need to be cautious.",
                    ),
                { TgodObjs.strangle_note } to
                    listOf(
                        "There's something different about this place. Something familiar. A stench of " +
                            "death that's hard to forget. Could this be where the Strangler was born?"
                    ),
            )

        /** Kasonde's warning note (Wiki "Transcript:Warning note"). */
        private val WARNING_PAGES =
            listOf(
                "Did you think I wouldn't know you've been following me? Yes, you: the one reading " +
                    "this note. Maybe you thought I was a fool, or maybe you're the fool yourself. " +
                    "Either way, like me, you've surely now discovered the truth.",
                "The Ascended of Arceuus were not the first to discover soul transference, after all. " +
                    "That honour belongs to the Old Ones. Had they not destroyed themselves, they might " +
                    "still be walking among us today.",
                "Make no mistake, I will find the remaining knowledge left by the Old Ones. I will " +
                    "discover the secrets of soul transference. As for you, I will only consider you one " +
                    "of two things. Either you're my ally, or my enemy. Only time will tell.<br>Until " +
                    "we cross paths again,<br>Kasonde Shaw",
            )

        val GARDEN_OF_DEATH =
            QuestDef(
                name = "The Garden of Death",
                varbit = { TgodVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("10,000 Farming XP"),
                scrollObj = { TgodObjs.warning_note },
                listRow = "quest_gardenofdeath",
            )
    }
}

// Every cache ref The Garden of Death uses (re-check after cache updates).
internal object TgodLocs : LocReferences() {
    val tent = find("tgod_tent")
    val camping_equipment = find("tgod_camping_equipment")
    val garden_1_entry = find("tgod_garden_1_entry")
    /** Multilocs on tgod (hidden until the garden before is translated). */
    val later_entries = listOf(find("tgod_garden_2_entry"), find("tgod_garden_3_entry"), find("tgod_garden_4_entry"))
    val exits =
        listOf(
            find("tgod_garden_1_exit"),
            find("tgod_garden_2_exit"),
            find("tgod_garden_3_exit"),
            find("tgod_garden_4_exit"),
        )
    val tablet_tables =
        listOf(
            find("tgod_garden_1_tablet_table"),
            find("tgod_garden_2_tablet_table"),
            find("tgod_garden_3_tablet_table"),
            find("tgod_garden_4_tablet_table"),
        )
    val vines = find("tgod_vines")
    val boaty = find("aerial_fishing_boat")

    val g1_lake_map_table = find("tgod_garden_1_lake_map_table")
    val g1_plant_table = find("tgod_garden_1_plant_diagram_table")
    val g1_hourglass_chest = find("tgod_garden_1_hourglass_chest")
    val g1_note_vase = find("tgod_garden_1_note_vase")
    val g2_forest_map_table = find("tgod_garden_2_forest_map_table")
    val g2_poison_vase = find("tgod_garden_2_poison_diagram_vase")
    val g2_rune_rubble = find("tgod_garden_2_rune_diagram_rubble")
    val g2_rune_chest = find("tgod_garden_2_rune_chest")
    val g2_poison_chest = find("tgod_garden_2_poison_chest")
    val g2_compost_vase = find("tgod_garden_2_compost_vase")
    val g3_bucket_table = find("tgod_garden_3_bucket_diagram_table")
    val g3_carving_table = find("tgod_garden_3_carving_diagram_table")
    val g3_package_vase = find("tgod_garden_3_package_diagram_vase")
    val g3_transfer_mushroom = find("tgod_garden_3_transfer_diagram_mushroom")
    val g3_compass_chest = find("tgod_garden_3_compass_chest")
    val g4_creature_table = find("tgod_garden_4_creature_diagram_table")
    val g4_recycling_table = find("tgod_garden_4_recycling_diagram_table")
    val g4_light_chest = find("tgod_garden_4_light_diagram_chest")
    val g4_rune_vase = find("tgod_garden_4_rune_diagram_vase")
    val g4_delivery_rubble = find("tgod_garden_4_delivery_diagram_rubble")
    val g4_teleport_chest = find("tgod_garden_4_teleport_chest")
    val g4_note_rubble = find("tgod_garden_4_note_rubble")
}

internal object TgodObjs : ObjReferences() {
    val journal = find("tgod_journal")
    val translations = find("tgod_translations")
    val hint_note = find("tgod_hint_note")
    val vine_note = find("tgod_vine_note")
    val strangle_note = find("tgod_strangle_note")
    val warning_note = find("tgod_final_note")
    val tablets = listOf(find("tgod_tablet_1"), find("tgod_tablet_2"), find("tgod_tablet_3"), find("tgod_tablet_4"))
    val compass = find("tgod_garden_3_compass")
    val secateurs = listOf(find("secateurs"), find("fairy_enchanted_secateurs"))

    val g1_plant = find("tgod_garden_1_plant_diagram")
    val g1_lake_map = find("tgod_garden_1_lake_map")
    val g2_forest_map = find("tgod_garden_2_forest_map")
    val g2_poison = find("tgod_garden_2_poison_diagram")
    val g2_rune = find("tgod_garden_2_rune_diagram")
    val g3_bucket = find("tgod_garden_3_bucket_diagram")
    val g3_carving = find("tgod_garden_3_carving_diagram")
    val g3_package = find("tgod_garden_3_package_diagram")
    val g3_transfer = find("tgod_garden_3_transfer_diagram")
    val g4_creature = find("tgod_garden_4_creature_diagram")
    val g4_delivery = find("tgod_garden_4_delivery_diagram")
    val g4_light = find("tgod_garden_4_light_diagram")
    val g4_recycling = find("tgod_garden_4_recycling_diagram")
    val g4_rune = find("tgod_garden_4_rune_diagram")
}

internal object TgodInterfaces : InterfaceReferences() {
    val g1_lake_map = find("tgod_garden_1_lake_map")
    val g1_plant = find("tgod_garden_1_plant_diagram")
    val g2_forest_map = find("tgod_garden_2_forest_map")
    val g2_poison = find("tgod_garden_2_poison_diagram")
    val g2_rune = find("tgod_garden_2_rune_diagram")
    val g3_bucket = find("tgod_garden_3_bucket_diagram")
    val g3_carving = find("tgod_garden_3_carving_diagram")
    val g3_package = find("tgod_garden_3_package_diagram")
    val g3_transfer = find("tgod_garden_3_transfer_diagram")
    val g4_creature = find("tgod_garden_4_creature_diagram")
    val g4_delivery = find("tgod_garden_4_delivery_diagram")
    val g4_light = find("tgod_garden_4_light_diagram")
    val g4_recycling = find("tgod_garden_4_recycling_diagram")
    val g4_rune = find("tgod_garden_4_rune_diagram")
}

internal object TgodVarBits : VarBitReferences() {
    val quest = find("tgod")
    val fully_translated = find("tgod_fully_translated")
}

internal object TgodVarps : VarpReferences() {
    /** Server varp (65990): words seen on carvings, labels and the compass, one bit per word. */
    val seen = find("tgod_seen")
    /** Server varp (65991): words translated, one bit per word. */
    val known = find("tgod_known")
}

internal object TgodVarpBuilds : VarpBuilder() {
    init {
        build("tgod_seen")
        build("tgod_known")
    }
}
