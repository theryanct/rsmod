package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.questState
import org.rsmod.game.entity.Player
import org.rsmod.game.type.varp.VarpType

/*
 * The Fremennik Trials' state. The cache quest varp `viking` only holds 0 / 1 (started) / 10
 * (dbrow quest_fremenniktrials endstate); everything else is in server varps 65955-65962:
 * - ft_votes: bits 0-6 the seven votes (Trial.bit), 7-13 each trial accepted, 14-17 / 18-21 the
 *   Fremennik name's prefix / suffix, 22 the name assigned.
 * - ft_manni: 0 lost the first contest, 1 lit object in the pipe, 2 keg switched.
 * - ft_olaf: 0 Lalli named Askeladden, 1 Askeladden gave a rock, 2 Lalli refused the rock (soup
 *   plan), 3-6 rock/potato/cabbage/onion in the stew, 7 soup tasted, 8 fleece given, 9 passed the
 *   bouncer (backstage).
 * - ft_sigli: 0-2 the Draugen's spot, 3 spot picked, 4 Draugen captured.
 * - ft_sigmund: 0-3 how many of the chain have been asked (0-13), 4-16 each link's item handed
 *   over (Link order).
 * - ft_swensen: 0-2 the maze step (rooms passed), 3 lost in a dark room.
 * - ft_peer: 0-2 the riddle (1-6), 3 riddle solved, 4-12 searched spots, 14 vase taken, 15 mural
 *   finished, 17 / 18 old red / red disk in the mural.
 * - ft_thorvald: 0-2 Koschei's current form (0 none, 1-4), 3 three forms beaten.
 */

internal enum class Trial(val bit: Int, val display: String) {
    Manni(0, "Manni the Reveller"),
    Olaf(1, "Olaf the Bard"),
    Sigli(2, "Sigli the Huntsman"),
    Sigmund(3, "Sigmund the Merchant"),
    Swensen(4, "Swensen the Navigator"),
    Peer(5, "Peer the Seer"),
    Thorvald(6, "Thorvald the Warrior");

    val voteMask: Int
        get() = 1 shl bit

    val acceptMask: Int
        get() = 1 shl (bit + 7)
}

internal const val FT_STARTED = 1
internal const val FT_COMPLETE = 10

internal fun Player.ftGet(varp: VarpType): Int = vars[varp]

internal fun Player.ftHas(varp: VarpType, mask: Int): Boolean = (vars[varp] and mask) != 0

internal fun Player.ftSet(varp: VarpType, mask: Int) {
    VarPlayerIntMapSetter.set(this, varp, vars[varp] or mask)
}

internal fun Player.ftClear(varp: VarpType, mask: Int) {
    VarPlayerIntMapSetter.set(this, varp, vars[varp] and mask.inv())
}

internal fun Player.ftField(varp: VarpType, shift: Int, width: Int): Int =
    (vars[varp] ushr shift) and ((1 shl width) - 1)

internal fun Player.ftSetField(varp: VarpType, shift: Int, width: Int, value: Int) {
    val mask = ((1 shl width) - 1) shl shift
    VarPlayerIntMapSetter.set(this, varp, (vars[varp] and mask.inv()) or ((value shl shift) and mask))
}

internal val Player.ftState: Int
    get() = questState(FremennikTrials.FREMENNIK_TRIALS)

internal val Player.ftStarted: Boolean
    get() = ftState in FT_STARTED until FT_COMPLETE

internal val Player.ftComplete: Boolean
    get() = isQuestComplete(FremennikTrials.FREMENNIK_TRIALS)

internal fun Player.accepted(trial: Trial): Boolean = ftHas(FtVarps.votes, trial.acceptMask)

internal fun Player.voted(trial: Trial): Boolean = ftHas(FtVarps.votes, trial.voteMask)

internal fun Player.accept(trial: Trial) = ftSet(FtVarps.votes, trial.acceptMask)

internal fun Player.giveVote(trial: Trial) = ftSet(FtVarps.votes, trial.voteMask or trial.acceptMask)

internal val Player.voteCount: Int
    get() = Trial.entries.count { voted(it) }

/** Doing [trial] right now: quest started, trial accepted, vote not yet earned. */
internal fun Player.onTrial(trial: Trial): Boolean = ftStarted && accepted(trial) && !voted(trial)

/** The merchant's chain of favours is open (Sigmund asked, flower not yet delivered). */
internal val Player.merchantChainActive: Boolean
    get() = onTrial(Trial.Sigmund)

/** "Bal"..."Ton" + "dar"..."vald" (OSRS Wiki, Fremennik name). */
internal val FREMENNIK_PREFIXES =
    listOf("Bal", "Bar", "Dal", "Dar", "Den", "Dok", "Jar", "Jik", "Lar", "Rak", "Ral", "Ril", "Sig", "Tal", "Thor", "Ton")

internal val FREMENNIK_SUFFIXES =
    listOf("dar", "dor", "dur", "kal", "kar", "kir", "kur", "lah", "lak", "lim", "lor", "rak", "tin", "ton", "tor", "vald")

