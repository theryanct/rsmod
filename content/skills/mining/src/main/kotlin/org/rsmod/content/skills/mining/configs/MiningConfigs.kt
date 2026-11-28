package org.rsmod.content.skills.mining.configs

import org.rsmod.api.config.refs.params
import org.rsmod.api.type.builders.param.ParamBuilder
import org.rsmod.api.type.editors.loc.LocEditor
import org.rsmod.api.type.editors.obj.ObjEditor
import org.rsmod.api.type.refs.content.ContentReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.param.ParamReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.stat.PlayerStatMap
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.seq.SeqType

internal typealias mining_content = MiningContent

internal typealias mining_params = MiningParams

internal typealias mining_locs = MiningLocs

internal typealias mining_objs = MiningObjs

object MiningContent : ContentReferences() {
    val rock = find("mining_rock")
    val pickaxe = find("mining_pickaxe")
}

object MiningParams : ParamReferences() {
    /** Packed success rate for a rock: `(low shl 16) or high`, on the 1/256 skilling scale. */
    val success_rates = find<Int>("mining_success_rates")

    /** Ticks between rolls for a pickaxe (dragon/crystal are handled in code). */
    val pickaxe_ticks = find<Int>("mining_pickaxe_ticks")
}

internal object MiningParamBuilder : ParamBuilder() {
    init {
        build<Int>("mining_success_rates")
        build<Int>("mining_pickaxe_ticks")
    }
}

internal object MiningLocs : LocReferences() {
    val copperrock1 = find("copperrock1")
    val copperrock2 = find("copperrock2")
    val tinrock1 = find("tinrock1")
    val tinrock2 = find("tinrock2")
    val clayrock1 = find("clayrock1")
    val clayrock2 = find("clayrock2")
    val ironrock1 = find("ironrock1")
    val ironrock2 = find("ironrock2")
    val coalrock1 = find("coalrock1")
    val coalrock2 = find("coalrock2")
    val silverrock1 = find("silverrock1")
    val silverrock2 = find("silverrock2")
    val goldrock1 = find("goldrock1")
    val goldrock2 = find("goldrock2")
    val mithrilrock1 = find("mithrilrock1")
    val mithrilrock2 = find("mithrilrock2")
    val adamantiterock1 = find("adamantiterock1")
    val adamantiterock2 = find("adamantiterock2")
    val runiterock1 = find("runiterock1")
    val runiterock2 = find("runiterock2")

    val prif_ironrock1 = find("prif_mine_ironrock1")
    val prif_coalrock1 = find("prif_mine_coalrock1")
    val prif_silverrock1 = find("prif_mine_silverrock1")
    val prif_goldrock1 = find("prif_mine_goldrock1")
    val prif_mithrilrock1 = find("prif_mine_mithrilrock1")
    val prif_adamantiterock1 = find("prif_mine_adamantiterock1")
    val prif_runiterock1 = find("prif_mine_runiterock1")

    val rocks1 = find("rocks1")
    val rocks2 = find("rocks2")
    val rocks3 = find("rocks3")
    val prif_rocks1_empty = find("prif_mine_rocks1_empty")

    /** Depleted rocks (cache op1 "Mine"): mining one only says there's no ore. */
    val empty = listOf(rocks1, rocks2, rocks3, prif_rocks1_empty)
}

internal object MiningObjs : ObjReferences() {
    val bronze_pickaxe = find("bronze_pickaxe")
    val iron_pickaxe = find("iron_pickaxe")
    val steel_pickaxe = find("steel_pickaxe")
    val black_pickaxe = find("black_pickaxe")
    val mithril_pickaxe = find("mithril_pickaxe")
    val adamant_pickaxe = find("adamant_pickaxe")
    val rune_pickaxe = find("rune_pickaxe")
    val dragon_pickaxe = find("dragon_pickaxe")

    val copper_ore = find("copper_ore")
    val tin_ore = find("tin_ore")
    val clay = find("clay")
    val iron_ore = find("iron_ore")
    val coal = find("coal")
    val silver_ore = find("silver_ore")
    val gold_ore = find("gold_ore")
    val mithril_ore = find("mithril_ore")
    val adamantite_ore = find("adamantite_ore")
    val runite_ore = find("runite_ore")

    val uncut_sapphire = find("uncut_sapphire")
    val uncut_emerald = find("uncut_emerald")
    val uncut_ruby = find("uncut_ruby")
    val uncut_diamond = find("uncut_diamond")
}

internal object MiningSeqs : SeqReferences() {
    val bronze = find("human_mining_bronze_pickaxe")
    val iron = find("human_mining_iron_pickaxe")
    val steel = find("human_mining_steel_pickaxe")
    val black = find("human_mining_black_pickaxe")
    val mithril = find("human_mining_mithril_pickaxe")
    val adamant = find("human_mining_adamant_pickaxe")
    val rune = find("human_mining_rune_pickaxe")
    val dragon = find("human_mining_dragon_pickaxe_pretty")
}

