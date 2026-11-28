package org.rsmod.content.quests.naturespirit

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
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
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.content.quests.priestinperil.PipNpcs
import org.rsmod.content.quests.restlessghost.RestlessGhost.Companion.RESTLESS_GHOST
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Nature Spirit (OSRS Wiki). Cache quest varp `druidspirit`, end state 110:
 * - 5 Drezel's pies for Filliman Tarlock in Mort Myre (through Ulizius's gate, over the broken
 *   bridge to the grotto island); 10 his ghost won't believe he's dead; 15 the mirror under the
 *   washing bowl convinces him; 20 his journal (in the grotto tree) reminds him of his plan, and
 *   "How can I help?" gets the druidic spell (25); 30 Drezel blesses you; 40 a bloomed fungus
 *   shown to Filliman (the puzzle: the fungus on the brown nature stone, the used spell on the
 *   grey spirit stone, and you on the orange faith stone); 50 the grove opens; 60 searching the
 *   grotto turns him into a nature spirit; 70 he blesses your silver sickle and gives a druid
 *   pouch; 75 / 80 / 90 one, two, three ghasts killed; 110 complete: 3,000 Crafting, 2,000
 *   Defence and 2,000 Hitpoints xp, 2 QP, and Bloom with the blessed sickle.
 * - Bloom (the spell, or the blessed sickle's Cast Bloom; 1-6 Prayer) makes adjacent rotting logs,
 *   branches and bushes bloom into fungi, budding branches and golden pears for 100 ticks. Filling
 *   the druid pouch with them gives charges; using it on an invisible ghast makes it visible.
 * - Server varp `ns_progress` (65787): bit 0 fungus on the brown stone, bit 1 spell on the grey.
 *
 * Swamp decay and the invisible ghasts' attacks are in [MortMyreHazards]. Not here: the cosmetic
 * Filliman apparition animation. The Altar of nature's Pray-at is the Prayer module's.
 */
class NatureSpirit
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(NatureSpirit::class.java, "nature_spirit") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Drezel" to PipNpcs.drezel,
            "Filliman Tarlock" to NsNpcs.filliman,
            "Nature Spirit" to NsNpcs.spirit,
            "Ulizius" to NsNpcs.ulizius,
        )
    }

    /** Each player's summoned Filliman apparition (outside the grotto). */
    private val apparitions = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, PipNpcs.drezel, NATURE_SPIRIT, wants = { wantsDrezel() }) {
                talkDrezel(it)
            }
        }
        onNpcOpLabel(npcTypes, NsNpcs.ulizius, "Talk-to") {
            runQuestSection(it, dialogue, "Talking to Ulizius", SimpleQuestHooks(), speakers)
        }
        for (gate in NsLocs.gates) onLocOpLabel(locTypes, gate, "Open") { swampGate(it) }
        onLocOpLabel(locTypes, NsLocs.bridge, "Jump") { jumpBridge(it) }

        onLocOpLabel(locTypes, NsLocs.grotto_entrance, "Enter") { enterGrotto() }
        onLocOpLabel(locTypes, NsLocs.grotto_exit, "Exit") { telejump(OUTSIDE_GROTTO) }
        onLocOpLabel(locTypes, NsLocs.grotto_exit_green, "Exit") { telejump(OUTSIDE_GROTTO) }
        onLocOpLabel(locTypes, NsLocs.grotto_tree, "Search") { searchTree() }
        onLocOpLabel(locTypes, NsLocs.grotto_tree, "Look-at") {
            mesbox("A large tree thriving where trees normally die.")
        }
        onLocOpLabel(locTypes, NsLocs.grotto, "Search") { searchGrotto() }
        onOpLocU(NsLocs.grotto, NsObjs.sickle) { refreshSickle() }

        onLocOpLabel(locTypes, NsLocs.stone_nature, "Search") { searchStone(STONE_NATURE) }
        onLocOpLabel(locTypes, NsLocs.stone_faith, "Search") { searchStone(STONE_FAITH) }
        onLocOpLabel(locTypes, NsLocs.stone_spirit, "Search") { searchStone(STONE_SPIRIT) }
        onOpLocU(NsLocs.stone_nature, NsObjs.fungus) { placeFungus() }
        onOpLocU(NsLocs.stone_spirit, NsObjs.used_spell) { placeSpell() }
        onOpLocU(NsLocs.stone_spirit, NsObjs.mirror) {
            mes("You place the mirror on the stone... nothing happens. You take it back.")
        }

        onNpcOpLabel(npcTypes, NsNpcs.filliman, "Talk-to") { talkFilliman(it) }
        onOpNpcU(NsNpcs.filliman, NsObjs.mirror) { useMirror(it.npc) }
        onOpNpcU(NsNpcs.filliman, NsObjs.journal) { useJournal(it.npc) }
        with(QuestTalk) { default(npcTypes, NsNpcs.spirit) { talkSpirit(it) } }

        onOpHeld1(NsObjs.bloom_spell) { castBloom(fromSickle = false) }
        onOpHeld1(NsObjs.sickle_blessed) { castBloom(fromSickle = true) }
        onLocOpLabel(locTypes, NsLocs.log_bloomed, "Pick") { harvest(it, NsLocs.log, NsObjs.fungus) }
        onLocOpLabel(locTypes, NsLocs.branch_bloomed, "Take-cutting") {
            harvest(it, NsLocs.branch, NsObjs.stem)
        }
        onLocOpLabel(locTypes, NsLocs.bush_bloomed, "Pick") { harvest(it, NsLocs.bush, NsObjs.pear) }

        onOpHeld4(NsObjs.pouch_empty) { fillPouch() }
        onOpHeld4(NsObjs.pouch) { fillPouch() }
        onOpNpcU(NsNpcs.ghast_invis, NsObjs.pouch) { revealGhast(it.npc) }
        onEvent<NpcKilledEvent> { ghastKilled(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(NATURE_SPIRIT)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(NATURE_SPIRIT, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[NsVarps.progress]

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, NsVarps.progress, flags() or bit)
    }

    private fun ProtectedAccess.wearingGhostspeak(): Boolean =
        invTotal(worn, NsObjs.ghostspeak) > 0 || invTotal(worn, NsObjs.ghostspeak_e) > 0

    private fun ProtectedAccess.meetsRequirements(): Boolean =
        player.isQuestComplete(PRIEST_IN_PERIL) && player.isQuestComplete(RESTLESS_GHOST)

    private fun ProtectedAccess.ghostHooks(
        conditions: Map<String, () -> Boolean> = emptyMap(),
        options: Map<String, suspend () -> Unit> = emptyMap(),
    ): SimpleQuestHooks {
        val wearing = wearingGhostspeak()
        return SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player is not wearing the Ghostspeak amulet:" to { !wearing },
                    "If the player is wearing the Ghostspeak amulet:" to { wearing },
                    "If the player is not wearing their ghostspeak amulet:" to { !wearing },
                    "If the player talks to him again without wearing a ghostspeak amulet:" to {
                        !wearing
                    },
                    "If the player talks to him again after speaking to him with a ghostspeak amulet:" to {
                        wearing
                    },
                ) + conditions,
            options = options,
        )
    }

    /* Drezel (Priest in Peril's; QuestTalk) */

    private fun ProtectedAccess.wantsDrezel(): Boolean =
        player.coords.z > UNDERGROUND_Z &&
            player.isQuestComplete(PRIEST_IN_PERIL) &&
            state < COMPLETE

    private suspend fun ProtectedAccess.talkDrezel(npc: Npc) {
        when {
            state == 0 -> {
                var start = false
                val reqs = meetsRequirements()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not meet the requirements for Nature Spirit:" to {
                                    !reqs
                                },
                                "If the player meets the requirements for Nature Spirit:" to { reqs },
                            ),
                        options = mapOf("Yes." to { start = true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Drezel", hooks, speakers)
                if (start && reqs) {
                    invAddOrDrop(objRepo, NsObjs.meat_pie, PIES)
                    invAddOrDrop(objRepo, NsObjs.apple_pie, PIES)
                    setState(STARTED)
                }
            }
            state < HELP -> run(npc, "Talking to Drezel again")
            state == HELP -> {
                runQuestSection(npc, dialogue, "Talking to Drezel (2)", SimpleQuestHooks(), speakers)
                setState(BLESSED)
            }
            state == BLESSED -> run(npc, "Subsequent dialogue with Drezel")
            state < TRANSFORMED -> run(npc, "Talking to Drezel after showing Filliman the fungus")
            state < SICKLE -> run(npc, "Talking to Drezel (3)")
            else -> {
                val killed = ghastsKilled()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has killed 1 ghast:" to { killed <= 1 },
                                "If the player has killed 2 ghasts:" to { killed == 2 },
                                "If the player has killed 3 ghasts:" to { killed >= 3 },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Drezel (4)", hooks, speakers)
            }
        }
    }

    private suspend fun ProtectedAccess.run(npc: Npc?, section: String) {
        runQuestSection(npc, dialogue, section, ghostHooks(), speakers)
    }

    private fun ProtectedAccess.ghastsKilled(): Int =
        when {
            state >= KILLED_ALL -> 3
            state >= KILLED_2 -> 2
            state >= KILLED_1 -> 1
            else -> 0
        }

    /* Getting there */

    private suspend fun ProtectedAccess.swampGate(gate: BoundLocInfo) {
        val entering = player.coords.z >= gate.coords.z
        if (entering) {
            val enter =
                choice2(
                    "Enter the swamp.",
                    true,
                    "Stay out",
                    false,
                    title = "Warning! Mort Myre is a dangerous ghast-infested swamp.",
                )
            if (!enter) return
        } else {
            SwampDecay.cancel(player)
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.jumpBridge(bridge: BoundLocInfo) {
        val dest = if (player.coords.z < bridge.coords.z) ISLAND_SIDE else SWAMP_SIDE
        anim(NsSeqs.jump)
        delay(1)
        if (random.of(1, BRIDGE_FAIL_ODDS) == 1) {
            mes("You slip and fall into the swamp!")
            player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(2, 7))
            return
        }
        telejump(dest)
    }

    /* Filliman's island */

    private suspend fun ProtectedAccess.enterGrotto() {
        if (state >= GROVE) {
            telejump(if (state >= COMPLETE) GROTTO_ALTAR else GROTTO_INSIDE)
            return
        }
        if (state < STARTED) {
            mes("The grotto is too dark to enter.")
            return
        }
        summonFilliman(player)
        val hooks = ghostHooks()
        if (state < MET) {
            runQuestSection(apparitions[player.uid.packed], dialogue, "Talking to Filliman", hooks, speakers)
            if (wearingGhostspeak()) setState(MET)
            return
        }
        talkFilliman(apparitions[player.uid.packed] ?: return)
    }

    private fun summonFilliman(player: Player) {
        val existing = apparitions[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) return
        val npc = Npc(npcTypes[NsNpcs.filliman], FILLIMAN_SPOT)
        npcRepo.add(npc, APPARITION_TICKS)
        apparitions[player.uid.packed] = npc
    }

    private suspend fun ProtectedAccess.searchTree() {
        if (state < MIRROR || carries(NsObjs.journal) || state >= JOURNAL) {
            mesbox("You search the strange rock but find nothing of interest.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have room to take anything.")
            return
        }
        invAdd(inv, NsObjs.journal)
        mesbox(
            "You search the strange rock. You find a knot and inside of it you discover a small " +
                "tome. The words on the front are faded, but you can make out that it is a journal."
        )
    }

    private suspend fun ProtectedAccess.searchStone(stone: Int) {
        val solved = state >= GROVE
        val text =
            when (stone) {
                STONE_NATURE -> "some sort of nature symbol"
                STONE_FAITH -> "some sort of faith symbol"
                else -> "some sort of spirit symbol"
            }
        val tail = if (solved) " This stone seems to be complete somehow." else ""
        mesbox("You search the stone and find that it has $text scratched into it.$tail")
    }

    private suspend fun ProtectedAccess.placeFungus() {
        if (state < PUZZLE || flags() and FUNGUS_PLACED != 0) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, NsObjs.fungus)
        setFlag(FUNGUS_PLACED)
        mesbox("The fungus seems to melt into the stone, which takes on a deeper brown colour.")
    }

    private suspend fun ProtectedAccess.placeSpell() {
        if (state < PUZZLE || flags() and SPELL_PLACED != 0) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, NsObjs.used_spell)
        setFlag(SPELL_PLACED)
        mesbox("The used spell seems to melt into the stone, which takes on a soft grey glow.")
    }

    /* Filliman Tarlock */

    private suspend fun ProtectedAccess.talkFilliman(npc: Npc) {
        when {
            state < MET -> runQuestSection(npc, dialogue, "Talking to Filliman", ghostHooks(), speakers)
            state == MET -> run(npc, "Subsequent dialogue with Filliman")
            state == MIRROR -> run(npc, "Talking to Filliman again after using a mirror on him")
            state < BLESSED -> {
                val hooks =
                    ghostHooks(
                        conditions =
                            mapOf(
                                "If the player did not go offer their help yet:" to { state < HELP },
                                "If the player offered their help:" to { state >= HELP },
                            )
                    )
                runQuestSection(npc, dialogue, "Subsequent dialogue with Filliman (2)", hooks, speakers)
            }
            state == BLESSED -> showFungus(npc)
            state == PUZZLE -> puzzleTalk(npc)
            else -> run(npc, "Talking to Filliman outside the grove again")
        }
    }

    private suspend fun ProtectedAccess.useMirror(npc: Npc) {
        when {
            state != MET -> run(npc, "Using a mirror on Filliman again")
            !wearingGhostspeak() -> run(npc, "Subsequent dialogue with Filliman")
            else -> {
                val hooks =
                    ghostHooks(
                        conditions =
                            mapOf(
                                "If the player uses the mirror on Filliman whilst wearing the ghostspeak amulet:" to {
                                    true
                                }
                            )
                    )
                runQuestSection(npc, dialogue, "Subsequent dialogue with Filliman", hooks, speakers)
                invDel(inv, NsObjs.mirror)
                setState(MIRROR)
            }
        }
    }

    private suspend fun ProtectedAccess.useJournal(npc: Npc) {
        if (state < MIRROR) {
            run(npc, "Using the journal on Filliman before using the mirror")
            return
        }
        if (state >= JOURNAL || !wearingGhostspeak()) {
            run(npc, "Subsequent dialogue with Filliman (2)")
            return
        }
        var help = false
        val hooks =
            ghostHooks(
                conditions =
                    mapOf(
                        "If the player uses his journal on Filliman whilst wearing the ghostspeak amulet:" to {
                            true
                        }
                    ),
                options = mapOf("How can I help?" to { help = true }),
            )
        runQuestSection(npc, dialogue, "Subsequent dialogue with Filliman", hooks, speakers)
        invDel(inv, NsObjs.journal)
        setState(JOURNAL)
        if (help) {
            invAddOrDrop(objRepo, NsObjs.bloom_spell)
            setState(HELP)
        }
    }

    private suspend fun ProtectedAccess.showFungus(npc: Npc) {
        val fungus = carries(NsObjs.fungus)
        var anotherSpell = false
        val full = inv.isFull()
        val hooks =
            ghostHooks(
                conditions =
                    mapOf(
                        "If the player talks to Filliman but has not cast the Bloom spell:" to { !fungus },
                        "If the player casts the Bloom spell but nothing rotten is nearby:" to { false },
                        "If the player casts the Bloom spell and there are nearby rotten remnants:" to {
                            false
                        },
                        "If the player returns and talks to Filliman after casting the Bloom spell:" to {
                            fungus
                        },
                        "If the player does not have all three items:" to { true },
                        "If the player does not have any inventory space:" to { full },
                        "If the player has one free inventory space:" to { !full },
                    ),
                options =
                    mapOf("Could I have another bloom scroll please?" to { anotherSpell = true }),
            )
        runQuestSection(npc, dialogue, "Talking to Filliman (2)", hooks, speakers)
        if (anotherSpell && !full) invAdd(inv, NsObjs.bloom_spell)
        if (fungus) setState(PUZZLE)
    }

    private suspend fun ProtectedAccess.puzzleTalk(npc: Npc) {
        val placed = flags() and (FUNGUS_PLACED or SPELL_PLACED) == (FUNGUS_PLACED or SPELL_PLACED)
        val onFaith = player.coords == FAITH_STONE
        val ready = placed && onFaith
        val hooks =
            ghostHooks(
                conditions =
                    mapOf(
                        "If the player asks for the spell but the items are not all on their respective stones:" to {
                            !ready
                        },
                        "If the player asks for the spell and all the items are placed onto their respective stones:" to {
                            ready
                        },
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Filliman again on the orange stone", hooks, speakers)
        if (ready) setState(GROVE)
    }

    /* The grotto and the nature spirit */

    private suspend fun ProtectedAccess.searchGrotto() {
        when {
            state == GROVE -> {
                val hooks =
                    SimpleQuestHooks(conditions = mapOf("If the player searches the grotto:" to { true }))
                runQuestSection(null, dialogue, "Talking to Filliman inside his grove", hooks, speakers)
                setState(TRANSFORMED)
            }
            state == KILLED_ALL -> finish()
            else -> mes("You search the grotto but find nothing of interest.")
        }
    }

    private suspend fun ProtectedAccess.talkSpirit(npc: Npc) {
        when {
            state < TRANSFORMED -> startDialogue(npc) { chatNpc(neutral, "...") }
            state == TRANSFORMED -> blessSickle(npc)
            state < KILLED_ALL ->
                runQuestSection(npc, dialogue, "Subsequent dialogue with Filliman (3)", SimpleQuestHooks(), speakers)
            state == KILLED_ALL -> finish()
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Welcome, my friend. The grotto is yours to pray at whenever you wish.")
                }
        }
    }

    private suspend fun ProtectedAccess.blessSickle(npc: Npc) {
        val sickle = carries(NsObjs.sickle)
        val full = inv.isFull()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have the sickle in their inventory:" to { !sickle },
                        "If the player has the silver sickle in their inventory:" to { sickle },
                        "If the player does not have inventory space:" to { full },
                        "If the player has one free inventory space:" to { !full },
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Filliman inside his grotto again", hooks, speakers)
        if (!sickle) return
        invReplace(inv, NsObjs.sickle, 1, NsObjs.sickle_blessed)
        if (!full) {
            invAdd(inv, NsObjs.pouch_empty)
            setState(SICKLE)
        }
    }

    private suspend fun ProtectedAccess.refreshSickle() {
        if (state < SICKLE) {
            mes("Nothing interesting happens.")
            return
        }
        invReplace(inv, NsObjs.sickle, 1, NsObjs.sickle_blessed)
        mesbox("You dip the silver sickle into the grotto waters and it is blessed.")
    }

    private suspend fun ProtectedAccess.finish() {
        runQuestSection(null, dialogue, "Returning to Filliman's grove", SimpleQuestHooks(), speakers)
        statAdvance(stats.crafting, 3000.0 * xpMods.get(player, stats.crafting))
        statAdvance(stats.defence, 2000.0 * xpMods.get(player, stats.defence))
        statAdvance(stats.hitpoints, 2000.0 * xpMods.get(player, stats.hitpoints))
        completeQuest(NATURE_SPIRIT)
    }

    /* Bloom, the druid pouch and the ghasts */

    private suspend fun ProtectedAccess.castBloom(fromSickle: Boolean) {
        val allowed = if (fromSickle) state >= SICKLE else state >= BLESSED
        if (!allowed) {
            mesbox("You've not been told how to use this item yet.")
            return
        }
        if (stat(stats.prayer) == 0) {
            mes("You need some Prayer points to cast Bloom.")
            return
        }
        val c = player.coords
        val targets =
            listOf(NsLocs.log to NsLocs.log_bloomed, NsLocs.branch to NsLocs.branch_bloomed, NsLocs.bush to NsLocs.bush_bloomed)
                .let { if (fromSickle) it else it.take(1) }
        val found = nearby(c, targets.map { it.first })
        statSub(stats.prayer, random.of(1, 6), 0)
        anim(NsSeqs.bloom)
        delay(2)
        if (found.isEmpty()) {
            mes("The swamp seems to sigh... but there's nothing rotten nearby to bloom.")
            return
        }
        for (loc in found) {
            val into = targets.first { it.first.id == loc.id }.second
            locRepo.change(loc, into, BLOOM_TICKS)
        }
        if (!fromSickle) invReplace(inv, NsObjs.bloom_spell, 1, NsObjs.used_spell)
        mes("The swamp around you blooms!")
    }

    private fun nearby(c: CoordGrid, types: List<LocType>): List<LocInfo> {
        val ids = types.map { it.id }.toSet()
        return locRepo
            .findAll(ZoneKey.from(c))
            .filter { it.id in ids && it.coords.level == c.level && it.coords.chebyshevDistance(c) <= 1 }
            .toList()
    }

    private suspend fun ProtectedAccess.harvest(loc: BoundLocInfo, back: LocType, obj: ObjType) {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        anim(NsSeqs.pick)
        delay(1)
        invAdd(inv, obj)
        locRepo.change(loc, back, BLOOM_TICKS)
    }

    private suspend fun ProtectedAccess.fillPouch() {
        if (state < SICKLE) {
            mesbox("You've not been told how to use this item yet.")
            return
        }
        var added = 0
        for (bounty in NsObjs.bounty) {
            val n = invTotal(inv, bounty)
            if (n > 0) {
                invDel(inv, bounty, n)
                added += n
            }
        }
        if (added == 0) {
            mes("You need some fungi, stems or pears from the swamp to fill the pouch.")
            return
        }
        if (carries(NsObjs.pouch_empty)) {
            invDel(inv, NsObjs.pouch_empty)
            invAdd(inv, NsObjs.pouch, added)
        } else {
            invAdd(inv, NsObjs.pouch, added)
        }
        mes("You fill the pouch with nature's bounty.")
        delay(1)
    }

    private suspend fun ProtectedAccess.revealGhast(ghast: Npc) {
        if (state < SICKLE) {
            mesbox("You've not been told how to use this item yet.")
            return
        }
        if (hasGhastHunter()) {
            mes("A ghast is already hunting you!")
            return
        }
        usePouchCharge()
        summonVisibleGhast(ghast, npcRepo, npcTypes, aiInteractions)
        mes("The ghast is revealed!")
        delay(1)
    }

    private fun ghastKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != NsNpcs.ghast_vis.id) return
        val player = event.hero
        val state = player.questState(NATURE_SPIRIT)
        if (state !in SICKLE until KILLED_ALL) return
        val next =
            when {
                state < KILLED_1 -> KILLED_1
                state < KILLED_2 -> KILLED_2
                else -> KILLED_ALL
            }
        player.setQuestState(NATURE_SPIRIT, next)
        val left = 3 - listOf(KILLED_1, KILLED_2, KILLED_ALL).indexOf(next) - 1
        player.mes(
            if (left == 0) "You've killed three ghasts. Return to the nature spirit."
            else "You've killed a ghast. $left more to go."
        )
    }

    companion object {
        const val STARTED = 5
        const val MET = 10
        const val MIRROR = 15
        const val JOURNAL = 20
        const val HELP = 25
        const val BLESSED = 30
        const val PUZZLE = 40
        const val GROVE = 50
        const val TRANSFORMED = 60
        const val SICKLE = 70
        const val KILLED_1 = 75
        const val KILLED_2 = 80
        const val KILLED_ALL = 90
        const val COMPLETE = 110

        private const val PIES = 3
        private const val UNDERGROUND_Z = 9000
        private const val BRIDGE_FAIL_ODDS = 4
        private const val APPARITION_TICKS = 200
        private const val BLOOM_TICKS = 100
        private const val FUNGUS_PLACED = 1
        private const val SPELL_PLACED = 2
        private const val STONE_NATURE = 0
        private const val STONE_FAITH = 1
        private const val STONE_SPIRIT = 2

        private val ISLAND_SIDE = CoordGrid(3440, 3332, 0)
        private val SWAMP_SIDE = CoordGrid(3440, 3328, 0)
        private val FILLIMAN_SPOT = CoordGrid(3441, 3336, 0)
        private val FAITH_STONE = CoordGrid(3440, 3335, 0)
        private val OUTSIDE_GROTTO = CoordGrid(3440, 3336, 0)
        private val GROTTO_INSIDE = CoordGrid(3442, 9734, 0)
        private val GROTTO_ALTAR = CoordGrid(3442, 9734, 1)

        val NATURE_SPIRIT =
            QuestDef(
                name = "Nature Spirit",
                varp = { NsVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "3,000 Crafting XP",
                        "2,000 Defence XP",
                        "2,000 Hitpoints XP",
                        "Bloom with a blessed silver sickle",
                    ),
                scrollObj = { NsObjs.sickle_blessed },
            )
    }
}

