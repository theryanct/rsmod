package org.rsmod.content.quests.templeofikov

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences

// Every cache ref Temple of Ikov uses (re-check after cache updates).

internal object IkNpcs : NpcReferences() {
    /** Lucien in the Flying Horse Inn (multinpc on `ikov_lucien_vis`: 0 shows `ikov_lucien1_vis`). */
    val lucien_pub = find("ikov_lucien1")
    val lucien_pub_vis = find("ikov_lucien1_vis")
    /** Lucien at his house (multinpc on `ikov_lucien_vis`: 1 talk only, 2 attackable, 0/3 hidden). */
    val lucien_house = find("ikov_lucien2")
    val lucien_house_talk = find("ikov_lucien2_vis_noattack")
    val lucien_house_fight = find("ikov_lucien2_vis")
    val guardian_male = find("ikov_guardianmale")
    val guardian_female = find("ikov_guardianfemale")
    val guardians = listOf(guardian_male, guardian_female)
    val winelda = find("ikov_winelda")
    val fire_warrior = find("ikov_firewarrior")
}

internal object IkObjs : ObjReferences() {
    val lever = find("ikov_lever")
    val staff = find("ikov_staffofarmardyl")
    val shiny_key = find("ikov_shinykey")
    val pendant_lucien = find("ikov_pendantoflucien")
    val pendant_armadyl = find("ikov_pendantofarmardyl")
    val boots = find("ikov_bootsoflightness")
    /** The boots' worn form (-10lb); the cache has no transform link to it, so it only supplies the weight. */
    val boots_worn = find("ikov_bootsoflightnessworn")
    val ice_arrow = find("ice_arrow")
    val limpwurt = find("limpwurt_root")
    /** Light sources for the dark cellar stairs (inventory or worn). */
    val lights =
        listOf(
            find("lit_candle"),
            find("lit_black_candle"),
            find("torch_lit"),
            find("oil_lamp_lit"),
            find("candle_lantern_lit"),
            find("candle_lantern_black_lit"),
            find("oil_lantern_lit"),
            find("bullseye_lantern_lit"),
            find("tog_sapphire_lantern_lit"),
            find("cave_goblin_mining_helmet_lit"),
        )
}

internal object IkLocs : LocReferences() {
    val lever_bracket = find("ikov_leverbracket")
    val mended_lever = find("ikov_mendedlever")
    val lever_down = find("ikov_leverdown")
    /** The south gate (opened by the mended lever). */
    val lever_gates = listOf(find("ikov_mendedleverdoorl"), find("ikov_mendedleverdoorr"))
    /** The north gate into the Chamber of Fear (needs the Pendant of Lucien). */
    val fear_gates = listOf(find("ikov_dooroffearl"), find("ikov_dooroffearr"))
    val trap_lever = find("ikov_traplever")
    val trap_lever_door = find("ikov_trapleverdoor")
    val trap_ladder = find("ikov_trapladder")
    val fire_warrior_door = find("ikov_firewarriordoor")
    /** Down to the boots cellar (2650,9804) and the two cellar stairs back up. */
    val stairs_down = find("ikov_darkstairsdown")
    val stairs_up = find("ikov_darkstairs")
    val shiny_key_door = find("ikov_shinykeydoor")
    /** The surface trapdoor south of Hemenster (2637,3408) above the lava ladder. */
    val surface_trapdoor = find("ikov_trapdoor_down")
    val chest_closed = find("ikov_chestclosed")
    val chest_open = find("ikov_chestopen")
}

internal object IkVarps : VarpReferences() {
    /** Cache quest varp (26); db end state 80. */
    val ikov = find("ikov")
}

internal object IkVarBits : VarBitReferences() {
    /** Bits 29-30 of `dov_primary`: which Lucien shows (see [IkNpcs]). */
    val lucien_vis = find("ikov_lucien_vis")
}

internal object IkTimers : TimerReferences() {
    /** Custom timer (65565): watches the bridge ends while the player is in the bridge room. */
    val bridge = find("ikov_bridge")
}