internal val Player.fremennikName: String
    get() {
        if (!ftHas(FtVarps.votes, NAME_SET)) return displayName
        val prefix = ftField(FtVarps.votes, NAME_PREFIX_SHIFT, 4)
        val suffix = ftField(FtVarps.votes, NAME_SUFFIX_SHIFT, 4)
        return FREMENNIK_PREFIXES[prefix] + FREMENNIK_SUFFIXES[suffix]
    }

internal const val NAME_PREFIX_SHIFT = 14
internal const val NAME_SUFFIX_SHIFT = 18
internal const val NAME_SET = 1 shl 22

// Every cache ref The Fremennik Trials uses (re-check after cache updates).

internal object FtNpcs : NpcReferences() {
    /** `viking_brundt` is a multi (vikingexile) whose children before The Fremennik Exiles are this. */
    val brundt = find("viking_brundt_child")
    val manni = find("viking_reveller_3")
    val olaf = find("viking_olaf")
    val sigli = find("viking_sigli")
    val sigmund = find("viking_sigmund")
    val swensen = find("viking_hallifred")
    val peer = find("viking_peer")
    val thorvald = find("viking_thorvald")

    val thora = find("viking_longhall_barkeep")
    val yrsa = find("viking_clothing_shopkeeper")
    val skulgrimen = find("viking_weapons_salesman")
    val fisherman = find("viking_fisherman1")
    val sailor = find("viking_sailor")
    /** Children of the `viking_askelapen` multi (askelladen_hasop). */
    val askeladdens = listOf(find("viking_askelladen_norockop"), find("viking_askelladen_rockop"))

    val lalli = find("viking_lalli_troll")
    val fossegrimen = find("viking_lake_spirit")
    val bouncer = find("viking_longhall_bouncer")
    val workman = find("vt_council_workmen")
    val poison_salesman = find("poison_salesman")
    val misc_sailor = find("misc_sailor")

    val freidir = find("viking_man2")
    val borrokar = find("viking_man3")
    val lanzig = find("viking_man4")
    val lensa = find("viking_woman2")

    val draugen = find("viking_draugen")
    val koschei = listOf(find("viking_enemy1"), find("viking_enemy2"), find("viking_enemy3"), find("viking_enemy4"))
}

internal object FtObjs : ObjReferences() {
    val coins = find("coins")
    val knife = find("knife")
    val tinderbox = find("tinderbox")
    val beer = find("beer")
    val beer_tankard = find("viking_tankard_full")
    val herring = find("herring")
    val raw_sardine = find("raw_sardine")
    val ball_of_wool = find("ball_of_wool")
    val axes =
        listOf(
            find("bronze_axe"),
            find("iron_axe"),
            find("steel_axe"),
            find("black_axe"),
            find("mithril_axe"),
            find("adamant_axe"),
            find("rune_axe"),
            find("dragon_axe"),
            find("crystal_axe"),
        )
    val potato = find("potato")
    val cabbage = find("cabbage")
    val onion = find("onion")
    val raw_shark = find("raw_shark")
    val raw_manta_ray = find("raw_mantaray")
    val raw_sea_turtle = find("raw_seaturtle")
    val raw_bass = find("raw_bass")

    // Manni
    val keg_of_beer = find("viking_beerkeg")
    val low_alcohol_keg = find("viking_low_alcahol_beerkeg")
    val strange_object = find("viking_firecracker")
    val lit_strange_object = find("viking_firecracker_lit")

    // Olaf
    val branch = find("viking_musical_tree_branch")
    val unstrung_lyre = find("viking_unstrung_lyre")
    val lyre = find("viking_strung_lyre")
    val enchanted_lyre = find("viking_enchanted_strung_lyre")
    val golden_fleece = find("viking_golden_fleece")
    val golden_wool = find("viking_golden_wool")
    val pet_rock = find("vt_useless_rock")

    // Sigli
    val talisman = find("viking_draugen_talisman")
    val talisman_charged = find("viking_draugen_talisman_uncharged")

    // Sigmund's chain (Wiki names in comments)
    val flower = find("viking_rare_flower") // Exotic flower
    val ballad = find("viking_song") // Fremennik ballad
    val boots = find("viking_new_boots") // Sturdy boots
    val hunting_map = find("viking_map_to_hunting_grounds") // Tracking map
    val bowstring = find("viking_bowstring") // Custom bow string
    val fish = find("viking_unique_fish") // Unusual fish
    val sea_map = find("viking_another_map") // Sea fishing map
    val forecast = find("viking_weather_forecast") // Weather forecast
    val token = find("viking_champion_token") // Champions token
    val cocktail = find("viking_legendary_cocktail") // Legendary cocktail
    val fiscal_statement = find("viking_promissary_note") // Fiscal statement (3708)
    val promissory_note = find("viking_promissary_note2") // Promissory note (3709)
    val contract = find("viking_promissary_note3") // Warriors' contract (3710)

