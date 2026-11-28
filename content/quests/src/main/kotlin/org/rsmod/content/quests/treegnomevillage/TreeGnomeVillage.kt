package org.rsmod.content.quests.treegnomevillage

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
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
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tree Gnome Village (OSRS Wiki). `treequest` varp (cache db end state 9; the cache's Elkoy
 * multinpcs drop his Follow op at 0 and 7, the spirit trees gain theirs at 9). Our steps: 1 King
 * Bolren's plea, 2 Montai wants six logs, 3 logs given, 4 sent for the trackers' coordinates,
 * 5 the ballista breached the stronghold, 6 the first orb taken from the stronghold chest, 7 orb
 * given to Bolren (the village was pillaged; a warlord has the other two), 8 the warlord's orbs,
 * 9 complete (the ceremony: gnome amulet, 11,450 Attack xp, spirit trees, 2 QP).
 *
 * Cache varbits: `gnometracker_h` / `gnometracker_y` / `gnometracker_x` (each tracker's coordinate
 * learnt), `ballista` (the mad tracker's x coordinate, 0-3 for 0001-0004, rolled when Montai sends
 * you) and `khazard_warlord_fighting` (the warlord multinpc turns attackable once he's
 * challenged). Elkoy's "guides you through the maze" is a teleport between the maze's north
 * entrance and the village.
 *
 * Not done: Bolkoy's shop and the local gnomes' chatter.
 */
