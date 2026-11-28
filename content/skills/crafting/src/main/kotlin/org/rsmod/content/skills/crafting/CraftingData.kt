package org.rsmod.content.skills.crafting

/**
 * Crafting recipes generated from the OSRS Wiki recipe bucket by `tools/skills/crafting_to_tsv.py`
 * into the `crafting.tsv` resource.
 */
internal object CraftingData {
    enum class Station(val key: String) {
        /** Spinning wheel: wool, flax, sinew/roots, hair, hemp, cotton. */
        Spin("spin"),
        /** Loom: cloth, linen, sacks, drift net, basket, canvas. */
        Loom("loom"),
        /** Potter's wheel: soft clay -> unfired pottery. */
        Wheel("wheel"),
        /** Pottery oven: unfired -> fired pottery. */
        Oven("oven"),
        /** Furnace: gold/silver jewellery with a mould ([Recipe.tool]), molten glass. */
        Furnace("furnace"),
        /** Needle on leather (thread used up once every five items). */
        Needle("needle"),
        /** Chisel on an uncut gem. */
        Chisel("chisel"),
        /** Glassblowing pipe on molten glass. */
        Pipe("pipe"),
        /** Ball of wool on an unstrung amulet or symbol. */
        Stringing("string"),
        /** Water container on clay -> soft clay (container returned). */
        Combine("combine"),
    }

    class Material(val obj: String, val count: Int)

    class Recipe(
        val station: Station,
        /** Held tool (needle, chisel, mould...) or the water container for [Station.Combine]. */
        val tool: String?,
        val product: String,
        val productCount: Int,
        val level: Int,
        val xp: Double,
        val ticks: Int,
        val anim: String?,
        val leftover: String?,
        val materials: List<Material>,
    )

    val recipes: List<Recipe> by lazy { load() }

    val objNames: Set<String> by lazy {
        buildSet {
            for (recipe in recipes) {
                add(recipe.product)
                recipe.tool?.let(::add)
                recipe.leftover?.let(::add)
                recipe.materials.forEach { add(it.obj) }
            }
        }
    }

    private fun load(): List<Recipe> {
        val input =
            CraftingData::class.java.getResourceAsStream("crafting.tsv")
                ?: error("Crafting resource not found: crafting.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val stations = Station.entries.associateBy { it.key }
        val recipes = ArrayList<Recipe>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            check(p[0] == "recipe") { "Unknown crafting line: $raw" }
            val station = stations[p[1]] ?: error("Unknown crafting station: $raw")
            val materials = ArrayList<Material>()
            var i = 10
            while (i + 1 < p.size && p[i].isNotEmpty()) {
                materials.add(Material(p[i], p[i + 1].toInt()))
                i += 2
            }
            recipes.add(
                Recipe(
                    station = station,
                    tool = p[2].takeIf { it != "-" },
                    product = p[3],
                    productCount = p[4].toInt(),
                    level = p[5].toInt(),
                    xp = p[6].toDouble(),
                    ticks = p[7].toInt(),
                    anim = p[8].takeIf { it != "-" },
                    leftover = p[9].takeIf { it != "-" },
                    materials = materials,
                )
            )
        }
        return recipes
    }
}
