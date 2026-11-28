package org.rsmod.content.interfaces.combat.tab

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.enumVarBit
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.autocast.AutocastWeapons
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.WeaponCategory
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The combat tab's "Choose spell" buttons (Ryan: autocast did nothing; log `UNHANDLED
 * overlay-button=combat_interface:autocast_normal`). As in OSRS, the button swaps the combat tab
 * for the `autocast` picker; its onload (cs2 autocast_init / autocast_setup) builds one child per
 * `autocast_spells` enum entry, so child N is autocast id N and child 0 is "Cancel". The client
 * greys out spells the staff or spellbook can't cast; the server checks again on the pick.
 *
 * Picking a spell sets the live autocast varbits (combat then casts it: [PvNCombatScript] via
 * `resolveAutocastSpell`, with rune use, staff substitution and the defensive xp split) and the
 * per-staff saved varbits that [CombatTabScript] restores on wield. Changing weapon already
 * clears/restores it there.
 */
class AutocastPickerScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val spells: MagicSpellRegistry,
    private val autocast: AutocastWeapons,
) : PluginScript() {
    private var Player.autocastEnabled by boolVarBit(varbits.autocast_set)
    private var Player.autocastSpell by intVarBit(varbits.autocast_spell)
    private var Player.defensiveCasting by boolVarBit(varbits.autocast_defmode)
    private val Player.spellbook by enumVarBit<Spellbook>(varbits.spellbook)

    /** Which button opened the picker: the defensive one sets defensive casting on the pick. */
    private val pendingDefensive = WeakHashMap<Player, Boolean>()

    override fun ScriptContext.startup() {
        onIfOverlayButton(AutocastComponents.choose_normal) { player.openPicker(defensive = false) }
        onIfOverlayButton(AutocastComponents.choose_defensive) {
            player.openPicker(defensive = true)
        }
        onIfOverlayButton(AutocastComponents.spells) { player.pick(comsub) }
    }

    private fun Player.openPicker(defensive: Boolean) {
        val weapon = objTypes.getOrNull(righthand)
        val category = WeaponCategory.getOrUnarmed(weapon?.weaponCategory)
        if (weapon == null || autocast.getVarBits(category) == null) {
            mes("You can't autocast spells with this weapon.")
            return
        }
        pendingDefensive[this] = defensive
        ifOpenOverlay(AutocastInterfaces.autocast, components.toplevel_target_side0, eventBus)
        ifSetEvents(AutocastComponents.spells, 0..MAX_AUTOCAST_ID, IfEvent.Op1)
    }

    private fun Player.pick(autocastId: Int) {
        val defensive = pendingDefensive.remove(this) ?: false
        if (autocastId > 0) {
            select(autocastId, defensive)
        }
        backToCombatTab()
    }

    private fun Player.select(autocastId: Int, defensive: Boolean) {
        val weapon = objTypes.getOrNull(righthand) ?: return
        val category = WeaponCategory.getOrUnarmed(weapon.weaponCategory)
        val saved = autocast.getVarBits(category) ?: return
        val spell = spells.getAutocastSpell(autocastId) ?: return
        if (spell.spellbook != null && spell.spellbook != spellbook) {
            mes("You can't autocast that spell with your current active spellbook.")
            return
        }
        if (magicLvl < spell.levelReq) {
            mes("You need a Magic level of ${spell.levelReq} to cast this spell.")
            return
        }
        // Sends "You can't autocast that spell with this staff." itself.
        if (!autocast.canStaffAutocast(this, weapon, autocastId)) {
            return
        }
        autocastSpell = autocastId
        defensiveCasting = defensive
        autocastEnabled = true
        autocast.set(this, saved, autocastId, defensive)
    }

    private fun Player.backToCombatTab() {
        // Reopening the combat tab runs CombatTabScript's onIfOpen update (icon + styles).
        ifOpenOverlay(interfaces.combat_interface, components.toplevel_target_side0, eventBus)
    }

    private companion object {
        /** autocast_setup loops children 1..58 (enum autocast_spells); child 0 is Cancel. */
        const val MAX_AUTOCAST_ID = 58
    }
}

internal object AutocastInterfaces : InterfaceReferences() {
    val autocast = find("autocast")
}

internal object AutocastComponents : ComponentReferences() {
    val choose_normal = find("combat_interface:autocast_normal")
    val choose_defensive = find("combat_interface:autocast_defensive")
    val spells = find("autocast:spells")
}
