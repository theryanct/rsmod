package org.rsmod.content.skills.construction

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.game.entity.Player
import org.rsmod.game.region.Region
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Construction PR1 step (a): player-owned house instance.
 *
 * - The Rimmington house portal ("Enter"/"Home"/"Build mode") builds a small dynamic region for
 *   the player from the cache's POH room templates: a garden (zone 0,0) with a parlour to its
 *   east (zone 1,0). The garden centrepiece space holds the exit portal, as in a new OSRS house,
 *   and the door hotspots on the shared wall are removed so the rooms connect.
 * - The exit portal returns the player outside the Rimmington portal. A player who logs out
 *   inside the house is moved back outside, since the region is gone by the next login.
 *
 * Region lifetime: [RegionRepository.add] first reclaims every region with no players in it
 * (`RegionRegistry.removeInactive*Regions`), so a house left empty is freed at the next house
 * entry, and the number of live houses is bounded by the region slot capacity (no leak).
 *
 * Next (step b): build mode, hotspots and furniture.
 */
class Construction
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val regionRepo: RegionRepository,
    private val locRepo: LocRepository,
) : PluginScript() {
    private val logger = InlineLogger()

    /** The house region each player is currently in, by player uid. */
    private val houses = HashMap<Int, Region>()

    private val template by lazy {
        RegionTemplate.create {
            this[0, 0, 0] = PohTemplates.garden
            this[1, 0, 0] = PohTemplates.parlour
        }
    }

    override fun ScriptContext.startup() {
        onLocOp(ConstructionLocs.rimmington_portal, "enter") { enterHouse(buildMode = false) }
        onLocOp(ConstructionLocs.rimmington_portal, "home") { enterHouse(buildMode = false) }
        onLocOp(ConstructionLocs.rimmington_portal, "build mode") { enterHouse(buildMode = true) }
        onLocOp(ConstructionLocs.exit_portal, "enter") { leaveHouse() }
        onPlayerLogout { moveOutOfHouse(player) }
        onPlayerLogin { moveOutOfHouse(player) }
    }

    private fun ScriptContext.onLocOp(
        type: LocType,
        label: String,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        when (locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> onOpLoc1(type) { action() }
            2 -> onOpLoc2(type) { action() }
            3 -> onOpLoc3(type) { action() }
            4 -> onOpLoc4(type) { action() }
            5 -> onOpLoc5(type) { action() }
            else -> logger.warn { "Construction: ${locTypes[type].name} has no \"$label\" op." }
        }
    }

    private fun ProtectedAccess.enterHouse(buildMode: Boolean) {
        val region = regionRepo.add(template)
        if (region == null) {
            mes("Your house can't be loaded right now. Try again in a few seconds.")
            return
        }
        setUpRooms(region)
        houses[player.uid.packed] = region
        if (buildMode) {
            mes("You enter your house in building mode.")
        } else {
            mes("Welcome to your house.")
        }
        telejump(region.local(0, 0, PohTemplates.ARRIVE_X, PohTemplates.ARRIVE_Z))
    }

    private fun setUpRooms(region: Region) {
        val centrepiece =
            region.local(0, 0, PohTemplates.CENTREPIECE_X, PohTemplates.CENTREPIECE_Z)
        val space = locRepo.findExact(centrepiece, ConstructionLocs.garden_centrepiece)
        if (space != null) {
            locRepo.change(space, ConstructionLocs.exit_portal, Int.MAX_VALUE)
        }
        // Open the shared wall: garden east edge (local x 7) meets parlour west edge (local x 0).
        for (z in 3..4) {
            removeDoorHotspot(region.local(0, 0, 7, z))
            removeDoorHotspot(region.local(1, 0, 0, z))
        }
    }

    private fun removeDoorHotspot(coords: CoordGrid) {
        for (door in listOf(ConstructionLocs.door_left, ConstructionLocs.door_right)) {
            val loc = locRepo.findExact(coords, door) ?: continue
            locRepo.del(loc, Int.MAX_VALUE)
        }
    }

    private fun ProtectedAccess.leaveHouse() {
        houses.remove(player.uid.packed)
        telejump(PohTemplates.rimmington)
    }

    private fun moveOutOfHouse(player: Player) {
        houses.remove(player.uid.packed)
        if (RegionRegistry.inWorkingArea(player.coords)) {
            player.coords = PohTemplates.rimmington
        }
    }

    /** Region coords of a tile in the room copied at region zone ([zoneX], [zoneZ]). */
    private fun Region.local(zoneX: Int, zoneZ: Int, x: Int, z: Int): CoordGrid =
        southWest.translate(zoneX * 8 + x, zoneZ * 8 + z)
}
