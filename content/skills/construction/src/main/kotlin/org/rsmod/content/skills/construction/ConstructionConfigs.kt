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

    /** Hotspot and furniture locs used by the parlour and garden data. */
    val byName: Map<String, LocType> =
        (ConstructionData.parlour.flatMap { group ->
                group.spots.map { it.hotspot } + group.options.flatMap { it.locs.values }
            } + ConstructionData.gardenSpots.map { it.hotspot })
            .distinct()
            .associateWith { find(it) }
}

internal object ConstructionObjs : ObjReferences() {
    val hammer = find("hammer")
    val saw = find("poh_saw")

    val byName: Map<String, ObjType> =
        (ConstructionData.materials + ConstructionData.nails).associateWith { find(it) }
}

internal object ConstructionSeqs : SeqReferences() {
    val build = find("human_poh_build")
}

/** Saved per character (world symbol range 65700-65899). */
internal object ConstructionVarpBuilds : VarpBuilder() {
    init {
        build("poh_parlour_furniture")
    }
}

internal object ConstructionVarps : VarpReferences() {
    /** [ConstructionData.TIER_BITS] bits per parlour group, in [ConstructionData.parlour] order. */
    val parlour = find("poh_parlour_furniture")
}

/**
 * POH room templates in the cache map (one 8x8 zone per room; zone level = house style, level 0
 * = the basic wooden Rimmington style). Positions were found by scanning the map for poh_* locs.
 */
internal object PohTemplates {
    val garden = ZoneKey(232, 713, 0)
    val parlour = ZoneKey(232, 719, 0)

    /** Local tile of the garden centrepiece space, where new houses have the exit portal. */
    const val CENTREPIECE_X = 3
    const val CENTREPIECE_Z = 3

    /** Where players arrive in the garden (next to the exit portal). */
    const val ARRIVE_X = 2
    const val ARRIVE_Z = 2

    /** Outside the Rimmington house portal. */
    val rimmington: CoordGrid = CoordGrid(2954, 3224, 0)
}
