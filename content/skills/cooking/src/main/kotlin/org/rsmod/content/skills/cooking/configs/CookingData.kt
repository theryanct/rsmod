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

    val heat: List<Heat> by lazy { rows("heat").map(::heat) }
    val combine: List<Combine> by lazy { rows("combine").map(::combine) }

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
