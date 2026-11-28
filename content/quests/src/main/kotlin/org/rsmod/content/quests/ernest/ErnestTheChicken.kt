package org.rsmod.content.quests.ernest

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.type.builders.varp.VarpBuilder
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
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Ernest the Chicken (OSRS Wiki): Veronica outside Draynor Manor -> Professor Oddenstein needs a
 * pressure gauge (poisoned fish food in the fountain, then search it), a rubber tube (key from the
 * compost heap opens the closet) and an oil can (manor basement). `haunted` varp (cache db end
 * state 3): 0, 1 started, 2 met the professor, 3 complete. Parts can be handed in one at a time
 * (saved with the poisoned-fountain flag in the server varp `ernest_progress`).
 *
 * Basement lever puzzle: levers A-F are multilocs on the cache varbits `ernestlever_a..f`
 * (0 up, 1 down) and the nine doors on `ernestdoor_*` (0 closed, 1 ajar with "Open"). After every
 * pull each door's varbit is recomputed from the OSRS Wiki "Draynor Manor" table of required
 * lever positions; an open door lets the player through. Reward: 300 coins, 4 QP.
 */
class ErnestTheChicken
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(ErnestTheChicken::class.java, "ernest_the_chicken")
    }

    /* `ernest_progress` varp: bits 0-2 parts handed in (gauge, tube, oil can), bit 3 fountain
     * poisoned. */
    private fun ProtectedAccess.progress(): Int = player.vars[ErnestVarps.progress]

    private fun ProtectedAccess.setProgress(value: Int) {
        VarPlayerIntMapSetter.set(player, ErnestVarps.progress, value)
    }

    private val ProtectedAccess.fountainPoisoned: Boolean
        get() = progress() and FOUNTAIN_BIT != 0

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, ErnestNpcs.veronica, "Talk-to") { talkVeronica(it) }
        onNpcOpLabel(npcTypes, ErnestNpcs.oddenstein, "Talk-to") { talkProfessor(it) }
        onOpHeldU(ErnestObjs.poison, ErnestObjs.fish_food) { poisonFood() }
        onOpLocU(ErnestLocs.fountain, ErnestObjs.poisoned_fish_food) { poisonFountain() }
        onOpLocU(ErnestLocs.fountain, ErnestObjs.fish_food) {
            runQuestSection(null, dialogue, "Using regular fish food on the fountain")
        }
        onLocOpLabel(locTypes, ErnestLocs.fountain, "Search") { searchFountain() }
        onLocOpLabel(locTypes, ErnestLocs.compost_heap, "Search") { searchCompost() }
        onLocOpLabel(locTypes, ErnestLocs.closet_door, "Open") { closetDoor(it) }
        for ((index, levers) in ErnestLocs.levers.withIndex()) {
            for (lever in levers) {
                onLocOpLabel(locTypes, lever, "Pull") { pullLever(index) }
            }
        }
        onLocOpLabel(locTypes, ErnestLocs.door_ajar, "Open") { basementDoor(it) }
    }

    /* Lever puzzle */

    private suspend fun ProtectedAccess.pullLever(index: Int) {
        val varbit = ErnestVarBits.levers[index]
        val down = player.vars[varbit] == 1
        anim(ErnestSeqs.pull_lever)
        delay(1)
        VarPlayerIntMapSetter.set(player, varbit, if (down) 0 else 1)
        mes("You pull lever ${'A' + index} ${if (down) "up" else "down"}.")
        updateDoors()
    }

    /** Door open (1) when every lever matches its required position from the Wiki table. */
    private fun ProtectedAccess.updateDoors() {
        val levers = ErnestVarBits.levers.map { player.vars[it] == 1 }
        for ((door, rule) in DOOR_RULES) {
            val open =
                rule.withIndex().all { (lever, want) ->
                    when (want) {
                        'D' -> levers[lever]
                        'U' -> !levers[lever]
                        else -> true
                    }
                }
            val varbit = ErnestVarBits.doors.getValue(door)
            VarPlayerIntMapSetter.set(player, varbit, if (open) 1 else 0)
        }
    }

    /** Through an open basement door: doors face east-west (rotation 0/2) or north-south. */
    private suspend fun ProtectedAccess.basementDoor(door: BoundLocInfo) {
        val at = door.coords
        val here = player.coords
        val dest =
            if (door.angleId % 2 == 0) {
                at.translate(if (here.x < at.x) 1 else -1, 0)
            } else {
                at.translate(0, if (here.z < at.z) 1 else -1)
            }
        telejump(dest)
        delay(1)
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(ERNEST)

    private suspend fun ProtectedAccess.talkVeronica(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Aha, sounds like a quest. I'll help." to {
                            if (state == 0) player.setQuestState(ERNEST, STARTED)
                        },
                    ),
            )
        val section =
            when (state) {
                0 -> "Starting off"
                STARTED -> "Veronica during quest"
                MET_PROFESSOR -> "Veronica during quest after finding out about Ernest"
                else -> "Talking to Veronica"
            }
        runQuestSection(npc, dialogue, section, hooks)
    }

    private suspend fun ProtectedAccess.talkProfessor(npc: Npc) {
        when (state) {
            0 ->
                startDialogue(npc) {
                    chatNpc(neutral, "Be careful in here, there's lots of dangerous equipment.")
                }
            STARTED -> {
                runQuestSection(npc, dialogue, "The Mad Scientist")
                player.setQuestState(ERNEST, MET_PROFESSOR)
            }
            MET_PROFESSOR -> handInParts(npc)
            else -> runQuestSection(npc, dialogue, "Post-quest")
        }
    }

    private suspend fun ProtectedAccess.handInParts(npc: Npc) {
        var given = progress() and ALL_PARTS
        for ((bit, part) in PARTS) {
            if (given and bit == 0 && carries(part())) {
                invDel(inv, part())
                given = given or bit
            }
        }
        setProgress((progress() and ALL_PARTS.inv()) or given)
        if (given == ALL_PARTS) {
            runQuestSection(npc, dialogue, "Finishing up")
            invAddOrDrop(objRepo, ErnestObjs.coins, 300)
            completeQuest(ERNEST)
            return
        }
        val section = if (given == 0) "Before returning any parts" else "Returning the parts"
        runQuestSection(npc, dialogue, section)
    }

    private fun ProtectedAccess.poisonFood() {
        invDel(inv, ErnestObjs.poison)
        invDel(inv, ErnestObjs.fish_food)
        invAdd(inv, ErnestObjs.poisoned_fish_food)
        mes("You poison the fish food.")
    }

    private suspend fun ProtectedAccess.poisonFountain() {
        invDel(inv, ErnestObjs.poisoned_fish_food)
        setProgress(progress() or FOUNTAIN_BIT)
        runQuestSection(null, dialogue, "Using poisoned food on the fountain")
    }

    private suspend fun ProtectedAccess.searchFountain() {
        when {
            carries(ErnestObjs.pressure_gauge) || state > MET_PROFESSOR ->
                runQuestSection(null, dialogue, FOUNTAIN_EMPTY)
            fountainPoisoned -> {
                runQuestSection(null, dialogue, FOUNTAIN_POISONED)
                invAddOrDrop(objRepo, ErnestObjs.pressure_gauge)
            }
            else -> runQuestSection(null, dialogue, FOUNTAIN_PIRANHAS)
        }
    }

    private suspend fun ProtectedAccess.searchCompost() {
        if (carries(ErnestObjs.closet_key) || state !in STARTED..MET_PROFESSOR) {
            mes("You search the compost heap but find nothing of interest.")
            return
        }
        if (!carries(ErnestObjs.spade)) {
            mes("You need a spade to dig through the compost heap.")
            return
        }
        runQuestSection(null, dialogue, "The key")
        invAddOrDrop(objRepo, ErnestObjs.closet_key)
    }

    /** The closet door is locked without the key; with it, step through (east-facing wall). */
    private suspend fun ProtectedAccess.closetDoor(door: BoundLocInfo) {
        if (!carries(ErnestObjs.closet_key)) {
            mes("This door is locked.")
            return
        }
        val inside = door.coords
        val outside = door.coords.translate(1, 0)
        val dest = if (player.coords == inside) outside else inside
        mes("You unlock the door.")
        telejump(dest)
        delay(1)
    }

    companion object {
        const val STARTED = 1
        const val MET_PROFESSOR = 2
        private const val ALL_PARTS = 0b111
        private const val FOUNTAIN_BIT = 0b1000

        /**
         * OSRS Wiki "Draynor Manor": required lever positions (A-F; D down, U up, x any) for each
         * basement door, keyed by the cache door varbit. Wiki door numbers -> cache doors from
         * the wiki's room layout: 1 4to7, 2 4to5, 3 5to8, 4 5to6, 5 3to6, 6 2to3, 7 1to2,
         * 8 2to5, 9 8to9. ("U?" / "D?" entries are taken as U / D.)
         */
        private val DOOR_RULES =
            listOf(
                "ernestdoor_4to7" to "DDUxUU",
                "ernestdoor_4to5" to "DxUxUU",
                "ernestdoor_5to8" to "xxxDUx",
                "ernestdoor_5to6" to "xxxDUx",
                "ernestdoor_3to6" to "xUxDxU",
                "ernestdoor_2to3" to "xUxDxD",
                "ernestdoor_1to2" to "UUxxDD",
                "ernestdoor_2to5" to "xxxDUD",
                "ernestdoor_8to9" to "xxDDxD",
            )
        private const val FOUNTAIN_EMPTY = "Searching the fountain after obtaining pressure gauge"
        private const val FOUNTAIN_POISONED =
            "Searching the fountain after piranhas have been poisoned"
        private const val FOUNTAIN_PIRANHAS =
            "Trying to search the fountain without poisoning the piranhas"

        private val PARTS =
            listOf(
                1 to { ErnestObjs.pressure_gauge },
                2 to { ErnestObjs.rubber_tube },
                4 to { ErnestObjs.oil_can },
            )

        val ERNEST =
            QuestDef(
                name = "Ernest the Chicken",
                varp = { ErnestVarps.haunted },
                complete = 3,
                questPoints = 4,
                rewards = listOf("300 coins"),
                scrollObj = { ErnestObjs.oil_can },
            )
    }
}

