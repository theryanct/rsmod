package org.rsmod.content.skills.construction

/**
 * Mahogany Homes houses, generated from the rev-243 loc dump (hotspot multilocs) and the Wiki
 * "Mahogany Homes/Mechanics" layouts (one slot mask per layout, bit n = hotspot n + 1). Plank
 * counts per furniture type follow the main Wiki page; range, sink and bath repairs take a steel bar.
 */
internal object MahoganyHomesData {
    class Spot(
        val slot: Int,
        val base: String,
        val broken: String,
        val editable: String?,
        val empty: String?,
        val built: List<String>,
        val label: String,
        val planks: Int,
        val steel: Boolean,
    ) {
        val repair: Boolean get() = empty == null
    }

    class House(
        val owner: String,
        val client: String,
        val town: String,
        val spots: List<Spot>,
        /** Per tier (Beginner, Novice, Adept, Expert): possible hotspot masks. */
        val layouts: List<List<Int>>,
    )

    val houses: List<House> =
        listOf(
            House(
                "Bob",
                "con_contract_client_1",
                "Varrock",
                listOf(
                    // 3237, 3486, 0
                    Spot(1, "con_contract_varrock_north_hotspot1", "con_contract_varrock_north_diningtable_broken", "con_contract_varrock_north_diningtable_editable", "con_contract_diningtable_empty", listOf("con_contract_varrock_north_diningtable_regular", "con_contract_varrock_north_diningtable_oak", "con_contract_varrock_north_diningtable_teak", "con_contract_varrock_north_diningtable_mahogany"), "large table", 4, false),
                    // 3235, 3483, 0
                    Spot(2, "con_contract_varrock_north_hotspot2", "con_contract_varrock_north_clock_broken", null, null, emptyList(), "grandfather clock", 1, false),
                    // 3235, 3486, 0
                    Spot(3, "con_contract_varrock_north_hotspot3", "con_contract_varrock_north_cabinet1_broken", "con_contract_varrock_north_cabinet1_editable", "con_contract_cabinet_empty", listOf("con_contract_varrock_north_cabinet1_regular", "con_contract_varrock_north_cabinet1_oak", "con_contract_varrock_north_cabinet1_teak", "con_contract_varrock_north_cabinet1_mahogany"), "cabinet", 2, false),
                    // 3235, 3487, 0
                    Spot(4, "con_contract_varrock_north_hotspot4", "con_contract_varrock_north_cabinet2_broken", "con_contract_varrock_north_cabinet2_editable", "con_contract_cabinet_empty", listOf("con_contract_varrock_north_cabinet2_regular", "con_contract_varrock_north_cabinet2_oak", "con_contract_varrock_north_cabinet2_teak", "con_contract_varrock_north_cabinet2_mahogany"), "cabinet", 2, false),
                    // 3235, 3484, 0
                    Spot(5, "con_contract_varrock_north_hotspot5", "con_contract_varrock_north_bookcase_broken", "con_contract_varrock_north_bookcase_editable", "con_contract_bookcase_empty", listOf("con_contract_varrock_north_bookcase_regular", "con_contract_varrock_north_bookcase_oak", "con_contract_varrock_north_bookcase_teak", "con_contract_varrock_north_bookcase_mahogany"), "bookcase", 2, false),
                    // 3235, 3489, 0
                    Spot(6, "con_contract_varrock_north_hotspot6", "con_contract_varrock_north_bookcase_broken", "con_contract_varrock_north_bookcase_editable", "con_contract_bookcase_empty", listOf("con_contract_varrock_north_bookcase_regular", "con_contract_varrock_north_bookcase_oak", "con_contract_varrock_north_bookcase_teak", "con_contract_varrock_north_bookcase_mahogany"), "bookcase", 2, false),
                    // 3242, 3487, 1
                    Spot(7, "con_contract_varrock_north_hotspot7", "con_contract_varrock_north_wardrobe_broken", "con_contract_varrock_north_wardrobe_editable", "con_contract_wardrobe_empty", listOf("con_contract_varrock_north_wardrobe_regular", "con_contract_varrock_north_wardrobe_oak", "con_contract_varrock_north_wardrobe_teak", "con_contract_varrock_north_wardrobe_mahogany"), "wardrobe", 2, false),
                    // 3242, 3486, 1
                    Spot(8, "con_contract_varrock_north_hotspot8", "con_contract_varrock_north_drawers_broken", "con_contract_varrock_north_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_varrock_north_drawers_regular", "con_contract_varrock_north_drawers_oak", "con_contract_varrock_north_drawers_teak", "con_contract_varrock_north_drawers_mahogany"), "drawers", 2, false),
                ),
                listOf(listOf(245, 207, 63), listOf(63, 245, 207), listOf(245, 255, 207), listOf(255, 253)),
            ),
            House(
                "Jeff",
                "con_contract_client_2",
                "Varrock",
                listOf(
                    // 3238, 3452, 0
                    Spot(1, "con_contract_varrock_mid_hotspot1", "con_contract_varrock_mid_table_broken", "con_contract_varrock_mid_table_editable", "con_contract_table_empty", listOf("con_contract_varrock_mid_table_regular", "con_contract_varrock_mid_table_oak", "con_contract_varrock_mid_table_teak", "con_contract_varrock_mid_table_mahogany"), "table", 3, false),
                    // 3242, 3454, 0
                    Spot(2, "con_contract_varrock_mid_hotspot2", "con_contract_varrock_north_bookcase_broken", "con_contract_varrock_north_bookcase_editable", "con_contract_bookcase_empty", listOf("con_contract_varrock_north_bookcase_regular", "con_contract_varrock_north_bookcase_oak", "con_contract_varrock_north_bookcase_teak", "con_contract_varrock_north_bookcase_mahogany"), "bookcase", 2, false),
                    // 3241, 3447, 0
                    Spot(3, "con_contract_varrock_mid_hotspot3", "con_contract_varrock_mid_shelves_broken", "con_contract_varrock_mid_shelves_editable", "con_contract_shelves_empty", listOf("con_contract_varrock_mid_shelves_regular", "con_contract_varrock_mid_shelves_oak", "con_contract_varrock_mid_shelves_teak", "con_contract_varrock_mid_shelves_mahogany"), "shelves", 2, false),
                    // 3239, 3453, 1
                    Spot(4, "con_contract_varrock_mid_hotspot4", "con_contract_varrock_mid_bed_broken", "con_contract_varrock_mid_bed_editable", "con_contract_doublebed_empty", listOf("con_contract_varrock_mid_bed_regular", "con_contract_varrock_mid_bed_oak", "con_contract_varrock_mid_bed_teak", "con_contract_varrock_mid_bed_mahogany"), "bed", 3, false),
                    // 3242, 3454, 1
                    Spot(5, "con_contract_varrock_mid_hotspot5", "con_contract_varrock_north_drawers_broken", "con_contract_varrock_north_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_varrock_north_drawers_regular", "con_contract_varrock_north_drawers_oak", "con_contract_varrock_north_drawers_teak", "con_contract_varrock_north_drawers_mahogany"), "drawers", 2, false),
                    // 3241, 3446, 1
                    Spot(6, "con_contract_varrock_mid_hotspot6", "con_contract_varrock_mid_dresser_broken", "con_contract_varrock_mid_dresser_editable", "con_contract_dresser_empty", listOf("con_contract_varrock_mid_dresser_regular", "con_contract_varrock_mid_dresser_oak", "con_contract_varrock_mid_dresser_teak", "con_contract_varrock_mid_dresser_mahogany"), "dresser", 2, false),
                    // 3238, 3454, 1
                    Spot(7, "con_contract_varrock_mid_hotspot7", "con_contract_varrock_mid_mirror_broken", null, null, emptyList(), "mirror", 1, false),
                    // 3242, 3447, 1
                    Spot(8, "con_contract_varrock_mid_hotspot8", "con_contract_varrock_mid_chair_broken", "con_contract_varrock_mid_chair_editable", "con_contract_chair_empty", listOf("con_contract_varrock_mid_chair_regular", "con_contract_varrock_mid_chair_oak", "con_contract_varrock_mid_chair_teak", "con_contract_varrock_mid_chair_mahogany"), "chair", 1, false),
                ),
                listOf(listOf(252, 231, 95), listOf(252, 95, 231), listOf(255, 231, 95), listOf(255, 191)),
            ),
            House(
                "Sarah",
                "con_contract_client_3",
                "Varrock",
                listOf(
                    // 3237, 3384, 0
                    Spot(1, "con_contract_varrock_south_hotspot1", "con_contract_varrock_south_medium_table_broken", "con_contract_varrock_south_medium_table_editable", "con_contract_table_empty", listOf("con_contract_varrock_south_medium_table_regular", "con_contract_varrock_south_medium_table_oak", "con_contract_varrock_south_medium_table_teak", "con_contract_varrock_south_medium_table_mahogany"), "table", 3, false),
                    // 3233, 3384, 0
                    Spot(2, "con_contract_varrock_south_hotspot2", "con_contract_varrock_south_medium_bed_broken", "con_contract_varrock_south_medium_bed_editable", "con_contract_singlebed_empty", listOf("con_contract_varrock_south_medium_bed_regular", "con_contract_varrock_south_medium_bed_oak", "con_contract_varrock_south_medium_bed_teak", "con_contract_varrock_south_medium_bed_mahogany"), "bed", 2, false),
                    // 3233, 3386, 0
                    Spot(3, "con_contract_varrock_south_hotspot3", "con_contract_varrock_mid_dresser_broken", "con_contract_varrock_mid_dresser_editable", "con_contract_dresser_empty", listOf("con_contract_varrock_mid_dresser_regular", "con_contract_varrock_mid_dresser_oak", "con_contract_varrock_mid_dresser_teak", "con_contract_varrock_mid_dresser_mahogany"), "dresser", 2, false),
                    // 3233, 3382, 0
                    Spot(4, "con_contract_varrock_south_hotspot4", "con_contract_varrock_south_small_table_broken", "con_contract_varrock_south_small_table_editable", "con_contract_sidetable_empty", listOf("con_contract_varrock_south_small_table_regular", "con_contract_varrock_south_small_table_oak", "con_contract_varrock_south_small_table_teak", "con_contract_varrock_south_small_table_mahogany"), "small table", 2, false),
                    // 3236, 3382, 0
                    Spot(5, "con_contract_varrock_south_hotspot5", "con_contract_falador_south_range_broken", null, null, emptyList(), "range", 1, true),
                    // 3234, 3382, 0
                    Spot(6, "con_contract_varrock_south_hotspot6", "con_contract_varrock_mid_shelves_broken", "con_contract_varrock_mid_shelves_editable", "con_contract_shelves_empty", listOf("con_contract_varrock_mid_shelves_regular", "con_contract_varrock_mid_shelves_oak", "con_contract_varrock_mid_shelves_teak", "con_contract_varrock_mid_shelves_mahogany"), "shelves", 2, false),
                ),
                listOf(listOf(63, 47), listOf(63, 47), listOf(63, 47), listOf(63, 47)),
            ),
            House(
                "Larry",
                "con_contract_client_5",
                "Falador",
                listOf(
                    // 3039, 3367, 0
                    Spot(1, "con_contract_falador_north_hotspot1", "con_contract_falador_south_range_broken", null, null, emptyList(), "range", 1, true),
                    // 3041, 3365, 0
                    Spot(2, "con_contract_falador_north_hotspot2", "con_contract_falador_north_drawers_broken", "con_contract_falador_north_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_falador_north_drawers_regular", "con_contract_falador_north_drawers_oak", "con_contract_falador_north_drawers_teak", "con_contract_falador_north_drawers_mahogany"), "drawers", 2, false),
                    // 3041, 3364, 0
                    Spot(3, "con_contract_falador_north_hotspot3", "con_contract_falador_north_drawers_broken", "con_contract_falador_north_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_falador_north_drawers_regular", "con_contract_falador_north_drawers_oak", "con_contract_falador_north_drawers_teak", "con_contract_falador_north_drawers_mahogany"), "drawers", 2, false),
                    // 3038, 3364, 0
                    Spot(4, "con_contract_falador_north_hotspot4", "con_contract_falador_southeast_medium_table_broken", "con_contract_falador_southeast_medium_table_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table_regular", "con_contract_falador_southeast_medium_table_oak", "con_contract_falador_southeast_medium_table_teak", "con_contract_falador_southeast_medium_table_mahogany"), "table", 3, false),
                    // 3041, 3362, 0
                    Spot(5, "con_contract_falador_north_hotspot5", "con_contract_falador_southeast_hatstand_broken", null, null, emptyList(), "hat stand", 1, false),
                    // 3038, 3363, 1
                    Spot(6, "con_contract_falador_north_hotspot6", "con_contract_falador_southeast_medium_table_broken", "con_contract_falador_southeast_medium_table_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table_regular", "con_contract_falador_southeast_medium_table_oak", "con_contract_falador_southeast_medium_table_teak", "con_contract_falador_southeast_medium_table_mahogany"), "table", 3, false),
                    // 3034, 3364, 1
                    Spot(7, "con_contract_falador_north_hotspot7", "con_contract_falador_south_clock_broken", null, null, emptyList(), "grandfather clock", 1, false),
                ),
                listOf(listOf(62, 31, 126), listOf(126, 31, 62), listOf(62, 119, 127), listOf(126, 127, 127)),
            ),
            House(
                "Norman",
                "falador_man4",
                "Falador",
                listOf(
                    // 3039, 3346, 0
                    Spot(1, "con_contract_falador_south_hotspot1", "con_contract_falador_south_range_broken", null, null, emptyList(), "range", 1, true),
                    // 3035, 3346, 0
                    Spot(2, "con_contract_falador_south_hotspot2", "con_contract_falador_south_clock_broken", null, null, emptyList(), "grandfather clock", 1, false),
                    // 3036, 3342, 0
                    Spot(3, "con_contract_falador_south_hotspot3", "con_contract_falador_southeast_medium_table_broken", "con_contract_falador_southeast_medium_table_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table_regular", "con_contract_falador_southeast_medium_table_oak", "con_contract_falador_southeast_medium_table_teak", "con_contract_falador_southeast_medium_table_mahogany"), "table", 3, false),
                    // 3039, 3346, 1
                    Spot(4, "con_contract_falador_south_hotspot4", "con_contract_falador_south_large_bed_broken", "con_contract_falador_south_large_bed_editable", "con_contract_doublebed_empty", listOf("con_contract_falador_south_large_bed_regular", "con_contract_falador_south_large_bed_oak", "con_contract_falador_south_large_bed_teak", "con_contract_falador_south_large_bed_mahogany"), "double bed", 3, false),
                    // 3035, 3346, 1
                    Spot(5, "con_contract_falador_south_hotspot5", "con_contract_falador_south_bookcase_broken", "con_contract_falador_south_bookcase_editable", "con_contract_bookcase_empty", listOf("con_contract_falador_south_bookcase_regular", "con_contract_falador_south_bookcase_oak", "con_contract_falador_south_bookcase_teak", "con_contract_falador_south_bookcase_mahogany"), "bookshelf", 2, false),
                    // 3035, 3347, 1
                    Spot(6, "con_contract_falador_south_hotspot6", "con_contract_falador_south_drawers_broken", "con_contract_falador_south_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_falador_south_drawers_regular", "con_contract_falador_south_drawers_oak", "con_contract_falador_south_drawers_teak", "con_contract_falador_south_drawers_mahogany"), "drawers", 2, false),
                    // 3040, 3345, 1
                    Spot(7, "con_contract_falador_south_hotspot7", "con_contract_varrock_south_small_table_broken", "con_contract_varrock_south_small_table_editable", "con_contract_sidetable_empty", listOf("con_contract_varrock_south_small_table_regular", "con_contract_varrock_south_small_table_oak", "con_contract_varrock_south_small_table_teak", "con_contract_varrock_south_small_table_mahogany"), "small table", 2, false),
                ),
                listOf(listOf(62, 111), listOf(61, 111, 62), listOf(127, 110, 117), listOf(127, 125, 126)),
            ),
            House(
                "Tau",
                "con_contract_client_4",
                "Falador",
                listOf(
                    // 3051, 3347, 0
                    Spot(1, "con_contract_falador_southeast_hotspot1", "con_contract_falador_southeast_sink_broken", null, null, emptyList(), "sink", 1, true),
                    // 3048, 3344, 0
                    Spot(2, "con_contract_falador_southeast_hotspot2", "con_contract_falador_southeast_medium_table_broken", "con_contract_falador_southeast_medium_table_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table_regular", "con_contract_falador_southeast_medium_table_oak", "con_contract_falador_southeast_medium_table_teak", "con_contract_falador_southeast_medium_table_mahogany"), "table", 3, false),
                    // 3048, 3349, 0
                    Spot(3, "con_contract_falador_southeast_hotspot3", "con_contract_falador_southeast_medium_table2_broken", "con_contract_falador_southeast_medium_table2_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table2_regular", "con_contract_falador_southeast_medium_table2_oak", "con_contract_falador_southeast_medium_table2_teak", "con_contract_falador_southeast_medium_table2_mahogany"), "table", 3, false),
                    // 3044, 3345, 0
                    Spot(4, "con_contract_falador_southeast_hotspot4", "con_contract_falador_southeast_cupboard_broken", "con_contract_falador_southeast_cupboard_editable", "con_contract_cupboard_empty", listOf("con_contract_falador_southeast_cupboard_regular", "con_contract_falador_southeast_cupboard_oak", "con_contract_falador_southeast_cupboard_teak", "con_contract_falador_southeast_cupboard_mahogany"), "cupboard", 2, false),
                    // 3047, 3341, 0
                    Spot(5, "con_contract_falador_southeast_hotspot5", "con_contract_falador_southeast_shelves_broken", "con_contract_falador_southeast_shelves_editable", "con_contract_shelves_empty", listOf("con_contract_falador_southeast_shelves_regular", "con_contract_falador_southeast_shelves_oak", "con_contract_falador_southeast_shelves_teak", "con_contract_falador_southeast_shelves_mahogany"), "shelves", 2, false),
                    // 3048, 3341, 0
                    Spot(6, "con_contract_falador_southeast_hotspot6", "con_contract_falador_southeast_shelves_broken", "con_contract_falador_southeast_shelves_editable", "con_contract_shelves_empty", listOf("con_contract_falador_southeast_shelves_regular", "con_contract_falador_southeast_shelves_oak", "con_contract_falador_southeast_shelves_teak", "con_contract_falador_southeast_shelves_mahogany"), "shelves", 2, false),
                    // 3046, 3341, 0
                    Spot(7, "con_contract_falador_southeast_hotspot7", "con_contract_falador_southeast_hatstand_broken", null, null, emptyList(), "hat stand", 1, false),
                ),
                listOf(listOf(61, 123, 91, 62), listOf(62, 91, 123), listOf(62, 126, 59), listOf(63, 126, 127)),
            ),
            House(
                "Noella",
                "con_contract_client_6",
                "East Ardougne",
                listOf(
                    // 2661, 3323, 1
                    Spot(1, "con_contract_ardy_north_hotspot1", "con_contract_varrock_mid_dresser_broken", "con_contract_varrock_mid_dresser_editable", "con_contract_dresser_empty", listOf("con_contract_varrock_mid_dresser_regular", "con_contract_varrock_mid_dresser_oak", "con_contract_varrock_mid_dresser_teak", "con_contract_varrock_mid_dresser_mahogany"), "dresser", 2, false),
                    // 2661, 3321, 1
                    Spot(2, "con_contract_ardy_north_hotspot2", "con_contract_falador_southeast_cupboard_broken", "con_contract_falador_southeast_cupboard_editable", "con_contract_cupboard_empty", listOf("con_contract_falador_southeast_cupboard_regular", "con_contract_falador_southeast_cupboard_oak", "con_contract_falador_southeast_cupboard_teak", "con_contract_falador_southeast_cupboard_mahogany"), "cupboard", 2, false),
                    // 2665, 3318, 1
                    Spot(3, "con_contract_ardy_north_hotspot3", "con_contract_falador_southeast_hatstand_broken", null, null, emptyList(), "hat stand", 1, false),
                    // 2661, 3318, 1
                    Spot(4, "con_contract_ardy_north_hotspot4", "con_contract_ardy_north_mirror_broken", null, null, emptyList(), "mirror", 1, false),
                    // 2657, 3322, 1
                    Spot(5, "con_contract_ardy_north_hotspot5", "con_contract_ardy_north_drawers_broken", "con_contract_ardy_north_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_ardy_north_drawers_regular", "con_contract_ardy_north_drawers_oak", "con_contract_ardy_north_drawers_teak", "con_contract_ardy_north_drawers_mahogany"), "drawers", 2, false),
                    // 2654, 3319, 1
                    Spot(6, "con_contract_ardy_north_hotspot6", "con_contract_falador_southeast_medium_table2_broken", "con_contract_falador_southeast_medium_table2_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table2_regular", "con_contract_falador_southeast_medium_table2_oak", "con_contract_falador_southeast_medium_table2_teak", "con_contract_falador_southeast_medium_table2_mahogany"), "table", 3, false),
                    // 2656, 3318, 1
                    Spot(7, "con_contract_ardy_north_hotspot7", "con_contract_falador_southeast_medium_table2_broken", "con_contract_falador_southeast_medium_table2_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table2_regular", "con_contract_falador_southeast_medium_table2_oak", "con_contract_falador_southeast_medium_table2_teak", "con_contract_falador_southeast_medium_table2_mahogany"), "table", 3, false),
                    // 2653, 3322, 1
                    Spot(8, "con_contract_ardy_north_hotspot8", "con_contract_falador_south_clock_broken", null, null, emptyList(), "grandfather clock", 1, false),
                ),
                listOf(listOf(183, 235, 252), listOf(183, 252, 235), listOf(123, 255, 63), listOf(251, 255, 191)),
            ),
            House(
                "Ross",
                "con_contract_client_7",
                "East Ardougne",
                listOf(
                    // 2617, 3317, 0
                    Spot(1, "con_contract_ardy_west_hotspot1", "con_contract_falador_south_range_broken", null, null, emptyList(), "range", 1, true),
                    // 2611, 3318, 0
                    Spot(2, "con_contract_ardy_west_hotspot2", "con_contract_ardy_west_drawers_broken", "con_contract_ardy_west_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_ardy_west_drawers_regular", "con_contract_ardy_west_drawers_oak", "con_contract_ardy_west_drawers_teak", "con_contract_ardy_west_drawers_mahogany"), "drawers", 2, false),
                    // 2611, 3315, 0
                    Spot(3, "con_contract_ardy_west_hotspot3", "con_contract_ardy_west_drawers_broken", "con_contract_ardy_west_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_ardy_west_drawers_regular", "con_contract_ardy_west_drawers_oak", "con_contract_ardy_west_drawers_teak", "con_contract_ardy_west_drawers_mahogany"), "drawers", 2, false),
                    // 2613, 3317, 1
                    Spot(4, "con_contract_ardy_west_hotspot4", "con_contract_ardy_west_red_broken", "con_contract_ardy_west_red_editable", "con_contract_doublebed_empty", listOf("con_contract_ardy_west_red_regular", "con_contract_ardy_west_red_oak", "con_contract_ardy_west_red_teak", "con_contract_ardy_west_red_mahogany"), "double bed", 3, false),
                    // 2615, 3316, 1
                    Spot(5, "con_contract_ardy_west_hotspot5", "con_contract_falador_southeast_hatstand_broken", null, null, emptyList(), "hat stand", 1, false),
                    // 2617, 3319, 1
                    Spot(6, "con_contract_ardy_west_hotspot6", "con_contract_ardy_west_small_bed_broken", "con_contract_ardy_west_small_bed_editable", "con_contract_singlebed_empty", listOf("con_contract_ardy_west_small_bed_regular", "con_contract_ardy_west_small_bed_oak", "con_contract_ardy_west_small_bed_teak", "con_contract_ardy_west_small_bed_mahogany"), "bed", 2, false),
                    // 2618, 3317, 1
                    Spot(7, "con_contract_ardy_west_hotspot7", "con_contract_ardy_north_mirror_broken", null, null, emptyList(), "mirror", 1, false),
                ),
                listOf(listOf(31, 126, 118), listOf(126, 31, 118), listOf(119, 127, 31), listOf(126, 127, 63)),
            ),
            House(
                "Jess",
                "con_contract_client_8",
                "East Ardougne",
                listOf(
                    // 2612, 3296, 1
                    Spot(1, "con_contract_ardy_south_hotspot1", "con_contract_ardy_west_drawers_broken", "con_contract_ardy_west_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_ardy_west_drawers_regular", "con_contract_ardy_west_drawers_oak", "con_contract_ardy_west_drawers_teak", "con_contract_ardy_west_drawers_mahogany"), "drawers", 2, false),
                    // 2620, 3291, 1
                    Spot(2, "con_contract_ardy_south_hotspot2", "con_contract_ardy_west_drawers_broken", "con_contract_ardy_west_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_ardy_west_drawers_regular", "con_contract_ardy_west_drawers_oak", "con_contract_ardy_west_drawers_teak", "con_contract_ardy_west_drawers_mahogany"), "drawers", 2, false),
                    // 2621, 3295, 1
                    Spot(3, "con_contract_ardy_south_hotspot3", "con_contract_varrock_north_cabinet1_broken", "con_contract_varrock_north_cabinet1_editable", "con_contract_cabinet_empty", listOf("con_contract_varrock_north_cabinet1_regular", "con_contract_varrock_north_cabinet1_oak", "con_contract_varrock_north_cabinet1_teak", "con_contract_varrock_north_cabinet1_mahogany"), "cabinet", 2, false),
                    // 2622, 3295, 1
                    Spot(4, "con_contract_ardy_south_hotspot4", "con_contract_varrock_north_cabinet2_broken", "con_contract_varrock_north_cabinet2_editable", "con_contract_cabinet_empty", listOf("con_contract_varrock_north_cabinet2_regular", "con_contract_varrock_north_cabinet2_oak", "con_contract_varrock_north_cabinet2_teak", "con_contract_varrock_north_cabinet2_mahogany"), "cabinet", 2, false),
                    // 2614, 3295, 1
                    Spot(5, "con_contract_ardy_south_hotspot5", "con_contract_ardy_south_pinkbed_broken", "con_contract_ardy_south_pinkbed_editable", "con_contract_doublebed_empty", listOf("con_contract_ardy_south_pinkbed_regular", "con_contract_ardy_south_pinkbed_oak", "con_contract_ardy_south_pinkbed_teak", "con_contract_ardy_south_pinkbed_mahogany"), "bed", 3, false),
                    // 2612, 3293, 1
                    Spot(6, "con_contract_ardy_south_hotspot6", "con_contract_falador_southeast_medium_table2_broken", "con_contract_falador_southeast_medium_table2_editable", "con_contract_table_empty", listOf("con_contract_falador_southeast_medium_table2_regular", "con_contract_falador_southeast_medium_table2_oak", "con_contract_falador_southeast_medium_table2_teak", "con_contract_falador_southeast_medium_table2_mahogany"), "table", 3, false),
                    // 2612, 3295, 1
                    Spot(7, "con_contract_ardy_south_hotspot7", "con_contract_falador_south_clock_broken", null, null, emptyList(), "grandfather clock", 1, false),
                    // 2615, 3291, 1
                    Spot(8, "con_contract_ardy_south_hotspot8", "con_contract_ardy_south_bath_broken", null, null, emptyList(), "bathtub", 1, true),
                ),
                listOf(listOf(207, 124, 115), listOf(115, 124, 207), listOf(95, 255, 223), listOf(191, 255, 127)),
            ),
            House(
                "Mariah",
                "con_contract_client_9",
                "Hosidius",
                listOf(
                    // 1768, 3622, 0
                    Spot(1, "con_contract_hosidius_north_hotspot1", "con_contract_hosidius_north_large_table_broken", "con_contract_hosidius_north_large_table_editable", "con_contract_longtable_empty", listOf("con_contract_hosidius_north_large_table_regular", "con_contract_hosidius_north_large_table_oak", "con_contract_hosidius_north_large_table_teak", "con_contract_hosidius_north_large_table_mahogany"), "table", 3, false),
                    // 1763, 3621, 0
                    Spot(2, "con_contract_hosidius_north_hotspot2", "con_contract_hosidius_north_sink_broken", null, null, emptyList(), "sink", 1, true),
                    // 1766, 3619, 0
                    Spot(3, "con_contract_hosidius_north_hotspot3", "con_contract_hosidius_north_shelves_broken", "con_contract_hosidius_north_shelves_editable", "con_contract_shelves_empty", listOf("con_contract_hosidius_north_shelves_regular", "con_contract_hosidius_north_shelves_oak", "con_contract_hosidius_north_shelves_teak", "con_contract_hosidius_north_shelves_mahogany"), "cooking shelves", 2, false),
                    // 1763, 3620, 0
                    Spot(4, "con_contract_hosidius_north_hotspot4", "con_contract_hosidius_north_cupboard_broken", "con_contract_hosidius_north_cupboard_editable", "con_contract_cupboard_empty", listOf("con_contract_hosidius_north_cupboard_regular", "con_contract_hosidius_north_cupboard_oak", "con_contract_hosidius_north_cupboard_teak", "con_contract_hosidius_north_cupboard_mahogany"), "cupboard", 2, false),
                    // 1764, 3621, 1
                    Spot(5, "con_contract_hosidius_north_hotspot5", "con_contract_hosidius_north_medium_bed_broken", "con_contract_hosidius_north_medium_bed_editable", "con_contract_singlebed_empty", listOf("con_contract_hosidius_north_medium_bed_regular", "con_contract_hosidius_north_medium_bed_oak", "con_contract_hosidius_north_medium_bed_teak", "con_contract_hosidius_north_medium_bed_mahogany"), "bed", 2, false),
                    // 1764, 3620, 1
                    Spot(6, "con_contract_hosidius_north_hotspot6", "con_contract_hosidius_north_small_table_broken", "con_contract_hosidius_north_small_table_editable", "con_contract_sidetable_empty", listOf("con_contract_hosidius_north_small_table_regular", "con_contract_hosidius_north_small_table_oak", "con_contract_hosidius_north_small_table_teak", "con_contract_hosidius_north_small_table_mahogany"), "small table", 2, false),
                    // 1770, 3623, 1
                    Spot(7, "con_contract_hosidius_north_hotspot7", "con_contract_hosidius_north_small_table_broken", "con_contract_hosidius_north_small_table_editable", "con_contract_sidetable_empty", listOf("con_contract_hosidius_north_small_table_regular", "con_contract_hosidius_north_small_table_oak", "con_contract_hosidius_north_small_table_teak", "con_contract_hosidius_north_small_table_mahogany"), "small table", 2, false),
                    // 1764, 3623, 0
                    Spot(8, "con_contract_hosidius_north_hotspot8", "con_contract_falador_southeast_hatstand_broken", null, null, emptyList(), "hat stand", 1, false),
                ),
                listOf(listOf(63, 248, 158), listOf(63, 248, 158), listOf(237, 255, 63), listOf(255, 253, 127)),
            ),
            House(
                "Leela",
                "con_contract_client_10",
                "Hosidius",
                listOf(
                    // 1784, 3594, 0
                    Spot(1, "con_contract_hosidius_east_hotspot1", "con_contract_hosidius_north_small_table_broken", "con_contract_hosidius_north_small_table_editable", "con_contract_sidetable_empty", listOf("con_contract_hosidius_north_small_table_regular", "con_contract_hosidius_north_small_table_oak", "con_contract_hosidius_north_small_table_teak", "con_contract_hosidius_north_small_table_mahogany"), "small table", 2, false),
                    // 1782, 3592, 0
                    Spot(2, "con_contract_hosidius_east_hotspot2", "con_contract_hosidius_north_small_table_broken", "con_contract_hosidius_north_small_table_editable", "con_contract_sidetable_empty", listOf("con_contract_hosidius_north_small_table_regular", "con_contract_hosidius_north_small_table_oak", "con_contract_hosidius_north_small_table_teak", "con_contract_hosidius_north_small_table_mahogany"), "small table", 2, false),
                    // 1786, 3594, 0
                    Spot(3, "con_contract_hosidius_east_hotspot3", "con_contract_hosidius_north_sink_broken", null, null, emptyList(), "sink", 1, true),
                    // 1782, 3590, 0
                    Spot(4, "con_contract_hosidius_east_hotspot4", "con_contract_hosidius_east_medium_bed_broken", "con_contract_hosidius_east_medium_bed_editable", "con_contract_doublebed_empty", listOf("con_contract_hosidius_east_medium_bed_regular", "con_contract_hosidius_east_medium_bed_oak", "con_contract_hosidius_east_medium_bed_teak", "con_contract_hosidius_east_medium_bed_mahogany"), "double bed", 3, false),
                    // 1782, 3590, 1
                    Spot(5, "con_contract_hosidius_east_hotspot5", "con_contract_hosidius_north_large_table_broken", "con_contract_hosidius_north_large_table_editable", "con_contract_longtable_empty", listOf("con_contract_hosidius_north_large_table_regular", "con_contract_hosidius_north_large_table_oak", "con_contract_hosidius_north_large_table_teak", "con_contract_hosidius_north_large_table_mahogany"), "table", 3, false),
                    // 1786, 3590, 1
                    Spot(6, "con_contract_hosidius_east_hotspot6", "con_contract_ardy_north_mirror_broken", null, null, emptyList(), "mirror", 1, false),
                    // 1788, 3590, 1
                    Spot(7, "con_contract_hosidius_east_hotspot7", "con_contract_falador_southeast_cupboard_broken", "con_contract_falador_southeast_cupboard_editable", "con_contract_cupboard_empty", listOf("con_contract_hosidius_north_cupboard_regular", "con_contract_hosidius_north_cupboard_oak", "con_contract_hosidius_north_cupboard_teak", "con_contract_hosidius_north_cupboard_mahogany"), "cupboard", 2, false),
                ),
                listOf(listOf(120, 47, 124), listOf(124, 120, 47), listOf(107, 111, 124), listOf(123, 127, 95)),
            ),
            House(
                "Barbara",
                "con_contract_client_11",
                "Hosidius",
                listOf(
                    // 1749, 3536, 0
                    Spot(1, "con_contract_hosidius_south_hotspot1", "con_contract_varrock_north_clock_broken", null, null, emptyList(), "grandfather clock", 1, false),
                    // 1747, 3532, 0
                    Spot(2, "con_contract_hosidius_south_hotspot2", "con_contract_hosidius_south_range_broken", null, null, emptyList(), "range", 1, true),
                    // 1750, 3535, 0
                    Spot(3, "con_contract_hosidius_south_hotspot3", "con_contract_hosidius_north_large_table_broken", "con_contract_hosidius_north_large_table_editable", "con_contract_longtable_empty", listOf("con_contract_hosidius_north_large_table_regular", "con_contract_hosidius_north_large_table_oak", "con_contract_hosidius_north_large_table_teak", "con_contract_hosidius_north_large_table_mahogany"), "table", 3, false),
                    // 1754, 3535, 0
                    Spot(4, "con_contract_hosidius_south_hotspot4", "con_contract_hosidius_south_drawers_broken", "con_contract_hosidius_south_drawers_editable", "con_contract_drawers_empty", listOf("con_contract_hosidius_south_drawers_regular", "con_contract_hosidius_south_drawers_oak", "con_contract_hosidius_south_drawers_teak", "con_contract_hosidius_south_drawers_mahogany"), "drawers", 2, false),
                    // 1748, 3538, 0
                    Spot(5, "con_contract_hosidius_south_hotspot5", "con_contract_hosidius_north_medium_bed_broken", "con_contract_hosidius_north_medium_bed_editable", "con_contract_singlebed_empty", listOf("con_contract_hosidius_north_medium_bed_regular", "con_contract_hosidius_north_medium_bed_oak", "con_contract_hosidius_north_medium_bed_teak", "con_contract_hosidius_north_medium_bed_mahogany"), "bed", 2, false),
                    // 1751, 3534, 0
                    Spot(6, "con_contract_hosidius_south_hotspot6", "con_contract_hosidius_south_chair_broken", "con_contract_hosidius_south_chair_editable", "con_contract_chair_empty", listOf("con_contract_hosidius_south_chair_regular", "con_contract_hosidius_south_chair_oak", "con_contract_hosidius_south_chair_teak", "con_contract_hosidius_south_chair_mahogany"), "chair", 1, false),
                    // 1750, 3532, 0
                    Spot(7, "con_contract_hosidius_south_hotspot7", "con_contract_hosidius_south_chair_broken", "con_contract_hosidius_south_chair_editable", "con_contract_chair_empty", listOf("con_contract_hosidius_south_chair_regular", "con_contract_hosidius_south_chair_oak", "con_contract_hosidius_south_chair_teak", "con_contract_hosidius_south_chair_mahogany"), "chair", 1, false),
                ),
                listOf(listOf(29, 99, 30), listOf(30, 99, 29), listOf(126, 125, 127), listOf(127, 125, 126)),
            ),
        )
}
