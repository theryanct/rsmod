package org.rsmod.content.skills.crafting

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varobjs
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.utils.format.formatAmount
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.isType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Serpentine visage and helm (OSRS Wiki "Serpentine visage", "Serpentine helm"):
 * - Chisel on the visage at 52 Crafting (120 xp) -> serpentine helm (uncharged).
 * - Zulrah's scales on either helm charge it, up to 11,000 scales (uncharged -> charged).
 * - Charged helm: "Check" (Scales: N (x%)), "Uncharge" (scales back to the inventory).
 * - Visage / uncharged helm: "Dismantle" -> 20,000 Zulrah's scales.
 * - Worn, the charged helm blocks poison and venom (`hasSerpentineProtection` in
 *   org.rsmod.api.player.poison).
 *
 * Scales are kept in the obj vars (`charges_16383`, bits 0-13).
 *
 * TODO(venom): scale use in combat (10 scales on entering combat, another 10 every 90 ticks
 * still in combat; reverting to uncharged at 0), and the venom-on-hit effect (melee hits on
 * npcs). Until then the charged helm's immunity costs no scales.
 */
class Serpentine
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val charges: ObjChargeManager,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onOpHeldU(SerpentineObjs.chisel, SerpentineObjs.visage) { chiselVisage() }
        onOpHeldU(SerpentineObjs.scales, SerpentineObjs.helm) { charge(it.secondSlot) }
        onOpHeldU(SerpentineObjs.scales, SerpentineObjs.helm_charged) { charge(it.secondSlot) }
        onHeldOp(SerpentineObjs.helm_charged, "check") { check(it) }
        onHeldOp(SerpentineObjs.helm_charged, "uncharge") { uncharge(it) }
        onHeldOp(SerpentineObjs.visage, "dismantle") { dismantle(it, SerpentineObjs.visage) }
        onHeldOp(SerpentineObjs.helm, "dismantle") { dismantle(it, SerpentineObjs.helm) }
    }

    private fun ScriptContext.onHeldOp(
        type: ObjType,
        label: String,
        action: suspend ProtectedAccess.(Int) -> Unit,
    ) {
        when (objTypes[type].iop.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> if (!isHeldOpBound(type, 1)) onOpHeld1(type) { action(it.slot) }
            2 -> if (!isHeldOpBound(type, 2)) onOpHeld2(type) { action(it.slot) }
            3 -> if (!isHeldOpBound(type, 3)) onOpHeld3(type) { action(it.slot) }
            4 -> if (!isHeldOpBound(type, 4)) onOpHeld4(type) { action(it.slot) }
            5 -> if (!isHeldOpBound(type, 5)) onOpHeld5(type) { action(it.slot) }
            else -> logger.warn { "Serpentine: ${objTypes[type].name} has no \"$label\" op." }
        }
    }

    private fun ProtectedAccess.chiselVisage() {
        if (player.craftingLvl < HELM_LEVEL) {
            mes("You need a Crafting level of $HELM_LEVEL to make a serpentine helm.")
            return
        }
        invDel(inv, SerpentineObjs.visage)
        invAdd(inv, SerpentineObjs.helm)
        statAdvance(stats.crafting, HELM_XP * xpMods.get(player, stats.crafting))
        mes("You carefully cut the serpentine visage into a helmet.")
    }

    private fun ProtectedAccess.scalesIn(slot: Int): Int =
        charges.getCharges(inv[slot], varobjs.charges_16383)

    private fun ProtectedAccess.charge(slot: Int) {
        val helm = inv[slot] ?: return
        val current = if (helm.isType(SerpentineObjs.helm_charged)) scalesIn(slot) else 0
        if (current >= MAX_SCALES) {
            mes("Your serpentine helm is already fully charged.")
            return
        }
        val add = min(MAX_SCALES - current, invTotal(inv, SerpentineObjs.scales))
        if (add <= 0) {
            return
        }
        invDel(inv, SerpentineObjs.scales, add)
        inv[slot] = InvObj(SerpentineObjs.helm_charged, vars = current + add)
        mes("You add ${add.formatAmount} scales to the helm. ${scalesMessage(current + add)}")
    }

    private fun ProtectedAccess.check(slot: Int) {
        mes(scalesMessage(scalesIn(slot)))
    }

    private suspend fun ProtectedAccess.uncharge(slot: Int) {
        val scales = scalesIn(slot)
        val confirm =
            choice2(
                "Yes.",
                true,
                "No.",
                false,
                title = "Uncharge the helm? All the scales will fall off.",
            )
        if (!confirm || !inv[slot].isType(SerpentineObjs.helm_charged)) {
            return
        }
        inv[slot] = InvObj(SerpentineObjs.helm)
        if (scales > 0) {
            invAddOrDrop(objRepo, SerpentineObjs.scales, scales)
        }
        mes("You remove ${scales.formatAmount} scales from the helm.")
    }

    private suspend fun ProtectedAccess.dismantle(slot: Int, type: ObjType) {
        val confirm =
            choice2(
                "Yes.",
                true,
                "No.",
                false,
                title = "Dismantle it into ${DISMANTLE_SCALES.formatAmount} Zulrah's scales?",
            )
        if (!confirm || !inv[slot].isType(type)) {
            return
        }
        invDel(inv, type, slot = slot)
        invAddOrDrop(objRepo, SerpentineObjs.scales, DISMANTLE_SCALES)
        mes("You dismantle it into ${DISMANTLE_SCALES.formatAmount} Zulrah's scales.")
    }

    private fun scalesMessage(scales: Int): String {
        val percent = scales * 1000 / MAX_SCALES
        return "Scales: ${scales.formatAmount} (${percent / 10}.${percent % 10}%)"
    }

    private companion object {
        const val HELM_LEVEL = 52
        const val HELM_XP = 120.0
        const val MAX_SCALES = 11_000
        const val DISMANTLE_SCALES = 20_000
    }
}

internal object SerpentineObjs : ObjReferences() {
    val chisel = find("chisel")
    val visage = find("serpentine_visage")
    val helm = find("serpentine_helm")
    val helm_charged = find("serpentine_helm_charged")
    val scales = find("snakeboss_scale")
}