// Every cache ref Ernest the Chicken uses (re-check after the rev 243 cutover).

internal object ErnestNpcs : NpcReferences() {
    val veronica = find("veronica")
    val oddenstein = find("professor_oddenstein")
}

internal object ErnestLocs : LocReferences() {
    /** Up / down variants of levers A-F (multilocs on ernestlever_a..f). */
    val levers: List<List<LocType>> =
        ('a'..'f').map { listOf(find("lever${it}_up"), find("lever${it}_down")) }
    val door_ajar = find("ernest_doorajar")
    val fountain = find("hauntedfountain")
    val compost_heap = find("hauntedcompostheap")
    val closet_door = find("closet_door")
}

internal object ErnestObjs : ObjReferences() {
    val fish_food = find("fish_food")
    val poison = find("poison")
    val poisoned_fish_food = find("poisoned_fish_food")
    val pressure_gauge = find("pressure_gauge")
    val closet_key = find("closet_key")
    val rubber_tube = find("rubber_tube")
    val oil_can = find("oil_can")
    val spade = find("spade")
    val coins = find("coins")
}

internal object ErnestVarps : VarpReferences() {
    val haunted = find("haunted")

    /** Server varp (65771): parts handed in + fountain poisoned. */
    val progress = find("ernest_progress")
}

/** Saved per character (world symbol range 65700-65899). */
internal object ErnestVarpBuilds : VarpBuilder() {
    init {
        build("ernest_progress")
    }
}

internal object ErnestVarBits : VarBitReferences() {
    val levers = ('a'..'f').map { find("ernestlever_$it") }
    val doors =
        listOf("1to2", "2to3", "4to5", "5to6", "8to9", "2to5", "3to6", "4to7", "5to8")
            .associate { "ernestdoor_$it" to find("ernestdoor_$it") }
}

internal object ErnestSeqs : SeqReferences() {
    val pull_lever = find("human_pull_lever")
}
