package org.rsmod.content.quests.coldwar

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.QuestTalk
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
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Cold War (OSRS Wiki "Cold War", its quick guide and "Transcript:Cold War"). Progress is the cache
 * quest varbit `peng_quest` (end state 135, dbrow `quest_coldwar`); the other cache `peng_*`
 * varbits drive what the client shows: `peng_multi_hide` the bird hide (0 patch, 1 frame, 2 snowy, 3
 * destroyed), `peng_multi_larry` Larry in Lumbridge, `peng_multi_kgp` the KGP agent and Noodle by
 * the avalanche (1) and the booth agent (gone at 2), `peng_multi_crack` the crack back to the Ice
 * Lords (130+). The greeting code is `peng_emote_1..3` (1-8, rolled at the first observation),
 * greeting progress `peng_emote_check`, the suit `peng_transmog`; `peng_farmer` remembers telling
 * the sheep the farmer is a threat, `peng_chat_check` an Ice Lord killed, `peng_pong_chat` the first
 * agility lap (see [PenguinAgility]). Stages are ours (companion object), monotonic, and match the
 * clockmaker's bench gate (20, `ClockmakersBench`) and the client's crack (130).
 *
 * Side content built here: Larry's teleports and boat (Rellekka <-> iceberg), the bird hide and the
 * observation cutscene, the penguin suit (Larry's Tuxedo-time: transmog, the penguin emote tab,
 * the spell breaking away from Larry's areas), the emote greetings, Noodle's trade, the KGP base
 * doors, Ping and Pong, the war room capture and the Ice Lord pen, mission reports, the clockwork
 * book, bongos and playing the instruments. The clockmaker's bench is in construction, cowbell
 * stealing in the dairy cow script, the agility course in [PenguinAgility].
 *
 * Simplified: Between a Rock... and Enlightened Journey aren't built (their branches never show and
 * the crazy penguins stay hidden); the suit sits in the inventory (the cache gives it no wearpos)
 * and equipping a weapon/cape while suited is not blocked; the Ping and Pong concert, the army and
 * the giant penguin suit are dialogue/camera only (no instrument npc swaps or music unlocks);
 * teleporting out of the Ice Lord pen is not undone; one Ice Lord kill opens the pen (Wiki 1-3);
 * followers are not refused; the talking Zanaris cow isn't built.
 */
