package org.rsmod.content.quests.misthalinmystery

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.cutscene.CutsceneRunner
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
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Misthalin Mystery (OSRS Wiki). Varbit `mistmyst_progress` (cache db end state 135); the cache
 * multis switch on it (Abigale / Hewey leave Lumbridge from 16, Sid gone 20, barrel emptied 25,
 * Tayten gone + library note 35, painting slashed 45, wall blown 65, Lacey gone + outside note 70,
 * piano open 80, Mandy gone + kitchen note 90, fireplace unlit 90 / revealed 100, mirror movable
 * 111, Abigale unmasked 115 / attackable 120 / unmasked 125, Mandy outside 130). Steps without a
 * multi use the values in between: 10 started, 15 on the island, 30 inside the manor, 40 / 75 / 95
 * a riddle read, 50 candles lit, 105 gem code entered, 135 complete.
 *
 * Murders and the showdown are live-map cutscenes (cutscene framework: victim + Killer, camera,
 * the transcript dialogue), triggered by the action next to them (taking the bucket -> Sid, the
 * red topaz door -> Tayten, climbing out over the blown wall -> Lacey, the diamond (kitchen) door
 * -> Mandy, the sapphire door -> the Killer).
 * Puzzles: the knife on the right painting (ruby key), 4 candles then the
 * tinderbox on the explosive barrel, D-E-A-D on the piano (emerald key), the knife on the unlit
 * fireplace and the gem switches S-D-Z-E-O-R (sapphire key). Reward: uncut ruby / emerald /
 * sapphire, 600 Crafting xp, 1 QP.
 *
 * Simplified: the mirror showdown is three pushes of the movable mirror (no wardrobe / thrown
 * knife tracking); no explosion damage; Lacey's answer doesn't matter.
 */
