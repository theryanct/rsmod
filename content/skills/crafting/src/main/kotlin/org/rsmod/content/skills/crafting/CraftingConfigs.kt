package org.rsmod.content.skills.crafting

import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.seq.SeqType

internal object CraftingObjs : ObjReferences() {
    val thread: ObjType = find("thread")

    val byName: Map<String, ObjType> = CraftingData.objNames.associateWith { find(it) }
}

internal object CraftingSeqs : SeqReferences() {
    val byName: Map<String, SeqType> =
        CraftingData.recipes.mapNotNull { it.anim }.distinct().associateWith { find(it) }
}

internal object CraftingQueues : QueueReferences() {
    val crafting = find("crafting")
}
