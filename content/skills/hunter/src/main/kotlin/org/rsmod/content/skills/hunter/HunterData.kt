package org.rsmod.content.skills.hunter

/**
 * Bird snare, box trap, butterfly and wasp swarm data from `hunter.tsv`
 * (tools/skills/hunter_to_tsv.py).
 */
internal object HunterData {
    class Loot(val obj: String, val min: Int, val max: Int)

    enum class Kind {
        /** Bird snare. */
        Snare,
        /** Box trap (ferrets, chinchompas). */
        Box,
        /** Butterfly net (caught directly from the npc). */
        Net,
        /**
         * Wasp swarm (The Birds and the Wasps): netted every tick with an insect net and a bee hat;
         * npcs are the calm swarm then its furious variant. See `Wasps.kt`.
         */
        Swarm,
    }

    class Creature(
        val kind: Kind,
        val title: String,
        val npcs: List<String>,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        /** Traps only: trap locs while catching (snares) / once caught. */
        val trappingLoc: String?,
        val fullLoc: String?,
        val loot: List<Loot>,
    ) {
        val snare: Boolean
            get() = kind == Kind.Snare

        /** Caught in a laid trap (snare or box) rather than directly. */
        val trapped: Boolean
            get() = kind == Kind.Snare || kind == Kind.Box
    }

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
            val kind =
                when (h[0]) {
                    "snare" -> Kind.Snare
                    "box" -> Kind.Box
                    "swarm" -> Kind.Swarm
                    else -> Kind.Net
                }
            val trap = kind == Kind.Snare || kind == Kind.Box
            creatures.add(
                Creature(
                    kind = kind,
                    title = h[1],
                    npcs = h[2].split(','),
                    level = h[3].toInt(),
                    xp = h[4].toDouble(),
                    low = h[5].toInt(),
                    high = h[6].toInt(),
                    trappingLoc = if (trap) h[7].takeIf { it != "-" } else null,
                    fullLoc = if (trap) h[8] else null,
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
                "box",
                "net",
                "swarm" -> {
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
