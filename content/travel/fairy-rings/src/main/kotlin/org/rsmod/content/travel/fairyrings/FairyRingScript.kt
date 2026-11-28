package org.rsmod.content.travel.fairyrings

import jakarta.inject.Inject
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fairy rings. Codes and destinations are Jagex's own: the 243 cache's `fairyring` dbtable (64
 * rows: code, destination coord, and the dial state index = dial1 * 16 + dial2 * 4 + dial3).
 *
 * - Configure opens the dial interface (fairyrings, 398). The client turns the dials itself (cs2
 *   397: clockwise +1, anticlockwise +3, mod 4, on varbits fairyring_1..3); the server mirrors it.
 *   Confirm travels to the dialled code. Letters per dial value: ADCB / ILKJ / PSRQ.
 * - Zanaris (op1 on world rings) goes to the Zanaris hub (code BKS); Last-destination repeats the
 *   last code (custom varp fairyring_last_dest, state index + 1).
 * - Requires a wielded dramen or lunar staff (the Lumbridge elite diary exemption comes with
 *   diaries). With the QuestGates toggle on, Fairytale II must be started: FT2 isn't built yet,
 *   so until it is, its cache varbits OR Fairytale I complete count.
 * - Codes with no destination in the cache, and DIQ (your house), say so instead of moving you.
 * - Not done yet: the travel log side panel (fairyrings_log) and favourites.
 */
class FairyRingScript
@Inject
constructor(private val dbRows: DbRowTypeList, private val names: NameMapping) : PluginScript() {
    private class Destination(val code: String, val coords: CoordGrid?)

    /** Dial state index (0..63) -> destination, from the `fairyring` dbtable. */
    private val destinations: Map<Int, Destination> by lazy {
        val table = names.dbTables["fairyring"] ?: return@lazy emptyMap()
        val out = HashMap<Int, Destination>()
        for ((_, row) in dbRows) {
            if (row.table != table) {
                continue
            }
            val state = row.data[STATE].orEmpty().filterIsInstance<Int>().firstOrNull() ?: continue
            val code = row.data[CODE].orEmpty().filterIsInstance<String>().firstOrNull() ?: continue
            val packed = row.data[DEST].orEmpty().filterIsInstance<Int>().firstOrNull()
            val coords = packed?.let(::CoordGrid)?.takeIf { it.isRealDestination() }
            out[state] = Destination(code.replace(" ", ""), coords)
        }
        out
    }

    override fun ScriptContext.startup() {
        for (ring in listOf(FairyRingLocs.minor_hub, FairyRingLocs.poh_ring)) {
            onOpLoc1(ring) { travelTo(ZANARIS_STATE) }
            onOpLoc2(ring) { configure() }
            onOpLoc3(ring) { lastDestination() }
            onOpLoc4(ring) { configure() }
        }
        onOpLoc2(FairyRingLocs.home_hub) { configure() }
        onOpLoc3(FairyRingLocs.home_hub) { lastDestination() }
        onOpLoc4(FairyRingLocs.home_hub) { configure() }

        onIfModalButton(FairyRingComponents.dial1_clockwise) { turn(FairyRingVarbits.dial1, 1) }
        onIfModalButton(FairyRingComponents.dial1_anticlockwise) { turn(FairyRingVarbits.dial1, 3) }
        onIfModalButton(FairyRingComponents.dial2_clockwise) { turn(FairyRingVarbits.dial2, 1) }
        onIfModalButton(FairyRingComponents.dial2_anticlockwise) { turn(FairyRingVarbits.dial2, 3) }
        onIfModalButton(FairyRingComponents.dial3_clockwise) { turn(FairyRingVarbits.dial3, 1) }
        onIfModalButton(FairyRingComponents.dial3_anticlockwise) { turn(FairyRingVarbits.dial3, 3) }
        onIfModalButton(FairyRingComponents.confirm) {
            ifClose()
            travelTo(dialledState())
        }
    }

    private fun ProtectedAccess.canUse(): Boolean {
        if (player.isFeatureOn(Feature.QuestGates) && !fairytaleStarted()) {
            mes("You need to have started Fairytale II - Cure a Queen to use the fairy rings.")
            return false
        }
        val staffs = listOf(FairyRingObjs.dramen_staff, FairyRingObjs.lunar_staff).map { it.id }
        if (player.worn.none { it != null && it.id in staffs }) {
            mes("The fairy ring only works for those who wield fairy magic.")
            return false
        }
        return true
    }

    /** FT2 isn't implemented: its own varbits, or Fairytale I complete, as the stand-in gate. */
    private fun ProtectedAccess.fairytaleStarted(): Boolean =
        vars[FairyRingVarbits.ft2_quest] > 0 ||
            vars[FairyRingVarbits.permission] > 0 ||
            vars[FairyRingVarbits.ft1_quest] >= FT1_COMPLETE

    private fun ProtectedAccess.configure() {
        if (!canUse()) {
            return
        }
        ifOpenMainModal(FairyRingInterfaces.dials)
    }

    /** Mirrors the client's own dial turn (cs2 397) so the server knows the code. */
    private fun ProtectedAccess.turn(dial: VarBitType, step: Int) {
        VarPlayerIntMapSetter.set(player, dial, (vars[dial] + step) % DIAL_VALUES)
    }

    private fun ProtectedAccess.dialledState(): Int =
        vars[FairyRingVarbits.dial1] * 16 + vars[FairyRingVarbits.dial2] * 4 +
            vars[FairyRingVarbits.dial3]

    private suspend fun ProtectedAccess.lastDestination() {
        val last = vars[FairyRingVarps.last_dest] - 1
        if (last < 0) {
            mes("You haven't used the fairy rings yet; configure a code first.")
            return
        }
        travelTo(last)
    }

    private suspend fun ProtectedAccess.travelTo(state: Int) {
        if (!canUse()) {
            return
        }
        val dest = destinations[state]
        val coords = dest?.coords
        if (dest == null || coords == null) {
            val code = dest?.code ?: codeOf(state)
            mes(
                if (code == HOUSE_CODE) "Fairy ring $code leads to your house, which isn't supported yet."
                else "Fairy ring $code doesn't lead anywhere."
            )
            return
        }
        VarPlayerIntMapSetter.set(player, FairyRingVarps.last_dest, state + 1)
        anim(FairyRingSeqs.vanish)
        spotanim(FairyRingSpots.flower_ring)
        delay(VANISH_TICKS)
        telejump(coords)
        anim(FairyRingSeqs.appear)
    }

    private fun codeOf(state: Int): String =
        "${DIAL1[state / 16 % 4]}${DIAL2[state / 4 % 4]}${DIAL3[state % 4]}"

    /** The dbtable fills unused codes with 0_1_1 (x 65, z 65, level 1) and DIQ with 0. */
    private fun CoordGrid.isRealDestination(): Boolean = x > 100 && z > 100

    private companion object {
        private const val STATE = 1
        private const val DEST = 2
        private const val CODE = 3
        private const val DIAL_VALUES = 4
        private const val DIAL1 = "ADCB"
        private const val DIAL2 = "ILKJ"
        private const val DIAL3 = "PSRQ"
        /** BKS: the Zanaris hub. */
        private const val ZANARIS_STATE = 57
        private const val HOUSE_CODE = "DIQ"
        private const val VANISH_TICKS = 3
        /** fairy_farmers_quest when Fairytale I is complete (world's quest). */
        private const val FT1_COMPLETE = 90
    }
}

