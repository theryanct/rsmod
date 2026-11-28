package org.rsmod.content.quests.fenkenstrain

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld1
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
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.content.quests.restlessghost.RestlessGhost.Companion.RESTLESS_GHOST
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Creature of Fenkenstrain (OSRS Wiki). The quest list reads the cache varbit
 * `creatureoffenkenstrain` (0-9); the castle's multinpcs (Fenkenstrain, his creature) and the
 * table corpse read varp `fenk_quest`, which is kept equal to it. Progress flags are the cache's
 * `fenk_*` varbits on varp `fenk_flags`.
 * - The Canifis signpost (or Dr Fenkenstrain) starts it; the interview (Braindead, Grave-digging).
 * - The upstairs bookcases: The Joy of Gravedigging (marble amulet), Handy Maggot Avoidance
 *   Techniques (obsidian amulet); together the star amulet.
 * - The grave east of the Haunted Woods fairy ring: a decapitated head; a pickled brain (the Hair
 *   of the Dog) in it.
 * - The star amulet in the easternmost memorial south-east of the castle; push it into the
 *   experiment cave. The chest by the mausoleum entrance has the cavern key; the three Lords'
 *   graves on the island give the torso, arms and legs.
 * - The parts, a needle and five thread to Fenkenstrain. The Gardener Ghost (ghostspeak amulet)
 *   has the shed key; the shed cupboard's brush, three canes and bronze wire: the extended brush;
 *   the upstairs west chimney drops the conductor mould; a silver bar at a furnace casts the
 *   conductor; repair the roof's lightning conductor.
 * - Fenkenstrain's tower key; his monster (Lord Rologarth); pickpocket the Ring of Charos.
 *   2 QP, 1,000 Thieving xp, the Ring of Charos.
 *
 * Simplified (Authored, not Wiki): the Gardener Ghost doesn't follow you to his grave; the experiment
 * cave's entry tile and the mausoleum island's way in and out are best guesses (Ryan-test); the
 * pickled brain is the pub's world spawn (no 50gp purchase).
 */
