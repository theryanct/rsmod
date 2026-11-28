package org.rsmod.content.quests.writingsonthewall

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Writing's on the Wall (OSRS Wiki). The quest varbit is the cache's `wotw` (0-48); its
 * multinpcs show Betty, Sergeant Hudson and the corporals at each crime scene for the stage, and
 * its multilocs show the bodies and blood. Each clue is its cache varbit (`wotw_lands_end_*`,
 * `wotw_hosidius_*`, `wotw_piscarilius_*`, `wotw_shayzien_*`).
 * - Land's End: Betty starts it; Hudson allows it. Jack's body (a letter), the shelves (a
 *   newspaper cutting), the blood on the wall, Lan the Butcher's bed (a knife; show it to him),
 *   Hugor, Betty; then Hudson.
 * - Hosidius (north-west of the Tithe Farm): in through the window, unlock the door for Betty;
 *   Bernard's body in the basement, the drink shelves, the bookcase (certificate), the compost bin
 *   (a head with a letter), Joyce, the monk, Betty. Betty takes you to Port Piscarilius.
 * - Port Piscarilius: jailed; the loose plank; Jensen's house (body, drawers, wardrobe, bed); back
 *   in the cell, the blood on the wall.
 * - Shayzien: Hudson at the war tent; Edwards' house (shelves, body, blood), Kyle, Hudson. The
 *   plant on the island west of Watson's house hides the killer's lair; the blood there ends it.
 *   1 QP, 2,500 Agility and Thieving xp, an amulet of sight.
 *
 * Simplified (Authored, not Wiki): no cutscenes (their dialogue plays as conversation); the
 * Piscarilius guards don't patrol; revisiting the old crime scenes isn't needed; the clue texts
 * the transcript lacks (shelves, walls) are authored; spawns and arrival tiles are best guesses
 * (Ryan-test).
 */
