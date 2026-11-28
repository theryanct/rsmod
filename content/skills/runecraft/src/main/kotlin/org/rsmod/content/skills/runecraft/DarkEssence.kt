package org.rsmod.content.skills.runecraft

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.player.stat.runecraftingLvl
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.skills.mining.scripts.Mining
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeAnim
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Arceuus dark essence (OSRS Wiki: Dense runestone, Dense/Dark essence block, Dark essence
 * fragments, Blood Altar (Kourend), Soul Altar (Kourend)):
 * - "Chip" a dense runestone (38 Mining + 38 Crafting, pickaxe and chisel; any pickaxe tier is
 *   equally fast): one dense essence block every [DENSE_TICKS] ticks, 12 Mining + 8 Crafting xp.
 *   The runestones never deplete here (multivar stays 0).
 * - "Venerate" the Dark Altar: every dense block becomes a dark essence block, 2.5 Runecraft xp
 *   and 1 Prayer point each (works at 0 Prayer).
 * - Chisel on dark blocks: 4 fragments per block, 8 Crafting xp, at most 108 fragments held.
 * - "Bind" Kourend's Blood Altar (77) / Soul Altar (90): every fragment becomes one blood / soul
 *   rune for 23.8 / 29.7 Runecraft xp. No talisman or tiara needed.
 * Not included: Kourend diary double blocks, blood essence, the Abyss rifts, teleport tablets.
 */
class DarkEssence
@Inject
constructor(private val objTypes: ObjTypeList, private val xpMods: XpModifiers) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLoc1(DarkEssenceLocs.runestone) { chip(it.loc) }
        onOpLoc1(DarkEssenceLocs.dark_altar) { venerate() }
        onOpLoc1(DarkEssenceLocs.blood_altar) {
            bind(DarkEssenceObjs.blood_rune, BLOOD_LEVEL, BLOOD_XP, "blood")
        }
        onOpLoc1(DarkEssenceLocs.soul_altar) {
            bind(DarkEssenceObjs.soul_rune, SOUL_LEVEL, SOUL_XP, "soul")
        }
        onOpHeldU(DarkEssenceObjs.chisel, DarkEssenceObjs.dark_block) { chiselBlocks() }
    }

    private fun ProtectedAccess.chip(rock: BoundLocInfo) {
        if (player.miningLvl < DENSE_LEVEL || player.craftingLvl < DENSE_LEVEL) {
            mes("You need Mining and Crafting levels of $DENSE_LEVEL to chip the runestone.")
            return
        }
        val pickaxe = Mining.findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine this rock.")
            return
        }
        if (DarkEssenceObjs.chisel !in inv) {
            mes("You need a chisel to chip the runestone.")
            return
        }
        if (inv.isFull()) {
            mes("Your inventory is too full to hold any more essence.")
            return
        }
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(objTypes[pickaxe].pickaxeAnim)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + DENSE_TICKS
            spam("You swing your pick at the rock.")
        } else if (actionDelay == mapClock) {
            invAdd(inv, DarkEssenceObjs.dense_block)
            statAdvance(stats.mining, DENSE_MINING_XP * xpMods.get(player, stats.mining))
            statAdvance(stats.crafting, DENSE_CRAFTING_XP * xpMods.get(player, stats.crafting))
            actionDelay = mapClock + DENSE_TICKS
        }
        opLoc1(rock)
    }

    private fun ProtectedAccess.venerate() {
        val blocks = invTotal(inv, DarkEssenceObjs.dense_block)
        if (blocks == 0) {
            mes("You need dense essence blocks to venerate the altar.")
            return
        }
        invDel(inv, DarkEssenceObjs.dense_block, blocks)
        invAdd(inv, DarkEssenceObjs.dark_block, blocks)
        anim(RunecraftSeqs.bind)
        statSub(stats.prayer, blocks, 0)
        statAdvance(stats.runecrafting, blocks * VENERATE_XP * xpMods.get(player, stats.runecrafting))
        mes("You bind the temple's power into dark essence blocks.")
    }

    private fun ProtectedAccess.chiselBlocks() {
        if (player.craftingLvl < DENSE_LEVEL) {
            mes("You need a Crafting level of $DENSE_LEVEL to chisel dark essence.")
            return
        }
        val held = invTotal(inv, DarkEssenceObjs.fragments)
        val room = (MAX_FRAGMENTS - held) / FRAGMENTS_PER_BLOCK
        val blocks = min(invTotal(inv, DarkEssenceObjs.dark_block), room)
        if (blocks <= 0) {
            mes("You can't carry any more dark essence fragments.")
            return
        }
        invDel(inv, DarkEssenceObjs.dark_block, blocks)
        invAdd(inv, DarkEssenceObjs.fragments, blocks * FRAGMENTS_PER_BLOCK)
        statAdvance(stats.crafting, blocks * CHISEL_XP * xpMods.get(player, stats.crafting))
        mes("You chip the dark essence into fragments.")
    }

    private fun ProtectedAccess.bind(rune: ObjType, level: Int, xp: Double, name: String) {
        if (player.runecraftingLvl < level) {
            mes("You need a Runecraft level of $level to bind $name runes.")
            return
        }
        val fragments = invTotal(inv, DarkEssenceObjs.fragments)
        if (fragments == 0) {
            mes("You need dark essence fragments to bind $name runes.")
            return
        }
        invDel(inv, DarkEssenceObjs.fragments, fragments)
        invAdd(inv, rune, fragments)
        anim(RunecraftSeqs.bind)
        spotanim(RunecraftSpotanims.bind, height = 92)
        statAdvance(stats.runecrafting, fragments * xp * xpMods.get(player, stats.runecrafting))
        mes("You bind the altar's power into $name runes.")
    }

    private companion object {
        const val DENSE_LEVEL = 38
        const val DENSE_TICKS = 9
        const val DENSE_MINING_XP = 12.0
        const val DENSE_CRAFTING_XP = 8.0
        const val VENERATE_XP = 2.5
        const val CHISEL_XP = 8.0
        const val FRAGMENTS_PER_BLOCK = 4
        const val MAX_FRAGMENTS = 108
        const val BLOOD_LEVEL = 77
        const val BLOOD_XP = 23.8
        const val SOUL_LEVEL = 90
        const val SOUL_XP = 29.7
    }
}

internal object DarkEssenceObjs : ObjReferences() {
    val chisel = find("chisel")
    val dense_block = find("arceuus_essence_block")
    val dark_block = find("arceuus_essence_block_dark")
    val fragments = find("bigblankrune")
    val blood_rune = find("bloodrune")
    val soul_rune = find("soulrune")
}

internal object DarkEssenceLocs : LocReferences() {
    /** Child of the multiloc runestones arceuus_runestone_base_1/2 (multivar 0 = mineable). */
    val runestone = find("arceuus_runestone_base_mine")
    val dark_altar = find("archeus_altar_dark")
    val blood_altar = find("archeus_altar_blood")
    val soul_altar = find("archeus_altar_soul")
}
