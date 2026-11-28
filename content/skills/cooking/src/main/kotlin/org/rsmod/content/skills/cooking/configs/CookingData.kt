package org.rsmod.content.skills.cooking.configs

/**
 * Cooking recipes generated from the OSRS Wiki recipe bucket by `tools/skills/cooking_to_tsv.py`
 * into the `cooking.tsv` resource.
 */
internal object CookingData {
    class Curve(val low: Int, val high: Int)

    class Heat(
        val raw: String,
        val cooked: String,
        val burnt: String?,
        val level: Int,
        val xp: Double,
        val fire: Boolean,
        val curve: Curve,
        val lumbridge: Curve?,
        val gauntlets: Curve?,
        val hosidius: Curve?,
        /** Container handed back whether cooked or burnt (cake tin). */
        val returns: String?,
    )

    class Combine(
        val first: String,
        val second: String,
        val product: String,
        val level: Int,
        val xp: Double,
        val firstLeftover: String?,
        val secondLeftover: String?,
    )

    /** 3+ ingredients used on [base] (cake tin + egg, flour, milk). */
    class Multi(
        val product: String,
        val level: Int,
        val xp: Double,
        val base: String,
        val ingredients: List<String>,
        val leftovers: List<String>,
    )

    /** Unfermented wine that turns into [fermented] (or [failed]) a while after it is made. */
    class Ferment(
        val unfermented: String,
        val fermented: String,
        val failed: String,
        val level: Int,
        val xp: Double,
        val curve: Curve,
    )

    val heat: List<Heat> by lazy { rows("heat").map(::heat) }
    val combine: List<Combine> by lazy { rows("combine").map(::combine) }
    val multi: List<Multi> by lazy { rows("multi").map(::multi) }
    val ferment: List<Ferment> by lazy { rows("ferment").map(::ferment) }

    private fun multi(p: List<String>): Multi {
        val pairs = p.drop(5).chunked(2).filter { it.size == 2 }
        return Multi(
            product = p[1],
            level = p[2].toInt(),
            xp = p[3].toDouble(),
            base = p[4],
            ingredients = pairs.map { it[0] },
            leftovers = pairs.mapNotNull { it[1].orNull() },
        )
    }

    private fun ferment(p: List<String>): Ferment =
        Ferment(
            unfermented = p[1],
            fermented = p[2],
            failed = p[3],
            level = p[4].toInt(),
            xp = p[5].toDouble(),
            curve = Curve(p[6].toInt(), p[7].toInt()),
        )

    private fun heat(p: List<String>): Heat =
        Heat(
            raw = p[1],
            cooked = p[2],
            burnt = p[3].orNull(),
            level = p[4].toInt(),
            xp = p[5].toDouble(),
            fire = p[6] == "1",
            curve = Curve(p[7].toInt(), p[8].toInt()),
            lumbridge = curve(p[9], p[10]),
            gauntlets = curve(p[11], p[12]),
            hosidius = if (p.size > 14) curve(p[13], p[14]) else null,
            returns = p.getOrNull(15)?.orNull(),
        )

    private fun combine(p: List<String>): Combine =
        Combine(
            first = p[1],
            second = p[2],
            product = p[3],
            level = p[4].toInt(),
            xp = p[5].toDouble(),
            firstLeftover = p[6].orNull(),
            secondLeftover = p[7].orNull(),
        )

    private fun curve(low: String, high: String): Curve? =
        if (low == "-" || high == "-") null else Curve(low.toInt(), high.toInt())

    private fun String.orNull(): String? = takeIf { it != "-" }

    private val lines: List<List<String>> by lazy {
        val input =
            CookingData::class.java.getResourceAsStream("/org/rsmod/content/skills/cooking/scripts/cooking.tsv")
                ?: error("Cooking resource not found: cooking.tsv")
        input.bufferedReader().use { it.readLines() }
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
    }

    private fun rows(kind: String): List<List<String>> = lines.filter { it[0] == kind }

    /** Every obj symbol name referenced by the table (for [CookingObjs.byName]). */
    val objNames: Set<String> by lazy {
        buildSet {
            for (h in heat) {
                add(h.raw)
                add(h.cooked)
                h.burnt?.let(::add)
                h.returns?.let(::add)
            }
            for (m in multi) {
                add(m.product)
                add(m.base)
                addAll(m.ingredients)
                addAll(m.leftovers)
            }
            for (f in ferment) {
                add(f.unfermented)
                add(f.fermented)
                add(f.failed)
            }
            for (c in combine) {
                add(c.first)
                add(c.second)
                add(c.product)
                c.firstLeftover?.let(::add)
                c.secondLeftover?.let(::add)
            }
        }
    }
}
