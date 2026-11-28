package org.rsmod.content.quests.watchtower

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Watchtower (OSRS Wiki). The quest varp is the cache's `itwatchtower` (0-13; 14 once the spell
 * scroll is read, which the spellbook reads for Watchtower Teleport). The order-free progress lives
 * on `watchtower_rework_var` (varp 936, Watchtower's own): the shaman multinpcs
 * (`watchtower_shaman_1-6`) and the pillar multilocs (`watchtower_pillar_1-4`) read it.
 * - The Watchtower Wizard (top floor; the guard blocks the ladder until the quest starts, the
 *   trellis needs 18 Agility) wants evidence: the far north-west bush holds skavid fingernails.
 * - Three ogre chieftains each give a relic part: Og (north-west of Yanille) for Toban's stolen
 *   gold (his key opens Toban's chest), Grew (the island; a rope on the branch) for Gorad's tooth,
 *   Toban (through the tunnel south of Gu'Tanoth) for dragon bones. Grew adds the yellow crystal.
 *   The wizard assembles the ogre relic.
 * - Gu'Tanoth: the relic gets you past the north-west gate guards; a rock cake stolen from the
 *   market counter (15 Thieving) past the battlement guards; 20 coins to jump the bridge gap (25
 *   Agility); a death rune (the city guard's riddle) buys the skavid map.
 * - The skavid caves: the scared skavid teaches the common words; four skavids each answer one
 *   phrase; through the east gate (a gold bar) the mad skavid gives the magenta crystal.
 * - Cave nightshade on an enclave guard opens the Ogre Enclave. The shamans can't be harmed; the
 *   wizard enchants a guam potion with jangerberries and ground bat bones (14 Herblore), which
 *   kills each of the six shamans (14 Magic); the last drops the cyan crystal. The Rock of
 *   Dalgroth (40 Mining, a pickaxe) holds the grey one.
 * - The crystals on the pillars (NE magenta, SE grey, SW yellow, NW cyan) and the lever. 4 QP,
 *   15,250 Magic xp, 5,000 coins and the Watchtower Teleport scroll.
 *
 * Simplified (Authored, not Wiki): the wizard takes the three relic parts together (not one at a
 * time); the skavid caves need the map but no light source; each skavid's phrase is keyed by npc
 * (skavidtalker1-4), and the fourth skavid's reply ("Gor cur" -> "Ar") is authored; a wrong answer
 * ends the chat (talk again to retry); the enclave cutscene is its transcript dialogue; the east
 * gate guard skips his second ("Afterwards, without a gold bar") chat; Gorad's Attack isn't
 * blocked after his tooth; the Watchtower's climbing-rocks shortcut and the Gu'Tanoth wall/rock
 * shortcuts aren't modelled. Arrival tiles (caves, islands, gates, the gap, the enclave) and the
 * Gu'Tanoth guard / skavid / shaman spawns are best guesses (Ryan-test).
 */
class Watchtower
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val rng: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(Watchtower::class.java, "watchtower") }

    private val wizardSpeaker by lazy { mapOf("Watchtower Wizard" to npcTypes[WtNpcs.wizard]) }
    private val towerGuardSpeaker by lazy { mapOf("Tower guard" to npcTypes[WtNpcs.tower_guard]) }
    private val ogreGuardSpeaker by lazy { mapOf("Ogre guard" to npcTypes[WtNpcs.ogre_guards[1]]) }
    private val enclaveGuardSpeaker by lazy { mapOf("Enclave guard" to npcTypes[WtNpcs.enclave_guard]) }
    private val shamanSpeaker by lazy { mapOf("Ogre shaman" to npcTypes[WtNpcs.shaman_chat]) }

    /** One skavid of the four: its phrase, the answer and its reply (Wiki word pairs). */
    private class Skavid(val npc: NpcType, val flag: VarBitType, val phrase: String, val answer: String, val reply: String)

    private val skavids by lazy {
        listOf(
            // The nightshade cave's skavid (OSRS Wiki walkthrough).
            Skavid(WtNpcs.skavids[0], WtVarBits.skavid_flags[0], "Cur bidith", "Ig", "Ig! Bidith ig!"),
            // Authored reply (not Wiki): the transcript has no section for this skavid.
            Skavid(WtNpcs.skavids[1], WtVarBits.skavid_flags[1], "Gor cur", "Ar", "Ar, gor cur!"),
            Skavid(WtNpcs.skavids[2], WtVarBits.skavid_flags[2], "Bidith tanath", "Cur", "Cur, cur tanath!"),
            Skavid(WtNpcs.skavids[3], WtVarBits.skavid_flags[3], "Tanath gor", "Nod", "Nod, gor nod!"),
        )
    }

    /** A Watchtower bush with an item: the transcript condition and the obj it gives. */
    private class Bush(val loc: LocType, val cond: String, val obj: ObjType)

    private val bushes by lazy {
        listOf(
            Bush(WtLocs.bush_nails, "Fingernails:", WtObjs.fingernails),
            Bush(WtLocs.bush_dagger, "Damaged dagger:", WtObjs.dagger),
            Bush(WtLocs.bush_eyepatch, "Tattered eye patch:", WtObjs.eyepatch),
            Bush(WtLocs.bush_robe, "Old robe:", WtObjs.robe),
            Bush(WtLocs.bush_armour, "Unusual armour:", WtObjs.armour),
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, WtNpcs.wizard, WATCHTOWER, wants = { true }) { talkWizard(it) }
            for (wizard in WtNpcs.wizards) {
                register(npcTypes, wizard, WATCHTOWER, wants = { true }) { talkLesserWizard(it) }
            }
            register(npcTypes, WtNpcs.tower_guard, WATCHTOWER, wants = { true }) {
                section(it, "Speaking to a tower guard")
            }
            register(npcTypes, WtNpcs.og, WATCHTOWER, wants = { true }) { talkOg(it) }
            register(npcTypes, WtNpcs.grew, WATCHTOWER, wants = { true }) { talkGrew(it) }
            register(npcTypes, WtNpcs.toban, WATCHTOWER, wants = { true }) { talkToban(it) }
            register(npcTypes, WtNpcs.gorad, WATCHTOWER, wants = { true }) { talkGorad(it) }
            for (guard in WtNpcs.ogre_guards) {
                register(npcTypes, guard, WATCHTOWER, wants = { true }) { talkOgreGuard(it) }
            }
            register(npcTypes, WtNpcs.city_guard, WATCHTOWER, wants = { true }) { talkCityGuard(it) }
            register(npcTypes, WtNpcs.chieftain, WATCHTOWER, wants = { true }) {
                section(it, "Talking to an ogre chieftain")
            }
            register(npcTypes, WtNpcs.scared_skavid, WATCHTOWER, wants = { true }) { talkScaredSkavid(it) }
            for (skavid in skavids) {
                register(npcTypes, skavid.npc, WATCHTOWER, wants = { true }) { talkSkavid(it, skavid) }
            }
            register(npcTypes, WtNpcs.mad_skavid, WATCHTOWER, wants = { true }) { talkMadSkavid(it) }
            register(npcTypes, WtNpcs.enclave_guard, WATCHTOWER, wants = { true }) { talkEnclaveGuard(it) }
            for (shaman in WtNpcs.shamans) {
                register(npcTypes, shaman, WATCHTOWER, wants = { true }) { touchShaman(it, attack = false) }
            }
        }

        // The Watchtower.
        onLocOpLabel(locTypes, WtLocs.tower_ladder, "Climb-up") { climbTowerLadder() }
        onLocOpLabel(locTypes, WtLocs.tower_ladder_top, "Climb-down") { climb(-1) }
        onLocOpLabel(locTypes, WtLocs.upper_ladder, "Climb-up") { climb(1) }
        onLocOpLabel(locTypes, WtLocs.upper_ladder_down, "Climb-down") { climb(-1) }
        onLocOpLabel(locTypes, WtLocs.trellis, "Climb-up") { climbTrellis() }
        for (bush in bushes) onLocOpLabel(locTypes, bush.loc, "Search") { searchBush(bush) }
        onLocOpLabel(locTypes, WtLocs.bush_empty, "Search") { searchEmptyBush() }
        onOpNpcU(WtNpcs.wizard, WtObjs.fingernails) { showFingernails(it.npc) }
        for (evidence in WtObjs.evidence) {
            onOpNpcU(WtNpcs.wizard, evidence) {
                section(it.npc, "Using evidence other than the fingernails on the Watchtower Wizard")
            }
        }
        for (crystal in WtObjs.crystals) {
            onOpNpcU(WtNpcs.wizard, crystal) { section(it.npc, "Using a crystal on the Watchtower Wizard") }
        }

        // The ogre chieftains.
        onOpLocU(WtLocs.branch, WtObjs.rope) { swingToIsland() }
        onLocOpLabel(locTypes, WtLocs.ropeswing, "Swing-on") { telejump(GREW_NORTH_SHORE) }
        onLocOpLabel(locTypes, WtLocs.toban_cave, "Enter") { enterTobanTunnel() }
        onLocOpLabel(locTypes, WtLocs.toban_ladder, "Climb-down") { telejump(TOBAN_HOLE) }
        onLocOpLabel(locTypes, WtLocs.toban_block, "Climb-over") {
            section(null, "Toban's island", islandHooks("Looking at the broken bridge:"))
        }
        onLocOpLabel(locTypes, WtLocs.toban_chest, "Open") { openTobanChest() }
        onEvent<NpcKilledEvent> { goradKilled(this) }
        for ((index, part) in WtObjs.relic_parts.withIndex()) {
            for (other in WtObjs.relic_parts.drop(index + 1)) {
                onOpHeldU(part, other) { section(null, "Using the relic parts on each other") }
            }
        }

        // Gu'Tanoth.
        for (gate in WtLocs.nw_gate) onLocOpLabel(locTypes, gate, "Open") { relicGuard(null, it) }
        for (gate in WtLocs.east_gate) onLocOpLabel(locTypes, gate, "Open") { eastGateGuard(null, it) }
        onLocOpLabel(locTypes, WtLocs.counter, "Steal-From") { stealRockCake() }
        onLocOpLabel(locTypes, WtLocs.battlement, "Climb-over") { climbBattlement(it) }
        onLocOpLabel(locTypes, WtLocs.gap_south, "Jump-Over") { jumpGap() }
        onLocOpLabel(locTypes, WtLocs.gap_north, "Jump-Over") {
            section(null, "Crossing back across the bridge")
            telejump(GAP_SOUTH)
        }
        onOpNpcU(WtNpcs.city_guard, WtObjs.death_rune) { giveDeathRune(it.npc) }

        // The skavid caves.
        for ((index, cave) in WtLocs.skavid_caves.withIndex()) {
            onLocOpLabel(locTypes, cave, "Enter") { enterSkavidCave(index) }
        }
        for ((index, exit) in WtLocs.cave_exits.withIndex()) {
            onLocOpLabel(locTypes, exit, "Leave") { telejump(CAVE_OUTSIDE[index]) }
        }
        onLocOpLabel(locTypes, WtLocs.yanille_hole, "Climb-down") {
            mes("So that's how the skavids are getting into Yanille!")
            telejump(YANILLE_HOLE)
        }

        // The Ogre Enclave.
        onOpNpcU(WtNpcs.enclave_guard, WtObjs.nightshade) { useNightshade(it.npc) }
        onLocOpLabel(locTypes, WtLocs.shaman_entrance, "Enter") { tryEnclave() }
        onLocOpLabel(locTypes, WtLocs.enclave_exit, "Enter") { telejump(ENCLAVE_EXIT) }
        for ((index, shaman) in WtNpcs.shamans.withIndex()) {
            onOpNpc2(shaman) { touchShaman(it.npc, attack = true) }
            onOpNpcU(shaman, WtObjs.magic_ogre_potion) { killShaman(index) }
        }
        onHeldOpLabel(objTypes, WtObjs.shaman_robe, "Search") { searchRobe() }
        onLocOpLabel(locTypes, WtLocs.rock, "Prospect") { prospectRock() }
        onLocOpLabel(locTypes, WtLocs.rock, "Mine") { mineRock() }

        // The magic ogre potion.
        onOpHeldU(WtObjs.pestle, WtObjs.bat_bones) { grindBatBones() }
        onOpHeldU(WtObjs.guam_unf, WtObjs.jangerberries) { mixJangerberries() }
        onOpHeldU(WtObjs.guam_janger, WtObjs.ground_bones) { mixBones(WtObjs.guam_janger) }
        onOpHeldU(WtObjs.guam_unf, WtObjs.ground_bones) { mixBones(WtObjs.guam_unf) }
        onOpHeldU(WtObjs.vial_water, WtObjs.ground_bones) { mixBones(WtObjs.vial_water) }

        // The shield generator.
        for ((pillar, loc) in WtLocs.pillars.withIndex()) {
            for (crystal in WtObjs.crystals) onOpLocU(loc, crystal) { placeCrystal(pillar, crystal) }
        }
        onLocOpLabel(locTypes, WtLocs.lever, "Pull") { pullLever() }
        onHeldOpLabel(objTypes, WtObjs.scroll, "Read") { readScroll() }
    }

    /* State */

    private val ProtectedAccess.stage: Int
        get() = player.questState(WATCHTOWER)

    private val ProtectedAccess.complete: Boolean
        get() = stage >= COMPLETE

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(WATCHTOWER, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.banked(obj: ObjType): Boolean = invTotal(bank, obj) > 0

    private fun ProtectedAccess.owns(obj: ObjType): Boolean =
        carries(obj) || invTotal(worn, obj) > 0 || banked(obj)

    private suspend fun ProtectedAccess.section(
        npc: Npc?,
        title: String,
        hooks: SimpleQuestHooks = SimpleQuestHooks(),
        speakers: Map<String, NpcType> = emptyMap(),
    ) {
        runQuestSection(npc, dialogue, title, hooks, speakers)
    }

    private fun ProtectedAccess.meets(stat: StatType, level: Int, name: String): Boolean {
        if (stat(stat) >= level) return true
        mes("You need a $name level of $level to do that.") // Authored (not Wiki).
        return false
    }

    private fun ProtectedAccess.give(obj: ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    /** A crystal that is carried, banked or already on its pillar. */
    private fun ProtectedAccess.hasCrystal(crystal: ObjType): Boolean {
        val pillar = WtObjs.pillar_crystals.indexOfFirst { it.id == crystal.id }
        return owns(crystal) || (pillar >= 0 && flag(WtVarBits.pillars[pillar]) > 0)
    }

    private fun ProtectedAccess.allCrystals(): Boolean =
        WtObjs.pillar_crystals.withIndex().all { (pillar, crystal) ->
            carries(crystal) || flag(WtVarBits.pillars[pillar]) > 0
        }

    private fun ProtectedAccess.shamansKilled(): Int = WtVarBits.shamans.count { flag(it) == 1 }

    private fun ProtectedAccess.enclaveSeen(): Boolean =
        flag(WtVarBits.enclave_scene) == 1 || flag(WtVarBits.shaman_hit) == 1

    /* The Watchtower */

    private suspend fun ProtectedAccess.climbTowerLadder() {
        if (stage == 0) {
            val hooks = SimpleQuestHooks(conditions = mapOf("Before starting the quest:" to { true }))
            section(null, "Attempting to climb the ladder", hooks, towerGuardSpeaker)
            return
        }
        if (stage == STARTED) {
            val hooks =
                SimpleQuestHooks(conditions = mapOf("The first time after starting the quest:" to { true }))
            section(null, "Attempting to climb the ladder", hooks, towerGuardSpeaker)
        }
        climb(1)
    }

    private suspend fun ProtectedAccess.climb(levels: Int) {
        delay(1)
        telejump(player.coords.translateLevel(levels))
    }

    private suspend fun ProtectedAccess.climbTrellis() {
        if (!meets(stats.agility, TRELLIS_AGILITY, "Agility")) return
        mes("You climb up the trellis.") // Authored (not Wiki).
        delay(1)
        telejump(TRELLIS_TOP)
    }

    private suspend fun ProtectedAccess.searchBush(bush: Bush) {
        val nails = bush.obj.id == WtObjs.fingernails.id
        when {
            stage == 0 -> {
                val cond = if (nails) "Before starting the quest, the bush with the fingernails:" else "Before starting the quest:"
                section(null, "Searching the bushes", SimpleQuestHooks(conditions = mapOf(cond to { true })))
            }
            owns(bush.obj) || (nails && stage >= NAILS) ->
                startDialogue { chatPlayer(neutral, "I have already searched this place.") }
            else -> {
                section(null, "Searching the bushes", SimpleQuestHooks(conditions = mapOf(bush.cond to { true })))
                give(bush.obj)
            }
        }
    }

    private suspend fun ProtectedAccess.searchEmptyBush() {
        if (stage == 0) {
            val hooks = SimpleQuestHooks(conditions = mapOf("Before starting the quest:" to { true }))
            section(null, "Searching the bushes", hooks)
            return
        }
        startDialogue { chatPlayer(neutral, "Hmmm, nothing here.") }
    }

    /* The Watchtower Wizard */

    private suspend fun ProtectedAccess.talkWizard(npc: Npc) {
        when {
            complete -> talkWizardAfterQuest(npc)
            stage == 0 -> {
                var start = false
                section(npc, "Speaking to the Watchtower Wizard", SimpleQuestHooks(options = mapOf("Yes." to { start = true })))
                if (start) advance(STARTED)
            }
            stage == STARTED -> talkEvidence(npc)
            stage == NAILS ->
                if (WtObjs.relic_parts.all { carries(it) }) {
                    assembleRelic(npc)
                } else {
                    section(npc, "Talking to the Watchtower Wizard (2)")
                }
            stage == RELIC -> talkLostRelic(npc)
            stage < MAP -> section(npc, "Talking to the Watchtower Wizard after gaining access to the city")
            stage < LEARNED -> section(npc, "Talking to the Watchtower Wizard after getting the skavid map")
            stage == MAD_CRYSTAL && enclaveSeen() -> {
                section(npc, "Talking to the Watchtower Wizard after discovering the shamans are invincible")
                advance(POTION_RECIPE)
            }
            stage < POTION_RECIPE ->
                section(npc, "Talking to the Watchtower Wizard after learning to navigate the skavid caves")
            carries(WtObjs.ogre_potion) && stage < ALL_CRYSTALS -> {
                section(npc, "Talking to the Watchtower Wizard after making the potion")
                invReplace(inv, WtObjs.ogre_potion, 1, WtObjs.magic_ogre_potion)
                advance(MAGIC_POTION)
            }
            stage == POTION_RECIPE -> section(npc, "Talking to the Watchtower Wizard again (2)")
            stage == MAGIC_POTION && allCrystals() -> {
                section(npc, "Talking to the Watchtower Wizard with all the crystals")
                advance(ALL_CRYSTALS)
            }
            stage == MAGIC_POTION && shamansKilled() == SHAMANS ->
                section(npc, "Returning to the Watchtower Wizard after killing the shamans without mining the rock")
            stage == MAGIC_POTION -> section(npc, "Talking to the Watchtower Wizard before killing the shamans")
            else -> section(npc, "Talking to the Watchtower Wizard again (3)")
        }
    }

    private suspend fun ProtectedAccess.talkEvidence(npc: Npc) {
        if (carries(WtObjs.fingernails)) {
            showFingernails(npc)
            return
        }
        val evidence = WtObjs.evidence.firstOrNull { carries(it) }
        val word =
            when (evidence?.id) {
                WtObjs.robe.id -> "robe"
                WtObjs.dagger.id -> "dagger"
                WtObjs.armour.id -> "armour"
                else -> "eyepatch"
            }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "With no evidence to show him:" to { evidence == null },
                        "With any of the evidence besides fingernails:" to { evidence != null },
                    ),
                rewrites = { it.replace("[robe/dagger/armour/eyepatch]", word) },
            )
        section(npc, "Talking to the Watchtower Wizard", hooks)
    }

    private suspend fun ProtectedAccess.showFingernails(npc: Npc) {
        if (stage != STARTED) {
            section(npc, "Using evidence other than the fingernails on the Watchtower Wizard")
            return
        }
        val nodes =
            dialogue.section("Using evidence other than the fingernails on the Watchtower Wizard")
                .first { it is QuestDialogue.Node.Cond && it.text == "With the fingernails" }
                .children
        var done = false
        val hooks = SimpleQuestHooks(options = mapOf("So what do I do?" to { done = true }))
        startDialogue(npc) {
            chatNpc(neutral, "Hello again. Did you find anything of interest?")
            QuestDialogueRunner(hooks).run(this, nodes)
        }
        if (done && carries(WtObjs.fingernails)) {
            invDel(inv, WtObjs.fingernails)
            advance(NAILS)
        }
    }

    private suspend fun ProtectedAccess.assembleRelic(npc: Npc) {
        // The transcript's "Assembling the relic" lines, all three parts in one go (see above).
        startDialogue(npc) {
            chatPlayer(neutral, "An ogre called Og gave me this...")
            chatNpc(
                neutral,
                "Ah, it's part of an old ogre statue. I'll take that off you - it might come in " +
                    "handy if I can fix it up.",
            )
            chatPlayer(neutral, "An ogre called Grew gave me this...")
            chatNpc(neutral, "Ah, it's more of that old ogre statue. I'll take this bit too.")
            chatPlayer(neutral, "An ogre called Toban gave me this...")
            chatNpc(neutral, "Ah, it's more of that old ogre statue. I'll take this bit too.")
            chatNpc(
                neutral,
                "It looks like you've brought me all the pieces of the relic. It is as I thought - " +
                    "a statue symbolising an ogre warrior of old.",
            )
            chatNpc(
                neutral,
                "Well, if you ever want to make friends with an ogre, this is the item to have! " +
                    "Now I'll try to assemble it...",
            )
            mesbox("The wizard gives you a complete statue.")
        }
        if (!WtObjs.relic_parts.all { carries(it) }) return
        for (part in WtObjs.relic_parts) invDel(inv, part)
        give(WtObjs.relic)
        advance(RELIC)
    }

    private suspend fun ProtectedAccess.talkLostRelic(npc: Npc) {
        val has = carries(WtObjs.relic)
        val stored = !has && owns(WtObjs.relic)
        var asked = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the relic in their inventory:" to { has },
                        "If the player lost the relic:" to { !has && !stored },
                        "If the player stored the relic somewhere:" to { stored },
                    ),
                options = mapOf("I have lost the relic you gave me." to { asked = true }),
            )
        section(npc, "Talking to the Watchtower Wizard again", hooks)
        if (asked && !has && !stored) give(WtObjs.relic)
    }

    private suspend fun ProtectedAccess.talkWizardAfterQuest(npc: Npc) {
        val read = stage >= SCROLL_READ
        val has = owns(WtObjs.scroll)
        var replace = false
        // Hand-coded "Post-quest" lines (the transcript's options there have no branches).
        startDialogue(npc) {
            chatNpc(happy, "Greetings, friend. I trust all is well with you? Yanille is safe at last!")
            val pick =
                if (read) {
                    choice2("Do you have any more quests for me?", 0, "That's okay.", 2, title = "Select an Option")
                } else {
                    choice2("I lost the scroll you gave me.", 1, "That's okay.", 2, title = "Select an Option")
                }
            when (pick) {
                0 -> {
                    chatPlayer(neutral, "Do you have any more quests for me?")
                    chatNpc(
                        neutral,
                        "More quests? No, indeed, adventurer. You have done us a great service, already.",
                    )
                }
                1 -> {
                    chatPlayer(neutral, "I lost the scroll you gave me.")
                    if (has) {
                        chatNpc(happy, "Ho, ho, ho! A comedian to the finish. There it is, in your backpack!")
                    } else {
                        chatNpc(neutral, "Never mind, have another...")
                        replace = true
                    }
                }
                else -> {
                    chatPlayer(neutral, "That's okay.")
                    chatNpc(neutral, "We are always in your debt; do come and visit us again.")
                }
            }
        }
        if (replace && !owns(WtObjs.scroll)) give(WtObjs.scroll)
    }

    private suspend fun ProtectedAccess.talkLesserWizard(npc: Npc) {
        val title =
            when {
                complete -> "Talking to a wizard (4)"
                stage == 0 -> "Speaking to a wizard"
                stage == STARTED -> "Speaking to a wizard (2)"
                stage == NAILS -> "Talking to a wizard"
                stage == RELIC -> "Talking to a wizard (2)"
                stage < MAP -> "Talking to the wizards after gaining access to the city"
                stage < LEARNED -> "Talking to the wizards after getting the skavid map"
                stage == MAD_CRYSTAL && enclaveSeen() ->
                    "Talking to the wizards after discovering the shamans are invincible"
                stage < POTION_RECIPE -> "Talking to the wizards after learning to navigate the skavid caves"
                stage == MAGIC_POTION && shamansKilled() == SHAMANS -> "Talking to a wizard (3)"
                stage < ALL_CRYSTALS -> "Talking to the wizards after learning the potion recipe"
                else -> "Talking to a wizard before activating the generator"
            }
        section(npc, title)
    }

    /* Og, Grew, Toban and Gorad */

    private suspend fun ProtectedAccess.talkOg(npc: Npc) {
        val done = flag(WtVarBits.og_done) == 1
        when {
            done -> {
                val has = carries(WtObjs.relic_parts[0])
                val stored = !has && owns(WtObjs.relic_parts[0])
                val lost = !has && !stored && stage < RELIC
                var asked = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the relic part on them:" to { has },
                                "If the player lost the relic part:" to { lost },
                                "If the relic part is stored somewhere:" to { stored },
                            ),
                        options = mapOf("I have lost the relic part you gave me." to { asked = true }),
                    )
                section(npc, "Speaking to Og after giving him Toban's gold", hooks)
                if (asked && lost) give(WtObjs.relic_parts[0])
            }
            stage >= NAILS && (owns(WtObjs.toban_key) || owns(WtObjs.toban_gold)) -> {
                val gold = carries(WtObjs.toban_gold)
                val key = carries(WtObjs.toban_key)
                val keyStored = !key && owns(WtObjs.toban_key)
                var lostKey = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the gold:" to { gold },
                                "Otherwise:" to { !gold },
                                "If the player no longer has the key:" to { !key && !keyStored },
                                "If the player has the key on them:" to { key },
                                "If the player has the key stored somewhere:" to { keyStored },
                            ),
                        options = mapOf("I have lost the key!" to { lostKey = !key && !keyStored }),
                    )
                section(npc, "Speaking to Og again", hooks)
                if (gold && carries(WtObjs.toban_gold)) {
                    invDel(inv, WtObjs.toban_gold)
                    give(WtObjs.relic_parts[0])
                    setFlag(WtVarBits.og_done, 1)
                } else if (lostKey) {
                    give(WtObjs.toban_key)
                }
            }
            else -> {
                var key = false
                val hooks =
                    SimpleQuestHooks(options = mapOf("I seek entrance to the city of ogres." to { key = true }))
                section(npc, "Og", hooks)
                if (key && stage >= NAILS && !owns(WtObjs.toban_key)) give(WtObjs.toban_key)
            }
        }
    }

    private suspend fun ProtectedAccess.talkGrew(npc: Npc) {
        if (flag(WtVarBits.grew_done) == 1) {
            val part = WtObjs.relic_parts[1]
            val crystal = WtObjs.crystals[0]
            val partHas = carries(part)
            val partStored = !partHas && owns(part)
            val partLost = !partHas && !partStored && stage < RELIC
            val crystalHas = carries(crystal) || flag(WtVarBits.pillars[YELLOW_PILLAR]) > 0
            val crystalStored = !crystalHas && owns(crystal)
            val crystalLost = !crystalHas && !crystalStored
            var askedPart = false
            var askedCrystal = false
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player has the relic part on them:" to { partHas },
                            "If the player doesn't have the relic part:" to { partLost },
                            "If the relic part is stored somewhere:" to { partStored },
                            "If the player has the crystal on them:" to { crystalHas },
                            "If the player doesn't have the crystal:" to { crystalLost },
                            "If the crystal is stored somewhere:" to { crystalStored },
                        ),
                    options =
                        mapOf(
                            "I've lost the relic part you gave me." to { askedPart = true },
                            "I've lost the crystal you gave me." to { askedCrystal = true },
                        ),
                )
            section(npc, "Speaking to Grew after giving him Gorad's tooth", hooks)
            if (askedPart && partLost) give(part)
            if (askedCrystal && crystalLost) give(crystal)
            return
        }
        if (stage < NAILS || flag(WtVarBits.tooth_got) == 0) {
            section(npc, "Grew")
            return
        }
        val tooth = carries(WtObjs.tooth)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("Without the ogre tooth:" to { !tooth }, "With the ogre tooth:" to { tooth })
            )
        section(npc, "Speaking to Grew again", hooks)
        if (tooth && carries(WtObjs.tooth)) {
            invDel(inv, WtObjs.tooth)
            give(WtObjs.relic_parts[1])
            give(WtObjs.crystals[0])
            setFlag(WtVarBits.grew_done, 1)
        }
    }

    private suspend fun ProtectedAccess.talkToban(npc: Npc) {
        if (flag(WtVarBits.toban_done) == 1) {
            val part = WtObjs.relic_parts[2]
            val has = carries(part)
            val lost = !has && !owns(part) && stage < RELIC
            var asked = false
            val hooks =
                SimpleQuestHooks(
                    conditions = mapOf("If the player has the relic part on them:" to { has }),
                    options = mapOf("I can't find the relic part you gave me." to { asked = true }),
                )
            section(npc, "Speaking to Toban after giving him dragon bones", hooks)
            if (asked && lost) {
                mes("Toban grunts and hands you another piece of the statue.") // Authored (not Wiki).
                give(part)
            }
            return
        }
        if (stage < NAILS || !carries(WtObjs.dragon_bones)) {
            section(npc, "Toban")
            return
        }
        val hooks = SimpleQuestHooks(conditions = mapOf("With dragon bones:" to { true }))
        section(npc, "Speaking to Toban again", hooks)
        if (carries(WtObjs.dragon_bones)) {
            invDel(inv, WtObjs.dragon_bones)
            give(WtObjs.relic_parts[2])
            setFlag(WtVarBits.toban_done, 1)
        }
    }

    private suspend fun ProtectedAccess.talkGorad(npc: Npc) {
        val beaten = flag(WtVarBits.tooth_got) == 1 &&
            (carries(WtObjs.tooth) || flag(WtVarBits.grew_done) == 1)
        if (beaten) {
            section(npc, "Talking to Gorad after getting his tooth")
            return
        }
        section(npc, "Facing Gorad")
        npc.opPlayer2(player, aiInteractions)
    }

    private fun goradKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != WtNpcs.gorad.id) return
        val player = event.hero
        if (player.questState(WATCHTOWER) < NAILS) return
        if (player.vars[WtVarBits.grew_done] == 1 || player.inv.freeSpace() == 0) return
        if (WtObjs.tooth in player.inv) return
        player.invAddOrDrop(objRepo, WtObjs.tooth)
        VarPlayerIntMapSetter.set(player, WtVarBits.tooth_got, 1)
        player.mes("He's dropped a tooth. You grab it quickly.")
    }

    private fun ProtectedAccess.swingToIsland() {
        invDel(inv, WtObjs.rope)
        mes("You tie the rope to the branch and swing across to the island.") // Authored (not Wiki).
        telejump(GREW_ISLAND)
    }

    private fun islandHooks(cond: String): SimpleQuestHooks =
        SimpleQuestHooks(conditions = mapOf(cond to { true }))

    private suspend fun ProtectedAccess.enterTobanTunnel() {
        telejump(TOBAN_ISLAND)
        delay(1)
        section(null, "Toban's island", islandHooks("Entering the tunnel:"))
    }

    private suspend fun ProtectedAccess.openTobanChest() {
        val key = carries(WtObjs.toban_key)
        if (key && (flag(WtVarBits.og_done) == 1 || owns(WtObjs.toban_gold))) {
            mes("The chest is empty.") // Authored (not Wiki).
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("With Toban's key:" to { key }, "Without Toban's key:" to { !key })
            )
        section(null, "Looting Toban's chest", hooks)
        if (key) give(WtObjs.toban_gold)
    }

    /* Gu'Tanoth's guards */

    private suspend fun ProtectedAccess.talkOgreGuard(npc: Npc) {
        val at = npc.coords
        when {
            at.z >= NW_GATE_Z -> relicGuard(npc, null)
            at.z <= BATTLEMENT_Z && at.x <= BATTLEMENT_X -> battlementGuard(npc)
            at.x >= EAST_GATE_X -> eastGateGuard(npc, null)
            else -> section(npc, "Talking to the southern ogre guard")
        }
    }

    /** The north-west gate: the guards want the ogre relic. */
    private suspend fun ProtectedAccess.relicGuard(npc: Npc?, gate: BoundLocInfo?) {
        val speakers = if (npc == null) ogreGuardSpeaker else emptyMap()
        if (stage >= IN_CITY) {
            if (gate != null) passThroughWall(gate) else section(npc, "Talking to the city guard after giving him the relic")
            return
        }
        val relic = carries(WtObjs.relic)
        if (!relic && WtObjs.relic_parts.any { carries(it) }) {
            val hooks = SimpleQuestHooks(conditions = mapOf("With a relic part:" to { true }))
            section(npc, "Talking to the city guard again", hooks, speakers)
            pushedDownHill()
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is not carrying the relic:" to { !relic },
                        "If the player is carrying the relic:" to { relic },
                    )
            )
        section(npc, "City guard", hooks, speakers)
        if (!relic) {
            pushedDownHill()
            return
        }
        if (stage >= RELIC) advance(IN_CITY)
        if (gate != null && stage >= IN_CITY) passThroughWall(gate)
    }

    private fun ProtectedAccess.pushedDownHill() {
        telejump(NW_GATE_PUSHED)
        player.queueHit(delay = 0, type = HitType.Typeless, damage = PUSH_DAMAGE)
    }

    /** The east gate: a gold bar, once. */
    private suspend fun ProtectedAccess.eastGateGuard(npc: Npc?, gate: BoundLocInfo?) {
        val speakers = if (npc == null) ogreGuardSpeaker else emptyMap()
        if (flag(WtVarBits.east_gate_paid) == 1 || complete) {
            if (gate != null) {
                passThroughWall(gate)
            } else {
                section(npc, "East gate guard", SimpleQuestHooks(conditions = mapOf("After giving him the gold:" to { true })))
            }
            return
        }
        val gold = carries(WtObjs.gold_bar)
        var opened = false
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("The first time:" to { !gold }, "Afterwards, with a gold bar:" to { gold }),
                actions = mapOf("(The gate opens and the player walks through)" to { opened = true }),
            )
        section(npc, "East gate guard", hooks, speakers)
        if (opened && carries(WtObjs.gold_bar)) {
            invDel(inv, WtObjs.gold_bar)
            setFlag(WtVarBits.east_gate_paid, 1)
            if (gate != null) passThroughWall(gate)
        }
    }

    /** The battlement guards: a rock cake from the market. */
    private suspend fun ProtectedAccess.battlementGuard(npc: Npc?) {
        val speakers = if (npc == null) ogreGuardSpeaker else emptyMap()
        when {
            stage >= BATTLEMENT -> {
                val hooks = SimpleQuestHooks(conditions = mapOf("After giving him the rock cake:" to { true }))
                section(npc, "Talking to the ogre guard again", hooks, speakers)
            }
            stage >= IN_CITY && carries(WtObjs.rock_cake) -> {
                val hooks = SimpleQuestHooks(conditions = mapOf("With an ogre rockcake:" to { true }))
                section(npc, "Talking to the ogre guard again", hooks, speakers)
                if (carries(WtObjs.rock_cake)) {
                    invDel(inv, WtObjs.rock_cake)
                    advance(BATTLEMENT)
                }
            }
            else -> section(npc, "Ogre guard", SimpleQuestHooks(), speakers)
        }
    }

    private suspend fun ProtectedAccess.climbBattlement(loc: BoundLocInfo) {
        if (stage < BATTLEMENT && !complete) {
            battlementGuard(null)
            if (stage < BATTLEMENT) return
        }
        val west = player.coords.x < loc.coords.x
        val dest = CoordGrid(loc.coords.x + if (west) 1 else -1, loc.coords.z, 0)
        delay(1)
        telejump(dest)
    }

    private suspend fun ProtectedAccess.stealRockCake() {
        if (stage < IN_CITY && !complete) {
            mes("The ogre selling the cakes glares at you.") // Authored (not Wiki).
            return
        }
        if (!meets(stats.thieving, COUNTER_THIEVING, "Thieving")) return
        delay(1)
        give(WtObjs.rock_cake)
        statAdvance(stats.thieving, COUNTER_XP * xpMods.get(player, stats.thieving))
        mes("You steal a rock cake.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.jumpGap() {
        if (!meets(stats.agility, GAP_AGILITY, "Agility")) return
        val coins = carries(WtObjs.coins, TOLL)
        var pay = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have 20 coins:" to { !coins },
                        "If the player has 20 coins:" to { coins },
                    ),
                options = mapOf("Okay, I'll pay it." to { pay = true }),
            )
        section(null, "Crossing the bridge", hooks, ogreGuardSpeaker)
        if (!pay || !carries(WtObjs.coins, TOLL)) return
        invDel(inv, WtObjs.coins, TOLL)
        telejump(GAP_NORTH)
        statAdvance(stats.agility, GAP_XP * xpMods.get(player, stats.agility))
    }

    /** The city guard past the bridge: the riddle (death rune) for the skavid map. */
    private suspend fun ProtectedAccess.talkCityGuard(npc: Npc) {
        when {
            stage >= MAP -> {
                val has = carries(WtObjs.skavid_map)
                val stored = !has && owns(WtObjs.skavid_map)
                var asked = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have a Skavid map in their inventory or bank:" to
                                    { !has && !stored },
                                "If the player has a Skavid map in their bank:" to { stored },
                                "If the player has a Skavid map in their inventory:" to { has },
                            ),
                        options = mapOf("I have lost the map you gave me." to { asked = true }),
                    )
                section(npc, "Talking to him after having given him the rune", hooks)
                if (asked && !has && !stored) give(WtObjs.skavid_map)
            }
            flag(WtVarBits.riddle_heard) == 1 && carries(WtObjs.death_rune) -> giveDeathRune(npc)
            flag(WtVarBits.riddle_heard) == 1 -> section(npc, "Talking to the city guard again (2)")
            else -> {
                var heard = false
                var fight = false
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(
                                "I seek passage into the skavid caves" to { heard = true },
                                "I am an ogre killer come to destroy you!" to { fight = true },
                            )
                    )
                section(npc, "Seeking passage", hooks)
                if (heard) setFlag(WtVarBits.riddle_heard, 1)
                if (fight) npc.opPlayer2(player, aiInteractions)
            }
        }
    }

    private suspend fun ProtectedAccess.giveDeathRune(npc: Npc) {
        if (stage < IN_CITY || stage >= MAP) {
            startDialogue(npc) { chatNpc(neutral, "That is not what I want, imp!") }
            return
        }
        val noSpace = inv.freeSpace() == 0 && invTotal(inv, WtObjs.death_rune) > 1
        val hooks = SimpleQuestHooks(conditions = mapOf("With no space for the map:" to { noSpace }))
        section(npc, "Using the death rune on the guard (or talking to him with it)", hooks)
        if (noSpace || !carries(WtObjs.death_rune)) return
        invDel(inv, WtObjs.death_rune)
        give(WtObjs.skavid_map)
        setFlag(WtVarBits.riddle_heard, 1)
        advance(MAP)
    }

    /* The skavid caves */

    private suspend fun ProtectedAccess.enterSkavidCave(index: Int) {
        if (!carries(WtObjs.skavid_map)) {
            val hooks = SimpleQuestHooks(conditions = mapOf("Without the skavid map:" to { true }))
            section(null, "Entering the caves", hooks)
            telejump(CAVE_OUTSIDE[(index + 1) % CAVE_OUTSIDE.size])
            return
        }
        delay(1)
        telejump(CAVE_INSIDE[index])
    }

    private suspend fun ProtectedAccess.talkScaredSkavid(npc: Npc) {
        when {
            stage >= LEARNED -> section(npc, "Talking to the scared skavid after learning skavid")
            stage == SCARED -> section(npc, "Talking to the scared skavid again")
            else -> {
                var calmed = false
                val hooks =
                    SimpleQuestHooks(options = mapOf("Okay, okay, I'm not going to hurt you." to { calmed = true }))
                section(npc, "Scared skavid", hooks, shamanSpeaker)
                if (calmed && stage >= MAP) advance(SCARED)
            }
        }
    }

    private suspend fun ProtectedAccess.talkSkavid(npc: Npc, skavid: Skavid) {
        if (stage < SCARED) {
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf("Before the scared skavid has taught the player any words:" to { true })
                )
            section(npc, "Learning skavid", hooks)
            return
        }
        if (flag(skavid.flag) == 1 || stage > SCARED) {
            val hooks = SimpleQuestHooks(rewrites = { it.replace("[Cur tanath!/Gor nod!/...]", skavid.reply) })
            section(npc, "Talking to a skavid again", hooks)
            return
        }
        var understood = false
        startDialogue(npc) {
            chatNpc(neutral, "${skavid.phrase}...")
            mesbox("The skavid is trying to communicate...")
            val words = SKAVID_WORDS
            val pick = choice5(words[0], 0, words[1], 1, words[2], 2, words[3], 3, words[4], 4, title = "Select an Option")
            chatPlayer(neutral, "${words[pick]}.")
            if (words[pick] == skavid.answer) {
                mesbox("It seems the skavid understood you.")
                chatNpc(happy, skavid.reply)
                understood = true
            } else {
                chatNpc(confused, "???")
                mesbox("It seems that was the wrong reply.")
            }
        }
        if (!understood) return
        setFlag(skavid.flag, 1)
        if (skavids.all { flag(it.flag) == 1 }) advance(LEARNED)
    }

    private suspend fun ProtectedAccess.talkMadSkavid(npc: Npc) {
        val crystal = WtObjs.crystals[1]
        if (stage >= MAD_CRYSTAL) {
            val has = carries(crystal) || flag(WtVarBits.pillars[MAGENTA_PILLAR]) > 0
            val stored = !has && owns(crystal)
            var asked = false
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player lost the crystal:" to { !has && !stored },
                            "If the player has the crystal:" to { has },
                            "If the crystal is stored somewhere:" to { stored },
                        ),
                    options = mapOf("But I've lost it!" to { asked = true }),
                )
            section(npc, "Talking to the mad skavid again", hooks)
            if (asked && !has && !stored) give(crystal)
            return
        }
        val (phrase, answer) = MAD_PAIRS[rng.of(0, MAD_PAIRS.size - 1)]
        if (stage < LEARNED) {
            startDialogue(npc) {
                chatNpc(neutral, "$phrase...")
                mesbox("The skavid is trying to communicate...")
            }
            mes("You don't know enough skavid to answer it yet.") // Authored (not Wiki).
            return
        }
        val others = MAD_WORDS.filter { it != answer }.toMutableList()
        val choices = mutableListOf(answer)
        repeat(MAD_CHOICES - 1) { choices += others.removeAt(rng.of(0, others.size - 1)) }
        for (i in choices.indices.reversed()) {
            val j = rng.of(0, i)
            choices[i] = choices[j].also { choices[j] = choices[i] }
        }
        var right = false
        startDialogue(npc) {
            chatNpc(neutral, "$phrase...")
            val c = choices
            val pick = choice5("${c[0]}.", 0, "${c[1]}.", 1, "${c[2]}.", 2, "${c[3]}.", 3, "${c[4]}.", 4, title = "Select an Option")
            chatPlayer(neutral, "${c[pick]}.")
            if (c[pick] == answer) {
                chatNpc(happy, "Hehe! So you speak a little skavid, eh? I'll give you a prize for that!")
                chatNpc(
                    neutral,
                    "The others are with the shamans in the enclave - you'll never get them back. " +
                        "They hid one in the sacred rock! Nothing can touch it now.",
                )
                mesbox("The skavid gives you a large crystal.")
                right = true
            } else {
                chatNpc(confused, "???")
                mesbox("It seems that was the wrong reply.")
            }
        }
        if (!right) return
        give(crystal)
        advance(MAD_CRYSTAL)
    }

    /* The Ogre Enclave */

    private suspend fun ProtectedAccess.talkEnclaveGuard(npc: Npc) {
        if (stage < MAD_CRYSTAL && !complete) {
            section(npc, "Talking to an enclave guard before getting the mad skavid's crystal")
            return
        }
        section(npc, "Talking to an enclave guard")
        npc.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.useNightshade(npc: Npc) {
        if (stage < MAD_CRYSTAL && !complete) {
            section(npc, "Talking to an enclave guard before getting the mad skavid's crystal")
            return
        }
        var run = false
        section(npc, "Using cave nightshade on an enclave guard", SimpleQuestHooks(options = mapOf("I'm not scared" to { run = true })))
        if (!run || !carries(WtObjs.nightshade)) return
        invDel(inv, WtObjs.nightshade)
        setFlag(WtVarBits.nightshade_used, 1)
        enterEnclave()
    }

    private suspend fun ProtectedAccess.tryEnclave() {
        if (complete || flag(WtVarBits.nightshade_used) == 1) {
            enterEnclave()
            return
        }
        section(null, "Attempting to enter the Ogre Enclave before distracting the guards", SimpleQuestHooks(), enclaveGuardSpeaker)
    }

    private suspend fun ProtectedAccess.enterEnclave() {
        setFlag(WtVarBits.nightshade_used, 0)
        telejump(ENCLAVE_INSIDE)
        if (complete || flag(WtVarBits.enclave_scene) == 1) return
        setFlag(WtVarBits.enclave_scene, 1)
        delay(1)
        section(null, "Inside the enclave", SimpleQuestHooks(), shamanSpeaker)
    }

    private suspend fun ProtectedAccess.touchShaman(npc: Npc, attack: Boolean) {
        if (attack) section(npc, "Attacking an ogre shaman") else section(npc, "Talking to an ogre shaman")
        setFlag(WtVarBits.shaman_hit, 1)
        player.queueHit(delay = 0, type = HitType.Typeless, damage = if (attack) ATTACK_BLAST else TALK_BLAST)
    }

    private suspend fun ProtectedAccess.killShaman(index: Int) {
        if (!meets(stats.magic, POTION_MAGIC, "Magic")) return
        val varbit = WtVarBits.shamans[index]
        if (flag(varbit) == 1) return
        setFlag(varbit, 1)
        val killed = shamansKilled()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "After killing one shaman:" to { killed == 1 },
                        "After killing two shamans:" to { killed == 2 },
                        "After killing three shamans:" to { killed == 3 },
                        "After killing four shamans:" to { killed == 4 },
                        "After killing five shamans:" to { killed == 5 },
                        "After killing the last shaman:" to { killed == SHAMANS },
                    )
            )
        section(null, "Killing the ogre shamans", hooks)
        if (killed == SHAMANS && !hasCrystal(WtObjs.crystals[2])) {
            give(WtObjs.crystals[2])
            setFlag(WtVarBits.shaman_crystal, 1)
        }
    }

    private suspend fun ProtectedAccess.searchRobe() {
        val dead = shamansKilled() == SHAMANS
        val has = hasCrystal(WtObjs.crystals[2])
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Searching the shaman robe before killing all the shamans:" to { !dead },
                        "Searching the shaman robe while already having the crystal:" to { dead && has },
                        "Searching the shaman robe while not having the crystal:" to { dead && !has },
                    )
            )
        section(null, "Killing the ogre shamans", hooks)
        if (dead && !has) give(WtObjs.crystals[2])
    }

    private suspend fun ProtectedAccess.prospectRock() {
        if (flag(WtVarBits.rock_mined) == 1) {
            section(null, "Prospecting the Rock of Dalgroth after mining it")
            return
        }
        val hooks = SimpleQuestHooks(conditions = mapOf("Prospecting:" to { true }))
        section(null, "The Rock of Dalgroth before killing the shamans", hooks)
    }

    private suspend fun ProtectedAccess.mineRock() {
        if (shamansKilled() < SHAMANS) {
            val hooks = SimpleQuestHooks(conditions = mapOf("Mining:" to { true }))
            section(null, "The Rock of Dalgroth before killing the shamans", hooks)
            return
        }
        if (hasCrystal(WtObjs.crystals[3])) {
            section(null, "Attempting to mine the Rock of Dalgroth again")
            return
        }
        if (!meets(stats.mining, ROCK_MINING, "Mining")) return
        if (WtObjs.pickaxes.none { carries(it) || invTotal(worn, it) > 0 }) {
            mes("You need a pickaxe to mine this rock.") // Authored (not Wiki).
            return
        }
        delay(2)
        section(null, "Mining the Rock of Dalgroth")
        give(WtObjs.crystals[3])
        setFlag(WtVarBits.rock_mined, 1)
    }

    /* The magic ogre potion */

    private fun ProtectedAccess.grindBatBones() {
        invReplace(inv, WtObjs.bat_bones, 1, WtObjs.ground_bones)
        mes("You grind the bat bones to a fine powder.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.mixJangerberries() {
        if (!meets(stats.herblore, POTION_HERBLORE, "Herblore")) return
        invDel(inv, WtObjs.jangerberries)
        invReplace(inv, WtObjs.guam_unf, 1, WtObjs.guam_janger)
        val hooks = SimpleQuestHooks(conditions = mapOf("Jangerberries into a guam potion (unf):" to { true }))
        section(null, "Mixing the potion", hooks)
    }

    private suspend fun ProtectedAccess.mixBones(into: ObjType) {
        val janger = into.id == WtObjs.guam_janger.id
        val unf = into.id == WtObjs.guam_unf.id
        if (janger && !meets(stats.herblore, POTION_HERBLORE, "Herblore")) return
        invDel(inv, WtObjs.ground_bones)
        when {
            janger -> invReplace(inv, into, 1, WtObjs.ogre_potion)
            // Authored outcome (not Wiki): the mixture is lost and the vial is left empty.
            else -> invReplace(inv, into, 1, WtObjs.vial_empty)
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Ground bat bones into the guam and jangerberry potion:" to { janger },
                        "Ground bat bones into a guam potion (unf):" to { unf },
                        "Ground bat bones into a vial of water:" to { !janger && !unf },
                    )
            )
        section(null, "Mixing the potion", hooks)
    }

    /* The shield generator */

    private suspend fun ProtectedAccess.placeCrystal(pillar: Int, crystal: ObjType) {
        if (stage < ALL_CRYSTALS) {
            mes("You should show the crystals to the Watchtower Wizard first.") // Authored (not Wiki).
            return
        }
        if (crystal.id != WtObjs.pillar_crystals[pillar].id) {
            section(null, "Placing a crystal on the wrong pillar")
            return
        }
        invDel(inv, crystal)
        setFlag(WtVarBits.pillars[pillar], 1)
        mes("You place the crystal on the pillar.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.pullLever() {
        if (complete) {
            mes("The shield is already working.") // Authored (not Wiki).
            return
        }
        if (WtVarBits.pillars.any { flag(it) == 0 }) {
            section(null, "Pulling the lever before placing the crystals")
            return
        }
        val hooks =
            SimpleQuestHooks(boxes = mapOf("Congratulations, you've completed a quest: Watchtower" to { true }))
        section(null, "Activating the shield generator", hooks, wizardSpeaker)
        for (pillar in WtVarBits.pillars) setFlag(pillar, GLOWING)
        statAdvance(stats.magic, REWARD_XP * xpMods.get(player, stats.magic))
        invAddOrDrop(objRepo, WtObjs.coins, REWARD_COINS)
        give(WtObjs.scroll)
        completeQuest(WATCHTOWER)
    }

    private suspend fun ProtectedAccess.readScroll() {
        if (!complete) return
        section(null, "Reading the spell scroll")
        invDel(inv, WtObjs.scroll)
        advance(SCROLL_READ)
    }

    companion object {
        const val STARTED = 1
        const val NAILS = 2
        const val RELIC = 3
        const val IN_CITY = 4
        const val BATTLEMENT = 5
        const val MAP = 6
        const val SCARED = 7
        const val LEARNED = 8
        const val MAD_CRYSTAL = 9
        const val POTION_RECIPE = 10
        const val MAGIC_POTION = 11
        const val ALL_CRYSTALS = 12
        const val COMPLETE = 13
        /** The spellbook unlocks Watchtower Teleport at 14 (cache script 2664). */
        const val SCROLL_READ = 14

        private const val SHAMANS = 6
        private const val GLOWING = 2
        private const val YELLOW_PILLAR = 1
        private const val MAGENTA_PILLAR = 2
        private const val TRELLIS_AGILITY = 18
        private const val COUNTER_THIEVING = 15
        private const val COUNTER_XP = 6.4
        private const val GAP_AGILITY = 25
        private const val GAP_XP = 5.0
        private const val TOLL = 20
        private const val POTION_HERBLORE = 14
        private const val POTION_MAGIC = 14
        private const val ROCK_MINING = 40
        private const val PUSH_DAMAGE = 3
        private const val TALK_BLAST = 20
        private const val ATTACK_BLAST = 30
        private const val REWARD_XP = 15250.0
        private const val REWARD_COINS = 5000
        private const val MAD_CHOICES = 5

        /** Ogre guard spots (npc coords): north of this z is the north-west gate. */
        private const val NW_GATE_Z = 3050
        private const val BATTLEMENT_Z = 3016
        private const val BATTLEMENT_X = 2512
        private const val EAST_GATE_X = 2540

        /** The scared skavid's five common words (the regular skavids' menu). */
        private val SKAVID_WORDS = listOf("Cur", "Ar", "Ig", "Nod", "Gor")
        private val MAD_WORDS = listOf("Ar", "Bidith", "Cur", "Gor", "Ig", "Nod", "Tanath")

        /** The mad skavid's phrases and their answers (OSRS Wiki). */
        private val MAD_PAIRS =
            listOf(
                "Ar cur" to "Gor",
                "Bidith ig" to "Cur",
                "Gor nod" to "Tanath",
                "Cur tanath" to "Bidith",
                "Bidith tanath" to "Cur",
                "Tanath gor" to "Nod",
                "Cur bidith" to "Ig",
                "Gor cur" to "Ar",
            )

        /** Authored arrival tiles (Ryan-test). */
        private val TRELLIS_TOP = CoordGrid(2548, 3116, 1)
        private val GREW_ISLAND = CoordGrid(2507, 3087, 0)
        private val GREW_NORTH_SHORE = CoordGrid(2511, 3095, 0)
        private val TOBAN_ISLAND = CoordGrid(2576, 3029, 0)
        private val TOBAN_HOLE = CoordGrid(2499, 2991, 0)
        private val NW_GATE_PUSHED = CoordGrid(2500, 3063, 0)
        private val GAP_NORTH = CoordGrid(2530, 3030, 0)
        private val GAP_SOUTH = CoordGrid(2530, 3025, 0)
        private val YANILLE_HOLE = CoordGrid(2589, 3107, 0)
        private val ENCLAVE_INSIDE = CoordGrid(2588, 9412, 0)
        private val ENCLAVE_EXIT = CoordGrid(2522, 3066, 0)

        /** Inside each skavid cave (by its exit, cave1exit-cave6exit) and outside its entrance. */
        private val CAVE_INSIDE =
            listOf(
                CoordGrid(2498, 9418, 0),
                CoordGrid(2532, 9469, 0),
                CoordGrid(2518, 9455, 0),
                CoordGrid(2498, 9451, 0),
                CoordGrid(2504, 9441, 0),
                CoordGrid(2522, 9411, 0),
            )
        private val CAVE_OUTSIDE =
            listOf(
                CoordGrid(2560, 3021, 0),
                CoordGrid(2522, 3067, 0),
                CoordGrid(2539, 3051, 0),
                CoordGrid(2552, 3051, 0),
                CoordGrid(2553, 3032, 0),
                CoordGrid(2527, 3010, 0),
            )

        val WATCHTOWER =
            QuestDef(
                name = "Watchtower",
                varp = { WtVarps.quest },
                complete = COMPLETE,
                questPoints = 4,
                rewards =
                    listOf(
                        "15,250 Magic XP",
                        "5,000 Coins",
                        "Watchtower Teleport scroll",
                        "Access to Gu'Tanoth",
                    ),
                scrollObj = { WtObjs.scroll },
            )
    }
}

