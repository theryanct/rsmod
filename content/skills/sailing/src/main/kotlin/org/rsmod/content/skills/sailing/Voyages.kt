package org.rsmod.content.skills.sailing

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseSailingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Raft voyages (single player, no world entities yet): "Board" / "Board-previous" on a dock's
 * gangplank (`sailing_gangplank_<dock>`) or an island's mooring point (`sailing_mooring_<dock>`)
 * lists the docks your Sailing level reaches (the `sailing_dock` row's `level_required`, nearest
 * first) and sails your boat there with the shared [sailTo] fade. Your boat is then moored at that
 * dock (`sailing_boat_1_port`).
 *
 * Simplified until tier 1 (boardable, steerable world entities): no helm/sails/trim, no sea
 * collision or hazards, no travel time; boarding at a dock where your boat isn't moored has the
 * dockhands bring it round instead of needing a shipwright's "Recover-boat".
 *
 * Data: docks.tsv (dock rows from the rev 243 dbrow dump, plank/mooring positions from a cache map
 * scan).
 */
class Voyages @Inject constructor(private val collision: CollisionFlagMap) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var bound = 0
        for (dock in VoyageData.docks) {
            val loc = VoyageLocs.planks.getValue(dock.loc)
            if (!isLocOpBound(loc, 1)) onOpLoc1(loc) { board(dock) }
            if (!isLocOpBound(loc, 2)) onOpLoc2(loc) { board(dock) }
            bound++
        }
        logger.info { "Sailing: voyages between $bound docks." }
    }

    private suspend fun ProtectedAccess.board(from: VoyageData.Dock) {
        if (!SailingBoats.ownsBoat(player)) {
            mes("You don't have a boat to board. A shipwright can sort you out with one.")
            return
        }
        val moored = VoyageData.byId[SailingBoats.dockOf(player)]
        if (moored != null && moored.id != from.id) {
            mes("The dockhands bring your boat round from ${moored.name}.")
        }
        SailingBoats.moor(player, from.id)
        val level = player.baseSailingLvl
        val reachable =
            VoyageData.docks
                .filter { it.id != from.id && it.level <= level }
                .sortedBy { (it.x - from.x) * (it.x - from.x) + (it.z - from.z) * (it.z - from.z) }
        if (reachable.isEmpty()) {
            mes("There's nowhere you can sail to from here yet.")
            return
        }
        val dest = pickDock(reachable) ?: return
        val arrival = collision.nearestWalkable(CoordGrid(dest.x, dest.z, 0), ARRIVAL_RADIUS)
        val sailed = sailTo(arrival, fare = 0, "You board your boat and set sail for ${dest.name}.", collision)
        if (sailed) {
            SailingBoats.moor(player, dest.id)
            mes("You moor your boat at ${dest.name}.")
        }
    }

    /** OSRS uses the sea chart; here the docks page through choice dialogs (4 per page + More). */
    private suspend fun ProtectedAccess.pickDock(docks: List<VoyageData.Dock>): VoyageData.Dock? {
        var page = 0
        while (true) {
            val start = page * PAGE
            val shown = docks.drop(start).take(PAGE)
            val more = start + PAGE < docks.size
            val options =
                shown.mapIndexed { i, dock -> dock.name to i } +
                    (if (more) "More destinations..." to MORE else "Stay ashore." to CANCEL)
            val picked =
                when (options.size) {
                    2 -> choice2(options[0].first, options[0].second, options[1].first, options[1].second, title = TITLE)
                    3 ->
                        choice3(
                            options[0].first, options[0].second, options[1].first, options[1].second,
                            options[2].first, options[2].second, title = TITLE,
                        )
                    4 ->
                        choice4(
                            options[0].first, options[0].second, options[1].first, options[1].second,
                            options[2].first, options[2].second, options[3].first, options[3].second,
                            title = TITLE,
                        )
                    else ->
                        choice5(
                            options[0].first, options[0].second, options[1].first, options[1].second,
                            options[2].first, options[2].second, options[3].first, options[3].second,
                            options[4].first, options[4].second, title = TITLE,
                        )
                }
            when (picked) {
                CANCEL -> return null
                MORE -> page = if (more) page + 1 else 0
                else -> return shown[picked]
            }
        }
    }

    private companion object {
        const val PAGE = 4
        const val MORE = -1
        const val CANCEL = -2
        const val TITLE = "Where would you like to sail?"
        const val ARRIVAL_RADIUS = 4
    }
}

/**
 * The player's boat (boat slot 1, the raft): owned flag, `sailing_boat` type and the
 * `sailing_dock` id it is moored at - the varbits the client's sailing side panel reads.
 */
public object SailingBoats {
    /** `sailing_boat` raft: type_id 0. */
    public const val RAFT_TYPE: Int = 0

    /** `sailing_dock` port_sarim: dock_id 0. */
    public const val PORT_SARIM_DOCK: Int = 0

    /** `sailing_dock` the_pandemonium: dock_id 1. */
    public const val PANDEMONIUM_DOCK: Int = 1

    public fun ownsBoat(player: Player): Boolean = player.vars[SailingVarBits.boat_1_owned] != 0

    public fun dockOf(player: Player): Int = player.vars[SailingVarBits.boat_1_port]

    public fun moor(player: Player, dock: Int) {
        VarPlayerIntMapSetter.set(player, SailingVarBits.boat_1_port, dock)
    }

    /** Gives [player] the raft moored at [dock]; `false` (and no change) if they own a boat. */
    public fun grantRaft(player: Player, dock: Int): Boolean {
        if (ownsBoat(player)) {
            return false
        }
        VarPlayerIntMapSetter.set(player, SailingVarBits.boat_1_owned, 1)
        VarPlayerIntMapSetter.set(player, SailingVarBits.boat_1_type, RAFT_TYPE)
        moor(player, dock)
        return true
    }
}

internal object VoyageData {
    class Dock(val id: Int, val key: String, val name: String, val level: Int, val loc: String, val x: Int, val z: Int)

    val docks: List<Dock> by lazy {
        val input =
            VoyageData::class.java.getResourceAsStream("docks.tsv")
                ?: error("Sailing resource not found: docks.tsv")
        input.bufferedReader().use { it.readLines() }
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .map { Dock(it[0].toInt(), it[1], it[2], it[3].toInt(), it[4], it[5].toInt(), it[6].toInt()) }
    }

    val byId: Map<Int, Dock> by lazy { docks.associateBy { it.id } }
}

internal object VoyageLocs : LocReferences() {
    val planks: Map<String, LocType> = VoyageData.docks.associate { it.loc to find(it.loc) }
}
