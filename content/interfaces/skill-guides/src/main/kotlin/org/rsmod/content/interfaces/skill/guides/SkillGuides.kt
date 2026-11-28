package org.rsmod.content.interfaces.skill.guides

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.content.interfaces.skill.guides.configs.guide_interfaces

/** `[clientscript,skill_guide_v2_init](int skill, int tab, int fromLevel, int toLevel)`. */
internal const val SKILL_GUIDE_V2_INIT = 1902

/** Skill guide ids (client enum 681), the first argument of `skill_guide_v2_init`. */
object SkillGuideIds {
    const val FARMING = 21
}

/**
 * Opens the rev 243 skill guide floater on [tab] (a `skill_guide_subsections` index; 0 is the
 * overview), e.g. a farming patch's "Guide" op.
 */
fun ProtectedAccess.openSkillGuide(skill: Int, tab: Int) {
    ifOpenOverlay(guide_interfaces.skill_guide_v2)
    runClientScript(SKILL_GUIDE_V2_INIT, skill, tab, 0, 0)
}
