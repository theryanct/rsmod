package org.rsmod.content.skills.agility

import org.rsmod.api.type.builders.bas.BasBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.bas.UnpackedBasType
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.seq.SeqType

object AgilityLocs : LocReferences() {
    val byName: Map<String, LocType> =
        (AgilityData.obstacles.flatMap { it.locs } + AgilityData.shortcuts.flatMap { it.locs })
            .distinct()
            .associateWith { find(it) }
}

object AgilitySeqs : SeqReferences() {
    val byName: Map<String, SeqType> =
        (AgilityData.obstacles.map { it.anim } + AgilityData.shortcuts.map { it.anim })
            .distinct()
            .associateWith { find(it) }
}

object AgilityObjs : ObjReferences() {
    val mark_of_grace = find("grace")
}

/** Crossing anims that must hold for the whole crossing (see [AgilityBas]). */
object AgilityCrossingSeqs : SeqReferences() {
    val logbalance = find("human_walk_logbalance")
    val sidestep = find("human_walk_sidestepl")
    val monkeybars = find("human_monkeybars_walk")
    val tunnel = find("agilty_shortcut_tunnel_walk")
    val crawl = find("human_crawling")
}

/**
 * Temporary base anim sets for crossings: every ready/turn/walk/run anim is the crossing anim,
 * so the client keeps playing it while the player is force-moved, instead of falling back to the
 * normal walk/ready anims once the one-shot sequence ends (OSRS swaps the BAS the same way).
 * Ids are server-side only (bas.sym 1-5).
 */
object AgilityBas : BasBuilder() {
    val logbalance = crossing("agility_logbalance") { AgilityCrossingSeqs.logbalance }
    val sidestep = crossing("agility_sidestep") { AgilityCrossingSeqs.sidestep }
    val monkeybars = crossing("agility_monkeybars") { AgilityCrossingSeqs.monkeybars }
    val tunnel = crossing("agility_tunnel") { AgilityCrossingSeqs.tunnel }
    val crawl = crossing("agility_crawl") { AgilityCrossingSeqs.crawl }

    /** Crossing anim name (agility.tsv) -> its base anim set. */
    val byAnim: Map<String, UnpackedBasType> =
        mapOf(
            "human_walk_logbalance" to logbalance,
            "human_walk_sidestepl" to sidestep,
            "human_monkeybars_walk" to monkeybars,
            "agilty_shortcut_tunnel_walk" to tunnel,
            "human_crawling" to crawl,
        )

    private fun crossing(name: String, seq: () -> SeqType) =
        build(name) {
            readyAnim = seq()
            turnAnim = seq()
            walkAnim = seq()
            walkAnimBack = seq()
            walkAnimLeft = seq()
            walkAnimRight = seq()
            runAnim = seq()
        }
}
