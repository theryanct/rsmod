package org.rsmod.content.skills.fletching

/**
 * Fletching recipes generated from the OSRS Wiki recipe bucket by
 * `tools/skills/fletching_to_tsv.py` into the `fletching.tsv` resource.
 */
internal object FletchingData {
    enum class Kind {
        /** Knife on logs: arrow shafts, unstrung bows, stocks, shields. */
        Knife,
        /** Bow string on an unstrung bow. */
        String,
        /** Two stackables combined in sets (arrows, headless arrows, darts, bolts). */
        Combine,
        /** Two items joined with a hammer in the inventory (crossbow limbs on a stock). */
        Hammer,
        /** Chisel on a cut gem: bolt tips. */
        Chisel,
    }

    class Material(val obj: String, val count: Int)

    class Recipe(
        val kind: Kind,
        val product: String,
        val productCount: Int,
        val level: Int,
        val xp: Double,
        val ticks: Int,
        val anim: String?,
        val materials: List<Material>,
    )

    val recipes: List<Recipe> by lazy { load() }

    private fun load(): List<Recipe> {
        val input =
            FletchingData::class.java.getResourceAsStream("fletching.tsv")
                ?: error("Fletching resource not found: fletching.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val recipes = ArrayList<Recipe>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            check(p[0] == "recipe") { "Unknown fletching line: $raw" }
            val kind =
                when (p[1]) {
                    "knife" -> Kind.Knife
                    "string" -> Kind.String
                    "combine" -> Kind.Combine
                    "hammer" -> Kind.Hammer
                    "chisel" -> Kind.Chisel
                    else -> error("Unknown fletching recipe kind: $raw")
                }
            val materials = ArrayList<Material>()
            var i = 8
            while (i + 1 < p.size && p[i].isNotEmpty()) {
                materials.add(Material(p[i], p[i + 1].toInt()))
                i += 2
            }
            recipes.add(
                Recipe(
                    kind = kind,
                    product = p[2],
                    productCount = p[3].toInt(),
                    level = p[4].toInt(),
                    xp = p[5].toDouble(),
                    ticks = p[6].toInt(),
                    anim = p[7].takeIf { it != "-" },
                    materials = materials,
                )
            )
        }
        return recipes
    }
}
