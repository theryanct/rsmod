package org.rsmod.content.quests.lostcity

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Lost City (OSRS Wiki). `zanaris` varp (cache db end state 6); ours: 1 the warrior at the
 * Lumbridge Swamp adventurers' camp let slip about Zanaris and the leprechaun, 2 Shamus (out of the
 * tree that only has "Chop") sent you for a Dramen staff, 3 the tree spirit guarding the Dramen
 * tree killed, 6 complete on first stepping into Zanaris through the swamp shed with the staff
 * wielded (3 QP; dragon dagger/longsword and cosmic runecrafting follow from Zanaris).
 *
 * Also here: the Monks of Entrana's boat between Port Sarim and Entrana (sailTo), refusing anyone
 * carrying or wearing weapons or armour, and Entrana's dungeon ladder. Dramen branches come from
 * the tree with an axe (Woodcutting 36) and a knife makes the staff (Crafting 31).
 *
 * Not done: the Entrana dungeon's prayer drain and the cave monk's warning beyond his dialogue;
 * Zanaris's own content (Fairytale I etc.).
 */
class LostCity
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(LostCity::class.java, "lost_city") }

    override fun ScriptContext.startup() {
        for ((index, adventurer) in LcNpcs.adventurers.withIndex()) {
            onNpcOpLabel(npcTypes, adventurer, "Talk-to") { talkAdventurer(it, index) }
        }
        onLocOpLabel(locTypes, LcLocs.leprechaun_tree, "Chop") { chopLeprechaunTree() }
        onNpcOpLabel(npcTypes, LcNpcs.shamus, "Talk-to") { talkShamus(it) }

        // Entrana
        for (monk in LcNpcs.port_sarim_monks) {
            onNpcOpLabel(npcTypes, monk, "Take-boat") { boat(TO_ENTRANA) }
            onNpcOpLabel(npcTypes, monk, "Talk-to") { talkShipMonk(it, TO_ENTRANA) }
        }
        for (monk in LcNpcs.entrana_monks) {
            onNpcOpLabel(npcTypes, monk, "Take-boat") { boat(TO_PORT_SARIM) }
            onNpcOpLabel(npcTypes, monk, "Talk-to") { talkShipMonk(it, TO_PORT_SARIM) }
        }
        onNpcOpLabel(npcTypes, LcNpcs.cave_monk, "Talk-to") {
            runQuestSection(it, dialogue, "Cave monk")
        }
        onLocOpLabel(locTypes, LcLocs.entrana_ladder, "Climb-down") { telejump(ENTRANA_DUNGEON) }

        // The Dramen tree and Zanaris
        onLocOpLabel(locTypes, LcLocs.dramen_tree, "Chop down") { chopDramen() }
        onEvent<NpcKilledEvent> { spiritKilled(this) }
        onOpHeldU(LcObjs.knife, LcObjs.dramen_branch) { carveStaff() }
        onLocOpLabel(locTypes, LcLocs.shed_door, "Open") { shedDoor(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(LOST_CITY)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(LOST_CITY, value)
    }

    /* The adventurers' camp and Shamus */

    private suspend fun ProtectedAccess.talkAdventurer(npc: Npc, index: Int) {
        val name = ADVENTURERS[index]
        if (index == WARRIOR && state == 0) {
            var started = false
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            START_WARNING to {
                                player.appearance.combatLevel < RECOMMENDED_COMBAT &&
                                    (statBase(stats.crafting) < CRAFTING_LEVEL ||
                                        statBase(stats.woodcutting) < WOODCUTTING_LEVEL)
                            }
                        ),
                    options = mapOf("Yes." to { started = true }),
                )
            runQuestSection(npc, dialogue, "Warrior", hooks)
            if (started) setState(STARTED)
            return
        }
        val section =
            when (state) {
                0 -> name
                STARTED -> "Talking to $name again after learning about Zanaris"
                else -> "Talking to $name after talking to Shamus"
            }
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.chopLeprechaunTree() {
        if (npcRepo.findAll(LEPRECHAUN_SPOT).any { it.type.id == LcNpcs.shamus.id }) {
            runQuestSection(null, dialogue, SHAMUS_OUT)
            return
        }
        npcRepo.add(Npc(npcTypes[LcNpcs.shamus], LEPRECHAUN_SPOT), SHAMUS_TICKS)
        mes("A leprechaun jumps down from the tree!")
    }

    private suspend fun ProtectedAccess.talkShamus(npc: Npc) {
        val section =
            when {
                state == 0 -> "Talking to Shamus before starting the quest"
                state == STARTED -> "First talk"
                else -> "Subsequent talks"
            }
        runQuestSection(npc, dialogue, section)
        if (state == STARTED) setState(MET_SHAMUS)
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
    }

    /* Entrana */

    private suspend fun ProtectedAccess.talkShipMonk(npc: Npc, dest: CoordGrid) {
        val toEntrana = dest == TO_ENTRANA
        var sail = false
        startDialogue(npc) {
            if (toEntrana) {
                chatNpc(
                    neutral,
                    "Do you seek passage to holy Entrana? If so, you must leave your weaponry " +
                        "and armour behind. This is Saradomin's will.",
                )
            } else {
                chatNpc(neutral, "Do you wish to leave holy Entrana?")
            }
            sail = choice2("Yes, okay, I'm ready to go.", true, "No, not right now.", false)
        }
        if (sail) boat(dest)
    }

    private suspend fun ProtectedAccess.boat(dest: CoordGrid) {
        if (dest == TO_ENTRANA && carriesArms()) {
            mes("The monks won't let you take weapons or armour to Entrana.")
            return
        }
        sailTo(dest, fare = 0, message = "You board the ship.", collision = collision)
    }

    /** Anything worn, or carried, that goes in a weapon or armour slot. */
    private fun ProtectedAccess.carriesArms(): Boolean {
        val all = worn.objs.filterNotNull() + inv.objs.filterNotNull()
        return all.any { objTypes[it].wearpos1 in ARMS_SLOTS }
    }

    /* The Dramen tree */

    private suspend fun ProtectedAccess.chopDramen() {
        val alive = npcRepo.findAll(DRAMEN_TREE).any { it.type.id == LcNpcs.tree_spirit.id }
        when {
            state < MET_SHAMUS -> mes("You decide not to chop down this ancient-looking tree.")
            state == MET_SHAMUS && alive ->
                runQuestSection(null, dialogue, SPIRIT_ALIVE)
            state == MET_SHAMUS -> {
                runQuestSection(null, dialogue, "Attempting to chop down the tree")
                npcRepo.add(Npc(npcTypes[LcNpcs.tree_spirit], SPIRIT_SPOT), SPIRIT_TICKS)
                mes("A Tree Spirit appears!")
            }
            !hasAxe() -> mes("You need an axe to chop this tree.")
            statBase(stats.woodcutting) < WOODCUTTING_LEVEL ->
                mes("You need a Woodcutting level of $WOODCUTTING_LEVEL to chop this tree.")
            inv.isFull() -> mes("Your inventory is too full to hold any more branches.")
            else -> {
                invAdd(inv, LcObjs.dramen_branch)
                mes("You cut a branch from the Dramen tree.")
            }
        }
    }

    private fun ProtectedAccess.hasAxe(): Boolean {
        val all = worn.objs.filterNotNull() + inv.objs.filterNotNull()
        return all.any {
            val name = objTypes[it].internalName.orEmpty()
            name.endsWith("_axe") && !name.contains("pickaxe")
        }
    }

    private fun spiritKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != LcNpcs.tree_spirit.id) return
        val player = event.hero
        if (player.questState(LOST_CITY) != MET_SHAMUS) return
        player.setQuestState(LOST_CITY, SPIRIT_DEAD)
        player.mes("With the Tree Spirit defeated you can now chop the tree.")
    }

    private suspend fun ProtectedAccess.carveStaff() {
        if (statBase(stats.crafting) < CRAFTING_LEVEL) {
            mes("You need a Crafting level of $CRAFTING_LEVEL to make a Dramen staff.")
            return
        }
        invDel(inv, LcObjs.dramen_branch)
        invAdd(inv, LcObjs.dramen_staff)
        mes("You carve the branch into a staff.")
    }

    /* Zanaris */

    private suspend fun ProtectedAccess.shedDoor(door: BoundLocInfo) {
        val wielding = invTotal(worn, LcObjs.dramen_staff) > 0
        if (!wielding || state < SPIRIT_DEAD) {
            passThroughWall(door)
            return
        }
        mes("The world starts to shimmer...")
        telejump(ZANARIS)
        if (state < COMPLETE) completeQuest(LOST_CITY)
    }

    companion object {
        const val STARTED = 1
        const val MET_SHAMUS = 2
        const val SPIRIT_DEAD = 3
        const val COMPLETE = 6

        private const val RECOMMENDED_COMBAT = 45
        private const val CRAFTING_LEVEL = 31
        private const val WOODCUTTING_LEVEL = 36
        private const val SHAMUS_TICKS = 100
        private const val SPIRIT_TICKS = 300
        private const val WARRIOR = 0

        /** Head, weapon, body, shield, legs, hands, feet (wearpos). */
        private val ARMS_SLOTS = setOf(0, 3, 4, 5, 7, 9, 10)
        private val ADVENTURERS = listOf("Warrior", "Archer", "Wizard", "Monk")

        private val LEPRECHAUN_SPOT = CoordGrid(3139, 3211, 0)
        private val TO_ENTRANA = CoordGrid(2834, 3335, 0)
        private val TO_PORT_SARIM = CoordGrid(3048, 3234, 0)
        private val ENTRANA_DUNGEON = CoordGrid(2821, 9777, 0)
        private val DRAMEN_TREE = CoordGrid(2860, 9734, 0)
        private val SPIRIT_SPOT = CoordGrid(2860, 9736, 0)
        private val ZANARIS = CoordGrid(2452, 4473, 0)

        private const val START_WARNING =
            "If the player has lower than 45 combat and either 31 Crafting or 36 Woodcutting:"
        private const val SHAMUS_OUT = "Attempting to chop the tree after Shamus has appeared"
        private const val SPIRIT_ALIVE =
            "Attempting to chop down the tree while the Tree Spirit is still alive"

        val LOST_CITY =
            QuestDef(
                name = "Lost City",
                varp = { LcVarps.zanaris },
                complete = COMPLETE,
                questPoints = 3,
                rewards = listOf("Access to Zanaris", "Dragon dagger and longsword"),
                scrollObj = { LcObjs.dramen_staff },
            )
    }
}

// Every cache ref Lost City uses (re-check after cache updates).

internal object LcNpcs : NpcReferences() {
    /** Warrior, Archer, Wizard, Monk (the camp). */
    val adventurers =
        listOf(
            find("warrioradventurerpg"),
            find("archeradventurerpg"),
            find("wizardadventuterpg"),
            find("monkadventurerpg"),
        )
    val shamus = find("zanarisleprechaun")
    val tree_spirit = find("tree_spirit")
    val cave_monk = find("cave_monk")
    val port_sarim_monks = listOf(find("shipmonk"), find("shipmonk1_b"), find("shipmonk1_c"))
    val entrana_monks = listOf(find("shipmonk2"), find("shipmonk2_b"), find("shipmonk2_c"))
}

internal object LcLocs : LocReferences() {
    val leprechaun_tree = find("leprechauntree")
    val entrana_ladder = find("entranaladdertop")
    val dramen_tree = find("dramentree")
    val shed_door = find("zanarisdoor")
}

internal object LcObjs : ObjReferences() {
    val knife = find("knife")
    val dramen_branch = find("dramen_branch")
    val dramen_staff = find("dramen_staff")
}

internal object LcVarps : VarpReferences() {
    val zanaris = find("zanaris")
}
