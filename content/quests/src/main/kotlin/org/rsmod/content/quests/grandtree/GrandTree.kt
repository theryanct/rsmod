package org.rsmod.content.quests.grandtree

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Grand Tree (OSRS Wiki). Cache quest varp `grandtree`, end state 160. Stages are multiples of
 * 10 (Charlie's multinpc shows only at 40, 50 ... 150; Glough's trapdoor opens at 130):
 * - 10 King Narnode (Grand Tree) gives a bark sample and a translation book; 20 Hazelmere (island
 *   east of Yanille) takes the bark and writes a scroll; 30 the King hears the translation (None,
 *   None, "A man came to me with the King's seal.", "I gave the man Daconia rocks.", "And Daconia
 *   rocks will kill the tree!") and sends you to Glough; 40 Glough says humans will invade (Charlie
 *   appears in the top-floor cell); 50 Charlie blames Glough; 60 the King won't believe it; 70
 *   Glough's journal (his cupboard); 80 confronting Glough gets you jailed, Charlie gives the
 *   shipyard password (Ka-Lu-Min) and the King frees you (90); 100 Captain Errdo's glider crashes
 *   west of the Karamja shipyard; 110 the gate worker's password and the Foreman's questions (or
 *   killing him) give the lumber order; 120 the King still doubts you, Charlie points to Anita (her
 *   key opens Glough's chest: the invasion plans; the King then gives the four twigs); 130 the twigs
 *   on the watchtower pillars (T, U, Z, O) open the trapdoor; 140 Glough's black demon (spawned for
 *   you) is killed; 150 the King in the tunnel sends you after the last Daconia rock (one random
 *   root); 160 complete: 18,400 Attack, 7,900 Agility, 2,150 Magic xp, 5 QP.
 * - Server varp `gt_progress` (65789): bits 0-3 the twigs placed (T, U, Z, O), bit 4 passed the
 *   shipyard gate, bits 8-11 the root holding the Daconia rock (index into [ROOTS] + 1).
 *
 * Simplified: no cutscenes (the King's root tour, the arrest, the demon's entrance), Femi and the
 * front-gate guards aren't involved (the gate stays open), and Errdo's crash is a fade.
 */
