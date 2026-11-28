package org.rsmod.content.skills.farming

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Spirit tree travel (OSRS Wiki "Spirit tree"): Travel or Talk-to on any spirit tree lists the
 * network - Tree Gnome Village, Gnome Stronghold, Battlefield of Khazard, Grand Exchange, Feldip
 * Hills, plus the player's own farmed spirit trees once grown and health-checked - and teleports
 * beside the chosen tree. Talk-to works too because the client only shows "Travel" after its
 * quest varbit; with the QuestGates toggle on, Tree Gnome Village must be complete (the client's
 * `treequest` >= 9, its Travel threshold). Prifddinas is left out (no Song of the Elves here).
 */
class SpiritTrees @Inject constructor(private val collision: CollisionFlagMap) : PluginScript() {
    override fun ScriptContext.startup() {
        for (tree in SpiritTreeLocs.all) {
            if (!isLocOpBound(tree, 1)) onOpLoc1(tree) { travelFrom(it.loc) }
            if (!isLocOpBound(tree, 2)) onOpLoc2(tree) { travelFrom(it.loc) }
        }
    }

    private suspend fun ProtectedAccess.travelFrom(loc: BoundLocInfo) {
        if (player.isFeatureOn(Feature.QuestGates) && vars[SpiritTreeVarps.treequest] < TREE_GNOME_VILLAGE_DONE) {
            mes("The tree doesn't feel like talking. Perhaps after Tree Gnome Village...")
            return
        }
        val here = loc.coords
        val options =
            (SpiritTreeData.network + grownFarmTrees(player))
                .filter { it.coords.chebyshevDistance(here) > NEAR || it.coords.level != here.level }
        val picked = menu("Where would you like to go?", hotkeys = true, choices = options.map { it.name } + "Cancel")
        val dest = options.getOrNull(picked) ?: return
        mes("You place your hands on the dry tough bark of the spirit tree, and feel a surge of energy run through your veins.")
        telejump(arrival(dest.coords))
    }

    /** The player's farmed spirit trees that are grown and health-checked. */
    private fun grownFarmTrees(player: Player): List<SpiritTreeData.Destination> =
        SpiritTreeData.farmed.filter { (patchKey, _) ->
            val patch = FarmingData.patches.firstOrNull { it.key == patchKey } ?: return@filter false
            val state = player.vars[FarmingVarps.byName.getValue(patch.stateVarp)]
            val crop = FarmingData.crops.getOrNull(((state shr 2) and 63) - 1)
            crop?.kind == FarmingData.Kind.SpiritTree && (state shr 10) and 1 == 1
        }.map { it.second }

    private fun arrival(pin: CoordGrid): CoordGrid {
        for (r in 1..ARRIVAL_RADIUS) {
            for (dz in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val tile = pin.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) == 0) return tile
                }
            }
        }
        return pin
    }

    private companion object {
        const val TREE_GNOME_VILLAGE_DONE = 9
        const val NEAR = 6
        const val ARRIVAL_RADIUS = 4
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
    }
}

internal object SpiritTreeData {
    class Destination(val name: String, val coords: CoordGrid)

    /** Static trees (tiles from the cache map scan). */
    val network: List<Destination> =
        listOf(
            Destination("Tree Gnome Village", CoordGrid(2543, 3168, 0)),
            Destination("Gnome Stronghold", CoordGrid(2460, 3445, 0)),
            Destination("Battlefield of Khazard", CoordGrid(2555, 3260, 0)),
            Destination("Grand Exchange", CoordGrid(3184, 3509, 0)),
            Destination("Feldip Hills", CoordGrid(2486, 2850, 0)),
        )

    /** Farming patch key -> destination (patch tiles from farming_patch_scan.py). */
    val farmed: List<Pair<String, Destination>> =
        listOf(
            "spirit_port_sarim" to Destination("Port Sarim", CoordGrid(3059, 3257, 0)),
            "spirit_etceteria" to Destination("Etceteria", CoordGrid(2612, 3857, 0)),
            "spirit_brimhaven" to Destination("Brimhaven", CoordGrid(2801, 3202, 0)),
        )
}

internal object SpiritTreeLocs : LocReferences() {
    val all =
        listOf(
            find("spirittree_big_1op"),
            find("spirittree_big_2ops"),
            find("spirittree_big_2ops_orbs"),
            find("spirittree_small_1op"),
            find("spirittree_small_2ops"),
            // A farmed spirit tree, grown and health-checked (ops 1-2: Talk-to, Travel).
            find("spirit_tree_fullygrown"),
        )
}

internal object SpiritTreeVarps : VarpReferences() {
    /** Client varp: Tree Gnome Village progress. */
    val treequest = find("treequest")
}
