package org.rsmod.content.skills.smithing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.smithing.configs.AnvilObjs
import org.rsmod.content.skills.smithing.configs.AnvilSlots
import org.rsmod.content.skills.smithing.configs.SmithingEnums
import org.rsmod.content.skills.smithing.configs.SmithingInterfaces
import org.rsmod.content.skills.smithing.configs.SmithingQueues
import org.rsmod.content.skills.smithing.configs.SmithingSeqs
import org.rsmod.content.skills.smithing.configs.SmithingVarBits
import org.rsmod.content.skills.smithing.configs.SmithingVarps
import org.rsmod.content.skills.smithing.configs.smithing_components
import org.rsmod.content.skills.smithing.configs.smithing_objs
import org.rsmod.game.entity.Player
import org.rsmod.game.enums.EnumTypeMapResolver
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Anvil smithing through the OSRS smithing interface for bronze through rune: every slot the rev 233
 * client shows, including multi-output sets (nails, arrowtips, dart tips, bolts, knives, javelin
 * heads) and the "other" slots (wire, spit, studs, lamp frames, grapple tip, limbs).
 *
 * Bars and level per product come from the cache enums the client also uses
 * (`smithing_product_to_bars_required` / `smithing_product_to_requirement`), and the number made per
 * action from `smithing_product_to_quantity`; xp is bars x the bar's xp. Quantity (actions) comes
 * from the skillmain buttons (`makexcrafting`). One action every [SMITH_TICKS] ticks from a weak
 * queue.
 */
