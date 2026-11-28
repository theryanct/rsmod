package org.rsmod.content.skills.cooking.configs

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.ObjType

internal typealias cooking_objs = CookingObjs

internal typealias cooking_seqs = CookingSeqs

internal typealias cooking_locs = CookingLocs

internal object CookingObjs : ObjReferences() {
    val gauntlets_of_cooking = find("gauntlets_of_cooking")

    val byName: Map<String, ObjType> = CookingData.objNames.associateWith { find(it) }
}

internal object CookingLocs : LocReferences() {
    /** Lumbridge Castle kitchen range (lower burn rates; Cook's Assistant gate skipped). */
    val lumbridge_range = find("cooksquestrange")
}

internal object CookingSeqs : SeqReferences() {
    val human_cooking = find("human_cooking")
    val human_firecooking = find("human_firecooking")
}