class GrandTree
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(GrandTree::class.java, "grand_tree") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "King Narnode Shareen" to GtNpcs.narnode_talk,
            "Charlie" to GtNpcs.charlie,
            "Gnome guard" to GtNpcs.guard,
            "Glough" to GtNpcs.glough,
            "Captain Errdo" to GtNpcs.errdo_crash,
            "Shipyard worker" to GtNpcs.worker,
            "Jogre" to GtNpcs.jogre,
        )
    }

    /** Each player's black demon / tunnel King, by player uid. */
    private val demons = HashMap<Int, Npc>()
    private val tunnelKings = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (king in GtNpcs.kings) {
                register(npcTypes, king, GRAND_TREE, wants = { state < COMPLETE }) { talkKing(it) }
            }
            register(npcTypes, GtNpcs.hazelmere, GRAND_TREE, wants = { state >= STARTED }) {
                talkHazelmere(it)
            }
            register(npcTypes, GtNpcs.glough, GRAND_TREE, wants = { true }) { talkGlough(it) }
            register(npcTypes, GtNpcs.charlie, GRAND_TREE, wants = { true }) { talkCharlie(it) }
            register(npcTypes, GtNpcs.anita, GRAND_TREE, wants = { state == PLANS }) { talkAnita(it) }
            register(npcTypes, GtNpcs.foreman, GRAND_TREE, wants = { state == CRASHED }) {
                talkForeman(it)
            }
            for (errdo in GtNpcs.errdos) {
                register(npcTypes, errdo, GRAND_TREE, wants = { state == FREED }) { flee(it) }
            }
            register(npcTypes, GtNpcs.errdo_crash, GRAND_TREE, wants = { state >= CRASHED }) {
                runQuestSection(it, dialogue, "Talking to Captain Errdo again", speakers = speakers)
            }
        }
        onLocOpLabel(locTypes, GtLocs.cupboard, "Open") { searchCupboard() }
        onLocOpLabel(locTypes, GtLocs.cupboard_open, "Search") { searchCupboard() }
        onLocOpLabel(locTypes, GtLocs.chest, "Open") { openChest() }
        onLocOpLabel(locTypes, GtLocs.prison_door, "Open") { prisonDoor(it) }
        for (gate in GtLocs.shipyard_gates) onLocOpLabel(locTypes, gate, "Open") { shipyardGate(it) }
        onLocOpLabel(locTypes, GtLocs.climb_tree, "Climb-up") { telejump(player.coords.translateLevel(1)) }
        onLocOpLabel(locTypes, GtLocs.down_tree, "Climb-down") {
            telejump(player.coords.translateLevel(-1))
        }
        onLocOpLabel(locTypes, GtLocs.tower_trapdoor, "Climb-down") { enterTunnel() }
        onLocOpLabel(locTypes, GtLocs.tunnel_ladder, "Climb-up") { telejump(TOWER_TOP) }
        for (root in GtLocs.roots) onLocOpLabel(locTypes, root, "Search") { searchRoot(it) }
        for ((index, pillar) in GtLocs.pillars.withIndex()) {
            for ((twigIndex, twig) in GtObjs.twigs.withIndex()) {
                onOpLocU(pillar, twig) { placeTwig(index, twigIndex) }
            }
        }
        onEvent<NpcKilledEvent> { killed(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(GRAND_TREE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(GRAND_TREE, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[GtVarps.progress]

    private fun ProtectedAccess.setFlags(value: Int) {
        VarPlayerIntMapSetter.set(player, GtVarps.progress, value)
    }

    private fun ProtectedAccess.hasFlag(bit: Int): Boolean = (flags() and (1 shl bit)) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        setFlags(flags() or (1 shl bit))
    }

    private fun ProtectedAccess.giveIfMissing(obj: ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    /* King Narnode Shareen */

    private suspend fun ProtectedAccess.talkKing(npc: Npc) {
        when (state) {
            0 -> {
                runQuestSection(npc, dialogue, "The Dying Tree")
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player is below level 50 combat:" to { false }),
                        options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                        boxes =
                            mapOf(
                                "The king hands you the bark sample and the translation book." to {
                                    giveIfMissing(GtObjs.bark)
                                    giveIfMissing(GtObjs.book)
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Foundations cutscene", hooks)
            }
            STARTED -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has lost the bark sample:" to { !carries(GtObjs.bark) },
                                "If the player has lost the translation book:" to {
                                    !carries(GtObjs.book)
                                },
                            ),
                        boxes =
                            mapOf(
                                "The King gives you another bark sample." to {
                                    giveIfMissing(GtObjs.bark)
                                    false
                                },
                                "The King gives you another translation book." to {
                                    giveIfMissing(GtObjs.book)
                                    false
                                },
                            ),
                    )
                runQuestSection(npc, dialogue, "When speaking to King Narnode Shareen again", hooks)
            }
            HAZELMERE -> translation(npc)
            TRANSLATED ->
                runQuestSection(npc, dialogue, "When speaking to King Narnode Shareen again (2)")
            GLOUGH -> runQuestSection(npc, dialogue, "Reporting to the King")
            CHARLIE -> {
                runQuestSection(npc, dialogue, "Reporting back to King Narnode")
                setState(REPORTED)
            }
            REPORTED, JOURNAL -> runQuestSection(npc, dialogue, "When talking to King Narnode again")
            ARRESTED, FREED -> runQuestSection(npc, dialogue, "When speaking to King Narnode again")
            ORDER -> {
                runQuestSection(npc, dialogue, "King Narnode")
                setState(PLANS)
            }
            PLANS -> kingPlans(npc)
            TRAPDOOR -> runQuestSection(npc, dialogue, "When talking to King Narnode without finishing the fight")
            DEMON -> {
                runQuestSection(npc, dialogue, "The End of the Tunnel", speakers = speakers)
                val root = random.of(0, ROOTS.size - 1)
                setFlags((flags() and ROOT_MASK.inv()) or ((root + 1) shl ROOT_SHIFT))
                setState(ROCK)
            }
            ROCK ->
                if (carries(GtObjs.daconia)) {
                    finish(npc)
                } else {
                    runQuestSection(npc, dialogue, "When talking to King Narnode again (2)")
                }
            else -> runQuestSection(npc, dialogue, "When speaking to King Narnode")
        }
    }

    private suspend fun ProtectedAccess.translation(npc: Npc) {
        val picks = ArrayList<String>()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player chose the correct options:" to { picks == CORRECT_TRANSLATION },
                        "If the player chose the wrong options:" to { picks != CORRECT_TRANSLATION },
                    ),
                defaultCondition = { false },
                options = TRANSLATION_OPTIONS.associateWith<String, suspend () -> Unit> { label -> { picks += label } },
                actions = mapOf("" to { giveIfMissing(GtObjs.bark) }),
            )
        runQuestSection(npc, dialogue, "Bring Back the News", hooks)
        if (picks == CORRECT_TRANSLATION) setState(TRANSLATED)
    }

    private suspend fun ProtectedAccess.kingPlans(npc: Npc) {
        when {
            carries(GtObjs.plans) -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "You give the King the invasion plans." to {
                                    invDel(inv, GtObjs.plans)
                                    false
                                },
                                "The King has given you some twigs lashed together." to {
                                    giveTwigs()
                                    false
                                },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to King Narnode Shareen", hooks)
            }
            hasTwigsOrPlaced() -> {
                val missing = GtObjs.twigs.withIndex().any { (i, twig) -> !hasFlag(i) && !carries(twig) }
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has lost the twigs:" to { missing },
                                "If the player doesn't have enough inventory space:" to {
                                    inv.freeSpace() < GtObjs.twigs.size
                                },
                                "If the player has enough inventory space:" to {
                                    inv.freeSpace() >= GtObjs.twigs.size
                                },
                            ),
                        boxes =
                            mapOf(
                                "The King has given you some twigs lashed together." to {
                                    giveTwigs()
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "When speaking to King Narnode again (2)", hooks)
            }
            else -> runQuestSection(npc, dialogue, "When speaking to King Narnode")
        }
    }

    private fun ProtectedAccess.hasTwigsOrPlaced(): Boolean =
        hasFlag(TWIGS_GIVEN) || GtObjs.twigs.any { carries(it) }

    private fun ProtectedAccess.giveTwigs() {
        setFlag(TWIGS_GIVEN)
        for ((i, twig) in GtObjs.twigs.withIndex()) {
            if (!hasFlag(i)) giveIfMissing(twig)
        }
    }

    private suspend fun ProtectedAccess.finish(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "You give the King the Daconia rock." to {
                            invDel(inv, GtObjs.daconia)
                            false
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Daconia Delivered", hooks)
        statAdvance(stats.attack, 18400.0 * xpMods.get(player, stats.attack))
        statAdvance(stats.agility, 7900.0 * xpMods.get(player, stats.agility))
        statAdvance(stats.magic, 2150.0 * xpMods.get(player, stats.magic))
        completeQuest(GRAND_TREE)
        tunnelKings.remove(player.uid.packed)?.let { npcRepo.del(it, 1) }
    }

    /* Hazelmere */

    private suspend fun ProtectedAccess.talkHazelmere(npc: Npc) {
        if (state == STARTED && carries(GtObjs.bark)) {
            val hooks =
                SimpleQuestHooks(
                    boxes =
                        mapOf(
                            "You give the bark sample to Hazelmere." to {
                                invDel(inv, GtObjs.bark)
                                false
                            },
                            "Hazelmere has given you the scroll." to {
                                giveIfMissing(GtObjs.scroll)
                                false
                            },
                        )
                )
            runQuestSection(npc, dialogue, "Hazelmere's Island", hooks)
            setState(HAZELMERE)
            return
        }
        runQuestSection(npc, dialogue, "When speaking again to Hazelmere")
        if (state in HAZELMERE until TRANSLATED) giveIfMissing(GtObjs.scroll)
    }

    /* Glough */

    private suspend fun ProtectedAccess.talkGlough(npc: Npc) {
        when {
            state == TRANSLATED -> {
                runQuestSection(npc, dialogue, "Glough")
                setState(GLOUGH)
            }
            state == JOURNAL -> arrest(npc)
            state in CRASHED until PLANS -> runQuestSection(npc, dialogue, "When speaking to Glough")
            state == PLANS -> runQuestSection(npc, dialogue, "When speaking to Glough (2)")
            state >= COMPLETE -> runQuestSection(npc, dialogue, "Glough (2)")
            else ->
                startDialogue(npc) {
                    // Authored line (not Wiki): Glough between the quest's steps.
                    chatNpc(neutral, "Can't you see I'm eating, human? Go away!")
                }
        }
    }

    private suspend fun ProtectedAccess.arrest(npc: Npc) {
        runQuestSection(npc, dialogue, "Suspicions", speakers = speakers)
        setState(ARRESTED)
        telejump(CELL)
        runQuestSection(null, dialogue, "Imprisoned", speakers = speakers)
        setState(FREED)
    }

    private suspend fun ProtectedAccess.prisonDoor(door: BoundLocInfo) {
        if (state == ARRESTED) {
            mes("The door is locked.")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        if (state == REPORTED && !carries(GtObjs.journal)) {
            runQuestSection(null, dialogue, "Searching Glough's cupboard")
            invAddOrDrop(objRepo, GtObjs.journal)
            setState(JOURNAL)
            return
        }
        mes("You search the cupboard but find nothing of interest.")
    }

    private suspend fun ProtectedAccess.openChest() {
        if (state != PLANS || !carries(GtObjs.key)) {
            mes("The chest is locked.")
            return
        }
        if (carries(GtObjs.plans) || hasTwigsOrPlaced()) {
            mes("The chest is empty.")
            return
        }
        invDel(inv, GtObjs.key)
        runQuestSection(null, dialogue, "Searching Glough's chest")
        invAddOrDrop(objRepo, GtObjs.plans)
    }

    /* Charlie and Anita */

    private suspend fun ProtectedAccess.talkCharlie(npc: Npc) {
        when (state) {
            GLOUGH -> {
                runQuestSection(npc, dialogue, "Interrogating the Prisoner")
                setState(CHARLIE)
            }
            ARRESTED, FREED -> runQuestSection(npc, dialogue, "When speaking to Charlie again")
            PLANS ->
                if (carries(GtObjs.plans)) runQuestSection(npc, dialogue, "Talking to Charlie")
                else runQuestSection(npc, dialogue, "Charlie")
            else -> runQuestSection(npc, dialogue, "When talking to Charlie again")
        }
    }

    private suspend fun ProtectedAccess.talkAnita(npc: Npc) {
        if (carries(GtObjs.key) || carries(GtObjs.plans) || hasTwigsOrPlaced()) {
            runQuestSection(npc, dialogue, "Talking to Anita again")
            return
        }
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "Anita gives you a key." to {
                            giveIfMissing(GtObjs.key)
                            false
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Anita", hooks)
    }

    /* The glider and the shipyard */

    private suspend fun ProtectedAccess.flee(npc: Npc) {
        var go = false
        val hooks = SimpleQuestHooks(options = mapOf("Take me to Karamja please!" to { go = true }))
        runQuestSection(npc, dialogue, "Fleeing the Grand Tree", hooks, speakers)
        if (!go) return
        // Authored line (not Wiki): the crash itself is a cutscene in OSRS.
        mes("The glider shudders violently over the Karamja Volcano...")
        delay(2)
        telejump(CRASH_SITE)
        setState(CRASHED)
        val errdo = Npc(npcTypes[GtNpcs.errdo_crash], CRASH_SITE.translate(1, 1))
        npcRepo.add(errdo, ERRDO_TICKS)
        runQuestSection(errdo, dialogue, "Landing (crashing) the glider", speakers = speakers)
    }

    private suspend fun ProtectedAccess.shipyardGate(gate: BoundLocInfo) {
        val inside = player.coords.x > gate.coords.x
        if (inside || hasFlag(GATE_PASSED) || state > CRASHED) {
            passThroughWall(gate)
            return
        }
        if (state != CRASHED) {
            mes("The gate is locked from the other side.")
            return
        }
        val picks = ArrayList<String>()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player chose Ka-Lu-Min:" to { picks == PASSWORD },
                        "If the player chose any other options:" to { picks != PASSWORD },
                    ),
                options = PASSWORD_OPTIONS.associateWith<String, suspend () -> Unit> { label -> { picks += label } },
            )
        runQuestSection(null, dialogue, "Password", hooks, speakers)
        if (picks == PASSWORD) {
            setFlag(GATE_PASSED)
            passThroughWall(gate)
        }
    }

    private suspend fun ProtectedAccess.talkForeman(npc: Npc) {
        if (carries(GtObjs.order)) {
            runQuestSection(npc, dialogue, "Talking to Foreman after obtaining the lumber order")
            return
        }
        var attack = false
        var order = false
        val hooks =
            SimpleQuestHooks(
                actions =
                    mapOf(
                        "The player is attacked by the Foreman." to { attack = true },
                        "The foreman has given you the lumber order." to { order = true },
                    )
            )
        runQuestSection(npc, dialogue, "Foreman", hooks)
        when {
            order -> {
                giveIfMissing(GtObjs.order)
                setState(ORDER)
            }
            attack -> npc.opPlayer2(player, aiInteractions)
        }
    }

    /* The watchtower, the tunnel and the demon */

    private suspend fun ProtectedAccess.placeTwig(pillar: Int, twig: Int) {
        if (state != PLANS) return
        if (pillar != twig) {
            // Authored line (not Wiki): a twig on the wrong pillar.
            mes("That twig doesn't seem to fit this pillar.")
            return
        }
        if (hasFlag(pillar)) return
        invDel(inv, GtObjs.twigs[twig])
        setFlag(pillar)
        if ((0 until GtObjs.twigs.size).all { hasFlag(it) }) {
            runQuestSection(null, dialogue, "Using the correct combination")
            setState(TRAPDOOR)
        } else {
            mes("You place the twig in the pillar.")
        }
    }

    private suspend fun ProtectedAccess.enterTunnel() {
        telejump(TUNNEL_ARRIVAL)
        if (state != TRAPDOOR) return
        val current = demons[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        val glough = Npc(npcTypes[GtNpcs.glough_battle], GLOUGH_SPOT)
        npcRepo.add(glough, DEMON_TICKS)
        runQuestSection(glough, dialogue, "Glough's Pet", speakers = speakers)
        val demon = Npc(npcTypes[GtNpcs.demon], DEMON_SPOT)
        npcRepo.add(demon, DEMON_TICKS)
        demons[player.uid.packed] = demon
        demon.opPlayer2(player, aiInteractions)
    }

    private fun killed(event: NpcKilledEvent) {
        if (event.npc.type.id == GtNpcs.foreman.id) {
            val player = event.hero
            if (player.questState(GRAND_TREE) == CRASHED) {
                objRepo.add(Obj.fromOwner(player, event.npc.coords, GtObjs.order, 1), DROP_TICKS)
                player.setQuestState(GRAND_TREE, ORDER)
            }
            return
        }
        if (event.npc.type.id != GtNpcs.demon.id) return
        val player = event.hero
        demons.remove(player.uid.packed)
        if (player.questState(GRAND_TREE) != TRAPDOOR) return
        player.setQuestState(GRAND_TREE, DEMON)
        player.mes("Glough: Mummy!")
        spawnTunnelKing(player)
    }

    private fun spawnTunnelKing(player: Player) {
        val current = tunnelKings[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        val king = Npc(npcTypes[GtNpcs.narnode_talk], TUNNEL_KING)
        npcRepo.add(king, KING_TICKS)
        tunnelKings[player.uid.packed] = king
    }

    private suspend fun ProtectedAccess.searchRoot(root: BoundLocInfo) {
        val chosen = (flags() and ROOT_MASK) shr ROOT_SHIFT
        val index = ROOTS.indexOfFirst { it.chebyshevDistance(root.coords) <= 1 }
        if (state == ROCK && chosen > 0 && index == chosen - 1 && !carries(GtObjs.daconia)) {
            runQuestSection(null, dialogue, "Searching a root with the Daconia rock")
            invAddOrDrop(objRepo, GtObjs.daconia)
            return
        }
        // Authored line (not Wiki): the Wiki's "no Daconia rocks" section is empty.
        mes("You search the root but find nothing.")
    }

    companion object {
        const val STARTED = 10
        const val HAZELMERE = 20
        const val TRANSLATED = 30
        const val GLOUGH = 40
        const val CHARLIE = 50
        const val REPORTED = 60
        const val JOURNAL = 70
        const val ARRESTED = 80
        const val FREED = 90
        const val CRASHED = 100
        const val ORDER = 110
        const val PLANS = 120
        const val TRAPDOOR = 130
        const val DEMON = 140
        const val ROCK = 150
        const val COMPLETE = 160

        private const val GATE_PASSED = 4
        private const val TWIGS_GIVEN = 5
        private const val ROOT_SHIFT = 8
        private const val ROOT_MASK = 0xF00
        private const val ERRDO_TICKS = 300
        private const val DEMON_TICKS = 1000
        private const val KING_TICKS = 3000
        private const val DROP_TICKS = 200

        private val CELL = CoordGrid(2463, 3496, 3)
        private val CRASH_SITE = CoordGrid(2917, 3053, 0)
        private val TOWER_TOP = CoordGrid(2486, 3464, 2)
        private val TUNNEL_ARRIVAL = CoordGrid(2491, 9864, 0)
        private val GLOUGH_SPOT = CoordGrid(2484, 9864, 0)
        private val DEMON_SPOT = CoordGrid(2482, 9864, 0)
        private val TUNNEL_KING = CoordGrid(2464, 9897, 0)

        /** The tunnel's searchable roots (largeroot_gnome / largeroot2_gnome). */
        private val ROOTS =
            listOf(
                CoordGrid(2439, 9881, 0),
                CoordGrid(2444, 9893, 0),
                CoordGrid(2455, 9874, 0),
                CoordGrid(2457, 9881, 0),
                CoordGrid(2465, 9891, 0),
                CoordGrid(2467, 9872, 0),
                CoordGrid(2443, 9878, 0),
                CoordGrid(2452, 9893, 0),
                CoordGrid(2456, 9886, 0),
                CoordGrid(2467, 9896, 0),
                CoordGrid(2473, 9897, 0),
                CoordGrid(2485, 9885, 0),
            )

        private val TRANSLATION_OPTIONS =
            listOf(
                "King Narnode must be stopped, he is a madman!",
                "Praise be to the great Zamorak!",
                "Do you have any bread? I do like bread.",
                "The time has come to attack!",
                "None of the above.",
                "The tree is fine, you have nothing to fear.",
                "You must come and see me!",
                "The tree needs watering as there has been drought.",
                "Grave danger lies ahead, only the bravest will linger.",
                "Time is of the essence! We must move quickly.",
                "A man came to me with the King's seal.",
                "There is no need for haste, just send a runner.",
                "You must act now, or we will all die!",
                "Time passes us by.",
                "I gave the man Daconia rocks.",
                "You must use force!",
                "Use a bucket of milk from a scared cow.",
                "Take this banana to him, he will understand.",
                "Only Adamantite is any use.",
                "All with be fine on the third night.",
                "You must wait till the second night.",
                "Nothing will help us now!",
                "The tree will die in five days!",
                "And Daconia rocks will kill the tree!",
            )
        private val CORRECT_TRANSLATION =
            listOf(
                "None of the above.",
                "None of the above.",
                "A man came to me with the King's seal.",
                "I gave the man Daconia rocks.",
                "And Daconia rocks will kill the tree!",
            )
        private val PASSWORD_OPTIONS = listOf("Ka.", "Ko.", "Ke.", "Lo.", "Lu.", "Le.", "Mon.", "Min.", "Men.")
        private val PASSWORD = listOf("Ka.", "Lu.", "Min.")

        val GRAND_TREE =
            QuestDef(
                name = "The Grand Tree",
                varp = { GtVarps.quest },
                complete = COMPLETE,
                questPoints = 5,
                rewards =
                    listOf(
                        "18,400 Attack XP",
                        "7,900 Agility XP",
                        "2,150 Magic XP",
                        "Gnome gliders and the spirit tree",
                    ),
                scrollObj = { GtObjs.daconia },
                listRow = "quest_grandtree",
            )
    }
}

// Every cache ref The Grand Tree uses (re-check after cache updates).

internal object GtNpcs : NpcReferences() {
    val narnode_talk = find("grandtree_narnode_1op")
    val kings = listOf(find("grandtree_narnode_1op"), find("grandtree_narnode_2ops"))
    val hazelmere = find("grandtree_hazelmere")
    val glough = find("grandtree_glough_visible")
    val glough_battle = find("grandtree_glough_battle")
    val charlie = find("grandtree_charlie")
    val anita = find("gt_anita")
    val foreman = find("grandtree_foreman")
    val worker = find("shipyardworker1")
    val guard = find("grandtree_spawnedguard")
    val demon = find("grandtree_blackdemon")
    val jogre = find("jogre")
    val errdo_crash = find("pilot_digsite")
    val errdos =
        listOf(
            "pilot_grand_tree_base",
            "pilot_grand_tree_karamja",
            "pilot_grand_tree_al_kharid",
            "pilot_grand_tree_varrock",
            "pilot_grand_tree_whitewolf",
            "pilot_grand_tree_ogre",
            "pilot_grand_tree_ape",
        ).map { find(it) }
}

internal object GtLocs : LocReferences() {
    val cupboard = find("grandtree_cupboardclosed")
    val cupboard_open = find("grandtree_cupboardopen")
    val chest = find("grandtree_chestclosed")
    val prison_door = find("grandtree_prisondoor")
    val shipyard_gates = listOf(find("grandtree_fencegate_l"), find("grandtree_fencegate_r"))
    val climb_tree = find("grandtree_climbtree")
    val down_tree = find("grandtree_downtree")
    val tower_trapdoor = find("grandtree_trapdoortoweropen")
    val tunnel_ladder = find("ladder_broken")
    val roots: List<LocType> = listOf(find("largeroot_gnome"), find("largeroot2_gnome"))

    /** The watchtower pillars, in twig order (T, U, Z, O). */
    val pillars =
        listOf(
            find("grandtree_pillart"),
            find("grandtree_pillaru"),
            find("grandtree_pillarz"),
            find("grandtree_pillaro"),
        )
}

internal object GtObjs : ObjReferences() {
    val bark = find("grandtree_barksample")
    val book = find("grandtree_translationbook")
    val scroll = find("grandtree_scroll")
    val journal = find("grandtree_journal")
    val order = find("grandtree_order")
    val key = find("grandtree_gloughskey")
    val plans = find("grandtree_invasionplans")
    val daconia = find("grandtree_daconiarock")

    /** T, U, Z, O (the pillars' order). */
    val twigs =
        listOf(
            find("grandtree_twigt"),
            find("grandtree_twigu"),
            find("grandtree_twigz"),
            find("grandtree_twigo"),
        )
}

internal object GtVarps : VarpReferences() {
    val quest = find("grandtree")

    /** Server varp (65789): see [GrandTree]. */
    val progress = find("gt_progress")
}

internal object GtVarpBuilds : VarpBuilder() {
    init {
        build("gt_progress")
    }
}
