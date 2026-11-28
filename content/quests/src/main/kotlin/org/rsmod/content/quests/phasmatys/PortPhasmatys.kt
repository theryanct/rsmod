package org.rsmod.content.quests.phasmatys

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Port Phasmatys (OSRS Wiki): the town's energy barriers charge 2 ecto-tokens to enter (leaving is
 * free), and the Ectofuntus temple's stairs, cellar trapdoor and the cavern stairs down to the
 * Pool of Slime. The Ectofuntus itself is in the Prayer module.
 *
 * After Ghosts Ahoy the barrier multiloc shows its toll-free child. Arrival tiles are the
 * counterpart stair/ladder's own tile; players walk off it. Not done: the 58 Agility
 * weathered-wall shortcut.
 */
class PortPhasmatys
@Inject
constructor(private val locTypes: LocTypeList, private val locRepo: LocRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onLocOpLabel(locTypes, PhasLocs.barrier, "Pass") { barrier(it) }
        onLocOpLabel(locTypes, PhasLocs.barrier, "Pay-toll(2-Ecto)") { barrier(it) }
        onLocOpLabel(locTypes, PhasLocs.barrier_free, "Pass") {
            if (!wearingBedsheet()) passThroughWall(it)
        }

        onLocOpLabel(locTypes, PhasLocs.trapdoor, "Open") {
            locRepo.change(it, PhasLocs.trapdoor_open, TRAPDOOR_TICKS)
        }
        onLocOpLabel(locTypes, PhasLocs.trapdoor_open, "Close") {
            locRepo.change(it, PhasLocs.trapdoor, TRAPDOOR_TICKS)
        }
        onLocOpLabel(locTypes, PhasLocs.trapdoor_open, "Climb-down") { climb(CELLAR) }
        onLocOpLabel(locTypes, PhasLocs.cellar_ladder, "Climb-up") { climb(TRAPDOOR) }

        onLocOpLabel(locTypes, PhasLocs.tower_stairs, "Climb-up") { climb(TOWER_TOP) }
        onLocOpLabel(locTypes, PhasLocs.tower_stairs_top, "Climb-down") { climb(TOWER_FOOT) }

        onLocOpLabel(locTypes, PhasLocs.cavern_stairs, "Climb-up") {
            climb(it.coords.copy(level = it.coords.level + 1))
        }
        onLocOpLabel(locTypes, PhasLocs.cavern_stairs_top, "Climb-down") {
            climb(it.coords.copy(level = it.coords.level - 1))
        }
    }

    private suspend fun ProtectedAccess.climb(dest: CoordGrid) {
        delay(1)
        telejump(dest)
    }

    /**
     * Wall angle 2 (west gate, east edge of x 3652) has the town to the east; angle 3 (north gate,
     * south edge of z 3508) has it to the south. Only the way in is tolled.
     */
    private suspend fun ProtectedAccess.barrier(loc: BoundLocInfo) {
        if (wearingBedsheet()) return
        val at = loc.coords
        val entering =
            when (loc.angleId and 3) {
                2 -> player.coords.x <= at.x
                3 -> player.coords.z >= at.z
                0 -> player.coords.x < at.x
                else -> player.coords.z <= at.z
            }
        if (entering) {
            if (invTotal(inv, PhasObjs.ecto_token) < TOLL) {
                mes("You need $TOLL ecto-tokens to pass through the barrier.")
                return
            }
            invDel(inv, PhasObjs.ecto_token, TOLL)
            mes("You pay the ghost guard $TOLL ecto-tokens and pass through the barrier.")
        }
        passThroughWall(loc)
    }

    /** Ghosts Ahoy's disguise can't leave town. */
    private fun ProtectedAccess.wearingBedsheet(): Boolean {
        val sheet = PhasObjs.bedsheets.any { invTotal(worn, it) > 0 }
        if (sheet) mes("You can't pass the barriers of Port Phasmatys while wearing the bedsheet.")
        return sheet
    }

    private companion object {
        const val TOLL = 2
        const val TRAPDOOR_TICKS = 100
        val CELLAR = CoordGrid(3669, 9888, 3)
        val TRAPDOOR = CoordGrid(3654, 3519, 0)
        val TOWER_TOP = CoordGrid(3666, 3520, 1)
        val TOWER_FOOT = CoordGrid(3666, 3518, 0)
    }
}

internal object PhasLocs : LocReferences() {
    /** Children of ahoy_town_barrier_multi (ahoy_questvar): tolled until Ghosts Ahoy (8). */
    val barrier = find("ahoy_town_barrier")
    val barrier_free = find("ahoy_town_barrier_post_quest")
    val trapdoor = find("ahoy_trapdoor")
    val trapdoor_open = find("ahoy_trapdoor_open")
    val cellar_ladder = find("ahoy_ladder_from_cellar")
    val tower_stairs = find("ahoy_tower_stairs_lv1")
    val tower_stairs_top = find("ahoy_tower_stairs_lv1_top")
    val cavern_stairs = find("ahoy_cavern_stairs")
    val cavern_stairs_top = find("ahoy_cavern_stairs_top")
}

internal object PhasObjs : ObjReferences() {
    val ecto_token = find("ectotoken")
    val bedsheets = listOf(find("ahoy_bedsheet"), find("ahoy_bedsheetgreen"))
}
