package org.rsmod.content.quests.sleepinggiants

import jakarta.inject.Inject
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.interf.InterfaceReferences
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
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sleeping Giants (OSRS Wiki). Kovac asks for help restoring the Giants' Foundry; the quest is
 * the repair of three tools and one tutorial sword. Quest varbit `sleeping_giants` (on
 * `giants_foundry_main`), cache db end state 30, 1 QP, 6,000 Smithing XP (db 60000 tenths).
 *
 * The client reads the cache varbits throughout: the quest varbit drives both Kovac multinpcs
 * (outside: 0-4 the "Strike" hill giant, 5-9 Kovac, hidden from 10; inside: Kovac with Talk-to
 * only until 30), `sleeping_giants_tutorial` the crate (0-10 full, 15+ empty), the
 * `giants_foundry_*` crucible / mould / preform varbits the crucible and jig multilocs and the
 * refinement HUD (`giants_foundry_hud`, drawn from the temperature / completion / quality /
 * difficulty / seed varbits). Our stages:
 * - quest 5 agreed outside, 10 inside (the three `sleeping_giants_repair_*` bits), 20 all three
 *   repaired and reported, 30 the sword handed in.
 * - tutorial 1 commission given (Flat Broad), 15 crate looted, 16 crucible full and the mould
 *   explained, 17 mould set, 18 Kovac checked it, 19 poured, 20 cooled, 21 preform picked up,
 *   22 refinement explained, 23 sword finished.
 *
 * Simplified (the full minigame is a follow-up):
 * - OSRS runs the quest in an instanced copy of the foundry with broken `_quest` tool locs; here
 *   it is the static foundry, and an unrepaired tool's Use runs its repair conversation.
 * - The mould jig is a dialogue menu over the default moulds (cache structs, enums 4373-4375)
 *   instead of the `giants_foundry_mould` interface, whose selection varbits are set client-side.
 * - The crate's contents are authored (the Wiki doesn't list them): 28 bars' worth of bronze and
 *   iron; the tutorial sword is clamped to the lowest difficulty (3 sections: hammer, grind,
 *   polish, the transcript's order). No passive cooling, sweet spots or per-action xp; lava and
 *   waterfall rates are authored. The preform goes into the weapon slot (both hands).
 * - Obor's kill count isn't tracked, so the hand-in takes the "0 Obor kill count" branch.
 */
class SleepingGiants
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(SleepingGiants::class.java, "sleeping_giants") }

    private val kovacSpeaker: Map<String, NpcType> by lazy { mapOf("Kovac" to SgNpcs.kovac_1op) }

    /** Players whose refinement HUD is open (cleared on logout). */
    private val hudOpen = HashSet<Int>()

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, SgNpcs.kovac_strike, "Strike") { strikeKovac(it) }
        with(QuestTalk) {
            register(npcTypes, SgNpcs.kovac_outside, SLEEPING_GIANTS, wants = { true }) { followKovac(it) }
            register(npcTypes, SgNpcs.kovac_1op, SLEEPING_GIANTS, wants = { true }) { talkKovac(it) }
            register(npcTypes, SgNpcs.kovac, SLEEPING_GIANTS, wants = { true }) { talkKovac(it) }
        }
        onLocOpLabel(locTypes, SgLocs.entrance, "Enter") { enterFoundry() }
        onLocOpLabel(locTypes, SgLocs.exit, "Exit") { leaveFoundry() }
        onLocOpLabel(locTypes, SgLocs.buckets, "Take-from") { takeBucket() }

        onLocOpLabel(locTypes, SgLocs.trip_hammer, "Use") { useTool(Tool.Hammer) }
        onLocOpLabel(locTypes, SgLocs.grindstone, "Use") { useTool(Tool.Grind) }
        onLocOpLabel(locTypes, SgLocs.polishing_wheel, "Use") { useTool(Tool.Polish) }

        onLocOpLabel(locTypes, SgLocs.supply_box, "Search") { searchCrate(empty = false) }
        onLocOpLabel(locTypes, SgLocs.supply_box_empty, "Search") { searchCrate(empty = true) }

        for (crucible in listOf(SgLocs.crucible_empty, SgLocs.crucible_partial)) {
            onLocOpLabel(locTypes, crucible, "Fill") { fillCrucible(only = null) }
            onOpLocU(crucible) { fillCrucible(only = it.objType) }
        }
        for (crucible in SgLocs.crucibles) {
            onLocOpLabel(locTypes, crucible, "Check") { checkCrucible() }
            onLocOpLabel(locTypes, crucible, "Empty") { emptyCrucible() }
        }
        onLocOpLabel(locTypes, SgLocs.crucible_full, "Pour") { pour() }
        onOpLocU(SgLocs.crucible_full) { mes("The crucible is already full.") } // Authored (not Wiki).

        onLocOpLabel(locTypes, SgLocs.jig_empty, "Setup") { setupMould() }
        onLocOpLabel(locTypes, SgLocs.jig_mould, "Check") { checkMould() }
        onLocOpLabel(locTypes, SgLocs.jig_filled, "Pick-up") { pickUpMolten() }
        onOpLocU(SgLocs.jig_filled) { coolMould(it.objType) }
        onLocOpLabel(locTypes, SgLocs.jig_cooled, "Pick-up") { pickUpPreform() }

        onLocOpLabel(locTypes, SgLocs.lava_pool, "Heat-preform") { heat(LAVA_SLOW) }
        onLocOpLabel(locTypes, SgLocs.lava_pool, "Dunk-preform") { heat(LAVA_FAST) }
        onLocOpLabel(locTypes, SgLocs.waterfall, "Cool-preform") { heat(WATER_SLOW) }
        onLocOpLabel(locTypes, SgLocs.waterfall, "Quench-preform") { heat(WATER_FAST) }
        onOpLocU(SgLocs.waterfall) { fillBucket(it.objType) }
        onLocOpLabel(locTypes, SgLocs.storage_empty, "Store-preform") { storePreform() }
        onLocOpLabel(locTypes, SgLocs.storage_full, "Take-preform") { takePreform() }

        onPlayerLogout { hudOpen.remove(player.uid.packed) }
    }

    /* State */

    private val ProtectedAccess.state: Int
        get() = player.questState(SLEEPING_GIANTS)

    private val ProtectedAccess.tutorial: Int
        get() = bit(SgVarBits.tutorial)

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.raiseTutorial(value: Int) {
        if (tutorial < value) setBit(SgVarBits.tutorial, value)
    }

    private fun ProtectedAccess.allRepaired(): Boolean =
        bit(SgVarBits.repair_hammer) > 0 && bit(SgVarBits.repair_grind) > 0 && bit(SgVarBits.repair_polish) > 0

    private suspend fun ProtectedAccess.mbox(text: String) {
        startDialogue { mesbox(text) }
    }

    private suspend fun ProtectedAccess.kovacSection(section: String, hooks: SimpleQuestHooks = SimpleQuestHooks()) {
        runQuestSection(null, dialogue, section, hooks, kovacSpeaker)
    }

    /* Starting out */

    private suspend fun ProtectedAccess.strikeKovac(npc: Npc) {
        if (state >= AGREED) {
            followKovac(npc)
            return
        }
        if (statBase(stats.smithing) < SMITHING_REQ) {
            startDialogue(npc) {
                chatNpc(neutral, "Wait...")
                chatNpc(neutral, "Do not attack!")
                // Authored (not Wiki): the quest's Smithing requirement.
                mesbox("You need a Smithing level of $SMITHING_REQ to help this giant.")
            }
            return
        }
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Yes." to { if (state < AGREED) player.setQuestState(SLEEPING_GIANTS, AGREED) }),
                actions = mapOf("cutscene" to { moveInside() }),
            )
        runQuestSection(npc, dialogue, "Talking to Kovac", hooks)
    }

    /** After agreeing but before going in (the start conversation was cut short). */
    private suspend fun ProtectedAccess.followKovac(npc: Npc) {
        startDialogue(npc) {
            chatNpc(neutral, "Please...") // Authored (not Wiki): the start's "follow in", again.
            chatNpc(neutral, "follow in.")
        }
        moveInside()
    }

    private fun ProtectedAccess.moveInside() {
        telejump(INSIDE)
        if (state in AGREED until INSIDE_STATE) player.setQuestState(SLEEPING_GIANTS, INSIDE_STATE)
    }

    private suspend fun ProtectedAccess.enterFoundry() {
        if (state < AGREED) {
            runQuestSection(null, dialogue, "Entering the cave before starting the quest")
            return
        }
        moveInside()
    }

    private fun ProtectedAccess.leaveFoundry() {
        if (holdsPreform()) {
            removePreform()
            setBit(SgVarBits.preform_stored, 1)
            mes("You leave your preform in the preform storage to keep warm.") // Authored (not Wiki).
        }
        closeHud()
        telejump(OUTSIDE)
    }

    private fun ProtectedAccess.takeBucket() {
        if (inv.freeSpace() == 0) {
            mes("You don't have enough inventory space.")
            return
        }
        invAdd(inv, SgObjs.bucket)
        mes("You take a bucket.") // Authored (not Wiki).
    }

    private fun ProtectedAccess.fillBucket(used: UnpackedObjType) {
        if (used.id != SgObjs.bucket.id) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, SgObjs.bucket)
        invAdd(inv, SgObjs.bucket_water)
        mes("You fill the bucket from the waterfall.") // Authored (not Wiki).
    }

    /* Kovac */

    private suspend fun ProtectedAccess.talkKovac(npc: Npc) {
        when {
            state < INSIDE_STATE -> followKovac(npc)
            state < REPAIRED ->
                if (allRepaired()) {
                    runQuestSection(npc, dialogue, "Talking to Kovac after all the tools are repaired")
                    player.setQuestState(SLEEPING_GIANTS, REPAIRED)
                } else {
                    runQuestSection(npc, dialogue, "Talking to Kovac (2)")
                }
            state < COMPLETE -> kovacSword(npc)
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): the commissions themselves are the minigame (not built yet).
                    chatNpc(neutral, "Thank you, little one. Foundry ready for you whenever you want.")
                }
        }
    }

    private suspend fun ProtectedAccess.kovacSword(npc: Npc) {
        when {
            tutorial < T_BRIEFED -> {
                runQuestSection(npc, dialogue, "Talking to Kovac (3)")
                setBit(SgVarBits.commission_1, COMMISSION_BROAD)
                setBit(SgVarBits.commission_2, COMMISSION_FLAT)
                raiseTutorial(T_BRIEFED)
            }
            tutorial < T_LOOTED ->
                startDialogue(npc) {
                    // Authored (not Wiki): a reminder of the briefing.
                    chatNpc(neutral, "Metals in that box-crate by entry...")
                    chatNpc(neutral, "Use bars and weapons. Crucible can take both.")
                }
            tutorial < T_CRUCIBLE_FULL ->
                when (bit(SgVarBits.crucible_state)) {
                    CRUCIBLE_FULL -> {
                        runQuestSection(npc, dialogue, "Talking to Kovac after filling the crucible")
                        raiseTutorial(T_CRUCIBLE_FULL)
                    }
                    CRUCIBLE_PARTIAL -> runQuestSection(npc, dialogue, "Talking to Kovac after partially filling the crucible")
                    else -> runQuestSection(npc, dialogue, "Talking to Kovac after looting the crate")
                }
            tutorial < T_POURED && bit(SgVarBits.mould_state) == MOULD_SET -> {
                runQuestSection(npc, dialogue, "Talking to Kovac after setting the mould")
                raiseTutorial(T_CHECKED)
            }
            tutorial < T_POURED -> runQuestSection(npc, dialogue, "Talking to Kovac before setting the mould")
            tutorial < T_COOLED -> runQuestSection(npc, dialogue, "Before picking up the preform")
            tutorial < T_HELD || !hasPreform() ->
                if (tutorial >= T_HELD && bit(SgVarBits.preform_stored) > 0) {
                    // Authored (not Wiki): the preform was left in storage.
                    startDialogue(npc) { chatNpc(neutral, "Preform keeping warm in storage. Take it out, then we work.") }
                } else {
                    runQuestSection(npc, dialogue, "Talk to Kovac after using a bucket of water")
                }
            tutorial < T_EXPLAINED -> {
                runQuestSection(npc, dialogue, "After picking up the preform")
                raiseTutorial(T_EXPLAINED)
                openHud()
            }
            bit(SgVarBits.completion) < COMPLETION_MAX ->
                when (requiredTool()) {
                    Tool.Hammer -> runQuestSection(npc, dialogue, "Before completing the use of the trip hammer")
                    Tool.Grind -> runQuestSection(npc, dialogue, "Talking to him after hammering the preform enough")
                    Tool.Polish -> runQuestSection(npc, dialogue, "Talking to him after hammering the preform enough (2)")
                }
            else -> handIn(npc)
        }
    }

    private suspend fun ProtectedAccess.handIn(npc: Npc) {
        val ruined = bit(SgVarBits.quality) == 0
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the quality of the sword is at 0:" to { ruined },
                        "If the quality of the sword is above 0:" to { !ruined },
                        "If the player has 0 Obor kill count:" to { true },
                        "If the player has at least 1 Obor kill count:" to { false },
                    ),
                boxes =
                    mapOf(
                        "You pass the sword over to Kovac..." to {
                            removePreform()
                            false
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Handing in the sword", hooks)
        if (state >= COMPLETE) return
        removePreform()
        closeHud()
        clearPreformVars()
        setBit(SgVarBits.tutorial, 0)
        statAdvance(stats.smithing, SMITHING_XP * xpMods.get(player, stats.smithing))
        completeQuest(SLEEPING_GIANTS)
    }

    /* Repairs */

    private suspend fun ProtectedAccess.useTool(tool: Tool) {
        if (state in INSIDE_STATE until REPAIRED && bit(tool.repaired) == 0) {
            repair(tool)
            return
        }
        if (!hasPreform()) {
            mes("You need a preform to use the ${tool.label}.") // Authored (not Wiki).
            return
        }
        if (!holdsPreform()) {
            mbox("You must free your hands so you can hold the preform.")
            return
        }
        work(tool)
    }

    private suspend fun ProtectedAccess.repair(tool: Tool) {
        when (tool) {
            Tool.Grind -> {
                if (!carries(SgObjs.chisel)) {
                    // Authored (not Wiki): the Wiki's item list has the chisel for the grindstone.
                    mbox("You will need a chisel to repair the grindstone.")
                    return
                }
                val hooks =
                    SimpleQuestHooks(
                        actions = mapOf("The player begins to fix the grindstone." to { setBit(SgVarBits.repair_grind, 1) })
                    )
                kovacSection("Reparing the grindstone", hooks)
            }
            Tool.Hammer -> repairWith(tool, "Reparing the trip hammer", logs = 1, wool = false)
            Tool.Polish -> repairWith(tool, "Reparing the polishing wheel", logs = 2, wool = true)
        }
    }

    private suspend fun ProtectedAccess.repairWith(tool: Tool, section: String, logs: Int, wool: Boolean) {
        val nails = SgObjs.nails.firstOrNull { carries(it, NAILS_PER_REPAIR) }
        val hasHammer = carries(SgObjs.hammer) || carries(SgObjs.imcando_hammer)
        val hasMats = nails != null && hasHammer && carries(SgObjs.oak_logs, logs) && (!wool || carries(SgObjs.wool))
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player does not have the materials required:" to { !hasMats }),
                options =
                    mapOf(
                        "Yes." to {
                            if (hasMats && nails != null && bit(tool.repaired) == 0) {
                                invDel(inv, nails, NAILS_PER_REPAIR)
                                invDel(inv, SgObjs.oak_logs, logs)
                                if (wool) invDel(inv, SgObjs.wool)
                                setBit(tool.repaired, 1)
                            }
                        }
                    ),
            )
        kovacSection(section, hooks)
    }

    /* The crate and the crucible */

    private suspend fun ProtectedAccess.searchCrate(empty: Boolean) {
        if (state < REPAIRED || tutorial < T_BRIEFED) {
            kovacSection("Crate")
            return
        }
        val needMetal = bit(SgVarBits.crucible_state) != CRUCIBLE_FULL && tutorial < T_POURED
        if (empty && !needMetal) {
            mes("The crate is empty.") // Authored (not Wiki).
            return
        }
        val space = inv.freeSpace() >= CRATE_SLOTS
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player does not have 20 open inventory spaces:" to { !space }),
                options =
                    mapOf(
                        "Yes." to {
                            if (space) {
                                for ((obj, count) in CRATE) invAdd(inv, obj(), count)
                                raiseTutorial(T_LOOTED)
                            }
                        }
                    ),
            )
        runQuestSection(null, dialogue, "Searching crate", hooks)
    }

    private suspend fun ProtectedAccess.fillCrucible(only: UnpackedObjType?) {
        if (state < REPAIRED || tutorial < T_BRIEFED) {
            kovacSection("Crucible")
            return
        }
        if (tutorial >= T_POURED || bit(SgVarBits.mould_state) >= MOULD_FILLED) {
            runQuestSection(null, dialogue, "Attempting to fill the crucible before handing in the preform")
            return
        }
        var bronze = bit(SgVarBits.bronze)
        var iron = bit(SgVarBits.iron)
        var added = 0
        for (metal in Metal.entries) {
            for ((obj, bars) in metal.items()) {
                if (only != null && only.id != obj.id) continue
                while (bronze + iron < CRUCIBLE_BARS && invTotal(inv, obj) > 0) {
                    invDel(inv, obj)
                    val room = CRUCIBLE_BARS - bronze - iron
                    val worth = minOf(bars, room)
                    if (metal == Metal.Bronze) bronze += worth else iron += worth
                    added++
                }
            }
        }
        if (added == 0) {
            if (only != null) {
                // Authored (not Wiki): Kovac keeps the first sword to bronze and iron.
                mes("Kovac said to stick to bronze and iron bars, weapons and armour for this sword.")
            } else {
                mes("You have no bronze or iron to add to the crucible.") // Authored (not Wiki).
            }
            return
        }
        setBit(SgVarBits.bronze, bronze)
        setBit(SgVarBits.iron, iron)
        val full = bronze + iron >= CRUCIBLE_BARS
        setBit(SgVarBits.crucible_state, if (full) CRUCIBLE_FULL else CRUCIBLE_PARTIAL)
        // Authored (not Wiki).
        mes(if (full) "The crucible is full." else "You add the metal to the crucible (${bronze + iron}/$CRUCIBLE_BARS).")
    }

    private fun ProtectedAccess.checkCrucible() {
        val bronze = bit(SgVarBits.bronze)
        val iron = bit(SgVarBits.iron)
        // Authored (not Wiki).
        mes("The crucible holds $bronze bronze and $iron iron: ${bronze + iron}/$CRUCIBLE_BARS bars' worth.")
    }

    private fun ProtectedAccess.emptyCrucible() {
        if (bit(SgVarBits.crucible_state) == 0) {
            mes("The crucible is already empty.") // Authored (not Wiki).
            return
        }
        setBit(SgVarBits.bronze, 0)
        setBit(SgVarBits.iron, 0)
        setBit(SgVarBits.crucible_state, 0)
        mes("You tip the molten metal out of the crucible.") // Authored (not Wiki).
    }

    /* The mould */

    private suspend fun ProtectedAccess.setupMould() {
        if (state < REPAIRED || tutorial < T_CRUCIBLE_FULL) {
            kovacSection("Mould jig")
            return
        }
        var picks = IntArray(0)
        startDialogue {
            // Authored (not Wiki): a menu stands in for the mould setup interface.
            val forte = pickMould(Mould.fortes, "Select a forte")
            val blade = pickMould(Mould.blades, "Select a blade")
            val tip = pickMould(Mould.tips, "Select a tip")
            picks = intArrayOf(forte, blade, tip)
        }
        if (picks.size != 3) return
        setBit(SgVarBits.mould_forte, Mould.fortes[picks[0]].index)
        setBit(SgVarBits.mould_blade, Mould.blades[picks[1]].index)
        setBit(SgVarBits.mould_tip, Mould.tips[picks[2]].index)
        setBit(SgVarBits.mould_state, MOULD_SET)
        raiseTutorial(T_MOULD_SET)
        mes("You set the mould in the jig.") // Authored (not Wiki).
    }

    private suspend fun org.rsmod.api.player.dialogue.Dialogue.pickMould(moulds: List<Mould>, title: String): Int {
        val labels = moulds.map { it.label() }
        val first = choice5(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3, "More moulds...", 4, title = title)
        if (first < 4) return first
        return choice3(labels[4], 4, labels[5], 5, "Back to the first moulds", -1, title = title).let {
            if (it >= 0) it else pickMould(moulds, title)
        }
    }

    private fun ProtectedAccess.checkMould() {
        val forte = Mould.fortes.firstOrNull { it.index == bit(SgVarBits.mould_forte) }
        val blade = Mould.blades.firstOrNull { it.index == bit(SgVarBits.mould_blade) }
        val tip = Mould.tips.firstOrNull { it.index == bit(SgVarBits.mould_tip) }
        // Authored (not Wiki).
        mes("The mould is set: ${forte?.name ?: "-"}, ${blade?.name ?: "-"}, ${tip?.name ?: "-"}.")
    }

    private suspend fun ProtectedAccess.pour() {
        if (tutorial >= T_POURED || bit(SgVarBits.mould_state) >= MOULD_FILLED) {
            runQuestSection(null, dialogue, "Attempting to fill the crucible before handing in the preform")
            return
        }
        if (bit(SgVarBits.mould_state) != MOULD_SET) {
            mes("You need to set up a mould in the jig before you can pour.") // Authored (not Wiki).
            return
        }
        val metal = metalScore(bit(SgVarBits.bronze), bit(SgVarBits.iron))
        val quality = (metal + mouldScore()).coerceIn(1, MAX_QUALITY)
        setBit(SgVarBits.difficulty, minOf(metal, TUTORIAL_DIFFICULTY))
        setBit(SgVarBits.quality, quality)
        setBit(SgVarBits.start_quality, quality)
        for ((index, seed) in SgVarBits.seeds.withIndex()) setBit(seed, Tool.entries.getOrNull(index)?.seed ?: 0)
        setBit(SgVarBits.temperature, 0)
        setBit(SgVarBits.completion, 0)
        setBit(SgVarBits.bronze, 0)
        setBit(SgVarBits.iron, 0)
        setBit(SgVarBits.crucible_state, 0)
        setBit(SgVarBits.mould_state, MOULD_FILLED)
        raiseTutorial(T_POURED)
        mes("You pour the molten metal into the mould.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.pickUpMolten() {
        if (iceGloves()) {
            setBit(SgVarBits.mould_state, MOULD_COOLED)
            raiseTutorial(T_COOLED)
            pickUpPreform()
            return
        }
        runQuestSection(null, dialogue, "Trying to pick up the preform before cooling it")
    }

    private fun ProtectedAccess.coolMould(used: UnpackedObjType) {
        if (used.id != SgObjs.bucket_water.id) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, SgObjs.bucket_water)
        invAdd(inv, SgObjs.bucket)
        setBit(SgVarBits.mould_state, MOULD_COOLED)
        raiseTutorial(T_COOLED)
        mes("You pour the water over the mould and the metal hardens.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.pickUpPreform() {
        if (!handsFree()) {
            runQuestSection(null, dialogue, "Trying to pick up the preform with a weapon or shield equipped")
            return
        }
        setBit(SgVarBits.mould_state, 0)
        wieldPreform()
        raiseTutorial(T_HELD)
    }

    /* The preform */

    private fun ProtectedAccess.handsFree(): Boolean =
        player.worn[Wearpos.RightHand.slot] == null && player.worn[Wearpos.LeftHand.slot] == null

    private fun ProtectedAccess.iceGloves(): Boolean = invTotal(worn, SgObjs.ice_gloves) > 0

    private fun ProtectedAccess.holdsPreform(): Boolean = invTotal(worn, SgObjs.preform) > 0

    private fun ProtectedAccess.hasPreform(): Boolean = holdsPreform() || carries(SgObjs.preform)

    private fun ProtectedAccess.wieldPreform() {
        invAdd(worn, SgObjs.preform, slot = Wearpos.RightHand.slot)
        rebuildAppearance()
        openHud()
    }

    private fun ProtectedAccess.removePreform() {
        if (invTotal(worn, SgObjs.preform) > 0) {
            invDel(worn, SgObjs.preform)
            rebuildAppearance()
        }
        if (carries(SgObjs.preform)) invDel(inv, SgObjs.preform)
    }

    private suspend fun ProtectedAccess.storePreform() {
        if (!hasPreform()) {
            kovacSection("Preform storage")
            return
        }
        removePreform()
        closeHud()
        setBit(SgVarBits.preform_stored, 1)
        mes("You place the preform in the storage.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.takePreform() {
        if (!handsFree()) {
            mbox("You must free your hands so you can hold the preform.")
            return
        }
        setBit(SgVarBits.preform_stored, 0)
        wieldPreform()
    }

    private fun ProtectedAccess.clearPreformVars() {
        for (varbit in listOf(SgVarBits.temperature, SgVarBits.completion, SgVarBits.quality, SgVarBits.start_quality, SgVarBits.difficulty)) {
            setBit(varbit, 0)
        }
        for (seed in SgVarBits.seeds) setBit(seed, 0)
        setBit(SgVarBits.mould_forte, 0)
        setBit(SgVarBits.mould_blade, 0)
        setBit(SgVarBits.mould_tip, 0)
        setBit(SgVarBits.commission_1, 0)
        setBit(SgVarBits.commission_2, 0)
    }

    private fun ProtectedAccess.openHud() {
        if (!hudOpen.add(player.uid.packed)) return
        ifOpenOverlay(SgInterfaces.hud, components.toplevel_target_overlay_hud)
    }

    private fun ProtectedAccess.closeHud() {
        if (!hudOpen.remove(player.uid.packed)) return
        ifCloseSub(SgInterfaces.hud)
    }

    /* Refinement */

    /** The tool the current section needs (seed order: hammer, grind, polish). */
    private fun ProtectedAccess.requiredTool(): Tool {
        val sections = Tool.entries.size
        val section = (bit(SgVarBits.completion) * sections / COMPLETION_MAX).coerceIn(0, sections - 1)
        return Tool.entries[section]
    }

    /**
     * The tool's temperature window, as the HUD draws it (cs2 script 6121): a third of the bar,
     * narrowed by the difficulty down to half at 130, centred on cold / medium / hot.
     */
    private fun ProtectedAccess.window(tool: Tool): IntRange {
        val third = COMPLETION_MAX / 3
        val half = third - third / 2
        val width = third - bit(SgVarBits.difficulty) * half / 130
        val start = third / 2 + third * tool.band - width / 2
        return start..(start + width)
    }

    private suspend fun ProtectedAccess.work(tool: Tool) {
        openHud()
        if (bit(SgVarBits.completion) >= COMPLETION_MAX) {
            runQuestSection(null, dialogue, "Using the finished sword on any tool after its finished")
            return
        }
        while (true) {
            val required = requiredTool()
            if (tool != required) {
                damage()
                val section =
                    when {
                        required.ordinal < tool.ordinal && required == Tool.Hammer ->
                            "Using a preform that has not been completely hammered on the grindstone or polishing wheel"
                        required.ordinal < tool.ordinal ->
                            "Using a preform that has not been completely ground on the grindstone on the polishing wheel"
                        tool == Tool.Hammer -> "Using a preform on the trip hammer after its been hammered enough"
                        else -> "Using a preform on the grindstone after its been ground enough"
                    }
                kovacSection(section)
                return
            }
            val temp = bit(SgVarBits.temperature)
            val window = window(tool)
            if (temp !in window) {
                damage()
                val hot = temp > window.last
                val section =
                    when (tool) {
                        Tool.Hammer -> "Using a preform that is too cold on the trip hammer"
                        Tool.Grind ->
                            if (hot) "Using a preform that is too hot on the grindstone" else "Using a preform that is too cold on the grindstone"
                        Tool.Polish ->
                            if (hot) "Using a preform that is too hot on the polishing wheel"
                            else "Using a preform that is too cold on the polishing wheel"
                    }
                kovacSection(section)
                return
            }
            delay(tool.interval)
            if (!holdsPreform()) return
            val before = bit(SgVarBits.completion)
            val after = minOf(COMPLETION_MAX, before + tool.progress)
            setBit(SgVarBits.completion, after)
            setBit(SgVarBits.temperature, (temp + tool.heat).coerceIn(0, TEMP_MAX))
            if (after >= COMPLETION_MAX) {
                raiseTutorial(T_FINISHED)
                runQuestSection(null, dialogue, "After polishing it enough")
                return
            }
            if (requiredTool() != tool) {
                mes("That part of the sword is done; it needs a different tool now.") // Authored (not Wiki).
                return
            }
        }
    }

    private fun ProtectedAccess.damage() {
        // The tutorial sword is never junked: Kovac takes it at any quality (the hand-in's 0 branch).
        setBit(SgVarBits.quality, maxOf(0, bit(SgVarBits.quality) - DAMAGE))
    }

    private suspend fun ProtectedAccess.heat(rate: HeatRate) {
        if (!hasPreform()) {
            kovacSection(if (rate.step > 0) "Lava pool" else "Waterfall")
            return
        }
        if (!holdsPreform()) {
            mbox("You must free your hands so you can hold the preform.")
            return
        }
        openHud()
        var step = rate.step
        while (true) {
            delay(1)
            if (!holdsPreform()) return
            val temp = bit(SgVarBits.temperature)
            val next = (temp + step).coerceIn(0, TEMP_MAX)
            setBit(SgVarBits.temperature, next)
            if (next == 0 || next == TEMP_MAX) return
            step += rate.accel
        }
    }

    /* Scores */

    /** The Wiki's alloy formula over the two metals (tiers 1 and 2). */
    private fun metalScore(bronze: Int, iron: Int): Int {
        val v1 = 10.0 * 1 * bronze / CRUCIBLE_BARS
        val v2 = 10.0 * 2 * iron / CRUCIBLE_BARS
        return ((10 * v1).toInt() + (10 * v2).toInt() + (v1 * v2).toInt()) / 10
    }

    /** The selected parts' Broad + Flat scores (the tutorial commission). */
    private fun ProtectedAccess.mouldScore(): Int {
        val parts =
            listOfNotNull(
                Mould.fortes.firstOrNull { it.index == bit(SgVarBits.mould_forte) },
                Mould.blades.firstOrNull { it.index == bit(SgVarBits.mould_blade) },
                Mould.tips.firstOrNull { it.index == bit(SgVarBits.mould_tip) },
            )
        return parts.sumOf { it.broad + it.flat }
    }

    /* Data */

    private enum class Tool(
        val label: String,
        val seed: Int,
        val band: Int,
        val interval: Int,
        val progress: Int,
        val heat: Int,
    ) {
        // Wiki: hammer every 5th tick +2% progress, -2.5% heat, grindstone every 2nd tick +1%,
        // +1.5%, polishing wheel every 2nd tick +1%, -1.7% (bars of 1000).
        Hammer("trip hammer", seed = 0, band = 2, interval = 5, progress = 20, heat = -25),
        Grind("grindstone", seed = 1, band = 1, interval = 2, progress = 10, heat = 15),
        Polish("polishing wheel", seed = 2, band = 0, interval = 2, progress = 10, heat = -17);

        val repaired: VarBitType
            get() =
                when (this) {
                    Hammer -> SgVarBits.repair_hammer
                    Grind -> SgVarBits.repair_grind
                    Polish -> SgVarBits.repair_polish
                }
    }

    private class HeatRate(val step: Int, val accel: Int)

    private enum class Metal {
        Bronze,
        Iron;

        /** Bars and equipment with their bar value (Wiki: the smithing bars needed minus one). */
        fun items(): List<Pair<ObjType, Int>> =
            if (this == Bronze) SgObjs.bronze_items else SgObjs.iron_items
    }

    /**
     * A default mould part: its index in the part's enum (4373 forte, 4374 blade, 4375 tip) and its
     * cache struct scores (param 1625 broad +/narrow -, 1627 spiked +/flat -), here only as the
     * tutorial's Broad and Flat.
     */
    private class Mould(val index: Int, val name: String, broadNarrow: Int, spikedFlat: Int) {
        val broad = maxOf(0, broadNarrow)
        val flat = maxOf(0, -spikedFlat)

        fun label(): String = "$name (Broad $broad, Flat $flat)"

        companion object {
            val fortes =
                listOf(
                    Mould(11, "Chopper Forte", 4, -4),
                    Mould(10, "Gladius Ricasso", 4, -4),
                    Mould(9, "Disarming Forte", -4, 4),
                    Mould(8, "Medusa Ricasso", 8, -8),
                    Mould(7, "Serpent Ricasso", -6, -8),
                    Mould(6, "Serrated Forte", -8, 6),
                )
            val blades =
                listOf(
                    Mould(11, "Saw Blade", 4, 4),
                    Mould(10, "Defenders Edge", 4, 4),
                    Mould(9, "Fish Blade", -4, -4),
                    Mould(8, "Medusa Blade", 8, -6),
                    Mould(7, "Stiletto Blade", -8, -8),
                    Mould(6, "Gladius Edge", -6, -8),
                )
            val tips =
                listOf(
                    Mould(11, "People Poker Point", -4, -4),
                    Mould(10, "Chopper Tip", 4, 4),
                    Mould(9, "Medusa's Head", 4, 4),
                    Mould(8, "Serpent's Fang", -8, 8),
                    Mould(7, "Gladius Point", -8, -6),
                    Mould(6, "Saw Tip", 6, 8),
                )
        }
    }

    companion object {
        const val AGREED = 5
        const val INSIDE_STATE = 10
        const val REPAIRED = 20
        const val COMPLETE = 30

        private const val T_BRIEFED = 1
        private const val T_LOOTED = 15
        private const val T_CRUCIBLE_FULL = 16
        private const val T_MOULD_SET = 17
        private const val T_CHECKED = 18
        private const val T_POURED = 19
        private const val T_COOLED = 20
        private const val T_HELD = 21
        private const val T_EXPLAINED = 22
        private const val T_FINISHED = 23

        private const val CRUCIBLE_PARTIAL = 1
        private const val CRUCIBLE_FULL = 2
        private const val MOULD_SET = 1
        private const val MOULD_FILLED = 2
        private const val MOULD_COOLED = 3

        /** `giants_foundry_commission_word_1/2` values for Broad / Flat (first in each Wiki list). */
        private const val COMMISSION_BROAD = 1
        private const val COMMISSION_FLAT = 1

        private const val SMITHING_REQ = 15
        private const val SMITHING_XP = 6000.0 // Cache db stat_xp_awarded smithing 60000 (tenths); Wiki 6,000.
        private const val NAILS_PER_REPAIR = 5
        private const val CRATE_SLOTS = 20
        private const val CRUCIBLE_BARS = 28
        private const val COMPLETION_MAX = 1000
        private const val TEMP_MAX = 1000
        /** Metal scores 10-19 give 3 sections (cs2 script 6119). */
        private const val TUTORIAL_DIFFICULTY = 19
        private const val MAX_QUALITY = 199
        private const val DAMAGE = 10

        // Authored (the Wiki gives no numbers): per-tick heat change, accelerating while held there.
        private val LAVA_SLOW = HeatRate(step = 6, accel = 1)
        private val LAVA_FAST = HeatRate(step = 12, accel = 3)
        private val WATER_SLOW = HeatRate(step = -6, accel = -1)
        private val WATER_FAST = HeatRate(step = -12, accel = -3)

        /**
         * Authored crate contents (the Wiki only shows a bronze battleaxe and an iron bar): 20
         * bronze and 8 iron bars' worth in 18 slots, metal score 16 (lowest difficulty).
         */
        private val CRATE: List<Pair<() -> ObjType, Int>> =
            listOf(
                { SgObjs.bronze_bar } to 8,
                { SgObjs.bronze_platebody } to 2,
                { SgObjs.bronze_battleaxe } to 2,
                { SgObjs.iron_bar } to 4,
                { SgObjs.iron_battleaxe } to 2,
            )

        /** Positions from the rev 243 map: by the cave (44635 at 3359,3150, 5x2) and inside by the exit. */
        private val OUTSIDE = CoordGrid(3361, 3149, 0)
        private val INSIDE = CoordGrid(3366, 11485, 0)

        val SLEEPING_GIANTS =
            QuestDef(
                name = "Sleeping Giants",
                varbit = { SgVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("6,000 Smithing XP", "Access to the Giants' Foundry"),
                scrollObj = { SgObjs.preform },
            )
    }
}

// Every cache ref Sleeping Giants uses (re-check after cache updates).

internal object SgNpcs : NpcReferences() {
    /** Children of `giants_foundry_kovac_multi_outside` (quest 0-4 / 5-9). */
    val kovac_strike = find("giants_foundry_kovac_fake_attack")
    val kovac_outside = find("giants_foundry_kovac_quest")
    /** Children of `giants_foundry_kovac_multi` in the foundry (quest 0-29 / 30). */
    val kovac_1op = find("giants_foundry_kovac_1op")
    val kovac = find("giants_foundry_kovac")
}

internal object SgLocs : LocReferences() {
    val entrance = find("giants_foundry_entrance")
    val exit = find("giants_foundry_exit")
    /** The pile of buckets by the polishing wheel. */
    val buckets = find("my2arm_throne_room_buckets")
    val trip_hammer = find("giants_foundry_trip_hammer")
    val grindstone = find("giants_foundry_grindstone")
    val polishing_wheel = find("giants_foundry_polishing_wheel")
    /** Children of `giants_foundry_supply_box_multi` (tutorial 0-10 / 15+). */
    val supply_box = find("giants_foundry_supply_box")
    val supply_box_empty = find("giants_foundry_supply_box_empty")
    /** Children of `giants_foundry_crucible_multi` (crucible state 0/1/2). */
    val crucible_empty = find("giants_foundry_crucible_empty")
    val crucible_partial = find("giants_foundry_crucible_partial")
    val crucible_full = find("giants_foundry_crucible_full")
    val crucibles = listOf(crucible_empty, crucible_partial, crucible_full)
    /** Children of `giants_foundry_mould_jig` (mould state 0/1/2/3). */
    val jig_empty = find("giants_foundry_jig_empty")
    val jig_mould = find("giants_foundry_jig_mould")
    val jig_filled = find("giants_foundry_jig_mould_filled")
    val jig_cooled = find("giants_foundry_jig_mould_cooled")
    val lava_pool = find("giants_foundry_lava_pool")
    val waterfall = find("giants_foundry_waterfall")
    /** Children of `giants_foundry_preform_storage` (preform stored 0/1). */
    val storage_empty = find("giants_foundry_preform_storage_empty")
    val storage_full = find("giants_foundry_preform_storage_full")
}

internal object SgObjs : ObjReferences() {
    val preform = find("giants_foundry_preform")
    val bucket = find("bucket_empty")
    val bucket_water = find("bucket_water")
    val ice_gloves = find("ice_gloves")
    val hammer = find("hammer")
    val imcando_hammer = find("imcando_hammer")
    val chisel = find("chisel")
    val oak_logs = find("oak_logs")
    val wool = find("wool")
    val nails =
        listOf(
            find("nails_bronze"),
            find("nails_iron"),
            find("nails"),
            find("nails_black"),
            find("nails_mithril"),
            find("nails_adamant"),
            find("nails_rune"),
        )
    val bronze_bar = find("bronze_bar")
    val iron_bar = find("iron_bar")
    val bronze_platebody = find("bronze_platebody")
    val bronze_battleaxe = find("bronze_battleaxe")
    val iron_battleaxe = find("iron_battleaxe")
    val bronze_items: List<Pair<ObjType, Int>> =
        listOf(
            bronze_bar to 1,
            find("bronze_scimitar") to 1,
            find("bronze_longsword") to 1,
            find("bronze_full_helm") to 1,
            find("bronze_sq_shield") to 1,
            find("bronze_claws") to 1,
            find("bronze_warhammer") to 2,
            bronze_battleaxe to 2,
            find("bronze_chainbody") to 2,
            find("bronze_kiteshield") to 2,
            find("bronze_2h_sword") to 2,
            find("bronze_platelegs") to 2,
            find("bronze_plateskirt") to 2,
            bronze_platebody to 4,
        )
    val iron_items: List<Pair<ObjType, Int>> =
        listOf(
            iron_bar to 1,
            find("iron_scimitar") to 1,
            find("iron_longsword") to 1,
            find("iron_full_helm") to 1,
            find("iron_sq_shield") to 1,
            find("iron_claws") to 1,
            find("iron_warhammer") to 2,
            iron_battleaxe to 2,
            find("iron_chainbody") to 2,
            find("iron_kiteshield") to 2,
            find("iron_2h_sword") to 2,
            find("iron_platelegs") to 2,
            find("iron_plateskirt") to 2,
            find("iron_platebody") to 4,
        )
}

internal object SgInterfaces : InterfaceReferences() {
    val hud = find("giants_foundry_hud")
}

internal object SgVarBits : VarBitReferences() {
    val quest = find("sleeping_giants")
    val tutorial = find("sleeping_giants_tutorial")
    val repair_hammer = find("sleeping_giants_repair_hammer")
    val repair_grind = find("sleeping_giants_repair_grind")
    val repair_polish = find("sleeping_giants_repair_polish")
    val commission_1 = find("giants_foundry_commission_word_1")
    val commission_2 = find("giants_foundry_commission_word_2")
    val mould_forte = find("giants_foundry_mould_selected_ricasso")
    val mould_blade = find("giants_foundry_mould_selected_blade")
    val mould_tip = find("giants_foundry_mould_selected_tip")
    val mould_state = find("giants_foundry_mould_state")
    val bronze = find("giants_foundry_bronze_count")
    val iron = find("giants_foundry_iron_count")
    val crucible_state = find("giants_foundry_crucible_state")
    val difficulty = find("giants_foundry_preform_dificulty")
    val quality = find("giants_foundry_preform_quality")
    val start_quality = find("giants_foundry_preform_start_quality")
    /** Section tools for the HUD (cs2 script 6118 reads seed_1..seed_6 for sections 0-5). */
    val seeds =
        listOf(
            find("giants_foundry_seed_1"),
            find("giants_foundry_seed_2"),
            find("giants_foundry_seed_3"),
            find("giants_foundry_seed_4"),
            find("giants_foundry_seed_5"),
            find("giants_foundry_seed_6"),
        )
    val preform_stored = find("giants_foundry_preform_stored")
    val temperature = find("giants_foundry_preform_temperature")
    val completion = find("giants_foundry_preform_completion")
}