/** Pickaxe tiers: Mining level, anim and ticks between rolls (OSRS Wiki, "Pickaxe"). */
internal object MiningPickaxes : ObjEditor() {
    init {
        pickaxe(mining_objs.bronze_pickaxe, level = 1, MiningSeqs.bronze, ticks = 8)
        pickaxe(mining_objs.iron_pickaxe, level = 1, MiningSeqs.iron, ticks = 7)
        pickaxe(mining_objs.steel_pickaxe, level = 6, MiningSeqs.steel, ticks = 6)
        pickaxe(mining_objs.black_pickaxe, level = 11, MiningSeqs.black, ticks = 5)
        pickaxe(mining_objs.mithril_pickaxe, level = 21, MiningSeqs.mithril, ticks = 5)
        pickaxe(mining_objs.adamant_pickaxe, level = 31, MiningSeqs.adamant, ticks = 4)
        pickaxe(mining_objs.rune_pickaxe, level = 41, MiningSeqs.rune, ticks = 3)
        // Dragon: 3 ticks with a 1/6 chance of 2 (applied in Mining.rollTicks).
        pickaxe(mining_objs.dragon_pickaxe, level = 61, MiningSeqs.dragon, ticks = 3)
    }

    private fun pickaxe(type: ObjType, level: Int, anim: SeqType, ticks: Int) {
        edit(type) {
            contentGroup = mining_content.pickaxe
            param[params.levelrequire] = level
            param[params.skill_anim] = anim
            param[mining_params.pickaxe_ticks] = ticks
        }
    }
}

/**
 * F2P rocks (OSRS Wiki): level, xp, success rates (low/high on the 1/256 scale) and respawn ticks.
 * Each of these rocks depletes after a single ore.
 */
internal object MiningRocks : LocEditor() {
    init {
        val copper = Rock(mining_objs.copper_ore, level = 1, xp = 17.5, 100, 350, respawn = 4)
        val tin = Rock(mining_objs.tin_ore, level = 1, xp = 17.5, 100, 350, respawn = 4)
        val clay = Rock(mining_objs.clay, level = 1, xp = 5.0, 128, 400, respawn = 2)
        val iron = Rock(mining_objs.iron_ore, level = 15, xp = 35.0, 96, 350, respawn = 9)
        val coal = Rock(mining_objs.coal, level = 30, xp = 50.0, 16, 100, respawn = 49)

        rock(mining_locs.copperrock1, mining_locs.rocks1, copper)
        rock(mining_locs.copperrock2, mining_locs.rocks2, copper)
        rock(mining_locs.tinrock1, mining_locs.rocks1, tin)
        rock(mining_locs.tinrock2, mining_locs.rocks2, tin)
        rock(mining_locs.clayrock1, mining_locs.rocks1, clay)
        rock(mining_locs.clayrock2, mining_locs.rocks2, clay)
        rock(mining_locs.ironrock1, mining_locs.rocks1, iron)
        rock(mining_locs.ironrock2, mining_locs.rocks2, iron)
        rock(mining_locs.coalrock1, mining_locs.rocks1, coal)
        rock(mining_locs.coalrock2, mining_locs.rocks2, coal)
        rock(mining_locs.prif_ironrock1, mining_locs.prif_rocks1_empty, iron)
        rock(mining_locs.prif_coalrock1, mining_locs.prif_rocks1_empty, coal)
        // Members ores (OSRS Wiki Mining info + success charts; respawn 1/1/2/4/12 minutes).
        val silver = Rock(mining_objs.silver_ore, level = 20, xp = 40.0, 25, 200, respawn = 100)
        val gold = Rock(mining_objs.gold_ore, level = 40, xp = 65.0, 7, 75, respawn = 100)
        val mithril = Rock(mining_objs.mithril_ore, level = 55, xp = 80.0, 4, 50, respawn = 200)
        val adamantite = Rock(mining_objs.adamantite_ore, level = 70, xp = 95.0, 2, 25, respawn = 400)
        val runite = Rock(mining_objs.runite_ore, level = 85, xp = 125.0, 1, 18, respawn = 1200)
        rock(mining_locs.silverrock1, mining_locs.rocks1, silver)
        rock(mining_locs.silverrock2, mining_locs.rocks2, silver)
        rock(mining_locs.goldrock1, mining_locs.rocks1, gold)
        rock(mining_locs.goldrock2, mining_locs.rocks2, gold)
        rock(mining_locs.mithrilrock1, mining_locs.rocks1, mithril)
        rock(mining_locs.mithrilrock2, mining_locs.rocks2, mithril)
        rock(mining_locs.adamantiterock1, mining_locs.rocks1, adamantite)
        rock(mining_locs.adamantiterock2, mining_locs.rocks2, adamantite)
        rock(mining_locs.runiterock1, mining_locs.rocks1, runite)
        rock(mining_locs.runiterock2, mining_locs.rocks2, runite)
        // Prifddinas mine: same ores (cache categories match), one shared empty rock.
        rock(mining_locs.prif_silverrock1, mining_locs.prif_rocks1_empty, silver)
        rock(mining_locs.prif_goldrock1, mining_locs.prif_rocks1_empty, gold)
        rock(mining_locs.prif_mithrilrock1, mining_locs.prif_rocks1_empty, mithril)
        rock(mining_locs.prif_adamantiterock1, mining_locs.prif_rocks1_empty, adamantite)
        rock(mining_locs.prif_runiterock1, mining_locs.prif_rocks1_empty, runite)
    }

    private class Rock(
        val ore: ObjType,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        val respawn: Int,
    )

    private fun rock(type: LocType, empty: LocType, rock: Rock) {
        edit(type) {
            contentGroup = mining_content.rock
            param[params.levelrequire] = rock.level
            param[params.skill_xp] = PlayerStatMap.toFineXP(rock.xp).toInt()
            param[params.skill_productitem] = rock.ore
            param[params.next_loc_stage] = empty
            param[params.respawn_time] = rock.respawn
            param[mining_params.success_rates] = (rock.low shl 16) or rock.high
        }
    }
}