class ColdWar
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val cutscenes: CutsceneRunner,
    private val launcher: ProtectedAccessLauncher,
    private val events: EventBus,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ColdWar::class.java, "cold_war") }

    /** A greeting in progress: who is watching, the section's branches, what success does. */
    private class Greeting(
        val npc: Npc,
        val section: String,
        val onSuccess: suspend ProtectedAccess.() -> Unit,
    )

    private val greetings = HashMap<Int, Greeting>()

    private val pingPongSpeakers: Map<String, NpcType> by lazy {
        mapOf("Ping" to CwNpcs.ping, "Pong" to CwNpcs.pong, "KGP Agent" to CwNpcs.kgp)
    }
    private val paxSpeakers: Map<String, NpcType> by lazy { mapOf("Pescaling Pax" to CwNpcs.pax) }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (larry in CwNpcs.larries) {
                register(npcTypes, larry, COLD_WAR, wants = { true }) { talkLarry(it) }
            }
            register(npcTypes, CwNpcs.zoo_penguin, COLD_WAR, wants = { true }) { talkZooPenguin(it) }
            register(npcTypes, CwNpcs.sheep, COLD_WAR, wants = { true }) { talkSheep(it) }
            register(npcTypes, CwNpcs.fred, COLD_WAR, wants = { state == INVESTIGATE }) { talkFred(it) }
            for (kgp in CwNpcs.kgps) register(npcTypes, kgp, COLD_WAR, wants = { true }) { talkKgp(it) }
            register(npcTypes, CwNpcs.debrief, COLD_WAR, wants = { true }) { talkDebrief(it) }
            for (noodle in CwNpcs.noodles) {
                register(npcTypes, noodle, COLD_WAR, wants = { true }) { talkNoodle(it) }
            }
            register(npcTypes, CwNpcs.ping, COLD_WAR, wants = { true }) { talkBards(it) }
            register(npcTypes, CwNpcs.pong, COLD_WAR, wants = { true }) { talkBards(it) }
            register(npcTypes, CwNpcs.commander, COLD_WAR, wants = { true }) { talkCommander(it) }
        }
        for (larry in CwNpcs.larries) {
            onNpcOpLabel(npcTypes, larry, "Tuxedo-time") { tuxedo(it) }
            onNpcOpLabel(npcTypes, larry, "Travel") { larryTravel() }
        }
        onOpNpcU(CwNpcs.zoo_penguin, CwObjs.raw_cod) { talkZooPenguin(it.npc) }

        // The penguin emote tab (shown while in the suit).
        for ((index, button) in CwComponents.emotes.withIndex()) {
            onIfOverlayButton(button) { launcher.launch(player) { penguinEmote(index + 1) } }
        }
        onPlayerSoftTimer(CwTimers.suit) { checkSuitRange(player) }
        onPlayerLogin { loginReset(player) }

        // Travel.
        for (boat in CwLocs.boats) onLocOpLabel(locTypes, boat, "Travel") { boatTravel() }

        // The bird hide.
        for (hide in CwLocs.hides) {
            onLocOpLabel(locTypes, hide, "Use") { useHide() }
            onOpLocU(hide) { onHide(it.objType.id) }
        }
        QuestDigSpots.register { digHide() }

        // Ardougne Zoo.
        onLocOpLabel(locTypes, CwLocs.enclosure_door, "Open") { enclosureDoor(it) }

        // The outpost.
        for (avalanche in CwLocs.avalanches) onLocOpLabel(locTypes, avalanche, "Use") { avalanche() }
        onLocOpLabel(locTypes, CwLocs.base_exit, "Open") { leaveBase() }
        for (door in CwLocs.base_doors) onLocOpLabel(locTypes, door, "Open") { passThroughWall(it) }
        onLocOpLabel(locTypes, CwLocs.agility_exit, "Open") { toAgilityArea() }
        onLocOpLabel(locTypes, CwLocs.cliff_door, "Open") { fromAgilityArea() }
        onLocOpLabel(locTypes, CwLocs.control_panel, "Use") { controlPanel() }
        onLocOpLabel(locTypes, CwLocs.door_button, "Use") { controlPanel() }
        onLocOpLabel(locTypes, CwLocs.blast_door, "Open") {
            mes("The doors are firmly shut. Perhaps the controls in the booth open them.") // Authored (not Wiki).
        }

        // The Ice Lords.
        onLocOpLabel(locTypes, CwLocs.pen_door, "Open") { penDoor(it) }
        onLocOpLabel(locTypes, CwLocs.icelord_door, "Open") { passThroughWall(it) }
        onLocOpLabel(locTypes, CwLocs.chasm, "Use") { chasm() }
        for (crack in CwLocs.cracks) onLocOpLabel(locTypes, crack, "Use") { crack() }
        onEvent<NpcKilledEvent> { icelordKilled(this) }

        // Items.
        onHeldOpLabel(objTypes, CwObjs.book, "Read") { ifOpenMainModal(CwInterfaces.book) }
        onHeldOpLabel(objTypes, CwObjs.report_zoo, "Read") { mesbox(REPORT_ZOO) }
        onHeldOpLabel(objTypes, CwObjs.report_lumbridge, "Read") { mesbox(REPORT_LUMBRIDGE) }
        onHeldOpLabel(objTypes, CwObjs.report_fake, "Read") { mesbox(REPORT_FAKE) }
        onHeldOpLabel(objTypes, CwObjs.bongos, "Play") { playInstrument(bongos = true) }
        onHeldOpLabel(objTypes, CwObjs.cowbell, "Play") { playInstrument(bongos = false) }
        onOpHeldU(CwObjs.mahogany_plank, CwObjs.leather) { makeBongos() }
    }

    /* State */

    private val ProtectedAccess.state: Int
        get() = player.questState(COLD_WAR)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(COLD_WAR, value)
        syncVars(player)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /** The client-side visibility varbits follow the quest stage. */
    private fun syncVars(player: Player) {
        val state = player.questState(COLD_WAR)
        val larry = if (state in LUMBRIDGE until AT_OUTPOST) 1 else 0
        val kgp =
            when {
                state == DISTRACTED -> 2
                state in OUTPOST until DISTRACTED || state >= CAUGHT -> 1
                else -> 0
            }
        VarPlayerIntMapSetter.set(player, CwVarBits.multi_larry, larry)
        VarPlayerIntMapSetter.set(player, CwVarBits.multi_kgp, kgp)
        if (state >= SUIT_TRIP) VarPlayerIntMapSetter.set(player, CwVarBits.multi_hide, HIDE_DESTROYED)
    }

    private val ProtectedAccess.suited: Boolean
        get() = player.vars[CwVarBits.transmog] == 1

    private fun ProtectedAccess.owns(obj: ObjType): Boolean =
        carries(obj) || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

    private fun ProtectedAccess.give(obj: ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    private suspend fun ProtectedAccess.authored(npc: Npc, text: String) {
        startDialogue(npc) { chatNpc(neutral, text) }
    }

    private fun ProtectedAccess.inLumbridge(): Boolean = player.coords.x >= LUMBRIDGE_MIN_X

    private fun ProtectedAccess.onIceberg(): Boolean = player.coords.inBox(ICEBERG_AREA)

    /* Larry */

    private suspend fun ProtectedAccess.talkLarry(npc: Npc) {
        if (suited) {
            runQuestSection(npc, dialogue, "Speaking to Larry while in the suit")
            unsuit()
            return
        }
        val ice = onIceberg()
        when (state) {
            0 -> startQuest(npc)
            STARTED -> materials(npc)
            ICEBERG ->
                if (!ice) {
                    authored(npc, "Meet me on the iceberg! My boat is on the dock east of Rellekka.")
                } else {
                    when (bit(CwVarBits.multi_hide)) {
                        0 -> runQuestSection(npc, dialogue, "Before building the hideout")
                        1 -> runQuestSection(npc, dialogue, "With hideout partially built")
                        else -> observe(first = true)
                    }
                }
            OBSERVED ->
                if (ice) {
                    runQuestSection(npc, dialogue, "After observing the penguins")
                    setState(LEAVE)
                } else {
                    giveBook(npc)
                }
            LEAVE ->
                if (ice) runQuestSection(npc, dialogue, "Speaking to Larry again without leaving") else giveBook(npc)
            BOOK -> suitCheck(npc)
            SUIT_TRIP ->
                if (ice) {
                    var go = false
                    val hooks = SimpleQuestHooks(options = mapOf("Yes." to { go = true }))
                    runQuestSection(npc, dialogue, "Second iceberg visit", hooks)
                    setState(ZOO_TEST)
                    if (go) larryTeleport(ZOO_ARRIVAL)
                } else {
                    authored(npc, "We must go back to the iceberg and test the suit. My boat is east of Rellekka.")
                }
            ZOO_TEST ->
                when {
                    ice -> runQuestSection(npc, dialogue, "Speaking to Larry again without leaving (2)")
                    inLumbridge() -> authored(npc, "Test the suit on the penguins at the Zoo first!")
                    else -> {
                        runQuestSection(npc, dialogue, "Speaking to Larry at Ardougne Zoo")
                        suitUp(npc, quiet = true)
                    }
                }
            ARDY_REPORT -> {
                runQuestSection(npc, dialogue, "After speaking with Ardougne Zoo's penguins")
                setState(LUMBRIDGE)
            }
            LUMBRIDGE ->
                if (inLumbridge()) {
                    runQuestSection(npc, dialogue, "Searching for the penguins at Lumbridge")
                } else {
                    authored(npc, "Go on ahead to Lumbridge, to Fred the Farmer's sheep field. I'll meet you there.")
                }
            NEED_PHRASE ->
                if (inLumbridge()) {
                    runQuestSection(npc, dialogue, "Returning to Larry about a secret phrase")
                    offerTeleport(npc, "Teleport to the Zoo?", ZOO_ARRIVAL)
                } else {
                    runQuestSection(npc, dialogue, "Not getting the phrase from the Zoo penguin")
                }
            PHRASE -> runQuestSection(npc, dialogue, "After obtaining the phrase")
            INVESTIGATE, FRED_DONE ->
                authored(npc, "Go on, talk to the sheep. And deal with that farmer if they ask you to!")
            OUTPOST -> {
                val threat = bit(CwVarBits.farmer) == 1
                var go = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player told the penguins that Fred is a threat:" to { threat },
                                "If the player told the penguins that Fred is harmless:" to { !threat },
                            ),
                        options = mapOf("Yes" to { go = true }),
                    )
                runQuestSection(npc, dialogue, "After finding the outpost's location", hooks)
                if (go) {
                    setState(AT_OUTPOST)
                    larryTeleport(ICE_ARRIVAL)
                }
            }
            AT_OUTPOST ->
                if (ice) {
                    runQuestSection(npc, dialogue, "Speaking to Larry")
                } else {
                    offerTeleport(npc, "Are you ready to go to the iceberg?", ICE_ARRIVAL)
                }
            NEED_ID, NOODLE -> runQuestSection(npc, dialogue, "Asking Larry about an ID")
            HAVE_ID, ENTRY, DEBRIEFED ->
                authored(npc, "Get in there and find out what those penguins are up to!")
            COURSE_DONE -> {
                runQuestSection(npc, dialogue, "Reporting to Larry after finding about penguin training")
                setState(FIND_ROOM)
            }
            FIND_ROOM -> runQuestSection(npc, dialogue, "Speaking to Larry again before looking for the war room")
            INSTRUMENTS -> runQuestSection(npc, dialogue, "Asking Larry about the cowbell and bongos")
            DISTRACTED -> authored(npc, "The guard's away? Then get through those doors, quickly!")
            CAUGHT -> finish(npc)
            else -> authored(npc, "I've sent word to my...friends. Keep an eye on those penguins.")
        }
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        val missing = REQUIREMENTS.filter { (stat, level) -> statBase(stat.first()) < level }
        if (missing.isNotEmpty()) {
            authored(npc, "You don't look ready to help me. Come back when you're more experienced.") // Authored.
            val list = missing.joinToString { (stat, level) -> "$level ${stat.second}" }
            mes("You need $list to start Cold War.") // Authored (not Wiki).
            return
        }
        var start = false
        val hooks = SimpleQuestHooks(options = mapOf("Okay, why not!" to { start = true }))
        runQuestSection(npc, dialogue, "Speaking to Larry at Ardougne Zoo", hooks)
        if (start && state == 0) {
            setState(STARTED)
            mes("You've started a new quest: <col=00ffff>Cold War</col>")
        }
    }

    private fun ProtectedAccess.hasMaterials(): Boolean =
        carries(CwObjs.oak_plank, PLANKS) && carries(CwObjs.nails, PLANKS) && carries(CwObjs.hammer) &&
            carries(CwObjs.spade)

    /**
     * "Getting materials to build the hideout". The transcript's missing-material lines are plain
     * text (the converter drops them), so this branch is written out here with the Wiki's words.
     */
    private suspend fun ProtectedAccess.materials(npc: Npc) {
        var go = false
        startDialogue(npc) {
            chatNpc(neutral, "Have you got the equipment?")
            val pick =
                choice4(
                    "Yes, I have all the materials.", 0,
                    "What do I need to get?", 1,
                    "Where should I meet you when I have everything?", 2,
                    "Never mind.", 3,
                )
            when (pick) {
                0, 1 -> {
                    chatPlayer(neutral, if (pick == 0) "Yes, I have them with me." else "What do I need to get?")
                    when {
                        !carries(CwObjs.oak_plank, PLANKS) -> mesbox("You do not have enough oak planks.")
                        !carries(CwObjs.nails, PLANKS) -> mesbox("You do not have enough nails.")
                        !carries(CwObjs.hammer) -> mesbox("You need a hammer.")
                        !carries(CwObjs.spade) -> mesbox("You need a spade.") // Authored (not Wiki).
                        else -> {
                            chatNpc(neutral, "You have all the materials. Off to the iceberg!")
                            go = choice2("Yes", true, "No", false, title = "Are you ready to go to the iceberg?")
                            if (!go) chatPlayer(neutral, "No.")
                        }
                    }
                }
                2 -> {
                    chatNpc(
                        neutral,
                        "Once you have the items, meet me up by the entrance to Keldagrim, east of Rellekka. " +
                            "I'll be there with a boat to take us to our destination.",
                    )
                    val lost = choice2("I have no idea where that is.", true, "Oh, I know where to go.", false)
                    if (lost) {
                        chatPlayer(neutral, "I have no idea where that is.")
                        chatNpc(
                            neutral,
                            "And you call yourself an adventurer! There is a road from Catherby going north. " +
                                "Take it until you reach the Sinclair Mansion.",
                        )
                        chatNpc(
                            neutral,
                            "Take a left at the Mansion, cross two bridges and follow the road until you reach " +
                                "Rellekka. Head east, around Rellekka to the coast near the entrance of Keldagrim.",
                        )
                        chatNpc(neutral, "Did I spell that out enough for you?")
                        chatPlayer(neutral, "No need to be snippy.")
                    } else {
                        chatPlayer(neutral, "Oh, I know where to go.")
                    }
                }
                else -> chatPlayer(neutral, "Never mind.")
            }
        }
        if (go && hasMaterials() && state == STARTED) {
            setState(ICEBERG)
            larryTeleport(ICE_ARRIVAL)
        }
    }

    private suspend fun ProtectedAccess.giveBook(npc: Npc) {
        runQuestSection(npc, dialogue, "Speaking to Larry at the Zoo or Relekka")
        if (!owns(CwObjs.book)) give(CwObjs.book)
        setState(BOOK)
    }

    private suspend fun ProtectedAccess.suitCheck(npc: Npc) {
        if (onIceberg()) {
            authored(npc, "We can't talk here! Not with all these penguins listening.")
            return
        }
        var go = false
        var newBook = false
        val hasBook = owns(CwObjs.book)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have the suit:" to { !carries(CwObjs.suit) },
                        "If the player still possesses the book:" to { hasBook },
                    ),
                options =
                    mapOf(
                        "Yes" to { go = true },
                        "I've lost the clockwork book, could I have another?" to { newBook = !hasBook },
                    ),
            )
        runQuestSection(npc, dialogue, "Making the penguin suit", hooks)
        if (newBook) give(CwObjs.book)
        if (go && carries(CwObjs.suit) && state == BOOK) {
            setState(SUIT_TRIP)
            larryTeleport(ICE_ARRIVAL)
        }
    }

    private suspend fun ProtectedAccess.offerTeleport(npc: Npc, title: String, dest: CoordGrid) {
        var go = false
        startDialogue(npc) { go = choice2("Yes", true, "No", false, title = title) }
        if (go) larryTeleport(dest)
    }

    private suspend fun ProtectedAccess.larryTeleport(dest: CoordGrid) {
        if (suited) unsuit()
        delay(1)
        telejump(dest)
    }

    private suspend fun ProtectedAccess.larryTravel() {
        boatTravel()
    }

    private suspend fun ProtectedAccess.boatTravel() {
        if (state < ICEBERG) {
            mes("Larry will take you to the iceberg once you have the materials for his bird hide.") // Authored.
            return
        }
        if (suited) unsuit()
        val toIceberg = !onIceberg()
        mes(if (toIceberg) "You board Larry's boat and set off for the iceberg." else "You sail back to Rellekka.") // Authored.
        larryTeleport(if (toIceberg) ICE_ARRIVAL else RELLEKKA_ARRIVAL)
    }

    /** "After escaping the Ice Lords": the reward. */
    private suspend fun ProtectedAccess.finish(npc: Npc) {
        if (bit(CwVarBits.icelord_killed) == 0 || player.coords.inBox(BASE_AREA)) {
            authored(npc, "Where have you been? Find your way out of there and tell me what happened!")
            return
        }
        runQuestSection(npc, dialogue, "After escaping the Ice Lords")
        npc.anim(seqs.emote_dance)
        statAdvance(stats.crafting, CRAFTING_XP * xpMods.get(player, stats.crafting))
        statAdvance(stats.agility, AGILITY_XP * xpMods.get(player, stats.agility))
        statAdvance(stats.construction, CONSTRUCTION_XP * xpMods.get(player, stats.construction))
        completeQuest(COLD_WAR)
        syncVars(player)
    }

    /* The penguin suit */

    private suspend fun ProtectedAccess.tuxedo(npc: Npc) {
        if (suited) {
            runQuestSection(npc, dialogue, "Using Larry's Tuxedo-time while in the suit")
            unsuit()
            return
        }
        if (state < ZOO_TEST) {
            authored(npc, "Not yet! We need to test the suit on the penguins first.") // Authored (not Wiki).
            return
        }
        if (inLumbridge() && state !in LUMBRIDGE until AT_OUTPOST) {
            authored(npc, "There's nothing for us to do here now.") // Authored (not Wiki).
            return
        }
        suitUp(npc, quiet = false)
    }

    /** "Penguin time!": the transcript's hands/cape conditions, then the shrink. */
    private suspend fun ProtectedAccess.suitUp(npc: Npc, quiet: Boolean) {
        if (!carries(CwObjs.suit)) {
            authored(npc, "You need your clockwork suit with you. Make one at your house if you've lost it.")
            return
        }
        val handsBusy = player.worn[Wearpos.RightHand.slot] != null || player.worn[Wearpos.LeftHand.slot] != null
        if (handsBusy) {
            authored(npc, "You will need your hands free to operate the suit.")
            return
        }
        if (player.worn[Wearpos.Back.slot] != null) {
            authored(npc, "You can't wear a cape inside the suit, it might get caught.")
            return
        }
        if (!quiet) startDialogue(npc) { chatPlayer(neutral, "Penguin time!") }
        setBit(CwVarBits.transmog, 1)
        transmog(CwNpcs.suit_transmog)
        ifOpenOverlay(CwInterfaces.peng_emote, components.toplevel_target_side12)
        softTimer(CwTimers.suit, SUIT_CHECK_TICKS)
        mes("You shrink and Larry puts you in the penguin suit.")
    }

    private fun ProtectedAccess.unsuit() {
        unsuitPlayer(player)
        mes("The spell wears off as you climb out of the suit, growing back to normal size.")
    }

    private fun unsuitPlayer(player: Player) {
        VarPlayerIntMapSetter.set(player, CwVarBits.transmog, 0)
        VarPlayerIntMapSetter.set(player, CwVarBits.emote_check, 0)
        player.transmog = null
        player.clearSoftTimer(CwTimers.suit)
        player.ifOpenOverlay(interfaces.emote, components.toplevel_target_side12, events)
        greetings.remove(player.uid.packed)
    }

    /** Larry's spell only holds near him (the zoo, Lumbridge's field) or on the iceberg. */
    private fun checkSuitRange(player: Player) {
        if (player.vars[CwVarBits.transmog] == 0) {
            player.clearSoftTimer(CwTimers.suit)
            return
        }
        val c = player.coords
        if (SUIT_AREAS.any { c.inBox(it) }) return
        unsuitPlayer(player)
        player.mes("The spell wears off as you climb out of the suit, growing back to normal size.")
    }

    private fun loginReset(player: Player) {
        syncVars(player)
        if (player.vars[CwVarBits.transmog] == 1) {
            VarPlayerIntMapSetter.set(player, CwVarBits.transmog, 0)
            VarPlayerIntMapSetter.set(player, CwVarBits.emote_check, 0)
            player.transmog = null
        }
        // Without the suit the base is no place to be (the Ice Lord pen and its way out excepted).
        if (player.coords.inBox(BASE_AREA) && !player.coords.inBox(PEN_AREA)) player.coords = ICE_ARRIVAL
    }

    /* Greetings */

    private suspend fun ProtectedAccess.penguinEmote(index: Int) {
        if (!suited) return
        anim(CwSeqs.emotes[index - 1])
        val greeting = greetings[player.uid.packed] ?: return
        val npc = greeting.npc
        if (!npc.isSlotAssigned || player.coords.chebyshevDistance(npc.coords) > GREETING_RANGE) {
            greetings.remove(player.uid.packed)
            return
        }
        delay(2)
        val nodes = dialogue.section(greeting.section)
        val progress = bit(CwVarBits.emote_check)
        val code = listOf(CwVarBits.emote_1, CwVarBits.emote_2, CwVarBits.emote_3).map { bit(it) }
        if (code[progress] != index) {
            setBit(CwVarBits.emote_check, 0)
            greetings.remove(player.uid.packed)
            val fail = findCond(nodes, "If the player fails the greeting:")?.children.orEmpty()
            startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, fail) }
            mes("That is not the right greeting.")
            return
        }
        val now = progress + 1
        val branch =
            when (now) {
                1 -> findCond(nodes, "After one out of three correct moves:")?.children.orEmpty().filterIsInstance<Node.Line>()
                2 -> findCond(nodes, "After two out of three correct moves:")?.children.orEmpty().filterIsInstance<Node.Line>()
                else -> findCond(nodes, "After a full proper greeting:")?.children.orEmpty()
            }
        if (now < 3) {
            setBit(CwVarBits.emote_check, now)
            startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, branch) }
            return
        }
        setBit(CwVarBits.emote_check, 0)
        greetings.remove(player.uid.packed)
        startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, branch) }
        greeting.onSuccess(this)
    }

    /** Runs a greeting section up to the emote panel, then waits for the player's emotes. */
    private suspend fun ProtectedAccess.startGreeting(npc: Npc, section: String, onSuccess: suspend ProtectedAccess.() -> Unit) {
        val lead = dialogue.section(section).takeWhile { it !is Node.Cond }
        startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, lead) }
        setBit(CwVarBits.emote_check, 0)
        greetings[player.uid.packed] = Greeting(npc, section, onSuccess)
    }

    private fun findCond(nodes: List<Node>, text: String): Node.Cond? {
        for (node in nodes) {
            if (node is Node.Cond && node.text == text) return node
            findCond(node.children, text)?.let { return it }
        }
        return null
    }

    private fun ProtectedAccess.rollGreeting() {
        if (bit(CwVarBits.emote_1) != 0) return
        for (varbit in listOf(CwVarBits.emote_1, CwVarBits.emote_2, CwVarBits.emote_3)) {
            setBit(varbit, random.of(1, EMOTE_COUNT))
        }
    }

    /* The bird hide */

    private suspend fun ProtectedAccess.useHide() {
        when {
            state >= SUIT_TRIP || bit(CwVarBits.multi_hide) == HIDE_DESTROYED ->
                runQuestSection(null, dialogue, "Interacting with the destroyed hideout")
            state < ICEBERG -> mes("A firm patch of snow.") // Authored (not Wiki).
            bit(CwVarBits.multi_hide) == 0 ->
                mes("Larry wants a bird hide built here: use the oak planks on the snow.") // Authored (not Wiki).
            bit(CwVarBits.multi_hide) == 1 ->
                mes("The structure needs covering in snow. A spade would do it.") // Authored (not Wiki).
            else -> observe(first = state == ICEBERG)
        }
    }

    private suspend fun ProtectedAccess.onHide(objId: Int) {
        val hide = bit(CwVarBits.multi_hide)
        when {
            objId == CwObjs.oak_plank.id && state == ICEBERG && hide == 0 -> buildHide()
            objId == CwObjs.spade.id && state == ICEBERG && hide == 1 -> coverHide()
            else -> mes("Nothing interesting happens.")
        }
    }

    private suspend fun ProtectedAccess.buildHide() {
        if (!carries(CwObjs.oak_plank, PLANKS) || !carries(CwObjs.nails, PLANKS)) {
            mes("You need 10 oak planks and 10 steel nails to build the bird hide.") // Authored (not Wiki).
            return
        }
        if (!carries(CwObjs.hammer)) {
            mes("You need a hammer to build the bird hide.") // Authored (not Wiki).
            return
        }
        anim(CwSeqs.hammer)
        delay(3)
        invDel(inv, CwObjs.oak_plank, PLANKS)
        invDel(inv, CwObjs.nails, PLANKS)
        setBit(CwVarBits.multi_hide, 1)
        mes("You create the bird hide structure.")
    }

    private suspend fun ProtectedAccess.coverHide() {
        anim(CwSeqs.dig)
        delay(3)
        setBit(CwVarBits.multi_hide, 2)
        mes("You finish the bird hide.")
    }

    /** Dig with a spade next to the frame (the Wiki's alternative to using the spade on it). */
    private suspend fun ProtectedAccess.digHide(): Boolean {
        if (state != ICEBERG || bit(CwVarBits.multi_hide) != 1) return false
        if (player.coords.chebyshevDistance(HIDE) > HIDE_DIG_RANGE || player.coords.level != HIDE.level) return false
        coverHide()
        return true
    }

    /** The observation: two penguins meet and the left one performs the player's greeting. */
    private suspend fun ProtectedAccess.observe(first: Boolean) {
        rollGreeting()
        val code = listOf(CwVarBits.emote_1, CwVarBits.emote_2, CwVarBits.emote_3).map { bit(it) }
        val section = if (first) "With a fully built hideout" else "Interacting with the hideout again"
        val lines = dialogue.section(section).filterIsInstance<Node.Line>()
        val finished =
            cutscenes.play(this, template = null, returnTo = player.coords) {
                teleport(HIDE_VIEW)
                val left = spawn(CwNpcs.cutscene_penguin, PENGUIN_A)
                val right = spawn(CwNpcs.cutscene_penguin, PENGUIN_B)
                left.faceTile(PENGUIN_B)
                right.faceTile(PENGUIN_A)
                camMoveTo(CAMERA, height = 450)
                camLookAt(MEETING, height = 150)
                fadeIn()
                conversation {
                    startDialogue { QuestDialogueRunner(SimpleQuestHooks()).run(this, lines.take(2)) }
                }
                left.walkTo(MEETING.translate(-1, 0))
                right.walkTo(MEETING.translate(1, 0))
                delay(4)
                for (emote in code) {
                    left.play(CwSeqs.emotes[emote - 1])
                    delay(3)
                }
                right.play(CwSeqs.emotes[0])
                delay(3)
                conversation {
                    startDialogue { QuestDialogueRunner(SimpleQuestHooks()).run(this, lines.drop(2)) }
                }
                fadeOut()
                delay(2)
            }
        if (finished && state == ICEBERG) setState(OBSERVED)
    }

    /* The zoo */

    private suspend fun ProtectedAccess.enclosureDoor(door: BoundLocInfo) {
        if (!suited && player.coords.z <= door.coords.z) {
            mes("The zoo keepers wouldn't want you in there.") // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkZooPenguin(npc: Npc) {
        if (!suited) {
            runQuestSection(npc, dialogue, "Attempting to speak to \"sheep\" as a human")
            return
        }
        when (state) {
            ZOO_TEST ->
                startGreeting(npc, "Entering the cage as a \"penguin\"") {
                    if (state == ZOO_TEST) {
                        give(CwObjs.report_zoo)
                        setState(ARDY_REPORT)
                    }
                }
            NEED_PHRASE -> askPhrase(npc)
            else -> authored(npc, "Shh, comrade. The humans are watching.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.askPhrase(npc: Npc) {
        val ring = invTotal(worn, CwObjs.ring_of_charos) > 0
        val cod = !ring && carries(CwObjs.raw_cod)
        var traded = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If player is wearing a Ring of Charos:" to { ring },
                        "With a raw cod in inventory:" to { cod },
                    ),
                options = mapOf("Sure!" to { traded = true }),
            )
        if (ring) mes("The Ring of Charos persuades the penguin to help you.")
        runQuestSection(npc, dialogue, "Asking the Zoo penguin the phrase", hooks)
        if (traded && carries(CwObjs.raw_cod)) invDel(inv, CwObjs.raw_cod, 1)
        if ((ring || traded) && state == NEED_PHRASE) setState(PHRASE)
    }

    /* Lumbridge */

    private suspend fun ProtectedAccess.talkSheep(npc: Npc) {
        if (!suited) {
            if (state in LUMBRIDGE..OUTPOST) {
                runQuestSection(npc, dialogue, "Attempting to speak to \"sheep\" as a human")
            } else {
                mes("Baa?") // Authored (not Wiki).
            }
            return
        }
        when (state) {
            LUMBRIDGE ->
                startGreeting(npc, "Greeting the \"sheep\"") {
                    if (state == LUMBRIDGE) setState(NEED_PHRASE)
                }
            NEED_PHRASE ->
                authored(npc, "No phrase, no talk. GO AWAY.") // Authored (not Wiki).
            PHRASE -> {
                runQuestSection(npc, dialogue, "Returning to the \"sheep\" with the phrase")
                setState(INVESTIGATE)
            }
            INVESTIGATE -> authored(npc, "Well? Go and talk to the Farmer!") // Authored (not Wiki).
            FRED_DONE -> {
                var threat = false
                val hooks = SimpleQuestHooks(options = mapOf("The farmer is definitely an enemy." to { threat = true }))
                runQuestSection(npc, dialogue, "Reporting back to the \"sheep\"", hooks)
                setBit(CwVarBits.farmer, if (threat) 1 else 0)
                if (!owns(CwObjs.report_lumbridge)) give(CwObjs.report_lumbridge)
                setState(OUTPOST)
            }
            else -> authored(npc, "The outpost is the iceberg to the North, comrade. Now off you go.") // Authored.
        }
    }

    private suspend fun ProtectedAccess.talkFred(npc: Npc) {
        if (suited) {
            mes("You should get out of the penguin suit before talking to Fred.") // Authored (not Wiki).
            return
        }
        var done = false
        val hooks = SimpleQuestHooks(options = mapOf("Bully Fred" to { done = true }, "Warn Fred" to { done = true }))
        runQuestSection(npc, dialogue, "Speaking with Fred", hooks)
        if (done && state == INVESTIGATE) setState(FRED_DONE)
    }

    /* The iceberg */

    private suspend fun ProtectedAccess.caughtAsHuman(npc: Npc?) {
        mes("The KGP thinks you are a threat.")
        npc?.say("There's been a breach! Red alert!")
        delay(2)
        telejump(ICE_ARRIVAL)
        player.say("Urrrrrgh!")
    }

    private suspend fun ProtectedAccess.talkKgp(npc: Npc) {
        if (!suited) {
            caughtAsHuman(npc)
            return
        }
        if (npc.coords.z > UNDERGROUND_Z) {
            talkBoothAgent(npc)
            return
        }
        when (state) {
            AT_OUTPOST ->
                startGreeting(npc, "In front of the entrance") { if (state == AT_OUTPOST) setState(NEED_ID) }
            NEED_ID, NOODLE, HAVE_ID -> {
                val id = carries(CwObjs.id_card)
                val hooks = SimpleQuestHooks(conditions = mapOf("Without the ID in hand:" to { !id }))
                runQuestSection(npc, dialogue, "Obtaining entry permission", hooks)
                if (id) setState(ENTRY)
            }
            else ->
                if (state >= ENTRY) {
                    authored(npc, "Move along, agent. The entrance is under the avalanche.") // Authored (not Wiki).
                } else {
                    authored(npc, "Don't stand around, agent. You look absurd.")
                }
        }
    }

    private suspend fun ProtectedAccess.talkNoodle(npc: Npc) {
        if (!suited) {
            mes("The penguin eyes you suspiciously and waddles away.") // Authored (not Wiki).
            return
        }
        when {
            state < NEED_ID -> authored(npc, "Oi, wotcha lookin' at?") // Authored (not Wiki).
            state == NEED_ID -> {
                runQuestSection(npc, dialogue, "Obtaining an ID from the black market")
                setState(NOODLE)
            }
            state == NOODLE -> tradeNoodle(npc)
            else -> {
                val hasId = owns(CwObjs.id_card)
                val needReport = state < DEBRIEFED
                val hasReport = !needReport || owns(CwObjs.report_fake)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If player has the Kgp id:" to { hasId }, "If player has the Mission report:" to { hasReport })
                    )
                runQuestSection(npc, dialogue, "Retrieving lost items", hooks)
                if (!hasId) give(CwObjs.id_card)
                if (!hasReport) give(CwObjs.report_fake)
            }
        }
    }

    private suspend fun ProtectedAccess.tradeNoodle(npc: Npc) {
        val tar = carries(CwObjs.swamp_tar)
        val feathers = carries(CwObjs.feather, FEATHERS)
        var offered = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have the items:" to { !tar && !feathers },
                        "With swamp tar only:" to { tar && !feathers },
                        "With feathers only:" to { feathers && !tar },
                    ),
                options = mapOf("Yeah, I got it." to { offered = true }),
            )
        runQuestSection(npc, dialogue, "Trading Noodle", hooks)
        if (!offered || !carries(CwObjs.swamp_tar) || !carries(CwObjs.feather, FEATHERS)) return
        invDel(inv, CwObjs.swamp_tar, 1)
        invDel(inv, CwObjs.feather, FEATHERS)
        give(CwObjs.id_card)
        give(CwObjs.report_fake)
        startDialogue(npc) { chatNpc(neutral, "Oi, cheers. 'Ers 'ur stuff, now git outta 'ere.") }
        setState(HAVE_ID)
    }

    private suspend fun ProtectedAccess.avalanche() {
        if (!suited) {
            caughtAsHuman(null)
            return
        }
        if (state < ENTRY) {
            mes("A KGP agent is watching. You'd better speak to him first.") // Authored (not Wiki).
            return
        }
        telejump(BASE_ENTRANCE)
        mes("You enter the secret lair.")
    }

    private suspend fun ProtectedAccess.leaveBase() {
        telejump(AVALANCHE_OUTSIDE)
        mes("You slip back out under the avalanche.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.talkDebrief(npc: Npc) {
        if (!suited) {
            caughtAsHuman(npc)
            return
        }
        if (state != ENTRY) {
            authored(npc, if (state < ENTRY) "Who let you in here?" else "Off you go, agent. The physical awaits.") // Authored.
            return
        }
        val reports = listOf(CwObjs.report_zoo, CwObjs.report_lumbridge, CwObjs.report_fake)
        val missing = reports.filter { !carries(it) }
        if (missing.isNotEmpty()) {
            authored(npc, "Welcome back agent... Where are your mission reports? Bring all three.") // Authored.
            return
        }
        val male = player.appearance.bodyType == constants.bodytype_a
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player character is male:" to { male }, "If the player character is female:" to { !male })
            )
        runQuestSection(npc, dialogue, "Debriefing", hooks)
        for (report in reports) invDel(inv, report, 1)
        setState(DEBRIEFED)
    }

    private suspend fun ProtectedAccess.toAgilityArea() {
        if (!suited) return
        if (state < DEBRIEFED) {
            mes("You should report to be debriefed first.") // Authored (not Wiki).
            return
        }
        telejump(AGILITY_OUTSIDE)
        if (state == DEBRIEFED && bit(CwVarBits.first_lap) == 0) armyScene()
    }

    private suspend fun ProtectedAccess.fromAgilityArea() {
        telejump(AGILITY_INSIDE)
    }

    /** The penguin army doing drills (camera only). */
    private suspend fun ProtectedAccess.armyScene() {
        cutscenes.play(this, template = null, returnTo = player.coords) {
            camMoveTo(ARMY_CAMERA, height = 600)
            camLookAt(ARMY, height = 150)
            fadeIn()
            delay(5)
            fadeOut()
            delay(2)
        }
    }

    private suspend fun ProtectedAccess.talkCommander(npc: Npc) {
        runQuestSection(npc, dialogue, "Speaking with the army commander outside the course")
    }

    private suspend fun ProtectedAccess.talkBards(npc: Npc) {
        if (!suited) {
            caughtAsHuman(npc)
            return
        }
        when (state) {
            FIND_ROOM -> {
                runQuestSection(npc, dialogue, "Asking penguins in a room nearby about the door", speakers = pingPongSpeakers)
                setState(INSTRUMENTS)
            }
            INSTRUMENTS -> {
                val bell = carries(CwObjs.cowbell)
                val bongos = carries(CwObjs.bongos)
                var yes = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have either:" to { !bell && !bongos },
                                "With cowbells only:" to { bell && !bongos },
                                "With bongos only:" to { bongos && !bell },
                            ),
                        options = mapOf("Yes." to { yes = true }),
                    )
                runQuestSection(npc, dialogue, "The iceberg's history song", hooks, speakers = pingPongSpeakers)
                if (yes && bell && bongos) {
                    invDel(inv, CwObjs.cowbell, 1)
                    invDel(inv, CwObjs.bongos, 1)
                    setState(DISTRACTED)
                }
            }
            DISTRACTED -> authored(npc, "Like, go man. The guard's busy with us.") // Authored (not Wiki).
            else -> authored(npc, "Hey man, peace and harmonious vibes.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.talkBoothAgent(npc: Npc) {
        if (state in FIND_ROOM until DISTRACTED) {
            runQuestSection(npc, dialogue, "Talking to the agent near the large door")
        } else {
            authored(npc, "Move along, comrade.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.controlPanel() {
        if (state != DISTRACTED || !suited) {
            mes("The controls won't respond. Better not touch them with the guard watching.") // Authored (not Wiki).
            return
        }
        warRoom()
    }

    /** The war room: Pescaling Pax's anti-magic catches you; the suit is confiscated. */
    private suspend fun ProtectedAccess.warRoom() {
        val nodes = dialogue.section("Entering the secret room")
        val finished =
            cutscenes.play(this, template = null, returnTo = player.coords) {
                teleport(WAR_ROOM_PLAYER)
                val pax = spawn(CwNpcs.pax, WAR_ROOM_PAX)
                pax.faceTile(WAR_ROOM_PLAYER)
                camMoveTo(WAR_ROOM_CAMERA, height = 500)
                camLookAt(WAR_ROOM_PAX, height = 150)
                fadeIn()
                conversation {
                    val hooks =
                        SimpleQuestHooks(
                            actions =
                                mapOf(
                                    "(Player is transformed back to human)" to { unsuitPlayer(player) },
                                )
                        )
                    startDialogue(pax) { QuestDialogueRunner(hooks, paxSpeakers).run(this, nodes) }
                }
                fadeOut()
                delay(2)
            }
        if (!finished) return
        if (suited) unsuitPlayer(player)
        val suits = invTotal(inv, CwObjs.suit)
        if (suits > 0) invDel(inv, CwObjs.suit, suits)
        setBit(CwVarBits.icelord_killed, 0)
        setState(CAUGHT)
        telejump(PEN_INSIDE)
        mes("You are caught.")
    }

    private suspend fun ProtectedAccess.penDoor(door: BoundLocInfo) {
        val inside = player.coords.x >= door.coords.x
        if (inside && state == CAUGHT && bit(CwVarBits.icelord_killed) == 0) {
            mes("The Ice Lords prevent your escape.")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.chasm() {
        mes("You climb up through the chasm.") // Authored (not Wiki).
        telejump(ICE_ARRIVAL)
    }

    private suspend fun ProtectedAccess.crack() {
        if (state < CAUGHT) {
            mes("A crack in the ice. It's too dark to see where it leads.") // Authored (not Wiki).
            return
        }
        mes("You squeeze down through the crack.") // Authored (not Wiki).
        telejump(PEN_INSIDE)
    }

    private fun icelordKilled(event: NpcKilledEvent) {
        if (event.npc.type.id !in CwNpcs.icelord_ids) return
        val player = event.hero
        if (player.questState(COLD_WAR) != CAUGHT) return
        VarPlayerIntMapSetter.set(player, CwVarBits.icelord_killed, 1)
    }

    /* Items */

    private suspend fun ProtectedAccess.playInstrument(bongos: Boolean) {
        if (!suited) {
            mes("You need to be in your penguin suit to play these.") // Authored (not Wiki).
            return
        }
        if (bongos) anim(CwSeqs.bongo)
        mes(if (bongos) "You play the bongos." else "You ring the cowbells.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.makeBongos() {
        if (state == 0) {
            mes("Nothing interesting happens.")
            return
        }
        if (statBase(stats.crafting) < BONGO_LEVEL) {
            mes("You need a Crafting level of $BONGO_LEVEL to make penguin bongos.") // Authored (not Wiki).
            return
        }
        invDel(inv, CwObjs.mahogany_plank, 1)
        invDel(inv, CwObjs.leather, 1)
        invAdd(inv, CwObjs.bongos)
        statAdvance(stats.crafting, BONGO_XP * xpMods.get(player, stats.crafting))
        mes("You use the leather and plank to create penguin bongos.")
    }

    /* Helpers */

    private class Box(val x0: Int, val z0: Int, val x1: Int, val z1: Int, val level: Int? = null)

    private fun CoordGrid.inBox(box: Box): Boolean =
        (box.level == null || level == box.level) && x in box.x0..box.x1 && z in box.z0..box.z1

    companion object {
        const val STARTED = 5
        const val ICEBERG = 10
        const val OBSERVED = 15
        const val LEAVE = 18
        /** Larry gave the clockwork book (ClockmakersBench gates the suit on this value). */
        const val BOOK = 20
        const val SUIT_TRIP = 25
        const val ZOO_TEST = 30
        const val ARDY_REPORT = 35
        const val LUMBRIDGE = 40
        const val NEED_PHRASE = 45
        const val PHRASE = 50
        const val INVESTIGATE = 55
        const val FRED_DONE = 60
        const val OUTPOST = 65
        const val AT_OUTPOST = 70
        const val NEED_ID = 75
        const val NOODLE = 78
        const val HAVE_ID = 80
        const val ENTRY = 85
        /** Debriefed: the agility course is open (PenguinAgility). */
        const val DEBRIEFED = 90
        const val COURSE_DONE = 95
        const val FIND_ROOM = 100
        const val INSTRUMENTS = 105
        const val DISTRACTED = 110
        /** Caught in the war room; the client shows the crack from here (`peng_multi_crack`). */
        const val CAUGHT = 130
        const val COMPLETE = 135

        private const val HIDE_DESTROYED = 3
        private const val PLANKS = 10
        private const val FEATHERS = 5
        private const val EMOTE_COUNT = 8
        private const val GREETING_RANGE = 12
        private const val HIDE_DIG_RANGE = 4
        private const val SUIT_CHECK_TICKS = 8
        private const val BONGO_LEVEL = 30
        private const val BONGO_XP = 25.0
        // Cache db stat_xp_awarded (tenths): agility 50000, crafting 20000, construction 15000.
        private const val CRAFTING_XP = 2000.0
        private const val AGILITY_XP = 5000.0
        private const val CONSTRUCTION_XP = 1500.0
        private const val LUMBRIDGE_MIN_X = 3000
        private const val UNDERGROUND_Z = 9000

        /** Unboosted requirements to start (cache db requirement_stats, Wiki "Questreqstart"). */
        private val REQUIREMENTS =
            listOf(
                ({ stats.hunter } to "Hunter") to 10,
                ({ stats.agility } to "Agility") to 30,
                ({ stats.crafting } to "Crafting") to 30,
                ({ stats.construction } to "Construction") to 34,
                ({ stats.thieving } to "Thieving") to 15,
            )

        /* Positions from the rev 243 map (tools/agility/maplocs.py) and its tile flags. */
        private val ZOO_ARRIVAL = CoordGrid(2597, 3265, 0)
        private val RELLEKKA_ARRIVAL = CoordGrid(2706, 3734, 0)
        /** The iceberg surface is level 1 (Larry 2661,3990,1). */
        private val ICE_ARRIVAL = CoordGrid(2661, 3989, 1)
        private val HIDE = CoordGrid(2665, 3991, 1)
        private val HIDE_VIEW = CoordGrid(2663, 3991, 1)
        private val PENGUIN_A = CoordGrid(2665, 3996, 1)
        private val PENGUIN_B = CoordGrid(2673, 3994, 1)
        private val MEETING = CoordGrid(2669, 3995, 1)
        private val CAMERA = CoordGrid(2664, 3991, 1)
        /** Outside the avalanche (2638,4011) and inside the base by its south double door. */
        private val AVALANCHE_OUTSIDE = CoordGrid(2640, 4008, 1)
        private val BASE_ENTRANCE = CoordGrid(2658, 10372, 0)
        /** Either side of the agility corridor's north door (2633,10404) / the cliff door (2643,4032). */
        private val AGILITY_INSIDE = CoordGrid(2633, 10403, 0)
        private val AGILITY_OUTSIDE = CoordGrid(2643, 4033, 1)
        private val ARMY = CoordGrid(2638, 4044, 1)
        private val ARMY_CAMERA = CoordGrid(2644, 4036, 1)
        private val WAR_ROOM_PLAYER = CoordGrid(2658, 10412, 0)
        private val WAR_ROOM_PAX = CoordGrid(2660, 10412, 0)
        private val WAR_ROOM_CAMERA = CoordGrid(2655, 10411, 0)
        private val PEN_INSIDE = CoordGrid(2645, 10425, 0)

        private val ICEBERG_AREA = Box(2600, 3960, 2700, 4100)
        private val BASE_AREA = Box(2620, 10360, 2690, 10440)
        private val PEN_AREA = Box(2636, 10416, 2660, 10430)
        private val SUIT_AREAS =
            listOf(
                Box(2580, 3250, 2615, 3285), // Ardougne Zoo.
                Box(3190, 3250, 3235, 3290), // Lumbridge sheep field.
                ICEBERG_AREA,
                BASE_AREA,
            )

        private const val REPORT_ZOO =
            "Codename: Pop<br>Day 420: A strange penguin has arrived at the enclosure. He looks like he's been in " +
                "some sort of horrible accident. But he has revealed to me that he can fly! Further investigation " +
                "is suggested."
        private const val REPORT_LUMBRIDGE =
            "Codenames: Paxton and Philbert<br>Day 350: Our nemesis, the farmer, has again foiled our plans. These " +
                "blasted humans keep attempting to steal our disguise. Perhaps they wish to infiltrate the sheep as " +
                "well? We must look into this further."
        // Authored (not Wiki): the fake report's text isn't transcribed.
        private const val REPORT_FAKE =
            "Codename: Peach/Pablo<br>Mission complete. Flight achieved by flapping. Really fast. Recommend all " +
                "agents attempt it. Nothing further to report."

        val COLD_WAR =
            QuestDef(
                name = "Cold War",
                varbit = { CwVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "2,000 Crafting XP",
                        "5,000 Agility XP",
                        "1,500 Construction XP",
                        "Penguin Agility Course",
                        "Clockwork suits",
                    ),
                scrollObj = { CwObjs.suit },
            )
    }
}
