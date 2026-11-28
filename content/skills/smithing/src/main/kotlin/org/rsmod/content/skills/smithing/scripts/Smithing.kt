package org.rsmod.content.skills.smithing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
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
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Anvil smithing through the OSRS smithing interface for bronze, iron and steel.
 *
 * Bars and level per product come from the cache enums the client also uses
 * (`smithing_product_to_bars_required` / `smithing_product_to_requirement`); xp is bars x the bar's
 * xp (OSRS Wiki: bronze 12.5, iron 25, steel 37.5). Quantity comes from the skillmain buttons
 * (`makexcrafting`). One item every [SMITH_TICKS] ticks from a weak queue.
 */
class Smithing
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val enumResolver: EnumTypeMapResolver,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val productComponents =
        listOf(
            smithing_components.dagger,
            smithing_components.sword,
            smithing_components.scimitar,
            smithing_components.longsword,
            smithing_components.twohand,
            smithing_components.axe,
            smithing_components.mace,
            smithing_components.warhammer,
            smithing_components.battleaxe,
            smithing_components.chainbody,
            smithing_components.platelegs,
            smithing_components.plateskirt,
            smithing_components.platebody,
            smithing_components.medhelm,
            smithing_components.fullhelm,
            smithing_components.squareshield,
            smithing_components.kiteshield,
        )

    override fun ScriptContext.startup() {
        // Bind every loc named "Anvil" by the slot of its "Smith" op.
        var bound = 0
        for (type in locTypes.values) {
            // Any anvil variant (e.g. "Rusted anvil") with a "Smith" op.
            if (!type.name.contains("anvil", ignoreCase = true)) {
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

        onIfModalButton(smithing_components.make_1) { player.setMakeX(1) }
        onIfModalButton(smithing_components.make_5) { player.setMakeX(5) }
        onIfModalButton(smithing_components.make_10) { player.setMakeX(10) }
        onIfModalButton(smithing_components.make_all) { player.setMakeX(MAKE_ALL) }
        onIfModalButton(smithing_components.make_x) {
            val count = countDialog()
            if (count > 0) {
                player.setMakeX(count)
            }
        }

        for ((index, component) in productComponents.withIndex()) {
            onIfModalButton(component) { selectProduct(index) }
        }

        onPlayerQueueWithArgs<SmithJob>(SmithingQueues.smithing) { smith(it.args) }
    }

    private suspend fun ProtectedAccess.openAnvil(usedObj: ObjType?) {
        if (invTotal(inv, smithing_objs.hammer) == 0) {
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
        ifOpenMainModal(SmithingInterfaces.smithing)
    }

    private suspend fun ProtectedAccess.selectProduct(index: Int) {
        val metal = currentMetal(player) ?: return
        val product = metal.products().getOrNull(index) ?: return
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
        if (invTotal(inv, smithing_objs.hammer) == 0) {
            mesbox("You need a hammer to work the metal with.")
            return
        }
        if (invTotal(inv, metal.bar()) < bars) {
            val noun = if (bars == 1) "bar" else "bars"
            val item = productType.name.withArticle()
            mesbox("You don't have enough ${metal.metal} $noun to make $item.")
            return
        }

        anim(SmithingSeqs.smithing)
        invDel(inv, metal.bar(), bars)
        invAdd(inv, product)
        statAdvance(stats.smithing, metal.xpPerBar * bars * xpMods.get(player, stats.smithing))
        val made = productType.name.lowercase().withArticle()
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

    private fun levelRequired(product: ObjType): Int =
        enumResolver[SmithingEnums.level_required].firstOrNull { it.key.id == product.id }?.value
            ?: 1

    private fun String.withArticle(): String {
        val article = if (first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $this"
    }

    private data class SmithJob(val metal: Metal, val product: ObjType, val remaining: Int)

    /** Products in the same order as `productComponents`. */
    private enum class Metal(
        val metal: String,
        val baseLevel: Int,
        val xpPerBar: Double,
        val bar: () -> ObjType,
        val products: () -> List<ObjType>,
    ) {
        Bronze(
            "bronze",
            baseLevel = 1,
            xpPerBar = 12.5,
            bar = { smithing_objs.bronze_bar },
            products = {
                with(smithing_objs) {
                    listOf(
                        bronze_dagger,
                        bronze_sword,
                        bronze_scimitar,
                        bronze_longsword,
                        bronze_2h_sword,
                        bronze_axe,
                        bronze_mace,
                        bronze_warhammer,
                        bronze_battleaxe,
                        bronze_chainbody,
                        bronze_platelegs,
                        bronze_plateskirt,
                        bronze_platebody,
                        bronze_med_helm,
                        bronze_full_helm,
                        bronze_sq_shield,
                        bronze_kiteshield,
                    )
                }
            },
        ),
        Iron(
            "iron",
            baseLevel = 15,
            xpPerBar = 25.0,
            bar = { smithing_objs.iron_bar },
            products = {
                with(smithing_objs) {
                    listOf(
                        iron_dagger,
                        iron_sword,
                        iron_scimitar,
                        iron_longsword,
                        iron_2h_sword,
                        iron_axe,
                        iron_mace,
                        iron_warhammer,
                        iron_battleaxe,
                        iron_chainbody,
                        iron_platelegs,
                        iron_plateskirt,
                        iron_platebody,
                        iron_med_helm,
                        iron_full_helm,
                        iron_sq_shield,
                        iron_kiteshield,
                    )
                }
            },
        ),
        Steel(
            "steel",
            baseLevel = 30,
            xpPerBar = 37.5,
            bar = { smithing_objs.steel_bar },
            products = {
                with(smithing_objs) {
                    listOf(
                        steel_dagger,
                        steel_sword,
                        steel_scimitar,
                        steel_longsword,
                        steel_2h_sword,
                        steel_axe,
                        steel_mace,
                        steel_warhammer,
                        steel_battleaxe,
                        steel_chainbody,
                        steel_platelegs,
                        steel_plateskirt,
                        steel_platebody,
                        steel_med_helm,
                        steel_full_helm,
                        steel_sq_shield,
                        steel_kiteshield,
                    )
                }
            },
        ),
    }

    private companion object {
        private const val SMITH_TICKS = 5
        private const val MAKE_ALL = 28
    }
}
