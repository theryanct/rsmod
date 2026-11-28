package org.rsmod.content.interfaces.prayer.tab.configs

import org.rsmod.api.type.refs.enums.EnumReferences
import org.rsmod.game.type.obj.ObjType

internal typealias prayer_enums = PrayerTabEnums

object PrayerTabEnums : EnumReferences() {
    val obj_configs = find<Int, ObjType>("prayer_oc", 18447792)
    // Rev 243: keyed by the prayer's obj (was the prayer id in 233).
    val attack_collisions = find<ObjType, Boolean>("prayer_attack_collisions")
    val strength_collisions = find<ObjType, Boolean>("prayer_strength_collisions")
    val defence_collisions = find<ObjType, Boolean>("prayer_defence_collisions")
    val overhead_collisions = find<ObjType, Boolean>("prayer_overhead_collisions")
}
