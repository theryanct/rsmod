package org.rsmod.content.quests.biohazard

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
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
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.plaguecity.PlagueCity.Companion.PLAGUE_CITY
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Biohazard (OSRS Wiki). Cache quest varp `biohazard`, end state 16 (needs Plague City):
 * - 1 Elena asks for her distillator; 2 Jerico's cupboard has bird feed (his pigeon cage lies
 *   behind the house); 3 Omart wants a distraction; 4 bird feed thrown at the watchtower fence and
 *   the pigeons released; 5 Omart's rope ladder over the wall (gas mask worn; Kilron's brings you
 *   back); 6 a rotten apple in the Mourner HQ stew; 7 Nurse Sarah's cupboard: a medical gown; worn,
 *   the HQ's front door opens; 8 the sickest mourner upstairs (killed) drops the gate key; the crate
 *   holds the distillator.
 * - 9 Elena tests it and hands over a plague sample, ethenea, liquid honey and sulphuric broline;
 *   10 the Chemist (Rimmington) gives touch paper; his errand boys smuggle the vials (Da Vinci the
 *   ethenea, Chancy the honey, Hops the broline: who got which is in `bio_progress`); the guard at
 *   south-east Varrock takes any vials still carried; 11 the boys hand them back at the Dancing
 *   Donkey; 12 Guidor (priest gown top and bottom worn) finds nothing; 14 Elena; 16 King Lathas:
 *   3 QP, 1,250 Thieving xp.
 * - Server varp `bio_progress` (65792): bits 0-1 Da Vinci's vial, 2-3 Chancy's, 4-5 Hops's
 *   (1 ethenea, 2 honey, 3 broline).
 *
 * Simplified: no cutscenes, the watchtower check is a radius, and the vials can't break.
 */
