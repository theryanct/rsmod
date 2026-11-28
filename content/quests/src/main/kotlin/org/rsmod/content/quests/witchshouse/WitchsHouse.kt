package org.rsmod.content.quests.witchshouse

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
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
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Witch's House (OSRS Wiki). `ballquest` varp (cache db end state 7); our steps: 1 the boy's ball,
 * 2 the magnet on the mouse unlocked the garden door, 3 the witch's experiment beaten in her shed
 * (four forms: glob, spider, bear, wolf; each death brings the next), 7 complete (6,325 Hitpoints
 * xp, 4 QP).
 *
 * The door key is under the potted plant; the basement gate shocks bare hands (worn leather
 * gloves let you through) and its cupboard holds the magnet; cheese at the mouse hole brings the
 * mouse out. The shed key is in the garden fountain. The ball is the world's ground spawn in the
 * shed.
 *
 * Not done: Nora T. Hagg's garden patrol catching you (she just wanders); the basement boxes'
 * random leather gloves (bring your own).
 */
class WitchsHouse
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(WitchsHouse::class.java, "witchs_house") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, WhNpcs.boy, "Talk-to") { talkBoy(it) }
        onLocOpLabel(locTypes, WhLocs.pot, "Look-under") { lookUnderPot() }
        onLocOpLabel(locTypes, WhLocs.front_door, "Open") { frontDoor(it) }
        onLocOpLabel(locTypes, WhLocs.ladder_down, "Climb-down") { telejump(BASEMENT) }
        onLocOpLabel(locTypes, WhLocs.ladder_up, "Climb-up") { telejump(HOUSE) }
        for (gate in WhLocs.shock_gates) {
            onLocOpLabel(locTypes, gate, "Open") { shockGate(it) }
        }
        onLocOpLabel(locTypes, WhLocs.cupboard_shut, "Open") {
            locRepo.change(it, WhLocs.cupboard_open, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, WhLocs.cupboard_open, "Shut") {
            locRepo.change(it, WhLocs.cupboard_shut, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, WhLocs.cupboard_open, "Search") { searchCupboard() }
        onOpLocU(WhLocs.mouse_hole, WhObjs.cheese) { cheeseAtHole() }
        onOpNpcU(WhNpcs.mouse, WhObjs.magnet) { magnetOnMouse(it.npc) }
        onLocOpLabel(locTypes, WhLocs.garden_door, "Open") { gardenDoor(it) }
        onLocOpLabel(locTypes, WhLocs.fountain, "Check") { checkFountain() }
        onLocOpLabel(locTypes, WhLocs.shed_door, "Open") { shedDoor(it) }
        onOpObj3(WhObjs.ball) { takeBall(it.obj) }
        onEvent<NpcKilledEvent> { experimentKilled(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(WITCHS_HOUSE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(WITCHS_HOUSE, value)
    }

    private suspend fun ProtectedAccess.talkBoy(npc: Npc) {
        when {
            state == 0 -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is below level 10 combat:" to {
                                    player.appearance.combatLevel < RECOMMENDED_COMBAT
                                }
                            ),
                        options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                    )
                runQuestSection(npc, dialogue, "Getting started", hooks)
            }
            state == COMPLETE ->
                startDialogue(npc) { chatNpc(happy, "Thanks again for getting my ball back!") }
            carries(WhObjs.ball) -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "You give the ball back." to {
                                    invDel(inv, WhObjs.ball)
                                    false
                                }
                            )
                    )
                runQuestSection(npc, dialogue, "Finishing up", hooks)
                statAdvance(stats.hitpoints, REWARD_XP * xpMods.get(player, stats.hitpoints))
                completeQuest(WITCHS_HOUSE)
            }
            else -> runQuestSection(npc, dialogue, "Talking to him again")
        }
    }

    /* The house */

    private suspend fun ProtectedAccess.lookUnderPot() {
        if (carries(WhObjs.door_key)) {
            mes("You find nothing else under the flower pot.")
            return
        }
        runQuestSection(null, dialogue, "Searching the plant pot for the key")
        invAddOrDrop(objRepo, WhObjs.door_key)
    }

    private suspend fun ProtectedAccess.frontDoor(door: BoundLocInfo) {
        if (!carries(WhObjs.door_key) && !insideHouse()) {
            mes("The door is locked.")
            return
        }
        passThroughWall(door)
    }

    private fun ProtectedAccess.insideHouse(): Boolean {
        val c = player.coords
        return c.level == 0 && c.x in HOUSE_X && c.z in HOUSE_Z
    }

    private suspend fun ProtectedAccess.shockGate(gate: BoundLocInfo) {
        if (invTotal(worn, WhObjs.leather_gloves) == 0) {
            runQuestSection(null, dialogue, "Attempting to open the gate without correct gloves")
            return
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        if (carries(WhObjs.magnet)) {
            mes("The cupboard is empty.")
            return
        }
        runQuestSection(null, dialogue, "Searching the cupboard for the magnet")
        invAddOrDrop(objRepo, WhObjs.magnet)
    }

    private suspend fun ProtectedAccess.cheeseAtHole() {
        if (state != STARTED) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, WhObjs.cheese)
        runQuestSection(null, dialogue, "After dropping cheese in front of the mouse hole")
        val mouse = Npc(npcTypes[WhNpcs.mouse], MOUSE_SPOT)
        npcRepo.add(mouse, MOUSE_TICKS)
    }

    private suspend fun ProtectedAccess.magnetOnMouse(mouse: Npc) {
        if (state != STARTED) {
            mes("The garden door is already unlocked.")
            return
        }
        invDel(inv, WhObjs.magnet)
        runQuestSection(null, dialogue, "Using a magnet on the mouse")
        if (mouse.isSlotAssigned) npcRepo.del(mouse, Int.MAX_VALUE)
        setState(DOOR_UNLOCKED)
    }

    private suspend fun ProtectedAccess.gardenDoor(door: BoundLocInfo) {
        if (state < DOOR_UNLOCKED) {
            runQuestSection(null, dialogue, GARDEN_LOCKED)
            return
        }
        passThroughWall(door)
    }

    /* The garden and the shed */

    private suspend fun ProtectedAccess.checkFountain() {
        if (carries(WhObjs.shed_key)) {
            runQuestSection(null, dialogue, "Searching the fountain again whilst holding the key")
            return
        }
        runQuestSection(null, dialogue, "Searching the fountain in the garden")
        invAddOrDrop(objRepo, WhObjs.shed_key)
    }

    private suspend fun ProtectedAccess.shedDoor(door: BoundLocInfo) {
        if (!carries(WhObjs.shed_key)) {
            mes("The shed door is locked.")
            return
        }
        passThroughWall(door)
        if (state == DOOR_UNLOCKED && npcRepo.findAll(EXPERIMENT_SPOT).none { isExperiment(it) }) {
            spawn(0, EXPERIMENT_SPOT)
        }
    }

    private fun isExperiment(npc: Npc): Boolean = WhNpcs.forms.any { it.id == npc.type.id }

    private fun spawn(form: Int, at: CoordGrid) {
        npcRepo.add(Npc(npcTypes[WhNpcs.forms[form]], at), FORM_TICKS)
    }

    private fun experimentKilled(event: NpcKilledEvent) {
        val form = WhNpcs.forms.indexOfFirst { it.id == event.npc.type.id }
        if (form < 0) return
        val player = event.hero
        if (form < WhNpcs.forms.lastIndex) {
            spawn(form + 1, event.npc.coords)
            player.mes("The experiment shifts into a new form!")
            return
        }
        if (player.questState(WITCHS_HOUSE) == DOOR_UNLOCKED) {
            player.setQuestState(WITCHS_HOUSE, DEFEATED)
            player.mes("You have defeated the witch's experiment. You can take the ball now.")
        }
    }

    private suspend fun ProtectedAccess.takeBall(obj: Obj) {
        if (state < DEFEATED || state == COMPLETE) {
            mes("As you reach for the ball, you feel a strange weakness wash over you.")
            mes("Something is guarding it.")
            return
        }
        if (carries(WhObjs.ball)) {
            mes("You already have the ball.")
            return
        }
        if (inv.isFull() || !objRepo.del(obj)) return
        invAdd(inv, WhObjs.ball)
    }

    companion object {
        const val STARTED = 1
        const val DOOR_UNLOCKED = 2
        const val DEFEATED = 3
        const val COMPLETE = 7

        private const val RECOMMENDED_COMBAT = 10
        private const val REWARD_XP = 6325.0
        private const val CUPBOARD_TICKS = 200
        private const val MOUSE_TICKS = 50
        private const val FORM_TICKS = 300

        private val HOUSE_X = 2901..2907
        private val HOUSE_Z = 3466..3476
        private val BASEMENT = CoordGrid(2906, 9876, 0)
        private val HOUSE = CoordGrid(2906, 3476, 0)
        private val MOUSE_SPOT = CoordGrid(2903, 3467, 0)
        private val EXPERIMENT_SPOT = CoordGrid(2936, 3464, 0)

        private const val GARDEN_LOCKED = "Attempting to open the garden door without unlocking it"

        val WITCHS_HOUSE =
            QuestDef(
                name = "Witch's House",
                varp = { WhVarps.ballquest },
                complete = COMPLETE,
                questPoints = 4,
                rewards = listOf("6,325 Hitpoints XP"),
                scrollObj = { WhObjs.ball },
            )
    }
}

