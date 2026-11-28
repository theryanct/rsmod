package org.rsmod.content.skills.farming

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.varp.VarpType

internal object FarmingObjs : ObjReferences() {
    val rake = find("rake")
    val dibber = find("dibber")
    val spade = find("spade")
    val weeds = find("weeds")
    val compost = find("bucket_compost")
    val supercompost = find("bucket_supercompost")
    val bucket = find("bucket_empty")
    val secateurs = find("secateurs")
    val trowel = find("gardening_trowel")
    val plantpot = find("plantpot_empty")

    /** Watering cans by charges remaining (index 0 = empty). */
    val watering_cans: List<ObjType> = (0..8).map { find("watering_can_$it") }

    val byName: Map<String, ObjType> =
        (FarmingData.crops.flatMap { listOfNotNull(it.seed, it.produce, it.payment, it.logs.ifEmpty { null }) } +
                FarmingData.compostables)
            .distinct()
            .associateWith { find(it) }
}

internal object FarmingLocs : LocReferences() {
    val byName: Map<String, LocType> =
        (FarmingData.patches.map { it.loc } + FarmingData.bins.map { it.loc })
            .distinct()
            .associateWith { find(it) }
}

internal object FarmingNpcs : NpcReferences() {
    val leprechaun = find("farming_tools_leprechaun")

    /** Gardeners with a Pay op (patch protection). */
    val gardeners =
        listOf(
            "farming_gardener_cactus",
            "elstan",
            "dantaera",
            "kragen",
            "lyra",
            "francis",
            "garth",
            "farming_gardener_fruit_1",
            "farming_gardener_fruit_2",
            "farming_gardener_fruit_4",
            "farming_gardener_fruit_tree_5",
            "farming_gardener_hops_1",
            "farming_gardener_hops_3",
            "farming_gardener_hops_4",
            "farming_gardener_bush_1",
            "farming_gardener_bush_2",
            "farming_gardener_bush_3",
            "farming_gardener_bush_4",
            "farming_gardener_tree_1",
            "farming_gardener_tree_2",
            "farming_gardener_tree_3",
            "farming_gardener_tree_4",
            "farming_gardener_tree_gnome",
            "fossil_squirrel_gardener1",
            "fossil_squirrel_gardener2",
            "fossil_squirrel_gardener3",        )
            .map { find(it) }
}

internal object FarmingSeqs : SeqReferences() {
    val rake = find("farming_raking")
    val dibbing = find("farming_seed_dibbing")
    val watering = find("farming_watering")
    val dig = find("human_dig")
    val pick = find("picking_low")
    val check = find("human_pickupfloor")
    val bin = find("human_pickuptable")
    val compost = find("farming_pour_water")
}

internal object FarmingVarBits : VarBitReferences() {
    val byName: Map<String, VarBitType> =
        (FarmingData.patches.map { it.transmit } + FarmingData.bins.map { it.transmit })
            .distinct()
            .associateWith { find(it) }
}

internal object FarmingTimers : TimerReferences() {
    val refresh = find("farming_refresh")
}

/** Saved per character (world symbol range 65700-65899: varps 65710-65768). */
internal object FarmingVarpBuilds : VarpBuilder() {
    init {
        for (name in FarmingData.varpNames) {
            build(name)
        }
    }
}

internal object FarmingVarps : VarpReferences() {
    val byName: Map<String, VarpType> = FarmingData.varpNames.associateWith { find(it) }
}

internal object FarmingNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<FarmingNpcSpawns>("npcs.toml")
    }
}