// Every cache ref Nature Spirit uses (re-check after cache updates).

internal object NsNpcs : NpcReferences() {
    val filliman = find("filliman_tarlock_spirit")
    val spirit = find("filliman_tarlock_ns")
    val ulizius = find("mort_myre_gate_guard")
    val ghast_invis = find("ghast_invis")
    val ghast_vis = find("ghast_vis")
}

internal object NsLocs : LocReferences() {
    val gates = listOf(find("mortmyre_metalgateclosed_l"), find("mortmyre_metalgateclosed_r"))
    val bridge = find("druidjump_loc")
    val grotto_entrance = find("grotto_door_druidicspirit")
    val grotto_tree = find("grotto_druidicspirit")
    val grotto = find("druidic_spirit_grotto")
    val grotto_exit = find("underground_rootwall_door")
    val grotto_exit_green = find("underground_rootwall_door_green")
    val stone_nature = find("stonedisc_ds_nature")
    val stone_faith = find("stonedisc_ds_faith")
    val stone_spirit = find("stonedisc_ds_spirit")
    val log = find("log_druidicspirit")
    val log_bloomed = find("log_druidicspirit2")
    val branch = find("branch_druidicspirit")
    val branch_bloomed = find("branch_druidicspirit2")
    val bush = find("peartree_druidicspirit")
    val bush_bloomed = find("peartree_druidicspirit2")
}