class MisthalinMystery
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val cutscenes: CutsceneRunner,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(MisthalinMystery::class.java, "misthalin_mystery")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Sid" to MistNpcs.sid,
            "Tayten" to MistNpcs.tayten,
            "Lacey" to MistNpcs.lacey,
            "Mandy" to MistNpcs.mandy,
            "Killer" to MistNpcs.killer,
            "Abigale" to MistNpcs.abigale_unmasked,
            "Hewey" to MistNpcs.hewey_unmasked,
        )
    }

    private val lumbridgeSpeakers: Map<String, NpcType> by lazy {
        mapOf("Abigale" to MistNpcs.abigale_lum, "Hewey" to MistNpcs.hewey_lum)
    }

    /** Mirror pushes per player during the showdown. */
    private val mirrorHits = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, MistNpcs.abigale_lum, "Talk-to") { talkLumbridge(it) }
        onNpcOpLabel(npcTypes, MistNpcs.hewey_lum, "Talk-to") { talkLumbridge(it) }
        onLocOpLabel(locTypes, MistLocs.boat_lumbridge, "Board") { boardToIsland() }
        onLocOpLabel(locTypes, MistLocs.boat_island, "Board") { telejump(LUMBRIDGE_LANDING) }

        onLocOpLabel(locTypes, MistLocs.bucket, "Take") { takeBucket() }
        onLocOpLabel(locTypes, MistLocs.barrel_water, "Search") {
            runQuestSection(null, dialogue, "Search the barrel without emptying it")
        }
        onOpLocU(MistLocs.barrel_water, MistObjs.bucket) { emptyBarrel() }
        onLocOpLabel(locTypes, MistLocs.barrel_emptied, "Search") { searchBarrel() }
        for (door in MistLocs.front_doors) {
            onLocOpLabel(locTypes, door, "Open") { frontDoor(it) }
        }
        onLocOpLabel(locTypes, MistLocs.table_knife, "Take-knife") { takeTool(MistObjs.knife) }
        onLocOpLabel(locTypes, MistLocs.shelves, "Take-tinderbox") {
            takeTool(MistObjs.tinderbox)
        }

        for ((door, key) in MistLocs.keyed_doors) {
            onLocOpLabel(locTypes, door, "Open") { keyedDoor(it, key) }
        }
        onLocOpLabel(locTypes, MistLocs.door_redtopaz, "Open") { redTopazDoor() }
        onLocOpLabel(locTypes, MistLocs.door_diamond, "Open") { kitchenDoor() }
        for (door in MistLocs.locked_doors) {
            onLocOpLabel(locTypes, door, "Open") { mes("The door is securely locked.") }
        }

        for ((note, clue) in MistLocs.notes) {
            onLocOpLabel(locTypes, note, "Take") { takeNote(clue) }
        }
        for (clue in MistObjs.clues) {
            onHeldOpLabel(objTypes, clue, "Read") { readNote(clue) }
        }

        onOpLocU(MistLocs.painting, MistObjs.knife) { slashPainting() }
        for (painting in MistLocs.wrong_paintings) {
            onOpLocU(painting, MistObjs.knife) {
                runQuestSection(null, dialogue, "Slashing the wrong painting")
                mes("You don't want to ruin a perfectly good painting.")
            }
        }
        onLocOpLabel(locTypes, MistLocs.painting_slashed, "Search") { searchPainting() }

        onOpLocU(MistLocs.candle_unlit, MistObjs.tinderbox) { lightCandle(it.loc) }
        onOpLocU(MistLocs.explosive_barrel, MistObjs.tinderbox) { lightFuse() }
        onLocOpLabel(locTypes, MistLocs.wall_climbable, "Climb") { climbWall(it) }

        onLocOpLabel(locTypes, MistLocs.piano_closed, "Play") { playPiano() }
        onLocOpLabel(locTypes, MistLocs.piano_open, "Play") { mes("You play a gloomy tune.") }
        onLocOpLabel(locTypes, MistLocs.piano_open, "Search") { searchPiano() }

        onOpLocU(MistLocs.fireplace_unlit, MistObjs.knife) { pryFireplace() }
        onLocOpLabel(locTypes, MistLocs.fireplace_revealed, "Search") { searchFireplace() }

        onNpcOpLabel(npcTypes, MistNpcs.mirror_movable, "Push") { pushMirror() }
        onNpcOpLabel(npcTypes, MistNpcs.abigale_attackable, "Fight") { fightAbigale() }
        onNpcOpLabel(npcTypes, MistNpcs.mandy_post, "Talk-to") { talkMandy(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(MISTHALIN_MYSTERY)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(MISTHALIN_MYSTERY, value)
    }

    /**
     * A murder (or the showdown) as a live-map cutscene: the [victim] and the Killer appear at
     * [spot], the camera frames them for the transcript dialogue, then the Killer slips away.
     * Returns `false` if the scene was cut short (the quest then replays it).
     */
    private suspend fun ProtectedAccess.murderScene(
        victim: NpcType?,
        spot: CoordGrid,
        section: String,
    ): Boolean =
        cutscenes.play(this, template = null, returnTo = player.coords) {
            victim?.let { spawn(it, spot) }
            val killer = spawn(MistNpcs.killer, spot.translate(1, 0))
            killer.faceTile(spot)
            camMoveTo(spot.translate(0, -5), height = 500)
            camLookAt(spot, height = 150)
            fadeIn()
            delay(2)
            conversation { runQuestSection(null, dialogue, section, speakers = speakers) }
            killer.walkTo(spot.translate(5, 0))
            delay(3)
            fadeOut()
            delay(1)
        }

    private fun ProtectedAccess.takeIfRoom(obj: ObjType): Boolean {
        if (inv.isFull()) {
            mes("You don't have enough space in your inventory.")
            return false
        }
        invAdd(inv, obj)
        return true
    }

    /* Lumbridge Swamp and the boats */

    private suspend fun ProtectedAccess.talkLumbridge(npc: Npc) {
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
        val section =
            if (state == 0) "Talking to Abigale or Hewey" else "Talking to Abigale or Hewey again"
        runQuestSection(npc, dialogue, section, hooks, lumbridgeSpeakers)
    }

    private suspend fun ProtectedAccess.boardToIsland() {
        if (state == 0) {
            runQuestSection(null, dialogue, "Trying to use the rowboat before starting the quest")
            return
        }
        mes("You row out to the island.")
        delay(BOAT_TICKS)
        telejump(ISLAND_LANDING)
        if (state == STARTED) setState(ON_ISLAND)
    }

    /* Sid, the barrel and the front door */

    private suspend fun ProtectedAccess.takeBucket() {
        if (!takeIfRoom(MistObjs.bucket)) return
        if (state == ON_ISLAND) {
            if (murderScene(MistNpcs.sid, SID_SPOT, "Sid's demise")) setState(SID_DEAD)
        }
    }

    private fun ProtectedAccess.emptyBarrel() {
        if (state != SID_DEAD) return
        invDel(inv, MistObjs.bucket)
        invAdd(inv, MistObjs.bucket_water)
        mes("You fill the bucket from the barrel.")
        setState(BARREL_EMPTY)
    }

    private fun ProtectedAccess.searchBarrel() {
        if (carries(MistObjs.manor_key) || state >= INSIDE) {
            mes("The barrel is empty.")
            return
        }
        if (takeIfRoom(MistObjs.manor_key)) mes("You find a key at the bottom of the barrel.")
    }

    private suspend fun ProtectedAccess.frontDoor(door: BoundLocInfo) {
        when {
            state >= ABIGALE_STABBED && state < MANDY_OUTSIDE -> {
                runQuestSection(null, dialogue, "Leaving the manor", speakers = speakers)
                setState(MANDY_OUTSIDE)
                passThroughWall(door)
            }
            state >= INSIDE -> passThroughWall(door)
            !carries(MistObjs.manor_key) -> mes("The door is securely locked.")
            else -> {
                setState(INSIDE)
                passThroughWall(door)
            }
        }
    }

    private fun ProtectedAccess.takeTool(obj: ObjType) {
        if (carries(obj)) {
            mes("You already have one of those.")
            return
        }
        takeIfRoom(obj)
    }

    /* Doors, notes, Tayten and the painting */

    private suspend fun ProtectedAccess.keyedDoor(door: BoundLocInfo, key: ObjType) {
        if (!carries(key)) {
            mes("The door is securely locked.")
            return
        }
        if (key == MistObjs.sapphire_key && state == GEMS_SOLVED) {
            passThroughWall(door)
            if (!murderScene(null, SHOWDOWN_SPOT, "Confronting the killer")) return
            mirrorHits.remove(player.uid.packed)
            setState(SHOWDOWN)
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.redTopazDoor() {
        if (state == INSIDE) {
            if (murderScene(MistNpcs.tayten, TAYTEN_SPOT, "Tayten's demise")) setState(TAYTEN_DEAD)
            return
        }
        mes("The door is securely locked.")
    }

    private suspend fun ProtectedAccess.kitchenDoor() {
        if (state == PIANO_OPEN && carries(MistObjs.emerald_key)) {
            if (murderScene(MistNpcs.mandy, MANDY_SPOT, "Mandy in the kitchen")) {
                setState(MANDY_DEAD)
            }
            return
        }
        mes("The door is securely locked.")
    }

    private fun ProtectedAccess.takeNote(clue: ObjType) {
        if (carries(clue)) {
            mes("You already have a copy of the note.")
            return
        }
        if (takeIfRoom(clue)) mes("You take a copy of the note.")
    }

    private suspend fun ProtectedAccess.readNote(clue: ObjType) {
        val (text, from, to) =
            when (clue) {
                MistObjs.clue_library -> Triple(NOTE_1, TAYTEN_DEAD, NOTE_1_READ)
                MistObjs.clue_outside -> Triple(NOTE_2, LACEY_DEAD, NOTE_2_READ)
                else -> Triple(NOTE_3, MANDY_DEAD, NOTE_3_READ)
            }
        startDialogue { mesbox(text) }
        if (state == from) setState(to)
    }

    private suspend fun ProtectedAccess.slashPainting() {
        if (state != NOTE_1_READ) {
            mes("Why would you want to slash a painting?")
            return
        }
        runQuestSection(null, dialogue, "Slashing the painting")
        setState(PAINTING_SLASHED)
    }

    private fun ProtectedAccess.searchPainting() {
        if (carries(MistObjs.ruby_key)) {
            mes("There's nothing else hidden in the painting.")
            return
        }
        if (takeIfRoom(MistObjs.ruby_key)) {
            mes("You find a key hidden inside the slashed painting.")
        }
    }

    /* Candles, the explosive barrel and Lacey */

    private fun ProtectedAccess.lightCandle(candle: BoundLocInfo) {
        val index = CANDLES.indexOf(candle.coords)
        if (index < 0 || state != PAINTING_SLASHED) return
        VarPlayerIntMapSetter.set(player, MistVarBits.candles[index], 1)
        if (MistVarBits.candles.all { player.vars[it] == 1 }) {
            VarPlayerIntMapSetter.set(player, MistVarBits.candles_lit, 1)
            setState(CANDLES_LIT)
            mes("You light the candle. The room is sufficiently warmed, the fuse should now have")
            mes("dried out.")
        } else {
            mes("You light the candle.")
        }
    }

    private suspend fun ProtectedAccess.lightFuse() {
        when {
            state < CANDLES_LIT -> mes("The fuse is too damp to light.")
            state > CANDLES_LIT -> mes("The fuse has already been lit.")
            else -> {
                mes("You light the fuse. You'd better get out of this room!")
                delay(FUSE_TICKS)
                mes("BOOM! The explosion blows a hole in the wall.")
                setState(WALL_BLOWN)
            }
        }
    }

    private suspend fun ProtectedAccess.climbWall(wall: BoundLocInfo) {
        passThroughWall(wall)
        if (state == WALL_BLOWN && player.coords.x > wall.coords.x) {
            if (murderScene(MistNpcs.lacey, LACEY_SPOT, "Run in with Lacey")) setState(LACEY_DEAD)
        }
    }

    /* The piano */

    private suspend fun ProtectedAccess.playPiano() {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Before getting the note:" to { state < LACEY_DEAD },
                        "Before reading the note:" to { state == LACEY_DEAD },
                    ),
            )
        if (state < NOTE_2_READ) {
            runQuestSection(null, dialogue, "Playing the piano", hooks)
            return
        }
        val played = ArrayList<String>()
        repeat(PIANO_TUNE.size) { played += pickNote() ?: return }
        mes("You play ${played.joinToString("-")}.")
        if (played == PIANO_TUNE && state == NOTE_2_READ) {
            mes("You hear a click as a compartment in the piano springs open.")
            setState(PIANO_OPEN)
        } else {
            mes("Nothing happens.")
        }
    }

    private suspend fun ProtectedAccess.pickNote(): String? {
        var note = ""
        startDialogue {
            note = choice5("A", "A", "B", "B", "C", "C", "D", "D", "More...", "", title = "Play")
            if (note.isEmpty()) {
                note = choice4("E", "E", "F", "F", "G", "G", "Stop playing", "", title = "Play")
            }
        }
        return note.ifEmpty { null }
    }

    private fun ProtectedAccess.searchPiano() {
        if (carries(MistObjs.emerald_key)) {
            mes("The compartment is empty.")
            return
        }
        if (takeIfRoom(MistObjs.emerald_key)) {
            mes("You find a key hidden inside the piano's compartment.")
        }
    }

    /* The fireplace and the gem switches */

    private suspend fun ProtectedAccess.pryFireplace() {
        if (state != NOTE_3_READ) return
        runQuestSection(null, dialogue, "Using a knife on the fireplace")
        setState(FIREPLACE_OPEN)
    }

    private suspend fun ProtectedAccess.searchFireplace() {
        if (state >= GEMS_SOLVED) {
            if (carries(MistObjs.sapphire_key)) {
                mes("The compartment is empty.")
            } else if (takeIfRoom(MistObjs.sapphire_key)) {
                mes("You find a key hidden inside the fireplace.")
            }
            return
        }
        runQuestSection(null, dialogue, "Searching the fireplace")
        val code = ArrayList<String>()
        repeat(GEM_CODE.size) { code += pickGem() ?: return }
        val correct = code == GEM_CODE
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the code was incorrect:" to { !correct },
                        "If the code was correct:" to { correct },
                    ),
            )
        runQuestSection(null, dialogue, "Entering a code", hooks)
        if (correct) {
            setState(GEMS_SOLVED)
            mes("You hear a click from inside the fireplace.")
        } else {
            mes("Nothing happens. Perhaps the switches need flipping in a different order.")
        }
    }

    private suspend fun ProtectedAccess.pickGem(): String? {
        var gem = ""
        startDialogue {
            gem =
                choice5(
                    "Sapphire", "Sapphire",
                    "Diamond", "Diamond",
                    "Zenyte", "Zenyte",
                    "Emerald", "Emerald",
                    "More...", "",
                    title = "Flip a switch",
                )
            if (gem.isEmpty()) {
                gem = choice3("Onyx", "Onyx", "Ruby", "Ruby", "Stop", "", title = "Flip a switch")
            }
        }
        return gem.ifEmpty { null }
    }

    /* Showdown */

    private suspend fun ProtectedAccess.pushMirror() {
        if (state != SHOWDOWN) return
        val hits = (mirrorHits[player.uid.packed] ?: 0) + 1
        mirrorHits[player.uid.packed] = hits
        mes("You push the mirror. A thrown knife rebounds off it, back at the Killer!")
        if (hits < MIRROR_HITS) return
        mirrorHits.remove(player.uid.packed)
        runQuestSection(null, dialogue, "Defeating the killer", speakers = speakers)
        invAddOrDrop(objRepo, MistObjs.killers_knife)
        setState(ABIGALE_ATTACKABLE)
    }

    private suspend fun ProtectedAccess.fightAbigale() {
        val weapon = player.righthand
        val section =
            when {
                weapon == null -> "Attempt to fight Abigale without wielding a weapon"
                weapon.id != MistObjs.killers_knife.id ->
                    "Attempt to fight Abigale while wielding other weapons"
                else -> "Fight Abigale while wielding the killer's knife"
            }
        runQuestSection(null, dialogue, section, speakers = speakers)
        if (weapon?.id == MistObjs.killers_knife.id && state == ABIGALE_ATTACKABLE) {
            setState(ABIGALE_STABBED)
        }
    }

    private suspend fun ProtectedAccess.talkMandy(npc: Npc) {
        if (state != MANDY_OUTSIDE) {
            runQuestSection(npc, dialogue, "Talking to Mandy again")
            return
        }
        val room = inv.freeSpace() >= REWARD_SLOTS
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has insufficient inventory space:" to { !room },
                        "If the player has at least three inventory spaces:" to { room },
                    ),
                rewrites = { it.replace(SPACE_PLACEHOLDER, "more space") },
            )
        runQuestSection(npc, dialogue, "Finishing up", hooks)
        if (!room) return
        invAdd(inv, MistObjs.uncut_ruby)
        invAdd(inv, MistObjs.uncut_emerald)
        invAdd(inv, MistObjs.uncut_sapphire)
        statAdvance(stats.crafting, CRAFTING_XP * xpMods.get(player, stats.crafting))
        completeQuest(MISTHALIN_MYSTERY)
    }

    companion object {
        const val STARTED = 10
        const val ON_ISLAND = 15
        const val SID_DEAD = 20
        const val BARREL_EMPTY = 25
        const val INSIDE = 30
        const val TAYTEN_DEAD = 35
        const val NOTE_1_READ = 40
        const val PAINTING_SLASHED = 45
        const val CANDLES_LIT = 50
        const val WALL_BLOWN = 65
        const val LACEY_DEAD = 70
        const val NOTE_2_READ = 75
        const val PIANO_OPEN = 80
        const val MANDY_DEAD = 90
        const val NOTE_3_READ = 95
        const val FIREPLACE_OPEN = 100
        const val GEMS_SOLVED = 105
        const val SHOWDOWN = 111
        const val ABIGALE_ATTACKABLE = 120
        const val ABIGALE_STABBED = 125
        const val MANDY_OUTSIDE = 130
        const val COMPLETE = 135

        private const val BOAT_TICKS = 3
        private const val FUSE_TICKS = 8
        private const val MIRROR_HITS = 3
        private const val REWARD_SLOTS = 3
        private const val CRAFTING_XP = 600.0
        private const val SPACE_PLACEHOLDER = "[3 more spaces/2 more spaces/another space]"

        private val ISLAND_LANDING = CoordGrid(1638, 4802, 0)

        /* Where each scene plays (approximate, by the rooms the Wiki names). */
        private val SID_SPOT = CoordGrid(1620, 4822, 0)
        private val TAYTEN_SPOT = CoordGrid(1636, 4840, 0)
        private val LACEY_SPOT = CoordGrid(1631, 4848, 0)
        private val MANDY_SPOT = CoordGrid(1628, 4844, 0)
        private val SHOWDOWN_SPOT = CoordGrid(1623, 4830, 0)
        private val LUMBRIDGE_LANDING = CoordGrid(3238, 3141, 0)

        /** mistmyst_candle1..4 by placed position. */
        private val CANDLES =
            listOf(
                CoordGrid(1641, 4831, 0),
                CoordGrid(1646, 4832, 0),
                CoordGrid(1647, 4827, 0),
                CoordGrid(1641, 4826, 0),
            )
        private val PIANO_TUNE = listOf("D", "E", "A", "D")
        private val GEM_CODE = listOf("Sapphire", "Diamond", "Zenyte", "Emerald", "Onyx", "Ruby")

        /* Note transcripts (OSRS Wiki). */
        private const val NOTE_1 =
            "Isn't murder just a work of art?<br>Beautiful, yet haunting, like the blade of a " +
                "knife.<br>As we wander through the valley of death."
        private const val NOTE_2 =
            "It's like music to my ears!<br>The glorious sounds, spelling out your fate!"
        private const val NOTE_3 =
            "Hear at first these words. Each murder you witness helplessly from start to end. " +
                "As you fail to solve the final letters of this quiz. Razor sharp like a " +
                "gemstone is the blade of my knife. The last sound you hear will be your " +
                "scream's echo. Heed that I will have the final word in this thriller."

        val MISTHALIN_MYSTERY =
            QuestDef(
                name = "Misthalin Mystery",
                varbit = { MistVarBits.progress },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf("Uncut ruby", "Uncut emerald", "Uncut sapphire", "600 Crafting XP"),
                scrollObj = { MistObjs.killers_knife },
            )
    }
}

