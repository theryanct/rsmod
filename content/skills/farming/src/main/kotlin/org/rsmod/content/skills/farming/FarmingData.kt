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
    enum class Kind(val noun: String, val wateredOffset: Int) {
        Allotment("allotment", 64),
        Herb("herb patch", 0),
        Flower("flower patch", 64),
        Hops("hops patch", 64),
        Tree("tree patch", 0),
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
        /** Level at which [save1] applies (the Wiki gives hops' chances from their level). */
        val saveLevel: Int = 1,
        /** Trees: check-health xp and chopping (Woodcutting level, logs, xp). */
        val checkXp: Double = 0.0,
        val woodLevel: Int = 0,
        val logs: String = "",
        val woodXp: Double = 0.0,
    ) {
        /** Fully grown (for trees: waiting for a health check). */
        val grownValue: Int
            get() = seedValue + cycles

        /** Trees only: health checked (choppable) and stump variants. */
        val checkedValue: Int
            get() = seedValue + cycles + 1

        val stumpValue: Int
            get() = seedValue + cycles + 2

        fun saveChance(level: Int): Double {
            val from = saveLevel.coerceIn(1, 98)
            val progress = (level.coerceIn(from, 99) - from).toDouble() / (99 - from)
            return save1 + (save99 - save1) * progress
        }
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

    private fun hops(
        key: String,
        seed: String,
        seedsPer: Int,
        produce: String,
        level: Int,
        plantXp: Double,
        harvestXp: Double,
        cycles: Int,
        seedValue: Int,
        saveAtLevel: Double,
    ) =
        Crop(
            key = key,
            kind = Kind.Hops,
            seed = seed,
            seedsPer = seedsPer,
            produce = produce,
            level = level,
            plantXp = plantXp,
            harvestXp = harvestXp,
            cycles = cycles,
            minutesPerCycle = 10,
            seedValue = seedValue,
            save1 = saveAtLevel,
            save99 = 70.7,
            saveLevel = level,
        )

    private fun tree(
        key: String,
        level: Int,
        plantXp: Double,
        checkXp: Double,
        cycles: Int,
        seedValue: Int,
        woodLevel: Int,
        woodXp: Double,
    ) =
        Crop(
            key = key,
            kind = Kind.Tree,
            seed = "plantpot_${key}_sapling",
            seedsPer = 1,
            produce = "${key}_logs",
            level = level,
            plantXp = plantXp,
            harvestXp = 0.0,
            cycles = cycles,
            minutesPerCycle = 40,
            seedValue = seedValue,
            save1 = 0.0,
            save99 = 0.0,
            checkXp = checkXp,
            woodLevel = woodLevel,
            logs = "${key}_logs",
            woodXp = woodXp,
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
            hops("barley", "barley_seed", 4, "barley", 3, 8.5, 9.5, 4, 49, 40.6),
            hops(
                "hammerstone",
                "hammerstone_hop_seed",
                4,
                "hammerstone_hops",
                4,
                9.0,
                10.0,
                4,
                4,
                41.4,
            ),
            hops(
                "asgarnian",
                "asgarnian_hop_seed",
                4,
                "asgarnian_hops",
                8,
                10.9,
                12.0,
                5,
                11,
                44.1,
            ),
            hops("jute", "jute_seed", 3, "jute_fibre", 13, 13.0, 14.5, 5, 56, 47.7),
            hops(
                "yanillian",
                "yanillian_hop_seed",
                4,
                "yanillian_hops",
                16,
                14.5,
                16.0,
                6,
                19,
                49.2,
            ),
            tree("oak", 15, 14.0, 467.3, 4, 8, 15, 37.5),
            tree("willow", 30, 25.0, 1456.5, 6, 15, 30, 67.5),
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

    /** A compost bin: its base loc, transmit varbit and varps (state + closed minute). */
    class Bin(val key: String, val loc: String, val transmit: String) {
        val stateVarp = "farming_${key}_state"
        val timeVarp = "farming_${key}_time"
    }

    class Area(
        val key: String,
        val centre: CoordGrid,
        val patches: List<Patch>,
        val bin: Bin? = null,
    )

    private fun area(
        key: String,
        centre: CoordGrid,
        veg1: Int,
        veg2: Int,
        flower: Int,
        herb: Int,
        bin: Int,
    ) =
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
            Bin("${key}_bin", "farming_compost_bin_$bin", "farming_transmit_e"),
        )

    private fun single(key: String, centre: CoordGrid, kind: Kind, loc: String) =
        Area(key, centre, listOf(Patch(key, kind, loc, "farming_transmit_a")))

    val areas: List<Area> =
        listOf(
            area("falador", CoordGrid(3055, 3308), 1, 2, 1, 1, bin = 1),
            area("catherby", CoordGrid(2810, 3463), 3, 4, 2, 2, bin = 2),
            area("ardougne", CoordGrid(2666, 3373), 5, 6, 3, 3, bin = 4),
            area("morytania", CoordGrid(3601, 3525), 7, 8, 4, 4, bin = 3),
            single("tree_taverley", CoordGrid(2936, 3438), Kind.Tree, "farming_tree_patch_1"),
            single("tree_falador", CoordGrid(3004, 3373), Kind.Tree, "farming_tree_patch_2"),
            single("tree_varrock", CoordGrid(3229, 3459), Kind.Tree, "farming_tree_patch_3"),
            single("tree_lumbridge", CoordGrid(3193, 3231), Kind.Tree, "farming_tree_patch_4"),
            single("tree_gnome", CoordGrid(2436, 3415), Kind.Tree, "farming_tree_patch_5"),
            single("hops_yanille", CoordGrid(2576, 3105), Kind.Hops, "farming_hops_patch_1"),
            single("hops_seers", CoordGrid(2811, 3337), Kind.Hops, "farming_hops_patch_2"),
            single("hops_lumbridge", CoordGrid(3229, 3315), Kind.Hops, "farming_hops_patch_3"),
            single("hops_mcgrubor", CoordGrid(2666, 3525), Kind.Hops, "farming_hops_patch_4"),
        )

    val patches: List<Patch> = areas.flatMap { it.patches }

    val bins: List<Bin> = areas.mapNotNull { it.bin }

    /** The tool leprechaun's store (tools, watering can, buckets, compost) in one varp. */
    const val STORE_VARP = "farming_leprechaun_store"

    val varpNames: List<String> =
        patches.flatMap { listOf(it.stateVarp, it.timeVarp) } +
            bins.flatMap { listOf(it.stateVarp, it.timeVarp) } +
            STORE_VARP

    /** Items a compost bin takes for regular compost (supercompost is not in PR2). */
    val compostables: List<String> =
        listOf(
            "weeds",
            "potato",
            "onion",
            "cabbage",
            "sweetcorn",
            "strawberry",
            "unidentified_guam",
            "unidentified_marentill",
            "unidentified_tarromin",
            "unidentified_harralander",
            "marigold",
            "rosemary",
            "nasturtium",
            "woadleaf",
            "barley",
            "hammerstone_hops",
            "asgarnian_hops",
            "jute_fibre",
            "yanillian_hops",
        )

    /** Wiki: a closed bin of compost takes about 40 minutes to rot. */
    const val ROT_MINUTES = 40

    /** Farmed oak/willow stumps grow back after this many minutes (approximation). */
    const val STUMP_MINUTES = 15

    /** Players within this many tiles of an area's centre see its patch states. */
    const val AREA_RADIUS = 40

    const val RAKE_XP = 4.0
}
