package org.rsmod.content.quests.dwarfcannon

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onIfModalButtonT
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Dwarf Cannon (OSRS Wiki). Cache quest varp `mcannon`, end state 11 (the cache shows the remains
 * in the watchtower at 3 and the fixed cannon from 8):
 * - 1 Lawgof's six railings (Inspect a broken railing with a railing and hammer; fails hurt for
 *   0-2), 2 all fixed, 3 sent to the watchtower, 4 Gilob's remains taken, 5 remains given (find
 *   Lollk), 6 Lollk freed from his crate in the Goblin Cave, 7 toolkit given, 8 cannon fixed on the
 *   toolkit interface (pliers on the safety switch, hook on the spring, toothed tool on the gear),
 *   9 sent to Nulodion, 10 ammo mould and notes, 11 complete: 750 Crafting xp, 1 QP, and the right
 *   to buy a cannon from Nulodion (who also replaces lost ones).
 *
 * The fixed cannon at the outpost is a dwarf_multicannon1, whose ops the multicannon module owns.
 */
class DwarfCannon
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DwarfCannon::class.java, "dwarf_cannon") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Captain Lawgof" to DcNpcs.lawgof,
            "Nulodion" to DcNpcs.nulodion,
            "Lollk" to DcNpcs.lollk,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, DcNpcs.lawgof, "Talk-to") { talkLawgof(it) }
        onNpcOpLabel(npcTypes, DcNpcs.nulodion, "Talk-to") { talkNulodion(it) }
        for ((index, railing) in DcLocs.railings.withIndex()) {
            onLocOpLabel(locTypes, railing, "Inspect") { fixRailing(index) }
        }
        onLocOpLabel(locTypes, DcLocs.railing_fixed, "Inspect") {
            runQuestSection(null, dialogue, FIXED_RAILING)
        }
        onLocOpLabel(locTypes, DcLocs.broken_cannon, "Inspect") { inspectCannon() }
        onOpLocU(DcLocs.broken_cannon, DcObjs.toolkit) { useToolkit() }
        onLocOpLabel(locTypes, DcLocs.remains, "Take") { takeRemains() }
        onLocOpLabel(locTypes, DcLocs.crate, "Search") { searchCrate() }
        onLocOpLabel(locTypes, DcLocs.cave, "Enter") { telejump(CAVE_INSIDE) }
        onLocOpLabel(locTypes, DcLocs.mud_pile, "Climb-over") { telejump(CAVE_OUTSIDE) }
        onLocOpLabel(locTypes, DcLocs.tower_ladder, "Climb-up") { telejump(TOWER_TOP) }

        for ((tool, part) in TOOL_PARTS) {
            onIfModalButtonT(tool, part) { applyTool(tool, part) }
        }
        for (tool in DcComponents.tools) {
            onIfModalButton(tool) { selectTool(tool) }
        }
        for (part in DcComponents.parts) {
            onIfModalButton(part) { applySelected(part) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(DWARF_CANNON)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(DWARF_CANNON, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Boolean = player.vars[varbit] != 0

    private fun ProtectedAccess.setBit(varbit: VarBitType, on: Boolean) {
        VarPlayerIntMapSetter.set(player, varbit, if (on) 1 else 0)
    }

    /* Captain Lawgof */

    private suspend fun ProtectedAccess.talkLawgof(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val noHammer = !carries(DcObjs.hammer)
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player does not have a hammer:" to { noHammer }),
                        options = mapOf("Yes." to { start = true }),
                    )
                runQuestSection(npc, dialogue, "Captain Lawgof", hooks, speakers)
                if (start) {
                    invAddOrDrop(objRepo, DcObjs.railing, RAILINGS)
                    if (noHammer) invAddOrDrop(objRepo, DcObjs.hammer)
                    setState(STARTED)
                }
            }
            STARTED -> {
                val none = !carries(DcObjs.railing)
                val full = inv.isFull()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has no railings:" to { none },
                                "If the player has no inventory space:" to { none && full },
                                "If the player has at least 1 railing:" to { !none },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Captain Lawgof before fixing the railings",
                    hooks,
                    speakers,
                )
                if (none && !full) {
                    invAdd(inv, DcObjs.railing, RAILINGS - fixedRailings())
                }
            }
            RAILINGS_FIXED -> {
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Captain Lawgof after fixing the railings",
                    hooks(),
                    speakers,
                )
                setState(SENT_TO_TOWER)
            }
            SENT_TO_TOWER ->
                runQuestSection(npc, dialogue, LAWGOF_NO_TOWER, hooks(), speakers)
            REMAINS_TAKEN -> {
                if (!carries(DcObjs.remains)) {
                    runQuestSection(npc, dialogue, LAWGOF_LOST_REMAINS, hooks(), speakers)
                    setState(SENT_TO_TOWER)
                    return
                }
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Captain Lawgof after getting the remains",
                    hooks(),
                    speakers,
                )
                invDel(inv, DcObjs.remains)
                setState(REMAINS_GIVEN)
            }
            REMAINS_GIVEN ->
                startDialogue(npc) {
                    chatNpc(
                        sad,
                        "Please, find Lollk. The goblins must have taken him to their cave.",
                    )
                }
            LOLLK_FOUND -> {
                var agree = false
                val full = inv.isFull()
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player has no inventory space:" to { full }),
                        options = mapOf("Okay, I'll see what I can do." to { agree = true }),
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Captain Lawgof after finding Lollk",
                    hooks,
                    speakers,
                )
                if (agree && !full) {
                    invAdd(inv, DcObjs.toolkit)
                    setState(TOOLKIT)
                }
            }
            TOOLKIT -> {
                val lost = !carries(DcObjs.toolkit)
                val full = inv.isFull()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player lost the toolkit:" to { lost },
                                "If the player has no inventory space:" to { full },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Captain Lawgof before fixing the cannon",
                    hooks,
                    speakers,
                )
                if (lost && !full) invAdd(inv, DcObjs.toolkit)
            }
            CANNON_FIXED -> {
                var agree = false
                val hooks =
                    SimpleQuestHooks(
                        options = mapOf("Okay then, just for you!" to { agree = true }),
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Captain Lawgof after fixing the cannon",
                    hooks,
                    speakers,
                )
                if (agree) setState(SENT_TO_NULODION)
            }
            SENT_TO_NULODION ->
                runQuestSection(
                    npc,
                    dialogue,
                    LAWGOF_BEFORE_NULODION,
                    hooks(),
                    speakers,
                )
            MOULD_GIVEN -> {
                val has = carries(DcObjs.ammo_mould) && carries(DcObjs.notes)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have the notes and ammo mould:" to { !has },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Lawgof after getting the ammo mould",
                    hooks,
                    speakers,
                )
                if (!has) return
                invDel(inv, DcObjs.notes)
                statAdvance(stats.crafting, CRAFTING_XP * xpMods.get(player, stats.crafting))
                completeQuest(DWARF_CANNON)
            }
            else ->
                startDialogue(npc) {
                    chatNpc(
                        happy,
                        "Hello trooper! Thanks to you, the goblins don't stand a chance.",
                    )
                }
        }
    }

    private fun hooks(): SimpleQuestHooks = SimpleQuestHooks()

    /* The railings */

    private fun ProtectedAccess.fixedRailings(): Int = DcVarBits.railings.count { bit(it) }

    private suspend fun ProtectedAccess.fixRailing(index: Int) {
        if (state != STARTED || bit(DcVarBits.railings[index])) {
            runQuestSection(null, dialogue, FIXED_RAILING)
            return
        }
        val railing = carries(DcObjs.railing)
        val hammer = carries(DcObjs.hammer)
        val fails = random.of(1, FAIL_ODDS) == 1
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have a railing:" to { !railing },
                        "If the player does not have a hammer:" to { railing && !hammer },
                        "If the player fails to repair the railing:" to { false },
                        "If the player successfully repairs the railing:" to { false },
                        "If all the railing are fixed:" to { false },
                    )
            )
        runQuestSection(null, dialogue, "Trying to fix a railing", hooks)
        if (!railing) return
        if (!hammer) {
            mes("You need a hammer to fix the railing.")
            return
        }
        anim(DcSeqs.hammer)
        delay(2)
        if (fails) {
            val damage = random.of(0, 2)
            if (damage > 0) player.queueHit(delay = 0, type = HitType.Typeless, damage = damage)
            startDialogue { chatPlayer(angry, OUCHES[random.of(0, OUCHES.size - 1)]) }
            return
        }
        invDel(inv, DcObjs.railing)
        setBit(DcVarBits.railings[index], true)
        mes("You fit the new railing.")
        if (fixedRailings() == RAILINGS) {
            startDialogue { chatPlayer(happy, "I've fixed all these railings now.") }
            setState(RAILINGS_FIXED)
        }
    }

    /* The watchtower and the Goblin Cave */

    private suspend fun ProtectedAccess.takeRemains() {
        val has = carries(DcObjs.remains)
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player already has the remains:" to { has }),
            )
        runQuestSection(null, dialogue, "Picking up the remains", hooks)
        if (has || state != SENT_TO_TOWER) return
        invAddOrDrop(objRepo, DcObjs.remains)
        setState(REMAINS_TAKEN)
    }

    private suspend fun ProtectedAccess.searchCrate() {
        when {
            state < REMAINS_GIVEN ->
                runQuestSection(
                    null,
                    dialogue,
                    "Searching the crate containing Lollk before returning the remains",
                )
            state == REMAINS_GIVEN -> {
                runQuestSection(
                    null,
                    dialogue,
                    "Searching the crate containing Lollk",
                    hooks(),
                    speakers,
                )
                setState(LOLLK_FOUND)
            }
            else -> mes("The crate is empty.")
        }
    }

    /* The cannon and its toolkit */

    private suspend fun ProtectedAccess.inspectCannon() {
        if (state == TOOLKIT && carries(DcObjs.toolkit)) {
            runQuestSection(
                null,
                dialogue,
                "Inspecting the broken multicannon after getting the toolkit",
            )
            return
        }
        runQuestSection(null, dialogue, "Inspecting the broken multicannon")
    }

    private fun ProtectedAccess.useToolkit() {
        if (state != TOOLKIT) {
            mes("Nothing interesting happens.")
            return
        }
        for (tool in DcVarBits.tools) setBit(tool, false)
        ifOpenMainModal(DcInterfaces.toolkit)
    }

    /** The tool the player picked up by clicking it (the cache tracks it in mcannonmulti bits). */
    private fun ProtectedAccess.selectTool(tool: ComponentType) {
        val index = DcComponents.tools.indexOf(tool)
        for ((i, bit) in DcVarBits.tools.withIndex()) setBit(bit, i == index)
    }

    private fun ProtectedAccess.applySelected(part: ComponentType) {
        val index = DcVarBits.tools.indexOfFirst { bit(it) }
        if (index == -1) {
            mes("You need to pick a tool from the toolkit first.")
            return
        }
        applyTool(DcComponents.tools[index], part)
    }

    private fun ProtectedAccess.applyTool(tool: ComponentType, part: ComponentType) {
        if (state != TOOLKIT) return
        for (bit in DcVarBits.tools) setBit(bit, false)
        val safety = bit(DcVarBits.safety)
        val spring = bit(DcVarBits.spring)
        when {
            tool == DcComponents.pliers && part == DcComponents.safety && !safety -> {
                setBit(DcVarBits.safety, true)
                mes("You use the pliers to set the safety switch.")
            }
            tool == DcComponents.hook && part == DcComponents.spring && safety && !spring -> {
                setBit(DcVarBits.spring, true)
                mes("You use the hooked tool to pull the spring into place.")
            }
            tool == DcComponents.toothed && part == DcComponents.gear && safety && spring -> {
                mes("You use the toothed tool to turn the gear... the cannon is fixed!")
                ifClose()
                setState(CANNON_FIXED)
            }
            else -> mes("That doesn't seem to work.")
        }
    }

    /* Nulodion */

    private suspend fun ProtectedAccess.talkNulodion(npc: Npc) {
        when {
            state < SENT_TO_NULODION ->
                startDialogue(npc) { chatNpc(neutral, "Can I help you? I'm rather busy.") }
            state == SENT_TO_NULODION -> {
                if (inv.freeSpace() < 2) {
                    val hooks =
                        SimpleQuestHooks(
                            conditions = mapOf("If the player has no inventory space:" to { true })
                        )
                    runQuestSection(npc, dialogue, "Talking to Nulodion", hooks, speakers)
                    return
                }
                runQuestSection(npc, dialogue, "Talking to Nulodion", hooks(), speakers)
                invAdd(inv, DcObjs.ammo_mould)
                invAdd(inv, DcObjs.notes)
                setState(MOULD_GIVEN)
            }
            state == MOULD_GIVEN -> {
                val noNotes = !carries(DcObjs.notes)
                val noMould = !carries(DcObjs.ammo_mould)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player lost the notes:" to { noNotes },
                                "If the player lost the ammo mould:" to { noMould },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Nulodion again", hooks, speakers)
                if (noNotes) invAddOrDrop(objRepo, DcObjs.notes)
                if (noMould) invAddOrDrop(objRepo, DcObjs.ammo_mould)
            }
            else -> nulodionAfter(npc)
        }
    }

    private suspend fun ProtectedAccess.nulodionAfter(npc: Npc) {
        val lost = player.vars[DcVarps.cannon_state] and CANNON_LOST != 0
        startDialogue(npc) {
            chatNpc(happy, "Hello again, adventurer. Here to buy a cannon?")
            val choice =
                if (lost) {
                    choice3(
                        "I'd like the full cannon set.",
                        1,
                        "I've lost my cannon.",
                        2,
                        "No, thanks.",
                        3,
                    )
                } else {
                    choice2("I'd like the full cannon set.", 1, "No, thanks.", 3)
                }
            when (choice) {
                1 -> buySet()
                2 -> replaceCannon()
                else -> chatPlayer(neutral, "No, thanks.")
            }
        }
    }

    private suspend fun Dialogue.buySet() {
        chatNpc(
            neutral,
            "The full set is 750,000 coins: base, stand, barrels, furnace, mould and manual.",
        )
        with(access) {
            val need = DcObjs.cannon_parts.size + 2
            when {
                !carries(DcObjs.coins, SET_PRICE) -> chatNpc(sad, "You don't have enough coins.")
                inv.freeSpace(
                    ) < need -> chatNpc(neutral,
                    "You'll need $need free spaces for the set.",
                )
                else -> {
                    invDel(inv, DcObjs.coins, SET_PRICE)
                    for (part in DcObjs.cannon_parts) invAdd(inv, part)
                    invAdd(inv, DcObjs.ammo_mould)
                    invAdd(inv, DcObjs.manual)
                    chatNpc(happy, "There you go. Look after it!")
                }
            }
        }
    }

    private suspend fun Dialogue.replaceCannon() {
        with(access) {
            val need = DcObjs.cannon_parts.size
            if (inv.freeSpace() < need) {
                chatNpc(neutral, "You'll need $need free spaces for a replacement.")
                return
            }
            for (part in DcObjs.cannon_parts) invAdd(inv, part)
            VarPlayerIntMapSetter.set(player, DcVarps.cannon_state, 0)
            chatNpc(happy, "Here's a replacement. Try not to lose this one!")
        }
    }

    companion object {
        const val STARTED = 1
        const val RAILINGS_FIXED = 2
        const val SENT_TO_TOWER = 3
        const val REMAINS_TAKEN = 4
        const val REMAINS_GIVEN = 5
        const val LOLLK_FOUND = 6
        const val TOOLKIT = 7
        const val CANNON_FIXED = 8
        const val SENT_TO_NULODION = 9
        const val MOULD_GIVEN = 10
        const val COMPLETE = 11

        private const val RAILINGS = 6
        private const val FAIL_ODDS = 3
        private const val CRAFTING_XP = 750.0
        private const val SET_PRICE = 750_000
        private const val CANNON_LOST = 2

        private val OUCHES = listOf("Oooch!", "Ow!", "Urrrgh!", "Gah!")

        private val CAVE_INSIDE = CoordGrid(2621, 9797, 0)
        private val CAVE_OUTSIDE = CoordGrid(2622, 3391, 0)
        private val TOWER_TOP = CoordGrid(2570, 3444, 2)

        private const val FIXED_RAILING =
            "Inspecting a fixed railing or using a railing on a fixed railing"
        private const val LAWGOF_NO_TOWER =
            "Talking to Captain Lawgof again without visiting the watchtower"
        private const val LAWGOF_LOST_REMAINS =
            "Talking to Captain Lawgof after picking up and losing the remains"
        private const val LAWGOF_BEFORE_NULODION =
            "Talking to Captain Lawgof again after agreeing to see Nulodion"

        private val TOOL_PARTS: List<Pair<ComponentType, ComponentType>> by lazy {
            DcComponents.tools.flatMap { tool -> DcComponents.parts.map { tool to it } }
        }

        val DWARF_CANNON =
            QuestDef(
                name = "Dwarf Cannon",
                varp = { DcVarps.mcannon },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("750 Crafting XP", "Can buy a dwarf multicannon"),
                scrollObj = { DcObjs.cannon_parts.first() },
            )
    }
}

