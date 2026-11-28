package org.rsmod.content.quests.shilovillage

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences

// Every cache ref Shilo Village uses (re-check after cache updates).

internal object SvNpcs : NpcReferences() {
    /** Mosol Rei's multinpc (`zombiequeen` 0-14 `mosol_rei`, 15+ `mosol_rei_complete`). */
    val mosol_multi = find("mosol_rei_multi")
    val mosol = find("mosol_rei")
    val mosol_complete = find("mosol_rei_complete")
    val trufitus = find("trufitus")
    val zadimus = find("zadimus_ghost")
    val rashiliyia = find("zqzombiequeen")
    /** Nazastarool's three forms: zombie (91), skeleton (68), ghost (93). */
    val nazastarool = listOf(find("zq_mainzombie1"), find("zq_mainzombie2"), find("zq_mainzombie3"))
    /** Rashiliyia's minions (undead ones) that rise when she is angered. */
    val undead =
        listOf(
            find("zqskeleton_armed"),
            find("zqzombie_unarmed"),
            find("zqskeleton_unarmed"),
            find("zqzombie_armed"),
        )
    val yanni = find("shiloantiques")
    val vigroy = find("shilocartdriver")
    val hajedy = find("brimhavencartdriver")
}

internal object SvObjs : ObjReferences() {
    val wampum_belt = find("mosol_wampum_belt")
    val plaque = find("zqplaque")
    val tattered_scroll = find("zqberviriusscroll")
    val crumpled_scroll = find("zqrashiliyiascroll")
    val zadimus_corpse = find("zqzadimusbones")
    val bone_shard = find("zqboneshard")
    val bone_key = find("zqbonekey")
    val pommel = find("zqbevsword")
    val crystal = find("zqcrystal")
    val notes = find("zqberviriusscroll2")
    val bone_beads = find("zqbonebeads")
    val dead_beads = find("zqdeadbeads")
    val remains = find("zqcorpse")
    val wire = find("bronzecraftwire")
    val chisel = find("chisel")
    val spade = find("spade")
    val rope = find("rope")
    val bones = find("bones")
    val coins = find("coins")
    /** The tomb's bait coins (only spawn: Rashiliyia's Tomb 2915,9503). */
    val fake_coins = find("fake_coins")
    val lit_candle = find("lit_candle")
    val lit_black_candle = find("lit_black_candle")
    val torch_lit = find("torch_lit")
    val candle_lantern_lit = find("candle_lantern_lit")
    val candle_lantern_black_lit = find("candle_lantern_black_lit")
    /** Lights that can be dropped into the fissure (Wiki: bullseye lanterns don't work). */
    val lights = listOf(lit_candle, lit_black_candle, torch_lit, candle_lantern_lit, candle_lantern_black_lit)
}

internal object SvLocs : LocReferences() {
    val mound = find("ahzarhoon_entrance")
    val fissure = find("ahzarhoon_entrance_fissure")
    val fissure_rope = find("ahzarhoon_entrance_fissure_withrope")
    val stone = find("zqsecretstone")
    val cave_in = find("secretrubble")
    val loose_rocks = find("secretrubblebook")
    val sacks = find("zqsacks")
    val gallows = find("zqgallows")
    val table = find("zqtableraft")
    val waterfall_rocks = find("zqwaterfallrocks")
    /** The rocks onto Cairn Isle (the `_falloff`/`_inactive` locs around them have no ops). */
    val cairn_isle_rocks = find("zqclimbingrocks")
    val cairn = find("zqrocks")
    val bervirius_dolmen = find("zqdolmen")
    val handholds = find("zqhandholds")
    val palms = listOf(find("zqquest_hidytree"))
    /** `hillsidedoor[l/r]_multi` children: `zqdoor_multi` 1 closed, 2 open. */
    val carved_doors = listOf(find("hillsideclosedl"), find("hillsideclosedr"))
    val open_doors = listOf(find("hillsidedooropenl"), find("hillsidedooropenr"))
    val tomb_gates = listOf(find("zombiequeengateclosedl"), find("zombiequeengateclosedr"))
    val tomb_rocks = find("zq_rashrocks")
    val tomb_doors = listOf(find("thzq_tombrooml1"), find("thzq_tombroomr1"))
    val tomb_dolmen = find("zqrashdolmen")
    val tomb_exits = listOf(find("hillsideexitclosedl"), find("hillsideexitclosedr"))
    val village_gates =
        listOf(
            find("zqshilogateclosedl"),
            find("zqshilogateclosedr"),
            find("zqwoodengateclosed_l"),
            find("zqwoodengateclosed_r"),
        )
    /** `shilo_brokencart` children: before (Search) and after the quest (Climb over). */
    val broken_cart = find("shilo_brokencart_original")
    val broken_cart_open = find("shilo_brokencart_postquest")
    val shilo_cart = find("shilocart")
    val brimhaven_cart = find("brimhavencart")
}

internal object SvVarps : VarpReferences() {
    val zombiequeen = find("zombiequeen")
}

internal object SvVarBits : VarBitReferences() {
    /** The hillside doors (bits 16-17 of `my2arm_perm_1`): 0 jungle plants, 1 carved doors, 2 open. */
    val door_multi = find("zqdoor_multi")
}
