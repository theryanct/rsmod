package org.rsmod.content.quests.lostcity

import jakarta.inject.Inject
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Zanaris extras unlocked by Lost City: the Chicken Shrine takes a raw chicken to Evil Chicken's
 * Lair (an egg does nothing), whose portal leads back to the shrine. Jukat's dragon swords and
 * Irksol's rings are generic shops; the cosmic altar's Lost City check is in Runecraft.
 *
 * Not done: dragon dagger/longsword wield requirements (Lost City), the lair's rope to the baby
 * black dragon.
 */
class Zanaris @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLocU(ZanarisLocs.chicken_shrine, ZanarisObjs.raw_chicken) {
            invDel(inv, ZanarisObjs.raw_chicken)
            mes("You place the raw chicken on the shrine...")
            mes("...and are whisked away to the Evil Chicken's Lair!")
            telejump(LAIR)
        }
        onOpLocU(ZanarisLocs.chicken_shrine, ZanarisObjs.egg) {
            mes("Nice idea, but nothing interesting happens.")
        }
        onLocOpLabel(locTypes, ZanarisLocs.lair_portal, "Use") { telejump(SHRINE) }
    }

    private companion object {
        val LAIR = CoordGrid(2459, 4357, 0)
        val SHRINE = CoordGrid(2453, 4475, 0)
    }
}

internal object ZanarisLocs : LocReferences() {
    val chicken_shrine = find("fairy_chicken_shrine")
    val lair_portal = find("100_portal_chicken")
}

internal object ZanarisObjs : ObjReferences() {
    val raw_chicken = find("raw_chicken")
    val egg = find("egg")
}