class CreatureOfFenkenstrain
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(CreatureOfFenkenstrain::class.java, "creature_of_fenkenstrain")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Dr Fenkenstrain" to npcTypes[FenkNpcs.fenkenstrain],
            "Gardener Ghost" to npcTypes[FenkNpcs.gardener],
            "Fenkenstrain's Monster" to npcTypes[FenkNpcs.creature],
            "Rologarth" to npcTypes[FenkNpcs.rologarth],
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, FenkNpcs.fenkenstrain, CREATURE_OF_FENKENSTRAIN, wants = { true }) {
                talkFenkenstrain(it)
            }
            register(npcTypes, FenkNpcs.gardener, CREATURE_OF_FENKENSTRAIN, wants = { true }) {
                talkGardener(it)
            }
            register(npcTypes, FenkNpcs.creature, CREATURE_OF_FENKENSTRAIN, wants = { true }) {
                talkCreature(it)
            }
            register(npcTypes, FenkNpcs.rologarth, CREATURE_OF_FENKENSTRAIN, wants = { true }) {
                section(it, "Lord Rologarth")
            }
        }
        onNpcOpLabel(npcTypes, FenkNpcs.fenkenstrain, "Pickpocket") { pickpocket(it) }
        for (part in FenkObjs.parts) onOpNpcU(FenkNpcs.fenkenstrain, part) { talkFenkenstrain(it.npc) }

        onLocOpLabel(locTypes, FenkLocs.signpost, "Read") { readSignpost() }
        onLocOpLabel(locTypes, FenkLocs.bookcase, "Search") { searchBookcase(it) }
        onOpHeldU(FenkObjs.marble, FenkObjs.obsidian) { combineAmulets() }
        onLocOpLabel(locTypes, FenkLocs.clock, "Wind") { windClock() }
        onOpHeld1(FenkObjs.letter) { readLetter() }
        onOpHeldU(FenkObjs.head_empty, FenkObjs.brain) { fillHead() }

        onLocOpLabel(locTypes, FenkLocs.woods_grave, "Dig") { digWoodsGrave(it) }
        onLocOpLabel(locTypes, FenkLocs.woods_grave, "Read") { readWoodsGrave(it) }
        onLocOpLabel(locTypes, FenkLocs.grave, "Dig") { digGrave(it) }
        onLocOpLabel(locTypes, FenkLocs.grave, "Read") { readGrave(it) }
        onLocOpLabel(locTypes, FenkLocs.memorial, "Search") { searchMemorial(it) }
        onLocOpLabel(locTypes, FenkLocs.memorial, "Push") { pushMemorial(it) }
        onOpLocU(FenkLocs.memorial, FenkObjs.star_amulet) { insertStar(it.loc) }
        onLocOpLabel(locTypes, FenkLocs.chest_closed, "Open") { searchChest() }
        onLocOpLabel(locTypes, FenkLocs.chest_open, "Search") { searchChest() }
        onLocOpLabel(locTypes, FenkLocs.mausoleum_door, "Open") { openMausoleum() }

        onLocOpLabel(locTypes, FenkLocs.shed_door, "Open") { openShed(it) }
        onLocOpLabel(locTypes, FenkLocs.cupboard, "Open") { searchCupboard() }
        onLocOpLabel(locTypes, FenkLocs.cupboard_open, "Search") { searchCupboard() }
        onLocOpLabel(locTypes, FenkLocs.canes, "Take-from") { takeCane() }
        for (brush in FenkObjs.brushes.dropLast(1)) onOpHeldU(FenkObjs.cane, brush) { extendBrush(brush) }
        for (fireplace in FenkLocs.fireplaces) onOpLocU(fireplace, FenkObjs.brushes.last()) { sweep(it.loc) }
        for (type in locTypes.values) {
            if (!type.name.contains("furnace", ignoreCase = true)) continue
            val exact = type.name.equals("Furnace", ignoreCase = true)
            val smelts = type.op.any { it.equals("Smelt", true) || (exact && it.equals("Use", true)) }
            if (!smelts) continue
            // Silver bar on a furnace is Crafting's (silver jewellery); the quest uses the mould.
            onOpLocU(type, FenkObjs.mould) { castConductor() }
        }
        onLocOpLabel(locTypes, FenkLocs.conductor_broken, "Repair") { repairConductor() }
        onLocOpLabel(locTypes, FenkLocs.tower_door, "Open") { openTowerDoor(it) }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(CREATURE_OF_FENKENSTRAIN)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage >= to) return
        player.setQuestState(CREATURE_OF_FENKENSTRAIN, to)
        // The castle's multinpcs and the table read varp fenk_quest: keep it in step.
        VarPlayerIntMapSetter.set(player, FenkVarps.quest, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.give(obj: ObjType) = invAddOrDrop(objRepo, obj)

    private suspend fun ProtectedAccess.section(
        npc: Npc?,
        title: String,
        hooks: SimpleQuestHooks = SimpleQuestHooks(),
    ) {
        runQuestSection(npc, dialogue, title, hooks, speakers)
    }

    /** A section of `If the player ...:` events: only [event] is true. */
    private suspend fun ProtectedAccess.event(title: String, event: String, extra: Map<String, () -> Boolean> = emptyMap()) {
        section(null, title, SimpleQuestHooks(conditions = mapOf(event to { true }) + extra))
    }

    private val ProtectedAccess.ghostspeak: Boolean
        get() = invTotal(worn, FenkObjs.ghostspeak) > 0 || invTotal(worn, FenkObjs.ghostspeak_enchanted) > 0

    /* Starting out */

    private suspend fun ProtectedAccess.readSignpost() {
        var start = false
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player reads the signpost:" to { true }),
                options = mapOf("Yes." to { start = true }),
            )
        section(null, "Reading the signpost at the centre of Canifis", hooks)
        setFlag(FenkVarBits.read_signpost, 1)
        if (start && requirementsMet()) advance(STARTED)
    }

    private fun ProtectedAccess.requirementsMet(): Boolean {
        val ok =
            player.isQuestComplete(PRIEST_IN_PERIL) &&
                player.isQuestComplete(RESTLESS_GHOST) &&
                stat(stats.thieving) >= THIEVING_REQUIRED &&
                stat(stats.crafting) >= CRAFTING_REQUIRED
        if (!ok) {
            // Authored line (not Wiki): the quest's requirements.
            mes(
                "You need Priest in Peril and The Restless Ghost, $THIEVING_REQUIRED Thieving and " +
                    "$CRAFTING_REQUIRED Crafting to start this quest."
            )
        }
        return ok
    }

    private suspend fun ProtectedAccess.talkFenkenstrain(npc: Npc) {
        when {
            stage <= STARTED -> {
                if (!requirementsMet()) return
                var hired = false
                val hooks = SimpleQuestHooks(options = mapOf("Grave-digging" to { hired = true }))
                section(npc, "Talking to Dr Fenkenstrain", hooks)
                if (hired) advance(HIRED)
            }
            stage == HIRED && FenkObjs.parts.any { carries(it) } -> deliverParts(npc)
            stage == HIRED -> section(npc, "Talking to Dr Fenkenstrain again")
            stage == PARTS -> giveNeedleAndThread(npc)
            stage == SEWN -> section(npc, "Talking to Dr Fenkenstrain again after sewing the body parts together")
            stage == LIGHTNING -> {
                section(npc, "Talking to Dr Fenkenstrain after using the conductor")
                give(FenkObjs.tower_key)
                setFlag(FenkVarBits.unlocked_tower, 1)
                advance(TOWER_KEY)
            }
            stage == TOWER_KEY -> {
                val key = carries(FenkObjs.tower_key)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the Tower key:" to { key },
                                "If the player has lost the Tower key:" to { !key },
                            )
                    )
                section(npc, "Talking to Dr Fenkenstrain again (3)", hooks)
                if (!key) give(FenkObjs.tower_key)
            }
            stage == MET_CREATURE -> {
                section(npc, "Convincing Dr Fenkenstrain to stop his experiments")
                advance(IN_TOWER)
            }
            stage == IN_TOWER -> section(npc, "Convincing Dr Fenkenstrain to stop his experiments")
            else -> section(npc, "Dr Fenkenstrain (2)")
        }
    }

    private suspend fun ProtectedAccess.deliverParts(npc: Npc) {
        val head = carries(FenkObjs.head_full)
        val torso = carries(FenkObjs.torso)
        val arms = carries(FenkObjs.arms)
        val legs = carries(FenkObjs.legs)
        fun has(v: VarBitType, now: Boolean) = flag(v) == 1 || now
        val all =
            has(FenkVarBits.head, head) && has(FenkVarBits.torso, torso) &&
                has(FenkVarBits.arms, arms) && has(FenkVarBits.legs, legs)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the decapitated head with the brain:" to { head },
                        "If the player has the torso:" to { torso },
                        "If the player has the arms:" to { arms },
                        "If the player has the legs:" to { legs },
                        "If the player brought all parts to Dr Fenkenstrain:" to { all },
                    )
            )
        section(npc, "Talking to Dr Fenkenstrain with the body parts", hooks)
        for ((obj, varbit) in listOf(
            FenkObjs.head_full to FenkVarBits.head,
            FenkObjs.torso to FenkVarBits.torso,
            FenkObjs.arms to FenkVarBits.arms,
            FenkObjs.legs to FenkVarBits.legs,
        )) {
            if (carries(obj)) {
                invDel(inv, obj)
                setFlag(varbit, 1)
            }
        }
        if (all) advance(PARTS)
    }

    private suspend fun ProtectedAccess.giveNeedleAndThread(npc: Npc) {
        val costume = carries(FenkObjs.costume_needle)
        val needleNow = carries(FenkObjs.needle) || costume
        val threadNow = invTotal(inv, FenkObjs.thread) >= THREAD || costume
        val needleGiven = flag(FenkVarBits.needle) == 1
        val threadGiven = flag(FenkVarBits.threads) >= THREAD
        val needle = needleGiven || needleNow
        val thread = threadGiven || threadNow
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have either the needle or five thread:" to { !needle && !thread },
                        "If the player has a costume needle:" to { false },
                        "If the player only has a needle in their inventory:" to { needleNow && !needleGiven && !thread },
                        "If the player only has 1-5 thread in their inventory:" to { threadNow && !threadGiven && !needle },
                        "If the player only brought the needle and speaks to him again:" to { needle && !thread },
                        "If the player only brought the thread and speaks to him again:" to { thread && !needle },
                        "If the player brought the needle and five thread to Dr Fenkenstrain:" to { needle && thread },
                    )
            )
        section(npc, "Talking to Dr Fenkenstrain again (2)", hooks)
        if (needleNow && !needleGiven && !costume) invDel(inv, FenkObjs.needle)
        if (threadNow && !threadGiven && !costume) invDel(inv, FenkObjs.thread, THREAD)
        if (needle) setFlag(FenkVarBits.needle, 1)
        if (thread) setFlag(FenkVarBits.threads, THREAD)
        if (needle && thread) advance(SEWN)
    }

    private suspend fun ProtectedAccess.pickpocket(npc: Npc) {
        when {
            stage == 0 -> section(npc, "Attempting to pickpocket Dr Fenkenstrain before starting the quest")
            stage == IN_TOWER -> {
                event("Stealing the Ring of Charos", "If the player pickpockets Dr Fenkenstrain:")
                give(FenkObjs.ring)
                statAdvance(stats.thieving, THIEVING_XP * xpMods.get(player, stats.thieving))
                completeQuest(CREATURE_OF_FENKENSTRAIN)
            }
            // Authored line (not Wiki).
            else -> mes("You don't find anything worth taking.")
        }
    }

    /* The castle's books, clock and amulets */

    private suspend fun ProtectedAccess.searchBookcase(case: BoundLocInfo) {
        val title = "Inside Dr Fenkenstrain's Tower"
        if (stage < HIRED || case.coords.level == 0) {
            event(title, "If the player searches either bookcase before getting the job:")
            return
        }
        val west = case.coords.x < CASTLE_MID_X
        var found = false
        val haveMarble = carries(FenkObjs.marble) || carries(FenkObjs.star_amulet)
        val haveObsidian = carries(FenkObjs.obsidian) || carries(FenkObjs.star_amulet)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        (if (west) "If the player searches the bookcase to the north-west:"
                        else "If the player searches the bookcase to the north-east:") to { true },
                        "If the player does not have the marble amulet:" to { !haveMarble },
                        "If marble or star amulet is in the player's inventory:" to { haveMarble },
                        "Without the obsidian amulet:" to { !haveObsidian },
                        "If obsidian or star amulet is in the backpack:" to { haveObsidian },
                        "One of the following:" to { true },
                    ),
                options =
                    mapOf(
                        "The Joy of Gravedigging" to { found = !haveMarble },
                        "Handy Maggot Avoidance Techniques" to { found = !haveObsidian },
                    ),
            )
        section(null, title, hooks)
        if (found) give(if (west) FenkObjs.marble else FenkObjs.obsidian)
    }

    private suspend fun ProtectedAccess.combineAmulets() {
        event("Inside Dr Fenkenstrain's Tower", "If the player combines the marble and obsidian amulets:")
        invDel(inv, FenkObjs.marble)
        invReplace(inv, FenkObjs.obsidian, 1, FenkObjs.star_amulet)
    }

    private suspend fun ProtectedAccess.windClock() {
        val letter = carries(FenkObjs.letter)
        event(
            "Inside Dr Fenkenstrain's Tower",
            if (letter) "If the player winds the clock again with the letter in the inventory:"
            else "If the player winds the clock at the south-west:",
        )
        setFlag(FenkVarBits.wound_clock, 1)
        if (!letter) give(FenkObjs.letter)
    }

    private suspend fun ProtectedAccess.readLetter() {
        event("Inside Dr Fenkenstrain's Tower", "If the player reads the letter:")
    }

    private suspend fun ProtectedAccess.fillHead() {
        event("Finding the brains", "If the player uses the decapitated head on the brain (or vice versa):")
        invDel(inv, FenkObjs.brain)
        invReplace(inv, FenkObjs.head_empty, 1, FenkObjs.head_full)
    }

    /* Graves and memorials */

    private fun woodsGrave(loc: BoundLocInfo): String? = WOODS_GRAVES[loc.coords.x to loc.coords.z]

    private suspend fun ProtectedAccess.readWoodsGrave(loc: BoundLocInfo) {
        val title = woodsGrave(loc)
        if (title == null) {
            mes("The grave's inscription has worn away.") // Authored (not Wiki).
            return
        }
        event(title, "If the player searches the grave:")
    }

    private suspend fun ProtectedAccess.digWoodsGrave(loc: BoundLocInfo) {
        val title = woodsGrave(loc)
        val head = title == HEAD_GRAVE
        if (title == null || head && (stage < HIRED || owns(FenkObjs.head_empty, FenkObjs.head_full) || flag(FenkVarBits.head) == 1)) {
            // Authored line (not Wiki): an empty grave.
            mes("You start digging... but the grave is empty.")
            return
        }
        event(title, "If the player digs the grave:", mapOf("If the Gardener was following the player:" to { false }))
        if (head) give(FenkObjs.head_empty)
    }

    private fun ProtectedAccess.owns(vararg objs: ObjType): Boolean = objs.any { carries(it) }

    /** The east graveyard's graves (south-east of the castle) and the mausoleum island's three. */
    private fun lordPart(loc: BoundLocInfo): Pair<String, ObjType>? =
        when (loc.coords.x to loc.coords.z) {
            3502 to 3576 -> "north-west" to FenkObjs.torso
            3504 to 3577 -> "north" to FenkObjs.arms
            3506 to 3576 -> "north-east" to FenkObjs.legs
            else -> null
        }

    private suspend fun ProtectedAccess.readGrave(loc: BoundLocInfo) {
        val part = lordPart(loc)
        if (part != null) {
            event("Mausoleum", "If the player reads the grave ${part.first} of the entrance:")
            return
        }
        val title = if (loc.coords.x < EAST_GRAVEYARD_MID_X) "Westernmost grave" else "Middle - East Grave"
        event(title, "If the player reads the grave:")
    }

    private suspend fun ProtectedAccess.digGrave(loc: BoundLocInfo) {
        val part = lordPart(loc)
        if (part == null) {
            val title = if (loc.coords.x < EAST_GRAVEYARD_MID_X) "Westernmost grave" else "Middle - East Grave"
            event(title, "If the player digs the grave:")
            return
        }
        val (where, obj) = part
        val partVarbit =
            when (obj) {
                FenkObjs.torso -> FenkVarBits.torso
                FenkObjs.arms -> FenkVarBits.arms
                else -> FenkVarBits.legs
            }
        if (stage < HIRED || carries(obj) || flag(partVarbit) == 1) {
            mes("You start digging... but find nothing more.") // Authored (not Wiki).
            return
        }
        event("Mausoleum", "If the player digs the grave $where of the entrance:")
        give(obj)
    }

    private suspend fun ProtectedAccess.searchMemorial(loc: BoundLocInfo) {
        when {
            loc.coords.x == EAST_MEMORIAL_X ->
                event("Easternmost memorial", "If the player searches the memorial:")
            loc.coords.z > ISLAND_Z -> event("Mausoleum", "If the player searches the memorial right next to the entrance:")
            else -> event("Middle - West Memorial", "If the player searches the memorial:")
        }
    }

    private suspend fun ProtectedAccess.pushMemorial(loc: BoundLocInfo) {
        when {
            loc.coords.x == EAST_MEMORIAL_X && flag(FenkVarBits.unlocked_cavern) == 1 -> {
                event("Easternmost memorial", "If the player pushes the memorial after inserting the star amulet:")
                telejump(CAVE_ARRIVE)
            }
            loc.coords.x == EAST_MEMORIAL_X ->
                event("Easternmost memorial", "If the player pushes the memorial without inserting the star amulet:")
            loc.coords.z > ISLAND_Z -> {
                // Authored (not Wiki): the island's memorial leads back down to the cave.
                mes("The memorial slides aside and you climb back down into the caves.")
                telejump(MAUSOLEUM_DOOR_INSIDE)
            }
            else -> event("Middle - West Memorial", "If the player pushes the memorial:")
        }
    }

    private suspend fun ProtectedAccess.insertStar(loc: BoundLocInfo) {
        if (loc.coords.x != EAST_MEMORIAL_X) {
            mes("Nothing interesting happens.")
            return
        }
        event("Easternmost memorial", "If the player uses the star amulet on the memorial:")
        invDel(inv, FenkObjs.star_amulet)
        setFlag(FenkVarBits.unlocked_cavern, 1)
    }

    private suspend fun ProtectedAccess.searchChest() {
        val found = carries(FenkObjs.cavern_key)
        event(
            "Underground",
            if (found) "If the player searches the chest near the Mausoleum entrance after finding the key:"
            else "If the player searches the chest near the Mausoleum entrance:",
        )
        if (!found) give(FenkObjs.cavern_key)
    }

    private fun ProtectedAccess.openMausoleum() {
        if (!carries(FenkObjs.cavern_key)) {
            mes("The entrance is locked.") // Authored (not Wiki).
            return
        }
        mes("You unlock the entrance and climb up to the mausoleum.") // Authored (not Wiki).
        telejump(ISLAND_ARRIVE)
    }

    /* The gardener, the shed and the brush */

    private suspend fun ProtectedAccess.talkGardener(npc: Npc) {
        val speak = ghostspeak
        when {
            stage < HIRED -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player is not wearing the amulet of ghostspeak:" to { !speak })
                    )
                section(npc, "Talking to the Gardener Ghost before getting the job", hooks)
            }
            stage >= LIGHTNING -> section(npc, "Talking to the Gardener Ghost after using the conductor")
            stage >= SEWN && speak -> {
                var key = false
                val hooks =
                    SimpleQuestHooks(options = mapOf("Do you know where the key to the shed is?" to { key = true }))
                section(npc, "Getting help from the Gardener Ghost", hooks)
                setFlag(FenkVarBits.spoken_to_gardener, 1)
                if (key && !carries(FenkObjs.shed_key)) give(FenkObjs.shed_key)
            }
            else -> {
                val found = flag(FenkVarBits.head) == 1 || owns(FenkObjs.head_empty, FenkObjs.head_full)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is not wearing the amulet of ghostspeak:" to { !speak },
                                "If the player is wearing the amulet of ghostspeak:" to { speak },
                                "If the player already found the head:" to { found },
                                "If the player does not have the head:" to { !found },
                            )
                    )
                section(npc, "Talking to the Gardener Ghost", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.openShed(door: BoundLocInfo) {
        if (!carries(FenkObjs.shed_key) && flag(FenkVarBits.unlocked_shed) == 0) {
            event("The Gardener's shed", "If the player attempts to open the shed door without the key:")
            return
        }
        setFlag(FenkVarBits.unlocked_shed, 1)
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        val has = FenkObjs.brushes.any { carries(it) }
        event(
            "The Gardener's shed",
            if (has) "If the player searches the cupboard whilst having the garden brush:"
            else "If the player searches the cupboard:",
        )
        if (!has) give(FenkObjs.brushes.first())
    }

    private suspend fun ProtectedAccess.takeCane() {
        event("Making an extended brush", "If the player takes a cane from the pile of canes:")
        give(FenkObjs.cane)
    }

    private suspend fun ProtectedAccess.extendBrush(brush: ObjType) {
        if (!carries(FenkObjs.bronze_wire)) {
            event("Making an extended brush", "If the player uses the garden cane on the garden brush (or vice versa):")
            return
        }
        event(
            "With the bronze wire in the inventory",
            "If the player uses the cane with a brush (or extended brush) whilst having a bronze wire in their inventory:",
        )
        invDel(inv, FenkObjs.cane)
        invDel(inv, FenkObjs.bronze_wire)
        val next = FenkObjs.brushes[FenkObjs.brushes.indexOf(brush) + 1]
        invReplace(inv, brush, 1, next)
    }

    private suspend fun ProtectedAccess.sweep(fireplace: BoundLocInfo) {
        val upstairs = fireplace.coords.level == 1
        val west = fireplace.coords.x < CASTLE_MID_X
        val title = "Searching the fireplaces"
        when {
            upstairs && west && !carries(FenkObjs.mould) && stage >= SEWN -> {
                event(title, "If the player uses the extended brush on the west chimney upstairs:")
                give(FenkObjs.mould)
            }
            upstairs && west ->
                event(title, "If the player uses the extended brush on the west chimney upstairs with the mould in their inventory:")
            upstairs -> event(title, "If the player uses the extended brush on the east chimney upstairs:")
            west -> event(title, "If the player uses the extended brush on the main floor - west chimney:")
            else -> event(title, "If the player uses the extended brush on the main floor - east chimney:")
        }
    }

    /* The conductor and the roof */

    private fun ProtectedAccess.castConductor() {
        if (!carries(FenkObjs.mould) || !carries(FenkObjs.silver_bar)) {
            mes("You need a silver bar to cast a conductor in this mould.") // Authored (not Wiki).
            return
        }
        invReplace(inv, FenkObjs.silver_bar, 1, FenkObjs.conductor)
        // Authored line (not Wiki): the cast (the transcript notes the smithing interface).
        mes("You pour the molten silver into the mould and make a conductor.")
    }

    private suspend fun ProtectedAccess.repairConductor() {
        if (stage < SEWN || !carries(FenkObjs.conductor)) {
            mes("You need a silver conductor to repair the lightning conductor.") // Authored.
            return
        }
        event("At the roof", "If the player attempts to place the conductor without fixing the lightning conductor stand:")
        invDel(inv, FenkObjs.conductor)
        setFlag(FenkVarBits.conductor_repaired, 1)
        advance(LIGHTNING)
    }

    private fun ProtectedAccess.openTowerDoor(door: BoundLocInfo) {
        if (stage < TOWER_KEY || !carries(FenkObjs.tower_key) && stage < COMPLETE) {
            mes("The door is locked.") // Authored (not Wiki).
            return
        }
        // Authored (not Wiki): through the tower door.
        telejump(if (player.coords.z >= door.coords.z) door.coords.translateZ(-1) else door.coords)
    }

    private suspend fun ProtectedAccess.talkCreature(npc: Npc) {
        when {
            stage == TOWER_KEY -> {
                section(npc, "Talking to the Creature of Fenkenstrain")
                advance(MET_CREATURE)
            }
            else -> section(npc, "Talking to the Creature of Fenkenstrain again")
        }
    }

    companion object {
        const val STARTED = 1
        const val HIRED = 2
        const val PARTS = 3
        const val SEWN = 4
        const val LIGHTNING = 5
        const val TOWER_KEY = 6
        const val MET_CREATURE = 7
        const val IN_TOWER = 8
        const val COMPLETE = 9

        private const val THIEVING_REQUIRED = 25
        private const val CRAFTING_REQUIRED = 20
        private const val THIEVING_XP = 1000.0
        private const val THREAD = 5

        /** The castle's east/west split (stairs at x 3537 and 3559). */
        private const val CASTLE_MID_X = 3548
        /** The east graveyard: graves at 3572 and 3576; the star memorial at 3578. */
        private const val EAST_GRAVEYARD_MID_X = 3574
        private const val EAST_MEMORIAL_X = 3578
        /** North of this the memorial is the mausoleum island's (3505,3571). */
        private const val ISLAND_Z = 3560

        private const val HEAD_GRAVE = "Grave east of the Fairy Ring"
        /** The Haunted Woods graves around fairy ring ALQ (OSRS Wiki map positions). */
        private val WOODS_GRAVES =
            mapOf(
                (3593 to 3509) to "Grave north of the Fairy Ring",
                (3585 to 3497) to "Grave west of the Fairy Ring",
                (3594 to 3491) to "Grave south of the Fairy Ring",
                (3608 to 3491) to HEAD_GRAVE,
            )

        /** Authored arrival tiles (Ryan-test). */
        private val CAVE_ARRIVE = CoordGrid(3546, 9935, 0)
        private val ISLAND_ARRIVE = CoordGrid(3504, 3573, 0)
        private val MAUSOLEUM_DOOR_INSIDE = CoordGrid(3511, 9957, 0)

        val CREATURE_OF_FENKENSTRAIN =
            QuestDef(
                name = "Creature of Fenkenstrain",
                varbit = { FenkVarBits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("1,000 Thieving XP", "Ring of Charos"),
                scrollObj = { FenkObjs.ring },
                listRow = "quest_creatureoffenkenstrain",
            )
    }
}

// Every cache ref Creature of Fenkenstrain uses (re-check after cache updates).

internal object FenkNpcs : NpcReferences() {
    /** Child of fenk_fenkenstrain (0-7) and fenk_fenkenstrain_in_tower (8). */
    val fenkenstrain = find("fenk_fenkenstrain_model")
    /** Child of fenk_gardener_multi / _2. */
    val gardener = find("fenk_gardener")
    /** Child of fenk_creature (0-7): Fenkenstrain's Monster. */
    val creature = find("fenk_creature_model")
    /** Child of fenk_creature_released (8-9): Lord Rologarth. */
    val rologarth = find("fenk_creature_model_2")
}

internal object FenkLocs : LocReferences() {
    val signpost = find("fenk_signpost")
    val bookcase = find("fenk_bookcase")
    val clock = find("fenk_clock")
    val woods_grave = find("fenk_grave_poor")
    val grave = find("fenk_grave")
    val memorial = find("fenk_coffin")
    val chest_closed = find("fenk_chest_closed")
    val chest_open = find("fenk_chest_open")
    val mausoleum_door = find("fenk_mausoleum_door")
    val shed_door = find("fenk_shed_door")
    val cupboard = find("fenk_broomcupboard")
    val cupboard_open = find("fenk_broomcupboard_open")
    val canes = find("fenk_canepile")
    val fireplaces = listOf(find("fenk_fireplace"), find("fenk_fireplace_left"), find("fenk_fireplace_right"))
    val conductor_broken = find("fenk_conductor_broken")
    val tower_door = find("fenk_tower_door")
}

internal object FenkObjs : ObjReferences() {
    val star_amulet = find("fenk_star_amulet")
    val cavern_key = find("fenk_mausoleum_key")
    val tower_key = find("fenk_tower_key")
    val shed_key = find("fenk_shed_key")
    val marble = find("fenk_marble_amulet")
    val obsidian = find("fenk_obsidian_amulet")
    val cane = find("fenk_cane")
    /** The garden brush with 0-3 canes (fenk_brush3 is the extended brush). */
    val brushes = listOf(find("fenk_brush0"), find("fenk_brush1"), find("fenk_brush2"), find("fenk_brush3"))
    val torso = find("fenk_torso")
    val arms = find("fenk_arms")
    val legs = find("fenk_legs")
    val head_empty = find("fenk_head_empty")
    val head_full = find("fenk_head_full")
    val brain = find("fenk_brain")
    val mould = find("fenk_lightning_mould")
    val conductor = find("fenk_conductor")
    val ring = find("ring_of_charos")
    val letter = find("fenk_letter")
    val needle = find("needle")
    val costume_needle = find("costumeneedle")
    val thread = find("thread")
    val silver_bar = find("silver_bar")
    val bronze_wire = find("bronzecraftwire")
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_enchanted = find("amulet_of_ghostspeak_enchanted")
    val parts = listOf(head_full, torso, arms, legs)
}

internal object FenkVarBits : VarBitReferences() {
    val quest = find("creatureoffenkenstrain")
    val arms = find("fenk_arms")
    val legs = find("fenk_legs")
    val torso = find("fenk_torso")
    val head = find("fenk_head")
    val needle = find("fenk_needle")
    val threads = find("fenk_threads_given")
    val spoken_to_gardener = find("fenk_spoken_to_gardener")
    val conductor_repaired = find("fenk_conductor_repaired")
    val wound_clock = find("fenk_wound_clock")
    val unlocked_tower = find("fenk_unlocked_tower")
    val unlocked_cavern = find("fenk_unlocked_cavern")
    val unlocked_shed = find("fenk_unlocked_shed")
    val read_signpost = find("fenk_read_signpost")
}

internal object FenkVarps : VarpReferences() {
    val quest = find("fenk_quest")
}