class Biohazard
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(Biohazard::class.java, "biohazard") }

    /** Per player: bird feed thrown at the watchtower this visit. */
    private val fed = HashSet<Int>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, BioNpcs.elena, BIOHAZARD, wants = { wantsElena() }) { talkElena(it) }
            register(npcTypes, BioNpcs.jerico, BIOHAZARD, wants = { state in STARTED until COMPLETE }) {
                talkJerico(it)
            }
            register(npcTypes, BioNpcs.omart, BIOHAZARD, wants = { state in STARTED until COMPLETE }) {
                talkOmart(it)
            }
            register(npcTypes, BioNpcs.kilron, BIOHAZARD, wants = { state >= OVER_WALL }) {
                runQuestSection(it, dialogue, "Talking to Kilron", ladderHooks())
                telejump(EAST_OF_WALL)
            }
            register(npcTypes, BioNpcs.nurse, BIOHAZARD, wants = { state in OVER_WALL until COMPLETE }) {
                runQuestSection(it, dialogue, "Talking to Nurse Sarah")
            }
            for (mourner in BioNpcs.outside_mourners) {
                register(npcTypes, mourner, BIOHAZARD, wants = { state in OVER_WALL until COMPLETE }) {
                    talkOutsideMourner(it)
                }
            }
            register(npcTypes, BioNpcs.inside_mourner, BIOHAZARD, wants = { state in GOWN until COMPLETE }) {
                runQuestSection(it, dialogue, "Talking to Mourner inside Mourner Headquarters")
            }
            register(npcTypes, BioNpcs.sick_mourner, BIOHAZARD, wants = { state == GOWN }) {
                runQuestSection(it, dialogue, "Talking to sickest Mourner upstairs")
                it.opPlayer2(player, aiInteractions)
            }
            register(npcTypes, BioNpcs.chemist, BIOHAZARD, wants = { state >= SAMPLES }) { talkChemist(it) }
            for ((name, boy) in BioNpcs.errand_boys) {
                register(npcTypes, boy.first, BIOHAZARD, wants = { state in SAMPLES until GUIDOR }) {
                    talkErrandBoy(it, name, varrock = false)
                }
                register(npcTypes, boy.second, BIOHAZARD, wants = { state in SAMPLES until GUIDOR }) {
                    talkErrandBoy(it, name, varrock = true)
                }
            }
            register(npcTypes, BioNpcs.julie, BIOHAZARD, wants = { state >= SAMPLES }) {
                val section = if (state >= GUIDOR) "Talking to Julie after talking to Guido" else "Talking to Julie after talking to the Chemist"
                runQuestSection(it, dialogue, section)
            }
            register(npcTypes, BioNpcs.guidor, BIOHAZARD, wants = { state >= SAMPLES }) { talkGuidor(it) }
            register(npcTypes, BioNpcs.lathas, BIOHAZARD, wants = { state == ELENA_TOLD }) { talkLathas(it) }
        }
        onLocOpLabel(locTypes, BioLocs.jerico_cupboard, "Open") { searchJericoCupboard() }
        onLocOpLabel(locTypes, BioLocs.jerico_cupboard_open, "Search") { searchJericoCupboard() }
        onHeldOpLabel(objTypes, BioObjs.birdfeed, "Throw") { throwFeed() }
        onHeldOpLabel(objTypes, BioObjs.pigeons, "Open") { releasePigeons() }
        onOpLocU(BioLocs.cauldron, BioObjs.rotten_apple) { poisonStew() }
        onLocOpLabel(locTypes, BioLocs.nurse_cupboard, "Open") { searchNurseCupboard() }
        onLocOpLabel(locTypes, BioLocs.nurse_cupboard_open, "Search") { searchNurseCupboard() }
        for (door in BioLocs.hq_doors) onLocOpLabel(locTypes, door, "Open") { hqDoor(it) }
        for (gate in BioLocs.hq_gates) onLocOpLabel(locTypes, gate, "Open") { hqGate(it) }
        onLocOpLabel(locTypes, BioLocs.crate, "Search") { searchCrate() }
        for (gate in BioLocs.varrock_gates) onLocOpLabel(locTypes, gate, "Open") { varrockGate(it) }
        onLocOpLabel(locTypes, BioLocs.guidor_door, "Open") { guidorDoor(it) }
        onEvent<NpcKilledEvent> { mournerKilled(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BIOHAZARD)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(BIOHAZARD, value)
    }

    private fun ProtectedAccess.give(obj: ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    private fun ProtectedAccess.wearing(obj: ObjType): Boolean = invTotal(worn, obj) > 0

    private fun ProtectedAccess.spaceHooks(extra: Map<String, () -> Boolean> = emptyMap()) =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has no inventory space:" to { inv.freeSpace() == 0 },
                    "If the player is not wearing a gas mask:" to { !wearing(BioObjs.gasmask) },
                ) + extra
        )

    /* Elena, Jerico, Omart and the wall */

    private fun ProtectedAccess.wantsElena(): Boolean =
        (state == 0 && player.isQuestComplete(PLAGUE_CITY)) || state in STARTED until COMPLETE

    private suspend fun ProtectedAccess.talkElena(npc: Npc) {
        when {
            state == 0 -> {
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                runQuestSection(npc, dialogue, "Talking to Elena", hooks)
            }
            state < DISTILLATOR || (state == DISTILLATOR && !carries(BioObjs.distillator)) ->
                runQuestSection(npc, dialogue, "Talking to Elena without the distillator")
            state == DISTILLATOR -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "Elena gives you a plague sample along with three vials." to {
                                    invDel(inv, BioObjs.distillator)
                                    for (obj in BioObjs.samples) give(obj)
                                    false
                                }
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Elena with the distillator", hooks)
                setState(SAMPLES)
            }
            state in SAMPLES until GUIDOR -> {
                val missing = BioObjs.samples.any { !carries(it) }
                val hooks =
                    spaceHooks(
                        mapOf(
                            "If the player has all the items:" to { !missing },
                            "If the player is missing items:" to { missing },
                        )
                    )
                runQuestSection(npc, dialogue, "Talking to Elena again before visiting the chemist", hooks)
                if (state == SAMPLES) for (obj in BioObjs.samples) give(obj)
            }
            state == GUIDOR -> {
                runQuestSection(npc, dialogue, "Talking to Elena after talking to Guidor")
                setState(ELENA_TOLD)
            }
            state == ELENA_TOLD -> runQuestSection(npc, dialogue, "Talking to Elena again before talking to the King")
        }
    }

    private suspend fun ProtectedAccess.talkJerico(npc: Npc) {
        when {
            state == STARTED -> {
                runQuestSection(npc, dialogue, "Talking to Jerico")
                setState(JERICO)
            }
            state == JERICO -> runQuestSection(npc, dialogue, "Talking to Jerico again")
            state == OMART -> runQuestSection(npc, dialogue, "Talking to Jerico after talking to Omart")
            state == DISTRACTED -> runQuestSection(npc, dialogue, "Talking to Jerico after freeing the birds")
            else -> runQuestSection(npc, dialogue, "Talking to Jerico after entering West Ardougne")
        }
    }

    private suspend fun ProtectedAccess.searchJericoCupboard() {
        if (state < JERICO) {
            mes("You search the cupboard but find nothing of interest.")
            return
        }
        val hooks = spaceHooks(mapOf("If the player already has bird feed:" to { carries(BioObjs.birdfeed) }))
        runQuestSection(null, dialogue, "Searching the cupboard", hooks)
        if (inv.freeSpace() > 0) give(BioObjs.birdfeed)
    }

    private fun ladderHooks() =
        SimpleQuestHooks(conditions = mapOf("If the player is not wearing a gas mask:" to { false }))

    private suspend fun ProtectedAccess.talkOmart(npc: Npc) {
        when {
            state == JERICO -> {
                runQuestSection(npc, dialogue, "Talking to Omart", spaceHooks())
                setState(OMART)
            }
            state == OMART -> runQuestSection(npc, dialogue, "Talking to Omart again")
            state == DISTRACTED -> {
                if (!wearing(BioObjs.gasmask)) {
                    runQuestSection(npc, dialogue, "Talking to Omart after freeing the birds", spaceHooks())
                    return
                }
                runQuestSection(npc, dialogue, "Talking to Omart after freeing the birds", spaceHooks())
                setState(OVER_WALL)
                telejump(WEST_OF_WALL)
            }
            state in OVER_WALL until SAMPLES -> {
                runQuestSection(npc, dialogue, "Talking to Omart after entering West Ardougne", spaceHooks())
                if (wearing(BioObjs.gasmask)) telejump(WEST_OF_WALL)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Omart after returning the distillator")
        }
    }

    private suspend fun ProtectedAccess.throwFeed() {
        val near = player.coords.chebyshevDistance(WATCHTOWER) <= WATCHTOWER_RANGE
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is not near the watchtower fence:" to { !near },
                        "If the player is near the fence:" to { near },
                    )
            )
        runQuestSection(null, dialogue, "Throwing bird feed", hooks)
        if (near) {
            invDel(inv, BioObjs.birdfeed)
            fed += player.uid.packed
        }
    }

    private suspend fun ProtectedAccess.releasePigeons() {
        val ready = player.uid.packed in fed && player.coords.chebyshevDistance(WATCHTOWER) <= WATCHTOWER_RANGE
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is in the wrong place or has not thrown the bird feed:" to { !ready },
                        "If the player has thrown the bird feed and is near the watchtower fence:" to { ready },
                    )
            )
        runQuestSection(null, dialogue, "Opening pigeon cage", hooks)
        if (!ready) return
        invDel(inv, BioObjs.pigeons)
        invAdd(inv, BioObjs.pigeon_cage_empty)
        fed -= player.uid.packed
        if (state == OMART) setState(DISTRACTED)
    }

    /* The Mourner HQ */

    private suspend fun ProtectedAccess.poisonStew() {
        if (state != OVER_WALL) return
        invDel(inv, BioObjs.rotten_apple)
        runQuestSection(null, dialogue, "Using rotten apple on cauldron")
        setState(STEW)
    }

    private suspend fun ProtectedAccess.searchNurseCupboard() {
        val hooks =
            spaceHooks(
                mapOf(
                    "If the player has not yet poisoned the stew or already has a medical gown:" to {
                        state < STEW || carries(BioObjs.gown) || wearing(BioObjs.gown)
                    }
                )
            )
        runQuestSection(null, dialogue, "Searching cupboard by Nurse Sarah", hooks)
        if (state >= STEW && !carries(BioObjs.gown) && !wearing(BioObjs.gown) && inv.freeSpace() > 0) {
            give(BioObjs.gown)
        }
    }

    private suspend fun ProtectedAccess.talkOutsideMourner(npc: Npc) {
        when {
            state < STEW -> runQuestSection(npc, dialogue, "Talking to Mourner outside Mourner Headquarters")
            !wearing(BioObjs.gown) ->
                runQuestSection(npc, dialogue, "Talking to Mourner outside Mourner Headquarters after poisoning the stew")
            else -> {
                runQuestSection(npc, dialogue, "Talking to Mourner outside Mourner Headquarters while wearing gown")
                if (state == STEW) setState(GOWN)
            }
        }
    }

    private suspend fun ProtectedAccess.hqDoor(door: BoundLocInfo) {
        val inside = player.coords.z in HQ_INSIDE_Z
        when {
            inside -> {
                runQuestSection(null, dialogue, "Opening back door of the Mourner Headquarters from the inside")
                passThroughWall(door)
            }
            state >= GOWN && wearing(BioObjs.gown) -> passThroughWall(door)
            else -> runQuestSection(null, dialogue, "Opening back door of the Mourner Headquarters")
        }
    }

    private suspend fun ProtectedAccess.hqGate(gate: BoundLocInfo) {
        val inside = player.coords.x > gate.coords.x
        if (inside || carries(BioObjs.key)) {
            if (!inside) {
                runQuestSection(
                    null,
                    dialogue,
                    "Opening the gate (when entering)",
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the key:" to { true },
                                "If the player does not have the key:" to { false },
                            )
                    ),
                )
            }
            passThroughWall(gate)
            return
        }
        runQuestSection(
            null,
            dialogue,
            "Opening the gate (when entering)",
            SimpleQuestHooks(conditions = mapOf("If the player does not have the key:" to { true })),
        )
    }

    private fun mournerKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != BioNpcs.sick_mourner.id) return
        val player = event.hero
        if (player.questState(BIOHAZARD) != GOWN) return
        player.invAddOrDrop(objRepo, BioObjs.key)
        player.mes("The mourner drops a key.")
    }

    private suspend fun ProtectedAccess.searchCrate() {
        val hooks =
            spaceHooks(mapOf("If the player already has the distillator:" to { carries(BioObjs.distillator) }))
        runQuestSection(null, dialogue, "Searching the distillator crate", hooks)
        if (state >= GOWN && state < SAMPLES && !carries(BioObjs.distillator) && inv.freeSpace() > 0) {
            give(BioObjs.distillator)
            if (state == GOWN) setState(DISTILLATOR)
        }
    }

    /* The Chemist, his errand boys and Varrock */

    private suspend fun ProtectedAccess.talkChemist(npc: Npc) {
        if (!carries(BioObjs.touch_paper) && state == SAMPLES && !hasFlag(CHEMIST_MET)) {
            val hooks =
                SimpleQuestHooks(boxes = mapOf("The chemist gives you some touch paper." to { give(BioObjs.touch_paper); false }))
            runQuestSection(npc, dialogue, "Talking to Chemist", hooks)
            setFlag(CHEMIST_MET)
            return
        }
        val hooks =
            spaceHooks(
                mapOf(
                    "If the player still has the touch paper:" to { carries(BioObjs.touch_paper) },
                    "If the player does not have the touch paper:" to { !carries(BioObjs.touch_paper) },
                )
            )
        runQuestSection(npc, dialogue, "Talking to the Chemist again", hooks)
        give(BioObjs.touch_paper)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[BioVarps.progress]

    private fun ProtectedAccess.hasFlag(bit: Int): Boolean = (flags() and (1 shl bit)) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, BioVarps.progress, flags() or (1 shl bit))
    }

    private fun ProtectedAccess.heldBy(name: String): Int {
        val shift = BOY_SHIFTS.getValue(name)
        return (flags() shr shift) and VIAL_MASK
    }

    private fun ProtectedAccess.setHeldBy(name: String, vial: Int) {
        val shift = BOY_SHIFTS.getValue(name)
        val cleared = flags() and (VIAL_MASK shl shift).inv()
        VarPlayerIntMapSetter.set(player, BioVarps.progress, cleared or (vial shl shift))
    }

    private suspend fun ProtectedAccess.talkErrandBoy(npc: Npc, name: String, varrock: Boolean) {
        val held = heldBy(name)
        if (!varrock) {
            if (!hasFlag(CHEMIST_MET)) {
                runQuestSection(npc, dialogue, "Talking to $name in Rimmington before talking to the Chemist")
                return
            }
            if (held != 0) {
                runQuestSection(npc, dialogue, "Talking to $name in Rimmington after giving him a vial")
                return
            }
            var given = 0
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player does not have ethenea:" to { !carries(BioObjs.ethenea) },
                            "If the player does not have liquid honey:" to { !carries(BioObjs.honey) },
                            "If the player does not have sulphuric broline:" to { !carries(BioObjs.broline) },
                        ),
                    boxes =
                        mapOf(
                            "You give him the vial of ethenea." to { invDel(inv, BioObjs.ethenea); given = ETHENEA; false },
                            "You give him the vial of liquid honey." to { invDel(inv, BioObjs.honey); given = HONEY; false },
                            "You give him the vial of sulphuric broline." to {
                                invDel(inv, BioObjs.broline)
                                given = BROLINE
                                false
                            },
                        ),
                )
            runQuestSection(npc, dialogue, "Talking to $name in Rimmington after talking to the Chemist", hooks)
            if (given != 0) setHeldBy(name, given)
            return
        }
        when {
            !hasFlag(CHEMIST_MET) || held == 0 ->
                runQuestSection(npc, dialogue, "Talking to $name in Varrock before talking to the Chemist")
            held != BOY_VIALS.getValue(name) -> {
                runQuestSection(npc, dialogue, "Talking to $name in Varrock after giving him the wrong vial")
                setHeldBy(name, 0)
            }
            else -> {
                val section =
                    when (name) {
                        "Chancy" -> "Talking to Chancy in Varrock after giving him honey"
                        "Da Vinci" -> "Talking to Da Vinci in Varrock after giving him ethenea"
                        else -> "Talking to Hops in Varrock after giving him broline"
                    }
                runQuestSection(npc, dialogue, section)
                give(VIAL_OBJS.getValue(held)())
                setHeldBy(name, 0)
                if (BOY_SHIFTS.keys.all { heldBy(it) == 0 } && BioObjs.vials.all { carries(it) }) {
                    if (state == SAMPLES) setState(VIALS)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.varrockGate(gate: BoundLocInfo) {
        val entering = player.coords.x < gate.coords.x
        if (!entering) {
            if (BioObjs.vials.any { carries(it) }) {
                runQuestSection(null, dialogue, "Exiting south-east Varrock while holding the vials")
            }
            passThroughWall(gate)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has ethenea:" to { carries(BioObjs.ethenea) },
                        "If the player has liquid honey:" to { carries(BioObjs.honey) },
                        "If the player has sulphuric broline:" to { carries(BioObjs.broline) },
                    ),
                boxes =
                    mapOf(
                        "He takes the vial of ethenea from you." to { invDel(inv, BioObjs.ethenea); false },
                        "He takes the vial of liquid honey from you." to { invDel(inv, BioObjs.honey); false },
                        "He takes the vial of sulphuric broline from you." to { invDel(inv, BioObjs.broline); false },
                    ),
            )
        if (state in SAMPLES until COMPLETE) runQuestSection(null, dialogue, "Entering south-east Varrock", hooks)
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.guidorDoor(door: BoundLocInfo) {
        val dressed = wearing(BioObjs.priest_top) && wearing(BioObjs.priest_bottom)
        if (state >= SAMPLES && !dressed && player.coords.z < door.coords.z) {
            val section =
                if (hasFlag(CHEMIST_MET)) "Entering Guidor's room after talking to the Chemist"
                else "Entering Guidor's room before talking to the Chemist"
            runQuestSection(null, dialogue, section)
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkGuidor(npc: Npc) {
        if (state == VIALS && carries(BioObjs.touch_paper) && carries(BioObjs.sample) &&
            BioObjs.vials.all { carries(it) }
        ) {
            runQuestSection(npc, dialogue, "Talking to Guidor")
            invDel(inv, BioObjs.touch_paper)
            invDel(inv, BioObjs.sample)
            for (vial in BioObjs.vials) invDel(inv, vial)
            setState(GUIDOR)
            return
        }
        runQuestSection(npc, dialogue, if (state >= COMPLETE) "Talking to Guidor after finishing the quest" else "Talking to Guidor again")
    }

    private suspend fun ProtectedAccess.talkLathas(npc: Npc) {
        runQuestSection(npc, dialogue, "Talking to King Lathas after talking to Elena about the plague")
        statAdvance(stats.thieving, 1250.0 * xpMods.get(player, stats.thieving))
        completeQuest(BIOHAZARD)
    }

    companion object {
        const val STARTED = 1
        const val JERICO = 2
        const val OMART = 3
        const val DISTRACTED = 4
        const val OVER_WALL = 5
        const val STEW = 6
        const val GOWN = 7
        const val DISTILLATOR = 8
        const val SAMPLES = 9
        const val VIALS = 11
        const val GUIDOR = 12
        const val ELENA_TOLD = 14
        const val COMPLETE = 16

        private const val ETHENEA = 1
        private const val HONEY = 2
        private const val BROLINE = 3
        private const val VIAL_MASK = 3
        private const val CHEMIST_MET = 6
        private const val WATCHTOWER_RANGE = 3
        /** The HQ lies between its two doors (2551,3320 and 2551,3328). */
        private val HQ_INSIDE_Z = 3321..3327

        private val BOY_SHIFTS = mapOf("Da Vinci" to 0, "Chancy" to 2, "Hops" to 4)
        /** The vial each boy has no reason to steal (Wiki). */
        private val BOY_VIALS = mapOf("Da Vinci" to ETHENEA, "Chancy" to HONEY, "Hops" to BROLINE)
        private val VIAL_OBJS: Map<Int, () -> ObjType> =
            mapOf(ETHENEA to { BioObjs.ethenea }, HONEY to { BioObjs.honey }, BROLINE to { BioObjs.broline })

        /** The watchtower north of Omart, and either side of the wall at Omart's ladder. */
        private val WATCHTOWER = CoordGrid(2562, 3271, 0)
        private val WEST_OF_WALL = CoordGrid(2556, 3266, 0)
        private val EAST_OF_WALL = CoordGrid(2560, 3265, 0)

        val BIOHAZARD =
            QuestDef(
                name = "Biohazard",
                varp = { BioVarps.quest },
                complete = COMPLETE,
                questPoints = 3,
                rewards = listOf("1,250 Thieving XP", "Free passage through the West Ardougne gate"),
                scrollObj = { BioObjs.distillator },
                listRow = "quest_biohazard",
            )
    }
}

// Every cache ref Biohazard uses (re-check after cache updates).

internal object BioNpcs : NpcReferences() {
    val elena = find("elena2_vis")
    val jerico = find("jerico")
    val omart = find("omart_vis")
    val kilron = find("kilron_vis")
    val nurse = find("bionurse")
    val outside_mourners = listOf(find("mourner_armed_guard_vis"), find("mourner_armed_vis"))
    val inside_mourner = find("mournerstew1_vis")
    val sick_mourner = find("mournerstew3_vis")
    val chemist = find("chemist")
    val julie = find("guidors_wife")
    val guidor = find("guidor")
    val lathas = find("kinglathas_vis")

    /** Each errand boy: (Rimmington, Varrock). */
    val errand_boys =
        listOf(
            "Chancy" to (find("gambler1") to find("gambler2")),
            "Da Vinci" to (find("artist1") to find("artist2")),
            "Hops" to (find("drunk1") to find("drunk2")),
        )
}

internal object BioLocs : LocReferences() {
    val jerico_cupboard = find("jericoscupboardshut")
    val jerico_cupboard_open = find("jericoscupboardopen")
    val cauldron = find("mournercauldron")
    val nurse_cupboard = find("bionursescupboardshut")
    val nurse_cupboard_open = find("bionursescupboardopen")
    val hq_doors = listOf(find("mournerstewdoor"))
    val hq_gates = listOf(find("mournerquaters_gatel"), find("mournerquaters_gater"))
    val crate = find("mournercrateup")
    val varrock_gates = listOf(find("guidorgatelclosed"), find("guidorgaterclosed"))
    val guidor_door = find("guidordoor")
}

internal object BioObjs : ObjReferences() {
    val birdfeed = find("birdfeed")
    val pigeons = find("pigeons")
    val pigeon_cage_empty = find("pigeoncage")
    val gasmask = find("gasmask")
    val rotten_apple = find("rottenapples")
    val gown = find("doctor_gown")
    val key = find("mournerkeytw")
    val distillator = find("distillator")
    val sample = find("plaguesample")
    val ethenea = find("ethenea")
    val honey = find("liquid_honey")
    val broline = find("sulphuric_broline")
    val touch_paper = find("touch_paper")
    val priest_top = find("priest_gown")
    val priest_bottom = find("priest_robe")
    val vials = listOf(ethenea, honey, broline)
    val samples = listOf(sample, ethenea, honey, broline)
}

internal object BioVarps : VarpReferences() {
    val quest = find("biohazard")

    /** Server varp (65792): see [Biohazard]. */
    val progress = find("bio_progress")
}

internal object BioVarpBuilds : VarpBuilder() {
    init {
        build("bio_progress")
    }
}
