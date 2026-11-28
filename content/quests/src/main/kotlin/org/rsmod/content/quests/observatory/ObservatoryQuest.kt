package org.rsmod.content.quests.observatory

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Observatory Quest (OSRS Wiki). Cache quest varp `itgronigen`, end state 7:
 * - 1 the Observatory professor (north of Castle Wars) needs three planks; 2 a bronze bar;
 *   3 molten glass; 4 the lens mould, in the goblins' kitchen stove in the dungeon below (the
 *   kitchen key is in one of the keep's chests, chosen per player in cache varbit
 *   observatory_chestchoice; the sleeping guard at the kitchen gate must be woken and beaten);
 *   5 molten glass on the mould makes the lens (10 Crafting); 6 the professor fits it and you
 *   look through the telescope (a random star sign, cache varbit observatory_starsign); 7 naming
 *   it: 2 QP, 2,250 Crafting xp and the sign's reward.
 * - Assistant chat is the Wiki's per-stage sections; his random "Dialogue N" lines are a menu here.
 *
 * Simplified: the telescope view is a message, the stairs between the grounds and the dungeon are
 * telejumps, and the keep chests' spiders/antipoison are left out (the others are just empty).
 */
class ObservatoryQuest
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
    private val dialogue by lazy { QuestDialogue.load(ObservatoryQuest::class.java, "observatory") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, ObNpcs.professor, OBSERVATORY_QUEST, wants = { state < COMPLETE }) {
                talkProfessor(it)
            }
            register(npcTypes, ObNpcs.assistant, OBSERVATORY_QUEST, wants = { state in STARTED until COMPLETE }) {
                talkAssistant(it)
            }
        }
        onNpcOpLabel(npcTypes, ObNpcs.sleeping_guard, "Prod") { prodGuard(it) }
        onLocOpLabel(locTypes, ObLocs.kitchen_gate, "Open") { kitchenGate(it) }
        onLocOpLabel(locTypes, ObLocs.stove, "Inspect") { inspectStove() }
        for (chest in ObLocs.keep_chests) onLocOpLabel(locTypes, chest, "Open") { searchKeepChest(it) }
        onLocOpLabel(locTypes, ObLocs.keep_chest_open, "Search") { searchKeepChest(it) }
        onLocOpLabel(locTypes, ObLocs.stairs_down_north, "Climb-down") { telejump(DUNGEON_NORTH) }
        onLocOpLabel(locTypes, ObLocs.stairs_down_south, "Climb-down") { telejump(DUNGEON_SOUTH) }
        onLocOpLabel(locTypes, ObLocs.stairs_up_dungeon, "Climb up") { climbOut(it) }
        onOpHeldU(ObObjs.molten_glass, ObObjs.mould) { makeLens() }
        onOpLocU(ObLocs.telescope, ObObjs.lens) { mes("The professor will need to fit that.") }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(OBSERVATORY_QUEST)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(OBSERVATORY_QUEST, value)
    }

    private fun ProtectedAccess.setBit(bit: org.rsmod.game.type.varbit.VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, bit, value)
    }

    /* The professor */

    private suspend fun ProtectedAccess.talkProfessor(npc: Npc) {
        when (state) {
            0 -> {
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                runQuestSection(npc, dialogue, "Starting off", hooks)
            }
            STARTED -> deliver(npc, ObObjs.plank, PLANKS, "Returning with three planks", "Returning with no planks", BAR)
            BAR -> deliver(npc, ObObjs.bronze_bar, 1, "Returning with a bronze bar", "Returning without a bronze bar", GLASS)
            GLASS -> deliver(npc, ObObjs.molten_glass, 1, "Returning with molten glass", "Returning without molten glass", MOULD)
            MOULD ->
                if (carries(ObObjs.mould)) {
                    runQuestSection(npc, dialogue, "Returning with the lens mould")
                    setState(LENS)
                } else {
                    runQuestSection(npc, dialogue, "Returning without the lens mould")
                }
            LENS ->
                if (carries(ObObjs.lens)) {
                    runQuestSection(npc, dialogue, "Returning with the observatory lens")
                    invDel(inv, ObObjs.lens)
                    setBit(ObVarBits.starsign, random.of(1, SIGNS.size))
                    setState(TELESCOPE)
                } else {
                    runQuestSection(npc, dialogue, "Returning without the observatory lens")
                }
            TELESCOPE -> telescope(npc)
        }
    }

    private suspend fun ProtectedAccess.deliver(
        npc: Npc,
        obj: ObjType,
        count: Int,
        withSection: String,
        withoutSection: String,
        next: Int,
    ) {
        if (invTotal(inv, obj) >= count) {
            runQuestSection(npc, dialogue, withSection)
            invDel(inv, obj, count)
            setState(next)
            return
        }
        val section =
            if (obj == ObObjs.plank && invTotal(inv, obj) > 0) "Returning without enough planks" else withoutSection
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.telescope(npc: Npc) {
        val sign = player.vars[ObVarBits.starsign].coerceIn(1, SIGNS.size)
        if (player.vars[ObVarBits.scope_looked] == 0) {
            runQuestSection(npc, dialogue, "Talking to the professor")
            // Authored line (not Wiki): the telescope's view is an interface in OSRS.
            mes("You look through the telescope and see the constellation of ${SIGNS[sign - 1]}.")
            setBit(ObVarBits.scope_looked, 1)
            return
        }
        var named = ""
        val hooks =
            SimpleQuestHooks(
                defaultCondition = { text ->
                    when {
                        text.startsWith("If the player did see ") -> text.removePrefix("If the player did see ").removeSuffix(":") == SIGNS[sign - 1]
                        text == "If the player did not see this sign:" -> named != SIGNS[sign - 1]
                        text == "If the player has inventory space for the reward:" -> inv.freeSpace() > 0
                        text == "If the player does not have inventory space for the reward:" -> inv.freeSpace() == 0
                        else -> false
                    }
                },
                options = SIGNS.associateWith<String, suspend () -> Unit> { name -> { named = name } },
            )
        runQuestSection(npc, dialogue, "After viewing the telescope", hooks)
        if (named == SIGNS[sign - 1] && inv.freeSpace() > 0) {
            signReward(sign - 1)
            statAdvance(stats.crafting, 2250.0 * xpMods.get(player, stats.crafting))
            completeQuest(OBSERVATORY_QUEST)
        }
    }

    private fun ProtectedAccess.signReward(index: Int) {
        when (SIGNS[index]) {
            "Aquarius" -> invAddOrDrop(objRepo, ObObjs.water_rune, 25)
            "Capricorn" -> statAdvance(stats.strength, 875.0 * xpMods.get(player, stats.strength))
            "Sagittarius" -> invAddOrDrop(objRepo, ObObjs.maple_longbow)
            "Scorpio" -> invAddOrDrop(objRepo, ObObjs.weapon_poison)
            "Libra" -> invAddOrDrop(objRepo, ObObjs.law_rune, 3)
            "Virgo" -> statAdvance(stats.defence, 875.0 * xpMods.get(player, stats.defence))
            "Leo" -> statAdvance(stats.hitpoints, 875.0 * xpMods.get(player, stats.hitpoints))
            "Cancer" -> invAddOrDrop(objRepo, ObObjs.amulet_defence)
            "Gemini" -> invAddOrDrop(objRepo, ObObjs.black_2h)
            "Taurus" -> invAddOrDrop(objRepo, ObObjs.super_strength)
            "Aries" -> statAdvance(stats.attack, 875.0 * xpMods.get(player, stats.attack))
            "Pisces" -> invAddOrDrop(objRepo, ObObjs.tuna, 3)
        }
    }

    /* The assistant */

    private suspend fun ProtectedAccess.talkAssistant(npc: Npc) {
        val section =
            when (state) {
                STARTED ->
                    when {
                        invTotal(inv, ObObjs.plank) >= PLANKS -> "Talking to the assistant with three planks"
                        invTotal(inv, ObObjs.plank) > 0 -> "Talking to the assistant with less than three planks"
                        ObObjs.other_planks.any { carries(it) } ->
                            "Talking to the assistant with less than three not normal planks"
                        else -> "Talking to the assistant without any planks"
                    }
                BAR ->
                    if (carries(ObObjs.bronze_bar)) "Talking to the assistant with a bronze bar"
                    else "Talking to the assistant without a bronze bar"
                GLASS ->
                    if (carries(ObObjs.molten_glass)) "Talking to the assistant with molten glass"
                    else "Talking to the assistant without molten glass"
                MOULD ->
                    when {
                        carries(ObObjs.mould) -> "Talking to the assistant with the lens mould"
                        carries(ObObjs.key) -> "Speaking to the observatory assistant with the goblin kitchen key"
                        else -> "Talking to the assistant without the lens mould"
                    }
                LENS ->
                    if (carries(ObObjs.lens)) "Talking to the assistant with the observatory lens"
                    else "Talking to the assistant without the observatory lens"
                else -> "Talking to the assistant before going up to the observatory"
            }
        runQuestSection(npc, dialogue, section)
    }

    /* The dungeon */

    private suspend fun ProtectedAccess.searchKeepChest(chest: BoundLocInfo) {
        if (state != MOULD) {
            runQuestSection(null, dialogue, "Searching an empty chest")
            return
        }
        if (player.vars[ObVarBits.chest_choice] == 0) setBit(ObVarBits.chest_choice, random.of(1, KEY_CHEST_CHOICES))
        val index = KEEP_CHESTS.indexOfFirst { it.chebyshevDistance(chest.coords) <= 1 }
        val keyChest = (player.vars[ObVarBits.chest_choice] - 1) % KEEP_CHESTS.size
        when {
            index != keyChest -> runQuestSection(null, dialogue, "Searching an empty chest")
            carries(ObObjs.key) -> runQuestSection(null, dialogue, "Searching the key chest with one in the inventory")
            else -> {
                runQuestSection(null, dialogue, "Searching the key chest")
                invAddOrDrop(objRepo, ObObjs.key)
            }
        }
    }

    private suspend fun ProtectedAccess.prodGuard(npc: Npc) {
        runQuestSection(npc, dialogue, "Prodding the goblin guard")
        val guard = Npc(npcTypes[ObNpcs.goblin_guard], npc.coords)
        npcRepo.hide(npc, GUARD_TICKS)
        npcRepo.add(guard, GUARD_TICKS)
        guard.opPlayer2(player, aiInteractions)
        setBit(ObVarBits.gate_lock, 1)
    }

    private suspend fun ProtectedAccess.kitchenGate(gate: BoundLocInfo) {
        // The kitchen (and its stove, 2326,9389) lies south of the gate.
        val inside = player.coords.z < gate.coords.z
        if (inside) {
            passThroughWall(gate)
            return
        }
        if (!carries(ObObjs.key)) {
            mes("The kitchen gate is locked.")
            return
        }
        if (player.vars[ObVarBits.gate_lock] == 0) {
            runQuestSection(null, dialogue, "Attempting to unlock the kitchen gate without waking the goblin guard")
            return
        }
        runQuestSection(null, dialogue, "Unlocking the kitchen gate")
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.inspectStove() {
        if (state != MOULD || carries(ObObjs.mould) || player.vars[ObVarBits.mould_taken] == 1) {
            runQuestSection(null, dialogue, "Inspecting the goblin stove")
            return
        }
        runQuestSection(null, dialogue, "Inspecting the goblin stove (2)")
        invAddOrDrop(objRepo, ObObjs.mould)
        setBit(ObVarBits.mould_taken, 1)
    }

    private suspend fun ProtectedAccess.climbOut(stairs: BoundLocInfo) {
        telejump(if (stairs.coords.z > DUNGEON_SPLIT_Z) GROUNDS_NORTH else GROUNDS_SOUTH)
    }

    private suspend fun ProtectedAccess.makeLens() {
        if (statBase(stats.crafting) < LENS_LEVEL) {
            mes("You need a Crafting level of $LENS_LEVEL to make the lens.")
            return
        }
        invDel(inv, ObObjs.molten_glass)
        invAdd(inv, ObObjs.lens)
        runQuestSection(null, dialogue, "Making the observatory lens")
    }

    companion object {
        const val STARTED = 1
        const val BAR = 2
        const val GLASS = 3
        const val MOULD = 4
        const val LENS = 5
        const val TELESCOPE = 6
        const val COMPLETE = 7

        private const val PLANKS = 3
        private const val LENS_LEVEL = 10
        private const val GUARD_TICKS = 300
        private const val KEY_CHEST_CHOICES = 3
        private const val DUNGEON_SPLIT_Z = 9380

        private val SIGNS =
            listOf(
                "Aquarius", "Capricorn", "Sagittarius", "Scorpio", "Libra", "Virgo",
                "Leo", "Cancer", "Gemini", "Taurus", "Aries", "Pisces",
            )

        /** The keep chests (qip_obs_keep_chest_closed). */
        private val KEEP_CHESTS =
            listOf(
                CoordGrid(2310, 9374, 0),
                CoordGrid(2312, 9400, 0),
                CoordGrid(2326, 9360, 0),
                CoordGrid(2333, 9405, 0),
                CoordGrid(2335, 9374, 0),
                CoordGrid(2348, 9383, 0),
                CoordGrid(2356, 9380, 0),
            )

        /** The grounds' two stairs down (2458,3186 and 2438,3164) and their dungeon ends. */
        private val DUNGEON_NORTH = CoordGrid(2355, 9394, 0)
        private val DUNGEON_SOUTH = CoordGrid(2335, 9350, 0)
        private val GROUNDS_NORTH = CoordGrid(2457, 3186, 0)
        private val GROUNDS_SOUTH = CoordGrid(2437, 3164, 0)

        val OBSERVATORY_QUEST =
            QuestDef(
                name = "Observatory Quest",
                varp = { ObVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("2,250 Crafting XP", "A reward from your star sign"),
                scrollObj = { ObObjs.lens },
                listRow = "quest_observatory",
            )
    }
}

// Every cache ref Observatory Quest uses (re-check after cache updates).

internal object ObNpcs : NpcReferences() {
    val professor = find("observatory_professor")
    val assistant = find("qip_obs_proffesors_assistant")
    val sleeping_guard = find("qip_obs_goblin_guard")
    val goblin_guard = find("goblin_guard")
}

internal object ObLocs : LocReferences() {
    val kitchen_gate = find("keepgate_closed")
    val stove = find("qip_obs_dungeon_stove")
    val keep_chests = listOf(find("qip_obs_keep_chest_closed"))
    val keep_chest_open = find("qip_obs_keep_chest_open")
    val stairs_down_north = find("qip_obs_vstairs2")
    val stairs_down_south = find("qip_obs_stairs_to_dungeon2")
    val stairs_up_dungeon = find("qip_obs_stairs1_dungeon")
    val telescope = find("observatory_telescope")
}

internal object ObObjs : ObjReferences() {
    val plank = find("woodplank")
    val other_planks = listOf(find("plank_oak"), find("plank_teak"), find("plank_mahogany"))
    val bronze_bar = find("bronze_bar")
    val molten_glass = find("molten_glass")
    val key = find("keep_key")
    val mould = find("lens_mould")
    val lens = find("lens")
    val water_rune = find("waterrune")
    val law_rune = find("lawrune")
    val maple_longbow = find("maple_longbow")
    val weapon_poison = find("weapon_poison")
    val amulet_defence = find("amulet_of_defence")
    val black_2h = find("black_2h_sword")
    val super_strength = find("4dose2strength")
    val tuna = find("tuna")
}

internal object ObVarps : VarpReferences() {
    val quest = find("itgronigen")
}

internal object ObVarBits : VarBitReferences() {
    val gate_lock = find("observatory_gatelock")
    val chest_choice = find("observatory_chestchoice")
    val starsign = find("observatory_starsign")
    val scope_looked = find("observatory_scopelooked")
    val mould_taken = find("observatory_mould_pres")
}
