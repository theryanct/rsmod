package org.rsmod.content.skills.cooking.configs

import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences

internal typealias cooking_objs = CookingObjs

internal typealias cooking_seqs = CookingSeqs

internal object CookingObjs : ObjReferences() {
    val raw_shrimps = find("raw_shrimp")
    val shrimps = find("shrimp")
    val burnt_shrimp = find("burnt_shrimp")
    val raw_anchovies = find("raw_anchovies")
    val anchovies = find("anchovies")
    val burnt_anchovies = find("burntfish1")
    val raw_sardine = find("raw_sardine")
    val sardine = find("sardine")
    val burnt_sardine = find("burntfish5")
    val raw_herring = find("raw_herring")
    val herring = find("herring")
    val burnt_herring = find("burntfish3")
    val bread_dough = find("bread_dough")
    val bread = find("bread")
    val burnt_bread = find("burnt_bread")

    val pot_flour = find("pot_flour")
    val pot_empty = find("pot_empty")
    val bucket_water = find("bucket_water")
    val bucket_empty = find("bucket_empty")
    val jug_water = find("jug_water")
    val jug_empty = find("jug_empty")
}

internal object CookingSeqs : SeqReferences() {
    val human_cooking = find("human_cooking")
    val human_firecooking = find("human_firecooking")
}
