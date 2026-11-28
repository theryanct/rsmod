package org.rsmod.content.skills.construction

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.ui.FurnitureMenuEntry
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.construction.ConstructionData.EDGE_EAST
import org.rsmod.content.skills.construction.ConstructionData.EDGE_NORTH
import org.rsmod.content.skills.construction.ConstructionData.EDGE_SOUTH
import org.rsmod.content.skills.construction.ConstructionData.EDGE_WEST
import org.rsmod.content.skills.construction.ConstructionData.Option
import org.rsmod.content.skills.construction.ConstructionData.Room
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.region.Region
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Construction PR2: a player-owned house of rooms on a grid (PR1 had a fixed garden + parlour).
 *
 * - The layout is saved per player: up to 38 room slots (x, z, room type, rotation), two per
 *   `poh_layout_N` varp, plus one furniture varp per slot. A house that has never been saved is a
 *   garden with a parlour to its east; the PR1 parlour varp is moved into that parlour once.
 * - Entering builds a dynamic region from the cache's Rimmington-style room templates, each room
 *   zone turned by its rotation. Hotspots are found from the locs actually placed in each room's
 *   zone, so no tile rotation maths is needed. Doors on walls shared by two rooms that both have a
 *   door there are removed so the rooms connect (build mode keeps them: they don't block walking
 *   and are where rooms are built and removed).
 * - Build mode: "Build" on a door hotspot whose far side is empty offers the rooms (Wiki/cache
 *   level and coins, the Wiki's room count and yard size limits), then a facing if more than one
 *   keeps a door back through that doorway; on a doorway to an existing room it offers to remove
 *   that room (never the first garden, which holds the exit portal). The house is rebuilt and the
 *   player put back at the same spot.
 * - Furniture: "Build" on any hotspot offers that hotspot group's options (cache levels and
 *   materials, Wiki xp; hammer and saw), "Remove" (build mode) puts the hotspots back.
 *
 * Region lifetime: [RegionRepository.add] first reclaims every region with no players in it, so a
 * house left empty (including the old copy after a rebuild) is freed at the next house entry.
 */