class WritingsOnTheWall
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(WritingsOnTheWall::class.java, "writings_on_the_wall")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Betty" to npcTypes[WotwNpcs.betty],
            "Sergeant Hudson" to npcTypes[WotwNpcs.hudson],
            "Corporal Breiman" to npcTypes[WotwNpcs.breiman],
            "Corporal Campter" to npcTypes[WotwNpcs.campter],
            "Corporal Breiman/Campter" to npcTypes[WotwNpcs.breiman],
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, WotwNpcs.betty, WRITINGS_ON_THE_WALL, wants = { !complete }) { talkBetty(it) }
            register(npcTypes, WotwNpcs.hudson, WRITINGS_ON_THE_WALL, wants = { true }) { talkHudson(it) }
            register(npcTypes, WotwNpcs.breiman, WRITINGS_ON_THE_WALL, wants = { true }) {
                talkCorporal(it, breiman = true)
            }
            register(npcTypes, WotwNpcs.campter, WRITINGS_ON_THE_WALL, wants = { true }) {
                talkCorporal(it, breiman = false)
            }
            register(npcTypes, WotwNpcs.lan, WRITINGS_ON_THE_WALL, wants = { stage in DETECTIVE until LANDS_END_DONE }) {
                talkLan(it)
            }
            register(npcTypes, WotwNpcs.hugor, WRITINGS_ON_THE_WALL, wants = { stage in DETECTIVE until LANDS_END_DONE }) {
                interview(it, "Talking to Hugor", mapOf(SEE_OR_HEAR to WotwVarBits.hugor_alibi))
            }
            register(npcTypes, WotwNpcs.monk, WRITINGS_ON_THE_WALL, wants = { stage in INSIDE until PISCARILIUS }) {
                interview(it, "Talking to the monk", mapOf(ODD to WotwVarBits.hos_confrontation))
            }
            register(npcTypes, WotwNpcs.joyce, WRITINGS_ON_THE_WALL, wants = { stage in INSIDE until PISCARILIUS }) {
                interview(it, "Talking to Joyce", mapOf(ODD to WotwVarBits.hos_stranger))
            }
            register(npcTypes, WotwNpcs.kyle, WRITINGS_ON_THE_WALL, wants = { stage in SHAYZIEN until LAIR }) {
                interview(it, "Talking to Kyle", mapOf(SEE_OR_HEAR to WotwVarBits.shay_witness))
            }
        }
        onOpNpcU(WotwNpcs.lan, WotwObjs.knife) {
            runQuestBranch(it.npc, dialogue, LAN, SHOW_KNIFE)
            if (stage >= DETECTIVE) setFlag(WotwVarBits.le_butcher, 1)
        }

        // Land's End
        onLocOpLabel(locTypes, WotwLocs.jack_inspect, "Inspect") { inspectJack() }
        onLocOpLabel(locTypes, WotwLocs.jack_search, "Search") {
            runQuestSection(null, dialogue, "Searching Jack's body again")
        }
        onLocOpLabel(locTypes, WotwLocs.le_shelves, "Search") { searchLandsEndShelves() }
        onLocOpLabel(locTypes, WotwLocs.le_blood, "Inspect") { inspectLandsEndBlood() }
        onLocOpLabel(locTypes, WotwLocs.lan_bed, "Search") { searchLanBed() }
        onOpHeld1(WotwObjs.knife) { runQuestSection(null, dialogue, "Inspecting the knife") }
        onOpHeld1(WotwObjs.le_letter) { readLetter() }
        onOpHeld1(WotwObjs.news) { readNews() }

        // Hosidius
        onLocOpLabel(locTypes, WotwLocs.window, "Climb-through") { climbWindow(it) }
        for (door in WotwLocs.hos_doors) onLocOpLabel(locTypes, door, "Open") { openHosidiusDoor(it) }
        onLocOpLabel(locTypes, WotwLocs.basement_down, "Climb-down") { telejump(BASEMENT) }
        onLocOpLabel(locTypes, WotwLocs.basement_up, "Climb-up") { telejump(BASEMENT_TOP) }
        onLocOpLabel(locTypes, WotwLocs.bernard, "Inspect") { inspectBernard() }
        onLocOpLabel(locTypes, WotwLocs.hos_shelves, "Search") { searchHosidiusShelves() }
        onLocOpLabel(locTypes, WotwLocs.bookcase, "Search") { searchBookcase() }
        onLocOpLabel(locTypes, WotwLocs.compost, "Search") { searchCompost() }
        onOpHeld1(WotwObjs.head) { inspectHead() }
        onOpHeld1(WotwObjs.certificate) { readCertificate() }
        onOpHeld1(WotwObjs.hos_letter) { readLetter() }

        // Piscarilius
        onLocOpLabel(locTypes, WotwLocs.cell_wall_blocked, "Remove-plank") { removePlank() }
        onLocOpLabel(locTypes, WotwLocs.cell_wall_open, "Climb-over") { climbCellWall() }
        for (ladder in WotwLocs.pisc_ladders_up) onLocOpLabel(locTypes, ladder, "Climb") { climb(+1) }
        for (ladder in WotwLocs.pisc_ladders_down) onLocOpLabel(locTypes, ladder, "Climb") { climb(-1) }
        onLocOpLabel(locTypes, WotwLocs.jensen, "Inspect") { inspectJensen() }
        onLocOpLabel(locTypes, WotwLocs.drawers, "Inspect") { inspectDrawers() }
        onLocOpLabel(locTypes, WotwLocs.wardrobe, "Search") {
            runQuestSection(null, dialogue, "Inspecting the wardrobe")
            if (stage >= JAILED) setFlag(WotwVarBits.pisc_wardrobe, 1)
        }
        onLocOpLabel(locTypes, WotwLocs.pisc_bed, "Search") {
            runQuestSection(null, dialogue, "Inspecting the bed")
            if (stage >= JAILED) setFlag(WotwVarBits.pisc_bed, 1)
        }
        onLocOpLabel(locTypes, WotwLocs.cell_blood, "Inspect") { inspectCellBlood() }

        // Shayzien and the lair
        onLocOpLabel(locTypes, WotwLocs.shay_shelves, "Search") {
            runQuestSection(null, dialogue, "Searching the shelves")
            if (stage >= SHAYZIEN) setFlag(WotwVarBits.shay_shelves, 1)
        }
        onLocOpLabel(locTypes, WotwLocs.edwards, "Inspect") { inspectEdwards() }
        onLocOpLabel(locTypes, WotwLocs.shay_blood, "Inspect") { inspectShayzienBlood() }
        onLocOpLabel(locTypes, WotwLocs.plant, "Inspect") { inspectPlant() }
        onLocOpLabel(locTypes, WotwLocs.lair_ladder, "Climb-up") { telejump(ISLAND) }
        onLocOpLabel(locTypes, WotwLocs.lair_blood, "Inspect") { inspectLairBlood() }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(WRITINGS_ON_THE_WALL)

    private val ProtectedAccess.complete: Boolean
        get() = stage >= COMPLETE

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(WRITINGS_ON_THE_WALL, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.all(varbits: List<VarBitType>): Boolean = varbits.all { flag(it) == 1 }

    private fun ProtectedAccess.any(varbits: List<VarBitType>): Boolean = varbits.any { flag(it) == 1 }

    private fun ProtectedAccess.give(obj: ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: SimpleQuestHooks = SimpleQuestHooks()) {
        runQuestSection(npc, dialogue, title, hooks, speakers)
    }

    /** An interview: each option in [asks] marks its clue varbit. */
    private suspend fun ProtectedAccess.interview(npc: Npc, title: String, asks: Map<String, VarBitType>) {
        val hooks =
            SimpleQuestHooks(
                options = asks.mapValues<String, VarBitType, suspend () -> Unit> { (_, v) -> { setFlag(v, 1) } }
            )
        section(npc, title, hooks)
    }

    /* Betty */

    private suspend fun ProtectedAccess.talkBetty(npc: Npc) {
        when {
            stage == 0 -> {
                val reqs = stat(stats.agility) >= AGILITY_REQUIRED && stat(stats.thieving) >= THIEVING_REQUIRED
                var start = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have the requirements to start the quest:" to { !reqs },
                                "If the player has the requirements to start the quest:" to { reqs },
                            ),
                        options = mapOf("Yes." to { start = true }),
                    )
                section(npc, "Talking to Betty", hooks)
                if (start) advance(STARTED)
            }
            stage < DETECTIVE -> section(npc, "Talking to Betty again")
            stage == DETECTIVE && all(LANDS_END_CLUES) -> {
                section(npc, "Talking to Betty after finding all evidence")
                advance(EVIDENCE)
            }
            stage == DETECTIVE -> {
                val some = any(LANDS_END_CLUES)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If no evidence has been found." to { !some }, "If some evidence has been found." to { some }),
                        options =
                            mapOf(
                                "Did you see or hear anything before your father's death?" to
                                    { setFlag(WotwVarBits.le_betty_alibi, 1) },
                                "Do you know why someone would want your father dead?" to
                                    { setFlag(WotwVarBits.le_history, 1) },
                            ),
                    )
                section(npc, "Talking to Betty (2)", hooks)
                if (all(LANDS_END_CLUES)) {
                    section(npc, "Talking to Betty after finding all evidence")
                    advance(EVIDENCE)
                }
            }
            stage == EVIDENCE -> section(npc, "Talking to Betty after finding all evidence")
            stage == HOSIDIUS && player.coords.x < HOSIDIUS_X -> section(npc, "Talking to Betty again (2)")
            stage == HOSIDIUS -> {
                section(npc, "Talking to Betty (3)")
                advance(AT_HOUSE)
            }
            stage == AT_HOUSE -> section(npc, "Talking to Betty again (3)")
            stage < PISCARILIUS && all(HOSIDIUS_CLUES) -> {
                var go = false
                val hooks = SimpleQuestHooks(options = mapOf("Let's get going." to { go = true }))
                section(npc, if (flag(WotwVarBits.hos_notify) == 1) HOS_AGAIN else HOS_ALL_CLUES, hooks)
                setFlag(WotwVarBits.hos_notify, 1)
                if (go) {
                    advance(JAILED)
                    telejump(CELL)
                }
            }
            stage < PISCARILIUS -> {
                val some = any(HOSIDIUS_CLUES)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If no evidence has been found." to { !some }, "If some evidence has been found." to { some }),
                        options =
                            mapOf(
                                "Was your uncle a big drinker?" to { setFlag(WotwVarBits.hos_drinking, 1) },
                                "Do you know why someone would want your uncle dead?" to
                                    { setFlag(WotwVarBits.hos_uncle_job, 1) },
                            ),
                    )
                section(npc, "Talking to Betty in her uncle's basement", hooks)
            }
            stage == JAILED && flag(WotwVarBits.plank_removed) == 0 -> section(npc, "Talking to Betty (4)")
            stage < CELL_BLOOD -> section(npc, "Talking to Betty after escaping")
            else -> section(npc, "Talking to Betty after escaping")
        }
    }

    /* Sergeant Hudson and the corporals */

    private suspend fun ProtectedAccess.talkHudson(npc: Npc) {
        when {
            stage == 0 -> section(npc, "Sergeant Hudson")
            stage == STARTED -> {
                section(npc, "Sergeant Hudson (2)")
                advance(DETECTIVE)
            }
            stage == DETECTIVE -> section(npc, "Sergeant Hudson (3)")
            stage == EVIDENCE -> {
                section(npc, "Talking to Sergeant Hudson")
                advance(HOSIDIUS)
            }
            stage == SHAYZIEN -> {
                section(npc, "Approaching the war tent", SimpleQuestHooks(rewrites = { it.replace("[him/her/them]", "them") }))
                advance(SHAYZIEN_HOUSE)
            }
            stage == SHAYZIEN_HOUSE && all(SHAYZIEN_CLUES) -> {
                section(npc, "Talking to Sergeant Hudson after finding all the clues")
                advance(LAIR)
            }
            stage == SHAYZIEN_HOUSE -> {
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf("What can you tell me about the victim?" to { setFlag(WotwVarBits.shay_victim, 1) })
                    )
                section(npc, "Talking to Sergeant Hudson (2)", hooks)
                if (all(SHAYZIEN_CLUES)) {
                    section(npc, "Talking to Sergeant Hudson after finding all the clues")
                    advance(LAIR)
                }
            }
            stage in LAIR until COMPLETE -> section(npc, "Talking to Sergeant Hudson again")
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): after the quest.
                    chatNpc(neutral, "Thanks again for your help, adventurer.")
                }
        }
    }

    private suspend fun ProtectedAccess.talkCorporal(npc: Npc, breiman: Boolean) {
        val title =
            when {
                stage == 0 -> if (breiman) "Corporal Breiman" else "Corporal Campter"
                stage < DETECTIVE -> "Corporal Breiman or Corporal Campter"
                stage < SHAYZIEN -> if (breiman) "Corporal Breiman (2)" else "Corporal Campter (2)"
                else -> if (breiman) "Talking to Corporal Breiman" else "Talking to Corporal Campter"
            }
        section(npc, title)
    }

    /* Land's End */

    private suspend fun ProtectedAccess.inspectJack() {
        var letter = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Inspect the arms." to { setFlag(WotwVarBits.le_hands, 1) },
                        "Yes." to { letter = true },
                    )
            )
        section(null, "Inspecting Jack's body", hooks)
        if (letter) {
            give(WotwObjs.le_letter)
            setFlag(WotwVarBits.le_letter, 1)
        }
    }

    private fun ProtectedAccess.searchLandsEndShelves() {
        if (stage < DETECTIVE || flag(WotwVarBits.le_news) == 1 && carries(WotwObjs.news)) {
            mes("You search the shelves, but find nothing of note.")
            return
        }
        give(WotwObjs.news)
        setFlag(WotwVarBits.le_news, 1)
        // Authored line (not Wiki): the clue the transcript doesn't quote.
        mes("You search the shelves and find a newspaper cutting tucked between some papers.")
    }

    private fun ProtectedAccess.inspectLandsEndBlood() {
        setFlag(WotwVarBits.le_wall, 1)
        // Authored line (not Wiki).
        mes("Strange symbols have been daubed on the wall in blood. They look like some sort of code.")
    }

    private suspend fun ProtectedAccess.searchLanBed() {
        if (stage < DETECTIVE || flag(WotwVarBits.le_knife) == 1) {
            runQuestSection(null, dialogue, "Searching Lan the Butcher's bed again")
            if (stage >= DETECTIVE) give(WotwObjs.knife)
            return
        }
        runQuestSection(null, dialogue, "Searching Lan the Butcher's bed")
        give(WotwObjs.knife)
        setFlag(WotwVarBits.le_knife, 1)
    }

    private suspend fun ProtectedAccess.talkLan(npc: Npc) {
        val knife = carries(WotwObjs.knife)
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        SHOW_KNIFE to { if (knife) setFlag(WotwVarBits.le_butcher, 1) },
                        "Do you know why someone would want the victim dead?" to
                            { setFlag(WotwVarBits.le_history, 1) },
                    )
            )
        section(npc, LAN, hooks)
    }

    private suspend fun ProtectedAccess.readLetter() {
        // Authored (not Wiki): the letters are written in the killer's cipher.
        mesbox("The letter is written in a strange code. Some of the symbols match those daubed in blood at the crime scene.")
    }

    private suspend fun ProtectedAccess.readNews() {
        // Authored (not Wiki).
        mesbox("A cutting about the Keystone Lodge, with a few of its members' names circled.")
    }

    /* Hosidius */

    private suspend fun ProtectedAccess.climbWindow(window: BoundLocInfo) {
        if (stage < AT_HOUSE) {
            mes("You'd better not climb into someone's house.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Climbing through the window")
        setFlag(WotwVarBits.found_window, 1)
        passThroughWall(window)
    }

    private suspend fun ProtectedAccess.openHosidiusDoor(door: BoundLocInfo) {
        val inside = player.coords.x in HOUSE_X && player.coords.z in HOUSE_Z
        when {
            stage >= INSIDE -> passThroughWall(door)
            !inside -> runQuestSection(null, dialogue, "Attempting to open the door from the outside")
            stage == AT_HOUSE -> {
                section(null, "Unlocking the doors")
                advance(INSIDE)
            }
            else -> passThroughWall(door)
        }
    }

    private suspend fun ProtectedAccess.inspectBernard() {
        val hooks =
            SimpleQuestHooks(options = mapOf("Inspect the arms." to { setFlag(WotwVarBits.hos_drink, 1) }))
        section(null, "Inspecting Bernard's body", hooks)
    }

    private fun ProtectedAccess.searchHosidiusShelves() {
        if (stage >= INSIDE) setFlag(WotwVarBits.hos_shelves, 1)
        // Authored line (not Wiki).
        mes("The shelves are full of bottles of wine. Several have been opened and recorked.")
    }

    private suspend fun ProtectedAccess.searchBookcase() {
        if (stage < INSIDE || carries(WotwObjs.certificate)) {
            runQuestSection(null, dialogue, "Searching the bookcase again")
            return
        }
        runQuestSection(null, dialogue, "Searching the bookcase")
        give(WotwObjs.certificate)
    }

    private suspend fun ProtectedAccess.readCertificate() {
        runQuestSection(null, dialogue, "Reading the certificate")
        // Authored (not Wiki): the certificate's text isn't in the transcript.
        mesbox("A certificate of membership of the Keystone Lodge, made out to Bernard.")
        if (stage >= INSIDE) setFlag(WotwVarBits.hos_certificate, 1)
    }

    private suspend fun ProtectedAccess.searchCompost() {
        if (stage < INSIDE || flag(WotwVarBits.hos_head) == 1 || carries(WotwObjs.head)) {
            runQuestSection(null, dialogue, "Searching the compost bin again")
            return
        }
        runQuestSection(null, dialogue, "Searching the compost bin")
        give(WotwObjs.head)
    }

    private suspend fun ProtectedAccess.inspectHead() {
        if (flag(WotwVarBits.hos_head) == 1) {
            mes("The head has nothing more to tell you.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Inspecting the head")
        give(WotwObjs.hos_letter)
        setFlag(WotwVarBits.hos_head, 1)
    }

    /* Port Piscarilius */

    private suspend fun ProtectedAccess.removePlank() {
        var climb = false
        val hooks = SimpleQuestHooks(options = mapOf("Climb through the gap." to { climb = true }))
        runQuestSection(null, dialogue, "Removing the plank from the wall", hooks)
        setFlag(WotwVarBits.plank_removed, 1)
        if (climb) climbCellWall()
    }

    private suspend fun ProtectedAccess.climbCellWall() {
        runQuestSection(null, dialogue, "Climbing over the wall")
        telejump(if (player.coords.z <= CELL_WALL_Z) OUTSIDE_CELL else CELL)
    }

    private fun ProtectedAccess.climb(levels: Int) {
        telejump(player.coords.translateLevel(levels))
    }

    private suspend fun ProtectedAccess.inspectJensen() {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Inspect the head." to { setFlag(WotwVarBits.pisc_head, 1) },
                        "Inspect the torso." to { setFlag(WotwVarBits.pisc_torso, 1) },
                        "Inspect the legs." to { setFlag(WotwVarBits.pisc_feet, 1) },
                    )
            )
        section(null, "Inspecting Jensen's body", hooks)
    }

    private suspend fun ProtectedAccess.inspectDrawers() {
        var blood = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Inspect the blood." to { blood = true },
                        "Search the drawers." to { setFlag(WotwVarBits.pisc_dagger, 1) },
                    )
            )
        section(null, "Inspecting the drawers", hooks)
        if (blood) {
            setFlag(WotwVarBits.pisc_blood, 1)
            // Authored (not Wiki): the transcript's action note, as a message.
            mes("The blood has been smeared into a key: the code's letters 'S', 'I' and 'N'.")
        }
    }

    private fun ProtectedAccess.inspectCellBlood() {
        if (stage < JAILED || !all(PISCARILIUS_CLUES)) {
            mes("There's blood smeared on the wall. You should look into what happened at Jensen's house first.")
            return
        }
        // Authored line (not Wiki): with the key from the drawers, the note can be read.
        mes("With the key from Jensen's drawers you translate the bloody note: the killer's next target is a Shayzien leader.")
        mes("You should get to the war tent in the Shayzien Encampment.")
        advance(SHAYZIEN)
    }

    /* Shayzien and the lair */

    private suspend fun ProtectedAccess.inspectEdwards() {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Inspect the head." to { setFlag(WotwVarBits.shay_eyes, 1) },
                        "Inspect the arms." to { setFlag(WotwVarBits.shay_hands, 1) },
                    )
            )
        section(null, "Inspecting Edwards' body", hooks)
    }

    private fun ProtectedAccess.inspectShayzienBlood() {
        if (stage >= SHAYZIEN) setFlag(WotwVarBits.shay_wall, 1)
        // Authored line (not Wiki).
        mes("More of the killer's symbols, written in blood. This time you can read them: 'Where it all began.'")
    }

    private suspend fun ProtectedAccess.inspectPlant() {
        if (stage < LAIR) {
            mes("It's just a plant.") // Authored (not Wiki).
            return
        }
        var down = false
        val hooks = SimpleQuestHooks(options = mapOf("Go down it." to { down = true }))
        runQuestSection(null, dialogue, "Opening the trapdoor", hooks)
        if (down) {
            advance(IN_LAIR)
            telejump(LAIR_ARRIVE)
        }
    }

    private suspend fun ProtectedAccess.inspectLairBlood() {
        if (stage < IN_LAIR || complete) return
        section(null, "Inspecting the blood in the lair")
        statAdvance(stats.agility, REWARD_XP * xpMods.get(player, stats.agility))
        statAdvance(stats.thieving, REWARD_XP * xpMods.get(player, stats.thieving))
        invAddOrDrop(objRepo, WotwObjs.amulet)
        completeQuest(WRITINGS_ON_THE_WALL)
    }

    companion object {
        const val STARTED = 2
        const val DETECTIVE = 4
        const val EVIDENCE = 6
        const val LANDS_END_DONE = 8
        const val HOSIDIUS = 12
        const val AT_HOUSE = 14
        const val INSIDE = 16
        const val PISCARILIUS = 22
        const val JAILED = 24
        const val CELL_BLOOD = 32
        const val SHAYZIEN = 36
        const val SHAYZIEN_HOUSE = 37
        const val LAIR = 38
        const val IN_LAIR = 40
        const val COMPLETE = 48

        private const val AGILITY_REQUIRED = 22
        private const val THIEVING_REQUIRED = 20
        private const val REWARD_XP = 2500.0

        private const val LAN = "Talking to Lan the Butcher"
        private const val SHOW_KNIFE = "I found this knife under your bed..."
        private const val SEE_OR_HEAR = "Did you see or hear anything around the time of the murder?"
        private const val ODD = "Have you seen anything odd around here lately?"
        private const val HOS_ALL_CLUES = "Talking to Betty after finding all the clues"
        private const val HOS_AGAIN = "Talking to Betty again at her uncle's house"

        private val LANDS_END_CLUES
            get() =
                listOf(
                    WotwVarBits.le_letter,
                    WotwVarBits.le_hands,
                    WotwVarBits.le_wall,
                    WotwVarBits.le_news,
                    WotwVarBits.le_knife,
                    WotwVarBits.le_butcher,
                    WotwVarBits.le_betty_alibi,
                    WotwVarBits.hugor_alibi,
                )

        private val HOSIDIUS_CLUES
            get() =
                listOf(
                    WotwVarBits.hos_head,
                    WotwVarBits.hos_certificate,
                    WotwVarBits.hos_drink,
                    WotwVarBits.hos_shelves,
                    WotwVarBits.hos_drinking,
                    WotwVarBits.hos_stranger,
                    WotwVarBits.hos_confrontation,
                )

        private val PISCARILIUS_CLUES
            get() =
                listOf(
                    WotwVarBits.pisc_head,
                    WotwVarBits.pisc_feet,
                    WotwVarBits.pisc_torso,
                    WotwVarBits.pisc_blood,
                    WotwVarBits.pisc_dagger,
                    WotwVarBits.pisc_wardrobe,
                    WotwVarBits.pisc_bed,
                )

        private val SHAYZIEN_CLUES
            get() =
                listOf(
                    WotwVarBits.shay_eyes,
                    WotwVarBits.shay_hands,
                    WotwVarBits.shay_wall,
                    WotwVarBits.shay_shelves,
                    WotwVarBits.shay_witness,
                    WotwVarBits.shay_victim,
                )

        /** Hosidius starts east of here (Land's End is around x 1500). */
        private const val HOSIDIUS_X = 1700
        /** Bernard's house, north-west of the Tithe Farm. */
        private val HOUSE_X = 1784..1789
        private val HOUSE_Z = 3521..3526
        /** The Piscarilius cell's north wall (1787,3782). */
        private const val CELL_WALL_Z = 3782

        /** Authored arrival tiles (Ryan-test). */
        private val BASEMENT = CoordGrid(1749, 9933, 0)
        private val BASEMENT_TOP = CoordGrid(1788, 3521, 0)
        private val CELL = CoordGrid(1787, 3781, 0)
        private val OUTSIDE_CELL = CoordGrid(1787, 3783, 0)
        private val LAIR_ARRIVE = CoordGrid(1624, 9933, 0)
        private val ISLAND = CoordGrid(1616, 3583, 0)

        val WRITINGS_ON_THE_WALL =
            QuestDef(
                name = "The Writing's on the Wall",
                varbit = { WotwVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("2,500 Agility XP", "2,500 Thieving XP", "An amulet of sight"),
                scrollObj = { WotwObjs.amulet },
                listRow = "quest_writingsonthewall",
            )
    }
}

