package org.rsmod.content.quests.elementalworkshop1

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.elementalworkshop2.Ew2Workbench
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Elemental Workshop I (OSRS Wiki). The quest list reads the cache's linear varbit
 * `elementalworkshop` (0, 1 started, 2 done); the workshop's machinery is the cache flag varbits
 * on `elemental_workshop_bits` (the multilocs read them), so no custom state is needed.
 * - Search the bookcase in Seers' Village for the battered book; read it to start.
 * - A knife on the book: the battered key (the book becomes the slashed book).
 * - The key on the odd looking wall in the anvil house; down the stairs to the workshop.
 * - Water controls: east then west to green, then the lever starts the water wheel.
 * - Fix the bellows (leather, needle, thread, 20 Crafting), then pull its lever.
 * - Mine an elemental rock (20 Mining): it wakes as an earth elemental; it drops elemental ore.
 * - The stone bowl (boxes north-east of the hatch), lava from the trough, into the furnace.
 * - Ore + 4 coal in the furnace (20 Smithing): elemental metal; the workbench, with a hammer and
 *   the slashed book, makes the elemental shield. 1 QP, 5,000 Crafting and Smithing xp.
 *
 * Simplified: only a knife slashes the book (OSRS also accepts slash weapons); the hatch is
 * Elemental Workshop II's.
 */
