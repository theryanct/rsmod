package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.commons.magic.MagicSpellType
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.CastResult
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Enchant Crossbow Bolt (standard spellbook, Magic 4+). The spell opens the skillmulti chatbox with
 * the ten bolt types; each cast enchants up to 10 bolts of the picked type (gem-tipped bolts first,
 * then dragon bolts of the same gem).
 *
 * Runes come from each sub-spell obj (`04_enchant_bolt_opal` .. `87_enchant_bolt_onyx`), checked
 * and removed by [MagicRuneManager] (staffs, Infinite runes toggle). Those objs carry no level
 * or xp params, so levels and cast xp are the OSRS Wiki's.
 *
 * Enchanted bolt special effects (Lucky Lightning, Blood Forfeit, Dragon's Breath...) are NOT
 * implemented: there is no ranged bolt-effect hook in combat yet. The bolts are made, they just
 * hit like plain bolts for now.
 */
class BoltEnchantScript
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    /** One bolt type: its sub-spell, Wiki level / xp, and the (plain, enchanted) bolt pairs. */
    private class Bolt(
        val spell: ObjType,
        val level: Int,
        val xp: Double,
        val pairs: List<Pair<ObjType, ObjType>>,
    )

    private val bolts by lazy {
        listOf(
            Bolt(BoltSpells.opal, 4, 9.0, listOf(BoltObjs.opal to BoltObjs.opal_e, BoltObjs.dragon_opal to BoltObjs.dragon_opal_e)),
            Bolt(BoltSpells.sapphire, 7, 17.5, listOf(BoltObjs.sapphire to BoltObjs.sapphire_e, BoltObjs.dragon_sapphire to BoltObjs.dragon_sapphire_e)),
            Bolt(BoltSpells.jade, 14, 19.0, listOf(BoltObjs.jade to BoltObjs.jade_e, BoltObjs.dragon_jade to BoltObjs.dragon_jade_e)),
            Bolt(BoltSpells.pearl, 24, 29.0, listOf(BoltObjs.pearl to BoltObjs.pearl_e, BoltObjs.dragon_pearl to BoltObjs.dragon_pearl_e)),
            Bolt(BoltSpells.emerald, 27, 37.0, listOf(BoltObjs.emerald to BoltObjs.emerald_e, BoltObjs.dragon_emerald to BoltObjs.dragon_emerald_e)),
            Bolt(BoltSpells.topaz, 29, 33.0, listOf(BoltObjs.topaz to BoltObjs.topaz_e, BoltObjs.dragon_topaz to BoltObjs.dragon_topaz_e)),
            Bolt(BoltSpells.ruby, 49, 59.0, listOf(BoltObjs.ruby to BoltObjs.ruby_e, BoltObjs.dragon_ruby to BoltObjs.dragon_ruby_e)),
            Bolt(BoltSpells.diamond, 57, 67.0, listOf(BoltObjs.diamond to BoltObjs.diamond_e, BoltObjs.dragon_diamond to BoltObjs.dragon_diamond_e)),
            Bolt(BoltSpells.dragonstone, 68, 78.0, listOf(BoltObjs.dragonstone to BoltObjs.dragonstone_e, BoltObjs.dragon_dragonstone to BoltObjs.dragon_dragonstone_e)),
            Bolt(BoltSpells.onyx, 87, 97.0, listOf(BoltObjs.onyx to BoltObjs.onyx_e, BoltObjs.dragon_onyx to BoltObjs.dragon_onyx_e)),
        )
    }

    /** The sub-spells as [MagicSpell]s, borrowing the menu spell's spellbook and button. */
    private val boltSpells by lazy {
        val menu = spells.getObjSpell(BoltSpells.menu)
        bolts.associateWith { bolt ->
            val unpacked = objTypes[bolt.spell]
            val reqs =
                listOf(
                    params.spell_runetype_1 to params.spell_runecount_1,
                    params.spell_runetype_2 to params.spell_runecount_2,
                    params.spell_runetype_3 to params.spell_runecount_3,
                    params.spell_runetype_4 to params.spell_runecount_4,
                ).mapNotNull { (type, count) ->
                    val rune = unpacked.paramOrNull(type) ?: return@mapNotNull null
                    val amount = unpacked.paramOrNull(count) ?: return@mapNotNull null
                    MagicSpell.ObjRequirement(rune, amount, wornSlot = null)
                }
            MagicSpell(
                obj = bolt.spell,
                name = unpacked.name,
                component = BoltComponents.menu,
                spellbook = menu?.spellbook,
                type = MagicSpellType.Utility,
                maxHit = 0,
                levelReq = bolt.level,
                castXp = bolt.xp,
                objReqs = reqs,
            )
        }
    }

    override fun ScriptContext.startup() {
        onIfOverlayButton(BoltComponents.menu) {
            val started =
                protectedAccess.launch(player, busyText = "You're busy right now.") { openMenu() }
            if (!started) {
                player.mes("Please finish what you're doing first.")
            }
        }
        onPlayerQueueWithArgs<Pair<Int, Int>>(BoltQueues.enchant) {
            val (index, casts) = it.args
            bolts.getOrNull(index)?.let { bolt -> castBolt(bolt, casts) }
        }
    }

    private suspend fun ProtectedAccess.openMenu() {
        val magic = statBase(stats.magic)
        val products =
            bolts.map { bolt ->
                val enchanted = bolt.pairs.first().second
                val has = bolt.pairs.any { (plain, _) -> invTotal(inv, plain) > 0 }
                SkillMultiProduct(enchanted, objTypes[enchanted].name, has && magic >= bolt.level)
            }
        val choice =
            skillMulti(
                verb = VERB_ENCHANT,
                title = "What would you like to enchant?",
                products = products,
                maxQuantity = MAX_CASTS,
            )
        val bolt = bolts[choice.index]
        if (magic < bolt.level) {
            mes("You need a Magic level of ${bolt.level} to enchant these bolts.")
            return
        }
        castBolt(bolt, choice.quantity)
    }

    /** One cast (up to 10 bolts), then queue the next while casts, bolts and runes remain. */
    private fun ProtectedAccess.castBolt(bolt: Bolt, casts: Int) {
        val pair = bolt.pairs.firstOrNull { (plain, _) -> invTotal(inv, plain) > 0 }
        if (pair == null) {
            mes("You don't have any ${objTypes[bolt.pairs.first().first].name.lowercase()} to enchant.")
            return
        }
        val spell = boltSpells.getValue(bolt)
        if (runes.attemptCast(player, spell) is CastResult.Failure) {
            return
        }
        val (plain, enchanted) = pair
        val count = minOf(BOLTS_PER_CAST, invTotal(inv, plain))
        invDel(inv, plain, count)
        invAdd(inv, enchanted, count)
        anim(BoltSeqs.enchant)
        spotanim(BoltSpots.enchant, height = 92)
        statAdvance(stats.magic, bolt.xp * xpMods.get(player, stats.magic))
        val index = bolts.indexOf(bolt)
        if (casts > 1) {
            weakQueue(BoltQueues.enchant, TICKS_PER_CAST, index to casts - 1)
        }
    }

    private companion object {
        /** Skillmulti verb "Enchant" (client enum 1809 key 19). */
        const val VERB_ENCHANT = 19
        const val BOLTS_PER_CAST = 10
        const val TICKS_PER_CAST = 3
        const val MAX_CASTS = 28
    }
}

