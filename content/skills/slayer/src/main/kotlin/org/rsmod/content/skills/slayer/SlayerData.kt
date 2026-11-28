package org.rsmod.content.skills.slayer

/** Slayer masters, tasks, categories and slayer xp from `slayer.tsv` (tools/slayer). */
internal object SlayerData {
    class Master(
        val key: String,
        val npc: String,
        val combatReq: Int,
        val points: Int,
        /** Every Nth completed task -> bonus points (instead of [points]). */
        val milestones: List<Pair<Int, Int>>,
        /** Slayer level needed for this master's tasks (Duradel: 50). */
        val slayerReq: Int = 1,
    ) {
        val id: Int = stableId(key)
    }

    class Task(
        val master: String,
        val category: String,
        val min: Int,
        val max: Int,
        val weight: Double,
        val slayerReq: Int,
        val combatReq: Int,
    )

    class Category(val key: String, val display: String, val npcs: List<String>) {
        val id: Int = stableId(key)
    }

    private class Parsed(
        val masters: List<Master>,
        val tasks: List<Task>,
        val categories: List<Category>,
        val xp: Map<String, Double>,
    )

    private val parsed: Parsed by lazy { load() }

    val masters: List<Master>
        get() = parsed.masters

    val tasks: List<Task>
        get() = parsed.tasks

    val categories: List<Category>
        get() = parsed.categories

    /** Slayer xp per kill by npc internal name. */
    val xp: Map<String, Double>
        get() = parsed.xp

    /** Saved in varps, so ids must not depend on list order: a positive hash of the key. */
    fun stableId(key: String): Int = (key.hashCode() and 0x7FFFFFFF).let { if (it == 0) 1 else it }

    private fun load(): Parsed {
        val input =
            SlayerData::class.java.getResourceAsStream("slayer.tsv")
                ?: error("Slayer resource not found: slayer.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val masters = ArrayList<Master>()
        val tasks = ArrayList<Task>()
        val categories = ArrayList<Category>()
        val xp = HashMap<String, Double>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            when (p[0]) {
                "master" -> {
                    val milestones =
                        if (p[6] == "-") {
                            emptyList()
                        } else {
                            p[6].split(';').map { it.substringBefore(':').toInt() to it.substringAfter(':').toInt() }
                        }
                    val slayerReq = p.getOrNull(7)?.toIntOrNull() ?: 1
                    masters.add(Master(p[1], p[2], p[4].toInt(), p[5].toInt(), milestones, slayerReq))
                }
                "task" ->
                    tasks.add(
                        Task(
                            master = p[1],
                            category = p[2],
                            min = p[3].toInt(),
                            max = p[4].toInt(),
                            weight = p[5].toDouble(),
                            slayerReq = p[6].toInt(),
                            combatReq = p[7].toInt(),
                        )
                    )
                "category" -> categories.add(Category(p[1], p[2], p[3].split(',')))
                "xp" -> xp[p[1]] = p[2].toDouble()
                else -> error("Unknown slayer line: $raw")
            }
        }
        return Parsed(masters, tasks, categories, xp)
    }
}