class ElementalWorkshopI
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
    private val ew2Workbench: Ew2Workbench,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ElementalWorkshopI::class.java, "elemental_workshop_1") }

    override fun ScriptContext.startup() {
        onLocOpLabel(locTypes, EwLocs.bookcase, "Search") { searchBookcase() }
        onOpHeld1(EwObjs.book) { readBook() }
        onOpHeld1(EwObjs.slashed_book) { readBook() }
        onOpHeldU(EwObjs.knife, EwObjs.book) { slashBook() }

        for (wall in EwLocs.oddwalls) {
            onOpLocU(wall, EwObjs.key) { unlockWall() }
            onLocOpLabel(locTypes, wall, "Open") { openWall() }
        }
        onLocOpLabel(locTypes, EwLocs.stairs_top, "Climb-down") { climbDown() }
        onLocOpLabel(locTypes, EwLocs.stairs_bottom, "Climb-up") { telejump(UP_ARRIVE) }

        for (valve in EwLocs.valves) onLocOpLabel(locTypes, valve, "Turn") { turnValve(it) }
        onLocOpLabel(locTypes, EwLocs.water_lever, "Pull") { pullWaterLever() }
        onLocOpLabel(locTypes, EwLocs.bellows_broken, "Fix") { fixBellows() }
        onLocOpLabel(locTypes, EwLocs.air_lever, "Pull") { pullAirLever() }

        onLocOpLabel(locTypes, EwLocs.leather_box, "Search") { searchLeather() }
        onLocOpLabel(locTypes, EwLocs.bowl_box, "Search") { searchBowl() }
        onLocOpLabel(locTypes, EwLocs.needle_crates, "Search") { searchNeedle() }

        for (trough in EwLocs.troughs) onOpLocU(trough, EwObjs.bowl) { fillBowl() }
        onOpLocU(EwLocs.furnace_out, EwObjs.bowl_full) { lightFurnace() }
        onOpLocU(EwLocs.furnace_out, EwObjs.tinderbox) {
            mes("The tinderbox cannot make a spark large enough to light this.")
        }
        onOpLocU(EwLocs.furnace_out, EwObjs.ore) { mes("The furnace is cold and useless for anything.") }
        onOpLocU(EwLocs.furnace_lit, EwObjs.ore) { smeltOre() }

        onLocOpLabel(locTypes, EwLocs.workbench, "Smith") { useWorkbench() }
        onOpLocU(EwLocs.workbench, EwObjs.bar) { useWorkbench() }
        onOpLocU(EwLocs.workbench, EwObjs.mind_bar) { useWorkbench() }

        onNpcOpLabel(npcTypes, EwNpcs.rock, "Mine") { mineRock(it) }
        onEvent<NpcKilledEvent> { elementalKilled(this) }
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private val ProtectedAccess.started: Boolean
        get() = player.questState(ELEMENTAL_WORKSHOP_I) >= STARTED

    /* The book and the key */

    private suspend fun ProtectedAccess.searchBookcase() {
        if (carries(EwObjs.book) || carries(EwObjs.slashed_book)) {
            // Authored line (not Wiki): you already have the book.
            mes("You search the bookcase but find nothing else of interest.")
            return
        }
        val full = inv.isFull()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player has a full inventory:" to { full }, "Otherwise:" to { !full })
            )
        runQuestSection(null, dialogue, "Searching the Bookcase", hooks)
        if (!full) invAdd(inv, EwObjs.book)
    }

    private suspend fun ProtectedAccess.readBook() {
        mes("The book has two parts: an introduction and an instruction section.")
        mes("You flip the book open to the introduction and start reading.")
        if (started) {
            // Authored (not Wiki): the book's text, condensed.
            mesbox(
                "In the Fifth Age a new ore was found, and a workshop was built to work it. When " +
                    "its true power was realised the workshop was sealed, bound under lock and key. " +
                    "The secret to entering it lies within this book."
            )
            return
        }
        var start = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player's combat level is lower than 15:" to { player.combatLevel < 15 }),
                options = mapOf("Yes." to { start = true }),
            )
        runQuestSection(null, dialogue, "Reading the Battered book", hooks)
        if (start) {
            player.setQuestState(ELEMENTAL_WORKSHOP_I, STARTED)
            setFlag(EwVarBits.book, 1)
        }
    }

    private suspend fun ProtectedAccess.slashBook() {
        if (!started) {
            // Authored line (not Wiki): read it first.
            mes("You should read the book before cutting it open.")
            return
        }
        invDel(inv, EwObjs.book)
        invAdd(inv, EwObjs.slashed_book)
        mes("You make a small cut in the spine of the book.")
        mes("Inside you find a small, old, battered key.")
        if (inv.isFull()) {
            mes("You drop the key on the floor as you have no space in your backpack.")
        }
        invAddOrDrop(objRepo, EwObjs.key)
        setFlag(EwVarBits.key, 1)
    }

    private suspend fun ProtectedAccess.unlockWall() {
        // Authored line (not Wiki): the crack hides a keyhole.
        mes("You unlock the odd looking wall and step through.")
        telejump(if (player.coords.z >= WALL_Z + 1) OUTSIDE_WALL else INSIDE_WALL)
    }

    private suspend fun ProtectedAccess.openWall() {
        when {
            player.coords.z > WALL_Z -> telejump(OUTSIDE_WALL)
            carries(EwObjs.key) -> telejump(INSIDE_WALL)
            // Authored line (not Wiki): it's locked without the key.
            else -> mes("The wall won't open. There seems to be a keyhole hidden in the crack.")
        }
    }

    private suspend fun ProtectedAccess.climbDown() {
        telejump(DOWN_ARRIVE)
        if (flag(EwVarBits.stairs) == 0) {
            setFlag(EwVarBits.stairs, 1)
            runQuestSection(null, dialogue, "Climbing down the Staircase")
        }
    }

    /* The water wheel */

    private fun ProtectedAccess.turnValve(loc: BoundLocInfo) {
        val east = loc.coords.x == EAST_VALVE_X
        // OSRS: the east (right) controls can't be turned while the west ones are green.
        if (east && flag(EwVarBits.gate1) == 1) {
            mes("You turn the handle.")
            return
        }
        val gate = if (east) EwVarBits.gate2 else EwVarBits.gate1
        setFlag(gate, 1 - flag(gate))
        mes("You turn the handle.")
    }

    private fun ProtectedAccess.pullWaterLever() {
        mes("You pull the lever.")
        when {
            flag(EwVarBits.wheel) == 1 -> {
                setFlag(EwVarBits.wheel, 0)
                mes("You hear the sound of a water wheel coming to a standstill.")
            }
            flag(EwVarBits.gate1) == 1 && flag(EwVarBits.gate2) == 1 -> {
                setFlag(EwVarBits.wheel, 1)
                mes("You hear the sound of a water wheel starting up.")
            }
            else -> {
                setFlag(EwVarBits.gate1, 0)
                setFlag(EwVarBits.gate2, 0)
                mes("You hear the sound of the flow gates resetting.")
            }
        }
    }

    /* The bellows */

    private fun ProtectedAccess.fixBellows() {
        if (flag(EwVarBits.bellows) == 1) {
            mes("The bellows already look fixed to you.")
            return
        }
        if (!carries(EwObjs.leather) || !carries(EwObjs.needle) || !carries(EwObjs.thread)) {
            mes("You need a piece of leather, some thread and a needle to fix this.")
            return
        }
        if (stat(stats.crafting) < LEVEL) {
            // Authored line (not Wiki): the Crafting requirement.
            mes("You need a Crafting level of $LEVEL to fix the bellows.")
            return
        }
        invDel(inv, EwObjs.leather)
        invDel(inv, EwObjs.thread)
        setFlag(EwVarBits.bellows, 1)
        mes("You stitch the leather over the hole in the bellows.")
    }

    private fun ProtectedAccess.pullAirLever() {
        mes("You pull the lever.")
        when {
            flag(EwVarBits.bellows_switch) == 1 -> {
                setFlag(EwVarBits.bellows_switch, 0)
                mes("The bellows stop pumping air.")
            }
            flag(EwVarBits.bellows) == 0 -> {
                mes("The bellows pump air out of the hole.")
                mes("Looks like they need fixing. You reset the lever.")
            }
            else -> {
                setFlag(EwVarBits.bellows_switch, 1)
                mes("The bellows pump air down the pipe.")
            }
        }
    }

    /* The crates */

    private fun ProtectedAccess.searchLeather() {
        if (carries(EwObjs.leather)) {
            // Authored line (not Wiki): one piece at a time.
            mes("You search the crate but find nothing else.")
            return
        }
        invAddOrDrop(objRepo, EwObjs.leather)
        setFlag(EwVarBits.leather, 1)
        mes("You find some leather.")
    }

    private fun ProtectedAccess.searchBowl() {
        if (carries(EwObjs.bowl) || carries(EwObjs.bowl_full)) {
            mes("You search the boxes but find nothing else.") // Authored (not Wiki).
            return
        }
        invAddOrDrop(objRepo, EwObjs.bowl)
        mes("You find a stone bowl.") // Authored (not Wiki).
    }

    private fun ProtectedAccess.searchNeedle() {
        if (carries(EwObjs.needle)) {
            mes("You search the crates but find nothing else.") // Authored (not Wiki).
            return
        }
        invAddOrDrop(objRepo, EwObjs.needle)
        mes("You find a needle.") // Authored (not Wiki).
    }

    /* The furnace and the shield */

    private fun ProtectedAccess.fillBowl() {
        invReplace(inv, EwObjs.bowl, 1, EwObjs.bowl_full)
        mes("You fill the bowl with hot lava.")
    }

    private fun ProtectedAccess.lightFurnace() {
        invReplace(inv, EwObjs.bowl_full, 1, EwObjs.bowl)
        setFlag(EwVarBits.fire, 1)
        mes("You empty the lava into the furnace.")
        mes("The furnace bursts to life.")
    }

    private fun ProtectedAccess.smeltOre() {
        if (flag(EwVarBits.wheel) == 0 || flag(EwVarBits.bellows_switch) == 0) {
            // Authored line (not Wiki): the machinery isn't running.
            mes("The furnace isn't hot enough. The water wheel and bellows must both be running.")
            return
        }
        if (!carries(EwObjs.coal, COAL)) {
            mes("You need four heaps of coal to smelt elemental ore.")
            return
        }
        if (stat(stats.smithing) < LEVEL) {
            mes("You need a Smithing level of $LEVEL to smelt elemental ore.") // Authored (not Wiki).
            return
        }
        invDel(inv, EwObjs.ore)
        invDel(inv, EwObjs.coal, COAL)
        invAdd(inv, EwObjs.bar)
        mes("You place the elemental ore and four heaps of coal into the furnace.")
        mes("You retrieve a bar of elemental metal.")
    }

    private suspend fun ProtectedAccess.useWorkbench() {
        // Elemental Workshop II shares the bench (the crane claw, mind helmets).
        if (with(ew2Workbench) { handle() }) return
        val section =
            when {
                !carries(EwObjs.bar) -> "Using the workbench without elemental metal"
                !carries(EwObjs.hammer) -> "Using the workbench without a hammer"
                !carries(EwObjs.slashed_book) -> "Using the workbench without the slashed book"
                else -> null
            }
        if (section != null) {
            runQuestSection(null, dialogue, section)
            return
        }
        if (stat(stats.smithing) < LEVEL) {
            mes("You need a Smithing level of $LEVEL to make an elemental shield.") // Authored.
            return
        }
        invDel(inv, EwObjs.bar)
        invAdd(inv, EwObjs.shield)
        mes("Following the instructions in the book, you make an elemental shield.")
        if (started && !player.isQuestComplete(ELEMENTAL_WORKSHOP_I)) {
            setFlag(EwVarBits.finished, 1)
            statAdvance(stats.crafting, REWARD_XP * xpMods.get(player, stats.crafting))
            statAdvance(stats.smithing, REWARD_XP * xpMods.get(player, stats.smithing))
            completeQuest(ELEMENTAL_WORKSHOP_I)
        }
    }

    /* The elemental rocks */

    private fun ProtectedAccess.mineRock(rock: Npc) {
        if (EwObjs.pickaxes.none { carries(it) || invTotal(worn, it) > 0 }) {
            mes("You need a pickaxe to mine this rock.") // Authored (not Wiki).
            return
        }
        if (stat(stats.mining) < LEVEL) {
            mes("You need a Mining level of $LEVEL to mine this rock.") // Authored (not Wiki).
            return
        }
        val at = rock.coords
        npcRepo.del(rock, ROCK_RESPAWN)
        val elemental = Npc(npcTypes[EwNpcs.elemental], at)
        npcRepo.add(elemental, ELEMENTAL_TICKS)
        elemental.opPlayer2(player, aiInteractions)
        // Authored line (not Wiki): the rock wakes.
        mes("The rock springs to life and attacks you!")
    }

    private fun elementalKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != EwNpcs.elemental.id) return
        val player = event.hero
        objRepo.add(EwObjs.ore, event.npc.coords, DROP_TICKS, player)
        objRepo.add(EwObjs.rock_remains, event.npc.coords, DROP_TICKS, player)
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 2

        private const val LEVEL = 20
        private const val COAL = 4
        private const val REWARD_XP = 5000.0
        private const val ROCK_RESPAWN = 50
        private const val ELEMENTAL_TICKS = 200
        private const val DROP_TICKS = 200

        /** The odd looking wall (2709-2710, 3495) in the anvil house; the stairs room is north. */
        private const val WALL_Z = 3495
        private val OUTSIDE_WALL = CoordGrid(2710, 3494, 0)
        private val INSIDE_WALL = CoordGrid(2709, 3496, 0)
        private val UP_ARRIVE = CoordGrid(2709, 3496, 0)
        private val DOWN_ARRIVE = CoordGrid(2716, 9888, 0)

        /** The east water controls (varbit gate2); the west ones are at x 2713 (gate1). */
        private const val EAST_VALVE_X = 2726

        val ELEMENTAL_WORKSHOP_I =
            QuestDef(
                name = "Elemental Workshop I",
                varbit = { EwVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("5,000 Crafting XP", "5,000 Smithing XP", "Elemental shields"),
                scrollObj = { EwObjs.shield },
                listRow = "quest_elementalworkshop1",
            )
    }
}

