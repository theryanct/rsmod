package org.rsmod.content.interfaces.combat.tab.configs

import org.rsmod.api.type.refs.comp.ComponentReferences

typealias combat_components = CombatTabComponents

object CombatTabComponents : ComponentReferences() {
    val stance1 = find("combat_interface:0", 2858390886771847764)
    val stance2 = find("combat_interface:1", 2437616626352755402)
    val stance3 = find("combat_interface:2", 4786378263585440965)
    val stance4 = find("combat_interface:3", 4365604003166348603)
    val auto_retaliate = find("combat_interface:retaliate", 729271659120961907)
    val special_attack = find("combat_interface:special_attack", 4152416858776729212)

    val special_attack_orb = find("orbs:specbutton", 7062014522453936719)
}
