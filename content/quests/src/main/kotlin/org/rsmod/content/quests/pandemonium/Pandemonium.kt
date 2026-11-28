package org.rsmod.content.quests.pandemonium

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.skills.sailing.SailingBoats
import org.rsmod.content.skills.sailing.SailingLedgerHooks
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Pandemonium (OSRS Wiki), the Sailing intro quest. Quest varbit `sailing_intro` (bits 0-5 of
 * `sailing_intro_primary`), end state 50. The cache multinpcs on it fix the stage windows: Will and
 * Anne stand on the Port Sarim docks for 0-14, Steve Beanie gains "Trade" after 18, Junior Jim's
 * shipyard copy shows for 28-32, Old Grog for 40-46 and Jim's shipwright ops come at the end.
 * - 2 the interview (Will or Anne); 4 offered the job; 6 aboard their ship: steer south through the
 *   Bay of Sarim to the wreck east of the Karamja Shipyard (`sailing_intro_reached_wreck`, 8).
 * - 12 the salvaging hook brings up an old cup and Barnaby's medallion; Will takes the medallion
 *   and Anne shoves you overboard. 16 you wash up at the Pandemonium; 18 Steve sends you to Ribs;
 *   20 Ribs' tale of the Crimson Kraken; 24 Steve sends you to Junior Jim.
 * - 28 the cup buys a raft (the Sailing module's raft, moored at the Pandemonium); Jim takes you
 *   to the shipyard: hammer and saw from the crates, build the cargo hold from the gangplank (30);
 *   back at the Pandemonium (32) Jim hands over a captain's log (34).
 * - Sail the raft to Port Sarim (Sailing [org.rsmod.content.skills.sailing.Voyages]), take the
 *   crate of ship parts from the ledger table, sail back and deposit it at the Pandemonium's
 *   ledger table: 100 Sailing XP (38). Jim: 2 repair kits and a spyglass (40). Steve introduces
 *   Old Grog (48), then 25 sawmill coupons, 300 Sailing XP and 1 QP (50).
 *
 * Simplified (no boardable world-entity boats yet): Will and Anne's ship is steered from a
 * heading menu - headings, wind gusts to trim for and the crew's direction hints come from the
 * transcript - instead of the helm and minimap, and the crew stay ashore visually; washing up,
 * the shipyard trips and raft voyages are fade/teleport jumps through the shared `sailTo`; the
 * cargo hold is built from the shipyard gangplank (no deck facility hotspot) and the cargo crate
 * is carried in the inventory rather than stowed in the hold, so Junior Jim's repair kits and
 * spyglass go straight to the inventory; Steve's pub stock isn't stocked (his "Trade" op stays
 * unbound); the sea-shanty music unlocks are skipped.
 */
class Pandemonium
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objRepo: ObjRepository,
    private val collision: CollisionFlagMap,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(Pandemonium::class.java, "pandemonium") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Will" to PanNpcs.will,
            "Anne" to PanNpcs.anne,
            "'Squawking' Steve Beanie" to PanNpcs.steve,
            "Ribs" to PanNpcs.ribs,
            "Junior Jim" to PanNpcs.jim,
            "Old Grog" to PanNpcs.grog,
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (npc in listOf(PanNpcs.will, PanNpcs.anne)) {
                register(npcTypes, npc, PANDEMONIUM, wants = { true }) { talkWillAnne(it) }
            }
            for (npc in listOf(PanNpcs.steve, PanNpcs.steve_trade)) {
                register(npcTypes, npc, PANDEMONIUM, wants = { state in WASHED_UP until COMPLETE }) { talkSteve(it) }
                default(npcTypes, npc) { steveDefault(it) }
            }
            register(npcTypes, PanNpcs.ribs, PANDEMONIUM, wants = { true }) { talkRibs(it) }
            register(npcTypes, PanNpcs.grog, PANDEMONIUM, wants = { true }) { talkGrog(it) }
            register(npcTypes, PanNpcs.jim, PANDEMONIUM, wants = { state < COMPLETE }) { talkJim(it) }
            default(npcTypes, PanNpcs.jim) { jimDefault(it) }
            default(npcTypes, PanNpcs.jim_shipwright) { jimDefault(it) }
            register(npcTypes, PanNpcs.master_sarim, PANDEMONIUM, wants = { state == LOG_GIVEN }) {
                runQuestSection(it, dialogue, "Talking to the Port master", cargoHooks(), speakers)
            }
            register(npcTypes, PanNpcs.master_pandemonium, PANDEMONIUM, wants = { state in LOG_GIVEN..DELIVERED }) {
                masterPandemonium(it)
            }
            for (master in listOf(PanNpcs.master_sarim, PanNpcs.master_pandemonium)) {
                default(npcTypes, master) { masterDefault(it) }
            }
        }
        onOpNpcU(PanNpcs.jim, PanObjs.cup) { talkJim(it.npc) }

        // Junior Jim's shipwright ops once the quest is done (Buy / Customise / Recover boat).
        val shipwright = PanNpcs.jim_shipwright
        if (!isNpcOpBound(shipwright, 3)) onOpNpc3(shipwright) { noNewBoats(it.npc) }
        if (!isNpcOpBound(shipwright, 4)) onOpNpc4(shipwright) { noNewBoats(it.npc) }
        if (!isNpcOpBound(shipwright, 5)) onOpNpc5(shipwright) { recoverBoat(it.npc) }

        // The shipyard (a static map area here; an instance in OSRS).
        if (!isLocOpBound(PanLocs.crate_hammers, 1)) onOpLoc1(PanLocs.crate_hammers) { takeTool(PanObjs.hammer, "hammers") }
        if (!isLocOpBound(PanLocs.crate_saws, 1)) onOpLoc1(PanLocs.crate_saws) { takeTool(PanObjs.saw, "saws") }
        if (!isLocOpBound(PanLocs.shipyard_gangplank, 1)) onOpLoc1(PanLocs.shipyard_gangplank) { buildCargoHold() }
        if (!isLocOpBound(PanLocs.schematics, 1)) {
            onOpLoc1(PanLocs.schematics) {
                runQuestSection(null, dialogue, "Modify Boat Schematics before building the cargo hold")
            }
        }
        if (!isLocOpBound(PanLocs.shipyard_portal, 1)) onOpLoc1(PanLocs.shipyard_portal) { leaveShipyard() }

        SailingLedgerHooks.register(SailingLedgerHooks.Hook(handle = { port -> ledger(port) }, carrying = { carriesCrate() }))
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(PANDEMONIUM)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(PANDEMONIUM, value)
    }

    private fun ProtectedAccess.give(obj: ObjType, count: Int = 1) {
        invAddOrDrop(objRepo, obj, count)
    }

    private fun ProtectedAccess.carriesCrate(): Boolean = carries(PanObjs.crate)

    private fun ProtectedAccess.hasLog(): Boolean =
        carries(PanObjs.log_initial) || carries(PanObjs.log) ||
            invTotal(bank, PanObjs.log_initial) > 0 || invTotal(bank, PanObjs.log) > 0

    private fun ProtectedAccess.hasTools(): Boolean = carries(PanObjs.hammer) && carries(PanObjs.saw)

    private fun ProtectedAccess.xp(amount: Double) {
        statAdvance(stats.sailing, amount * xpMods.get(player, stats.sailing))
    }

    /* Will and Anne, Port Sarim */

    private suspend fun ProtectedAccess.talkWillAnne(npc: Npc) {
        var ready = false
        val readyHooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the quest cannot be started:" to { false },
                        "If the player has no inventory space:" to { inv.isFull() },
                    ),
                options =
                    mapOf(
                        "Yes." to { setState(STARTED) },
                        "I guess I'm ready..." to { setState(OFFERED); ready = true },
                        "I'd rather not..." to { setState(OFFERED) },
                        "I guess so..." to { ready = true },
                        "Okay." to { ready = true },
                    ),
            )
        when (state) {
            0 -> runQuestSection(npc, dialogue, "Talking to Anne or Will", readyHooks, speakers)
            STARTED -> runQuestBranch(npc, dialogue, "Talking to Anne or Will", "Yes.", readyHooks, speakers)
            OFFERED -> runQuestSection(npc, dialogue, "Talking to Anne or Will after being offered the job", readyHooks, speakers)
            in SAILING until WASHED_UP ->
                runQuestSection(npc, dialogue, "Talking to Will or Anne back at Port Sarim", readyHooks, speakers)
            else -> return
        }
        if (ready && !inv.isFull()) voyage()
    }

    /* Aboard Will and Anne's ship */

    private suspend fun ProtectedAccess.voyage() {
        if (state == HOOKED) {
            overboard()
            return
        }
        val firstTime = state < SAILING
        setState(maxOf(state, SAILING))
        mes("You board Will and Anne's ship.")
        var outcome = Outcome.Abandoned
        startDialogue {
            if (firstTime) QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, dialogue.section("Talking to Anne or Will (2)"))
            outcome = if (state >= REACHED) Outcome.Arrived else navigate()
            if (outcome == Outcome.Arrived) outcome = salvage()
        }
        when (outcome) {
            Outcome.Hooked -> overboard()
            Outcome.Capsized -> mes("You find yourself back on the Port Sarim docks.")
            else -> mes("Will and Anne moor the ship back at Port Sarim.")
        }
    }

    private enum class Outcome {
        Abandoned,
        Capsized,
        Arrived,
        Hooked,
    }

    /**
     * The helm: headings move the ship [STEP] tiles over the Bay of Sarim; a gust doubles the next
     * leg if you trim the sails for it. Will and Anne call out the wreck's direction after each leg.
     */
    private suspend fun Dialogue.navigate(): Outcome {
        val runner = QuestDialogueRunner(SimpleQuestHooks(), speakers)
        var x = START.x
        var z = START.z
        var gust = false
        var boosted = false
        while (true) {
            if (maxOf(kotlin.math.abs(x - WRECK.x), kotlin.math.abs(z - WRECK.z)) <= ARRIVE_RADIUS) {
                return Outcome.Arrived
            }
            val last = if (gust) "Trim the sails." else "Talk to Will and Anne."
            val heading =
                choice5(
                    "Head north.", 0, "Head east.", 1, "Head south.", 2, "Head west.", 3, last, 4,
                    title = "Steer the ship",
                )
            if (heading == 4) {
                if (gust) {
                    gust = false
                    boosted = true
                    mesbox("You trim the sails, catching the wind for a burst of speed!")
                    praise()
                    continue
                }
                var back = false
                val hooks = SimpleQuestHooks(options = mapOf("Actually, I want to go back to Port Sarim." to { back = true }))
                QuestDialogueRunner(hooks, speakers).run(this, dialogue.section("Talking to Will or Anne before sailing off"))
                if (back) return Outcome.Abandoned
                continue
            }
            if (gust) {
                // The gust passes untrimmed (Wiki: "When the ship is losing wind").
                gust = false
                runner.run(this, listOf(dialogue.section("When the ship is losing wind").first().children.first()))
                mesbox("The wind dies down and your sails with it.")
            }
            val step = if (boosted) STEP * 2 else STEP
            boosted = false
            when (heading) {
                0 -> z += step
                1 -> x += step
                2 -> z -= step
                else -> x -= step
            }
            if (x !in BAY_X || z !in BAY_Z) {
                runner.run(this, dialogue.section("Capsizing"))
                return Outcome.Capsized
            }
            if (maxOf(kotlin.math.abs(x - WRECK.x), kotlin.math.abs(z - WRECK.z)) > ARRIVE_RADIUS) {
                hint(x, z)
                if (random.of(1, GUST_CHANCE) == 1) {
                    gust = true
                    runner.run(this, dialogue.section("If the wind is picking up"))
                }
            }
        }
    }

    /** "While the ship is moving": Will names a diagonal, Anne a straight heading (transcript). */
    private suspend fun Dialogue.hint(x: Int, z: Int) {
        val dx = WRECK.x - x
        val dz = WRECK.z - z
        val ns = when { dz > ARRIVE_RADIUS -> "north"; dz < -ARRIVE_RADIUS -> "south"; else -> "" }
        val ew = when { dx > ARRIVE_RADIUS -> "east"; dx < -ARRIVE_RADIUS -> "west"; else -> "" }
        if (ns.isNotEmpty() && ew.isNotEmpty()) {
            chatNpcSpecific("Will", PanNpcs.will, neutral, "The wreck should be $ns $ew of here.")
        } else {
            chatNpcSpecific("Anne", PanNpcs.anne, neutral, "We need to be going ${ns.ifEmpty { ew }}.")
        }
    }

    private suspend fun Dialogue.praise() {
        when (random.of(0, 2)) {
            0 -> chatNpcSpecific("Will", PanNpcs.will, happy, "Very good!")
            1 -> chatNpcSpecific("Anne", PanNpcs.anne, happy, "Good work!")
            else -> chatNpcSpecific("Anne", PanNpcs.anne, happy, "Nicely done!")
        }
    }

    /** At the wreck: lower the salvaging hook (needs a free slot for the cup). */
    private suspend fun Dialogue.salvage(): Outcome {
        val runner = QuestDialogueRunner(SimpleQuestHooks(), speakers)
        if (access.state < REACHED) {
            VarPlayerIntMapSetter.set(player, PanVarBits.reached_wreck, 1)
            access.setState(REACHED)
            runner.run(this, dialogue.section("Reaching the salvaging point"))
        } else {
            runner.run(this, dialogue.section("Talking to Anne or Will after they explain salvaging, or interacting with the helm or sails"))
        }
        while (true) {
            val deploy = choice2("Deploy the salvaging hook.", true, "Talk to Will and Anne.", false, title = "The wreck")
            if (!deploy) {
                runner.run(this, dialogue.section("Talking to Anne or Will after they explain salvaging, or interacting with the helm or sails"))
                continue
            }
            val full = access.inv.isFull()
            val hooks =
                SimpleQuestHooks(
                    conditions = mapOf("If the player has no inventory space:" to { full }),
                    boxes =
                        mapOf(
                            "You use the salvaging hook to gather an old cup and a mysterious medallion." to
                                {
                                    access.invAdd(access.inv, PanObjs.cup)
                                    access.setState(HOOKED)
                                    false
                                }
                        ),
                )
            QuestDialogueRunner(hooks, speakers).run(this, dialogue.section("Deploying the salvaging hook"))
            if (!full) return Outcome.Hooked
        }
    }

    private suspend fun ProtectedAccess.overboard() {
        val shore = collision.nearestWalkable(WASH_UP, 6)
        sailTo(shore, fare = 0, "Anne shoves you overboard! You swim for the nearest shore...", collision, fade = false)
        setState(WASHED_UP)
        val swim = dialogue.section("Swimming to shore")
        // The transcript's last swim line is Steve's greeting, which opens his first conversation.
        startDialogue { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, swim.take(2)) }
        steveFirst(null)
    }

    /* The Pandemonium */

    private suspend fun ProtectedAccess.steveFirst(npc: Npc?) {
        runQuestSection(npc, dialogue, "Talking to 'Squawking' Steve Beanie", speakers = speakers)
        setState(STEVE_RIBS)
    }

    private suspend fun ProtectedAccess.talkSteve(npc: Npc) {
        when (state) {
            WASHED_UP -> steveFirst(npc)
            in STEVE_RIBS until RIBS_TOLD -> steveMenu(npc, "Talking to Squaking' Steve Beanie again")
            in RIBS_TOLD until STEVE_JIM -> {
                var told = false
                var below = false
                val hooks =
                    steveHooks(
                        extraOptions = mapOf("About Will and Anne..." to { told = true }),
                        actions = mapOf("below" to { below = true }),
                    )
                runQuestSection(npc, dialogue, "Talking to 'Squawking' Steve Beanie again", hooks, speakers)
                if (below) {
                    // The "one rule" branch rejoins the other question's answer (Wiki "below").
                    val rest =
                        dialogue
                            .branch("Talking to 'Squawking' Steve Beanie again", "Do you have any idea where Will and Anne might have gone?")
                            .drop(2)
                    startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, rest) }
                }
                if (told) setState(STEVE_JIM)
                steveAfterMenu(npc)
            }
            in STEVE_JIM until GROG -> steveMenu(npc, "Subsequent dialogue with 'Squawking' Steve Beanie")
            in GROG until COMPLETE -> grogMeeting(npc)
            else -> steveDefault(npc)
        }
    }

    private var ProtectedAccess.pendingSteve: String?
        get() = pendingSteveOption[player.uid.packed]
        set(value) {
            if (value == null) pendingSteveOption.remove(player.uid.packed) else pendingSteveOption[player.uid.packed] = value
        }

    /** The option picked in Steve's menu whose answer is elsewhere in the transcript ("above"). */
    private val pendingSteveOption = HashMap<Int, String>()

    private fun ProtectedAccess.steveHooks(
        extraOptions: Map<String, suspend () -> Unit> = emptyMap(),
        actions: Map<String, suspend () -> Unit> = emptyMap(),
    ): SimpleQuestHooks {
        val options = HashMap<String, suspend () -> Unit>(extraOptions)
        options["Let's see what you have."] = { pendingSteve = SHOP }
        options["I think I might have lost a cup here."] = { pendingSteve = CUP }
        options["Could you tell me about the Pandemonium's one rule?"] = { pendingSteve = RULE }
        return SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has not yet asked about Will and Anne:" to { false },
                    "If the player has no inventory space:" to { inv.isFull() },
                ),
            options = options,
            actions = actions,
        )
    }

    private suspend fun ProtectedAccess.steveMenu(npc: Npc, section: String) {
        pendingSteve = null
        // The shop / cup / rule answers run afterwards (steveAfterMenu), so drop any inline copy.
        val nodes =
            dialogue.section(section).map { node ->
                if (node is Node.Option && node.label in DEFERRED_OPTIONS) Node.Option(node.label) else node
            }
        startDialogue(npc) { QuestDialogueRunner(steveHooks(), speakers).run(this, nodes) }
        steveAfterMenu(npc)
    }

    /** Steve's shop, lost cup and "one rule" answers (the later menus point back to these). */
    private suspend fun ProtectedAccess.steveAfterMenu(npc: Npc) {
        val picked = pendingSteve ?: return
        pendingSteve = null
        when (picked) {
            SHOP -> mes("Steve's rum stock isn't available on this server yet.")
            CUP ->
                if (state < CUP_GIVEN && !carries(PanObjs.cup)) {
                    val hooks =
                        SimpleQuestHooks(
                            conditions = mapOf("If the player has no inventory space:" to { inv.isFull() }),
                            boxes = mapOf("Steve gives you a cup." to { invAdd(inv, PanObjs.cup); false }),
                        )
                    runQuestBranch(npc, dialogue, "Talking to Squaking' Steve Beanie again", "I think I might have lost a cup here.", hooks, speakers)
                } else {
                    // Authored (not Wiki): nothing to hand back.
                    startDialogue(npc) { chatNpc(neutral, "Nobody's handed anything in for you, matey. Avast!") }
                }
            RULE ->
                runQuestBranch(npc, dialogue, "Talking to 'Squawking' Steve Beanie again", "What exactly is this one rule you keep mentioning?", speakers = speakers)
        }
    }

    private suspend fun ProtectedAccess.grogMeeting(npc: Npc) {
        val hooks = SimpleQuestHooks(boxes = mapOf("Old Grog departs." to { setState(GROG_GONE); false }))
        runQuestSection(npc, dialogue, "Talking to Steve", hooks, speakers)
        if (state < GROG_GONE) setState(GROG_GONE)
        give(PanObjs.sawmill_coupon, SAWMILL_COUPONS)
        xp(QUEST_XP)
        completeQuest(PANDEMONIUM)
    }

    private suspend fun ProtectedAccess.steveDefault(npc: Npc) {
        if (state >= COMPLETE) {
            pendingSteve = null
            val nodes = dialogue.section("Talking to Steve again after Old Grog departs").take(1)
            startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes) }
            return
        }
        // Authored (not Wiki): Steve before you wash up here.
        startDialogue(npc) {
            chatNpc(happy, "Yo ho ho and a Jolly Roger to you, me hearty! Welcome to the Pandemonium, finest establishment in all the seas!")
        }
    }

    private suspend fun ProtectedAccess.talkRibs(npc: Npc) {
        when {
            state in STEVE_RIBS until RIBS_TOLD -> {
                runQuestSection(npc, dialogue, "Talking to Ribs", speakers = speakers)
                setState(RIBS_TOLD)
            }
            state in RIBS_TOLD until STEVE_JIM -> runQuestSection(npc, dialogue, "Talking to Ribs again", speakers = speakers)
            else ->
                // Authored (not Wiki): Ribs outside his part of the quest.
                startDialogue(npc) { chatNpc(neutral, "Ahoy, young whippersnapper. Mind the curse of Barnacle Barnaby, and old Ribs will mind his rum.") }
        }
    }

    private suspend fun ProtectedAccess.talkGrog(npc: Npc) {
        // Authored (not Wiki): Old Grog leaves the talking to Steve.
        startDialogue(npc) { chatNpc(neutral, "Steve's the one doing the talking. Go on, speak to him.") }
    }

    /* Junior Jim and the shipyard */

    private suspend fun ProtectedAccess.talkJim(npc: Npc) {
        val atShipyard = npc.coords.x < SHIPYARD_MAX_X
        when (state) {
            in STEVE_JIM until CUP_GIVEN -> tradeCup(npc)
            in CUP_GIVEN until HOLD_BUILT ->
                if (atShipyard) {
                    val hooks =
                        SimpleQuestHooks(
                            conditions =
                                mapOf(
                                    "If the player has not grabbed both the sawmill and hammer before talking to him:" to { !hasTools() },
                                    "If the player talks to Jim after grabbing the tools:" to { hasTools() },
                                )
                        )
                    runQuestSection(npc, dialogue, "Talking to Junior Jim again", hooks, speakers)
                } else {
                    runQuestSection(npc, dialogue, "Talking to Junior Jim after giving him the cup", speakers = speakers)
                    toShipyard()
                }
            HOLD_BUILT -> {
                val nodes = dialogue.section("Talking to Junior Jim again (2)")
                startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes.take(1)) }
                if (atShipyard) {
                    sailTo(collision.nearestWalkable(JIM_DOCK), 0, "You head back to the Pandemonium with Junior Jim.", collision)
                }
                setState(BACK)
                startDialogue { QuestDialogueRunner(logHooks(), speakers).run(this, nodes.drop(1)) }
            }
            BACK -> runQuestSection(npc, dialogue, "Talking to Junior Jim after he gives you the Captain's log", logHooks(), speakers)
            in LOG_GIVEN until DELIVERED ->
                if (!hasLog()) {
                    runQuestSection(npc, dialogue, "Talking to Junior Jim after he gives you the Captain's log", logHooks(), speakers)
                } else {
                    val hooks =
                        SimpleQuestHooks(
                            conditions =
                                mapOf(
                                    "If the player is not carrying the cargo:" to { !carriesCrate() },
                                    "If the player is carrying the cargo:" to { carriesCrate() },
                                )
                        )
                    runQuestSection(npc, dialogue, "Talking to Junior Jim after he asks for the cargo", hooks, speakers)
                }
            DELIVERED -> {
                runQuestSection(npc, dialogue, "Talking to Junior Jim after depositing the cargo", speakers = speakers)
                give(PanObjs.repair_kit, REPAIR_KITS)
                give(PanObjs.spyglass)
                VarPlayerIntMapSetter.set(player, PanVarBits.received_repairkits, 1)
                mes("Junior Jim gives you two repair kits and a spyglass.")
                setState(GROG)
            }
            in GROG until COMPLETE ->
                // Authored (not Wiki): Jim waits on Steve's news with you.
                startDialogue(npc) { chatNpc(neutral, "Steve's been asking after you, mate. Best pop into the pub and see him.") }
            else -> runQuestSection(npc, dialogue, "Talking to Junior Jim")
        }
    }

    private suspend fun ProtectedAccess.tradeCup(npc: Npc) {
        val has = carries(PanObjs.cup)
        var traded = false
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player lost the old cup:" to { !has }),
                boxes =
                    mapOf(
                        "You give the cup to Junior Jim." to
                            {
                                invDel(inv, PanObjs.cup)
                                traded = true
                                false
                            }
                    ),
            )
        val nodes = dialogue.section("Talking to Junior Jim (2)")
        val split = nodes.indexOfFirst { it is Node.Line && it.text.startsWith("Right, time to sort you out with a cargo hold.") }
        startDialogue(npc) { QuestDialogueRunner(hooks, speakers).run(this, nodes.take(split + 1)) }
        if (!traded) return
        if (!SailingBoats.grantRaft(player, SailingBoats.PANDEMONIUM_DOCK)) {
            // Authored: a raft from before this quest existed on this server is fitted out instead.
            SailingBoats.moor(player, SailingBoats.PANDEMONIUM_DOCK)
            mes("Junior Jim has your raft brought round to the Pandemonium to be fitted out.")
        } else {
            mes("You now own a raft. It is moored at the Pandemonium.")
        }
        setState(CUP_GIVEN)
        toShipyard()
        startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes.drop(split + 1)) }
    }

    private suspend fun ProtectedAccess.toShipyard() {
        sailTo(collision.nearestWalkable(SHIPYARD), 0, "Junior Jim takes you over to the shipyard.", collision)
    }

    private suspend fun ProtectedAccess.leaveShipyard() {
        telejump(collision.nearestWalkable(JIM_DOCK))
        mes("You step through the portal and arrive back at the Pandemonium.")
    }

    private fun ProtectedAccess.logHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions = mapOf("If the player has no inventory space:" to { inv.isFull() }),
            boxes =
                mapOf(
                    "Junior Jim gives you a log book." to
                        {
                            invAdd(inv, PanObjs.log_initial)
                            if (state < LOG_GIVEN) setState(LOG_GIVEN)
                            false
                        }
                ),
        )

    private suspend fun ProtectedAccess.takeTool(tool: ObjType, kind: String) {
        val section = if (kind == "hammers") "Taking from the crate of hammers" else "Taking from the crate of saws"
        val has = carries(tool)
        if (!has && inv.isFull()) {
            mes("You don't have enough inventory space to take that.")
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not already have a hammer:" to { !has },
                        "If the player already has a hammer:" to { has },
                        "If the player does not already have a saw:" to { !has },
                        "If the player already has a saw:" to { has },
                    )
            )
        runQuestSection(null, dialogue, section, hooks)
        if (!has) invAdd(inv, tool)
    }

    private suspend fun ProtectedAccess.buildCargoHold() {
        when {
            state != CUP_GIVEN -> mes("You have no reason to board your raft here right now.")
            !hasTools() -> mes("You'll need a hammer and a saw to build the cargo hold.")
            else -> {
                mes("You board your raft and get to work on the facility hotspot.")
                runQuestSection(null, dialogue, "Building the Cargo hold", speakers = speakers)
                setState(HOLD_BUILT)
                mes("You build a basic cargo hold and step back onto the dock.")
            }
        }
    }

    private suspend fun ProtectedAccess.jimDefault(npc: Npc) {
        // Authored (not Wiki): Jim after the quest, before he has new vessels in.
        startDialogue(npc) {
            chatNpc(happy, "Alright, mate! Still sailing that raft? Come back once you've got a bit more know-how and I'll sort you right out.")
        }
    }

    private suspend fun ProtectedAccess.noNewBoats(npc: Npc) {
        // Authored (not Wiki): no bigger boats or customisation until boats are in (tier 1).
        startDialogue(npc) { chatNpc(neutral, "Sorry, mate, the raft is all I've got to hand right now. Come back later.") }
    }

    private suspend fun ProtectedAccess.recoverBoat(npc: Npc) {
        if (!SailingBoats.ownsBoat(player)) {
            startDialogue(npc) { chatNpc(neutral, "You haven't got a boat to recover, you silly plonker!") }
            return
        }
        SailingBoats.moor(player, SailingBoats.PANDEMONIUM_DOCK)
        startDialogue(npc) { chatNpc(happy, "Right you are, mate. Your boat's moored here at the Pandemonium now.") }
    }

    /* Port masters and the ledger tables */

    private fun ProtectedAccess.cargoHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player is holding the cargo:" to { carriesCrate() },
                    "If the player is not carrying the cargo:" to { !carriesCrate() },
                    "If the player is carrying the cargo:" to { carriesCrate() },
                )
        )

    private suspend fun ProtectedAccess.masterPandemonium(npc: Npc) {
        if (state == DELIVERED) {
            runQuestSection(npc, dialogue, "Talking to the Port master after dropping off the cargo")
            return
        }
        // The transcript repeats the player's line in the carrying branch; it is shown once.
        val nodes = dialogue.section("Talking to the Port master (2)").map { node ->
            if (node is Node.Cond && node.text == "If the player is carrying the cargo:") {
                Node.Cond(node.text).also { it.children += node.children.drop(1) }
            } else {
                node
            }
        }
        startDialogue(npc) { QuestDialogueRunner(cargoHooks(), speakers).run(this, nodes) }
    }

    private suspend fun ProtectedAccess.masterDefault(npc: Npc) {
        // Authored (not Wiki): the port master's general greeting.
        startDialogue(npc) {
            chatNpc(neutral, "Hi there. If you're after work, the notice board here has port tasks, and the ledger table is where cargo comes and goes.")
        }
    }

    /** Pandemonium's crate of ship parts at the ledger tables (asked before the courier tasks). */
    private suspend fun ProtectedAccess.ledger(port: String): Boolean {
        if (state != LOG_GIVEN) return false
        return when (port) {
            PORT_SARIM -> {
                if (carriesCrate()) return false
                if (inv.isFull()) {
                    mes("You need a free inventory space to carry the cargo.")
                    return true
                }
                invAdd(inv, PanObjs.crate)
                runQuestSection(null, dialogue, "Taking cargo from the ledger table")
                true
            }
            THE_PANDEMONIUM -> {
                if (!carriesCrate()) return false
                invDel(inv, PanObjs.crate)
                xp(DELIVERY_XP)
                setState(DELIVERED)
                val master = mapOf("Port master" to PanNpcs.master_pandemonium)
                runQuestSection(null, dialogue, "Dropping off the cargo on the ledger table", speakers = master)
                true
            }
            else -> false
        }
    }

    companion object {
        const val STARTED = 2
        const val OFFERED = 4
        const val SAILING = 6
        const val REACHED = 8
        const val HOOKED = 12
        const val WASHED_UP = 16
        const val STEVE_RIBS = 18
        const val RIBS_TOLD = 20
        const val STEVE_JIM = 24
        const val CUP_GIVEN = 28
        const val HOLD_BUILT = 30
        const val BACK = 32
        const val LOG_GIVEN = 34
        const val DELIVERED = 38
        const val GROG = 40
        const val GROG_GONE = 48
        const val COMPLETE = 50

        private const val QUEST_XP = 300.0
        private const val DELIVERY_XP = 100.0
        private const val SAWMILL_COUPONS = 25
        private const val REPAIR_KITS = 2

        private const val PORT_SARIM = "port_sarim"
        private const val THE_PANDEMONIUM = "the_pandemonium"

        private val DEFERRED_OPTIONS =
            setOf("Let's see what you have.", "I think I might have lost a cup here.", "Could you tell me about the Pandemonium's one rule?")

        private const val SHOP = "shop"
        private const val CUP = "cup"
        private const val RULE = "rule"

        /** Heading menu: Port Sarim's docks to the wreck east of the Karamja Shipyard (Wiki map). */
        private val START = CoordGrid(3046, 3180, 0)
        private val WRECK = CoordGrid(3031, 3035, 0)
        private const val STEP = 25
        private const val ARRIVE_RADIUS = 15
        private const val GUST_CHANCE = 3
        private val BAY_X = 2990..3075
        private val BAY_Z = 3010..3215

        /** Where you wash up, beside Steve's pub (his spawn is 3050,2966). */
        private val WASH_UP = CoordGrid(3051, 2970, 0)
        private val SHIPYARD = CoordGrid(2084, 2722, 0)
        private const val SHIPYARD_MAX_X = 2200
        private val JIM_DOCK = CoordGrid(3058, 2981, 0)

        val PANDEMONIUM =
            QuestDef(
                name = "Pandemonium",
                varbit = { PanVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "400 Sailing XP",
                        "Access to the Sailing skill",
                        "A raft",
                        "25 sawmill coupons",
                        "2 repair kits and a spyglass",
                    ),
                scrollObj = { PanObjs.spyglass },
            )
    }
}