// Every cache ref Elemental Workshop I uses (re-check after cache updates).

internal object EwNpcs : NpcReferences() {
    val rock = find("elem1_qip_earth_elemental_rock_version_rock")
    val elemental = find("elem1_qip_earth_elemental_rock_version")
}

internal object EwLocs : LocReferences() {
    val bookcase = find("elemental_workshop_bookcase")
    val oddwalls = listOf(find("elemental_workshop_oddwall_l"), find("elemental_workshop_oddwall_r"))
    val stairs_top = find("elemental_workshop_spiralstairstop")
    val stairs_bottom = find("elemental_workshop_spiralstairs")
    /** Children of both water controls (multis on gate1 / gate2). */
    val valves = listOf(find("elemental_workshop_valve_1_red"), find("elemental_workshop_valve_1_green"))
    val water_lever = find("elemental_workshop_water_lever")
    val bellows_broken = find("elemental_workshop_bellows_noanim")
    val air_lever = find("elemental_workshop_air_lever")
    /** North-west of the central room (Wiki: the leather crate). */
    val leather_box = find("elemental_workshop_box_7")
    /** North-east of the hatch (Wiki: the stone bowl). */
    val bowl_box = find("elemental_workshop_box_4")
    val needle_crates = find("elem_crate_3")
    val troughs = (1..5).map { find("elemental_workshop_trough_$it") }
    val furnace_out = find("elemental_workshop_furnace_out")
    val furnace_lit = find("elemental_workshop_furnace_lit")
    val workbench = find("elemental_workshop_workbench")
}

