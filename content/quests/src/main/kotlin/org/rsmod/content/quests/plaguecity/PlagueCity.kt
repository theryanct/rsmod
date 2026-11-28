package org.rsmod.content.quests.plaguecity

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
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
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Plague City (OSRS Wiki). `elenaquest` varp (cache db end state 29); the steps in between are
 * ours (the cache only keys Elena's prison npc on it): 1 Edmond's quest, 2 Alrena's gas mask
 * (for dwellberries; spare masks in her cupboard), 3 Edmond's plan, 4-7 buckets of water on the
 * mud patch (1-4), 8 dug into the sewer, 9 rope tied to the grill, 10 Edmond pulled it off,
 * 11 Jethick's book (for Elena's picture), 12 the book back to the Rehnisons, 13 Milli's story,
 * 14 the plague house refused, 15 the Clerk let you in, 16 Bravek's hangover-cure note,
 * 17 cure given, 18 the warrant, 19 Elena freed, 29 complete (Edmond: 2,425 Mining xp, an
 * Ardougne teleport scroll, 1 QP). The cache varbits in `elenaquest_extra_bits` drive the
 * world: Edmond down in the sewer (`can_see_edmond_up_top`), the dug hole, the checked grill, the
 * rope (`pipe`), Elena back home, and Jethick's picture flag.
 *
 * Entering West Ardougne needs the gas mask worn (the pipe); the manhole is the way back out.
 * Not done: the West Ardougne citizens' chatter, the Ardougne Teleport spell unlock (the scroll is
 * read and used up).
 */
