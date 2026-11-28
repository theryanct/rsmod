package org.rsmod.content.other.trails

import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varp.VarpReferences

internal object TrailObjs : ObjReferences() {
    /** The one beginner clue obj: its current step lives in [TrailVarps.beginner_step]. */
    val clue_beginner = find("trail_clue_beginner")
    val casket_beginner = find("trail_reward_casket_beginner")
    val casket_easy = find("trail_reward_casket_easy")
    val casket_medium = find("trail_reward_casket_medium")
    val casket_hard = find("trail_reward_casket_hard")
    val casket_elite = find("trail_reward_casket_elite")
    val casket_master = find("trail_reward_casket_master")

    /** Coordinate clue navigation: "Look through" the sextant with a watch and chart. */
    val sextant = find("trail_sextant")
    val watch = find("trail_watch")
    val chart = find("trail_chart")
    /** Beginner hot/cold "strange device" (from Reldo). */
    val strange_device = find("beginner_device")

    // Charlie the Tramp's beginner requests (cluehelper_skillchallenge_beginner_0..7).
    val trout = find("trout")
    val pike = find("pike")
    val raw_herring = find("raw_herring")
    val raw_trout = find("raw_trout")
    val iron_ore = find("iron_ore")
    val iron_dagger = find("iron_dagger")
    val leather_body = find("leather_armour")
    val leather_chaps = find("leather_chaps")

    // STASH unit building.
    val plank = find("woodplank")
    val hammer = find("hammer")
    val saw = find("poh_saw")
    /** Any one metal, ten of it (dragon nails don't work). */
    val nails =
        listOf(
            find("nails_bronze"),
            find("nails_iron"),
            find("nails_black"),
            find("nails_mithril"),
            find("nails_adamant"),
            find("nails_rune"),
        )
}

internal object TrailSeqs : SeqReferences() {
    val build = find("human_poh_build")
}

internal object TrailNpcs : NpcReferences() {
    val uri = find("trail_beginner_uri")
}

internal object TrailInterfaces : InterfaceReferences() {
    val cluetext = find("trail_cluetext")
    val rewardscreen = find("trail_rewardscreen")

    // Map clue interfaces, matched to dig spots in [MapInterfaces].
    val map01 = find("trail_map01")
    val map02 = find("trail_map02")
    val map03 = find("trail_map03")
    val map06 = find("trail_map06")
    val map11 = find("trail_map11")
    val easy_map006 = find("trail_clue_easy_map006")
}

internal object TrailComponents : ComponentReferences() {
    val cluetext = find("trail_cluetext:text")
}

internal object TrailInvs : InvReferences() {
    /** What the reward screen lists (its onload draws this inv). */
    val reward = find("trail_rewardinv")
}

/** Custom (ui range 65310-65324), saved per character. */
internal object TrailVarps : VarpReferences() {
    /** The beginner clue's current step: its cluehelper dbrow id (0 = not chosen yet). */
    val beginner_step = find("trail_beginner_step")
    /** Steps left on the current trail, counting the one in hand (0 = not chosen yet). */
    val beginner_remaining = find("trail_beginner_remaining")
    val easy_remaining = find("trail_easy_remaining")
    val beginner_completed = find("trail_beginner_completed")
    val easy_completed = find("trail_easy_completed")
    /** STASH units holding their emote outfit: bit = the unit's index among its tier's units. */
    val stash_beginner = find("trail_stash_beginner")
    val stash_easy = find("trail_stash_easy")
    val medium_remaining = find("trail_medium_remaining")
    val hard_remaining = find("trail_hard_remaining")
    val elite_remaining = find("trail_elite_remaining")
    val master_remaining = find("trail_master_remaining")
    val medium_completed = find("trail_medium_completed")
    val hard_completed = find("trail_hard_completed")
    val elite_completed = find("trail_elite_completed")
    val master_completed = find("trail_master_completed")
}

internal object TrailVarpBuilder : VarpBuilder() {
    init {
        build("trail_beginner_step")
        build("trail_beginner_remaining")
        build("trail_easy_remaining")
        build("trail_beginner_completed")
        build("trail_easy_completed")
        build("trail_stash_beginner")
        build("trail_stash_easy")
        build("trail_medium_remaining")
        build("trail_hard_remaining")
        build("trail_elite_remaining")
        build("trail_master_remaining")
        build("trail_medium_completed")
        build("trail_hard_completed")
        build("trail_elite_completed")
        build("trail_master_completed")
    }
}