// Every cache ref Dwarf Cannon uses (re-check after cache updates).

internal object DcNpcs : NpcReferences() {
    val lawgof = find("lawgof2")
    val nulodion = find("nulodion")
    val lollk = find("dwarfchildtw1")
}

internal object DcLocs : LocReferences() {
    /** Children of mcannon_railing1-6_multiloc (fixed child mcannon_dwarf_railing_fixed). */
    val railings: List<LocType> = (1..6).map { find("mcannonrailing$it") }
    val railing_fixed = find("mcannon_dwarf_railing_fixed")

    /** Children of mcannon_cannon_multiloc (fixed child dwarf_multicannon1 from 8). */
    val broken_cannon = find("broken_multicannon")

    /** Child of mcannonremains_multiloc, shown at state 3. */
    val remains = find("mcannonremains_location")
    val crate = find("mcannoncrateboy")
    val cave = find("mcannoncave")
    val mud_pile = find("mcanmudpile")
    val tower_ladder = find("mcannonladder")
}

internal object DcObjs : ObjReferences() {
    val railing = find("mcannonrailing1_obj")
    val hammer = find("hammer")
    val remains = find("mcannonremains")
    val toolkit = find("mcannontoolkit")
    val ammo_mould = find("ammo_mould")
    val notes = find("nulodions_notes")
    val manual = find("mcannonbook")
    val coins = find("coins")
    val cannon_parts = listOf(find("twpart1"), find("twpart2"), find("twpart3"), find("twpart4"))
}

