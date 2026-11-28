package org.rsmod.content.quests.hazeelcult

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
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
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Hazeel Cult (OSRS Wiki). `hazeelcultquest` varp (cache db end state 9). Both sides share our
 * step numbers; `hazeelcult_given_poison` marks the Hazeel side:
 * - 1 Ceril's stolen armour; Clivet in the cave under the city offers the choice.
 * - Carnillean side: 2 refused (Clivet flees to the hideout), 3 the armour (Alomone killed, his
 *   chest), 4 the false ending (armour returned, 5 coins, nobody believes Jones is a cultist),
 *   9 the cupboard's poison and amulet unmask Jones (he's jailed).
 * - Hazeel side: 2 joined (Clivet's poison), 3 the soup poisoned (Scruffy dies instead), 4 Clivet's
 *   amulet (the valve pattern), 5 met Alomone in the hideout, 6 the scroll of restoration (crate
 *   key, the knock-at wall, the hidden chest), 9 Hazeel restored.
 * Reward either way: 1,500 Thieving xp, 2,000 coins, 1 QP (Hazeel's side keeps his mark).
 *
 * The sewer raft reaches the hideout only with the five valves set (left, right, right, right,
 * right); server varp `hazeel_valves` (65779) holds them, bit n set = valve n+1 turned left.
 *
 * Not done: the resurrection is the transcript's lines, not a staged cutscene; Kudos.
 */
class HazeelCult
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(HazeelCult::class.java, "hazeel_cult") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Ceril Carnillean" to HcNpcs.ceril,
            "Butler Jones" to HcNpcs.jones,
            "Clivet" to HcNpcs.clivet,
            "Claus the Chef" to HcNpcs.claus,
            "Alomone" to HcNpcs.alomone_talk,
            "Hazeel" to HcNpcs.hazeel,
            "Guard" to HcNpcs.guard,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, HcNpcs.ceril, "Talk-to") { talkCeril(it) }
        onNpcOpLabel(npcTypes, HcNpcs.jones, "Talk-to") { talkJones(it) }
        onNpcOpLabel(npcTypes, HcNpcs.henryeta, "Talk-to") { talkFamily(it, HENRYETA) }
        onNpcOpLabel(npcTypes, HcNpcs.philipe, "Talk-to") { talkFamily(it, PHILIPE) }
        onNpcOpLabel(npcTypes, HcNpcs.claus, "Talk-to") { talkFamily(it, CLAUS) }
        onNpcOpLabel(npcTypes, HcNpcs.guard, "Talk-to") { talkFamily(it, GUARD) }
        onNpcOpLabel(npcTypes, HcNpcs.clivet, "Talk-to") { talkClivet(it) }
        onNpcOpLabel(npcTypes, HcNpcs.alomone_talk, "Talk-to") { talkAlomone(it) }
        for (cultist in HcNpcs.cultists) {
            onNpcOpLabel(npcTypes, cultist, "Talk-to") { talkCultist(it) }
        }
        onEvent<NpcKilledEvent> { alomoneKilled(this) }

        // The cave, the sewer and the hideout
        onLocOpLabel(locTypes, HcLocs.cave, "Enter") { telejump(SEWER_ENTRANCE) }
        onLocOpLabel(locTypes, HcLocs.cave_stairs, "Climb-up") { telejump(CAVE_OUTSIDE) }
        for ((index, valve) in HcLocs.valves.withIndex()) {
            onLocOpLabel(locTypes, valve, "Turn") { turnValve(index) }
        }
        onLocOpLabel(locTypes, HcLocs.raft, "Board") { boardRaft() }
        onLocOpLabel(locTypes, HcLocs.hideout_chest, "Search") { searchHideoutChest() }

        // The Carnillean mansion
        onLocOpLabel(locTypes, HcLocs.ladder_down, "Climb-down") { telejump(KITCHEN) }
        onLocOpLabel(locTypes, HcLocs.ladder_up, "Climb-up") { telejump(HALL) }
        onLocOpLabel(locTypes, HcLocs.stairs, "Climb-up") {
            telejump(player.coords.translateLevel(1))
        }
        onLocOpLabel(locTypes, HcLocs.stairs_top, "Climb-down") {
            telejump(player.coords.translateLevel(-1))
        }
        onLocOpLabel(locTypes, HcLocs.range, "Inspect") { inspectRange() }
        onOpLocU(HcLocs.range, HcObjs.poison) { inspectRange() }
        onLocOpLabel(locTypes, HcLocs.crate, "Search") { searchCrate() }
        onLocOpLabel(locTypes, HcLocs.knock_wall, "Knock-at") { knockWall(it) }
        onLocOpLabel(locTypes, HcLocs.push_wall, "Push-against") { passThroughWall(it) }
        onLocOpLabel(locTypes, HcLocs.scroll_chest, "Search") { searchScrollChest() }
        onLocOpLabel(locTypes, HcLocs.cupboard_shut, "Open") {
            locRepo.change(it, HcLocs.cupboard_open, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, HcLocs.cupboard_open, "Shut") {
            locRepo.change(it, HcLocs.cupboard_shut, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, HcLocs.cupboard_open, "Search") { searchCupboard() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(HAZEEL_CULT)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(HAZEEL_CULT, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setVar(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /** On the Hazeel side (took Clivet's poison). */
    private val ProtectedAccess.hazeelSide: Boolean
        get() = bit(HcVarBits.given_poison) != 0

    private val ProtectedAccess.carnilleanSide: Boolean
        get() = state >= SIDED && !hazeelSide

    private fun ProtectedAccess.invHooks(extra: Map<String, () -> Boolean> = emptyMap()) =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has a full inventory:" to { inv.isFull() },
                    "With a full inventory:" to { inv.isFull() },
                    "If the player inventory space:" to { !inv.isFull() },
                    "If the player has inventory space:" to { !inv.isFull() },
                    "If the player has enough space:" to { !inv.isFull() },
                    "Without a full inventory:" to { !inv.isFull() },
                ) + extra,
            rewrites = { it.replace(TYPO, "after it.") },
        )

    /* The Carnillean household */

    private suspend fun ProtectedAccess.talkCeril(npc: Npc) {
        var joined = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is below level 10 combat:" to {
                            player.appearance.combatLevel < RECOMMENDED_COMBAT
                        }
                    ),
                options = mapOf("Yes." to { joined = true }),
                boxes =
                    mapOf(
                        "You give the armour to Ceril." to {
                            invDel(inv, HcObjs.armour)
                            false
                        }
                    ),
            )
        val section =
            when {
                state == 0 -> "Starting off"
                state == COMPLETE -> if (hazeelSide) "Talking to Ceril (3)" else "Talking to Ceril"
                carnilleanSide && state == C_ARMOUR && carries(HcObjs.armour) ->
                    "Returning the armour"
                carnilleanSide && state == C_FALSE_END -> "Talking to Cecil"
                !hazeelSide -> "Talking to Ceril again"
                state == SIDED -> "Talking to Ceril (2)"
                state == H_POISONED && bit(HcVarBits.told_ceril) == 0 ->
                    "Talking to Ceril after the poisoning"
                state == H_POISONED -> "Talking to Ceril again after the poisoning"
                state in H_AMULET..H_MET_ALOMONE -> "Talking to Ceril again (2)"
                else -> "Talking to Ceril after obtaining the scroll"
            }
        runQuestSection(npc, dialogue, section, hooks, speakers)
        when (section) {
            "Starting off" -> if (joined) setState(STARTED)
            "Returning the armour" -> {
                invAddOrDrop(objRepo, HcObjs.coins, FALSE_REWARD)
                setVar(HcVarBits.given_armour, 1)
                setState(C_FALSE_END)
            }
            "Talking to Ceril after the poisoning" -> setVar(HcVarBits.told_ceril, 1)
        }
    }

    private suspend fun ProtectedAccess.talkJones(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have the scroll:" to { !carries(HcObjs.scroll) },
                        "If the player has the scroll:" to { carries(HcObjs.scroll) },
                    )
            )
        val section =
            when {
                state <= STARTED || state == COMPLETE -> "Talking to Butler Jones"
                carnilleanSide && state < C_FALSE_END -> "Talking to Butler Jones (2)"
                carnilleanSide -> "Talking to Butler Jones (3)"
                state == SIDED -> "Talking to Butler Jones (4)"
                state in H_POISONED..H_AMULET -> "Talking to Butler Jones after the poisoning"
                state == H_MET_ALOMONE && !carries(HcObjs.scroll) -> "Talking to Butler Jones (5)"
                else -> "Talking to Jones after obtaining the scroll"
            }
        runQuestSection(npc, dialogue, section, hooks)
    }

    /** Henryeta, Philipe, Claus and the guard: chatter that follows the quest. */
    private suspend fun ProtectedAccess.talkFamily(npc: Npc, who: Int) {
        val c = carnilleanSide
        val section =
            when {
                state == COMPLETE && !hazeelSide -> FAMILY_DONE[who]
                state == COMPLETE -> FAMILY_SCROLL[who]
                state <= STARTED || (c && state < C_FALSE_END) -> FAMILY_START[who]
                c -> FAMILY_FALSE_END[who]
                state == SIDED -> FAMILY_JOINED[who]
                state == H_SCROLL -> FAMILY_SCROLL[who]
                who == CLAUS && state > H_POISONED -> "Talking to Claus again"
                else -> FAMILY_POISONED[who]
            }
        runQuestSection(npc, dialogue, section, speakers = speakers)
    }

    private suspend fun ProtectedAccess.inspectRange() {
        if (!hazeelSide || state != SIDED || !carries(HcObjs.poison)) {
            val section =
                when {
                    hazeelSide && state > SIDED -> "Using another poison the range"
                    else -> "Inspecting the range"
                }
            runQuestSection(null, dialogue, section, speakers = speakers)
            return
        }
        var pour = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Yes." to { pour = true }),
            )
        runQuestSection(null, dialogue, "Inspecting the range (2)", hooks)
        if (!pour) return
        invDel(inv, HcObjs.poison)
        mes("You pour the poison into the pot of soup.")
        setVar(HcVarBits.poison_success, 1)
        setVar(HcVarBits.dog_vis, DOG_GRAVE)
        setState(H_POISONED)
    }

    private suspend fun ProtectedAccess.searchCrate() {
        val needKey = hazeelSide && state == H_MET_ALOMONE
        if (!needKey || carries(HcObjs.chest_key) || carries(HcObjs.scroll)) {
            runQuestSection(null, dialogue, "Searching a crate with the key")
            mes("You find nothing of interest.")
            return
        }
        val full = inv.isFull()
        runQuestSection(null, dialogue, "Searching the crate in the kitchen", invHooks())
        if (!full) invAdd(inv, HcObjs.chest_key)
    }

    private suspend fun ProtectedAccess.knockWall(wall: BoundLocInfo) {
        var push = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes." to { push = true }))
        runQuestSection(null, dialogue, "Using the Knock-at wall", hooks)
        if (push) passThroughWall(wall)
    }

    private suspend fun ProtectedAccess.searchScrollChest() {
        when {
            carries(HcObjs.scroll) || (hazeelSide && state >= H_SCROLL) ->
                runQuestSection(null, dialogue, "Searching the chest with a scroll already")
            !carries(HcObjs.chest_key) -> runQuestSection(null, dialogue, "Chest without the key")
            else -> {
                val full = inv.isFull()
                runQuestSection(null, dialogue, "Opening the chest", invHooks())
                if (full) return
                invDel(inv, HcObjs.chest_key)
                invAdd(inv, HcObjs.scroll)
                setVar(HcVarBits.given_scroll, 1)
                if (state == H_MET_ALOMONE) setState(H_SCROLL)
            }
        }
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        if (!carnilleanSide || state != C_FALSE_END) {
            mes("The cupboard is empty.")
            return
        }
        runQuestSection(null, dialogue, "Accusing the butler", speakers = speakers)
        setVar(HcVarBits.jones_location, JONES_JAILED)
        reward()
    }

    private fun ProtectedAccess.reward() {
        invAddOrDrop(objRepo, HcObjs.coins, REWARD_COINS)
        statAdvance(stats.thieving, REWARD_XP * xpMods.get(player, stats.thieving))
        completeQuest(HAZEEL_CULT)
    }

    /* The cult */

    private suspend fun ProtectedAccess.talkClivet(npc: Npc) {
        if (carnilleanSide) {
            runQuestSection(npc, dialogue, "Talking to Clivet", speakers = speakers)
            return
        }
        var refused = false
        var join = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has a full inventory:" to { inv.isFull() },
                        "If the player inventory space:" to { !inv.isFull() },
                        "If the player has inventory space:" to { !inv.isFull() },
                        "If the player has poison in their inventory:" to {
                            carries(HcObjs.poison)
                        },
                        "If the player has no poison in their inventory:" to {
                            !carries(HcObjs.poison)
                        },
                    ),
                options =
                    mapOf(
                        "I won't help you." to { refused = true },
                        "Alright, how do I do it?" to { join = true },
                    ),
                rewrites = { it.replace(TYPO, "after it.") },
            )
        val section =
            when {
                state < SIDED -> "Confronting the Hazeel Cult"
                state == SIDED && bit(HcVarBits.got_poison) == 0 -> POISON_RETRY
                state == SIDED -> "Talking to Clivet again"
                state == H_POISONED -> "Talking to Clivet (2)"
                state == H_AMULET && !carries(HcObjs.mark) && bit(HcVarBits.given_amulet) == 0 ->
                    AMULET_RETRY
                state == H_AMULET && !carries(HcObjs.mark) -> "Reclaiming a lost amulet"
                state == H_AMULET -> "Talking to Clivet again (2)"
                else -> "Talking to Clivet again (3)"
            }
        val full = inv.isFull()
        runQuestSection(npc, dialogue, section, hooks, speakers)
        when {
            section == "Confronting the Hazeel Cult" && refused -> {
                if (state == STARTED) setState(SIDED)
                setVar(HcVarBits.clivet_location, CLIVET_HIDEOUT)
            }
            section == "Confronting the Hazeel Cult" && join -> {
                setVar(HcVarBits.given_poison, 1)
                if (state == STARTED) setState(SIDED)
                if (!full) givePoison()
            }
            section == POISON_RETRY || section == "Talking to Clivet again" ->
                if (!full && !carries(HcObjs.poison)) givePoison()
            section == "Talking to Clivet (2)" -> {
                setState(H_AMULET)
                if (!full) giveAmulet()
            }
            section == AMULET_RETRY || section == "Reclaiming a lost amulet" ->
                if (!full) giveAmulet()
        }
    }

    private fun ProtectedAccess.givePoison() {
        invAdd(inv, HcObjs.poison)
        setVar(HcVarBits.got_poison, 1)
    }

    private fun ProtectedAccess.giveAmulet() {
        invAdd(inv, HcObjs.mark)
        setVar(HcVarBits.given_amulet, 1)
    }

    private suspend fun ProtectedAccess.talkCultist(npc: Npc) {
        val section = if (hazeelSide) "Talking to a Hazeel cultist" else "Talking to Hazeel Cultist"
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.talkAlomone(npc: Npc) {
        if (!hazeelSide) {
            runQuestSection(npc, dialogue, "Talking to Alomone", speakers = speakers)
            if (carnilleanSide && state < C_ARMOUR) setVar(HcVarBits.alomone_vis, ALOMONE_FIGHT)
            return
        }
        val has = carries(HcObjs.scroll)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the scroll in their inventory:" to { has },
                        "If the player does not have the scroll in their inventory:" to { !has },
                    )
            )
        when (state) {
            H_AMULET -> {
                runQuestSection(npc, dialogue, "Talking to Alomone (2)", speakers = speakers)
                setState(H_MET_ALOMONE)
            }
            H_MET_ALOMONE -> runQuestSection(npc, dialogue, "Talking to Alomone again")
            H_SCROLL -> {
                runQuestSection(npc, dialogue, ALOMONE_SCROLL, hooks, speakers)
                if (!has) return
                invDel(inv, HcObjs.scroll)
                setVar(HcVarBits.hazeel_cutscene, 1)
                reward()
            }
            else -> runQuestSection(npc, dialogue, "Talking to Alomone again")
        }
    }

    private fun alomoneKilled(event: NpcKilledEvent) {
        val id = event.npc.type.id
        if (id != HcNpcs.alomone_fight.id && id != HcNpcs.alomone.id) return
        val player = event.hero
        VarPlayerIntMapSetter.set(player, HcVarBits.alomone_vis, 0)
        VarPlayerIntMapSetter.set(player, HcVarBits.alomone_killed, 1)
    }

    private suspend fun ProtectedAccess.searchHideoutChest() {
        if (!carnilleanSide || state != SIDED) {
            mes("The chest is empty.")
            return
        }
        if (bit(HcVarBits.alomone_killed) == 0) {
            runQuestSection(null, dialogue, "If Alomone is alive", speakers = speakers)
            return
        }
        val full = inv.isFull()
        runQuestSection(null, dialogue, "If Alomone is killed first", invHooks())
        if (full) return
        invAdd(inv, HcObjs.armour)
        setVar(HcVarBits.found_armour, 1)
        setState(C_ARMOUR)
    }

    /* Valves and the raft */

    private suspend fun ProtectedAccess.turnValve(index: Int) {
        val bit = 1 shl index
        val left = player.vars[HcVarps.valves] and bit != 0
        var turned = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Turn it to the left." to { turned = true },
                        "Turn it to the right." to { turned = true },
                    )
            )
        val section =
            if (left) {
                "Inspecting a sewer valve that is to the left"
            } else {
                "Inspecting a sewer valve that is to the right"
            }
        runQuestSection(null, dialogue, section, hooks)
        if (turned) {
            VarPlayerIntMapSetter.set(player, HcVarps.valves, player.vars[HcVarps.valves] xor bit)
        }
    }

    private suspend fun ProtectedAccess.boardRaft() {
        if (player.coords.x >= HIDEOUT_WEST_EDGE) {
            runQuestSection(null, dialogue, "Taking the raft back")
            telejump(SEWER_ENTRANCE)
            return
        }
        if (state < SIDED || (hazeelSide && state < H_AMULET)) {
            runQuestSection(null, dialogue, "Trying to use the raft", speakers = speakers)
            return
        }
        val valves = player.vars[HcVarps.valves]
        val correct = (0 until VALVES).count { (valves xor VALVE_PATTERN) shr it and 1 == 0 }
        val section =
            when (correct) {
                VALVES -> "Sucessfully using the raft with the correct water valve settings"
                1 -> "One valve set correctly"
                2 -> "Two valves set correctly"
                3 -> "Three valves set correctly"
                4 -> "Four valves set correctly"
                else -> "Using the raft"
            }
        runQuestSection(null, dialogue, section)
        if (correct == VALVES) telejump(HIDEOUT)
    }

    companion object {
        const val STARTED = 1
        const val SIDED = 2
        const val C_ARMOUR = 3
        const val C_FALSE_END = 4
        const val H_POISONED = 3
        const val H_AMULET = 4
        const val H_MET_ALOMONE = 5
        const val H_SCROLL = 6
        const val COMPLETE = 9

        private const val RECOMMENDED_COMBAT = 10
        private const val REWARD_XP = 1500.0
        private const val REWARD_COINS = 2000
        private const val FALSE_REWARD = 5
        private const val CUPBOARD_TICKS = 200
        private const val VALVES = 5

        /** Valve 1 left, valves 2-5 right (bit set = left). */
        private const val VALVE_PATTERN = 0b00001
        private const val CLIVET_HIDEOUT = 1
        private const val ALOMONE_FIGHT = 1
        private const val JONES_JAILED = 1
        private const val DOG_GRAVE = 2
        private const val HIDEOUT_WEST_EDGE = 2595

        private val CAVE_OUTSIDE = CoordGrid(2585, 3235, 0)
        private val SEWER_ENTRANCE = CoordGrid(2569, 9681, 0)
        private val HIDEOUT = CoordGrid(2606, 9691, 0)
        private val KITCHEN = CoordGrid(2544, 9693, 0)
        private val HALL = CoordGrid(2570, 3266, 0)

        private const val HENRYETA = 0
        private const val PHILIPE = 1
        private const val CLAUS = 2
        private const val GUARD = 3

        private val FAMILY_START =
            listOf(
                "Talking to Henryeta",
                "Talking to Philipe",
                "Talking to Claus",
                "Talking to the guard",
            )
        private val FAMILY_FALSE_END =
            listOf(
                "Talking to Henryeta (2)",
                "Talking to Philipe (2)",
                "Talking to Claus the Chef",
                "Talking to the Guard",
            )
        private val FAMILY_DONE =
            listOf(
                "Talking to Henryeta (3)",
                "Talking to Philipe (3)",
                "Talking to Claus (2)",
                "Talking to Guard",
            )
        private val FAMILY_JOINED =
            listOf(
                "Talking to Henryeta (4)",
                "Talking to Philipe (4)",
                "Talking to Claus (3)",
                "Talking to the guard (2)",
            )
        private val FAMILY_POISONED =
            listOf(
                "Talking to Henryeta after the poisoning",
                "Talking to Philipe again",
                "Talking to Claus after the poisoning",
                "Talking to the guard again",
            )
        private val FAMILY_SCROLL =
            listOf(
                "Talking to Hentryeta after obtaining the scroll",
                "Talking to Philipe after obtaining the scroll",
                "Talking to Claus after obtaining the scroll",
                "Talking to the guard after obtaining the scroll",
            )

        private const val TYPO = "aferMight be a transcription error this one!"
        private const val POISON_RETRY = "Obtaining the poison after an initially full inventory"
        private const val AMULET_RETRY = "If the player did not have enough space for the amulet"
        private const val ALOMONE_SCROLL = "Talking to Alomone after obtaining the scroll"

        val HAZEEL_CULT =
            QuestDef(
                name = "Hazeel Cult",
                varp = { HcVarps.hazeelcultquest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,500 Thieving XP", "2,000 Coins"),
                scrollObj = { HcObjs.mark },
            )
    }
}

