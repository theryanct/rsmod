package org.rsmod.content.skills.thieving

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType

object ThievingNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        ThievingData.pickpockets.flatMap { it.npcs }.distinct().associateWith { find(it) }
}

object ThievingLocs : LocReferences() {
    val byName: Map<String, LocType> =
        ThievingData.stalls
            .flatMap { stall -> stall.locs + stall.empties.filterNotNull() }
            .distinct()
            .associateWith { find(it) }
}

object ThievingObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        (ThievingData.pickpockets.flatMap { it.loot } + ThievingData.stalls.flatMap { it.loot })
            .map { it.obj }
            .distinct()
            .associateWith { find(it) }
}

object ThievingSeqs : SeqReferences() {
    val pickpocket = find("human_pickpocket")
}
