package org.rsmod.content.skills.runecraft

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType

internal object RunecraftObjs : ObjReferences() {
    val rune_essence = find("blankrune")
    val pure_essence = find("blankrune_high")
    val tiara = find("tiara")

    val binding_necklace = find("magic_emerald_necklace")

    val byName: Map<String, ObjType> =
        (RunecraftData.altars.flatMap { listOfNotNull(it.talisman, it.tiara, it.rune) } +
                RunecraftData.combos.flatMap { listOf(it.product, it.rune) })
            .distinct()
            .associateWith { find(it) }
}

internal object RunecraftLocs : LocReferences() {
    val essence_rock = find("blankrunestone")
    val mine_portals =
        listOf(
            find("essencemine_portal_1"),
            find("essencemine_portal_2"),
            find("essencemine_portal_3"),
            find("essencemine_portal_4"),
            find("essencemine_portal_5"),
        )

    val byName: Map<String, LocType> =
        RunecraftData.altars
            .flatMap { listOfNotNull(it.ruins, it.ruinsEnter, it.altar, it.portal) }
            .associateWith { find(it) }
}

internal object RunecraftNpcs : NpcReferences() {
    val aubury = find("aubury_2op")
    val aubury_teleport = find("aubury_3op")
}

internal object RunecraftSeqs : SeqReferences() {
    val bind = find("human_runecraft")
}

internal object RunecraftSpotanims : SpotanimReferences() {
    val bind = find("runecrafting")
}

internal object RunecraftVarBits : VarBitReferences() {
    val byName: Map<String, VarBitType> =
        RunecraftData.altars.map { it.varbit }.filter { it.isNotEmpty() }.associateWith { find(it) }
}

internal object RunecraftVarps : VarpReferences() {
    val runemysteries = find("runemysteries")

    /** Lost City (cache end state 6): the cosmic altar is in Zanaris. */
    val zanaris = find("zanaris")
}