// Every cache ref Hazeel Cult uses (re-check after cache updates).

internal object HcNpcs : NpcReferences() {
    /* Children of the sir_ceril_carnillean / guard_carnillean multinpcs (sotn state). */
    val ceril = find("sir_ceril_carnillean_vis")
    val guard = find("guard_carnillean_vis")
    val henryeta = find("carnillean_wife")
    val philipe = find("philipe_carnillean")
    val claus = find("claus_carnillean")

    /* Child of butler_jones_hazeel_cultist (hazeelcult_jones_location 0); jailed is the noop. */
    val jones = find("butler_jones_hazeel_cultist_op")

    /* Child of the clivet_hazeel_cultist (cave) and _hideout multinpcs (clivet_location). */
    val clivet = find("clivet_hazeel_cultist_vis")

    /* The alomone_hazeel_cultist multinpc (hazeelcult_alomone_vis) and its children. */
    val alomone = find("alomone_hazeel_cultist")
    val alomone_talk = find("alomone_hazeel_cultist_1op")
    val alomone_fight = find("alomone_hazeel_cultist_2op")
    val cultists = listOf(find("hazeel_cultist"))
    val hazeel = find("hazeel")
}

internal object HcLocs : LocReferences() {
    val cave = find("hazeelcultcave")
    val cave_stairs = find("hazeelcultstairs")
    val valves =
        listOf(
            find("sewervalve1"),
            find("sewervalve2"),
            find("sewervalve3"),
            find("sewervalve4"),
            find("sewervalve5"),
        )
    val raft = find("hazeelsewerraft")
    val hideout_chest = find("hazeel_chest_closed")
    val ladder_down = find("carnillean_ladder_down")
    val ladder_up = find("carnillean_ladder_up")
    val stairs = find("carnillean_stairs")
    val stairs_top = find("carnillean_stairstop")
    val range = find("carnilleanrange")
    val crate = find("carnilleancrate")

