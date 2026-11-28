package org.rsmod.content.skills.construction

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Working POH furniture (Construction PR2 part B, OSRS Wiki):
 * - Larders, kitchen shelves and tool stores: "Search" hands out one of the tier's unlimited items
 *   (Wiki "Larder space", "Shelf space", "Tool space"). Kitchen shelves give the tier's tea cup.
 * - Portal room portals: "Enter" teleports to the portal's city (the magic module's landing
 *   tiles). Directing portals at the teleport focus lives in [Construction] (it saves state).
 * - Chapel lamps: "Light" with a tinderbox (incense burners also take a clean marrentill each);
 *   they stay lit. Bone offering on POH altars is Prayer's AltarOffering (gilded-altar rate on
 *   every altar, Ryan's local tweak), so lit burners are cosmetic here.
 */
class PohFurniture
@Inject
constructor(private val objTypes: ObjTypeList, private val locRepo: LocRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        for ((loc, items) in PohFurnitureData.larders) bindOp1(loc) { search("larder", items) }
        for ((loc, items) in PohFurnitureData.shelves) bindOp1(loc) { search("shelves", items) }
        for ((loc, items) in PohFurnitureData.toolStores) bindOp1(loc) { search("tool store", items) }
        for ((loc, dest) in PohFurnitureData.portals) bindOp1(loc) { enterPortal(dest) }
        for ((unlit, lit) in PohFurnitureData.lamps) bindOp1(unlit) { light(it, unlit, lit) }
    }

    private fun ScriptContext.bindOp1(name: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val type = PohFurnitureLocs.byName.getValue(name)
        if (isLocOpBound(type, 1)) return
        onOpLoc1(type) { action(it.loc) }
    }

    private suspend fun ProtectedAccess.search(what: String, items: List<String>) {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        val types = items.map { PohFurnitureObjs.byName.getValue(it) }
        val labels = types.map { objTypes[it].name }
        val picked = menu("Take what from the $what?", hotkeys = true, choices = labels + "Nothing")
        val type: ObjType = types.getOrNull(picked) ?: return
        invAdd(inv, type)
    }

    private fun ProtectedAccess.enterPortal(dest: String) {
        val coords = PohFurnitureData.destinations.getValue(dest).coords
        telejump(coords)
    }

    private fun ProtectedAccess.light(loc: BoundLocInfo, unlit: String, lit: String) {
        if (!player.hasTool(PohFurnitureObjs.tinderbox)) {
            mes("You need a tinderbox to light that.")
            return
        }
        val burner = unlit.substringAfterLast('_').toInt() >= FIRST_BURNER
        if (burner && PohFurnitureObjs.marrentill !in inv) {
            mes("You need a clean marrentill to light the incense burner.")
            return
        }
        if (burner) invDel(inv, PohFurnitureObjs.marrentill, 1)
        val current = locRepo.findExact(loc.coords, PohFurnitureLocs.byName.getValue(unlit)) ?: return
        locRepo.change(current, PohFurnitureLocs.byName.getValue(lit), Int.MAX_VALUE)
    }

    private companion object {
        /** poh_torch_5..7 are incense burners (1-4 are torches and candlesticks). */
        const val FIRST_BURNER = 5
    }
}

/** Item lists per furniture loc, from the OSRS Wiki tables. */
internal object PohFurnitureData {
    class Destination(val coords: CoordGrid, val level: Int, val xp: Double, val runes: List<Pair<String, Int>>)

    private val larderItems =
        listOf(
            listOf("poh_tea_leaves", "bucket_milk"),
            listOf("poh_tea_leaves", "bucket_milk", "egg", "pot_flour"),
            listOf(
                "poh_tea_leaves",
                "bucket_milk",
                "egg",
                "pot_flour",
                "potato",
                "garlic",
                "onion",
                "cheese",
            ),
        )

    val larders: Map<String, List<String>> =
        larderItems.withIndex().associate { (i, items) -> "poh_larder_${i + 1}" to items }

    /** Wiki "Shelf space": cumulative cookware per tier, plus the tier's cup and teapot. */
    private val shelfExtras =
        listOf("beer_glass", "cake_tin", "bowl_empty", "piedish", "pot_empty", "chefs_hat")
    private val shelfCups =
        listOf("clay", "clay", "china", "clay", "china", "china", "giltchina")
    private val teapots =
        mapOf(
            "clay" to "poh_teapot_clay_empty",
            "china" to "poh_teapot_porcelain_empty",
            "giltchina" to "poh_teapot_giltporcelain_empty",
        )

