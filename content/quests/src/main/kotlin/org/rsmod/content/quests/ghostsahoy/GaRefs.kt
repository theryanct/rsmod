package org.rsmod.content.quests.ghostsahoy

import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType

// Every cache ref Ghosts Ahoy uses (re-check after cache updates).

internal object GaNpcs : NpcReferences() {
    val velorina = find("ahoy_velorina")
    val necrovarus = find("ahoy_necrovarus")
    val crone = find("ahoy_crone")
    val gravingas = find("ahoy_ghost_protestor")
    val villager = find("ahoy_ghost_villager")
    val innkeeper = find("ahoy_ghost_innkeeper")
    val robin = find("ahoy_robin")

    /** Child of ahoy_akharanu_multi (from ahoy_questvar 4); his Trade is a generic shop. */
    val akharanu = find("ahoy_akharanu")
    val old_man = find("ahoy_oldman")
    val captain_dock = find("ahoy_ghost_captain_1")
    val captain_island = find("ahoy_ghost_captain_2")
    val lobster = find("giant_lobster")
    val disciple = find("ahoy_disciple")

    val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Velorina" to velorina,
            "Necrovarus" to necrovarus,
            "Old crone" to crone,
            "Gravingas" to gravingas,
            "Ghost villager" to villager,
            "Ghost innkeeper" to innkeeper,
            "Robin" to robin,
            "Ak-Haranu" to akharanu,
            "Old man" to old_man,
            "Ghost captain" to captain_dock,
            "Ghost disciple" to disciple,
        )
    }
}

internal object GaLocs : LocReferences() {
    val door = find("ahoy_harbour_door")
    val coffin = find("ahoy_coffin")
    val coffin_open = find("ahoy_coffin_open")
    val ectofuntus = find("ahoy_ectofuntus")
    val chest_locked = find("ahoy_chest_locked")
    val chest_closed = find("ahoy_chest_closed")
    val chest_open = find("ahoy_chest_open")
    val mast = find("ahoy_mast")
    val gangplank_on = find("ahoy_gangplank_shipwreck_on")
    val gangplank_off = find("ahoy_gangplank_shipwreck_off")
    val rock = find("ahoy_rock_invisible")
    val captain_skull = find("ahoy_skull_captain")
}

internal object GaObjs : ObjReferences() {
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_enchanted = find("amulet_of_ghostspeak_enchanted")
    val ectophial = find("ectophial")
    val ectophial_empty = find("ectophial_empty")
    val ecto_token = find("ectotoken")
    val coins = find("coins")

    val cup = find("chinacup_empty")
    val cup_tea = find("chinacup_of_nettletea")
    val cup_tea_milky = find("chinacup_of_nettletea_milky")
    val bowl_tea = find("bowl_nettletea")
    val bowl_tea_milky = find("bowl_nettletea_milky")
    val bowl = find("bowl_empty")
    val milk = find("bucket_milk")
    val bucket = find("bucket_empty")
    val water = find("bucket_water")
    val slime = find("bucket_ectoplasm")

    val book = find("ahoy_book_of_haricanto")
    val manual = find("ahoy_translation_manual")
    val robes = find("ahoy_robes_of_necrovarus")
    val petition = find("ahoy_petition")
    val bedsheet = find("ahoy_bedsheet")
    val bedsheet_slimy = find("ahoy_bedsheetgreen")
    val bone_key = find("ahoy_bone_key")
    val chest_key = find("ahoy_chest_key")

    val toy_boat = find("ahoy_toy_boat")
    val toy_boat_repaired = find("ahoy_toy_boat_repaired")
    val silk = find("silk")
    val needle = find("needle")
    val thread = find("thread")
    val knife = find("knife")
    val scraps: List<ObjType> = (1..3).map { find("ahoy_map_scrap_$it") }
    val map = find("ahoy_map_complete")
    val spade = find("spade")

    val longbow = find("oak_longbow")
    val longbow_signed = find("oak_longbow_signed")

    /** The six dyes, in flag colour order 1-6 (0 is the boat's white). */
    val dyes: List<ObjType> =
        listOf("reddye", "bluedye", "yellowdye", "orangedye", "greendye", "purpledye").map {
            find(it)
        }
}

internal object GaSeqs : SeqReferences() {
    val jump = find("human_jump_stones")
}

internal object GaVarBits : VarBitReferences() {
    val quest = find("ahoy_questvar")
    val windspeed = find("ahoy_windspeed")
    val given_manual = find("ahoy_given_manual")
    val given_robes = find("ahoy_given_robes")
    val given_book = find("ahoy_given_book")
    val signatures = find("ahoy_signaturecounter")
    val bow = find("ahoy_subquest_bow")
    val templedoor = find("ahoy_templedoor_unlocked")
    val toyboat = find("ahoy_subquest_toyboat")
    val killed_lobster = find("ahoy_killed_lobster")
    val requested_sheet = find("ahoy_requested_sheet")
}

internal object GaVarps : VarpReferences() {
    /**
     * Server varp (65786): bits 0-5 the shipwreck flag's colours (2 bits per part: top, skull,
     * bottom; 1 red, 2 blue, 3 yellow; 0 not yet rolled), bits 6-14 the toy boat flag (3 bits per
     * part, 0 white or dye 1-6), bits 15-21 Robin's Runedraw debt, bit 22 unlimited Dragontooth
     * trips, bits 23-25 map-scrap chests searched (office, rocky island, lobster).
     */
    val progress = find("ahoy_progress")
}

internal object GaVarpBuilds : VarpBuilder() {
    init {
        build("ahoy_progress")
    }
}