// Every cache ref Witch's House uses (re-check after cache updates).

internal object WhNpcs : NpcReferences() {
    val boy = find("ballboy")
    val mouse = find("witchrat")
    val forms =
        listOf(
            find("shapeshifterglob"),
            find("shapeshifterspider"),
            find("shapeshifterbear"),
            find("shapeshifterwolf"),
        )
}

internal object WhLocs : LocReferences() {
    val pot = find("witchpot")
    val front_door = find("witchhousedoor")
    val garden_door = find("witchbackdoor")
    val shed_door = find("witchsheddoor")
    val fountain = find("witchfountain")
    val shock_gates = listOf(find("shockgatel"), find("shockgater"))
    val cupboard_shut = find("magnetcbshut")
    val cupboard_open = find("magnetcbopen")
    val mouse_hole = find("witchmousehole")
    val ladder_down = find("grim_witch_ladder_down")
    val ladder_up = find("grim_witch_ladder_up")
}

internal object WhObjs : ObjReferences() {
    val ball = find("ball")
    val door_key = find("witches_doorkey")
    val shed_key = find("witches_shedkey")
    val magnet = find("magnet")
    val cheese = find("cheese")
    val leather_gloves = find("leather_gloves")
}

internal object WhVarps : VarpReferences() {
    val ballquest = find("ballquest")
}
