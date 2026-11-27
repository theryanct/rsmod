package org.rsmod.content.skills.agility

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
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