    /* Children of the carnilleanbookcase / carnilleanshutchest multilocs (sotn state 0). */
    val knock_wall = find("carnilleanbookcase_knock")
    val push_wall = find("carnilleanbookcase_push")
    val scroll_chest = find("carnilleanshutchest_normal")
    val cupboard_shut = find("hazeelcbshut")
    val cupboard_open = find("hazeelcbopen")
}

internal object HcObjs : ObjReferences() {
    val poison = find("poison")
    val armour = find("carnillean_armour")
    val mark = find("mark_of_hazeel")
    val scroll = find("hazeel_scroll")
    val chest_key = find("carnilleanchestkey")
    val coins = find("coins")
}

internal object HcVarps : VarpReferences() {
    val hazeelcultquest = find("hazeelcultquest")

    /** Server varp (65779): the five sewer valves, bit set = turned left. */
    val valves = find("hazeel_valves")
}

internal object HcVarBits : VarBitReferences() {
    val clivet_location = find("hazeelcult_clivet_location")
    val alomone_vis = find("hazeelcult_alomone_vis")
    val alomone_killed = find("hazeelcult_alomone_met")
    val given_armour = find("hazeelcult_given_armour")
    val jones_location = find("hazeelcult_jones_location")
    val poison_success = find("hazeelcult_poison_success")
    val given_amulet = find("hazeelcult_given_amulet")
    val found_armour = find("hazeelcult_found_armour")
    val dog_vis = find("carnillean_dog_vis")
    val given_scroll = find("hazeelcult_given_scroll")
    val hazeel_cutscene = find("hazeelcult_hazeel_cutscene")
    val given_poison = find("hazeelcult_given_poison")
    /** Repurposed: Clivet actually handed over the poison (full inventory retries). */
    val got_poison = find("hazeelcult_sewer_chat")
    val told_ceril = find("hazeelcult_returned_to_ceril")
}

internal object HcVarpBuilds : VarpBuilder() {
    init {
        build("hazeel_valves")
    }
}
