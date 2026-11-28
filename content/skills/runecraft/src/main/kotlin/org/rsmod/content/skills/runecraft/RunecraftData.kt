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
        /** `null` for walk-up altars with no ruins (astral). */
        val ruins: String?,
        /** The ruins' multiloc variant with "Enter", shown while the matching tiara is worn. */
        val ruinsEnter: String?,
        val altar: String,
        val portal: String?,
        val talisman: String?,
        val tiara: String?,
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
        /** Members runes bind pure essence only (rune essence works for the six F2P runes). */
        val pureOnly: Boolean = false,
        /**
         * [inside] / [outside] are Wiki map pins (altar / ruins) rather than exact arrival tiles:
         * arrive on the nearest walkable tile.
         */
        val pinned: Boolean = false,
    )

    private fun altar(
        key: String,
        level: Int,
        xp: Double,
        every: Int,
        tiaraXp: Double,
        inside: CoordGrid,
        outside: CoordGrid,
        pureOnly: Boolean = false,
        pinned: Boolean = false,
        ruinsEnter: String = "${key}temple_ruined_new",
    ) =
        Altar(
            key = key,
            ruins = "${key}temple_ruined",
            ruinsEnter = ruinsEnter,
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
            pureOnly = pureOnly,
            pinned = pinned,
        )

    val altars: List<Altar> =
        listOf(
            altar("air", 1, 5.0, 11, 25.0, CoordGrid(2841, 4830), CoordGrid(2983, 3288)),
            altar("mind", 2, 5.5, 14, 27.5, CoordGrid(2793, 4828), CoordGrid(2980, 3511)),
            altar("water", 5, 6.0, 19, 30.0, CoordGrid(2726, 4832), CoordGrid(3183, 3162)),
            altar("earth", 9, 6.5, 26, 32.5, CoordGrid(2655, 4830), CoordGrid(3304, 3472)),
            altar("fire", 14, 7.0, 35, 35.0, CoordGrid(2577, 4846), CoordGrid(3310, 3252)),
            altar("body", 20, 7.5, 46, 37.5, CoordGrid(2521, 4834), CoordGrid(3053, 3442)),
            // PR2 (OSRS Wiki Runecraft table, "Multiple runes", Tiara xp; altar/ruins map pins).
            // One extra rune per essence at the listed level (cosmic 59 ... death 99); wrath none.
            altar("cosmic", 27, 8.0, 59, 40.0, CoordGrid(2141, 4832), CoordGrid(2407, 4376), true, true),
            altar("chaos", 35, 8.5, 74, 42.5, CoordGrid(2270, 4841), CoordGrid(3059, 3590), true, true),
            altar("nature", 44, 9.0, 91, 45.0, CoordGrid(2399, 4840), CoordGrid(2868, 3018), true, true),
            altar("law", 54, 9.5, 95, 47.5, CoordGrid(2463, 4831), CoordGrid(2857, 3380), true, true),
            altar("death", 65, 10.0, 99, 50.0, CoordGrid(2204, 4835), CoordGrid(1859, 4638), true, true),
            altar(
                "wrath", 95, 8.0, NO_MULTIPLE, 52.5, CoordGrid(2334, 4833), CoordGrid(2445, 2824), true, true,
                ruinsEnter = "wrathtemple_ruined_1op",
            ),
            // Astral: a walk-up altar on Lunar Isle (Lunar Diplomacy skipped), no ruins/talisman.
            Altar(
                key = "astral", ruins = null, ruinsEnter = null, altar = "astral_altar", portal = null,
                talisman = null, tiara = null, rune = "astralrune", varbit = "", level = 40, xp = 8.7,
                multipleEvery = 82, tiaraXp = 0.0, inside = CoordGrid(2157, 3863),
                outside = CoordGrid(2157, 3863), pureOnly = true, pinned = true,
            ),
        )

    /**
     * Combination runes (OSRS Wiki "Combination runes"): use the [talisman]'s element talisman on
     * the [altar] with pure essence and one [rune] per essence; 50% of binds fail without a binding
     * necklace (magic emerald necklace) worn. xp is the Wiki per-altar value.
     */
    class Combo(val product: String, val altar: String, val talisman: String, val rune: String, val level: Int, val xp: Double)

    val combos: List<Combo> =
        listOf(
            Combo("mistrune", "water", "air", "airrune", 6, 8.5),
            Combo("mistrune", "air", "water", "waterrune", 6, 8.0),
            Combo("dustrune", "earth", "air", "airrune", 10, 9.0),
            Combo("dustrune", "air", "earth", "earthrune", 10, 8.3),
            Combo("mudrune", "earth", "water", "waterrune", 13, 9.5),
            Combo("mudrune", "water", "earth", "earthrune", 13, 9.3),
            Combo("smokerune", "fire", "air", "airrune", 15, 9.5),
            Combo("smokerune", "air", "fire", "firerune", 15, 8.5),
            Combo("steamrune", "fire", "water", "waterrune", 19, 10.0),
            Combo("steamrune", "water", "fire", "firerune", 19, 9.5),
            Combo("lavarune", "fire", "earth", "earthrune", 23, 10.5),
            Combo("lavarune", "earth", "fire", "firerune", 23, 10.0),
        )

    /** No multiple-rune level within 99. */
    private const val NO_MULTIPLE = 100

    /** Rune essence mine (Aubury's teleport) and where its portals return the player. */
    val essenceMine = CoordGrid(2911, 4832)
    val auburyShop = CoordGrid(3253, 3401)

    const val ESSENCE_XP = 5.0
    const val PURE_ESSENCE_MINING = 30
}