    val shelves: Map<String, List<String>> =
        (1..7).associate { tier ->
            val cup = shelfCups[tier - 1]
            val extras = shelfExtras.take(tier - 1)
            val items =
                listOf(teapots.getValue(cup), "poh_kettle_empty", "poh_${cup}cup_empty") + extras
            "poh_kitchen_shelves_$tier" to items
        }

    /** Wiki "Tool space": tool store N holds its own tools (each store is a separate loc). */
    private val toolItems =
        listOf(
            listOf("poh_saw", "hammer", "chisel", "shears"),
            listOf("bucket_empty", "knife", "spade", "tinderbox"),
            listOf("brown_apron", "glassblowingpipe", "needle"),
            listOf(
                "amulet_mould",
                "necklace_mould",
                "ring_mould",
                "holy_symbol_mould",
                "jewl_bracelet_mould",
                "tiara_mould",
            ),
            listOf("rake", "spade", "trowel", "dibber", "watering_can_0", "secateurs"),
        )

    val toolStores: Map<String, List<String>> =
        toolItems.withIndex().associate { (i, items) -> "poh_tools${i + 1}" to items }

    /**
     * Portal room destinations (Wiki "Portal chamber/Portal costs": Magic level, 100x the
     * spell's runes, 5x its xp); landing tiles from the magic module's city teleports.
     */
    val destinations: Map<String, Destination> =
        mapOf(
            "varrock" to
                Destination(CoordGrid(3213, 3424), 25, 175.0, listOf("lawrune" to 100, "firerune" to 100, "airrune" to 300)),
            "lumbridge" to
                Destination(CoordGrid(3222, 3218), 31, 205.0, listOf("lawrune" to 100, "earthrune" to 100, "airrune" to 300)),
            "falador" to
                Destination(CoordGrid(2964, 3378), 37, 240.0, listOf("lawrune" to 100, "waterrune" to 100, "airrune" to 300)),
            "camelot" to
                Destination(CoordGrid(2757, 3479), 45, 277.5, listOf("lawrune" to 100, "airrune" to 500)),
            "ardougne" to
                Destination(CoordGrid(2662, 3305), 51, 305.0, listOf("lawrune" to 200, "waterrune" to 200)),
            "yanille" to
                Destination(CoordGrid(2546, 3114, 2), 58, 340.0, listOf("lawrune" to 200, "earthrune" to 200)),
        )

    /** Saved destination ids (1-based, never reorder). */
    val destinationOrder: List<String> =
        listOf("varrock", "lumbridge", "falador", "camelot", "ardougne", "yanille")

    /** Portal frame tier (1 teak, 2 mahogany, 3 marble) -> loc family. */
    val frames: List<String> = listOf("teak", "mag", "marble")

    fun portalLoc(tier: Int, dest: String): String = "poh_portal_${frames[tier - 1]}_$dest"

    val portals: Map<String, String> =
        frames.flatMap { frame -> destinationOrder.map { "poh_portal_${frame}_$it" to it } }.toMap()

    /** Chapel lamps: unlit -> lit (1-4 torches/candlesticks, 5-7 incense burners). */
    val lamps: Map<String, String> = (1..7).associate { "poh_torch_$it" to "poh_torch_${it}_lit" }
}

internal object PohFurnitureLocs : LocReferences() {
    val byName: Map<String, LocType> =
        (PohFurnitureData.larders.keys +
                PohFurnitureData.shelves.keys +
                PohFurnitureData.toolStores.keys +
                PohFurnitureData.portals.keys +
                PohFurnitureData.lamps.keys +
                PohFurnitureData.lamps.values)
            .distinct()
            .associateWith { find(it) }

    /** Portal room centrepieces (op "Direct-portal"). */
    val focus: Map<String, LocType> =
        listOf("poh_teleport_centrepiece", "poh_teleport_centrepiece_grand", "poh_scrying_pool")
            .associateWith { find(it) }
}

internal object PohFurnitureObjs : ObjReferences() {
    val tinderbox = find("tinderbox")
    val marrentill = find("marentill")

    val byName: Map<String, ObjType> =
        (PohFurnitureData.larders.values.flatten() +
                PohFurnitureData.shelves.values.flatten() +
                PohFurnitureData.toolStores.values.flatten() +
                PohFurnitureData.destinations.values.flatMap { d -> d.runes.map { it.first } })
            .distinct()
            .associateWith { find(it) }
}
