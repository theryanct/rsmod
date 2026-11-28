package org.rsmod.content.skills.sailing

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.dbrow.DbRowReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.type.dbrow.DbRowType
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType

internal object SailingVarBits : VarBitReferences() {
    // Boat slot 1 (the raft): owned flag, sailing_boat type_id (raft = 0), sailing_dock dock_id.
    val boat_1_owned = find("sailing_boat_1_owned")
    val boat_1_type = find("sailing_boat_1_type")
    val boat_1_port = find("sailing_boat_1_port")

    /** Pandemonium's quest varbit (the raft is its reward). */
    val pandemonium = find("sailing_intro")

    /** Multivar of every ledger table: 0 shows "Take-cargo", 1 shows "Deposit-cargo". */
    val carrying_cargo = find("sailing_carrying_cargo")

    /** Port task slots 0-4: task_id, crates taken, crates delivered (client board ticks). */
    val slot_ids: List<VarBitType> = (0..4).map { find("port_task_slot_${it}_id") }
    val slot_taken: List<VarBitType> = (0..4).map { find("port_task_slot_${it}_cargo_taken") }
    val slot_delivered: List<VarBitType> = (0..4).map { find("port_task_slot_${it}_cargo_delivered") }

    /** "Look at the port task board in Port Sarim" (a generic chart with no Chart loc). */
    val chart_board_port_sarim = find("sailing_charting_generic_board_port_sarim_complete")

    val charts: Map<String, VarBitType> = SailingData.chartLocs.associate { it.varbit to find(it.varbit) }
}

internal object SailingNpcs : NpcReferences() {
    val shipwright_port_sarim = find("sailing_shipwright_port_sarim")
}

internal object SailingLocs : LocReferences() {
    val boards: Map<String, LocType> =
        SailingData.ports.mapNotNull { p -> p.board?.let { p.key to find(it) } }.toMap()
    val ledgers: Map<String, LocType> =
        SailingData.ports.mapNotNull { p -> p.ledger?.let { p.key to find(it) } }.toMap()
    val charts: Map<String, LocType> = SailingData.chartLocs.associate { it.loc to find(it.loc) }
}

internal object SailingObjs : ObjReferences() {
    val crates: Map<String, ObjType> = SailingData.couriers.map { it.crate }.distinct().associateWith { find(it) }
}

internal object SailingDbRows : DbRowReferences() {
    val tasks: Map<Int, DbRowType> = SailingData.couriers.associate { it.taskId to find(it.dbrow) }
}

internal object SailingInterfaces : InterfaceReferences() {
    val port_task_board = find("port_task_board")
}

internal object SailingComponents : ComponentReferences() {
    val board_container = find("port_task_board:container")
    val board_close = find("port_task_board:close")
}

internal object SailingNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<SailingNpcSpawns>("npcs.toml")
    }
}
