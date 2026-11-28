package org.rsmod.content.skills.construction

/**
 * Parlour furniture (Construction PR1), from the OSRS Wiki "Parlour" page (levels, materials,
 * xp). Hotspot positions are the parlour template's loc placements in the cache (local tiles of
 * the 8x8 room); furniture loc tiers follow the cache names (`poh_chair1` = crude wooden chair).
 */
internal object ConstructionData {
    /** One hotspot tile: the hotspot loc and its local position in the room. */
    class Spot(val hotspot: String, val x: Int, val z: Int)

    class Furniture(
        val name: String,
        val level: Int,
        val xp: Double,
        val materials: List<Pair<String, Int>>,
        val nails: Int,
        /** Furniture loc built on each hotspot loc type. */
        val locs: Map<String, String>,
    )

    /** A hotspot group (e.g. both bookcase spaces) built and removed together. */
    class Group(
        val key: String,
        val noun: String,
        val spots: List<Spot>,
        val options: List<Furniture>,
    )

    private fun chairs(hotspot: String) =
        listOf(
            Furniture(
                "Crude wooden chair",
                1,
                58.0,
                listOf("woodplank" to 2),
                2,
                mapOf(hotspot to "poh_chair1"),
            ),
            Furniture(
                "Wooden chair",
                8,
                87.0,
                listOf("woodplank" to 3),
                3,
                mapOf(hotspot to "poh_chair2"),
            ),
            Furniture(
                "Rocking chair",
                14,
                87.0,
                listOf("woodplank" to 3),
                3,
                mapOf(hotspot to "poh_chair3"),
            ),
            Furniture(
                "Oak chair",
                19,
                120.0,
                listOf("plank_oak" to 2),
                0,
                mapOf(hotspot to "poh_chair4"),
            ),
        )

    private fun rug(tier: Int) =
        mapOf(
            "poh_parlour_4_middle" to "poh_rugmiddle$tier",
            "poh_parlour_4_side" to "poh_rugside$tier",
            "poh_parlour_4_corner" to "poh_rugcorner$tier",
        )

