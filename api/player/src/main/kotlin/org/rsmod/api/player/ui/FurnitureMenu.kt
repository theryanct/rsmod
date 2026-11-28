package org.rsmod.api.player.ui

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.game.type.obj.ObjType

/**
 * One option of the POH furniture build menu (interface `poh_furniture_creation`).
 *
 * @param materials material lines, already colour-tagged by the caller (one per line).
 * @param available `false` greys the entry out (the client draws its "can't build" overlay).
 */
public data class FurnitureMenuEntry(
    val obj: ObjType,
    val level: Int,
    val materials: List<String>,
    val available: Boolean,
)

/**
 * Client script `poh_furniture_creation_entry` (1404), from the rev 233 cache: args
 * (slot, obj, level, unavailable 0/1, materials text). It builds the slot's model, name, level and
 * materials and gives it "Build"/"Examine" ops; "Build" runs `poh_furniture_creation_op` (1405),
 * which resumes a pause button on `poh_furniture_creation:contents` with the slot as sub.
 */
internal const val FURNITURE_ENTRY_SCRIPT = 1404

/** Max entries the menu takes (the interface names 7 rows). */
public const val FURNITURE_MENU_MAX_ENTRIES: Int = 7

internal object FurnitureMenuRefs : InterfaceReferences() {
    val menu = find("poh_furniture_creation")
}

internal object FurnitureMenuComponents : ComponentReferences() {
    val contents = find("poh_furniture_creation:contents")
}