class TreeGnomeVillage
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(TreeGnomeVillage::class.java, "tree_gnome_village")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Elkoy" to TgvNpcs.elkoy_follow,
            "Khazard commander" to TgvNpcs.commander,
            "[Northern Local Gnomes]" to TgvNpcs.chanter,
            "[Southern Local Gnomes]" to TgvNpcs.chanter,
        )
    }

    private val fixes =
        SimpleQuestHooks(
            rewrites = {
                when (it) {
                    "height}} coordinate." -> "Tracker gnome 1 gives you the height coordinate."
                    "y coordinate.}}" -> "Tracker gnome 2 gives you the y coordinate."
                    else -> it.replace("#ffffff|", "")
                }
            }
        )

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, TgvNpcs.bolren, "Talk-to") { talkBolren(it) }
        for (elkoy in TgvNpcs.elkoys) {
            onNpcOpLabel(npcTypes, elkoy, "Talk-to") { talkElkoy(it) }
        }
        onNpcOpLabel(npcTypes, TgvNpcs.elkoy_follow, "Follow") { guideThroughMaze() }
        onNpcOpLabel(npcTypes, TgvNpcs.remsai, "Talk-to") { talkRemsai(it) }
        onNpcOpLabel(npcTypes, TgvNpcs.kalron, "Talk-to") { talkKalron(it) }
        onNpcOpLabel(npcTypes, TgvNpcs.montai, "Talk-to") { talkMontai(it) }
        for ((index, tracker) in TgvNpcs.trackers.withIndex()) {
            onNpcOpLabel(npcTypes, tracker, "Talk-to") { talkTracker(it, index) }
        }
        onNpcOpLabel(npcTypes, TgvNpcs.warlord_chat, "Talk-to") { talkWarlord(it) }
        onEvent<NpcKilledEvent> { warlordKilled(this) }

        onLocOpLabel(locTypes, TgvLocs.ballista, "Fire") { fireBallista() }
        onLocOpLabel(locTypes, TgvLocs.crumbled_wall, "Climb-over") { climbWall(it) }
        onLocOpLabel(locTypes, TgvLocs.stronghold_door, "Open") { strongholdDoor(it) }
        onLocOpLabel(locTypes, TgvLocs.chest_closed, "Open") {
            locRepo.change(it, TgvLocs.chest_open, CHEST_TICKS)
        }
        onLocOpLabel(locTypes, TgvLocs.chest_open, "Close") {
            locRepo.change(it, TgvLocs.chest_closed, CHEST_TICKS)
        }
        onLocOpLabel(locTypes, TgvLocs.chest_open, "Search") { searchChest() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TREE_GNOME_VILLAGE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(TREE_GNOME_VILLAGE, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Boolean = player.vars[varbit] != 0

    private fun ProtectedAccess.setVar(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.inVillage(): Boolean = player.coords.z < MAZE_NORTH_EDGE

    /** Elkoy leads the player through the maze, whichever side they're on. */
    private suspend fun ProtectedAccess.guideThroughMaze() {
        telejump(if (inVillage()) MAZE_ENTRANCE else VILLAGE)
    }

    /* King Bolren and the village */

    private suspend fun ProtectedAccess.talkBolren(npc: Npc) {
        var guided = false
        var hunting = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes." to { if (state == 0) setState(STARTED) },
                        "I will find the warlord and bring back the orbs." to { hunting = true },
                    ),
                boxes =
                    mapOf(
                        "Elkoy guides you out of the maze." to {
                            guided = true
                            false
                        }
                    ),
            )
        val section =
            when (state) {
                0 -> "Tree Gnome King"
                STARTED -> "Before agreeing to bring logs to Montai"
                in LOGS_ASKED until ORB_TAKEN -> "After agreeing to bring logs to Montai"
                ORB_TAKEN ->
                    if (carries(TgvObjs.orb)) {
                        "Bringing Bolren the orb"
                    } else {
                        "Talking to Bolren without the orb after finding it"
                    }
                ORB_RETURNED -> BOLREN_NO_ORBS
                ORBS_FOUND -> if (carries(TgvObjs.orbs)) "Return of the orb" else BOLREN_NO_ORBS
                else -> null
            }
        if (section == null) {
            startDialogue(npc) {
                chatNpc(happy, "Welcome back, friend of the gnomes.")
            }
            return
        }
        runQuestSection(npc, dialogue, section, hooks, speakers)
        when {
            hunting && state == ORB_TAKEN -> {
                invDel(inv, TgvObjs.orb)
                setVar(TgvVarBits.got_orb, 1)
                setState(ORB_RETURNED)
            }
            section == "Return of the orb" -> {
                invDel(inv, TgvObjs.orbs)
                invAddOrDrop(objRepo, TgvObjs.amulet)
                statAdvance(stats.attack, REWARD_XP * xpMods.get(player, stats.attack))
                completeQuest(TREE_GNOME_VILLAGE)
            }
        }
        if (guided) telejump(MAZE_ENTRANCE)
    }

    private suspend fun ProtectedAccess.talkElkoy(npc: Npc) {
        if (state == 0) {
            startDialogue(npc) { chatNpc(neutral, "Hello there, welcome to the gnome maze.") }
            return
        }
        val inside = inVillage()
        val withOrb = carries(TgvObjs.orb)
        val section =
            when (state) {
                STARTED -> if (inside) "Inside the maze" else "Outside the maze"
                in LOGS_ASKED until ORB_TAKEN -> "Talking to Elkoy after agreeing to help Montai"
                ORB_TAKEN ->
                    when {
                        withOrb && inside -> "Inside the maze (3)"
                        withOrb -> "Elkoy after getting the orb"
                        inside -> "Inside the maze (2)"
                        else -> "Outside the maze (2)"
                    }
                ORB_RETURNED,
                ORBS_FOUND -> if (inside) "Inside the maze (4)" else "Outside the maze (3)"
                else -> if (inside) "Inside the maze (5)" else "Elkoy after defeating the warlord"
            }
        var guide = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes please." to { guide = true },
                        "Can you show me out of the village?" to { guide = true },
                    )
            )
        runQuestSection(npc, dialogue, section, hooks)
        if (guide) guideThroughMaze()
    }

    private suspend fun ProtectedAccess.talkRemsai(npc: Npc) {
        val section =
            when (state) {
                0 -> null
                ORB_TAKEN ->
                    if (carries(TgvObjs.orb)) {
                        "Remsai after getting the orb"
                    } else {
                        "Talking to Remsai without the orb after finding it"
                    }
                ORB_RETURNED -> "Talking to Remsai (2)"
                ORBS_FOUND,
                COMPLETE -> "Talking to Remsai after getting the orbs"
                else -> "Talking to Remsai"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "Hello traveller.") }
            return
        }
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.talkKalron(npc: Npc) {
        val section =
            when {
                state >= ORBS_FOUND -> "Talking to Kalron after getting the orbs"
                state == ORB_RETURNED -> "Talking to Kalron (2)"
                else -> "Talking to Kalron"
            }
        runQuestSection(npc, dialogue, section)
    }

    /* The battlefield */

    private suspend fun ProtectedAccess.talkMontai(npc: Npc) {
        val logs = carries(TgvObjs.logs, LOGS_NEEDED)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have 6 logs in their inventory:" to { !logs },
                        "If the player has 6 logs in their inventory:" to { logs },
                    ),
                options =
                    mapOf(
                        "Ok, I'll gather some wood." to {
                            if (state == STARTED) setState(LOGS_ASKED)
                        },
                        "I'll try my best." to {
                            if (state == LOGS_GIVEN) {
                                setVar(TgvVarBits.ballista_x, random.of(0, 3))
                                setState(COORDINATES)
                            }
                        },
                    ),
                rewrites = { fixes.rewrite(it) },
            )
        val section =
            when (state) {
                0 -> null
                STARTED -> "Commander Montai"
                LOGS_ASKED -> "Commander Montai, again"
                LOGS_GIVEN -> "Talking to Commander Montai afterwards"
                COORDINATES -> "Commander Montai while getting the coordinates"
                BREACHED -> "Commander Montai after shooting the ballista"
                ORB_TAKEN -> "Commander Montai after getting the orb"
                else -> "Talking to Commander Montai"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "This is no place for sightseers, traveller.") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (section == "Commander Montai, again" && logs) {
            invDel(inv, TgvObjs.logs, LOGS_NEEDED)
            setState(LOGS_GIVEN)
        }
    }

    private suspend fun ProtectedAccess.talkTracker(npc: Npc, index: Int) {
        val n = index + 1
        val x = player.vars[TgvVarBits.ballista_x]
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the coordinate is one:" to { x == 0 },
                        "If the coordinate is two:" to { x == 1 },
                        "If the coordinate is three:" to { x == 2 },
                        "If the coordinate is four:" to { x == 3 },
                    ),
                rewrites = { fixes.rewrite(it) },
            )
        val section =
            when {
                state < COORDINATES -> "Talking to Tracker gnome $n"
                state == COORDINATES -> "Tracker gnome #$n"
                state == BREACHED || (state == ORB_TAKEN && n == 3) ->
                    "Talking to Tracker gnome $n after successfully firing the ballista"
                state == ORB_TAKEN -> "Tracker gnome $n after getting the orb"
                else -> "Tracker gnome $n"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (state == COORDINATES) setVar(TgvVarBits.trackers[index], 1)
    }

    private suspend fun ProtectedAccess.fireBallista() {
        when {
            state < LOGS_GIVEN -> {
                runQuestSection(null, dialogue, BALLISTA_BROKEN)
                return
            }
            state > COORDINATES -> {
                runQuestSection(null, dialogue, "Using the ballista again")
                return
            }
            state < COORDINATES || TgvVarBits.trackers.any { !bit(it) } -> {
                mes("You don't have all the coordinates of the Khazard stronghold yet.")
                return
            }
        }
        var chosen = -1
        val target = player.vars[TgvVarBits.ballista_x]
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player chooses the wrong coordinate:" to { chosen != target },
                        "If the player chooses the correct coordinate:" to { chosen == target },
                    ),
                options = (0..3).associate { i -> "000${i + 1}" to suspend { chosen = i } },
            )
        runQuestSection(null, dialogue, "Using the ballista", hooks)
        if (chosen == target) setState(BREACHED)
    }

    /* The Khazard stronghold */

    private suspend fun ProtectedAccess.climbWall(wall: BoundLocInfo) {
        if (state < BREACHED) {
            mes("The wall is too high and solid to climb.")
            return
        }
        runQuestSection(null, dialogue, "Into the stronghold", speakers = speakers)
        val at = wall.coords
        telejump(if (player.coords.z <= at.z) at.translate(0, 1) else at.translate(0, -1))
    }

    private suspend fun ProtectedAccess.strongholdDoor(door: BoundLocInfo) {
        if (player.coords.z <= door.coords.z) {
            runQuestSection(null, dialogue, "Opening the stronghold door from the outside")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.searchChest() {
        if (state != BREACHED || carries(TgvObjs.orb)) {
            mes("The chest is empty.")
            return
        }
        runQuestSection(null, dialogue, ORB_CHEST, speakers = speakers)
        invAddOrDrop(objRepo, TgvObjs.orb)
        setState(ORB_TAKEN)
    }

    /* The Khazard warlord */

    private suspend fun ProtectedAccess.talkWarlord(npc: Npc) {
        val section =
            when {
                state >= ORBS_FOUND -> WARLORD_AGAIN
                state == ORB_RETURNED -> "The Khazard warlord"
                else -> "Talking to the Khazard warlord"
            }
        runQuestSection(npc, dialogue, section)
        setVar(TgvVarBits.warlord_fighting, 1)
    }

    private fun warlordKilled(event: NpcKilledEvent) {
        val id = event.npc.type.id
        if (id != TgvNpcs.warlord_combat.id && id != TgvNpcs.warlord.id) return
        val player = event.hero
        VarPlayerIntMapSetter.set(player, TgvVarBits.warlord_fighting, 0)
        if (player.questState(TREE_GNOME_VILLAGE) != ORB_RETURNED || player.hasOrbs()) return
        objRepo.add(TgvObjs.orbs, event.npc.coords, ORBS_DROP_TICKS, receiver = player)
        player.setQuestState(TREE_GNOME_VILLAGE, ORBS_FOUND)
        player.mes(
            "As the warlord falls to the ground, a ghostly vapour floats upwards from his " +
                "battle-worn armour. He drops the orbs of protection."
        )
    }

    private fun Player.hasOrbs(): Boolean = inv.objs.any { it?.id == TgvObjs.orbs.id }

    companion object {
        const val STARTED = 1
        const val LOGS_ASKED = 2
        const val LOGS_GIVEN = 3
        const val COORDINATES = 4
        const val BREACHED = 5
        const val ORB_TAKEN = 6
        const val ORB_RETURNED = 7
        const val ORBS_FOUND = 8
        const val COMPLETE = 9

        private const val LOGS_NEEDED = 6
        private const val REWARD_XP = 11450.0
        private const val CHEST_TICKS = 200
        private const val ORBS_DROP_TICKS = 200
        private const val MAZE_NORTH_EDGE = 3189

        private val MAZE_ENTRANCE = CoordGrid(2504, 3192, 0)
        private val VILLAGE = CoordGrid(2540, 3166, 0)

        private const val BOLREN_NO_ORBS =
            "Talking to King Bolren without the orbs after getting them"
        private const val BALLISTA_BROKEN = "Attempting to fire the ballista before the repairs"
        private const val ORB_CHEST = "Upon opening the chest containing the orb of protection"
        private const val WARLORD_AGAIN =
            "Talking to the Khazard warlord again after defeating him"

        val TREE_GNOME_VILLAGE =
            QuestDef(
                name = "Tree Gnome Village",
                varp = { TgvVarps.treequest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("11,450 Attack XP", "Gnome amulet", "Use of the spirit trees"),
                scrollObj = { TgvObjs.orb },
            )
    }
}

// Every cache ref Tree Gnome Village uses (re-check after cache updates).

internal object TgvNpcs : NpcReferences() {
    val bolren = find("king_bolren")

    /* Children of the elkoy (maze entrance) and elkoy_village multinpcs. */
    val elkoy_follow = find("elkoy_2ops")
    val elkoys = listOf(find("elkoy_1op"), elkoy_follow)
    val remsai = find("remsai")
    val kalron = find("lostgnome")
    val chanter = find("chantergnome")
    val montai = find("commander_montai")
    val trackers = listOf(find("tracker1"), find("tracker2"), find("tracker3"))
    val commander = find("khazard_commander")

    /* The khazard_warlord multinpc (on khazard_warlord_fighting) and its children. */
    val warlord = find("khazard_warlord")
    val warlord_chat = find("khazard_warlord_chat")
    val warlord_combat = find("khazard_warlord_combat")
}

internal object TgvLocs : LocReferences() {
    val ballista = find("catabow")
    val crumbled_wall = find("khazzacklowwall")
    val stronghold_door = find("khazard_stronghold_door")
    val chest_closed = find("chestclosed_khazard")
    val chest_open = find("chestopen_khazard")
}

internal object TgvObjs : ObjReferences() {
    val logs = find("logs")
    val orb = find("orb_of_protection")
    val orbs = find("orbs_of_protection")
    val amulet = find("gnome_amulet")
}

internal object TgvVarps : VarpReferences() {
    val treequest = find("treequest")
}

internal object TgvVarBits : VarBitReferences() {
    val trackers =
        listOf(find("gnometracker_h"), find("gnometracker_y"), find("gnometracker_x"))
    val ballista_x = find("ballista")
    val got_orb = find("bolren_got_orbs")
    val warlord_fighting = find("khazard_warlord_fighting")
}
