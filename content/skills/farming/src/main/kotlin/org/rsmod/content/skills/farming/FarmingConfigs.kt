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

    /** Watering cans by charges remaining (index 0 = empty). */
    val watering_cans: List<ObjType> = (0..8).map { find("watering_can_$it") }

    val byName: Map<String, ObjType> =
        FarmingData.crops
            .flatMap { listOf(it.seed, it.produce) }
            .distinct()
            .associateWith { find(it) }
}

internal object FarmingLocs : LocReferences() {
    val byName: Map<String, LocType> =
        FarmingData.patches.map { it.loc }.distinct().associateWith { find(it) }
}

internal object FarmingNpcs : NpcReferences() {
    val leprechaun = find("farming_tools_leprechaun")
}

internal object FarmingSeqs : SeqReferences() {
    val rake = find("farming_raking")
    val dibbing = find("farming_seed_dibbing")
    val watering = find("farming_watering")
    val dig = find("human_dig")
    val pick = find("picking_low")
    val compost = find("farming_pour_water")
}

internal object FarmingVarBits : VarBitReferences() {
    val byName: Map<String, VarBitType> =
        FarmingData.patches.map { it.transmit }.distinct().associateWith { find(it) }
}

internal object FarmingTimers : TimerReferences() {
    val refresh = find("farming_refresh")
}

/** Saved per character (world symbol range 65700-65899: varps 65710-65741). */
internal object FarmingVarpBuilds : VarpBuilder() {
    init {
        for (patch in FarmingData.patches) {
            build(patch.stateVarp)
            build(patch.timeVarp)
        }
    }
}

internal object FarmingVarps : VarpReferences() {
    val byName: Map<String, VarpType> =
        FarmingData.patches
            .flatMap { listOf(it.stateVarp, it.timeVarp) }
            .associateWith { find(it) }
}

internal object FarmingNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<FarmingNpcSpawns>("npcs.toml")
    }
}