    val parlour: List<Group> =
        listOf(
            Group("chair1", "chair", listOf(Spot("poh_parlour_1", 2, 4)), chairs("poh_parlour_1")),
            Group("chair2", "chair", listOf(Spot("poh_parlour_2", 5, 4)), chairs("poh_parlour_2")),
            Group("chair3", "chair", listOf(Spot("poh_parlour_3", 4, 3)), chairs("poh_parlour_3")),
            Group(
                "bookcase",
                "bookcase",
                listOf(Spot("poh_parlour_5", 0, 1), Spot("poh_parlour_5", 7, 1)),
                listOf(
                    Furniture(
                        "Wooden bookcase",
                        4,
                        115.0,
                        listOf("woodplank" to 4),
                        4,
                        mapOf("poh_parlour_5" to "poh_bookcase1"),
                    ),
                    Furniture(
                        "Oak bookcase",
                        29,
                        180.0,
                        listOf("plank_oak" to 3),
                        0,
                        mapOf("poh_parlour_5" to "poh_bookcase2"),
                    ),
                ),
            ),
            Group(
                "rug",
                "rug",
                listOf(
                    Spot("poh_parlour_4_middle", 3, 3),
                    Spot("poh_parlour_4_middle", 3, 4),
                    Spot("poh_parlour_4_middle", 4, 3),
                    Spot("poh_parlour_4_middle", 4, 4),
                    Spot("poh_parlour_4_side", 2, 3),
                    Spot("poh_parlour_4_side", 2, 4),
                    Spot("poh_parlour_4_side", 3, 2),
                    Spot("poh_parlour_4_side", 3, 5),
                    Spot("poh_parlour_4_side", 4, 2),
                    Spot("poh_parlour_4_side", 4, 5),
                    Spot("poh_parlour_4_side", 5, 3),
                    Spot("poh_parlour_4_side", 5, 4),
                    Spot("poh_parlour_4_corner", 2, 2),
                    Spot("poh_parlour_4_corner", 2, 5),
                    Spot("poh_parlour_4_corner", 5, 2),
                    Spot("poh_parlour_4_corner", 5, 5),
                ),
                listOf(
                    Furniture("Brown rug", 2, 30.0, listOf("cloth" to 2), 0, rug(1)),
                    Furniture("Rug", 13, 60.0, listOf("cloth" to 4), 0, rug(2)),
                ),
            ),
            Group(
                "fireplace",
                "fireplace",
                listOf(Spot("poh_parlour_6", 3, 7)),
                listOf(
                    Furniture(
                        "Clay fireplace",
                        3,
                        30.0,
                        listOf("softclay" to 3),
                        0,
                        mapOf("poh_parlour_6" to "poh_fireplace_1"),
                    ),
                ),
            ),
            Group(
                "curtains",
                "curtains",
                listOf(
                    Spot("poh_parlour_7", 0, 2),
                    Spot("poh_parlour_7", 0, 5),
                    Spot("poh_parlour_7", 2, 0),
                    Spot("poh_parlour_7", 2, 7),
                    Spot("poh_parlour_7", 5, 0),
                    Spot("poh_parlour_7", 5, 7),
                    Spot("poh_parlour_7", 7, 2),
                    Spot("poh_parlour_7", 7, 5),
                ),
                listOf(
                    Furniture(
                        "Torn curtains",
                        2,
                        132.0,
                        listOf("woodplank" to 3, "cloth" to 3),
                        3,
                        mapOf("poh_parlour_7" to "poh_curtains_1"),
                    ),
                    Furniture(
                        "Curtains",
                        18,
                        225.0,
                        listOf("plank_oak" to 3, "cloth" to 3),
                        0,
                        mapOf("poh_parlour_7" to "poh_curtains_2"),
                    ),
                ),
            ),
        )

    /** Garden hotspots other than the centrepiece (exit portal); hidden outside build mode. */
    val gardenSpots: List<Spot> =
        listOf(
            Spot("poh_crude_garden_2", 1, 5),
            Spot("poh_crude_garden_3", 6, 6),
            Spot("poh_crude_garden_4", 6, 0),
            Spot("poh_crude_garden_5", 0, 0),
            Spot("poh_crude_garden_6", 3, 1),
            Spot("poh_crude_garden_7", 4, 5),
            Spot("poh_garden_8", 6, 5),
        )

    /** Nails in the order they are used up (cheapest first). */
    val nails: List<String> =
        listOf(
            "nails_bronze",
            "nails_iron",
            "nails",
            "nails_black",
            "nails_mithril",
            "nails_adamant",
            "nails_rune",
        )

    val materials: List<String> = listOf("woodplank", "plank_oak", "cloth", "softclay")

    /** Build-menu icon obj per furniture (the cache's poh_* icon objs). */
    val icons: Map<String, String> =
        mapOf(
            "Crude wooden chair" to "poh_armchair_1",
            "Wooden chair" to "poh_armchair_2",
            "Rocking chair" to "poh_armchair_3",
            "Oak chair" to "poh_armchair_4",
            "Wooden bookcase" to "poh_bookcase_1",
            "Oak bookcase" to "poh_bookcase_2",
            "Brown rug" to "poh_rug_1",
            "Rug" to "poh_rug_2",
            "Clay fireplace" to "poh_fireplace_1",
            "Torn curtains" to "poh_curtains_1",
            "Curtains" to "poh_curtains_2",
        )

    /** Display names of material objs for the build menu. */
    val materialNames: Map<String, String> =
        mapOf(
            "woodplank" to "Plank",
            "plank_oak" to "Oak plank",
            "cloth" to "Bolt of cloth",
            "softclay" to "Soft clay",
        )

    /** Bits per group in the furniture varp (tier 0 = nothing built). */
    const val TIER_BITS = 3
}
