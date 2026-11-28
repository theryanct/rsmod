package org.rsmod.content.skills.construction

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.FurnitureMenuEntry
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
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
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.construction.ConstructionData.Furniture
import org.rsmod.content.skills.construction.ConstructionData.Group
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.region.Region
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Construction PR1: a player-owned house with a garden and a parlour.
 *
 * - The Rimmington house portal ("Enter"/"Home"/"Build mode") builds a small dynamic region for
 *   the player from the cache's POH room templates: a garden (zone 0,0) with a parlour to its
 *   east (zone 1,0). The garden centrepiece space holds the exit portal, as in a new OSRS house,
 *   and the door hotspots on the shared wall are removed so the rooms connect.
 * - Built parlour furniture is saved in one varp and placed on its hotspots when the house is
 *   built. In build mode the empty hotspots stay (with their "Build" op); otherwise they are
 *   removed so only real furniture shows.
 * - "Build" on a hotspot offers the Wiki furniture for it (level, planks/cloth/clay and nails,
 *   hammer and saw, xp); "Remove" on furniture (build mode only) puts the hotspot back.
 * - The exit portal returns the player outside the Rimmington portal. A player who logs out
 *   inside the house is moved back outside, since the region is gone by the next login.
 *
 * Region lifetime: [RegionRepository.add] first reclaims every region with no players in it
 * (`RegionRegistry.removeInactive*Regions`), so a house left empty is freed at the next house
 * entry, and the number of live houses is bounded by the region slot capacity (no leak).
 */
