package org.rsmod.content.skills.herblore

import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.ObjType

internal object HerbloreObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        (HerbloreData.herbs.flatMap { listOf(it.grimy, it.clean) } +
                HerbloreData.unfinished.flatMap { listOf(it.herb, it.unf) } +
                HerbloreData.potions.flatMap { listOf(it.unf, it.secondary, it.product) } +
                HerbloreData.drinks.flatMap { it.doses } +
                HerbloreData.grinds.flatMap { listOf(it.source, it.product) } +
                listOf(HerbloreData.VIAL, HerbloreData.VIAL_WATER, HerbloreData.PESTLE))
            .distinct()
            .associateWith { find(it) }
}

internal object HerbloreSeqs : SeqReferences() {
    val mix = find("human_herbing_vial")
    val drink = find("human_eat")
    val fill = find("human_pickuptable")
    val grind = find("human_herbing_grind")
}

internal object HerbloreQueues : QueueReferences() {
    val make = find("herblore_make")
}
