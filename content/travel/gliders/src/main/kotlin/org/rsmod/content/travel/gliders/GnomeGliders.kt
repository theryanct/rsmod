package org.rsmod.content.travel.gliders

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Gnome gliders (OSRS Wiki "Gnome glider"): a captain's "Glider" opens the glider map
 * (`glidermap`), and a destination flies you there with the shared travel fade.
 * - Needs The Grand Tree complete (cache varp `grandtree` = 160).
 * - Ta Quir Priw (Grand Tree top), Sindarpos (White Wolf Mountain), Kar-Hewo (Al Kharid), Gandius
 *   (Karamja) and Lemanto Andra (west of the Digsite; one way, the glider crash-lands).
 *   Lemantolly Undri needs One Small Favour and Ookookolly Undri Monkey Madness II; neither is
 *   built yet, so both stay closed.
 * - Every captain is a multinpc on `pilot_previous_destination`; the server sets it to the
 *   destination on arrival (the cache's 0-6 order below), so each one shows the right variant.
 */
class GnomeGliders
@Inject
constructor(private val npcTypes: NpcTypeList, private val collision: CollisionFlagMap) :
    PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var bound = 0
        for (type in npcTypes.values) {
            val name = type.internalName.orEmpty()
            if (!name.startsWith("pilot_") && !name.startsWith("gnormadium_avlafrim")) continue
            for ((index, text) in type.op.withIndex()) {
                val label = text?.trim() ?: continue
                val op = index + 1
                // "Glider" opens the map; "Glider-to <stop>" (Captain Errdo) flies straight there.
                val quick = Glider.entries.firstOrNull { label.equals("Glider-to ${it.label}", true) }
                if (!label.equals("Glider", ignoreCase = true) && quick == null) continue
                if (isNpcOpBound(type, op)) continue
                bind(type, op, quick)
                bound++
            }
        }
        for (dest in Glider.entries) {
            onIfModalButton(dest.button()) { fly(dest) }
        }
        logger.info { "Gnome gliders: bound $bound captains." }
    }

    private fun ScriptContext.bind(type: UnpackedNpcType, op: Int, quick: Glider?) {
        when (op) {
            1 -> onOpNpc1(type) { use(quick) }
            2 -> onOpNpc2(type) { use(quick) }
            3 -> onOpNpc3(type) { use(quick) }
            4 -> onOpNpc4(type) { use(quick) }
            5 -> onOpNpc5(type) { use(quick) }
        }
    }

    private suspend fun ProtectedAccess.use(quick: Glider?) {
        if (player.vars[GliderVarps.grandtree] < GRAND_TREE_DONE) {
            mes("You need to complete The Grand Tree quest to fly the gnome gliders.")
            return
        }
        if (quick != null) fly(quick) else openMap()
    }

    private fun ProtectedAccess.openMap() {
        ifOpenMainModal(GliderInterfaces.map)
        for (dest in Glider.entries) {
            ifSetEvents(dest.button(), 0..0, IfEvent.Op1)
        }
    }

    private suspend fun ProtectedAccess.fly(dest: Glider) {
        ifClose()
        if (player.vars[GliderVarps.grandtree] < GRAND_TREE_DONE) return
        val here = Glider.entries.minBy { it.arrival.chebyshevDistance(player.coords) }
        if (here == dest && here.arrival.chebyshevDistance(player.coords) <= HERE_RADIUS) {
            mes("You're already here.")
            return
        }
        val locked = dest.locked
        if (locked != null) {
            mes(locked)
            return
        }
        val arrival = collision.nearestWalkable(dest.arrival)
        val flown =
            sailTo(arrival, fare = 0, message = "You board the glider.", collision = collision)
        if (!flown) return
        VarPlayerIntMapSetter.set(player, GliderVarBits.previous, dest.index)
        if (dest == Glider.LemantoAndra) {
            mes("The glider becomes uncontrollable and crash-lands west of the Digsite!")
        }
    }

    private companion object {
        const val GRAND_TREE_DONE = 160
        const val HERE_RADIUS = 15
    }
}

/** Glider stops in the cache's `pilot_previous_destination` order (0-6). */
internal enum class Glider(
    val label: String,
    val index: Int,
    val arrival: CoordGrid,
    val button: () -> ComponentType,
    val locked: String? = null,
) {
    TaQuirPriw("Ta Quir Priw", 0, CoordGrid(2465, 3501, 3), { GliderComponents.grandtree }),
    Gandius("Gandius", 1, CoordGrid(2970, 2972, 0), { GliderComponents.karamja }),
    KarHewo("Kar-Hewo", 2, CoordGrid(3284, 3212, 0), { GliderComponents.alkharid }),
    LemantoAndra("Lemanto Andra", 3, CoordGrid(3321, 3430, 0), { GliderComponents.varrock }),
    Sindarpos("Sindarpos", 4, CoordGrid(2848, 3497, 0), { GliderComponents.whitewolf }),
    LemantollyUndri(
        "Lemantolly Undri",
        5,
        CoordGrid(2544, 2970, 0),
        { GliderComponents.ogre },
        "This glider needs One Small Favour, which isn't available yet.",
    ),
    OokookollyUndri(
        "Ookookolly Undri",
        6,
        CoordGrid(2712, 2804, 0),
        { GliderComponents.ape },
        "This glider needs Monkey Madness II, which isn't available yet.",
    ),
}

internal object GliderInterfaces : InterfaceReferences() {
    val map = find("glidermap")
}

internal object GliderComponents : ComponentReferences() {
    val grandtree = find("glidermap:grandtree_button")
    val whitewolf = find("glidermap:whitewolfmountain_button")
    val varrock = find("glidermap:varrock_button")
    val alkharid = find("glidermap:alkharid_button")
    val karamja = find("glidermap:karamja_button")
    val ogre = find("glidermap:ogrearea_button")
    val ape = find("glidermap:apeatoll_button")
}

internal object GliderVarps : VarpReferences() {
    val grandtree = find("grandtree")
}

internal object GliderVarBits : VarBitReferences() {
    val previous = find("pilot_previous_destination")
}
