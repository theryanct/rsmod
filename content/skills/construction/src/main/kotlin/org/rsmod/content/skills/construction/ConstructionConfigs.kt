package org.rsmod.content.skills.construction

import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

internal object ConstructionLocs : LocReferences() {
    val rimmington_portal = find("poh_rimmington_portal")
    val exit_portal = find("poh_exit_portal")
    val garden_centrepiece = find("poh_crude_garden_1")
    val door_left = find("poh_hotspot_doorl_rimmington")
    val door_right = find("poh_hotspot_doorr_rimmington")

    /** Hotspot and built furniture locs named by the room data. */
    val byName: Map<String, LocType> =
        (ConstructionData.spots.map { it.hotspot } +
                ConstructionData.options.flatMap { it.locs.values } +
                ConstructionData.gardenHotspots)
            .distinct()
            .associateWith { find(it) }
}

internal object ConstructionObjs : ObjReferences() {
    val hammer = find("hammer")
    val saw = find("poh_saw")
    val coins = find("coins")

    val byName: Map<String, ObjType> =
        (ConstructionData.options.flatMap { o -> o.materials.map { it.first } + o.icon } +
                ConstructionData.nails)
            .distinct()
            .associateWith { find(it) }
}

internal object ConstructionSeqs : SeqReferences() {
    val build = find("human_poh_build")
}

/** Saved per character. PR1's parlour varp is world's (65770); PR2's are skilling's 65609+. */
internal object ConstructionVarpBuilds : VarpBuilder() {
    init {
        build("poh_parlour_furniture")
        for (i in 1..LAYOUT_VARPS) build("poh_layout_$i")
        for (i in 1..ConstructionData.MAX_SLOTS) build("poh_room_furniture_$i")
    }
}

/** Two room slots per layout varp ([ROOM_SLOT_BITS] bits each). */
internal const val LAYOUT_VARPS = (ConstructionData.MAX_SLOTS + 1) / 2

/** Slot bits: x (4), z (4), room type id (5), rotation (2). */
internal const val ROOM_SLOT_BITS = 15

internal object ConstructionVarps : VarpReferences() {
    /** PR1 parlour furniture (3 bits per PR1 group); migrated into slot 1 on first entry. */
    val parlour = find("poh_parlour_furniture")
    val layout = (1..LAYOUT_VARPS).map { find("poh_layout_$it") }
    val furniture = (1..ConstructionData.MAX_SLOTS).map { find("poh_room_furniture_$it") }
}

/**
 * Region placement of a house: room slot (x, z) is region zone (x, z); a new house has its garden
 * at ([START_X], [START_Z]) with the parlour east of it, leaving room to grow on every side.
 */
internal object PohTemplates {
    /**
     * The template's void zone: every tile uses overlay 41, whose colour is the client's magenta
     * "draw nothing" colour, so copying it around the rooms gives OSRS's black surround instead of
     * the white of unmapped zones.
     */
    val void = ZoneKey(236, 713, 0)

    /** Zones per side of a small region (128 tiles). */
    const val REGION_ZONES = 16

    const val START_X = 4
    const val START_Z = 4

    /** Outside the Rimmington house portal. */
    val rimmington: CoordGrid = CoordGrid(2954, 3224, 0)
}
