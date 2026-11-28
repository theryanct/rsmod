package org.rsmod.content.skills.cooking.scripts

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Picking nettles (OSRS Wiki "Nettles"): any gloves worn (not beekeeper's gloves) give nettles
 * for nettle-water / nettle tea; without gloves the nettles sting instead.
 */
class NettlePicking @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for (type in NettleLocs.all) {
            if (!isLocOpBound(type, 1)) onOpLoc1(type) { pick() }
        }
    }

    private fun ProtectedAccess.pick() {
        val hands = player.worn[Wearpos.Hands.slot]
        val gloves =
            hands != null &&
                hands.id != objTypes[NettleObjs.beekeeper_gloves].id &&
                objTypes[hands].name.contains(Regex("glove|gauntlet|vamb", RegexOption.IGNORE_CASE))
        if (!gloves) {
            mes("You have been stung by the nettles!")
            queueHit(1, HitType.Typeless, STING_DAMAGE)
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        invAdd(inv, NettleObjs.nettles)
        mes("You pick a handful of nettles.")
    }

    private companion object {
        const val STING_DAMAGE = 2
    }
}

internal object NettleLocs : LocReferences() {
    val all = listOf(find("nettles"), find("nettles1"), find("nettles2"), find("nettles3"), find("nettles4"))
}

internal object NettleObjs : ObjReferences() {
    val nettles = find("nettles_picked")
    val beekeeper_gloves = find("beekeeper_gloves")
}
