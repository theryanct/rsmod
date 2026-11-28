package org.rsmod.content.generic.food

/** Rows of `food.tsv` (generated from the OSRS Wiki by tools/skills/food_to_tsv.py). */
internal object FoodData {
    class Food(
        val obj: String,
        val heal: Int,
        val drink: Boolean,
        /** The obj left in the slot after this bite (next bite or empty dish), if any. */
        val next: String?,
        /** Combo food (karambwan): its own eat delay, so it can follow normal food in a tick. */
        val combo: Boolean,
    )

    val foods: List<Food> by lazy {
        val input =
            checkNotNull(FoodData::class.java.getResourceAsStream("food.tsv")) {
                "food.tsv not found"
            }
        input.bufferedReader().use { it.readLines() }
            .filter { it.startsWith("food\t") }
            .map { line ->
                val p = line.split('\t')
                Food(
                    obj = p[1],
                    heal = p[2].toInt(),
                    drink = p[3] == "drink",
                    next = p[4].takeIf { it != "-" },
                    combo = p[5] == "1",
                )
            }
    }
}