internal object EwObjs : ObjReferences() {
    val book = find("elemental_workshop_shield_book")
    val slashed_book = find("elemental_workshop_shield_book_slashed")
    val key = find("elemental_workshop_key")
    val knife = find("knife")
    val bowl = find("elemental_workshop_lava_bowl")
    val bowl_full = find("elemental_workshop_lava_bowl_full")
    val ore = find("elemental_workshop_ore")
    val bar = find("elemental_workshop_bar")
    val shield = find("elemental_shield")
    val mind_bar = find("elem_mind_bar")
    val rock_remains = find("elem1_qip_rockremains")
    val leather = find("leather")
    val needle = find("needle")
    val thread = find("thread")
    val coal = find("coal")
    val hammer = find("hammer")
    val tinderbox = find("tinderbox")
    val pickaxes: List<ObjType> =
        listOf(
            "bronze_pickaxe", "iron_pickaxe", "steel_pickaxe", "black_pickaxe", "mithril_pickaxe",
            "adamant_pickaxe", "rune_pickaxe", "dragon_pickaxe", "infernal_pickaxe", "crystal_pickaxe",
        ).map { find(it) }
}

internal object EwVarBits : VarBitReferences() {
    val quest = find("elementalworkshop")
    val book = find("elemental_workshop_book")
    val key = find("elemental_workshop_key")
    val gate1 = find("elemental_workshop_gate1")
    val gate2 = find("elemental_workshop_gate2")
    val wheel = find("elemental_workshop_switch")
    val bellows = find("elemental_workshop_bellows")
    val fire = find("elemental_workshop_fire")
    val bellows_switch = find("elemental_workshop_bellows_switch")
    val stairs = find("elemental_workshop_stairs")
    val leather = find("elemental_workshop_leather")
    val finished = find("elemental_workshop_finished")
}