// Every cache ref Watchtower uses (re-check after cache updates).

internal object WtNpcs : NpcReferences() {
    val wizard = find("watchtower_wizard")
    /** The other three wizards (top floor x2, first floor). */
    val wizards = listOf(find("qip_watchtower_wizard_02"), find("qip_watchtower_wizard_03"), find("qip_watchtower_wizard_04"))
    val tower_guard = find("tower_guard")
    val og = find("og")
    val grew = find("grew")
    val toban = find("toban")
    val gorad = find("gorad")
    /** East gate (ogre_guard), north-west gate (1), battlement (2), bridge (3); 4 unplaced. */
    val ogre_guards =
        listOf(find("ogre_guard"), find("ogre_guard1"), find("ogre_guard2"), find("ogre_guard3"), find("ogre_guard4"))
    val city_guard = find("city_guard")
    val chieftain = find("ogre_chieftan")
    val scared_skavid = find("scared_skavid")
    val mad_skavid = find("mad_skavid")
    /** The four talking skavids, in [Watchtower.skavids] order. */
    val skavids = listOf(find("skavidtalker1"), find("skavidtalker2"), find("skavidtalker3"), find("skavidtalker4"))
    val enclave_guard = find("enclave_guard")
    /** Chathead for the shamans' lines away from a shaman. */
    val shaman_chat = find("ogre_shaman")
    /** Child 0 of qip_watchtower_ogre_shaman_01-06 (watchtower_shaman_1-6). */
    val shamans =
        listOf(
            find("qip_watchtower_ogre_shaman_01_normal"),
            find("qip_watchtower_ogre_shaman_02_normal"),
            find("qip_watchtower_ogre_shaman_03_normal"),
            find("qip_watchtower_ogre_shaman_04_normal"),
            find("qip_watchtower_ogre_shaman_05_normal"),
            find("qip_watchtower_ogre_shaman_06_normal"),
        )
}

