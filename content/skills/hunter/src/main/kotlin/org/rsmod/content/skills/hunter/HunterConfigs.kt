package org.rsmod.content.skills.hunter

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType

object HunterNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        HunterData.creatures.flatMap { it.npcs }.distinct().associateWith { find(it) }
}

object HunterObjs : ObjReferences() {
    val bird_snare = find("hunting_ojibway_bird_snare")
    val butterfly_net = find("hunting_butterfly_net")
    val butterfly_jar = find("butterfly_jar")

    val byName: Map<String, ObjType> =
        HunterData.creatures.flatMap { c -> c.loot.map { it.obj } }.distinct().associateWith { find(it) }
}

object HunterLocs : LocReferences() {
    val snare_laid = find("hunting_ojibway_trap")
    val snare_broken = find("hunting_ojibway_trap_broken")

    val byName: Map<String, LocType> =
        HunterData.creatures
            .flatMap { listOfNotNull(it.trappingLoc, it.fullLoc) }
            .distinct()
            .associateWith { find(it) }
}

object HunterSeqs : SeqReferences() {
    val lay_trap = find("human_laytrap")
    val pick_up = find("human_pickupfloor")
    val net_swing = find("human_butterflynet_swing")
}

object HunterQueues : QueueReferences() {
    val snare_check = find("hunter_snare_check")
}
