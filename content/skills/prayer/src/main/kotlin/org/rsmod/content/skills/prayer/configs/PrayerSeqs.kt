package org.rsmod.content.skills.prayer.configs

import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences

internal typealias prayer_seqs = PrayerSeqs

internal typealias prayer_spotanims = PrayerSpotanims

internal object PrayerSeqs : SeqReferences() {
    val human_pickupfloor = find("human_pickupfloor")
    val human_bone_sacrifice = find("human_bone_sacrifice")
}

internal object PrayerSpotanims : SpotanimReferences() {
    val poh_bone_sacrifice = find("poh_bone_sacrifice")
}