internal object WtLocs : LocReferences() {
    /** Ground floor (2544,3111) / its top on the first floor. */
    val tower_ladder = find("towerladder")
    val tower_ladder_top = find("qip_watchtower_ladder_top")
    /** First floor to the top floor (2549,3111). */
    val upper_ladder = find("watchladderup")
    val upper_ladder_down = find("watchladderdown")
    /** North wall (2548,3119). */
    val trellis = find("qip_watchtower_trellis_base")
    val bush_empty = find("watchtowerbush")
    val bush_nails = find("watchtowerbushnail")
    val bush_dagger = find("watchtowerbushdagger")
    val bush_eyepatch = find("watchtowerbusheyepatch")
    val bush_robe = find("watchtowerbushrobe")
    val bush_armour = find("watchtowerbusharmour")
    /** The branch west of Grew's island (2499,3087) and the swing off its north side. */
    val branch = find("tree_ropeswing4_norope")
    val ropeswing = find("tree_ropeswing3")
    /** The hole south of Gu'Tanoth (2499,2989), the island's ladder and broken bridge. */
    val toban_cave = find("tobancave")
    val toban_ladder = find("tobanladderdown")
    val toban_block = find("tobanblock")
    val toban_chest = find("tobanchest")
    /** North-west city gate (2504,3062-3063): the relic. */
    val nw_gate = listOf(find("ogreguardgate2"), find("ogreguardgate2right"))
    /** East city gate (2549-2550,3028): the gold bar. */
    val east_gate = listOf(find("ogreguardgate1"), find("ogreguardgate1right"))
    val counter = find("rockcounter_withcakes")
    val battlement = find("ganothbattlement")
    /** The bridge gap: south side (pays) and north side. */
    val gap_south = find("tanothjump1")
    val gap_north = find("tanothjump2")
    val skavid_caves =
        listOf(
            find("skavid_cave1"),
            find("skavid_cave2"),
            find("skavid_cave3"),
            find("skavid_cave4"),
            find("skavid_cave5"),
            find("skavid_cave6"),
        )
    val cave_exits =
        listOf(
            find("cave1exit"),
            find("cave2exit"),
            find("cave3exit"),
            find("cave4exit"),
            find("cave5exit"),
            find("cave6exit"),
        )
    /** The mad skavid's hole up into Yanille. */
    val yanille_hole = find("yanilleholein")
    /** Gu'Tanoth market (2504,3037) into the enclave; the enclave's north-east way out. */
    val shaman_entrance = find("shamanentrance")
    val enclave_exit = find("enclavecave")
    val rock = find("rock_of_dalgroth")
    /** Child 0 of each pillar multiloc, in watchtower_pillar_1-4 order (NW, SW, NE, SE). */
    val pillars =
        listOf(
            find("qip_watchtower_pillar_nocrystal_cyan"),
            find("qip_watchtower_pillar_nocrystal_yellow"),
            find("qip_watchtower_pillar_nocrystal_magenta"),
            find("qip_watchtower_pillar_nocrystal_white"),
        )
    /** Top floor (2543,3115). */
    val lever = find("watchleverup")
}

