package org.rsmod.content.other.companions

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.varp.VarpType

internal object CompanionNpcs : NpcReferences() {
    val doric = find("doric")
    /** Post-quest My Arm (Talk-to); spawned by [CompanionNpcSpawns]. */
    val myarm = find("myarm_fixed")
}

internal object CompanionVarBits : VarBitReferences() {
    /** My Arm's Big Adventure progress (cache quest endstate 320 = complete). */
    val myarm = find("myarm")
}

internal object CompanionTimers : TimerReferences() {
    /** Custom soft timer (65508, skilling range): affinity, knock-out return, chat. */
    val tick = find("companion_tick")
}

/** Saved per character (skilling symbol range 65566-65588). */
internal object CompanionVarpBuilds : VarpBuilder() {
    init {
        build("companion_active")
        build("companion_affinity_doric")
        build("companion_affinity_myarm")
        for (slot in 1..STORE_SLOTS) {
            build("companion_store_${slot}_id")
            build("companion_store_${slot}_count")
        }
    }
}

internal object CompanionVarps : VarpReferences() {
    /** [CompanionKind] ordinal + 1 of the active companion (0 = none). */
    val active = find("companion_active")
    /** Affinity points (+1 per 5 minutes together). */
    val affinity_doric = find("companion_affinity_doric")
    val affinity_myarm = find("companion_affinity_myarm")
    /** My Arm's storage: obj id + 1 (0 = empty slot) and count per slot. */
    val storeIds: List<VarpType> = (1..STORE_SLOTS).map { find("companion_store_${it}_id") }
    val storeCounts: List<VarpType> = (1..STORE_SLOTS).map { find("companion_store_${it}_count") }

    val affinity: Map<CompanionKind, VarpType> by lazy {
        mapOf(CompanionKind.Doric to affinity_doric, CompanionKind.MyArm to affinity_myarm)
    }
}

/** Companion npcs the world spawn data doesn't place yet (`npcs.toml`). */
internal object CompanionNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<CompanionNpcSpawns>("npcs.toml")
    }
}

private const val STORE_SLOTS = 10