internal object DcVarps : VarpReferences() {
    val mcannon = find("mcannon")

    /** The multicannon module's server varp (bit 1: cannon lost). */
    val cannon_state = find("mcannon_state")
}

internal object DcVarBits : VarBitReferences() {
    val railings = (1..6).map { find("mcannon_railing${it}_fixed") }
    val tools = (1..3).map { find("mcannonmulti_tool$it") }
    val safety = find("mcannon_safety_on")
    val spring = find("mcannon_spring_set")
}

internal object DcInterfaces : InterfaceReferences() {
    val toolkit = find("mcannon_interface")
}

internal object DcComponents : ComponentReferences() {
    /** Toothed tool, pliers, hooked tool (OSRS Wiki toolkit order). */
    val toothed = find("mcannon_interface:mcannon_tool1")
    val pliers = find("mcannon_interface:mcannon_tool2")
    val hook = find("mcannon_interface:mcannon_tool3")
    val tools = listOf(toothed, pliers, hook)
    val safety = find("mcannon_interface:mcannon_safety")
    val spring = find("mcannon_interface:mcannon_spring")
    val gear = find("mcannon_interface:mcannon_gear")
    val parts = listOf(safety, spring, gear)
}

internal object DcSeqs : SeqReferences() {
    val hammer = find("human_smithing")
}