class Construction
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val regionRepo: RegionRepository,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    /** A placed room: slot index, grid position, room and rotation. */
    private class Placed(val slot: Int, val x: Int, val z: Int, val room: Room, val rotation: Int)

    /** A furniture hotspot tile in the live house: which slot / group it belongs to. */
    private class Tile(val slot: Int, val group: Int, val suffix: String, val hotspot: LocType)

    private class House(
        val region: Region,
        val buildMode: Boolean,
        val rooms: List<Placed>,
        /** Hotspot tiles by (coords, layer), recorded before furniture replaces them. */
        val tiles: Map<Pair<CoordGrid, Int>, Tile>,
    )

    /** The house each player is currently in, by player uid. */
    private val houses = HashMap<Int, House>()

    /** (room key, hotspot loc id) -> spot. */
    private val spotsByLoc by lazy {
        ConstructionData.spots.associateBy { it.room to loc(it.hotspot).id }
    }

    private val doorIds by lazy {
        setOf(locTypes[ConstructionLocs.door_left].id, locTypes[ConstructionLocs.door_right].id)
    }

    override fun ScriptContext.startup() {
        onLocOp(ConstructionLocs.rimmington_portal, "enter") { enterHouse(buildMode = false) }
        onLocOp(ConstructionLocs.rimmington_portal, "home") { enterHouse(buildMode = false) }
        onLocOp(ConstructionLocs.rimmington_portal, "build mode") { enterHouse(buildMode = true) }
        onLocOp(ConstructionLocs.exit_portal, "enter") { leaveHouse() }
        onLocOp(ConstructionLocs.door_left, "build") { doorway(it) }
        onLocOp(ConstructionLocs.door_right, "build") { doorway(it) }

        for (hotspot in ConstructionData.spots.map { it.hotspot }.distinct()) {
            onLocOp(loc(hotspot), "build") { buildFurniture(it) }
        }
        for (name in ConstructionData.options.flatMap { it.locs.values }.distinct()) {
            onLocOp(loc(name), "remove") { removeFurniture(it) }
        }
        for (focus in PohFurnitureLocs.focus.values) {
            onLocOp(focus, "direct-portal") { directPortal(it) }
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
        val op = locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0) {
            logger.warn { "Construction: ${locTypes[type].name} has no \"$label\" op." }
            return
        }
        if (isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
            4 -> onOpLoc4(type) { action(it.loc) }
            5 -> onOpLoc5(type) { action(it.loc) }
        }
    }

    /* Saved layout */

    private fun layout(player: Player): List<Placed> {
        migrate(player)
        val rooms = ArrayList<Placed>()
        for (slot in 0 until ConstructionData.MAX_SLOTS) {
            val bits = slotBits(player, slot)
            val room = ConstructionData.roomsById[(bits shr 8) and 0x1F] ?: continue
            rooms += Placed(slot, bits and 0xF, (bits shr 4) and 0xF, room, (bits shr 13) and 3)
        }
        return rooms
    }

    private fun slotBits(player: Player, slot: Int): Int {
        val value = player.vars[ConstructionVarps.layout[slot / 2]]
        return (value ushr ((slot % 2) * ROOM_SLOT_BITS)) and ((1 shl ROOM_SLOT_BITS) - 1)
    }

    private fun setSlot(player: Player, slot: Int, x: Int, z: Int, room: Room?, rotation: Int) {
        val bits = if (room == null) 0 else x or (z shl 4) or (room.id shl 8) or (rotation shl 13)
        val varp = ConstructionVarps.layout[slot / 2]
        val shift = (slot % 2) * ROOM_SLOT_BITS
        val mask = ((1 shl ROOM_SLOT_BITS) - 1) shl shift
        val updated = (player.vars[varp] and mask.inv()) or (bits shl shift)
        VarPlayerIntMapSetter.set(player, varp, updated)
    }

    /** A never-saved house is PR1's: a garden with a parlour east of it (PR1 furniture kept). */
    private fun migrate(player: Player) {
        if (ConstructionVarps.layout.any { player.vars[it] != 0 }) return
        val garden = ConstructionData.roomsByKey.getValue("garden")
        val parlour = ConstructionData.roomsByKey.getValue("parlour")
        setSlot(player, 0, PohTemplates.START_X, PohTemplates.START_Z, garden, 0)
        setSlot(player, 1, PohTemplates.START_X + 1, PohTemplates.START_Z, parlour, 0)
        // PR1 groups (3 bits each): chair1, chair2, chair3, bookcase, rug, fireplace, curtains;
        // the cache's parlour hotspots: chair x3, rug, bookcase, fireplace, curtains.
        val legacy = player.vars[ConstructionVarps.parlour]
        val groups = intArrayOf(0, 1, 2, 4, 3, 5, 6)
        for ((old, group) in groups.withIndex()) {
            val tier = (legacy shr (old * 3)) and 7
            if (tier > 0) setTier(player, 1, "parlour", group, tier)
        }
    }

    /* Furniture state: per slot, the room's furniture layout bits. */

    private fun tier(player: Player, slot: Int, room: String, group: Int): Int {
        val (_, shift, bits) =
            ConstructionData.furnitureLayout.getValue(room).firstOrNull { it.first == group }
                ?: return 0
        return (player.vars[ConstructionVarps.furniture[slot]] ushr shift) and ((1 shl bits) - 1)
    }

    private fun setTier(player: Player, slot: Int, room: String, group: Int, tier: Int) {
        val (_, shift, bits) =
            ConstructionData.furnitureLayout.getValue(room).firstOrNull { it.first == group }
                ?: return
        val varp = ConstructionVarps.furniture[slot]
        val mask = ((1 shl bits) - 1) shl shift
        val updated = (player.vars[varp] and mask.inv()) or ((tier shl shift) and mask)
        VarPlayerIntMapSetter.set(player, varp, updated)
    }

    /* House instance */

    private fun template(rooms: List<Placed>): RegionTemplate =
        RegionTemplate.create {
            val used = HashSet<Pair<Int, Int>>()
            for (placed in rooms) {
                val zone = ZoneKey(placed.room.zoneX, placed.room.zoneZ, 0)
                when (placed.rotation) {
                    1 -> this[placed.x, placed.z, 0] = zone.rotate90()
                    2 -> this[placed.x, placed.z, 0] = zone.rotate180()
                    3 -> this[placed.x, placed.z, 0] = zone.rotate270()
                    else -> this[placed.x, placed.z, 0] = zone
                }
                used += placed.x to placed.z
            }
            // Black void everywhere else on the ground level (unmapped zones render white).
            for (x in 0 until PohTemplates.REGION_ZONES) {
                for (z in 0 until PohTemplates.REGION_ZONES) {
                    if (x to z !in used) this[x, z, 0] = PohTemplates.void
                }
            }
        }

    private fun ProtectedAccess.enterHouse(buildMode: Boolean) {
        val house = buildHouse(player, buildMode) ?: return
        val portal =
            house.region.zoneSw(house.rooms.first()).let { sw ->
                locRepo.findAll(ZoneKey.from(sw)).firstOrNull {
                    it.id == locTypes[ConstructionLocs.exit_portal].id
                }
            }
        if (buildMode) {
            mes("You enter your house in building mode.")
        } else {
            mes("Welcome to your house.")
        }
        val arrive =
            portal?.coords?.translate(-1, -1)
                ?: house.region.zoneSw(house.rooms.first()).translate(2, 2)
        telejump(arrive)
    }

    /** Builds the player's house region; null (with a message) when no region is free. */
    private fun ProtectedAccess.buildHouse(player: Player, buildMode: Boolean): House? {
        val rooms = layout(player)
        val region = regionRepo.add(template(rooms))
        if (region == null) {
            mes("Your house can't be loaded right now. Try again in a few seconds.")
            return null
        }
        val tiles = HashMap<Pair<CoordGrid, Int>, Tile>()
        for (placed in rooms) {
            setUpRoom(player, region, rooms, placed, buildMode, tiles)
        }
        val house = House(region, buildMode, rooms, tiles)
        houses[player.uid.packed] = house
        return house
    }

    private fun setUpRoom(
        player: Player,
        region: Region,
        rooms: List<Placed>,
        placed: Placed,
        buildMode: Boolean,
        tiles: MutableMap<Pair<CoordGrid, Int>, Tile>,
    ) {
        val sw = region.zoneSw(placed)
        val locs = locRepo.findAll(ZoneKey.from(sw)).toList()
        val entrance = placed.slot == rooms.first().slot
        for (loc in locs) {
            if (loc.id in doorIds) {
                val edge = edgeOf(loc, sw) ?: continue
                if (!buildMode && connected(rooms, placed, edge)) locRepo.del(loc, PERMANENT)
                continue
            }
            if (placed.room.key == "garden") {
                setUpGardenLoc(loc, entrance, buildMode)
                continue
            }
            val spot = spotsByLoc[placed.room.key to loc.id] ?: continue
            tiles[loc.coords to loc.layer] = Tile(placed.slot, spot.group, spot.suffix, loc(spot.hotspot))
            val tier = tier(player, placed.slot, placed.room.key, spot.group)
            val built = builtLoc(player, placed.slot, placed.room.key, spot.group, tier, spot.suffix)
            when {
                built != null -> locRepo.change(loc, built, PERMANENT)
                !buildMode -> locRepo.del(loc, PERMANENT)
            }
        }
    }

    private fun setUpGardenLoc(loc: LocInfo, entrance: Boolean, buildMode: Boolean) {
        if (loc.id == locTypes[ConstructionLocs.garden_centrepiece].id) {
            when {
                entrance -> locRepo.change(loc, ConstructionLocs.exit_portal, PERMANENT)
                !buildMode -> locRepo.del(loc, PERMANENT)
            }
            return
        }
        val hotspot = ConstructionData.gardenHotspots.any { loc(it).id == loc.id }
        if (hotspot && !buildMode) locRepo.del(loc, PERMANENT)
    }

    /** The wall a door hotspot sits on, from its tile in the (rotated) room zone. */
    private fun edgeOf(loc: LocInfo, sw: CoordGrid): Int? {
        val x = loc.coords.x - sw.x
        val z = loc.coords.z - sw.z
        return when {
            z == 7 -> EDGE_NORTH
            x == 7 -> EDGE_EAST
            z == 0 -> EDGE_SOUTH
            x == 0 -> EDGE_WEST
            else -> null
        }
    }

    private fun neighbour(placed: Placed, edge: Int): Pair<Int, Int> =
        when (edge) {
            EDGE_NORTH -> placed.x to placed.z + 1
            EDGE_EAST -> placed.x + 1 to placed.z
            EDGE_SOUTH -> placed.x to placed.z - 1
            else -> placed.x - 1 to placed.z
        }

    private fun connected(rooms: List<Placed>, placed: Placed, edge: Int): Boolean {
        val (nx, nz) = neighbour(placed, edge)
        val other = rooms.firstOrNull { it.x == nx && it.z == nz } ?: return false
        return ((edge + 2) and 3) in other.room.doors(other.rotation)
    }

    private fun Region.zoneSw(placed: Placed): CoordGrid = southWest.translate(placed.x * 8, placed.z * 8)

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

    /** Rebuilds the house after a layout change and puts the player back on the same tile. */
    private fun ProtectedAccess.rebuild(old: House, at: CoordGrid = player.coords) {
        val dx = at.x - old.region.southWest.x
        val dz = at.z - old.region.southWest.z
        val house = buildHouse(player, old.buildMode) ?: return
        telejump(house.region.southWest.translate(dx, dz))
    }

    /** The room whose zone holds [coords] in [house]. */
    private fun House.roomAt(coords: CoordGrid): Placed? {
        val x = (coords.x - region.southWest.x) / 8
        val z = (coords.z - region.southWest.z) / 8
        return rooms.firstOrNull { it.x == x && it.z == z }
    }

    /* Rooms */

    private suspend fun ProtectedAccess.doorway(loc: BoundLocInfo) {
        val house = houses[player.uid.packed]
        if (house == null || !house.buildMode) {
            mes("You can only build rooms in your own house in building mode.")
            return
        }
        val placed = house.roomAt(loc.coords) ?: return
        val sw = house.region.zoneSw(placed)
        val x = loc.coords.x - sw.x
        val z = loc.coords.z - sw.z
        val edge =
            when {
                z == 7 -> EDGE_NORTH
                x == 7 -> EDGE_EAST
                z == 0 -> EDGE_SOUTH
                x == 0 -> EDGE_WEST
                else -> return
            }
        val (nx, nz) = neighbour(placed, edge)
        val existing = house.rooms.firstOrNull { it.x == nx && it.z == nz }
        if (existing != null) {
            removeRoom(house, existing)
        } else {
            addRoom(house, nx, nz, edge)
        }
    }

    private suspend fun ProtectedAccess.addRoom(house: House, x: Int, z: Int, fromEdge: Int) {
        val level = player.constructionLvl
        if (house.rooms.size >= ConstructionData.maxRooms(level)) {
            mes("You can't build any more rooms at your Construction level.")
            return
        }
        if (!fitsYard(house.rooms, x, z, ConstructionData.yardSize(level))) {
            mes("Your house can't be any bigger at your Construction level.")
            return
        }
        if (x !in 1 until PohTemplates.REGION_ZONES - 1 || z !in 1 until PohTemplates.REGION_ZONES - 1) {
            mes("You can't build any further in that direction.")
            return
        }
        val choices = ConstructionData.rooms
        val labels =
            choices.map { room ->
                val colour = if (level >= room.level) "" else "<col=ff0000>"
                "$colour${room.name}: level ${room.level}, ${"%,d".format(room.coins)} coins"
            }
        val picked = menu("Choose a room", hotkeys = false, choices = labels + "Cancel")
        val room = choices.getOrNull(picked) ?: return
        if (level < room.level) {
            mes("You need a Construction level of ${room.level} to build a ${room.name.lowercase()}.")
            return
        }
        if (invTotal(inv, ConstructionObjs.coins) < room.coins) {
            mes("You need ${"%,d".format(room.coins)} coins to build a ${room.name.lowercase()}.")
            return
        }
        // The new room must have a door facing back through the doorway it was built from.
        val back = (fromEdge + 2) and 3
        val rotations = (0..3).filter { back in room.doors(it) }
        if (rotations.isEmpty()) {
            mes("A ${room.name.lowercase()} has no door that could face that doorway.")
            return
        }
        val rotation =
            if (rotations.size == 1 || room.doors.size == 4) {
                rotations.first()
            } else {
                val facing =
                    rotations.map { r ->
                        "Doors: " + room.doors(r).sorted().joinToString(", ") { EDGE_NAMES[it] }
                    }
                rotations.getOrNull(menu("Which way should it face?", hotkeys = false, choices = facing))
                    ?: return
            }
        val slot =
            (0 until ConstructionData.MAX_SLOTS).firstOrNull { s -> house.rooms.none { it.slot == s } }
                ?: return
        invDel(inv, ConstructionObjs.coins, room.coins)
        setSlot(player, slot, x, z, room, rotation)
        VarPlayerIntMapSetter.set(player, ConstructionVarps.furniture[slot], 0)
        mes("You build a ${room.name.lowercase()}.")
        rebuild(house)
    }

    /** The Wiki's yard limit: every room inside a [size] x [size] square. */
    private fun fitsYard(rooms: List<Placed>, x: Int, z: Int, size: Int): Boolean {
        val xs = rooms.map { it.x } + x
        val zs = rooms.map { it.z } + z
        return xs.max() - xs.min() < size && zs.max() - zs.min() < size
    }

    private suspend fun ProtectedAccess.removeRoom(house: House, placed: Placed) {
        if (placed.slot == house.rooms.first().slot) {
            mes("You can't remove the garden with your house's exit portal.")
            return
        }
        var confirmed = false
        startDialogue {
            confirmed =
                choice2(
                    "Yes, remove it.",
                    true,
                    "No, keep it.",
                    false,
                    title = "Remove the ${placed.room.name.lowercase()} and everything in it?",
                )
        }
        if (!confirmed) return
        setSlot(player, placed.slot, 0, 0, null, 0)
        VarPlayerIntMapSetter.set(player, ConstructionVarps.furniture[placed.slot], 0)
        mes("You remove the ${placed.room.name.lowercase()}.")
        // Standing in the removed room: come back in the first garden instead of the void.
        val at =
            if (house.roomAt(player.coords)?.slot == placed.slot) {
                house.region.zoneSw(house.rooms.first()).translate(2, 2)
            } else {
                player.coords
            }
        rebuild(house, at)
    }

    /* Furniture */

    private fun option(room: String, group: Int, tier: Int): Option? =
        if (tier <= 0) null else ConstructionData.optionsByGroup[room to group]?.firstOrNull { it.tier == tier }

    /** The loc built on a hotspot tile; portal frames show their directed destination. */
    private fun builtLoc(
        player: Player,
        slot: Int,
        room: String,
        group: Int,
        tier: Int,
        suffix: String,
    ): LocType? {
        val built = option(room, group, tier)?.locs?.get(suffix) ?: return null
        val portal = group - FIRST_PORTAL_GROUP
        if (room == "portal" && portal in 0 until PORTALS && tier in 1..PohFurnitureData.frames.size) {
            val dest = destination(player, slot, portal)
            if (dest > 0) {
                val name =
                    PohFurnitureData.portalLoc(tier, PohFurnitureData.destinationOrder[dest - 1])
                return PohFurnitureLocs.byName.getValue(name)
            }
        }
        return loc(built)
    }

    /* Portal room: destinations saved above the furniture bits of the portal room's slot. */

    private fun destination(player: Player, slot: Int, portal: Int): Int =
        (player.vars[ConstructionVarps.furniture[slot]] ushr (PORTAL_SHIFT + portal * 3)) and 7

    private fun setDestination(player: Player, slot: Int, portal: Int, dest: Int) {
        val varp = ConstructionVarps.furniture[slot]
        val shift = PORTAL_SHIFT + portal * 3
        val updated = (player.vars[varp] and (7 shl shift).inv()) or (dest shl shift)
        VarPlayerIntMapSetter.set(player, varp, updated)
    }

    private fun title(key: String): String = key.replaceFirstChar { it.uppercase() }

    /**
     * Wiki "Portal chamber": the owner directs a portal from the focus (any mode) for 100x the
     * teleport spell's runes and the spell's Magic level, gaining 5x the spell's xp. Elemental
     * staves don't count; quest requirements are not checked here.
     */
    private suspend fun ProtectedAccess.directPortal(loc: BoundLocInfo) {
        val house = houses[player.uid.packed] ?: return
        val placed = house.roomAt(loc.coords)?.takeIf { it.room.key == "portal" } ?: return
        val order = PohFurnitureData.destinationOrder
        val portalLabels =
            (0 until PORTALS).map { i ->
                val dest = destination(player, placed.slot, i)
                "Portal ${i + 1}: " + (order.getOrNull(dest - 1)?.let(::title) ?: "not directed")
            }
        val portal = menu("Direct which portal?", hotkeys = true, choices = portalLabels + "Cancel")
        if (portal !in 0 until PORTALS) return
        val labels =
            order.map { key ->
                val dest = PohFurnitureData.destinations.getValue(key)
                val colour = if (player.magicLvl >= dest.level) "" else "<col=ff0000>"
                "$colour${title(key)} (Magic ${dest.level})"
            }
        val picked = menu("Direct it to where?", hotkeys = true, choices = labels + "Cancel")
        val key = order.getOrNull(picked) ?: return
        val dest = PohFurnitureData.destinations.getValue(key)
        if (player.magicLvl < dest.level) {
            mes("You need a Magic level of ${dest.level} to direct a portal there.")
            return
        }
        val runes = dest.runes.map { (name, count) -> PohFurnitureObjs.byName.getValue(name) to count }
        if (runes.any { (type, count) -> invTotal(inv, type) < count }) {
            val needed = dest.runes.joinToString(", ") { (n, c) -> "$c ${n.removeSuffix("rune")}" }
            mes("You need these runes to direct a portal there: $needed.")
            return
        }
        for ((type, count) in runes) invDel(inv, type, count)
        setDestination(player, placed.slot, portal, order.indexOf(key) + 1)
        statAdvance(stats.magic, dest.xp * xpMods.get(player, stats.magic))
        val group = FIRST_PORTAL_GROUP + portal
        val tier = tier(player, placed.slot, "portal", group)
        option("portal", group, tier)?.let { placeGroup(house, placed.slot, group, it) }
        mes("You direct the portal to ${title(key)}.")
    }

    private suspend fun ProtectedAccess.buildFurniture(loc: BoundLocInfo) {
        val house = houses[player.uid.packed]
        if (house == null || !house.buildMode) {
            mes("You can only build furniture in your own house in building mode.")
            return
        }
        val tile = house.tiles[loc.coords to loc.layer] ?: return
        val placed = house.rooms.firstOrNull { it.slot == tile.slot } ?: return
        val room = placed.room.key
        if (tier(player, tile.slot, room, tile.group) > 0) return
        val options = ConstructionData.optionsByGroup[room to tile.group] ?: return
        val furniture = chooseFurniture(options) ?: return
        if (!canBuild(furniture)) return
        consumeMaterials(furniture)
        anim(ConstructionSeqs.build)
        delay(2)
        setTier(player, tile.slot, room, tile.group, furniture.tier)
        placeGroup(house, tile.slot, tile.group, furniture)
        statAdvance(stats.construction, furniture.xp * xpMods.get(player, stats.construction))
        mes("You build a ${furniture.name.lowercase()}.")
    }

    /** Puts [furniture] (or the hotspots back, when null) on every tile of the group. */
    private fun ProtectedAccess.placeGroup(house: House, slot: Int, group: Int, furniture: Option?) {
        val room = house.rooms.firstOrNull { it.slot == slot }?.room?.key ?: return
        for ((key, tile) in house.tiles) {
            if (tile.slot != slot || tile.group != group) continue
            val (coords, layer) = key
            val current = locRepo.findAll(coords).firstOrNull { it.layer == layer } ?: continue
            val built =
                furniture?.let { builtLoc(player, slot, room, group, it.tier, tile.suffix) }
                    ?: tile.hotspot
            if (current.id != locTypes[built].id) locRepo.change(current, built, PERMANENT)
        }
    }

    /** The OSRS furniture build menu: model, name, level and materials per option. */
    private suspend fun ProtectedAccess.chooseFurniture(options: List<Option>): Option? {
        val shown = options.take(MENU_ENTRIES)
        val entries =
            shown.map { furniture ->
                FurnitureMenuEntry(
                    obj = ConstructionObjs.byName.getValue(furniture.icon),
                    level = furniture.level,
                    materials = materialLines(furniture),
                    available = player.constructionLvl >= furniture.level && hasMaterials(furniture),
                )
            }
        val index = furnitureMenu(entries) ?: return null
        return shown.getOrNull(index)
    }

    /** "Plank: 2" style lines, green when carried and red when not (as the OSRS menu shows). */
    private fun ProtectedAccess.materialLines(furniture: Option): List<String> {
        val lines = ArrayList<String>()
        for ((name, count) in furniture.materials) {
            val type = ConstructionObjs.byName.getValue(name)
            lines += colour("${objTypes[type].name}: $count", invTotal(inv, type) >= count)
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

    private fun ProtectedAccess.hasMaterials(furniture: Option): Boolean =
        furniture.materials.all { (name, count) ->
            invTotal(inv, ConstructionObjs.byName.getValue(name)) >= count
        } && nailCount() >= furniture.nails

    private fun ProtectedAccess.canBuild(furniture: Option): Boolean {
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
        if (!furniture.materials.all { (name, count) -> invTotal(inv, ConstructionObjs.byName.getValue(name)) >= count }) {
            mes("You don't have the materials to build that.")
            return false
        }
        if (nailCount() < furniture.nails) {
            mes("You need ${furniture.nails} nails to build that.")
            return false
        }
        return true
    }

    private fun ProtectedAccess.consumeMaterials(furniture: Option) {
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

    private suspend fun ProtectedAccess.removeFurniture(loc: BoundLocInfo) {
        val house = houses[player.uid.packed]
        if (house == null || !house.buildMode) {
            mes("You can only remove furniture in your own house in building mode.")
            return
        }
        val tile = house.tiles[loc.coords to loc.layer] ?: return
        val placed = house.rooms.firstOrNull { it.slot == tile.slot } ?: return
        val tier = tier(player, tile.slot, placed.room.key, tile.group)
        val built = option(placed.room.key, tile.group, tier) ?: return
        var confirmed = false
        startDialogue {
            confirmed =
                choice2("Yes, remove it.", true, "No, keep it.", false, title = "Really remove it?")
        }
        if (!confirmed) return
        anim(ConstructionSeqs.build)
        setTier(player, tile.slot, placed.room.key, tile.group, 0)
        placeGroup(house, tile.slot, tile.group, null)
        mes("You remove the ${built.name.lowercase()}.")
    }

    private companion object {
        const val PERMANENT = Int.MAX_VALUE
        const val MENU_ENTRIES = 7
        /** Portal room hotspot groups 3-5 are its three portal frames. */
        const val FIRST_PORTAL_GROUP = 3
        const val PORTALS = 3
        /** Destinations (3 bits per portal) sit above the room's furniture bits (< 16). */
        const val PORTAL_SHIFT = 16
        val EDGE_NAMES = listOf("north", "east", "south", "west")
    }
}