internal object WtObjs : ObjReferences() {
    val fingernails = find("fingernails")
    val robe = find("watchtowerrobe")
    val armour = find("watchtowerarmour")
    val dagger = find("watchtowerdagger")
    val eyepatch = find("watch_eye_patch")
    val evidence = listOf(robe, armour, dagger, eyepatch)
    val toban_key = find("toban_key")
    val toban_gold = find("stolen_gold")
    val tooth = find("ogretooth")
    val dragon_bones = find("dragon_bones")
    /** Og's, Grew's and Toban's parts. */
    val relic_parts = listOf(find("relicpart1"), find("relicpart2"), find("relicpart3"))
    val relic = find("ogrerelic")
    val rope = find("rope")
    val gold_bar = find("gold_bar")
    val coins = find("coins")
    val rock_cake = find("rockcake")
    val death_rune = find("deathrune")
    val skavid_map = find("skavidmap")
    /** Yellow (Grew), magenta (mad skavid), cyan (shamans), grey (the rock). */
    val crystals =
        listOf(find("powering_crystal1"), find("powering_crystal2"), find("powering_crystal3"), find("powering_crystal4"))
    /** The crystal each pillar takes, in [WtLocs.pillars] order. */
    val pillar_crystals = listOf(crystals[2], crystals[0], crystals[1], crystals[3])
    val nightshade = find("nightshade")
    val shaman_robe = find("shaman_robe")
    val guam_unf = find("guamvial")
    val jangerberries = find("jangerberries")
    val guam_janger = find("guamjangervial")
    val bat_bones = find("bat_bones")
    val ground_bones = find("ground_bat_bones")
    val pestle = find("pestle_and_mortar")
    val vial_water = find("vial_water")
    val vial_empty = find("vial_empty")
    val ogre_potion = find("ogre_potion")
    val magic_ogre_potion = find("magic_ogre_potion")
    val scroll = find("watchtowerspell")
    val pickaxes: List<ObjType> =
        listOf(
            "bronze_pickaxe", "iron_pickaxe", "steel_pickaxe", "black_pickaxe", "mithril_pickaxe",
            "adamant_pickaxe", "rune_pickaxe", "dragon_pickaxe", "infernal_pickaxe", "crystal_pickaxe",
        ).map { find(it) }
}