class Smithing
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val enumResolver: EnumTypeMapResolver,
    private val xpMods: XpModifiers,
) : PluginScript() {
    /** Every interface slot any bar uses (27 in rev 233). */
    private val productComponents: List<ComponentType> by lazy {
        Metal.entries.flatMap { metal -> metal.products().map { it.first } }.distinctBy { it.id }
    }

    override fun ScriptContext.startup() {
        // Bind every loc named "Anvil" by the slot of its "Smith" op.
        var bound = 0
        for (type in locTypes.values) {
            // Any anvil variant (e.g. "Rusted anvil") with a "Smith" op.
            if (!type.name.contains("anvil", ignoreCase = true)) {
                continue
            }
            // Barbarian anvils only make spears and hastae (BarbarianSmithing).
            if (type.name.equals(BarbarianSmithing.ANVIL_NAME, ignoreCase = true)) {
                continue
            }
            val slot = type.op.indexOfFirst { it.equals("Smith", ignoreCase = true) }
            if (slot == -1) {
                continue
            }
            onOpLocSlot(type, slot + 1) { openAnvil(null) }
            onOpLocU(type) { openAnvil(it.objType) }
            bound++
        }
        logger.info { "Smithing: bound $bound anvil locs." }

        onIfModalButton(smithing_components.make_1) { player.pickMakeX(1) }
        onIfModalButton(smithing_components.make_5) { player.pickMakeX(5) }
        onIfModalButton(smithing_components.make_10) { player.pickMakeX(10) }
        onIfModalButton(smithing_components.make_all) { player.pickMakeX(MAKE_ALL) }
        onIfModalButton(smithing_components.make_x) { promptMakeX() }

        for (component in productComponents) {
            onIfModalButton(component) { selectProduct(component) }
        }

        onPlayerQueueWithArgs<SmithJob>(SmithingQueues.smithing) { smith(it.args) }
    }

    private suspend fun ProtectedAccess.openAnvil(usedObj: ObjType?) {
        if (!player.hasTool(smithing_objs.hammer)) {
            mesbox("You need a hammer to work the metal with.")
            return
        }
        val metal =
            if (usedObj != null) {
                Metal.entries.firstOrNull { it.bar().id == usedObj.id }
            } else {
                // Clicking the anvil picks the best bar the player carries and can work.
                Metal.entries.reversed().firstOrNull {
                    invTotal(inv, it.bar()) > 0 && player.smithingLvl >= it.baseLevel
                } ?: Metal.entries.firstOrNull { invTotal(inv, it.bar()) > 0 }
            }
        if (metal == null) {
            mesbox("You should select an item from your inventory and use it on the anvil.")
            return
        }
        val barTypeIndex = barTypeIndex(metal) ?: return
        VarPlayerIntMapSetter.set(player, SmithingVarBits.bar_type, barTypeIndex)
        // Default to "All" until the player picks a quantity this session (Ryan).
        if (player !in pickedMakeX) {
            player.setMakeX(MAKE_ALL)
        }
        ifOpenMainModal(SmithingInterfaces.smithing)
    }

    /**
     * OSRS X: the client has already set `makexcrafting` to 0 locally (X shows "?"), and
     * `skillmain_setup` draws any value other than 1/5/10/0/all in the single custom-quantity slot
     * (`make_some`) next to X. Mirror the 0 first so the entered value always transmits, even when it
     * equals the previous custom value, and restore the previous value if the dialog is cancelled.
     */
    private suspend fun ProtectedAccess.promptMakeX() {
        val previous = player.vars[SmithingVarps.makex]
        player.setMakeX(0)
        val count = countDialog()
        player.setMakeX(if (count > 0) count else previous)
        if (count > 0) {
            pickedMakeX[player] = true
        }
    }

    /** Players who chose an anvil quantity this session (weak keys drop on logout). */
    private val pickedMakeX = WeakHashMap<Player, Boolean>()

    private fun Player.pickMakeX(quantity: Int) {
        pickedMakeX[this] = true
        setMakeX(quantity)
    }

    private suspend fun ProtectedAccess.selectProduct(component: ComponentType) {
        val metal = currentMetal(player) ?: return
        val product =
            metal.products().firstOrNull { it.first.id == component.id }?.second ?: return
        val quantity = player.vars[SmithingVarps.makex].takeIf { it > 0 } ?: 1
        ifClose()
        smith(SmithJob(metal, product, quantity))
    }

    private suspend fun ProtectedAccess.smith(job: SmithJob) {
        val (metal, product) = job
        val productType = objTypes[product]
        val bars = barsRequired(product)
        val level = levelRequired(product)
        if (player.smithingLvl < level) {
            mesbox("You need a Smithing level of $level to make ${productType.name.withArticle()}.")
            return
        }
        if (!player.hasTool(smithing_objs.hammer)) {
            mesbox("You need a hammer to work the metal with.")
            return
        }
        if (invTotal(inv, metal.bar()) < bars) {
            val noun = if (bars == 1) "bar" else "bars"
            val item = productType.name.withArticle()
            mesbox("You don't have enough ${metal.metal} $noun to make $item.")
            return
        }

        val count = outputCount(product)
        // No space check: every bar used frees an inventory slot, and multi-output sets stack.
        anim(SmithingSeqs.smithing)
        invDel(inv, metal.bar(), bars)
        invAdd(inv, product, count)
        statAdvance(stats.smithing, metal.xpPerBar * bars * xpMods.get(player, stats.smithing))
        val made =
            if (count > 1) "some ${productType.name.lowercase()}"
            else productType.name.lowercase().withArticle()
        spam("You hammer the ${metal.metal} and make $made.")

        val remaining = job.remaining - 1
        if (remaining > 0 && invTotal(inv, metal.bar()) >= bars) {
            weakQueue(SmithingQueues.smithing, SMITH_TICKS, SmithJob(metal, product, remaining))
        }
    }

    private fun Player.setMakeX(quantity: Int) {
        VarPlayerIntMapSetter.set(this, SmithingVarps.makex, quantity)
    }

    private fun currentMetal(player: Player): Metal? {
        val index = player.vars[SmithingVarBits.bar_type]
        val bar = enumResolver[SmithingEnums.bar_types].getOrNull(index) ?: return null
        return Metal.entries.firstOrNull { it.bar().id == bar.id }
    }

    private fun barTypeIndex(metal: Metal): Int? =
        enumResolver[SmithingEnums.bar_types].firstOrNull { it.value?.id == metal.bar().id }?.key

    // Obj keys are compared by id: reference and cache obj types are different classes.
    private fun barsRequired(product: ObjType): Int =
        enumResolver[SmithingEnums.bars_required].firstOrNull { it.key.id == product.id }?.value
            ?: 1

    private fun outputCount(product: ObjType): Int =
        enumResolver[SmithingEnums.quantity].firstOrNull { it.key.id == product.id }?.value ?: 1

    private fun levelRequired(product: ObjType): Int =
        enumResolver[SmithingEnums.level_required].firstOrNull { it.key.id == product.id }?.value
            ?: 1

    private fun String.withArticle(): String {
        val article = if (first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $this"
    }

    private data class SmithJob(val metal: Metal, val product: ObjType, val remaining: Int)

    /**
     * Anvil products per bar: interface slot -> obj, exactly as the rev 233 client lays them out
     * ([AnvilSlots]/[AnvilObjs]). XP is bars x the bar's xp (OSRS Wiki: bronze 12.5, iron 25, steel
     * 37.5, mithril 50, adamant 62.5, rune 75), for multi-output sets too.
     */
    private enum class Metal(
        val metal: String,
        val baseLevel: Int,
        val xpPerBar: Double,
        val bar: () -> ObjType,
        val products: () -> List<Pair<ComponentType, ObjType>>,
    ) {
        Bronze(
            "bronze",
            baseLevel = 1,
            xpPerBar = 12.5,
            bar = { AnvilObjs.bronze_bar },
            products = {
                listOf(
                    AnvilSlots.dagger to AnvilObjs.bronze_dagger,
                    AnvilSlots.sword to AnvilObjs.bronze_sword,
                    AnvilSlots.scimitar to AnvilObjs.bronze_scimitar,
                    AnvilSlots.longsword to AnvilObjs.bronze_longsword,
                    AnvilSlots.twohand to AnvilObjs.bronze_2h_sword,
                    AnvilSlots.axe to AnvilObjs.bronze_axe,
                    AnvilSlots.mace to AnvilObjs.bronze_mace,
                    AnvilSlots.warhammer to AnvilObjs.bronze_warhammer,
                    AnvilSlots.battleaxe to AnvilObjs.bronze_battleaxe,
                    AnvilSlots.claws to AnvilObjs.bronze_claws,
                    AnvilSlots.chainbody to AnvilObjs.bronze_chainbody,
                    AnvilSlots.platelegs to AnvilObjs.bronze_platelegs,
                    AnvilSlots.plateskirt to AnvilObjs.bronze_plateskirt,
                    AnvilSlots.platebody to AnvilObjs.bronze_platebody,
                    AnvilSlots.nails to AnvilObjs.nails_bronze,
                    AnvilSlots.medhelm to AnvilObjs.bronze_med_helm,
                    AnvilSlots.fullhelm to AnvilObjs.bronze_full_helm,
                    AnvilSlots.squareshield to AnvilObjs.bronze_sq_shield,
                    AnvilSlots.kiteshield to AnvilObjs.bronze_kiteshield,
                    AnvilSlots.darttips to AnvilObjs.bronze_dart_tip,
                    AnvilSlots.arrowheads to AnvilObjs.bronze_arrowheads,
                    AnvilSlots.knives to AnvilObjs.bronze_knife,
                    AnvilSlots.other_1 to AnvilObjs.bronzecraftwire,
                    AnvilSlots.bolts to AnvilObjs.xbows_crossbow_bolts_bronze_unfeathered,
                    AnvilSlots.limbs to AnvilObjs.xbows_crossbow_limbs_bronze,
                    AnvilSlots.other_3 to AnvilObjs.bronze_javelin_head,
                )
            },
        ),
        Iron(
            "iron",
            baseLevel = 15,
            xpPerBar = 25.0,
            bar = { AnvilObjs.iron_bar },
            products = {
                listOf(
                    AnvilSlots.dagger to AnvilObjs.iron_dagger,
                    AnvilSlots.sword to AnvilObjs.iron_sword,
                    AnvilSlots.scimitar to AnvilObjs.iron_scimitar,
                    AnvilSlots.longsword to AnvilObjs.iron_longsword,
                    AnvilSlots.twohand to AnvilObjs.iron_2h_sword,
                    AnvilSlots.axe to AnvilObjs.iron_axe,
                    AnvilSlots.mace to AnvilObjs.iron_mace,
                    AnvilSlots.warhammer to AnvilObjs.iron_warhammer,
                    AnvilSlots.battleaxe to AnvilObjs.iron_battleaxe,
                    AnvilSlots.claws to AnvilObjs.iron_claws,
                    AnvilSlots.chainbody to AnvilObjs.iron_chainbody,
                    AnvilSlots.platelegs to AnvilObjs.iron_platelegs,
                    AnvilSlots.plateskirt to AnvilObjs.iron_plateskirt,
                    AnvilSlots.platebody to AnvilObjs.iron_platebody,
                    AnvilSlots.nails to AnvilObjs.nails_iron,
                    AnvilSlots.medhelm to AnvilObjs.iron_med_helm,
                    AnvilSlots.fullhelm to AnvilObjs.iron_full_helm,
                    AnvilSlots.squareshield to AnvilObjs.iron_sq_shield,
                    AnvilSlots.kiteshield to AnvilObjs.iron_kiteshield,
                    AnvilSlots.other_2 to AnvilObjs.oil_lantern_frame,
                    AnvilSlots.darttips to AnvilObjs.iron_dart_tip,
                    AnvilSlots.arrowheads to AnvilObjs.iron_arrowheads,
                    AnvilSlots.knives to AnvilObjs.iron_knife,
                    AnvilSlots.other_1 to AnvilObjs.spit_iron,
                    AnvilSlots.bolts to AnvilObjs.xbows_crossbow_bolts_iron_unfeathered,
                    AnvilSlots.limbs to AnvilObjs.xbows_crossbow_limbs_iron,
                    AnvilSlots.other_3 to AnvilObjs.iron_javelin_head,
                )
            },
        ),
        Steel(
            "steel",
            baseLevel = 30,
            xpPerBar = 37.5,
            bar = { AnvilObjs.steel_bar },
            products = {
                listOf(
                    AnvilSlots.dagger to AnvilObjs.steel_dagger,
                    AnvilSlots.sword to AnvilObjs.steel_sword,
                    AnvilSlots.scimitar to AnvilObjs.steel_scimitar,
                    AnvilSlots.longsword to AnvilObjs.steel_longsword,
                    AnvilSlots.twohand to AnvilObjs.steel_2h_sword,
                    AnvilSlots.axe to AnvilObjs.steel_axe,
                    AnvilSlots.mace to AnvilObjs.steel_mace,
                    AnvilSlots.warhammer to AnvilObjs.steel_warhammer,
                    AnvilSlots.battleaxe to AnvilObjs.steel_battleaxe,
                    AnvilSlots.claws to AnvilObjs.steel_claws,
                    AnvilSlots.chainbody to AnvilObjs.steel_chainbody,
                    AnvilSlots.platelegs to AnvilObjs.steel_platelegs,
                    AnvilSlots.plateskirt to AnvilObjs.steel_plateskirt,
                    AnvilSlots.platebody to AnvilObjs.steel_platebody,
                    AnvilSlots.nails to AnvilObjs.nails,
                    AnvilSlots.medhelm to AnvilObjs.steel_med_helm,
                    AnvilSlots.fullhelm to AnvilObjs.steel_full_helm,
                    AnvilSlots.squareshield to AnvilObjs.steel_sq_shield,
                    AnvilSlots.kiteshield to AnvilObjs.steel_kiteshield,
                    AnvilSlots.other_2 to AnvilObjs.bullseye_lantern_nolens,
                    AnvilSlots.darttips to AnvilObjs.steel_dart_tip,
                    AnvilSlots.arrowheads to AnvilObjs.steel_arrowheads,
                    AnvilSlots.knives to AnvilObjs.steel_knife,
                    AnvilSlots.other_1 to AnvilObjs.studs,
                    AnvilSlots.bolts to AnvilObjs.xbows_crossbow_bolts_steel_unfeathered,
                    AnvilSlots.limbs to AnvilObjs.xbows_crossbow_limbs_steel,
                    AnvilSlots.other_3 to AnvilObjs.steel_javelin_head,
                )
            },
        ),
        Mithril(
            "mithril",
            baseLevel = 50,
            xpPerBar = 50.0,
            bar = { AnvilObjs.mithril_bar },
            products = {
                listOf(
                    AnvilSlots.dagger to AnvilObjs.mithril_dagger,
                    AnvilSlots.sword to AnvilObjs.mithril_sword,
                    AnvilSlots.scimitar to AnvilObjs.mithril_scimitar,
                    AnvilSlots.longsword to AnvilObjs.mithril_longsword,
                    AnvilSlots.twohand to AnvilObjs.mithril_2h_sword,
                    AnvilSlots.axe to AnvilObjs.mithril_axe,
                    AnvilSlots.mace to AnvilObjs.mithril_mace,
                    AnvilSlots.warhammer to AnvilObjs.mithril_warhammer,
                    AnvilSlots.battleaxe to AnvilObjs.mithril_battleaxe,
                    AnvilSlots.claws to AnvilObjs.mithril_claws,
                    AnvilSlots.chainbody to AnvilObjs.mithril_chainbody,
                    AnvilSlots.platelegs to AnvilObjs.mithril_platelegs,
                    AnvilSlots.plateskirt to AnvilObjs.mithril_plateskirt,
                    AnvilSlots.platebody to AnvilObjs.mithril_platebody,
                    AnvilSlots.nails to AnvilObjs.nails_mithril,
                    AnvilSlots.medhelm to AnvilObjs.mithril_med_helm,
                    AnvilSlots.fullhelm to AnvilObjs.mithril_full_helm,
                    AnvilSlots.squareshield to AnvilObjs.mithril_sq_shield,
                    AnvilSlots.kiteshield to AnvilObjs.mithril_kiteshield,
                    AnvilSlots.darttips to AnvilObjs.mithril_dart_tip,
                    AnvilSlots.arrowheads to AnvilObjs.mithril_arrowheads,
                    AnvilSlots.knives to AnvilObjs.mithril_knife,
                    AnvilSlots.bolts to AnvilObjs.xbows_crossbow_bolts_mithril_unfeathered,
                    AnvilSlots.limbs to AnvilObjs.xbows_crossbow_limbs_mithril,
                    AnvilSlots.other_3 to AnvilObjs.mithril_javelin_head,
                    AnvilSlots.other_1 to AnvilObjs.xbows_grapple_tip_mithril,
                )
            },
        ),
        Adamant(
            "adamant",
            baseLevel = 70,
            xpPerBar = 62.5,
            bar = { AnvilObjs.adamantite_bar },
            products = {
                listOf(
                    AnvilSlots.dagger to AnvilObjs.adamant_dagger,
                    AnvilSlots.sword to AnvilObjs.adamant_sword,
                    AnvilSlots.scimitar to AnvilObjs.adamant_scimitar,
                    AnvilSlots.longsword to AnvilObjs.adamant_longsword,
                    AnvilSlots.twohand to AnvilObjs.adamant_2h_sword,
                    AnvilSlots.axe to AnvilObjs.adamant_axe,
                    AnvilSlots.mace to AnvilObjs.adamant_mace,
                    AnvilSlots.warhammer to AnvilObjs.adamnt_warhammer,
                    AnvilSlots.battleaxe to AnvilObjs.adamant_battleaxe,
                    AnvilSlots.claws to AnvilObjs.adamant_claws,
                    AnvilSlots.chainbody to AnvilObjs.adamant_chainbody,
                    AnvilSlots.platelegs to AnvilObjs.adamant_platelegs,
                    AnvilSlots.plateskirt to AnvilObjs.adamant_plateskirt,
                    AnvilSlots.platebody to AnvilObjs.adamant_platebody,
                    AnvilSlots.nails to AnvilObjs.nails_adamant,
                    AnvilSlots.medhelm to AnvilObjs.adamant_med_helm,
                    AnvilSlots.fullhelm to AnvilObjs.adamant_full_helm,
                    AnvilSlots.squareshield to AnvilObjs.adamant_sq_shield,
                    AnvilSlots.kiteshield to AnvilObjs.adamant_kiteshield,
                    AnvilSlots.darttips to AnvilObjs.adamant_dart_tip,
                    AnvilSlots.arrowheads to AnvilObjs.adamant_arrowheads,
                    AnvilSlots.knives to AnvilObjs.adamant_knife,
                    AnvilSlots.bolts to AnvilObjs.xbows_crossbow_bolts_adamantite_unfeathered,
                    AnvilSlots.limbs to AnvilObjs.xbows_crossbow_limbs_adamantite,
                    AnvilSlots.other_1 to AnvilObjs.adamant_javelin_head,
                )
            },
        ),
        Rune(
            "rune",
            baseLevel = 85,
            xpPerBar = 75.0,
            bar = { AnvilObjs.runite_bar },
            products = {
                listOf(
                    AnvilSlots.dagger to AnvilObjs.rune_dagger,
                    AnvilSlots.sword to AnvilObjs.rune_sword,
                    AnvilSlots.scimitar to AnvilObjs.rune_scimitar,
                    AnvilSlots.longsword to AnvilObjs.rune_longsword,
                    AnvilSlots.twohand to AnvilObjs.rune_2h_sword,
                    AnvilSlots.axe to AnvilObjs.rune_axe,
                    AnvilSlots.mace to AnvilObjs.rune_mace,
                    AnvilSlots.warhammer to AnvilObjs.rune_warhammer,
                    AnvilSlots.battleaxe to AnvilObjs.rune_battleaxe,
                    AnvilSlots.claws to AnvilObjs.rune_claws,
                    AnvilSlots.chainbody to AnvilObjs.rune_chainbody,
                    AnvilSlots.platelegs to AnvilObjs.rune_platelegs,
                    AnvilSlots.plateskirt to AnvilObjs.rune_plateskirt,
                    AnvilSlots.platebody to AnvilObjs.rune_platebody,
                    AnvilSlots.nails to AnvilObjs.nails_rune,
                    AnvilSlots.medhelm to AnvilObjs.rune_med_helm,
                    AnvilSlots.fullhelm to AnvilObjs.rune_full_helm,
                    AnvilSlots.squareshield to AnvilObjs.rune_sq_shield,
                    AnvilSlots.kiteshield to AnvilObjs.rune_kiteshield,
                    AnvilSlots.darttips to AnvilObjs.rune_dart_tip,
                    AnvilSlots.arrowheads to AnvilObjs.rune_arrowheads,
                    AnvilSlots.knives to AnvilObjs.rune_knife,
                    AnvilSlots.bolts to AnvilObjs.xbows_crossbow_bolts_runite_unfeathered,
                    AnvilSlots.limbs to AnvilObjs.xbows_crossbow_limbs_runite,
                    AnvilSlots.other_1 to AnvilObjs.rune_javelin_head,
                )
            },
        ),
    }

    private companion object {
        private const val SMITH_TICKS = 5
        private const val MAKE_ALL = 28
    }
}