// Every cache ref Pandemonium uses (re-check after cache updates).

internal object PanNpcs : NpcReferences() {
    /* Talk-to children of the sailing_intro multinpcs (the spawned parents have no ops). */
    val will = find("sailing_intro_will_vis")
    val anne = find("sailing_intro_anne_vis")
    val steve = find("steve_beanie_1op")
    val steve_trade = find("steve_beanie_2ops")
    val ribs = find("sailing_intro_ribs_vis")
    val jim = find("junior_jim_1op")
    val jim_shipwright = find("junior_jim_4ops")
    val grog = find("sailing_intro_grog_vis")
    val master_sarim = find("port_task_master_port_sarim")
    val master_pandemonium = find("port_task_master_pandemonium")
}

internal object PanLocs : LocReferences() {
    val crate_hammers = find("crate_hammers")
    val crate_saws = find("crate_saws")
    val shipyard_gangplank = find("sailing_gangplank_shipyard")
    val schematics = find("sailing_shipyard_schematics")
    val shipyard_portal = find("sailing_shipyard_portal_exit")
}

internal object PanObjs : ObjReferences() {
    val cup = find("sailing_intro_cup")
    val crate = find("sailing_intro_cargo_crate")
    val log_initial = find("sailing_log_initial")
    val log = find("sailing_log")
    val spyglass = find("sailing_charting_spyglass")
    val repair_kit = find("boat_repair_kit")
    val sawmill_coupon = find("sawmill_coupon")
    val hammer = find("hammer")
    val saw = find("poh_saw")
}

internal object PanVarBits : VarBitReferences() {
    val quest = find("sailing_intro")
    val reached_wreck = find("sailing_intro_reached_wreck")
    val received_repairkits = find("sailing_intro_received_repairkits")
}