internal object WtVarps : VarpReferences() {
    val quest = find("itwatchtower")
}

/**
 * Watchtower's own varp (watchtower_rework_var, 936): nothing else shares it. The shaman and
 * pillar bits drive the cache multinpcs/multilocs; the others are server-side only, and some are
 * used here for what the quest needs to remember (Authored use, noted per bit).
 */
internal object WtVarBits : VarBitReferences() {
    /** Authored use: Og, Grew and Toban have each given their relic part. */
    val og_done = find("watchtower_ogre_guard_1")
    val grew_done = find("watchtower_ogre_guard_2")
    val toban_done = find("watchtower_ogre_guard_3")
    /** Authored use: the east gate guard has had his gold bar. */
    val east_gate_paid = find("watchtower_guard")
    /** Authored use: each of the four skavids has understood you ([WtNpcs.skavids] order). */
    val skavid_flags =
        listOf(
            find("watchtower_gate_chat_1"),
            find("watchtower_gate_chat_2"),
            find("watchtower_gate_chat_3"),
            find("watchtower_nightshade_chat"),
        )
    /** The city guard has told his riddle. */
    val riddle_heard = find("watchtower_puzzle_chat")
    val shaman_hit = find("watchtower_shaman_hit")
    val rock_mined = find("watchtower_rock_mined")
    /** A nightshade has distracted the guards for the next trip into the enclave. */
    val nightshade_used = find("watchtower_nightshade_used")
    /** The last shaman's (cyan) crystal has been given. */
    val shaman_crystal = find("watchtower_crystal_variable")
    /** NW cyan, SW yellow, NE magenta, SE white (grey): 0 empty, 1 crystal, 2 glowing. */
    val pillars =
        listOf(
            find("watchtower_pillar_1"),
            find("watchtower_pillar_2"),
            find("watchtower_pillar_3"),
            find("watchtower_pillar_4"),
        )
    val shamans =
        listOf(
            find("watchtower_shaman_1"),
            find("watchtower_shaman_2"),
            find("watchtower_shaman_3"),
            find("watchtower_shaman_4"),
            find("watchtower_shaman_5"),
            find("watchtower_shaman_6"),
        )
    val enclave_scene = find("watchtower_enclave_scene")
    val tooth_got = find("watchtower_tooth_got")
}
