package org.rsmod.content.interfaces.skill.guides.configs

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.game.type.comp.ComponentType

typealias guide_interfaces = SkillGuideInterfaces

typealias guide_components = SkillGuideComponents

object SkillGuideInterfaces : InterfaceReferences() {
    /** Rev 243 skill guide (interface 860): resizable floater, tabs and lists built by cs2. */
    val skill_guide_v2 = find("skill_guide_v2")
}

object SkillGuideComponents : ComponentReferences() {
    val close_button = find("skill_guide_v2:close")

    /**
     * Stats tab buttons -> skill guide id (client enum 681: 1 attack .. 24 sailing), the first
     * argument of `skill_guide_v2_init`.
     */
    val skillButtons: Map<ComponentType, Int> =
        listOf(
                "attack",
                "strength",
                "ranged",
                "magic",
                "defence",
                "hitpoints",
                "prayer",
                "agility",
                "herblore",
                "thieving",
                "crafting",
                "runecraft",
                "mining",
                "smithing",
                "fishing",
                "cooking",
                "firemaking",
                "woodcutting",
                "fletching",
                "slayer",
                "farming",
                "construction",
                "hunter",
                "sailing",
            )
            .withIndex()
            .associate { (index, skill) -> find("stats:$skill") to index + 1 }
}
