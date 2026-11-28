package org.rsmod.content.skills.fletching

import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.seq.SeqType

object FletchingObjs : ObjReferences() {
    val knife: ObjType = find("knife")
    val hammer: ObjType = find("hammer")
    val chisel: ObjType = find("chisel")

    val byName: Map<String, ObjType> =
        FletchingData.recipes
            .flatMap { recipe -> recipe.materials.map { it.obj } + recipe.product }
            .distinct()
            .associateWith { find(it) }
}

object FletchingSeqs : SeqReferences() {
    val byName: Map<String, SeqType> =
        FletchingData.recipes.mapNotNull { it.anim }.distinct().associateWith { find(it) }
}

object FletchingQueues : QueueReferences() {
    val fletching = find("fletching")
}