    // Peer's house
    val magnet = find("viking_pointless_magnet")
    val thread = find("viking_pointless_thread")
    val small_pick = find("viking_pointless_pick")
    val toy_ship = find("viking_pointless_shiptoy")
    /** Index = fifths of water (0 empty .. 5 full). */
    val buckets =
        listOf(
            find("viking_bucket_empty"),
            find("viking_bucket_1"),
            find("viking_bucket_2"),
            find("viking_bucket_3"),
            find("viking_bucket_4"),
            find("viking_bucket_5"),
        )
    val bucket_frozen = find("viking_bucket_frozen")
    /** Index = thirds of water (0 empty .. 3 full). */
    val jugs = listOf(find("viking_jug_empty"), find("viking_jug_1"), find("viking_jug_2"), find("viking_jug_3"))
    val jug_frozen = find("viking_jug_frozen")
    val vase = find("viking_airtight_vase")
    val vase_water = find("viking_airtight_vase_water")
    val vase_frozen = find("viking_airtight_vase_frozen")
    val vase_lid = find("viking_vase_lid")
    val vase_sealed = find("viking_airtight_vase_with_lid")
    val vase_sealed_water = find("viking_airtight_vase_with_lid_water")
    val vase_sealed_frozen = find("viking_airtight_vase_with_lid_frozen")
    val frozen_key = find("viking_key_in_ice")
    val seers_key = find("viking_key")
    val red_herring = find("viking_red_herring")
    val red_goop = find("viking_red_splat")
    val wooden_disk = find("viking_uncoloured_wooden_coin")
    val red_disk = find("viking_red_wooden_coin")
    val old_red_disk = find("viking_red_wooden_coin_old")
}

internal object FtLocs : LocReferences() {
    val swaying_tree = find("viking_musical_tree")
    val strange_altar = find("viking_lake_shrine_altar")
    val lalli_stew = find("viking_troll_cauldron")
    val backstage_door = find("viking_bard_backstage_door")
    val pipe = find("viking_pipe_end_longhall")

    val maze_top = find("vt_mazeladdertopentrance")
    val maze_entrance = find("vt_mazeladderentrance")
    val maze_exit = find("vt_mazeladderexit")
    val maze_escape = find("vt_mazeladderescapeladder")
    val maze_portals =
        listOf(
            find("vt_mazeportal_1"),
            find("vt_mazeportal_2"),
            find("vt_mazeportal_3"),
            find("vt_mazeportal_4"),
            find("vt_mazeportal_5"),
            find("vt_mazeportal_6"),
            find("vt_mazeportal_7"),
        )
    val maze_portal_wrong = find("vt_mazeportal_wrong")

    val warrior_ladder = find("viking_warrior_ladder")
    val arena_ladder = find("viking_warrior_ladder_up")

    val seers_door_front = find("viking_seers_door1")
    val seers_door_back = find("viking_seers_door2")
    val seer_up_ladder = find("viking_seer_up_ladder")
    val seer_down_ladder = find("viking_seer_down_ladder")
    val trapdoor_open = find("viking_seer_trapdoor_open")
    val trapdoor_closed = find("viking_seer_trapdoor_closed")
    val chest = find("viking_seer_chest_closed")
    val chest_scales = find("viking_seer_chest_closed_scales")
    val bookcase = find("viking_seer_bookcase")
    val range = find("viking_seer_range")
    val drain = find("viking_seers_drain")
    val tap = find("viking_seers_tap")
    val cupboard = find("viking_cupboardhigh")
    val mural = find("viking_seers_mural")
    val mural_done = find("viking_seers_mural_done")
    val unicorn_head = find("viking_unimountedhead")
    val bull_head = find("viking_bullmountedhead")
    val boxes = find("viking_seerboxes_1")
    val boxes_east = find("viking_seerboxes_3")
    val crate = find("viking_seercrate")
    val crate_old = find("viking_seercrate_2")
    val frozen_table = find("viking_small_table_frozen")
    val rellekka_spinning_wheel = find("viking_spinningwheel")
}

internal object FtVarps : VarpReferences() {
    val quest = find("viking")
    val votes = find("ft_votes")
    val manni = find("ft_manni")
    val olaf = find("ft_olaf")
    val sigli = find("ft_sigli")
    val sigmund = find("ft_sigmund")
    val swensen = find("ft_swensen")
    val peer = find("ft_peer")
    val thorvald = find("ft_thorvald")

    val trials: List<VarpType>
        get() = listOf(votes, manni, olaf, sigli, sigmund, swensen, peer, thorvald)
}

internal object FtVarpBuilds : VarpBuilder() {
    init {
        build("ft_votes")
        build("ft_manni")
        build("ft_olaf")
        build("ft_sigli")
        build("ft_sigmund")
        build("ft_swensen")
        build("ft_peer")
        build("ft_thorvald")
    }
}

internal object FtTimers : TimerReferences() {
    /** The lit strange object's fuse (70-100 seconds). */
    val firecracker = find("ft_firecracker")
}
