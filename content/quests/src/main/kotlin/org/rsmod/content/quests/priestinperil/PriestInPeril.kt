package org.rsmod.content.quests.priestinperil

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
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
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Priest in Peril (OSRS Wiki). `priestperil` varp (cache db end state 60; the cache shows Drezel
 * in his cell from 4 and in the mausoleum from 8). Ours: 1 King Roald's errand, 2 the "monks" asked
 * you to kill the dog in the mausoleum, 3 the temple guardian killed, 4 the monks told, 5 Roald's
 * fury (go back and fix it; the temple door now opens), 6 met Drezel through his cell bars,
 * 7 cell unlocked with the iron key (swapped for the hooded monk's golden key at the southern
 * monument), 8 the vampyre's coffin sealed with blessed Salve water, 9 Drezel reinforcing the
 * barrier (wants 50 unnoted rune or pure essence; server varp `pip_essence` (65782) counts them),
 * 60 complete (wolfbane dagger, 1,406 Prayer xp, 1 QP).
 *
 * The holy barrier in the mausoleum lets the player into Morytania only after the quest.
 *
 * Not done: the guardian refusing magic attacks; Kudos.
 */
class PriestInPeril
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(PriestInPeril::class.java, "priest_in_peril")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Drezel" to PipNpcs.drezel, "King Roald" to PipNpcs.roald)
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) { default(npcTypes, PipNpcs.roald) { talkRoald(it) } }
        for (door in PipLocs.temple_doors) {
            onLocOpLabel(locTypes, door, "Open") { templeDoor(it) }
        }
        for (stairs in PipLocs.stairs) {
            onLocOpLabel(locTypes, stairs, "Climb-up") {
                telejump(player.coords.translateLevel(1))
            }
        }
        onLocOpLabel(locTypes, PipLocs.trapdoor, "Open") {
            if (atTemple(it)) {
                locRepo.change(it, PipLocs.trapdoor_open, TRAPDOOR_TICKS)
            } else {
                mes("The trapdoor won't budge.")
            }
        }
        onLocOpLabel(locTypes, PipLocs.trapdoor_open, "Climb-down") {
            telejump(if (atTemple(it)) MAUSOLEUM else it.coords.translate(0, CELLAR_OFFSET))
        }
        onLocOpLabel(locTypes, PipLocs.east_trapdoor, "Open") {
            locRepo.change(it, PipLocs.east_trapdoor_open, TRAPDOOR_TICKS)
        }
        onLocOpLabel(locTypes, PipLocs.east_trapdoor_open, "Climb-down") { telejump(BARRIER_WEST) }

        onEvent<NpcKilledEvent> { killed(this) }
        onNpcOpLabel(npcTypes, PipNpcs.drezel, "Talk-to") { talkDrezel(it) }
        onLocOpLabel(locTypes, PipLocs.cell_gate, "Open") { cellGate(it) }
        onOpLocU(PipLocs.cell_gate, PipObjs.golden_key) {
            runQuestSection(null, dialogue, "Using the golden key on the lock")
        }
        onLocOpLabel(locTypes, PipLocs.coffin, "Open") {
            val section =
                if (state >= SEALED) "Attempting to open the coffin again" else COFFIN_OPEN
            runQuestSection(null, dialogue, section, speakers = speakers)
        }
        onOpLocU(PipLocs.coffin, PipObjs.bucket_water) {
            runQuestSection(null, dialogue, "Attempting to pour normal water on the coffin")
        }
        onOpLocU(PipLocs.coffin, PipObjs.murky_water) {
            runQuestSection(null, dialogue, "Attempting to pour murky water on the coffin")
        }
        onOpLocU(PipLocs.coffin, PipObjs.blessed_water) { sealCoffin() }

        // The mausoleum
        onLocOpLabel(locTypes, PipLocs.monument_gate, "Open") { gate(it, needsKey = true) }
        onLocOpLabel(locTypes, PipLocs.drezel_gate, "Open") { gate(it, needsKey = false) }
        for ((index, monument) in PipLocs.monuments.withIndex()) {
            onLocOpLabel(locTypes, monument, "Study") { studyMonument(index) }
            onLocOpLabel(locTypes, monument, "Take-from") {
                mes("The ${MONUMENT_ITEMS[index]} is fixed firmly to the monument.")
            }
            onOpLocU(monument, PipObjs.golden_key) { goldenKeyOnMonument(index) }
        }
        onLocOpLabel(locTypes, PipLocs.well, "Inspect") {
            runQuestSection(null, dialogue, "Searching the well")
        }
        onOpLocU(PipLocs.well, PipObjs.bucket) { fillBucket() }
        onLocOpLabel(locTypes, PipLocs.barrier, "Pass-through") { passBarrier(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(PRIEST_IN_PERIL)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(PRIEST_IN_PERIL, value)
    }

    private fun atTemple(loc: BoundLocInfo): Boolean =
        loc.coords.x in TEMPLE_X && loc.coords.z in TEMPLE_Z

    /* King Roald */

    private suspend fun ProtectedAccess.talkRoald(npc: Npc) {
        var accepted = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is below level 15 combat:" to {
                            player.appearance.combatLevel < RECOMMENDED_COMBAT
                        }
                    ),
                options = mapOf("Yes." to { accepted = true }),
            )
        val section =
            when (state) {
                0 -> "Starting the quest"
                STARTED -> "Talking to King Roald again"
                ASKED -> "Talking to King Roald after accepting to kill the dog"
                DOG_DEAD,
                TOLD_MONKS -> "Talking to King Roald about the now dead dog"
                SENT_BACK -> "Talking to him again"
                MET_DREZEL,
                UNLOCKED -> "Updating King Roald"
                SEALED -> "Updating King Roald (2)"
                BARRIER -> "Talking to King Roald again (2)"
                else -> "Talking to King Roald"
            }
        runQuestSection(npc, dialogue, section, hooks)
        when {
            section == "Starting the quest" && accepted -> setState(STARTED)
            state == DOG_DEAD || state == TOLD_MONKS -> setState(SENT_BACK)
        }
    }

    /* The temple */

    private suspend fun ProtectedAccess.templeDoor(door: BoundLocInfo) {
        when {
            state == 0 -> runQuestSection(null, dialogue, "At the temple before starting the quest")
            state == STARTED -> {
                var sent = false
                val hooks =
                    SimpleQuestHooks(
                        options = mapOf("King Roald sent me to check on Drezel." to { sent = true })
                    )
                runQuestSection(null, dialogue, "At the temple", hooks)
                if (sent) setState(ASKED)
            }
            state == ASKED ->
                runQuestSection(null, dialogue, KNOCK_AFTER_DOG_JOB)
            state == DOG_DEAD -> {
                runQuestSection(null, dialogue, "Letting the monks know you did the deed")
                setState(TOLD_MONKS)
            }
            state == TOLD_MONKS -> runQuestSection(null, dialogue, "Talking to the monks again")
            else -> passThroughWall(door)
        }
    }

    private fun killed(event: NpcKilledEvent) {
        val player = event.hero
        val id = event.npc.type.id
        val state = player.questState(PRIEST_IN_PERIL)
        when {
            PipNpcs.guardians.any { it.id == id } && state == ASKED -> {
                player.setQuestState(PRIEST_IN_PERIL, DOG_DEAD)
                player.mes("You killed the temple guardian. You'd better tell the monks.")
            }
            PipNpcs.hooded_monks.any { it.id == id } && state in SENT_BACK until UNLOCKED -> {
                objRepo.add(PipObjs.golden_key, event.npc.coords, KEY_DROP_TICKS, player)
            }
        }
    }

    /* Drezel and the cell */

    private suspend fun ProtectedAccess.talkDrezel(npc: Npc) {
        if (player.coords.z > UNDERGROUND_Z) {
            talkDrezelMausoleum(npc)
            return
        }
        val murky = carries(PipObjs.murky_water)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has murky water:" to { murky },
                        "If the player doesn't have murky water:" to { !murky },
                        "First time blessing water:" to { true },
                    ),
                boxes =
                    mapOf(
                        "Drezel inspects the water. He then says some sort of prayer." to {
                            if (carries(PipObjs.murky_water)) {
                                invDel(inv, PipObjs.murky_water)
                                invAdd(inv, PipObjs.blessed_water)
                            }
                            false
                        }
                    ),
            )
        val section =
            when {
                state < SENT_BACK -> null
                state == SENT_BACK -> "Meeting Drezel"
                state == MET_DREZEL && carries(PipObjs.iron_key) -> DREZEL_IRON_KEY
                state == MET_DREZEL && murky -> DREZEL_MURKY
                state == MET_DREZEL && carries(PipObjs.golden_key) -> DREZEL_GOLDEN_KEY
                state == MET_DREZEL -> "Talking to Drezel again"
                state == UNLOCKED && murky -> DREZEL_IRON_KEY
                state == UNLOCKED && carries(PipObjs.blessed_water) -> DREZEL_BLESSED
                state == UNLOCKED -> "Talking to Drezel again"
                else -> "Talking to Drezel again (2)"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(sad, "Please... help...") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        when {
            section == "Meeting Drezel" -> setState(MET_DREZEL)
            section == DREZEL_IRON_KEY && state == MET_DREZEL -> setState(UNLOCKED)
        }
    }

    private suspend fun ProtectedAccess.cellGate(gate: BoundLocInfo) {
        if (state >= UNLOCKED) {
            passThroughWall(gate)
            return
        }
        if (carries(PipObjs.iron_key) && state == MET_DREZEL) {
            mes("You use the key to unlock the cell gate.")
            setState(UNLOCKED)
            passThroughWall(gate)
            return
        }
        mes("The cell gate is locked.")
    }

    private suspend fun ProtectedAccess.sealCoffin() {
        if (state != UNLOCKED) return
        runQuestSection(null, dialogue, "Pouring blessed water on the coffin")
        invDel(inv, PipObjs.blessed_water)
        invAdd(inv, PipObjs.bucket)
        setState(SEALED)
    }

    /* The mausoleum */

    private suspend fun ProtectedAccess.gate(gate: BoundLocInfo, needsKey: Boolean) {
        val open =
            if (needsKey) {
                state >= UNLOCKED || carries(PipObjs.golden_key) || carries(PipObjs.iron_key)
            } else {
                state >= SEALED
            }
        if (!open) {
            runQuestSection(null, dialogue, "Attempting to open the gate")
            return
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.studyMonument(index: Int) {
        mes("A monument to a fallen priest. It holds a ${MONUMENT_ITEMS[index]}.")
    }

    private suspend fun ProtectedAccess.goldenKeyOnMonument(index: Int) {
        if (index != KEY_MONUMENT) {
            runQuestSection(null, dialogue, "Interacting with the other monuments")
            mes("Nothing interesting happens.")
            return
        }
        runQuestSection(null, dialogue, "Exchanging the golden key with the iron key")
        invDel(inv, PipObjs.golden_key)
        invAdd(inv, PipObjs.iron_key)
    }

    private suspend fun ProtectedAccess.fillBucket() {
        invDel(inv, PipObjs.bucket)
        invAdd(inv, PipObjs.murky_water)
        runQuestSection(null, dialogue, "Filling a bucket from the well")
    }

    private suspend fun ProtectedAccess.talkDrezelMausoleum(npc: Npc) {
        when (state) {
            SEALED -> {
                runQuestSection(npc, dialogue, "Repairing the barrier")
                setState(BARRIER)
            }
            BARRIER -> deliverEssence(npc)
            COMPLETE -> runQuestSection(npc, dialogue, "Talking to Drezel again (4)")
            else -> startDialogue(npc) { chatNpc(neutral, "I must hold this barrier.") }
        }
    }

    private suspend fun ProtectedAccess.deliverEssence(npc: Npc) {
        val have = PipObjs.essences.sumOf { invTotal(inv, it) }
        if (have == 0) {
            val noted = PipObjs.noted_essences.any { carries(it) }
            val section = if (noted) "Bringing Drezel noted essence" else DREZEL_ESSENCE_WAIT
            runQuestSection(npc, dialogue, section)
            return
        }
        val delivered = player.vars[PipVarps.essence]
        var give = minOf(have, ESSENCE_NEEDED - delivered)
        val total = delivered + give
        for (ess in PipObjs.essences) {
            val take = minOf(give, invTotal(inv, ess))
            if (take > 0) invDel(inv, ess, take)
            give -= take
        }
        VarPlayerIntMapSetter.set(player, PipVarps.essence, total)
        if (total < ESSENCE_NEEDED) {
            runQuestSection(npc, dialogue, "Bringing Drezel essence")
            mes("Drezel still needs ${ESSENCE_NEEDED - total} essence.")
            return
        }
        runQuestSection(npc, dialogue, "Giving Drezel the last of the essence")
        invAddOrDrop(objRepo, PipObjs.wolfbane)
        statAdvance(stats.prayer, REWARD_XP * xpMods.get(player, stats.prayer))
        completeQuest(PRIEST_IN_PERIL)
    }

    private suspend fun ProtectedAccess.passBarrier(barrier: BoundLocInfo) {
        if (state < COMPLETE) {
            runQuestSection(null, dialogue, BARRIER_EARLY, speakers = speakers)
            return
        }
        passThroughWall(barrier)
    }

    companion object {
        const val STARTED = 1
        const val ASKED = 2
        const val DOG_DEAD = 3
        const val TOLD_MONKS = 4
        const val SENT_BACK = 5
        const val MET_DREZEL = 6
        const val UNLOCKED = 7
        const val SEALED = 8
        const val BARRIER = 9
        const val COMPLETE = 60

        private const val RECOMMENDED_COMBAT = 15
        private const val ESSENCE_NEEDED = 50
        private const val REWARD_XP = 1406.0
        private const val TRAPDOOR_TICKS = 100
        private const val KEY_DROP_TICKS = 200
        private const val CELLAR_OFFSET = 6400
        private const val UNDERGROUND_Z = 9000

        /** The southern monument (grave_base4) holds the iron key (OSRS Wiki). */
        private const val KEY_MONUMENT = 3
        private val MONUMENT_ITEMS =
            listOf(
                "golden tinderbox",
                "golden candle",
                "golden pot",
                "iron key",
                "golden hammer",
                "golden feather",
                "golden needle",
            )

        private val TEMPLE_X = 3400..3430
        private val TEMPLE_Z = 3480..3510
        private val MAUSOLEUM = CoordGrid(3405, 9906, 0)
        private val BARRIER_WEST = CoordGrid(3438, 9886, 0)

        private const val KNOCK_AFTER_DOG_JOB = "Knocking on the door after accepting to kill the dog"
        private const val COFFIN_OPEN = "Attempting to open the coffin on the top floor"
        private const val DREZEL_GOLDEN_KEY = "Talking to Drezel with the golden key"
        private const val DREZEL_MURKY =
            "Talking to Drezel with murky water before unlocking the cell"
        private const val DREZEL_IRON_KEY = "Talking to Drezel with the iron key"
        private const val DREZEL_BLESSED = "Talking to Drezel with blessed water"
        private const val DREZEL_ESSENCE_WAIT = "Talking to Drezel again (3)"
        private const val BARRIER_EARLY =
            "Attempting to pass the holy barrier before securing the barrier"

        val PRIEST_IN_PERIL =
            QuestDef(
                name = "Priest in Peril",
                varp = { PipVarps.priestperil },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,406 Prayer XP", "Wolfbane dagger", "Access to Morytania"),
                scrollObj = { PipObjs.wolfbane },
            )
    }
}

// Every cache ref Priest in Peril uses (re-check after cache updates).

internal object PipNpcs : NpcReferences() {
    val roald = find("king_roald")

    /* Child of the priestperiltrappedmonk (cell, from 4) and ..2 (mausoleum, from 8) multis. */
    val drezel = find("priestperiltrappedmonk_vis")
    val guardians = listOf(find("priestperilguarddog"), find("priestperil_guardian_model"))

    /* The hooded level 30 monk (priestperilevilmonk3 multi) drops the golden key. */
    val hooded_monks = listOf(find("priestperilevilmonk3"), find("priestperilevilmonk3_vis"))
}

internal object PipLocs : LocReferences() {
    val temple_doors = listOf(find("priestperiltempledoorl"), find("priestperiltempledoorr"))
    val stairs = listOf(find("paterdomus_spiralstairs"), find("paterdomus_spiralstairs_m"))
    val trapdoor = find("trapdoor")
    val trapdoor_open = find("trapdoor_open")
    val east_trapdoor = find("pipeastsidetrapdoor")
    val east_trapdoor_open = find("pipeastsidetrapdoor_open")
    val cell_gate = find("pip_prisondoor")
    val coffin = find("priestperil_coffin_noanim")
    val monument_gate = find("pip_underground_door1")
    val drezel_gate = find("pip_underground_door2")
    val monuments =
        listOf(
            find("priestperil_grave_base1"),
            find("priestperil_grave_base2"),
            find("priestperil_grave_base3"),
            find("priestperil_grave_base4"),
            find("priestperil_grave_base5"),
            find("priestperil_grave_base6"),
            find("priestperil_grave_base7"),
        )
    val well = find("priestperil_well")
    val barrier = find("pip_underground_wall_side_withportal")
}

internal object PipObjs : ObjReferences() {
    val golden_key = find("pipkey_gold")
    val iron_key = find("pipkey_iron")
    val bucket = find("bucket_empty")
    val bucket_water = find("bucket_water")
    val murky_water = find("bucket_murkywater")
    val blessed_water = find("bucket_blessedwater")
    val wolfbane = find("dagger_wolfbane")
    val essences = listOf(find("blankrune"), find("blankrune_high"))
    val noted_essences = listOf(find("cert_blankrune"), find("cert_blankrune_high"))
}

internal object PipVarps : VarpReferences() {
    val priestperil = find("priestperil")

    /** Server varp (65782): essence delivered to Drezel. */
    val essence = find("pip_essence")
}

internal object PipVarpBuilds : VarpBuilder() {
    init {
        build("pip_essence")
    }
}