// Every cache ref The Writing's on the Wall uses (re-check after cache updates).

internal object WotwNpcs : NpcReferences() {
    /** The multinpc children every Betty / Hudson / corporal resolves to. */
    val betty = find("wotw_betty_child")
    val hudson = find("wotw_hudson_child")
    val breiman = find("wotw_soldier_child_1")
    val campter = find("wotw_soldier_child_2")
    /** Child of kw_butcher_lan (wotw). */
    val lan = find("kw_butcher_lan_vis")
    val hugor = find("kw_hugor")
    val monk = find("wotw_monk")
    val joyce = find("wotw_farmer")
    val kyle = find("wotw_forester")
}

internal object WotwLocs : LocReferences() {
    /** Children of wotw_bed_lands_end (wotw). */
    val jack_inspect = find("wotw_bed_lands_end_body_inspect")
    val jack_search = find("wotw_bed_lands_end_body")
    val le_shelves = find("wotw_shelves_lands_end")
    /** Child of wotw_blood_lands_end (wotw 4-40). */
    val le_blood = find("wotw_blood_lands_end_inspect")
    val lan_bed = find("wotw_butcher_bed_lands_end")
    val window = find("wotw_hosidius_window")
    val hos_doors = listOf(find("wotw_hosidius_front_door"), find("wotw_hosidius_back_door"))
    val basement_down = find("hosidius_basement_ladder_entry")
    val basement_up = find("hosidius_basement_ladder_exit")
    /** Child of wotw_table_hosidius. */
    val bernard = find("wotw_table_hosidius_body")
    val hos_shelves = find("wotw_drink_shelves_hosidius")
    val bookcase = find("wotw_bookcase_hosidius")
    val compost = find("wotw_compost_bin_hosidius")
    /** Children of wotw_piscarilius_cell_wall (wotw_plank_removed). */
    val cell_wall_blocked = find("wotw_piscarilius_cell_wall_blocked")
    val cell_wall_open = find("wotw_piscarilius_cell_wall_unblocked")
    val pisc_ladders_up = listOf(find("wotw_piscarilius_ladder_bottom"), find("piscarilius_ladder_bottom"))
    val pisc_ladders_down = listOf(find("piscarilius_ladder_top_01"), find("piscarilius_ladder_top_02"))
    /** Child of wotw_armour_stand_piscarilius. */
    val jensen = find("wotw_armour_stand_piscarilius_body")
    /** Child of wotw_drawers_piscarilius. */
    val drawers = find("wotw_drawers_piscarilius_bloody")
    val wardrobe = find("wotw_wardrobe_piscarilius")
    val pisc_bed = find("wotw_bed_piscarilius")
    /** Child of wotw_blood_piscarilius (wotw 32-34), in the cell. */
    val cell_blood = find("wotw_blood_piscarilius_inspect")
    val shay_shelves = find("wotw_drink_shelves_shayzien")
    /** Child of wotw_bed_shayzien. */
    val edwards = find("wotw_bed_shayzien_body")
    /** Child of wotw_blood_shayzien (wotw 36-40). */
    val shay_blood = find("wotw_blood_shayzien_inspect")
    val plant = find("wotw_hideout_trapdoor_bush")
    val lair_ladder = find("wotw_hideout_ladder")
    /** Child of wotw_blood_hideout (wotw 40-44). */
    val lair_blood = find("wotw_blood_hideout_inspect")
}