class PlagueCity
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(PlagueCity::class.java, "plague_city") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Edmond" to PcNpcs.edmond,
            "Alrena" to PcNpcs.alrena,
            "Mourner" to PcNpcs.guard_mourner,
            "Mourner (2)" to PcNpcs.guard_mourner,
            "Head Mourner" to PcNpcs.head_mourner,
            "Elena" to PcNpcs.elena_prison,
            "Bravek" to PcNpcs.bravek,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, PcNpcs.edmond, "Talk-to") { talkEdmond(it) }
        onNpcOpLabel(npcTypes, PcNpcs.alrena, "Talk-to") { talkAlrena(it) }
        onOpNpcU(PcNpcs.alrena, PcObjs.dwellberries) { talkAlrena(it.npc) }
        onLocOpLabel(locTypes, PcLocs.cupboard_shut, "Open") {
            locRepo.change(it, PcLocs.cupboard_open, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, PcLocs.cupboard_open, "Close") {
            locRepo.change(it, PcLocs.cupboard_shut, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, PcLocs.cupboard_open, "Search") { searchCupboard() }

        // Edmond's garden and the sewer
        onOpLocU(PcLocs.mud, PcObjs.bucket_water) { pourWater() }
        QuestDigSpots.register { digMud() }
        onLocOpLabel(locTypes, PcLocs.hole, "Climb-down") { telejump(SEWER_LANDING) }
        onLocOpLabel(locTypes, PcLocs.mud_pile, "Climb") { telejump(GARDEN) }
        onLocOpLabel(locTypes, PcLocs.grill, "Open") { openGrill() }
        onOpLocU(PcLocs.grill, PcObjs.rope) { ropeOnGrill() }
        onLocOpLabel(locTypes, PcLocs.pipe, "Climb-up") { climbPipe() }
        onLocOpLabel(locTypes, PcLocs.manhole_closed, "Open") {
            locRepo.change(it, PcLocs.manhole_open, MANHOLE_TICKS)
            runQuestSection(null, dialogue, "Opening manhole cover")
        }
        onLocOpLabel(locTypes, PcLocs.manhole_open, "Climb-down") {
            runQuestSection(null, dialogue, "Climb-down manhole out of West Ardougne")
            telejump(SEWER_PIPE)
        }

        // West Ardougne
        onNpcOpLabel(npcTypes, PcNpcs.jethick, "Talk-to") { talkJethick(it) }
        onNpcOpLabel(npcTypes, PcNpcs.ted, "Talk-to") { talkTed(it) }
        onNpcOpLabel(npcTypes, PcNpcs.martha, "Talk-to") { talkMartha(it) }
        onNpcOpLabel(npcTypes, PcNpcs.milli, "Talk-to") { talkMilli(it) }
        for (door in PcLocs.plague_doors) {
            onLocOpLabel(locTypes, door, "Open") { plagueDoor(it) }
        }
        onNpcOpLabel(npcTypes, PcNpcs.mourner, "Talk-to") { talkGardenMourner(it) }
        onNpcOpLabel(npcTypes, PcNpcs.guard_mourner, "Talk-to") { talkMourner(it) }
        onNpcOpLabel(npcTypes, PcNpcs.head_mourner, "Talk-to") { talkHeadMourner(it) }
        onNpcOpLabel(npcTypes, PcNpcs.clerk, "Talk-to") { talkClerk(it) }
        onLocOpLabel(locTypes, PcLocs.bravek_door, "Open") { bravekDoor(it) }
        onNpcOpLabel(npcTypes, PcNpcs.bravek, "Talk-to") { talkBravek(it) }
        onOpNpcU(PcNpcs.bravek, PcObjs.hangover_cure) { talkBravek(it.npc) }
        onOpHeldU(PcObjs.chocolate_dust, PcObjs.bucket_milk) { mixChocolate() }
        onOpHeldU(PcObjs.snape_grass, PcObjs.chocolaty_milk) { mixSnapeGrass() }
        onLocOpLabel(locTypes, PcLocs.barrel, "Search") { searchBarrel() }
        onLocOpLabel(locTypes, PcLocs.stairs_down, "Walk-down") { telejump(BASEMENT) }
        onLocOpLabel(locTypes, PcLocs.stairs_up, "Walk-up") { telejump(PLAGUE_HOUSE) }
        onLocOpLabel(locTypes, PcLocs.elena_gate, "Open") { elenaGate(it) }
        onNpcOpLabel(npcTypes, PcNpcs.elena_prison, "Talk-to") { talkElena(it) }
        onHeldOpLabel(objTypes, PcObjs.scroll, "Read") { readScroll() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(PLAGUE_CITY)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(PLAGUE_CITY, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Boolean = player.vars[varbit] != 0

    private fun ProtectedAccess.setVar(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.hasMask(): Boolean =
        carries(PcObjs.gas_mask) || invTotal(worn, PcObjs.gas_mask) > 0

    private fun ProtectedAccess.wearingMask(): Boolean = invTotal(worn, PcObjs.gas_mask) > 0

    /* Edmond and Alrena */

    private suspend fun ProtectedAccess.talkEdmond(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes." to { if (state == 0) setState(STARTED) },
                        "Do you have any more of those scrolls?" to {
                            invAddOrDrop(objRepo, PcObjs.scroll)
                        },
                    ),
            )
        val section =
            when (state) {
                0 -> "Talking to Edmond"
                STARTED ->
                    if (carries(PcObjs.dwellberries)) {
                        "Talking to Edmond after getting dwellberries"
                    } else {
                        "Talking to Edmond before getting dwellberries"
                    }
                MASK -> "Talking to Edmond after getting the gas mask"
                PLAN -> "Talking to Edmond before pouring water on the mud patch"
                PLAN + 1 -> "Talking to Edmond after pouring one bucket of water on the mud patch"
                PLAN + 2 -> "Talking to Edmond after pouring two buckets of water on the mud patch"
                PLAN + 3 ->
                    "Talking to Edmond after pouring three buckets of water on the mud patch"
                SOFT -> "Talking to Edmond after pouring four buckets of water on the mud patch"
                DUG ->
                    if (bit(PcVarBits.checked_grill)) {
                        "Talking to Edmond after trying to pull the grill"
                    } else {
                        "Talking to Edmond in the tunnel"
                    }
                ROPE -> "Talking to Edmond after tying the rope to the grill on the pipe"
                in GRILL_OFF until FREED ->
                    if (bit(PcVarBits.picture_asked) && !carries(PcObjs.picture)) {
                        EDMOND_PICTURE
                    } else {
                        "Talking to Edmond after unlocking West Ardougne"
                    }
                FREED -> "Edmond"
                else -> "Talking to Edmond again before using the Ardougne teleport scroll"
            }
        runQuestSection(npc, dialogue, section, hooks, speakers)
        when (state) {
            MASK -> setState(PLAN)
            ROPE -> {
                setState(GRILL_OFF)
                mes("Edmond pulls the grill off the pipe.")
            }
            FREED -> {
                invAddOrDrop(objRepo, PcObjs.scroll)
                statAdvance(stats.mining, MINING_XP * xpMods.get(player, stats.mining))
                completeQuest(PLAGUE_CITY)
            }
        }
    }

    private suspend fun ProtectedAccess.talkAlrena(npc: Npc) {
        var masked = false
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "Alrena has given you a gas mask." to {
                            if (carries(PcObjs.dwellberries)) {
                                invDel(inv, PcObjs.dwellberries)
                                masked = true
                            }
                            false
                        },
                    ),
            )
        val section =
            when (state) {
                0 -> null
                STARTED ->
                    if (carries(PcObjs.dwellberries)) {
                        "Talking to Alrena after getting dwellberries"
                    } else {
                        "Talking to Alrena before getting dwellberries"
                    }
                MASK -> "Talking to Alrena after getting the gas mask"
                PLAN -> "Talking to Alrena before pouring water on the mud patch"
                in PLAN + 1 until SOFT ->
                    "Talking to Alrena after pouring one bucket of water on the mud patch"
                SOFT -> "Talking to Alrena after pouring four buckets of water on the mud patch"
                DUG,
                ROPE -> "Talking to Alrena after digging the tunnel"
                in GRILL_OFF until FREED ->
                    if (bit(PcVarBits.picture_asked) && !carries(PcObjs.picture)) {
                        ALRENA_PICTURE
                    } else {
                        "Talking to Alrena after unlocking West Ardougne"
                    }
                else -> "Talking to Alrena after freeing Elena"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(sad, "Oh, I do hope Elena is alright...") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (masked) {
            invAddOrDrop(objRepo, PcObjs.gas_mask)
            if (state == STARTED) setState(MASK)
        }
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player already has the gas mask:" to { hasMask() },
                        "If the player has no inventory space:" to {
                            !hasMask() && inv.isFull()
                        },
                        "If the player does not have the gas mask:" to {
                            !hasMask() && !inv.isFull()
                        },
                    ),
                boxes =
                    mapOf(
                        "You find a protective mask." to {
                            if (state >= MASK) invAdd(inv, PcObjs.gas_mask)
                            false
                        },
                    ),
            )
        runQuestSection(null, dialogue, "Searching the cupboard", hooks)
    }

    /* The mud patch, the sewer and the pipe */

    private suspend fun ProtectedAccess.pourWater() {
        val told = state in PLAN until SOFT
        val fourth = state == SOFT - 1
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If Edmond has not told the player to pour water:" to { !told },
                        "If this is the first, second, or third bucket of water:" to {
                            told && !fourth
                        },
                        "If this is the fourth bucket of water:" to { told && fourth },
                    ),
            )
        runQuestSection(null, dialogue, "Using bucket of water on the mud patch", hooks)
        if (!told) return
        invDel(inv, PcObjs.bucket_water)
        invAdd(inv, PcObjs.bucket)
        setState(state + 1)
    }

    /** The garden mud patch (via [QuestDigSpots]). */
    private suspend fun ProtectedAccess.digMud(): Boolean {
        val here = player.coords
        if (here.level != MUD.level || here.chebyshevDistance(MUD) > 1) return false
        if (state < SOFT) {
            runQuestSection(null, dialogue, "Digging the mud patch")
            return true
        }
        if (state == SOFT) {
            runQuestSection(null, dialogue, "Digging hole to make a tunnel")
            setVar(PcVarBits.dug_mud_pile, 1)
            setVar(PcVarBits.edmond_below, 1)
            setState(DUG)
            telejump(SEWER_LANDING)
            return true
        }
        return false
    }

    private suspend fun ProtectedAccess.openGrill() {
        runQuestSection(null, dialogue, "Open grill")
        setVar(PcVarBits.checked_grill, 1)
    }

    private suspend fun ProtectedAccess.ropeOnGrill() {
        if (state != DUG || !bit(PcVarBits.checked_grill)) {
            runQuestSection(null, dialogue, ROPE_EARLY)
            return
        }
        invDel(inv, PcObjs.rope)
        runQuestSection(null, dialogue, "Using rope on grill")
        setVar(PcVarBits.pipe, 1)
        setState(ROPE)
    }

    private suspend fun ProtectedAccess.climbPipe() {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the grill has not been removed:" to { state < GRILL_OFF },
                        "If the player is not wearing a gas mask:" to {
                            state >= GRILL_OFF && !wearingMask()
                        },
                    ),
            )
        runQuestSection(null, dialogue, "Climb-up pipe", hooks, speakers)
        if (state < GRILL_OFF || !wearingMask()) return
        setVar(PcVarBits.edmond_below, 0)
        telejump(WEST_ARDOUGNE)
    }

    /* Jethick and the Rehnisons */

    private suspend fun ProtectedAccess.talkJethick(npc: Npc) {
        var gaveBook = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not talked to Jethick before:" to {
                            !bit(PcVarBits.picture_asked)
                        },
                        "If the player has talked to Jethick before:" to {
                            bit(PcVarBits.picture_asked)
                        },
                        "If the player has no inventory space:" to { inv.isFull() },
                    ),
                boxes =
                    mapOf(
                        "Jethick gives you a book." to {
                            gaveBook = true
                            false
                        },
                    ),
            )
        when {
            state < GRILL_OFF -> startDialogue(npc) { chatNpc(neutral, "Clear off, stranger.") }
            state == GRILL_OFF && !carries(PcObjs.picture) -> {
                runQuestSection(npc, dialogue, JETHICK_NO_PICTURE, hooks)
                setVar(PcVarBits.picture_asked, 1)
            }
            state == GRILL_OFF -> runQuestSection(npc, dialogue, JETHICK_PICTURE, hooks)
            state == BOOK -> runQuestSection(npc, dialogue, JETHICK_AGAIN, hooks)
            else -> runQuestSection(npc, dialogue, "Talking to Jethick after delivering the book")
        }
        if (gaveBook && !carries(PcObjs.book)) {
            invAdd(inv, PcObjs.book)
            if (state == GRILL_OFF) setState(BOOK)
        }
    }

    private suspend fun ProtectedAccess.talkTed(npc: Npc) {
        val section =
            when {
                state < BOOK -> "Talking to Ted Rehnison before getting the book from Jethick"
                state == BOOK && carries(PcObjs.book) -> "Returning the book to Ted Rehnison"
                state == BOOK_BACK -> "Talking to Ted Rehnison"
                state in MILLI until FREED -> "Talking to Ted Rehnison after questioning Milli"
                else -> "Talking to Ted Rehnison after freeing Elena"
            }
        runQuestSection(npc, dialogue, section)
        if (section == "Returning the book to Ted Rehnison") {
            invDel(inv, PcObjs.book)
            setState(BOOK_BACK)
        }
    }

    private suspend fun ProtectedAccess.talkMartha(npc: Npc) {
        val section =
            when {
                state >= FREED -> "Talking to Martha after freeing Elena"
                state >= MILLI -> "Talking to Martha Rehnison after questioning Milli"
                else -> "Talking to Martha Rehnison"
            }
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.talkMilli(npc: Npc) {
        when {
            state == BOOK_BACK -> {
                runQuestSection(npc, dialogue, "Talking to Milli Rehnison")
                setState(MILLI)
            }
            state >= MILLI -> runQuestSection(npc, dialogue, MILLI_AFTER_DOOR)
            else -> startDialogue(npc) { chatNpc(sad, "...") }
        }
    }

    /* The plague house */

    private suspend fun ProtectedAccess.plagueDoor(door: BoundLocInfo) {
        when {
            state >= WARRANT && (state >= FREED || carries(PcObjs.warrant)) -> {
                runQuestSection(null, dialogue, DOOR_WARRANT, speakers = speakers)
                passThroughWall(door)
            }
            state == MILLI -> {
                runQuestSection(null, dialogue, DOOR_FIRST, speakers = speakers)
                setState(REFUSED)
            }
            else -> runQuestSection(null, dialogue, PLAGUE_DOOR_LATER, speakers = speakers)
        }
    }

    private suspend fun ProtectedAccess.talkGardenMourner(npc: Npc) {
        val section =
            when {
                state < PLAN -> "Talking to Mourner outside Edmond's house"
                state < DUG -> "Talking to Mourner outside Edmond's house after talking with Edmond"
                state < GRILL_OFF ->
                    "Talking to Mourner outside Edmond's house after digging the tunnel"
                else -> "Talking to Mourner outside Edmond's house after pulling off the grate"
            }
        runQuestSection(npc, dialogue, section)
    }

    /** The mourner guarding the plague house; with the warrant you slip past him. */
    private suspend fun ProtectedAccess.talkMourner(npc: Npc) {
        if (state in WARRANT until FREED && carries(PcObjs.warrant)) {
            runQuestSection(npc, dialogue, MOURNER_WARRANT)
            telejump(PLAGUE_HOUSE)
            return
        }
        runQuestSection(npc, dialogue, "Talking to Mourner or Head Mourner in West Ardougne")
    }

    private suspend fun ProtectedAccess.talkHeadMourner(npc: Npc) {
        val section =
            if (state >= REFUSED) {
                "Talking to Head Mourner after attempting to enter the plague house"
            } else {
                "Talking to Mourner or Head Mourner in West Ardougne"
            }
        runQuestSection(npc, dialogue, section)
    }

    /* The Clerk and Bravek */

    private suspend fun ProtectedAccess.talkClerk(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "This is urgent though! Someone's been kidnapped!" to {
                            if (state == REFUSED) setState(CLERK)
                        },
                    ),
            )
        val section =
            when {
                state < REFUSED -> "Talking to main Clerk in West Ardougne"
                state == REFUSED -> CLERK_BEFORE
                state == CLERK -> "Talking to the Clerk after getting permission to speak to Bravek"
                else -> "Talking to Clerk after meeting Bravek"
            }
        runQuestSection(npc, dialogue, section, hooks, speakers)
    }

    private suspend fun ProtectedAccess.bravekDoor(door: BoundLocInfo) {
        if (state < CLERK) {
            runQuestSection(null, dialogue, BRAVEK_DOOR_EARLY, speakers = speakers)
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkBravek(npc: Npc) {
        var gaveNote = false
        var gaveWarrant = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has no inventory space:" to { inv.isFull() },
                        "If the player lost the scruffy note:" to { !carries(PcObjs.note) },
                    ),
                boxes =
                    mapOf(
                        "Bravek hands you a tatty piece of paper." to {
                            gaveNote = true
                            false
                        },
                        "You give Bravek the hangover cure. Bravek gulps down the foul-looking " +
                            "liquid." to {
                                invDel(inv, PcObjs.hangover_cure)
                                setState(CURED)
                                false
                            },
                        "Bravek hands you a warrant." to {
                            gaveWarrant = true
                            false
                        },
                    ),
            )
        val section =
            when {
                state < CLERK -> null
                state == CLERK -> "Talking to Bravek"
                state == NOTE && carries(PcObjs.hangover_cure) -> BRAVEK_CURE
                state == NOTE -> "Talking to Bravek again before getting the hangover cure"
                state == CURED -> BRAVEK_NO_WARRANT
                state < FREED && !carries(PcObjs.warrant) -> BRAVEK_NO_WARRANT
                else -> "Talking to Bravek after receiving the warrant"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(angry, "Who let you in here?") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (gaveNote && !carries(PcObjs.note)) {
            invAddOrDrop(objRepo, PcObjs.note)
            if (state == CLERK) setState(NOTE)
        }
        if (gaveWarrant && !carries(PcObjs.warrant)) {
            invAddOrDrop(objRepo, PcObjs.warrant)
            if (state == CURED) setState(WARRANT)
        }
    }

    private suspend fun ProtectedAccess.mixChocolate() {
        invDel(inv, PcObjs.chocolate_dust)
        invDel(inv, PcObjs.bucket_milk)
        invAdd(inv, PcObjs.chocolaty_milk)
        runQuestSection(null, dialogue, "Using chocolate on a bucket of milk")
    }

    private suspend fun ProtectedAccess.mixSnapeGrass() {
        invDel(inv, PcObjs.snape_grass)
        invDel(inv, PcObjs.chocolaty_milk)
        invAdd(inv, PcObjs.hangover_cure)
        runQuestSection(null, dialogue, "Using snape grass on chocolatey milk")
    }

    private suspend fun ProtectedAccess.searchBarrel() {
        if (state < WARRANT || carries(PcObjs.key) || state >= FREED) {
            mes("The barrel is empty.")
            return
        }
        runQuestSection(null, dialogue, "Searching the barrel")
        invAddOrDrop(objRepo, PcObjs.key)
    }

    private suspend fun ProtectedAccess.elenaGate(gate: BoundLocInfo) {
        if (!carries(PcObjs.key) && state < FREED) {
            runQuestSection(null, dialogue, ELENA_GATE_LOCKED, speakers = speakers)
            return
        }
        if (state < FREED) runQuestSection(null, dialogue, ELENA_GATE_OPEN)
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.talkElena(npc: Npc) {
        if (state != WARRANT) {
            startDialogue(npc) { chatNpc(neutral, "Thank you for rescuing me!") }
            return
        }
        runQuestSection(npc, dialogue, "Talking to Elena after unlocking the basement door")
        if (carries(PcObjs.key)) invDel(inv, PcObjs.key)
        setVar(PcVarBits.elena_home, 1)
        setState(FREED)
    }

    private suspend fun ProtectedAccess.readScroll() {
        invDel(inv, PcObjs.scroll)
        runQuestSection(null, dialogue, "Using the teleport scroll")
    }

    companion object {
        const val STARTED = 1
        const val MASK = 2
        const val PLAN = 3
        const val SOFT = 7
        const val DUG = 8
        const val ROPE = 9
        const val GRILL_OFF = 10
        const val BOOK = 11
        const val BOOK_BACK = 12
        const val MILLI = 13
        const val REFUSED = 14
        const val CLERK = 15
        const val NOTE = 16
        const val CURED = 17
        const val WARRANT = 18
        const val FREED = 19
        const val COMPLETE = 29

        private const val CUPBOARD_TICKS = 200
        private const val MANHOLE_TICKS = 200
        private const val MINING_XP = 2425.0

        private val GARDEN = CoordGrid(2566, 3334, 0)
        private val MUD = CoordGrid(2566, 3332, 0)
        private val SEWER_LANDING = CoordGrid(2519, 9758, 0)
        private val SEWER_PIPE = CoordGrid(2514, 9736, 0)
        private val WEST_ARDOUGNE = CoordGrid(2529, 3304, 0)
        private val PLAGUE_HOUSE = CoordGrid(2536, 3270, 0)
        private val BASEMENT = CoordGrid(2537, 9671, 0)

        private const val EDMOND_PICTURE =
            "Talking to Edmond after Jethick asks for Elena's picture"
        private const val ALRENA_PICTURE =
            "Talking to Alrena after Jethick asks for Elena's picture"
        private const val DOOR_WARRANT = "Attempting to enter the plague house with the warrant"
        private const val DOOR_FIRST = "First attempt after talking to Milli Rehnison"
        private const val ROPE_EARLY = "Using rope on the grill before trying to open it first"
        private const val JETHICK_NO_PICTURE =
            "Talking to Jethick if the player does not have Elena's picture"
        private const val JETHICK_PICTURE = "Talking to Jethick after getting Elena's picture"
        private const val JETHICK_AGAIN = "Talking to Jethick again before delivering the book"
        private const val MILLI_AFTER_DOOR =
            "Talking to Milli Rehnison after attempting to enter the plague house"
        private const val PLAGUE_DOOR_LATER =
            "Attempting before talking to Milli Rehnison, and subsequent attempts"
        private const val MOURNER_WARRANT =
            "Talking to Mourners outside the plague house while holding the warrant"
        private const val CLERK_BEFORE =
            "Talking to the Clerk Before getting permission to speak to Bravek"
        private const val BRAVEK_DOOR_EARLY =
            "Attempting to open the door to Bravek before the Clerk lets you in"
        private const val BRAVEK_CURE = "Talking to Bravek after getting the hangover cure"
        private const val BRAVEK_NO_WARRANT = "Talking to Bravek before getting the warrant"
        private const val ELENA_GATE_LOCKED =
            "Attempting to open the basement door of the plague house without the key"
        private const val ELENA_GATE_OPEN =
            "Opening the basement door of the plague house while holding the key"

        val PLAGUE_CITY =
            QuestDef(
                name = "Plague City",
                varp = { PcVarps.elenaquest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("2,425 Mining XP", "Ardougne teleport scroll"),
                scrollObj = { PcObjs.gas_mask },
            )
    }
}

// Every cache ref Plague City uses (re-check after cache updates).

internal object PcNpcs : NpcReferences() {
    /* Children of the edmond_top / edmond_bottom, elenap, jethick, mourner1, headmourner and
     * mourner_elena_guard multinpcs. */
    val edmond = find("edmond")
    val alrena = find("alrena")
    val jethick = find("jethick_vis")
    val ted = find("ted_rehnison")
    val martha = find("martha_rehnison")
    val milli = find("milli")
    val mourner = find("mourner1_vis")
    val guard_mourner = find("mourner_elena_guard_vis")
    val head_mourner = find("headmourner_vis")
    val clerk = find("clerk")
    val bravek = find("bravek")
    val elena_prison = find("elenap_vis")
}

internal object PcLocs : LocReferences() {
    val cupboard_shut = find("alrenascupboardshut")
    val cupboard_open = find("alrenascupboardopen")

    /* Children of the plaguemudpatch2 multiloc (plaguecity_dug_mud_pile). */
    val mud = find("plague_mud")
    val hole = find("plague_hole")
    val mud_pile = find("plaguemudpile")
    val grill = find("plague_grill_vis")
    val pipe = find("plaguesewerpipe_open")
    val manhole_closed = find("plaguemanholeclosed")
    val manhole_open = find("plaguemanholeopen")
    val plague_doors =
        listOf(find("plagueelenadoorshut_vis"), find("plagueelenadoorshut_cross_vis"))
    val bravek_door = find("bravekdoorshut")
    val barrel = find("plaguekeybarrel")
    val stairs_down = find("plaguehousestairsdown")
    val stairs_up = find("plaguehousestairsup")
    val elena_gate = find("elenagateshut")
}

internal object PcObjs : ObjReferences() {
    val dwellberries = find("dwellberries")
    val gas_mask = find("gasmask")
    val picture = find("elena_picture")
    val book = find("turnip_book")
    val note = find("scruffy_note")
    val hangover_cure = find("hangover_cure")
    val warrant = find("warrant")
    val key = find("elenakey")
    val scroll = find("ardougnescroll")
    val chocolate_dust = find("chocolate_dust")
    val bucket_milk = find("bucket_milk")
    val chocolaty_milk = find("chocolaty_milk")
    val snape_grass = find("snape_grass")
    val bucket_water = find("bucket_water")
    val bucket = find("bucket_empty")
    val rope = find("rope")
}

internal object PcVarps : VarpReferences() {
    val elenaquest = find("elenaquest")
}

internal object PcVarBits : VarBitReferences() {
    val edmond_below = find("plaguecity_can_see_edmond_up_top")
    val elena_home = find("plaguecity_elena_at_home")
    val dug_mud_pile = find("plaguecity_dug_mud_pile")
    val checked_grill = find("plaguecity_checked_grill")
    val pipe = find("plaguecity_pipe")
    val picture_asked = find("plaguecity_picture_asked")
}
