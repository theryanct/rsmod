package org.rsmod.content.skills.firemaking.configs

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences

internal typealias firemaking_objs = FiremakingObjs

internal typealias firemaking_locs = FiremakingLocs

internal typealias firemaking_seqs = FiremakingSeqs

internal object FiremakingObjs : ObjReferences() {
    val tinderbox = find("tinderbox")
    val ashes = find("ashes")
    val logs = find("logs")
    val achey_tree_logs = find("achey_tree_logs")
    val oak_logs = find("oak_logs")
    val willow_logs = find("willow_logs")
    val teak_logs = find("teak_logs")
    val arctic_pine_logs = find("arctic_pine_log")
    val maple_logs = find("maple_logs")
    val mahogany_logs = find("mahogany_logs")
    val yew_logs = find("yew_logs")
    val magic_logs = find("magic_logs")
    val redwood_logs = find("redwood_logs")
}

internal object FiremakingLocs : LocReferences() {
    val fire = find("fire")
}

internal object FiremakingSeqs : SeqReferences() {
    val human_createfire = find("human_createfire")
}
