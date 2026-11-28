package org.rsmod.content.skills.herblore

/**
 * Herblore PR1 data. Levels and xp: OSRS Wiki recipe bucket (uses_skill = Herblore); potion
 * effects: the Wiki potion pages. Obj names are the cache symbols.
 */
internal object HerbloreData {
    class Herb(val grimy: String, val clean: String, val level: Int, val xp: Double)

    class Unfinished(val herb: String, val unf: String, val level: Int)

    class Potion(
        val name: String,
        val unf: String,
        val secondary: String,
        /** The 3-dose potion made. */
        val product: String,
        val level: Int,
        val xp: Double,
    )

    val herbs: List<Herb> =
        listOf(
            Herb("unidentified_guam", "guam_leaf", 3, 2.5),
            Herb("unidentified_marentill", "marentill", 5, 3.8),
            Herb("unidentified_tarromin", "tarromin", 11, 5.0),
            Herb("unidentified_harralander", "harralander", 20, 6.3),
            Herb("unidentified_ranarr", "ranarr_weed", 25, 7.5),
            Herb("unidentified_toadflax", "toadflax", 30, 8.0),
            Herb("unidentified_irit", "irit_leaf", 40, 8.8),
            Herb("unidentified_avantoe", "avantoe", 48, 10.0),
            Herb("unidentified_kwuarm", "kwuarm", 54, 11.3),
            Herb("unidentified_snapdragon", "snapdragon", 59, 11.8),
            Herb("unidentified_cadantine", "cadantine", 65, 12.5),
            Herb("unidentified_lantadyme", "lantadyme", 67, 13.1),
            Herb("unidentified_dwarf_weed", "dwarf_weed", 70, 13.8),
            Herb("unidentified_torstol", "torstol", 75, 15.0),
        )

    val unfinished: List<Unfinished> =
        listOf(
            Unfinished("guam_leaf", "guamvial", 3),
            Unfinished("marentill", "marrentillvial", 5),
            Unfinished("tarromin", "tarrominvial", 12),
            Unfinished("harralander", "harralandervial", 22),
            Unfinished("ranarr_weed", "ranarrvial", 30),
            Unfinished("toadflax", "toadflaxvial", 34),
            Unfinished("irit_leaf", "iritvial", 45),
            Unfinished("avantoe", "avantoevial", 50),
        )

    val potions: List<Potion> =
        listOf(
            Potion("Attack potion", "guamvial", "eye_of_newt", "3dose1attack", 3, 25.0),
            Potion(
                "Antipoison",
                "marrentillvial",
                "unicorn_horn_dust",
                "3doseantipoison",
                5,
                37.5,
            ),
            Potion("Strength potion", "tarrominvial", "limpwurt_root", "3dose1strength", 12, 50.0),
            Potion(
                "Restore potion",
                "harralandervial",
                "red_spiders_eggs",
                "3dosestatrestore",
                22,
                62.5,
            ),
            Potion("Energy potion", "harralandervial", "chocolate_dust", "3dose1energy", 26, 67.5),
            Potion("Defence potion", "ranarrvial", "white_berries", "3dose1defense", 30, 75.0),
            Potion("Agility potion", "toadflaxvial", "toads_legs", "3dose1agility", 34, 80.0),
            Potion(
                "Combat potion",
                "harralandervial",
                "ground_desert_goat_horn",
                "3dosecombat",
                36,
                84.0,
            ),
            Potion("Prayer potion", "ranarrvial", "snape_grass", "3doseprayerrestore", 38, 87.5),
            Potion("Super attack", "iritvial", "eye_of_newt", "3dose2attack", 45, 100.0),
            Potion(
                "Superantipoison",
                "iritvial",
                "unicorn_horn_dust",
                "3dose2antipoison",
                48,
                106.3,
            ),
            Potion("Fishing potion", "avantoevial", "snape_grass", "3dosefisherspotion", 50, 112.5),
            Potion("Super energy", "avantoevial", "mortmyremushroom", "3dose2energy", 52, 117.5),
        )

    /** What a dose does when drunk (Wiki values). */
    sealed class Effect {
        /** Boost each stat by `constant + percent%` of its base level. */
        data class Boost(val stats: List<String>, val constant: Int, val percent: Int) : Effect()

        /** Restore each stat by `constant + percent%` of its base, up to the base level. */
        data class Restore(val stats: List<String>, val constant: Int, val percent: Int) :
            Effect()

        /** Restore run energy by [percent]% of the maximum. */
        data class Energy(val percent: Int) : Effect()

        /** Antipoisons: there is no poison system yet, so only the message. */
        data object Cure : Effect()
    }

    class Drink(val name: String, val doses: List<String>, val effect: Effect)

    private fun doses(suffix: String) = (4 downTo 1).map { "${it}dose$suffix" }

    private val restoreStats = listOf("attack", "strength", "defence", "ranged", "magic")

    val drinks: List<Drink> =
        listOf(
            Drink("attack potion", doses("1attack"), Effect.Boost(listOf("attack"), 3, 10)),
            Drink(
                "strength potion",
                listOf("strength4") + (3 downTo 1).map { "${it}dose1strength" },
                Effect.Boost(listOf("strength"), 3, 10),
            ),
            Drink("defence potion", doses("1defense"), Effect.Boost(listOf("defence"), 3, 10)),
            Drink("super attack", doses("2attack"), Effect.Boost(listOf("attack"), 5, 15)),
            Drink("super strength", doses("2strength"), Effect.Boost(listOf("strength"), 5, 15)),
            Drink("super defence", doses("2defense"), Effect.Boost(listOf("defence"), 5, 15)),
            Drink(
                "combat potion",
                doses("combat"),
                Effect.Boost(listOf("attack", "strength"), 3, 10),
            ),
            Drink("agility potion", doses("1agility"), Effect.Boost(listOf("agility"), 3, 0)),
            Drink("fishing potion", doses("fisherspotion"), Effect.Boost(listOf("fishing"), 3, 0)),
            Drink("restore potion", doses("statrestore"), Effect.Restore(restoreStats, 10, 30)),
            Drink(
                "super restore",
                doses("2restore"),
                Effect.Restore(restoreStats + "prayer", 8, 25),
            ),
            Drink(
                "prayer potion",
                doses("prayerrestore"),
                Effect.Restore(listOf("prayer"), 7, 25),
            ),
            Drink("energy potion", doses("1energy"), Effect.Energy(10)),
            Drink("super energy", doses("2energy"), Effect.Energy(20)),
            Drink("antipoison", doses("antipoison"), Effect.Cure),
            Drink("superantipoison", doses("2antipoison"), Effect.Cure),
        )

    /** Water sources (by loc name) that fill vials. */
    val waterSources: Set<String> = setOf("sink", "fountain", "well", "water pump", "waterpump")

    const val VIAL = "vial_empty"
    const val VIAL_WATER = "vial_water"
}
