package org.rsmod.content.skills.magic.utility

import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.CastResult
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onIfOverlayButtonT
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Standard spellbook utility spells. Levels, runes and cast xp come from the cache spell objs
 * (via [MagicSpellRegistry]); runes are checked and removed by [MagicRuneManager], which also
 * honours the Infinite runes toggle. Targets are validated before any runes are used.
 * - City teleports (wiki landing tiles).
 * - Low / high alchemy: 40% / 60% of the item's value in coins.
 * - Superheat item: ore -> bar (wiki smithing levels and xp; iron becomes steel with 2 coal).
 * - Bones to bananas, Lvl-1 (sapphire) and Lvl-2 (emerald) enchant.
 */
class MagicUtilityScript
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    override fun ScriptContext.startup() {
        teleport(MagicUtilityComponents.varrock_teleport, MagicUtilitySpells.varrock_teleport, CoordGrid(3213, 3424))
        teleport(MagicUtilityComponents.lumbridge_teleport, MagicUtilitySpells.lumbridge_teleport, CoordGrid(3222, 3218))
        teleport(MagicUtilityComponents.falador_teleport, MagicUtilitySpells.falador_teleport, CoordGrid(2964, 3378))
        teleport(MagicUtilityComponents.camelot_teleport, MagicUtilitySpells.camelot_teleport, CoordGrid(2757, 3479))
        teleport(MagicUtilityComponents.ardougne_teleport, MagicUtilitySpells.ardougne_teleport, CoordGrid(2662, 3305))
        teleport(MagicUtilityComponents.watchtower_teleport, MagicUtilitySpells.watchtower_teleport, CoordGrid(2546, 3114, 2))

        onItem(MagicUtilityComponents.low_alchemy) { slot -> alchemy(slot, high = false) }
        onItem(MagicUtilityComponents.high_alchemy) { slot -> alchemy(slot, high = true) }
        onItem(MagicUtilityComponents.superheat) { slot -> superheat(slot) }
        onItem(MagicUtilityComponents.enchant_1) { slot -> enchant(slot, level = 1) }
        onItem(MagicUtilityComponents.enchant_2) { slot -> enchant(slot, level = 2) }
        onIfOverlayButton(MagicUtilityComponents.bones_bananas) { launch(player) { bonesToBananas() } }
    }

    private fun ScriptContext.teleport(button: ComponentType, spellObj: ObjType, dest: CoordGrid) {
        onIfOverlayButton(button) { launch(player) { castTeleport(spellObj, dest) } }
    }

    /** Spell used on an inventory item (selected spell button -> target inventory slot). */
    private fun ScriptContext.onItem(button: ComponentType, action: suspend ProtectedAccess.(Int) -> Unit) {
        onIfOverlayButtonT(button, components.inv_items) {
            val slot = targetSlot
            launch(player) { this.action(slot) }
        }
    }

    private fun launch(player: Player, block: suspend ProtectedAccess.() -> Unit) {
        val started = protectedAccess.launch(player, block = block)
        if (!started) {
            player.mes("Please finish what you're doing first.")
        }
    }

    /** Validates level/spellbook and removes runes; `null` when the cast can't go ahead. */
    private fun ProtectedAccess.cast(spellObj: ObjType): MagicSpell? {
        val spell = spells.getObjSpell(spellObj) ?: return null
        val result = runes.attemptCast(player, spell)
        return if (result is CastResult.Failure) null else spell
    }

    private fun ProtectedAccess.magicXp(spell: MagicSpell) {
        statAdvance(stats.magic, spell.castXp * xpMods.get(player, stats.magic))
    }

    private suspend fun ProtectedAccess.castTeleport(spellObj: ObjType, dest: CoordGrid) {
        val spell = cast(spellObj) ?: return
        anim(MagicUtilitySeqs.teleport)
        spotanim(MagicUtilitySpots.teleport, height = 92)
        soundSynth(MagicUtilitySynths.teleport)
        delay(3)
        telejump(dest)
        magicXp(spell)
    }

    private suspend fun ProtectedAccess.alchemy(slot: Int, high: Boolean) {
        val target = inv[slot] ?: return
        if (target.id == MagicUtilityObjs.coins.id) {
            mes("Coins are already made of gold.")
            return
        }
        val type = objTypes.uncert(objTypes[target])
        val coins = if (high) type.cost * 3 / 5 else type.cost * 2 / 5
        val spell = cast(if (high) MagicUtilitySpells.high_alchemy else MagicUtilitySpells.low_alchemy) ?: return
        if (high) {
            anim(MagicUtilitySeqs.high_alchemy)
            spotanim(MagicUtilitySpots.high_alchemy, height = 92)
            soundSynth(MagicUtilitySynths.high_alchemy)
        } else {
            anim(MagicUtilitySeqs.low_alchemy)
            spotanim(MagicUtilitySpots.low_alchemy, height = 92)
            soundSynth(MagicUtilitySynths.low_alchemy)
        }
        invDel(inv, objTypes[target], 1, slot = slot)
        if (coins > 0) {
            invAdd(inv, MagicUtilityObjs.coins, coins)
        }
        magicXp(spell)
        delay(if (high) 5 else 3)
    }

    private suspend fun ProtectedAccess.superheat(slot: Int) {
        val target = inv[slot] ?: return
        val bar = Bar.forOre(target.id, invTotal(inv, MagicUtilityObjs.coal))
        if (bar == null) {
            mes("You need to cast superheat item on ore.")
            return
        }
        if (player.smithingLvl < bar.level) {
            mes("You need a Smithing level of ${bar.level} to smelt that.")
            return
        }
        val missing = bar.ores().firstOrNull { (ore, count) -> invTotal(inv, ore) < count }
        if (missing != null) {
            mes("You haven't got enough ore to make that bar.")
            return
        }
        val spell = cast(MagicUtilitySpells.superheat) ?: return
        anim(MagicUtilitySeqs.superheat)
        spotanim(MagicUtilitySpots.superheat, height = 92)
        soundSynth(MagicUtilitySynths.superheat)
        for ((ore, count) in bar.ores()) {
            invDel(inv, ore, count)
        }
        invAdd(inv, bar.bar())
        statAdvance(stats.smithing, bar.smithingXp * xpMods.get(player, stats.smithing))
        magicXp(spell)
        delay(3)
    }

    private suspend fun ProtectedAccess.bonesToBananas() {
        val bones = invTotal(inv, MagicUtilityObjs.bones)
        if (bones <= 0) {
            mes("You aren't holding any bones!")
            return
        }
        val spell = cast(MagicUtilitySpells.bones_bananas) ?: return
        anim(MagicUtilitySeqs.bones_bananas)
        spotanim(MagicUtilitySpots.bones_bananas, height = 92)
        // Each bones slot becomes a banana slot, so the inventory always has room.
        invDel(inv, MagicUtilityObjs.bones, bones)
        invAdd(inv, MagicUtilityObjs.banana, bones)
        magicXp(spell)
        delay(2)
    }

    private suspend fun ProtectedAccess.enchant(slot: Int, level: Int) {
        val target = inv[slot] ?: return
        val table = if (level == 1) SAPPHIRE else EMERALD
        val into = table().firstOrNull { (from, _) -> from.id == target.id }?.second
        if (into == null) {
            val gem = if (level == 1) "sapphire" else "emerald"
            mes("This spell can only be cast on $gem amulets, necklaces, rings and bracelets.")
            return
        }
        val spell = cast(if (level == 1) MagicUtilitySpells.enchant_1 else MagicUtilitySpells.enchant_2) ?: return
        if (level == 1) {
            anim(MagicUtilitySeqs.enchant_1)
            spotanim(MagicUtilitySpots.enchant_1, height = 92)
        } else {
            anim(MagicUtilitySeqs.enchant_2)
            spotanim(MagicUtilitySpots.enchant_2, height = 92)
        }
        invDel(inv, objTypes[target], 1, slot = slot)
        invAdd(inv, into, 1, slot = slot)
        magicXp(spell)
        delay(2)
    }

    /** Superheat products (OSRS Wiki smithing level / xp per bar). */
    private enum class Bar(
        val level: Int,
        val smithingXp: Double,
        val bar: () -> ObjType,
        val ores: () -> List<Pair<ObjType, Int>>,
    ) {
        Bronze(1, 6.25, { MagicUtilityObjs.bronze_bar }, { listOf(MagicUtilityObjs.copper_ore to 1, MagicUtilityObjs.tin_ore to 1) }),
        Iron(15, 12.5, { MagicUtilityObjs.iron_bar }, { listOf(MagicUtilityObjs.iron_ore to 1) }),
        Silver(20, 13.7, { MagicUtilityObjs.silver_bar }, { listOf(MagicUtilityObjs.silver_ore to 1) }),
        Steel(30, 17.5, { MagicUtilityObjs.steel_bar }, { listOf(MagicUtilityObjs.iron_ore to 1, MagicUtilityObjs.coal to 2) }),
        Gold(40, 22.5, { MagicUtilityObjs.gold_bar }, { listOf(MagicUtilityObjs.gold_ore to 1) }),
        Mithril(50, 30.0, { MagicUtilityObjs.mithril_bar }, { listOf(MagicUtilityObjs.mithril_ore to 1, MagicUtilityObjs.coal to 4) }),
        Adamantite(70, 37.5, { MagicUtilityObjs.adamantite_bar }, { listOf(MagicUtilityObjs.adamantite_ore to 1, MagicUtilityObjs.coal to 6) }),
        Runite(85, 50.0, { MagicUtilityObjs.runite_bar }, { listOf(MagicUtilityObjs.runite_ore to 1, MagicUtilityObjs.coal to 8) });

        companion object {
            /** OSRS: iron ore with at least 2 coal in the inventory becomes steel. */
            fun forOre(ore: Int, coal: Int): Bar? =
                when (ore) {
                    MagicUtilityObjs.copper_ore.id, MagicUtilityObjs.tin_ore.id -> Bronze
                    MagicUtilityObjs.iron_ore.id -> if (coal >= 2) Steel else Iron
                    MagicUtilityObjs.silver_ore.id -> Silver
                    MagicUtilityObjs.gold_ore.id -> Gold
                    MagicUtilityObjs.mithril_ore.id -> Mithril
                    MagicUtilityObjs.adamantite_ore.id -> Adamantite
                    MagicUtilityObjs.runite_ore.id -> Runite
                    else -> null
                }
        }
    }

    private companion object {
        val SAPPHIRE: () -> List<Pair<ObjType, ObjType>> = {
            listOf(
                MagicUtilityObjs.sapphire_ring to MagicUtilityObjs.ring_of_recoil,
                MagicUtilityObjs.sapphire_necklace to MagicUtilityObjs.games_necklace_8,
                MagicUtilityObjs.sapphire_amulet to MagicUtilityObjs.amulet_of_magic,
                MagicUtilityObjs.sapphire_bracelet to MagicUtilityObjs.bracelet_of_clay,
            )
        }
        val EMERALD: () -> List<Pair<ObjType, ObjType>> = {
            listOf(
                MagicUtilityObjs.emerald_ring to MagicUtilityObjs.ring_of_dueling_8,
                MagicUtilityObjs.emerald_necklace to MagicUtilityObjs.binding_necklace,
                MagicUtilityObjs.emerald_amulet to MagicUtilityObjs.amulet_of_defence,
                MagicUtilityObjs.emerald_bracelet to MagicUtilityObjs.castle_wars_bracelet_3,
            )
        }
    }
}
