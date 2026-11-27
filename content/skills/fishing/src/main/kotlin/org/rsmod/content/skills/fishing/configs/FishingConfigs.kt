package org.rsmod.content.skills.fishing.configs

import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences

internal typealias fishing_objs = FishingObjs

internal typealias fishing_seqs = FishingSeqs

internal object FishingObjs : ObjReferences() {
    val net = find("net")
    val fishing_rod = find("fishing_rod")
    val fishing_bait = find("fishing_bait")
    val raw_shrimps = find("raw_shrimp")
    val raw_anchovies = find("raw_anchovies")
    val raw_sardine = find("raw_sardine")
    val raw_herring = find("raw_herring")
}

internal object FishingSeqs : SeqReferences() {
    val human_smallnet = find("human_smallnet")
    val human_fishing_casting = find("human_fishing_casting")
}
