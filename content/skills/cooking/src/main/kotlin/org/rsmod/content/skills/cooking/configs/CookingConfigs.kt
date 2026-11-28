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

    /** Hosidius kitchen ranges (+5% burn bonus; the diary +10% is not implemented). */
    val hosidius_range = find("hos_cooking_range")
    val hosidius_range_02 = find("hos_cooking_range_02")
}

internal object CookingSeqs : SeqReferences() {
    val human_cooking = find("human_cooking")
    val human_firecooking = find("human_firecooking")
}
