package org.rsmod.content.skills.magic.teleports.configs

import org.rsmod.api.type.editors.comp.ComponentEditor
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varp.VarpReferences

internal typealias home_tele_components = HomeTeleportComponents

internal typealias home_tele_queues = HomeTeleportQueues

internal typealias home_tele_seqs = HomeTeleportSeqs

internal typealias home_tele_spots = HomeTeleportSpotanims

internal typealias home_tele_varps = HomeTeleportVarps

internal object HomeTeleportComponents : ComponentReferences() {
    val standard = find("magic_spellbook:teleport_home_standard")
}

/** Home Teleport right-click op8 "Gallery" (the unused-asset gallery; HomeTeleportGallery). */
internal object HomeTeleportEdits : ComponentEditor() {
    init {
        editOps(HomeTeleportComponents.standard, 8 to "Gallery")
    }
}

internal object HomeTeleportQueues : QueueReferences() {
    val stage = find("home_teleport")
}

internal object HomeTeleportSeqs : SeqReferences() {
    val chalk_circle = find("aide_drawing_chalk_circle")
    val sit_down = find("aide_sitting_down_crosslegged")
    val get_book = find("aide_player_getting_book")
    val incantation = find("aide_reciting_incantation")
    val teleporting = find("aide_player_teleporting")
}

internal object HomeTeleportSpotanims : SpotanimReferences() {
    val chalk_circle = find("aide_chalk_circle")
    val book_get = find("aide_player_book_get")
    val book_portal = find("aide_player_book_portal")
    val teleport = find("aide_player_teleport")
}

internal object HomeTeleportVarps : VarpReferences() {
    /** Minutes since the unix epoch at which the player last cast a home teleport. */
    val last_cast = find("aide_tele_timer")
}
