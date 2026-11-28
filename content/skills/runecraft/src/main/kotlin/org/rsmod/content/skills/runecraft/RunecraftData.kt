package org.rsmod.content.skills.runecraft

import org.rsmod.map.CoordGrid

/**
 * Runecraft altars (OSRS Wiki: rune pages' Recipe/"Multiple runes", Tiara page, Mysterious ruins).
 * Loc positions come from the cache map; [inside] / [outside] are the arrival tiles next to the
 * altar's exit portal and just outside the ruins.
 */
internal object RunecraftData {
    class Altar(
        val key: String,
        val ruins: String,
        /** The ruins' multiloc variant with "Enter", shown while the matching tiara is worn. */
        val ruinsEnter: String,
        val altar: String,
        val portal: String,
        val talisman: String,
        val tiara: String,
        val rune: String,
        /** Varbit that switches [ruins] to [ruinsEnter] (`rc_no_tally_required_<key>`). */
        val varbit: String,
        val level: Int,
        val xp: Double,
        /** One extra rune per essence at every multiple of this level (e.g. air 11, 22, ...). */
        val multipleEvery: Int,
        val tiaraXp: Double,
        val inside: CoordGrid,
        val outside: CoordGrid,
    )

    private fun altar(
        key: String,
        level: Int,
        xp: Double,
        every: Int,
        tiaraXp: Double,
        inside: CoordGrid,
        outside: CoordGrid,
    ) =
        Altar(
            key = key,
            ruins = "${key}temple_ruined",
            ruinsEnter = "${key}temple_ruined_new",
            altar = "${key}_altar",
            portal = "${key}temple_exit_portal",
            talisman = "${key}_talisman",
            tiara = "tiara_$key",
            rune = "${key}rune",
            varbit = "rc_no_tally_required_$key",
            level = level,
            xp = xp,
            multipleEvery = every,
            tiaraXp = tiaraXp,
            inside = inside,
            outside = outside,
        )

    val altars: List<Altar> =
        listOf(
            altar("air", 1, 5.0, 11, 25.0, CoordGrid(2841, 4830), CoordGrid(2983, 3288)),
            altar("mind", 2, 5.5, 14, 27.5, CoordGrid(2793, 4828), CoordGrid(2980, 3511)),
            altar("water", 5, 6.0, 19, 30.0, CoordGrid(2726, 4832), CoordGrid(3183, 3162)),
            altar("earth", 9, 6.5, 26, 32.5, CoordGrid(2655, 4830), CoordGrid(3304, 3472)),
            altar("fire", 14, 7.0, 35, 35.0, CoordGrid(2577, 4846), CoordGrid(3310, 3252)),
            altar("body", 20, 7.5, 46, 37.5, CoordGrid(2521, 4834), CoordGrid(3053, 3442)),
        )

    /** Rune essence mine (Aubury's teleport) and where its portals return the player. */
    val essenceMine = CoordGrid(2911, 4832)
    val auburyShop = CoordGrid(3253, 3401)

    const val ESSENCE_XP = 5.0
    const val PURE_ESSENCE_MINING = 30
}
