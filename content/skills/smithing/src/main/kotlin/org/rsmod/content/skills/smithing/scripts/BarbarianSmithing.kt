package org.rsmod.content.skills.smithing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.skills.smithing.configs.smithing_objs
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Barbarian Training smithing (OSRS Wiki Barbarian Training, Barbarian anvil): spears and hastae,
 * bronze to rune, from one bar and one matching log (normal, oak, willow, maple, yew, magic) with a
 * hammer, on a barbarian anvil only (Otto's Grotto and the Barbarian Village pottery). Spear and
 * hasta share level and xp: 5/25, 20/50, 35/75, 55/100, 75/125, 90/150. Spears need Otto's spear
 * lesson (`brut_smith_spear` >= 1) and hastae his hasta lesson (`brut_smith_hasta` >= 1); the first
 * one made during a lesson completes it (1 -> 2). Click the anvil or use a bar or logs on it.
 *
 * Not done: Tai Bwo Wannai Trio (not on this server) is not required for the spear lesson.
 */
class BarbarianSmithing
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var bound = 0
        for (type in locTypes.values) {
            if (!type.name.equals(ANVIL_NAME, ignoreCase = true)) continue
            if (type.op.firstOrNull()?.equals("Smith", ignoreCase = true) != true) continue
            onOpLoc1(type) { openAnvil(null) }
            onOpLocU(type) { openAnvil(it.objType) }
            bound++
        }
        logger.info { "Smithing: bound $bound barbarian anvil locs." }
    }

    private fun ProtectedAccess.knowsSpears(): Boolean =
        player.vars[BarbarianSmithVarBits.smith_spear] >= LESSON_TAUGHT

    private fun ProtectedAccess.knowsHastae(): Boolean =
        player.vars[BarbarianSmithVarBits.smith_hasta] >= LESSON_TAUGHT

    private suspend fun ProtectedAccess.openAnvil(used: ObjType?) {
        if (!knowsSpears() && !knowsHastae()) {
            mes("This anvil is unfamiliar and barbaric, you can't seem to use it.")
            return
        }
        val metal =
            if (used != null) {
                Metal.entries.firstOrNull { it.bar().id == used.id || it.logs().id == used.id }
                    ?: run {
                        mes("Nothing interesting happens.")
                        return
                    }
            } else {
                // Clicking the anvil picks the best metal the player has a bar and logs for.
                Metal.entries.reversed().firstOrNull { has(it) && player.smithingLvl >= it.level }
                    ?: Metal.entries.firstOrNull { has(it) }
            }
        if (metal == null) {
            mes("You need a metal bar and some matching logs to make a spear here.") // Authored (not Wiki)
            return
        }
        if (!player.hasTool(smithing_objs.hammer)) {
            mesbox("You need a hammer to work the metal with.")
            return
        }
        if (!has(metal)) {
            mes("You need ${metal.metal} bars and ${objTypes[metal.logs()].name.lowercase()} to make that.") // Authored (not Wiki)
            return
        }
        val available = player.smithingLvl >= metal.level
        val products =
            buildList {
                if (knowsSpears()) add(metal.spear())
                if (knowsHastae()) add(metal.hasta())
            }
        val choice =
            skillMulti(
                verb = SkillMultiVerb.SMITH,
                title = "What would you like to make?",
                products = products.map { SkillMultiProduct(it, objTypes[it].name, available) },
                maxQuantity = maxMakes(metal).coerceAtLeast(1),
            )
        val product = products[choice.index]
        var remaining = choice.quantity
        while (remaining > 0) {
            if (!smith(metal, product)) return
            remaining--
            if (remaining > 0 && maxMakes(metal) > 0) delay(SMITH_TICKS) else return
        }
    }

    private fun ProtectedAccess.has(metal: Metal): Boolean = maxMakes(metal) > 0

    private fun ProtectedAccess.maxMakes(metal: Metal): Int =
        min(invTotal(inv, metal.bar()), invTotal(inv, metal.logs()))

    private fun ProtectedAccess.smith(metal: Metal, product: ObjType): Boolean {
        val name = objTypes[product].name.lowercase()
        if (player.smithingLvl < metal.level) {
            mes("You need a Smithing level of ${metal.level} to make ${name.withArticle()}.")
            return false
        }
        if (!player.hasTool(smithing_objs.hammer) || !has(metal)) {
            return false
        }
        anim(BarbarianSmithSeqs.smithing)
        invDel(inv, metal.bar())
        invDel(inv, metal.logs())
        invAdd(inv, product)
        statAdvance(stats.smithing, metal.xp * xpMods.get(player, stats.smithing))
        spam("You make ${name.withArticle()}.") // Authored (not Wiki)
        val lesson =
            if (product.id == metal.spear().id) BarbarianSmithVarBits.smith_spear
            else BarbarianSmithVarBits.smith_hasta
        if (player.vars[lesson] == LESSON_TAUGHT) {
            VarPlayerIntMapSetter.set(player, lesson, LESSON_DONE)
            mes("You feel you have learned more of barbarian ways. Otto might wish to talk to you more.")
        }
        return true
    }

    private fun String.withArticle(): String {
        val article = if (first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $this"
    }

    private enum class Metal(
        val metal: String,
        val level: Int,
        val xp: Double,
        val bar: () -> ObjType,
        val logs: () -> ObjType,
        val spear: () -> ObjType,
        val hasta: () -> ObjType,
    ) {
        Bronze("bronze", 5, 25.0, { smithing_objs.bronze_bar }, { BarbarianSmithObjs.logs }, { BarbarianSmithObjs.bronze_spear }, { BarbarianSmithObjs.bronze_hasta }),
        Iron("iron", 20, 50.0, { smithing_objs.iron_bar }, { BarbarianSmithObjs.oak_logs }, { BarbarianSmithObjs.iron_spear }, { BarbarianSmithObjs.iron_hasta }),
        Steel("steel", 35, 75.0, { smithing_objs.steel_bar }, { BarbarianSmithObjs.willow_logs }, { BarbarianSmithObjs.steel_spear }, { BarbarianSmithObjs.steel_hasta }),
        Mithril("mithril", 55, 100.0, { smithing_objs.mithril_bar }, { BarbarianSmithObjs.maple_logs }, { BarbarianSmithObjs.mithril_spear }, { BarbarianSmithObjs.mithril_hasta }),
        Adamant("adamantite", 75, 125.0, { smithing_objs.adamantite_bar }, { BarbarianSmithObjs.yew_logs }, { BarbarianSmithObjs.adamant_spear }, { BarbarianSmithObjs.adamant_hasta }),
        Rune("runite", 90, 150.0, { smithing_objs.runite_bar }, { BarbarianSmithObjs.magic_logs }, { BarbarianSmithObjs.rune_spear }, { BarbarianSmithObjs.rune_hasta }),
    }

    companion object {
        /** Loc name of the barbarian anvils (excluded from the other anvil scripts). */
        const val ANVIL_NAME = "Barbarian anvil"
        private const val LESSON_TAUGHT = 1
        private const val LESSON_DONE = 2
        private const val SMITH_TICKS = 5
    }
}