internal object WotwObjs : ObjReferences() {
    val le_letter = find("wotw_letter_lands_end")
    val news = find("wotw_news_lands_end")
    val knife = find("wotw_knife_lands_end")
    val head = find("wotw_head_hosidius")
    val hos_letter = find("wotw_letter_hosidius")
    val certificate = find("wotw_certificate_hosidius")
    val amulet = find("amulet_of_sight")
}

internal object WotwVarBits : VarBitReferences() {
    val quest = find("wotw")
    val le_letter = find("wotw_lands_end_letter")
    val le_hands = find("wotw_lands_end_hands")
    val le_wall = find("wotw_lands_end_wall")
    val le_news = find("wotw_lands_end_news")
    val le_knife = find("wotw_lands_end_knife")
    val le_butcher = find("wotw_lands_end_butcher_questioned")
    val le_history = find("wotw_lands_end_history")
    val le_betty_alibi = find("wotw_lands_end_betty_alibi")
    val hugor_alibi = find("wotw_lands_end_hugor_alibi")
    val found_window = find("wotw_found_window")
    val hos_head = find("wotw_hosidius_head")
    val hos_certificate = find("wotw_hosidius_certificate")
    val hos_drink = find("wotw_hosidius_unfinished_drink")
    val hos_shelves = find("wotw_hosidius_drink_shelves")
    val hos_uncle_job = find("wotw_hosidius_uncle_job")
    val hos_drinking = find("wotw_hosidius_uncle_drinking_habits")
    val hos_stranger = find("wotw_hosidius_stranger_info")
    val hos_confrontation = find("wotw_hosidius_confrontation_info")
    val hos_notify = find("wotw_hosidius_notify")
    val pisc_head = find("wotw_piscarilius_head")
    val pisc_feet = find("wotw_piscarilius_feet")
    val pisc_blood = find("wotw_piscarilius_blood")
    val pisc_torso = find("wotw_piscarilius_torso")
    val pisc_dagger = find("wotw_piscarilius_dagger")
    val pisc_wardrobe = find("wotw_piscarilius_wardrobe")
    val pisc_bed = find("wotw_piscarilius_bed")
    val shay_wall = find("wotw_shayzien_wall")
    val shay_shelves = find("wotw_shayzien_drink_shelves")
    val shay_eyes = find("wotw_shayzien_eyes")
    val shay_victim = find("wotw_shayzien_victim_info")
    val shay_witness = find("wotw_shayzien_witness")
    val shay_hands = find("wotw_shayzien_hands")
    val plank_removed = find("wotw_plank_removed")
}