internal object BoltComponents : ComponentReferences() {
    val menu = find("magic_spellbook:xbows_enchant")
}

/** Sub-spell objs (runes only; see BoltEnchantScript for level / xp). */
internal object BoltSpells : ObjReferences() {
    /** The menu spell (obj 5000; its symbol name is a leftover, its params say Crossbow Bolt Enchantments). */
    val menu = find("50_slayer_dart")
    val opal = find("04_enchant_bolt_opal")
    val sapphire = find("07_enchant_bolt_sapphire")
    val jade = find("14_enchant_bolt_jade")
    val pearl = find("24_enchant_bolt_pearl")
    val emerald = find("27_enchant_bolt_emerald")
    val topaz = find("29_enchant_bolt_redtopaz")
    val ruby = find("49_enchant_bolt_ruby")
    val diamond = find("57_enchant_bolt_diamond")
    val dragonstone = find("68_enchant_bolt_dragonstone")
    val onyx = find("87_enchant_bolt_onyx")
}

internal object BoltObjs : ObjReferences() {
    val opal = find("opal_bolt")
    val opal_e = find("xbows_crossbow_bolts_bronze_tipped_opal_enchanted")
    val jade = find("xbows_crossbow_bolts_blurite_tipped_jade")
    val jade_e = find("xbows_crossbow_bolts_blurite_tipped_jade_enchanted")
    val pearl = find("pearl_bolt")
    val pearl_e = find("xbows_crossbow_bolts_iron_tipped_pearl_enchanted")
    val topaz = find("xbows_crossbow_bolts_steel_tipped_redtopaz")
    val topaz_e = find("xbows_crossbow_bolts_steel_tipped_redtopaz_enchanted")
    val sapphire = find("xbows_crossbow_bolts_mithril_tipped_sapphire")
    val sapphire_e = find("xbows_crossbow_bolts_mithril_tipped_sapphire_enchanted")
    val emerald = find("xbows_crossbow_bolts_mithril_tipped_emerald")
    val emerald_e = find("xbows_crossbow_bolts_mithril_tipped_emerald_enchanted")
    val ruby = find("xbows_crossbow_bolts_adamantite_tipped_ruby")
    val ruby_e = find("xbows_crossbow_bolts_adamantite_tipped_ruby_enchanted")
    val diamond = find("xbows_crossbow_bolts_adamantite_tipped_diamond")
    val diamond_e = find("xbows_crossbow_bolts_adamantite_tipped_diamond_enchanted")
    val dragonstone = find("xbows_crossbow_bolts_runite_tipped_dragonstone")
    val dragonstone_e = find("xbows_crossbow_bolts_runite_tipped_dragonstone_enchanted")
    val onyx = find("xbows_crossbow_bolts_runite_tipped_onyx")
    val onyx_e = find("xbows_crossbow_bolts_runite_tipped_onyx_enchanted")