internal object FairyRingLocs : LocReferences() {
    /** Every ring outside Zanaris: Zanaris / Configure / Last-destination / Favourites. */
    val minor_hub = find("fairyring_minorhub")
    /** The Zanaris hub ring (no op1). */
    val home_hub = find("fairyring_homehub")
    val poh_ring = find("poh_fairy_ring")
}

internal object FairyRingInterfaces : InterfaceReferences() {
    val dials = find("fairyrings")
}

internal object FairyRingComponents : ComponentReferences() {
    val dial1_clockwise = find("fairyrings:1_clockwise")
    val dial1_anticlockwise = find("fairyrings:1_anticlockwise")
    val dial2_clockwise = find("fairyrings:2_clockwise")
    val dial2_anticlockwise = find("fairyrings:2_anticlockwise")
    val dial3_clockwise = find("fairyrings:3_clockwise")
    val dial3_anticlockwise = find("fairyrings:3_anticlockwise")
    val confirm = find("fairyrings:confirm")
}

internal object FairyRingVarbits : VarBitReferences() {
    val dial1 = find("fairyring_1")
    val dial2 = find("fairyring_2")
    val dial3 = find("fairyring_3")
    val ft2_quest = find("fairy2_queencure_quest")
    val permission = find("fairyring_permission")
    val ft1_quest = find("fairy_farmers_quest")
}

/** Custom (ui range, 65328): the last code used, as dial state index + 1 (0 = none). */
internal object FairyRingVarps : VarpReferences() {
    val last_dest = find("fairyring_last_dest")
}

internal object FairyRingVarpBuilder : VarpBuilder() {
    init {
        build("fairyring_last_dest")
    }
}

internal object FairyRingObjs : ObjReferences() {
    val dramen_staff = find("dramen_staff")
    val lunar_staff = find("lunar_moonclan_liminal_staff")
}

internal object FairyRingSeqs : SeqReferences() {
    val vanish = find("human_fairy_vanish")
    val appear = find("human_fairy_appear")
}

internal object FairyRingSpots : SpotanimReferences() {
    val flower_ring = find("fairy_flower_ring")
}
