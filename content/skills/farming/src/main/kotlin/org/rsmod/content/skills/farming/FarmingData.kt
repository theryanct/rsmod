package org.rsmod.content.skills.farming

import org.rsmod.map.CoordGrid

/**
 * Farming PR1 data: allotments, herbs and flowers at Falador, Catherby, Ardougne and Port
 * Phasmatys.
 *
 * - Levels, xp, seeds per patch and growth times: OSRS Wiki `Farming info` templates.
 * - Save-a-life chances (level 1 -> 99): the Wiki seed pages ("chance to not consume a harvest
 *   life ... scales from X% at level 1 to Y% at level 99").
 * - Varbit values: decoded from the patch locs' multiloc tables in the cache (e.g. allotment
 *   value 6 = potato seed, 7-9 growing, 10 fully grown, +64 = watered; 0-2 weeds, 3 cleared).
 * - Patch -> transmit varbit letter: the multiloc varbit of each patch loc in the cache.
 */
internal object FarmingData {
    enum class Kind(val noun: String, val wateredOffset: Int, val tool: String) {
        Allotment("allotment", 64, "spade"),
        Herb("herb patch", 0, "hands"),
        Flower("flower patch", 64, "hands"),
    }

    class Crop(
        val key: String,
        val kind: Kind,
        val seed: String,
        val seedsPer: Int,
        val produce: String,
        val level: Int,
        val plantXp: Double,
        val harvestXp: Double,
        /** Growth cycles (stages before fully grown) and minutes per cycle. */
        val cycles: Int,
        val minutesPerCycle: Int,
        /** Varbit value of the freshly planted stage; growing stages follow, then fully grown. */
        val seedValue: Int,
        /** Chance (percent) to keep a harvest life at level 1 / 99; 0 = fixed [fixedYield]. */
        val save1: Double,
        val save99: Double,
        val fixedYield: Int = 0,
    ) {
        val grownValue: Int
            get() = seedValue + cycles

        fun saveChance(level: Int): Double =
            save1 + (save99 - save1) * (level.coerceIn(1, 99) - 1) / 98.0
    }

    private fun allotment(
        key: String,
        level: Int,
        plantXp: Double,
        harvestXp: Double,
        cycles: Int,
        seedValue: Int,
        save1: Double,
    ) =
        Crop(
            key = key,
            kind = Kind.Allotment,
            seed = "${key}_seed",
            seedsPer = 3,
            produce = key,
            level = level,
            plantXp = plantXp,
            harvestXp = harvestXp,
            cycles = cycles,
            minutesPerCycle = 10,
            seedValue = seedValue,
            save1 = save1,
            save99 = 70.7,
        )

    private fun herb(
        key: String,
        seed: String,
        produce: String,
        level: Int,
        plantXp: Double,
        harvestXp: Double,
        seedValue: Int,
        save1: Double,
    ) =
        Crop(
            key = key,
            kind = Kind.Herb,
            seed = seed,
            seedsPer = 1,
            produce = produce,
            level = level,
            plantXp = plantXp,
            harvestXp = harvestXp,
            cycles = 4,
            minutesPerCycle = 20,
            seedValue = seedValue,
            save1 = save1,
            save99 = 31.6,
        )

    private fun flower(
        key: String,
        produce: String,
        level: Int,
        plantXp: Double,
        harvestXp: Double,
        seedValue: Int,
        yield: Int,
    ) =
        Crop(
            key = key,
            kind = Kind.Flower,
            seed = "${key}_seed",
            seedsPer = 1,
            produce = produce,
            level = level,
            plantXp = plantXp,
            harvestXp = harvestXp,
            cycles = 4,
            minutesPerCycle = 5,
            seedValue = seedValue,
            save1 = 0.0,
            save99 = 0.0,
            fixedYield = yield,
        )

    val crops: List<Crop> =
        listOf(
            allotment("potato", 1, 8.0, 9.0, 4, 6, 39.8),
            allotment("onion", 5, 9.5, 10.5, 4, 13, 41.4),
            allotment("cabbage", 7, 10.0, 11.5, 4, 20, 42.2),
            allotment("tomato", 12, 12.5, 14.0, 4, 27, 44.1),
            allotment("sweetcorn", 20, 17.0, 19.0, 6, 34, 34.8),
            allotment("strawberry", 31, 26.0, 29.0, 6, 43, 40.6),
            herb("guam", "guam_seed", "unidentified_guam", 9, 11.0, 12.5, 4, 10.2),
            herb(
                "marrentill",
                "marrentill_seed",
                "unidentified_marentill",
                14,
                13.5,
                15.0,
                11,
                11.3,
            ),
            herb("tarromin", "tarromin_seed", "unidentified_tarromin", 19, 16.0, 18.0, 18, 12.5),
            herb(
                "harralander",
                "harralander_seed",
                "unidentified_harralander",
                26,
                21.5,
                24.0,
                25,
                14.5,
            ),
            flower("marigold", "marigold", 2, 8.5, 47.0, 8, 1),
            flower("rosemary", "rosemary", 11, 12.0, 66.5, 13, 1),
            flower("nasturtium", "nasturtium", 24, 19.5, 111.0, 18, 1),
            flower("woad", "woadleaf", 25, 20.5, 115.5, 23, 3),
            flower("limpwurt", "limpwurt_root", 26, 21.5, 120.0, 28, 3),
        )

    /** A farming patch: its base loc, the transmit varbit that drives its visuals, its varps. */
    class Patch(
        val key: String,
        val kind: Kind,
        val loc: String,
        val transmit: String,
    ) {
        val stateVarp = "farming_${key}_state"
        val timeVarp = "farming_${key}_time"
    }

    class Area(val key: String, val centre: CoordGrid, val patches: List<Patch>)

    private fun area(key: String, centre: CoordGrid, veg1: Int, veg2: Int, flower: Int, herb: Int) =
        Area(
            key,
            centre,
            listOf(
                Patch(
                    "${key}_veg1",
                    Kind.Allotment,
                    "farming_veg_patch_$veg1",
                    "farming_transmit_a",
                ),
                Patch(
                    "${key}_veg2",
                    Kind.Allotment,
                    "farming_veg_patch_$veg2",
                    "farming_transmit_b",
                ),
                Patch(
                    "${key}_flower",
                    Kind.Flower,
                    "farming_flower_patch_$flower",
                    "farming_transmit_c",
                ),
                Patch("${key}_herb", Kind.Herb, "farming_herb_patch_$herb", "farming_transmit_d"),
            ),
        )

    val areas: List<Area> =
        listOf(
            area("falador", CoordGrid(3055, 3308), 1, 2, 1, 1),
            area("catherby", CoordGrid(2810, 3463), 3, 4, 2, 2),
            area("ardougne", CoordGrid(2666, 3373), 5, 6, 3, 3),
            area("morytania", CoordGrid(3601, 3525), 7, 8, 4, 4),
        )

    val patches: List<Patch> = areas.flatMap { it.patches }

    /** Players within this many tiles of an area's centre see its patch states. */
    const val AREA_RADIUS = 40

    const val RAKE_XP = 4.0
}
