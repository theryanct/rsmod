package org.rsmod.content.interfaces.skill.guides

import jakarta.inject.Inject
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.ui.ifCloseSub
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.content.interfaces.skill.guides.configs.guide_components
import org.rsmod.content.interfaces.skill.guides.configs.guide_interfaces
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rev 243 skill guides (`skill_guide_v2`). Clicking a skill in the stats tab opens the guide as a
 * floater (the game stays playable) and runs `skill_guide_v2_init(skill, tab, 0, 0)`. Everything
 * else - tabs, lists, drag/resize, the members/level-up rows - is client-side cs2 over the cache's
 * `skill_guide_subsections`/`skill_features` db tables, kept in client varcs, so the server only
 * opens and closes it.
 *
 * Not ported: the overview's links to other skills' guides (`skill_guide_button_trigger`) use the
 * client `if_runscript` packet, which this server does not decode yet.
 */
class SkillGuideScript @Inject constructor(private val eventBus: EventBus) : PluginScript() {
    override fun ScriptContext.startup() {
        for ((button, skill) in guide_components.skillButtons) {
            // 243 stats buttons: op2 "View <skill> guide". op1 "Toggle <skill> XP" is set by
            // stats_init on mobile clients only (desktop gets an empty op1), so nothing to handle.
            onIfOverlayButton(button) {
                if (op == IfButtonOp.Op2) {
                    player.openGuide(skill)
                }
            }
        }
        onIfOverlayButton(guide_components.close_button) { player.closeGuide() }
    }

    private fun Player.openGuide(skill: Int) {
        ifOpenOverlay(guide_interfaces.skill_guide_v2, eventBus)
        runClientScript(SKILL_GUIDE_V2_INIT, skill, OVERVIEW_TAB, 0, 0)
    }

    private fun Player.closeGuide() {
        ifCloseSub(guide_interfaces.skill_guide_v2, eventBus)
    }

    private companion object {
        /** `[clientscript,skill_guide_v2_init](int skill, int tab, int fromLevel, int toLevel)`. */
        const val SKILL_GUIDE_V2_INIT = 1902

        /** Tab 0 is the overview; 31 is "Level-up Unlocks" (uses the level range arguments). */
        const val OVERVIEW_TAB = 0
    }
}