internal object BarbarianSmithObjs : ObjReferences() {
    val logs = find("logs")
    val oak_logs = find("oak_logs")
    val willow_logs = find("willow_logs")
    val maple_logs = find("maple_logs")
    val yew_logs = find("yew_logs")
    val magic_logs = find("magic_logs")

    val bronze_spear = find("bronze_spear")
    val iron_spear = find("iron_spear")
    val steel_spear = find("steel_spear")
    val mithril_spear = find("mithril_spear")
    val adamant_spear = find("adamant_spear")
    val rune_spear = find("rune_spear")

    // Hastae are the cache's brut_*_spear objs (Wiki Bronze hasta id 11367).
    val bronze_hasta = find("brut_bronze_spear")
    val iron_hasta = find("brut_iron_spear")
    val steel_hasta = find("brut_steel_spear")
    val mithril_hasta = find("brut_mithril_spear")
    val adamant_hasta = find("brut_adamant_spear")
    val rune_hasta = find("brut_rune_spear")
}

internal object BarbarianSmithSeqs : SeqReferences() {
    val smithing = find("brut_human_smithing")
}

/**
 * Barbarian Training smithing lessons (cache varbits in `brut_base_1`): 0 untaught, 1 taught by
 * Otto, 2 one made, 3 reported to Otto.
 */
internal object BarbarianSmithVarBits : VarBitReferences() {
    val smith_spear = find("brut_smith_spear")
    val smith_hasta = find("brut_smith_hasta")
}