    val dragon_opal = find("dragon_bolts_unenchanted_opal")
    val dragon_opal_e = find("dragon_bolts_enchanted_opal")
    val dragon_jade = find("dragon_bolts_unenchanted_jade")
    val dragon_jade_e = find("dragon_bolts_enchanted_jade")
    val dragon_pearl = find("dragon_bolts_unenchanted_pearl")
    val dragon_pearl_e = find("dragon_bolts_enchanted_pearl")
    val dragon_topaz = find("dragon_bolts_unenchanted_topaz")
    val dragon_topaz_e = find("dragon_bolts_enchanted_topaz")
    val dragon_sapphire = find("dragon_bolts_unenchanted_sapphire")
    val dragon_sapphire_e = find("dragon_bolts_enchanted_sapphire")
    val dragon_emerald = find("dragon_bolts_unenchanted_emerald")
    val dragon_emerald_e = find("dragon_bolts_enchanted_emerald")
    val dragon_ruby = find("dragon_bolts_unenchanted_ruby")
    val dragon_ruby_e = find("dragon_bolts_enchanted_ruby")
    val dragon_diamond = find("dragon_bolts_unenchanted_diamond")
    val dragon_diamond_e = find("dragon_bolts_enchanted_diamond")
    val dragon_dragonstone = find("dragon_bolts_unenchanted_dragonstone")
    val dragon_dragonstone_e = find("dragon_bolts_enchanted_dragonstone")
    val dragon_onyx = find("dragon_bolts_unenchanted_onyx")
    val dragon_onyx_e = find("dragon_bolts_enchanted_onyx")
}

internal object BoltSeqs : SeqReferences() {
    val enchant = find("human_xbow_enchant_arrowtip")
}

internal object BoltSpots : SpotanimReferences() {
    val enchant = find("xbows_enchanting_tips_spot")
}

/** Custom queue (65300, ui range): the next cast of a multi-cast. */
internal object BoltQueues : QueueReferences() {
    val enchant = find("magic_bolt_enchant")
}
