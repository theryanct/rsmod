package org.rsmod.content.travel.charter

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Charter ships (OSRS Wiki "Charter ship"): Trader Stan and his crewmembers' "Charter" op lists the
 * destinations with their Wiki base fares from the port the crewmember stands at, then sails there
 * with the shared [sailTo] fade. Quest/visit unlocks are skipped (local server rule); the Cabin
 * Fever / ring of charos discounts are not applied yet. Ports with no direct fare on the Wiki
 * (e.g. Port Sarim -> the Pandemonium) need a layover, as in OSRS.
 */
class Charter
@Inject
constructor(private val npcTypes: NpcTypeList, private val collision: CollisionFlagMap) : PluginScript() {
    private val logger = InlineLogger()

    private val destinations = CharterData.destinations.associateBy { it.key }

    override fun ScriptContext.startup() {
        var bound = 0
        for (type in npcTypes.values) {
            if (type.name !in CREW_NAMES) {
                continue
            }
            val slot = type.op.indexOfFirst { it.equals("Charter", ignoreCase = true) }
            if (slot == -1) {
                continue
            }
            bind(type, slot + 1)
            bound++
        }
        logger.info { "Charter: ${destinations.size} destinations; Charter op bound on $bound npc types." }
    }

    private fun ScriptContext.bind(type: UnpackedNpcType, op: Int) {
        when (op) {
            1 -> if (!isNpcOpBound(type, 1)) onOpNpc1(type) { charter(it.npc) }
            2 -> if (!isNpcOpBound(type, 2)) onOpNpc2(type) { charter(it.npc) }
            3 -> if (!isNpcOpBound(type, 3)) onOpNpc3(type) { charter(it.npc) }
            4 -> if (!isNpcOpBound(type, 4)) onOpNpc4(type) { charter(it.npc) }
            5 -> if (!isNpcOpBound(type, 5)) onOpNpc5(type) { charter(it.npc) }
        }
    }

    private suspend fun ProtectedAccess.charter(npc: Npc) {
        val origin = originNear(npc.coords)
        if (origin == null) {
            mes("There are no charter ships sailing from here.")
            return
        }
        val routes =
            CharterData.fares
                .filter { it.from == origin.key && it.to in destinations }
                .sortedBy { it.coins }
        if (routes.isEmpty()) {
            mes("There are no charter ships sailing from ${origin.name}.")
            return
        }
        val route = pickRoute(routes) ?: return
        val dest = destinations.getValue(route.to)
        val arrival = collision.nearestWalkable(CoordGrid(dest.x, dest.z, 0))
        sailTo(arrival, route.coins, "You pay ${route.coins} coins and board the ship to ${dest.name}.", collision)
    }

    /** OSRS shows a map; here the destinations page through choice dialogs (4 per page + More). */
    private suspend fun ProtectedAccess.pickRoute(routes: List<CharterData.Fare>): CharterData.Fare? {
        var page = 0
        while (true) {
            val start = page * PAGE
            val shown = routes.drop(start).take(PAGE)
            val more = start + PAGE < routes.size
            val labels = shown.map { "${destinations.getValue(it.to).name} (${it.coins} coins)" }
            val options = labels.mapIndexed { i, label -> label to i } +
                (if (more) "More destinations..." to MORE else "Never mind." to CANCEL)
            val picked =
                when (options.size) {
                    2 -> choice2(options[0].first, options[0].second, options[1].first, options[1].second, title = TITLE)
                    3 -> choice3(options[0].first, options[0].second, options[1].first, options[1].second,
                        options[2].first, options[2].second, title = TITLE)
                    4 -> choice4(options[0].first, options[0].second, options[1].first, options[1].second,
                        options[2].first, options[2].second, options[3].first, options[3].second, title = TITLE)
                    else -> choice5(options[0].first, options[0].second, options[1].first, options[1].second,
                        options[2].first, options[2].second, options[3].first, options[3].second,
                        options[4].first, options[4].second, title = TITLE)
                }
            when (picked) {
                CANCEL -> return null
                MORE -> page++
                else -> return shown[picked]
            }
        }
    }

    /** The charter port whose Wiki dock pin is closest to the crewmember (within [PORT_RADIUS]). */
    private fun originNear(coords: CoordGrid): CharterData.Destination? =
        CharterData.destinations
            .minByOrNull { (it.x - coords.x) * (it.x - coords.x) + (it.z - coords.z) * (it.z - coords.z) }
            ?.takeIf { maxOf(kotlin.math.abs(it.x - coords.x), kotlin.math.abs(it.z - coords.z)) <= PORT_RADIUS }

    private companion object {
        val CREW_NAMES = setOf("Trader Crewmember", "Trader Stan")
        const val TITLE = "Where would you like to sail?"
        const val PAGE = 4
        const val MORE = -1
        const val CANCEL = -2
        const val PORT_RADIUS = 32
    }
}
