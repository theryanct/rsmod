package org.rsmod.content.skills.hunter

/** Bird snare and butterfly data from `hunter.tsv` (tools/skills/hunter_to_tsv.py). */
internal object HunterData {
    class Loot(val obj: String, val min: Int, val max: Int)

    class Creature(
        val snare: Boolean,
        val title: String,
        val npcs: List<String>,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        /** Snares only: trap locs while catching / once caught. */
        val trappingLoc: String?,
        val fullLoc: String?,
        val loot: List<Loot>,
    )

    val creatures: List<Creature> by lazy { load() }

    private fun load(): List<Creature> {
        val input =
            HunterData::class.java.getResourceAsStream("hunter.tsv")
                ?: error("Hunter resource not found: hunter.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val creatures = ArrayList<Creature>()
        var header: List<String>? = null
        var loot = ArrayList<Loot>()

        fun flush() {
            val h = header ?: return
            val snare = h[0] == "snare"
            creatures.add(
                Creature(
                    snare = snare,
                    title = h[1],
                    npcs = h[2].split(','),
                    level = h[3].toInt(),
                    xp = h[4].toDouble(),
                    low = h[5].toInt(),
                    high = h[6].toInt(),
                    trappingLoc = if (snare) h[7] else null,
                    fullLoc = if (snare) h[8] else null,
                    loot = loot,
                )
            )
            header = null
            loot = ArrayList()
        }

        for (raw in lines) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) {
                continue
            }
            val p = line.split('\t')
            when (p[0]) {
                "snare",
                "net" -> {
                    flush()
                    header = p
                }
                "loot" -> loot.add(Loot(p[1], p[2].toInt(), p[3].toInt()))
                else -> error("Unknown hunter line: $raw")
            }
        }
        flush()
        return creatures
    }
}
