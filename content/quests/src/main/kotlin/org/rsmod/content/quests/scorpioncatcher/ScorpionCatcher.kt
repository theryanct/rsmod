package org.rsmod.content.quests.scorpioncatcher

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.barcrawl.Barcrawl.Companion.BARCRAWL
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
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Scorpion Catcher (OSRS Wiki). Cache quest varp `scorpcatcher`, end state 6 (needs Alfred
 * Grimhand's Barcrawl to start; 31 Prayer for the monastery). No cache multis key on the varp, so
 * the stages are ours; which scorpions are caught lives in the scorpion cage's variant.
 * - 1 Thormac (Sorcerer's Tower) has lost three lesser Kharid scorpions; he gives a scorpion cage.
 *   2 a Seer (Seers' Village) sees one near the coffins and spiders of Taverley Dungeon, in a room
 *   behind an old wall (only searchable from now). With that scorpion caged, 3 the Seer places the
 *   others: the Barbarian Outpost (Peksa's brother Ivor's bed) and the Edgeville Monastery's upper
 *   floor (members only: Abbot Langley lets players with 31 Prayer join the order).
 * - 6 the full cage to Thormac: 1 QP, 6,625 Strength XP.
 *
 * Side content built here (nothing handled it): Velrak's cell in Taverley Dungeon (the jailer drops
 * the jail key; Velrak gives the dusty key), the dusty-key gate, Abbot Langley and the monastery's
 * ladder, and the Barbarian Outpost building's door (Barcrawl done).
 *
 * Simplified: a caught scorpion is hidden for a while (OSRS hides it per player); the Abbot's
 * spirit-shield and clue services aren't built.
 */
class ScorpionCatcher
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ScorpionCatcher::class.java, "scorpion_catcher") }
    private val velrakDialogue by lazy { QuestDialogue.load(ScorpionCatcher::class.java, "velrak") }
    private val abbotDialogue by lazy { QuestDialogue.load(ScorpionCatcher::class.java, "abbot_langley") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, ScNpcs.thormac, SCORPION_CATCHER, wants = { state < COMPLETE }) { talkThormac(it) }
            register(npcTypes, ScNpcs.seer, SCORPION_CATCHER, wants = { state in STARTED until COMPLETE }) {
                talkSeer(it)
            }
            default(npcTypes, ScNpcs.velrak) { talkVelrak(it) }
            default(npcTypes, ScNpcs.abbot) { talkAbbot(it) }
        }
        for ((scorpion, letter) in ScNpcs.scorpions) {
            onNpcOpLabel(npcTypes, scorpion, "Pick-up") { stung() }
            for (cage in ScObjs.cages.keys) onOpNpcU(scorpion, cage) { catchScorpion(it.npc, cage, letter) }
        }
        onLocOpLabel(locTypes, ScLocs.secret_wall, "Search") { searchWall(it) }

        onEvent<NpcKilledEvent> { npcKilled(this) }
        onLocOpLabel(locTypes, ScLocs.velrak_cell, "Open") { lockedDoor(it, ScObjs.jail_key, "This door is locked.") }
        onOpLocU(ScLocs.velrak_cell, ScObjs.jail_key) { lockedDoor(it.loc, ScObjs.jail_key, "") }
        onLocOpLabel(locTypes, ScLocs.dusty_gate, "Open") { lockedDoor(it, ScObjs.dusty_key, "This gate is locked.") }
        onOpLocU(ScLocs.dusty_gate, ScObjs.dusty_key) { lockedDoor(it.loc, ScObjs.dusty_key, "") }

        onLocOpLabel(locTypes, ScLocs.monastery_ladder, "Climb-up") { monasteryLadder() }
        for (door in ScLocs.outpost_doors) onLocOpLabel(locTypes, door, "Open") { outpostDoor(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SCORPION_CATCHER)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(SCORPION_CATCHER, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[ScVarps.flags]

    private fun ProtectedAccess.cage(): ObjType? = ScObjs.cages.keys.firstOrNull { carries(it) }

    /* Thormac and the Seer */

    private suspend fun ProtectedAccess.talkThormac(npc: Npc) {
        when {
            state == 0 && !player.isQuestComplete(BARCRAWL) ->
                runQuestSection(npc, dialogue, "Without the requirements")
            state == 0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "With the requirements", hooks)
                if (start) {
                    setState(STARTED)
                    invAddOrDrop(objRepo, ScObjs.cage_empty)
                }
            }
            carries(ScObjs.cage_full) -> {
                runQuestSection(npc, dialogue, "Finishing up")
                invDel(inv, ScObjs.cage_full)
                statAdvance(stats.strength, STRENGTH_XP * xpMods.get(player, stats.strength))
                completeQuest(SCORPION_CATCHER)
            }
            else -> {
                runQuestSection(npc, dialogue, "Subsequent dialogue")
                if (cage() == null) {
                    invAddOrDrop(objRepo, ScObjs.cage_empty)
                    mes("Thormac hands you another scorpion cage.") // Authored (not Wiki).
                }
            }
        }
    }

    private suspend fun ProtectedAccess.talkSeer(npc: Npc) {
        val caughtA = cage()?.let { 'a' in ScObjs.cages.getValue(it) } == true
        when {
            state == STARTED -> {
                runQuestSection(npc, dialogue, "Help from a seer")
                seerMirror()
                setState(SEER)
            }
            state == SEER && caughtA -> {
                runQuestSection(npc, dialogue, "More help from a seer")
                setState(SEER_AGAIN)
            }
            else -> {
                runQuestSection(npc, dialogue, "Subsequent dialogue (2)")
                seerMirror()
            }
        }
    }

    private fun ProtectedAccess.seerMirror() {
        mes("The seer produces a small mirror")
        mes("The seer gazes into the mirror")
        mes("The seer smoothes his hair with hand")
    }

    /* The scorpions */

    private fun ProtectedAccess.stung() {
        mes("The scorpion stings you!")
        player.queueHit(delay = 0, type = HitType.Typeless, damage = STING_DAMAGE)
    }

    private fun ProtectedAccess.catchScorpion(scorpion: Npc, cage: ObjType, letter: Char) {
        val held = ScObjs.cages.getValue(cage)
        if (state !in STARTED until COMPLETE || letter in held) return
        val next = ScObjs.cages.entries.first { it.value == (held + letter).toSortedSet().joinToString("") }.key
        invReplace(inv, cage, 1, next)
        npcRepo.del(scorpion, SCORPION_HIDE_TICKS)
        mes("You catch a scorpion!")
    }

    private suspend fun ProtectedAccess.searchWall(wall: BoundLocInfo) {
        if (state < SEER) {
            mes("You search the wall but find nothing.") // Authored (not Wiki).
            return
        }
        mes("You find a secret way through the wall.") // Authored (not Wiki).
        passThroughWall(wall)
    }

    /* Taverley Dungeon: Velrak, the jail and the dusty-key gate */

    private suspend fun ProtectedAccess.talkVelrak(npc: Npc) {
        val hasKey = carries(ScObjs.dusty_key)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Without having a dusty key in the inventory:" to { !hasKey },
                        "With a dusty key in the inventory:" to { hasKey },
                    )
            )
        runQuestSection(npc, velrakDialogue, "Standard dialogue", hooks)
        if (!hasKey) invAddOrDrop(objRepo, ScObjs.dusty_key)
    }

    private suspend fun ProtectedAccess.lockedDoor(door: BoundLocInfo, key: ObjType, locked: String) {
        if (!carries(key)) {
            if (locked.isNotEmpty()) mes(locked) // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    private fun npcKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != ScNpcs.jailer.id) return
        val player = event.hero
        if (player.inv.count(objTypes[ScObjs.jail_key]) > 0) return
        objRepo.add(ScObjs.jail_key, event.npc.coords, DROP_TICKS, player)
    }

    /* Edgeville Monastery */

    private suspend fun ProtectedAccess.talkAbbot(npc: Npc) {
        var asked = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have 31 prayer:" to { statBase(stats.prayer) < PRAYER_LEVEL },
                        "If the player has 31 prayer:" to { statBase(stats.prayer) >= PRAYER_LEVEL },
                    ),
                options = mapOf("Well can I join your order?" to { asked = true }),
                boxes =
                    mapOf(
                        "Abbot Langley places his hands on your head. You feel a little better." to {
                            statHeal(stats.hitpoints, HEAL_CONSTANT, 0)
                            false
                        },
                        // Authored (not Wiki): the sigil service isn't built.
                        "Abbot Langley carefully breaks each shield, leaving the sigils perfectly intact." to {
                            mes("You don't have any blessed spirit shields for him to break down.")
                            true
                        },
                    ),
            )
        runQuestSection(npc, abbotDialogue, "Standard dialogue", hooks)
        if (asked) joinOrder()
    }

    private fun ProtectedAccess.joinOrder() {
        if (statBase(stats.prayer) < PRAYER_LEVEL) return
        VarPlayerIntMapSetter.set(player, ScVarps.flags, flags() or MONASTERY_MEMBER)
    }

    private suspend fun ProtectedAccess.monasteryLadder() {
        if (flags() and MONASTERY_MEMBER == 0) {
            var asked = false
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player does not have 31 prayer:" to { statBase(stats.prayer) < PRAYER_LEVEL },
                            "If the player has 31 prayer:" to { statBase(stats.prayer) >= PRAYER_LEVEL },
                        ),
                    options = mapOf("Well can I join your order?" to { asked = true }),
                )
            val section = "Interacting with the south-eastern ladder before joining the Edgeville Monastery order"
            runQuestSection(null, abbotDialogue, section, hooks, mapOf("Abbot Langley" to ScNpcs.abbot))
            if (asked) joinOrder()
            return
        }
        val at = player.coords
        telejump(CoordGrid(at.x, at.z, at.level + 1))
    }

    /* The Barbarian Outpost building (Ivor's bed): Barcrawl done; other prison doors just open. */

    private suspend fun ProtectedAccess.outpostDoor(door: BoundLocInfo) {
        if (door.coords in OUTPOST_DOORS && !player.isQuestComplete(BARCRAWL)) {
            mes("The door is locked. Only barbarians may enter.") // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    companion object {
        const val STARTED = 1
        const val SEER = 2
        const val SEER_AGAIN = 3
        const val COMPLETE = 6

        private const val MONASTERY_MEMBER = 1 shl 0
        private const val PRAYER_LEVEL = 31
        private const val STRENGTH_XP = 6_625.0
        private const val STING_DAMAGE = 3
        private const val HEAL_CONSTANT = 2
        private const val SCORPION_HIDE_TICKS = 100
        private const val DROP_TICKS = 200

        private val OUTPOST_DOORS = setOf(CoordGrid(2551, 3569, 0), CoordGrid(2551, 3570, 0))

        val SCORPION_CATCHER =
            QuestDef(
                name = "Scorpion Catcher",
                varp = { ScVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("6,625 Strength XP", "Thormac makes mystic staves (40,000 coins)"),
                scrollObj = { ScObjs.cage_full },
                listRow = "quest_scorpioncatcher",
            )
    }
}

// Every cache ref Scorpion Catcher uses (re-check after cache updates).

internal object ScNpcs : NpcReferences() {
    val thormac = find("thormac")
    val seer = find("seer")
    val velrak = find("velrak_the_explorer")
    val jailer = find("jailer")
    val abbot = find("abbot_langley")

    /** a: Taverley Dungeon; b: Barbarian Outpost; c: Edgeville Monastery. */
    val scorpions: List<Pair<NpcType, Char>> =
        listOf(find("questscorpiona") to 'a', find("questscorpionb") to 'b', find("questscorpionc") to 'c')
}

internal object ScLocs : LocReferences() {
    val secret_wall = find("scorpionwall")
    val velrak_cell = find("dungeonjail")
    val dusty_gate = find("deepdungeondoor")
    val monastery_ladder = find("monasteryladder")
    val outpost_doors = listOf(find("prisondoor_l"), find("prisondoor_r"))
}

internal object ScObjs : ObjReferences() {
    val cage_empty = find("scorpioncageempty")
    val cage_full = find("scorpioncagefull")
    private val cage_a = find("scorpioncagea")
    private val cage_b = find("scorpioncageb")
    private val cage_c = find("scorpioncagec")
    private val cage_ab = find("scorpioncageab")
    private val cage_ac = find("scorpioncageac")
    private val cage_bc = find("scorpioncagebc")

    /** Each cage variant and the scorpions it holds (built lazily from the refs above: find() only resolves at init). */
    val cages: Map<ObjType, String> by lazy {
        linkedMapOf(
            cage_empty to "",
            cage_a to "a",
            cage_b to "b",
            cage_c to "c",
            cage_ab to "ab",
            cage_ac to "ac",
            cage_bc to "bc",
            cage_full to "abc",
        )
    }
    val jail_key = find("jail_key")
    val dusty_key = find("dusty_key")
}

internal object ScVarps : VarpReferences() {
    val quest = find("scorpcatcher")

    /** Server varp (65799): bit 0 Edgeville Monastery member (Abbot Langley). */
    val flags = find("world_flags")
}

internal object ScVarpBuilds : VarpBuilder() {
    init {
        build("world_flags")
    }
}
