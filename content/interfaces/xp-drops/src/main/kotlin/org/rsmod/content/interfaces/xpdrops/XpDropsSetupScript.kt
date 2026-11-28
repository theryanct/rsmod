package org.rsmod.content.interfaces.xpdrops

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.ifOpenMainModal
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * XP drops: the minimap XP orb's Show/Hide (op1) and Setup (op2, interface xpdrops_setup, 137).
 *
 * The client changes these settings itself (cs2 1040 toggles xpdrops_enabled; cs2 1036 sets the
 * dropdown varbit to the clicked option's comsub - 1). The server mirrors each change so it is
 * saved and survives a relog. Not done yet: per-skill trackers and goals (the skill setup half).
 */
class XpDropsSetupScript @Inject constructor(private val eventBus: EventBus) : PluginScript() {
    /** Dropdown option list -> the varbit cs2 1036 sets from it. */
    private val dropdowns: List<Pair<ComponentType, VarBitType>> by lazy {
        listOf(
            XpDropsComponents.position_options to XpDropsVarbits.position,
            XpDropsComponents.size_options to XpDropsVarbits.size,
            XpDropsComponents.duration_options to XpDropsVarbits.duration,
            XpDropsComponents.counter_options to XpDropsVarbits.counter_type,
            XpDropsComponents.progress_options to XpDropsVarbits.progress_type,
            XpDropsComponents.colour_options to XpDropsVarbits.colour,
            XpDropsComponents.grouping_options to XpDropsVarbits.groupskills,
            XpDropsComponents.speed_options to XpDropsVarbits.speed,
            XpDropsComponents.fakes_options to XpDropsVarbits.fakesoff,
        )
    }

    override fun ScriptContext.startup() {
        onIfOverlayButton(XpDropsComponents.orb) {
            when (op) {
                IfButtonOp.Op1 -> player.toggle()
                IfButtonOp.Op2 -> player.openSetup()
                else -> Unit
            }
        }
        for ((list, varbit) in dropdowns) {
            onIfModalButton(list) { choose(varbit, it.comsub) }
        }
    }

    private fun Player.toggle() {
        val on = vars[XpDropsVarbits.enabled]
        VarPlayerIntMapSetter.set(this, XpDropsVarbits.enabled, if (on == 0) 1 else 0)
    }

    private fun Player.openSetup() {
        ifOpenMainModal(XpDropsInterfaces.setup, eventBus)
        for ((list, _) in dropdowns) {
            ifSetEvents(list, 0..OPTION_SLOTS, IfEvent.Op1)
        }
    }

    private fun ProtectedAccess.choose(varbit: VarBitType, comsub: Int) {
        val value = comsub - 1
        if (value in 0 until MAX_VALUE) {
            VarPlayerIntMapSetter.set(player, varbit, value)
        }
    }

    private companion object {
        /** Dropdown option rows (the skill lists are the longest: ~25 skills + extras). */
        const val OPTION_SLOTS = 63
        /** Varbit values are small; clamp anything odd the client sends. */
        const val MAX_VALUE = 64
    }
}

internal object XpDropsInterfaces : InterfaceReferences() {
    val setup = find("xpdrops_setup")
}

internal object XpDropsComponents : ComponentReferences() {
    val orb = find("orbs:xp_drops")
    val position_options = find("xpdrops_setup:position_options")
    val size_options = find("xpdrops_setup:size_options")
    val duration_options = find("xpdrops_setup:duration_options")
    val counter_options = find("xpdrops_setup:counter_options")
    val progress_options = find("xpdrops_setup:progress_options")
    val colour_options = find("xpdrops_setup:colour_options")
    val grouping_options = find("xpdrops_setup:grouping_options")
    val speed_options = find("xpdrops_setup:speed_options")
    val fakes_options = find("xpdrops_setup:fakes_options")
}

internal object XpDropsVarbits : VarBitReferences() {
    val enabled = find("xpdrops_enabled")
    val position = find("xpdrops_position")
    val size = find("xpdrops_size")
    val duration = find("xpdrops_duration")
    val counter_type = find("xpdrops_counter_type")
    val progress_type = find("xpdrops_progress_type")
    val colour = find("xpdrops_colour")
    val groupskills = find("xpdrops_groupskills")
    val speed = find("xpdrops_speed")
    val fakesoff = find("xpdrops_fakesoff")
}