class Construction
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val regionRepo: RegionRepository,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    private class House(val region: Region, val buildMode: Boolean)

    /** The house each player is currently in, by player uid. */
    private val houses = HashMap<Int, House>()

    private val template by lazy {
        RegionTemplate.create {
            this[0, 0, 0] = PohTemplates.garden
            this[1, 0, 0] = PohTemplates.parlour
            // Black void everywhere else on the ground level (unmapped zones render white).
            for (x in 0 until PohTemplates.REGION_ZONES) {
                for (z in 0 until PohTemplates.REGION_ZONES) {
                    if (z == 0 && (x == 0 || x == 1)) continue
                    this[x, z, 0] = PohTemplates.void
                }
            }
        }
    }

    private val groupsByHotspot by lazy {
        ConstructionData.parlour
            .flatMap { group -> group.spots.map { loc(it.hotspot).id to group } }
            .groupBy({ it.first }, { it.second })
    }

    override fun ScriptContext.startup() {
        onLocOp(ConstructionLocs.rimmington_portal, "enter") { enterHouse(buildMode = false) }
        onLocOp(ConstructionLocs.rimmington_portal, "home") { enterHouse(buildMode = false) }
        onLocOp(ConstructionLocs.rimmington_portal, "build mode") { enterHouse(buildMode = true) }
        onLocOp(ConstructionLocs.exit_portal, "enter") { leaveHouse() }

        val hotspots = ConstructionData.parlour.flatMap { g -> g.spots.map { it.hotspot } }
        for (hotspot in hotspots.distinct()) {
            onLocOp(loc(hotspot), "build") { build(it) }
        }
        val furniture =
            ConstructionData.parlour.flatMap { g -> g.options.flatMap { it.locs.values } }
        for (name in furniture.distinct()) {
            onLocOp(loc(name), "remove") { remove(it) }
        }
        onPlayerLogout { moveOutOfHouse(player) }
        onPlayerLogin { moveOutOfHouse(player) }
    }

    private fun loc(name: String): LocType = ConstructionLocs.byName.getValue(name)

    private fun ScriptContext.onLocOp(
        type: LocType,
        label: String,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        when (locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
            4 -> onOpLoc4(type) { action(it.loc) }
            5 -> onOpLoc5(type) { action(it.loc) }
            else -> logger.warn { "Construction: ${locTypes[type].name} has no \"$label\" op." }
        }
    }

    /* House instance */

    private fun ProtectedAccess.enterHouse(buildMode: Boolean) {
        val region = regionRepo.add(template)
        if (region == null) {
            mes("Your house can't be loaded right now. Try again in a few seconds.")
            return
        }
        setUpRooms(player, region, buildMode)
        houses[player.uid.packed] = House(region, buildMode)
        if (buildMode) {
            mes("You enter your house in building mode.")
        } else {
            mes("Welcome to your house.")
        }
        telejump(region.local(0, 0, PohTemplates.ARRIVE_X, PohTemplates.ARRIVE_Z))
    }

    private fun setUpRooms(player: Player, region: Region, buildMode: Boolean) {
        val centrepiece =
            region.local(0, 0, PohTemplates.CENTREPIECE_X, PohTemplates.CENTREPIECE_Z)
        val space = locRepo.findExact(centrepiece, ConstructionLocs.garden_centrepiece)
        if (space != null) {
            locRepo.change(space, ConstructionLocs.exit_portal, PERMANENT)
        }
        // Open the shared wall: garden east edge (local x 7) meets parlour west edge (local x 0).
        for (z in 3..4) {
            removeDoorHotspots(region.local(0, 0, 7, z))
            removeDoorHotspots(region.local(1, 0, 0, z))
        }
        for ((index, group) in ConstructionData.parlour.withIndex()) {
            val tier = tier(player, index)
            for (spot in group.spots) {
                val coords = region.local(1, 0, spot.x, spot.z)
                val hotspot = locRepo.findExact(coords, loc(spot.hotspot)) ?: continue
                if (tier > 0) {
                    val built = group.options[tier - 1].locs.getValue(spot.hotspot)
                    locRepo.change(hotspot, loc(built), PERMANENT)
                } else if (!buildMode) {
                    locRepo.del(hotspot, PERMANENT)
                }
            }
        }
        if (!buildMode) {
            for (spot in ConstructionData.gardenSpots) {
                val coords = region.local(0, 0, spot.x, spot.z)
                val hotspot = locRepo.findExact(coords, loc(spot.hotspot)) ?: continue
                locRepo.del(hotspot, PERMANENT)
            }
        }
    }

    private fun removeDoorHotspots(coords: CoordGrid) {
        for (door in listOf(ConstructionLocs.door_left, ConstructionLocs.door_right)) {
            val loc = locRepo.findExact(coords, door) ?: continue
            locRepo.del(loc, PERMANENT)
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

    /* Furniture state: TIER_BITS per parlour group in the parlour varp. */

    private fun tier(player: Player, index: Int): Int {
        val bits = ConstructionData.TIER_BITS
        return (player.vars[ConstructionVarps.parlour] shr (index * bits)) and ((1 shl bits) - 1)
    }

    private fun setTier(player: Player, index: Int, tier: Int) {
        val bits = ConstructionData.TIER_BITS
        val mask = ((1 shl bits) - 1) shl (index * bits)
        val value = player.vars[ConstructionVarps.parlour]
        val updated = (value and mask.inv()) or ((tier shl (index * bits)) and mask)
        VarPlayerIntMapSetter.set(player, ConstructionVarps.parlour, updated)
    }

    /** The parlour group whose spot is at [loc]'s tile, or null outside the player's parlour. */
    private fun ProtectedAccess.groupAt(loc: BoundLocInfo, groups: List<Group>): Group? {
        val house = houses[player.uid.packed] ?: return null
        val parlour = house.region.local(1, 0, 0, 0)
        val x = loc.coords.x - parlour.x
        val z = loc.coords.z - parlour.z
        return groups.firstOrNull { group -> group.spots.any { it.x == x && it.z == z } }
    }

    /* Building */

    private suspend fun ProtectedAccess.build(loc: BoundLocInfo) {
        val house = houses[player.uid.packed]
        if (house == null || !house.buildMode) {
            mes("You can only build furniture in your own house in building mode.")
            return
        }
        val candidates = groupsByHotspot[loc.id] ?: return
        val group = groupAt(loc, candidates) ?: return
        val index = ConstructionData.parlour.indexOf(group)
        if (tier(player, index) > 0) return
        val furniture = chooseFurniture(group) ?: return
        if (!canBuild(furniture)) return
        consumeMaterials(furniture)
        anim(ConstructionSeqs.build)
        delay(2)
        setTier(player, index, group.options.indexOf(furniture) + 1)
        for (spot in group.spots) {
            val coords = house.region.local(1, 0, spot.x, spot.z)
            val hotspot = locRepo.findExact(coords, loc(spot.hotspot)) ?: continue
            locRepo.change(hotspot, loc(furniture.locs.getValue(spot.hotspot)), PERMANENT)
        }
        statAdvance(stats.construction, furniture.xp * xpMods.get(player, stats.construction))
        mes("You build a ${furniture.name.lowercase()}.")
    }

    /** The OSRS furniture build menu: model, name, level and materials per option. */
    private suspend fun ProtectedAccess.chooseFurniture(group: Group): Furniture? {
        val entries =
            group.options.map { furniture ->
                val iconName = ConstructionData.icons.getValue(furniture.name)
                val icon = ConstructionObjs.byName.getValue(iconName)
                FurnitureMenuEntry(
                    obj = icon,
                    level = furniture.level,
                    materials = materialLines(furniture),
                    available =
                        player.constructionLvl >= furniture.level && hasMaterials(furniture),
                )
            }
        val index = furnitureMenu(entries) ?: return null
        return group.options.getOrNull(index)
    }

    /** "Plank: 2" style lines, green when carried and red when not (as the OSRS menu shows). */
    private fun ProtectedAccess.materialLines(furniture: Furniture): List<String> {
        val lines = ArrayList<String>()
        for ((name, count) in furniture.materials) {
            val have = invTotal(inv, ConstructionObjs.byName.getValue(name)) >= count
            lines += colour("${ConstructionData.materialNames[name] ?: name}: $count", have)
        }
        if (furniture.nails > 0) {
            lines += colour("Nails: ${furniture.nails}", nailCount() >= furniture.nails)
        }
        return lines
    }

    private fun colour(text: String, ok: Boolean): String =
        if (ok) "<col=00ff00>$text</col>" else "<col=ff0000>$text</col>"

    private fun ProtectedAccess.nailCount(): Int =
        ConstructionData.nails.sumOf { invTotal(inv, ConstructionObjs.byName.getValue(it)) }

    private fun ProtectedAccess.hasMaterials(furniture: Furniture): Boolean =
        furniture.materials.all { (name, count) ->
            invTotal(inv, ConstructionObjs.byName.getValue(name)) >= count
        } && nailCount() >= furniture.nails

    private fun ProtectedAccess.canBuild(furniture: Furniture): Boolean {
        if (player.constructionLvl < furniture.level) {
            mes("You need a Construction level of ${furniture.level} to build that.")
            return false
        }
        val hasTools =
            invTotal(inv, ConstructionObjs.hammer) > 0 && invTotal(inv, ConstructionObjs.saw) > 0
        if (!hasTools) {
            mes("You need a hammer and a saw to build furniture.")
            return false
        }
        for ((name, count) in furniture.materials) {
            if (invTotal(inv, ConstructionObjs.byName.getValue(name)) < count) {
                mes("You don't have the materials to build that.")
                return false
            }
        }
        val nails =
            ConstructionData.nails.sumOf { invTotal(inv, ConstructionObjs.byName.getValue(it)) }
        if (nails < furniture.nails) {
            mes("You need ${furniture.nails} nails to build that.")
            return false
        }
        return true
    }

    private fun ProtectedAccess.consumeMaterials(furniture: Furniture) {
        for ((name, count) in furniture.materials) {
            invDel(inv, ConstructionObjs.byName.getValue(name), count)
        }
        var nails = furniture.nails
        for (name in ConstructionData.nails) {
            if (nails == 0) break
            val type = ConstructionObjs.byName.getValue(name)
            val used = minOf(nails, invTotal(inv, type))
            if (used > 0) {
                invDel(inv, type, used)
                nails -= used
            }
        }
    }

    private suspend fun ProtectedAccess.remove(loc: BoundLocInfo) {
        val house = houses[player.uid.packed]
        if (house == null || !house.buildMode) {
            mes("You can only remove furniture in your own house in building mode.")
            return
        }
        val candidates =
            ConstructionData.parlour.filter { group ->
                group.options.any { option -> option.locs.values.any { loc(it).id == loc.id } }
            }
        val group = groupAt(loc, candidates) ?: return
        val index = ConstructionData.parlour.indexOf(group)
        val tier = tier(player, index)
        if (tier == 0) return
        var confirmed = false
        startDialogue {
            confirmed =
                choice2(
                    "Yes, remove it.",
                    true,
                    "No, keep it.",
                    false,
                    title = "Really remove it?",
                )
        }
        if (!confirmed) return
        anim(ConstructionSeqs.build)
        setTier(player, index, 0)
        val built = group.options[tier - 1]
        for (spot in group.spots) {
            val coords = house.region.local(1, 0, spot.x, spot.z)
            val placed = locRepo.findExact(coords, loc(built.locs.getValue(spot.hotspot)))
            if (placed != null) {
                locRepo.change(placed, loc(spot.hotspot), PERMANENT)
            }
        }
        mes("You remove the ${group.noun}.")
    }

    private companion object {
        const val PERMANENT = Int.MAX_VALUE
    }
}