internal object NsObjs : ObjReferences() {
    val meat_pie = find("meat_pie")
    val apple_pie = find("apple_pie")
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_e = find("amulet_of_ghostspeak_enchanted")
    val mirror = find("mirror")
    val journal = find("filliman_journal")
    val bloom_spell = find("bloom_spell")
    val used_spell = find("used_bloom_spell")
    val fungus = find("mortmyremushroom")
    val stem = find("mortmyrebuddingstem")
    val pear = find("mortmyrepear")
    val bounty = listOf(fungus, stem, pear)
    val sickle = find("silver_sickle")
    val sickle_blessed = find("silver_sickle_blessed")
    val pouch = find("druid_pouch")
    val pouch_empty = find("druid_pouch_empty")
}

internal object NsSeqs : org.rsmod.api.type.refs.seq.SeqReferences() {
    val jump = find("human_jump_stones")
    val pick = find("human_pickupfloor")
    val bloom = find("human_casting")
}

internal object NsVarps : VarpReferences() {
    val quest = find("druidspirit")

    /** Server varp (65787): bit 0 fungus on the nature stone, bit 1 used spell on the spirit stone. */
    val progress = find("ns_progress")
}

internal object NsVarpBuilds : VarpBuilder() {
    init {
        build("ns_progress")
    }
}
