package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Swensen the Navigator's maze (Wiki). It is the static 4 x 4 grid of portal rooms under his house
 * (x 2626-2669, z 10000-10042): `vt_mazeportal_N` stands in the Nth lit room, on the wall that
 * spells SWENSEN (S, W, E, N, S, E, N), and leads to the room holding portal N+1; portal 7 leads
 * to the exit ladder's room. Any other portal drops you in one of the eight dark rooms, whose
 * portals all lead to dark rooms; the escape ropes climb back up to his house.
 *
 * Simplified: the rooms are the shared map rather than an instance (single player).
 */
internal class FtSwensen(private val ft: FtContext) {
    private val speakers by lazy { mapOf("Swensen the Navigator" to FtNpcs.swensen) }

    fun ScriptContext.bind() {
        ft.bound("maze_top Climb-down") { onLocOpLabel(ft.locTypes, FtLocs.maze_top, "Climb-down") { enterMaze() } }
        ft.bound("maze_entrance Climb-up") { onLocOpLabel(ft.locTypes, FtLocs.maze_entrance, "Climb-up") { leaveMaze() } }
        ft.bound("maze_escape Climb-up") { onLocOpLabel(ft.locTypes, FtLocs.maze_escape, "Climb-up") { escape() } }
        ft.bound("maze_exit Climb-up") { onLocOpLabel(ft.locTypes, FtLocs.maze_exit, "Climb-up") { finishMaze() } }
        for ((index, portal) in FtLocs.maze_portals.withIndex()) {
            ft.bound("portal Use") { onLocOpLabel(ft.locTypes, portal, "Use") { usePortal(index + 1) } }
        }
        ft.bound("maze_portal_wrong Use") { onLocOpLabel(ft.locTypes, FtLocs.maze_portal_wrong, "Use") { usePortal(0) } }
    }

    suspend fun ProtectedAccess.talk(npc: Npc) {
        when {
            !player.accepted(Trial.Swensen) -> {
                var accepted = false
                runQuestSection(npc, ft.dialogue, "Talking to Swensen", SimpleQuestHooks(options = mapOf("Yes" to { accepted = true })))
                if (accepted) player.accept(Trial.Swensen)
            }
            player.voted(Trial.Swensen) -> runQuestSection(npc, ft.dialogue, "Talking to Swensen again (4)")
            else -> runQuestSection(npc, ft.dialogue, "Talking to Swensen again (3)")
        }
    }

    private suspend fun ProtectedAccess.enterMaze() {
        when {
            !player.onTrial(Trial.Swensen) && !player.voted(Trial.Swensen) ->
                runQuestSection(null, ft.dialogue, "Attempting to climb down the ladder or trapdoor before accepting the challenge", speakers = speakers)
            player.voted(Trial.Swensen) ->
                with(ft) { cond(null, "Talking to Swensen again (4)", "If the player attempts to climb down into the maze again:") }
            else -> {
                player.ftSetField(FtVarps.swensen, 0, 4, 0)
                delay(1)
                telejump(room(PATH[0]))
            }
        }
    }

    private suspend fun ProtectedAccess.leaveMaze() {
        delay(1)
        telejump(ft.tile(HOUSE.x, HOUSE.z, HOUSE.level))
    }

    private suspend fun ProtectedAccess.escape() {
        leaveMaze()
        if (player.onTrial(Trial.Swensen)) {
            with(ft) { cond(null, "Whilst inside the maze", "If the player leaves through the escape rope:", speakers = speakers) }
        }
    }

    private suspend fun ProtectedAccess.finishMaze() {
        leaveMaze()
        if (!player.onTrial(Trial.Swensen) || step() != EXIT_STEP) return
        with(ft) {
            cond(null, "Whilst inside the maze", "If the player climbs up the stairs in the north-western portal room:", speakers = speakers)
        }
        player.giveVote(Trial.Swensen)
        mes("Congratulations! You have completed the Navigator's trial!") // Authored (not Wiki).
    }

    /** [portal] 1-7 for the named portals, 0 for a wrong one. */
    private suspend fun ProtectedAccess.usePortal(portal: Int) {
        delay(1)
        val step = step()
        val lost = player.ftHas(FtVarps.swensen, LOST)
        if (portal != 0 && !lost && step == portal - 1) {
            player.ftSetField(FtVarps.swensen, 0, 3, portal)
            telejump(room(PATH[portal]))
            return
        }
        player.ftSet(FtVarps.swensen, LOST)
        telejump(room(DARK[ft.random.of(0, DARK.lastIndex)]))
    }

    private fun ProtectedAccess.step(): Int = player.ftField(FtVarps.swensen, 0, 3)

    private fun room(centre: Pair<Int, Int>): CoordGrid = ft.tile(centre.first + 1, centre.second, 0)

    private companion object {
        const val LOST = 1 shl 3
        const val EXIT_STEP = 7

        /** Swensen's house, by the ladder down. */
        val HOUSE = CoordGrid(2645, 3657, 0)

        /**
         * Room centres (each room's ladder / escape rope): the entrance room, then the room each
         * correct portal leads to; the last is the exit ladder's room.
         */
        val PATH =
            listOf(
                2631 to 10005, // (0,0) entrance: portal 1 south
                2642 to 10015, // (1,1) portal 2 west
                2653 to 10004, // (2,0) portal 3 east
                2665 to 10015, // (3,1) portal 4 north
                2630 to 10026, // (0,2) portal 5 south
                2653 to 10037, // (2,3) portal 6 east
                2666 to 10026, // (3,2) portal 7 north
                2665 to 10037, // (3,3) exit ladder
            )

        /** The eight off-path (dark) rooms. */
        val DARK =
            listOf(
                2642 to 10005,
                2664 to 10004,
                2631 to 10015,
                2653 to 10015,
                2642 to 10026,
                2655 to 10027,
                2630 to 10037,
                2642 to 10039,
            )
    }
}
