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

/** Standard bows and their barbarian fire-lighting animation (longbows share the shortbow's). */
internal object FiremakingBows : ObjReferences() {
    private val seqRefs = FiremakingBowSeqs

    val bows: List<Pair<org.rsmod.game.type.obj.ObjType, org.rsmod.game.type.seq.SeqType>> =
        listOf(
            find("shortbow") to seqRefs.normal,
            find("longbow") to seqRefs.normal,
            find("oak_shortbow") to seqRefs.oak,
            find("oak_longbow") to seqRefs.oak,
            find("willow_shortbow") to seqRefs.willow,
            find("willow_longbow") to seqRefs.willow,
            find("maple_shortbow") to seqRefs.maple,
            find("maple_longbow") to seqRefs.maple,
            find("yew_shortbow") to seqRefs.yew,
            find("yew_longbow") to seqRefs.yew,
            find("magic_shortbow") to seqRefs.magic,
            find("magic_longbow") to seqRefs.magic,
        )
}

internal object FiremakingBowSeqs : SeqReferences() {
    val normal = find("brut_player_firemaking_shortbow")
    val oak = find("brut_player_firemaking_oak_shortbow")
    val willow = find("brut_player_firemaking_willow_shortbow")
    val maple = find("brut_player_firemaking_maple_shortbow")
    val yew = find("brut_player_firemaking_yew_shortbow")
    val magic = find("brut_player_firemaking_magic_shortbow")
}