// Every cache ref Misthalin Mystery uses (re-check after the rev 243 cutover).

internal object MistNpcs : NpcReferences() {
    /* Children of the mistmyst_* multinpcs on mistmyst_progress. */
    val abigale_lum = find("mistmyst_abigale_lum_vis")
    val hewey_lum = find("mistmyst_hewey_lum_vis")
    val sid = find("mistmyst_sid_vis")
    val tayten = find("mistmyst_tayten_vis")
    val lacey = find("mistmyst_lacey_vis")
    val mandy = find("mistmyst_mandy_vis")
    val mandy_post = find("mistmyst_mandy_post_vis")
    val killer = find("mistmyst_abigale_killer")
    val abigale_unmasked = find("mistmyst_abigale_killer_unmasked")
    val abigale_attackable = find("mistmyst_abigale_killer_attackable")
    val hewey_unmasked = find("mistmyst_hewey_killer_unmasked")
    val mirror_movable = find("mistmyst_mirror_movable")
}

internal object MistLocs : LocReferences() {
    val boat_lumbridge = find("mistmyst_boat_lumbridge")
    val boat_island = find("mistmyst_boat_island")
    val bucket = find("mistmyst_empty_bucket")
    val barrel_water = find("mistmyst_barrel_water")
    val barrel_emptied = find("mistmyst_barrel_emptied")
    val front_doors = listOf(find("mistmyst_front_doorl"), find("mistmyst_front_doorr"))
    val table_knife = find("mistmyst_table_knife")
    val shelves = find("mistmyst_shelves_tinderbox")
    val door_redtopaz = find("mistmyst_door_redtopaz")
    val door_diamond = find("mistmyst_door_diamond")
    val locked_doors =
        listOf(
            find("mistmyst_door_dragonstone"),
            find("mistmyst_door_jade"),
            find("mistmyst_door_opal"),
        )
    val keyed_doors: List<Pair<LocType, ObjType>> =
        listOf(
            find("mistmyst_door_ruby") to MistObjs.ruby_key,
            find("mistmyst_door_emerald") to MistObjs.emerald_key,
            find("mistmyst_door_sapphire") to MistObjs.sapphire_key,
        )
    val notes: List<Pair<LocType, ObjType>> =
        listOf(
            find("mistmyst_clue_library_vis") to MistObjs.clue_library,
            find("mistmyst_clue_outside_vis") to MistObjs.clue_outside,
            find("mistmyst_clue_kitchen_vis") to MistObjs.clue_kitchen,
        )
    val painting = find("mistmyst_painting_fixed")
    val painting_slashed = find("mistmyst_painting_slashed")
    val wrong_paintings =
        listOf(
            find("mistmyst_painting1"),
            find("mistmyst_painting2"),
            find("mistmyst_painting3"),
            find("mistmyst_painting4"),
            find("mistmyst_painting5"),
        )
    val candle_unlit = find("mistmyst_candle_unlit")
    val explosive_barrel = find("mistmyst_explosive_barrel_vis")
    val wall_climbable = find("mistmyst_destructable_wall_climbable_broken")
    val piano_closed = find("mistmyst_piano_closed")
    val piano_open = find("mistmyst_piano_open")
    val fireplace_unlit = find("mistmyst_fireplace_unlit")
    val fireplace_revealed = find("mistmyst_fireplace_revealed")
}

internal object MistObjs : ObjReferences() {
    val bucket = find("bucket_empty")
    val bucket_water = find("bucket_water")
    val knife = find("knife")
    val tinderbox = find("tinderbox")
    val manor_key = find("mistmyst_frontdoor_key")
    val ruby_key = find("mistmyst_ruby_key")
    val emerald_key = find("mistmyst_emerald_key")
    val sapphire_key = find("mistmyst_sapphire_key")
    val clue_library = find("mistmyst_clue_library")
    val clue_outside = find("mistmyst_clue_outside")
    val clue_kitchen = find("mistmyst_clue_kitchen")
    val killers_knife = find("mistmyst_cutscene_knife")
    val uncut_ruby = find("uncut_ruby")
    val uncut_emerald = find("uncut_emerald")
    val uncut_sapphire = find("uncut_sapphire")

    val clues = listOf(clue_library, clue_outside, clue_kitchen)
}

internal object MistVarBits : VarBitReferences() {
    val progress = find("mistmyst_progress")
    val candles =
        listOf(
            find("mistmyst_candle1"),
            find("mistmyst_candle2"),
            find("mistmyst_candle3"),
            find("mistmyst_candle4"),
        )
    val candles_lit = find("mistmyst_candles")
}
