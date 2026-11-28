package org.rsmod.content.skills.smithing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.skills.smithing.configs.AnvilObjs
import org.rsmod.content.skills.smithing.configs.SmithingSeqs
import org.rsmod.content.skills.smithing.configs.smithing_objs
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Smithable pickaxes - a local house rule (Ryan; OSRS has no pickaxe on the anvil): use a hammer on
 * any anvil to pick a pickaxe, bronze through rune, from the skillmulti chatbox. Each costs 2 bars
 * at the Smithing level of that metal's axe, with 2 bars' xp. One every [SMITH_TICKS] ticks until
 * the chosen amount is made or the bars run out (moving or clicking elsewhere stops it).
 *
 * The specific hammer-on-anvil binding takes precedence over Smithing's generic use-item handler,
 * so bars on the anvil still open the normal smithing interface.
 */
class Pickaxes
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var bound = 0
        for (type in locTypes.values) {
            if (!type.name.contains("anvil", ignoreCase = true)) {
                continue
            }
            if (type.op.none { it.equals("Smith", ignoreCase = true) }) {
                continue
            }
            onOpLocU(type, smithing_objs.hammer) { choosePickaxe() }
            bound++
        }
        logger.info { "Smithing: hammer-on-anvil pickaxes bound on $bound anvil locs." }
    }

    private suspend fun ProtectedAccess.choosePickaxe() {
        val products =
            Pickaxe.entries.map {
                val available = player.smithingLvl >= it.level && invTotal(inv, it.bar()) >= BARS
                SkillMultiProduct(it.pickaxe(), objTypes[it.pickaxe()].name, available)
            }
        val max = Pickaxe.entries.maxOf { invTotal(inv, it.bar()) / BARS }.coerceAtLeast(1)
        val choice =
            skillMulti(
                verb = SkillMultiVerb.SMITH,
                title = "What would you like to smith?",
                products = products,
                maxQuantity = max,
            )
        val pickaxe = Pickaxe.entries[choice.index]
        repeat(choice.quantity) { made ->
            if (!smith(pickaxe)) {
                return
            }
            if (made < choice.quantity - 1) {
                delay(SMITH_TICKS)
            }
        }
    }

    private fun ProtectedAccess.smith(pickaxe: Pickaxe): Boolean {
        val name = objTypes[pickaxe.pickaxe()].name
        if (player.smithingLvl < pickaxe.level) {
            mes("You need a Smithing level of ${pickaxe.level} to make a ${name.lowercase()}.")
            return false
        }
        if (!player.hasTool(smithing_objs.hammer)) {
            mes("You need a hammer to work the metal with.")
            return false
        }
        if (invTotal(inv, pickaxe.bar()) < BARS) {
            mes("You need $BARS ${pickaxe.metal} bars to make a ${name.lowercase()}.")
            return false
        }
        anim(SmithingSeqs.smithing)
        invDel(inv, pickaxe.bar(), BARS)
        invAdd(inv, pickaxe.pickaxe())
        statAdvance(stats.smithing, pickaxe.xpPerBar * BARS * xpMods.get(player, stats.smithing))
        spam("You hammer the ${pickaxe.metal} and make a ${name.lowercase()}.")
        return true
    }

    /** Level = that metal's axe (OSRS Wiki Smithing); xp per bar as the smithing interface. */
    private enum class Pickaxe(
        val metal: String,
        val level: Int,
        val xpPerBar: Double,
        val bar: () -> ObjType,
        val pickaxe: () -> ObjType,
    ) {
        Bronze("bronze", 1, 12.5, { AnvilObjs.bronze_bar }, { PickaxeObjs.bronze_pickaxe }),
        Iron("iron", 16, 25.0, { AnvilObjs.iron_bar }, { PickaxeObjs.iron_pickaxe }),
        Steel("steel", 31, 37.5, { AnvilObjs.steel_bar }, { PickaxeObjs.steel_pickaxe }),
        Mithril("mithril", 51, 50.0, { AnvilObjs.mithril_bar }, { PickaxeObjs.mithril_pickaxe }),
        Adamant("adamant", 71, 62.5, { AnvilObjs.adamantite_bar }, { PickaxeObjs.adamant_pickaxe }),
        Rune("rune", 86, 75.0, { AnvilObjs.runite_bar }, { PickaxeObjs.rune_pickaxe }),
    }

    private companion object {
        const val BARS = 2
        const val SMITH_TICKS = 5
    }
}

/** Top level so the type-reference scan finds it like the other config objects. */
internal object PickaxeObjs : ObjReferences() {
    val bronze_pickaxe = find("bronze_pickaxe")
    val iron_pickaxe = find("iron_pickaxe")
    val steel_pickaxe = find("steel_pickaxe")
    val mithril_pickaxe = find("mithril_pickaxe")
    val adamant_pickaxe = find("adamant_pickaxe")
    val rune_pickaxe = find("rune_pickaxe")
}
