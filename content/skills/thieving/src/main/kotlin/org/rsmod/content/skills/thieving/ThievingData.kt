package org.rsmod.content.skills.thieving

/**
 * Pickpocket and stall data generated from OSRS Wiki `{{Thieving info}}` pages by
 * `tools/skills/thieving_to_tsv.py` into the `thieving.tsv` resource.
 */
internal object ThievingData {
    class Loot(
        val obj: String,
        val min: Int,
        val max: Int,
        val numerator: Int,
        val denominator: Int,
        val always: Boolean,
    )

    class Pickpocket(
        val title: String,
        val npcs: List<String>,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        val stunTicks: Int,
        val damage: Int,
        val loot: List<Loot>,
    )

    class Stall(
        val title: String,
        val locs: List<String>,
        val level: Int,
        val xp: Double,
        val respawnTicks: Int,
        /** Empty-stall loc per entry of [locs], or `null` when the stall only goes on cooldown. */
        val empties: List<String?>,
        val loot: List<Loot>,
    )

    private class Parsed(val pickpockets: List<Pickpocket>, val stalls: List<Stall>)

    private val parsed: Parsed by lazy { load() }

    val pickpockets: List<Pickpocket>
        get() = parsed.pickpockets

    val stalls: List<Stall>
        get() = parsed.stalls

    private fun load(): Parsed {
        // thieving.tsv is generated; thieving_extra.tsv holds hand-built targets the generator skips.
        val lines =
            listOf("thieving.tsv", "thieving_extra.tsv").flatMap { name ->
                val input =
                    ThievingData::class.java.getResourceAsStream(name)
                        ?: error("Thieving resource not found: $name")
                input.bufferedReader().use { it.readLines() } + ""
            }
        val pickpockets = ArrayList<Pickpocket>()
        val stalls = ArrayList<Stall>()
        var header: List<String>? = null
        var loot = ArrayList<Loot>()

        fun flush() {
            val h = header ?: return
            when (h[0]) {
                "pickpocket" ->
                    pickpockets.add(
                        Pickpocket(
                            title = h[1],
                            npcs = h[2].split(','),
                            level = h[3].toInt(),
                            xp = h[4].toDouble(),
                            low = h[5].toInt(),
                            high = h[6].toInt(),
                            stunTicks = h[7].toInt(),
                            damage = h[8].toInt(),
                            loot = loot,
                        )
                    )
                "stall" ->
                    stalls.add(
                        Stall(
                            title = h[1],
                            locs = h[2].split(','),
                            level = h[3].toInt(),
                            xp = h[4].toDouble(),
                            respawnTicks = h[5].toInt(),
                            empties = h[6].split(',').map { it.takeIf { e -> e != "-" } },
                            loot = loot,
                        )
                    )
            }
            header = null
            loot = ArrayList()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            when (p[0]) {
                "pickpocket",
                "stall" -> {
                    flush()
                    header = p
                }
                "loot" ->
                    loot.add(
                        Loot(
                            obj = p[1],
                            min = p[2].toInt(),
                            max = p[3].toInt(),
                            numerator = p[4].toInt(),
                            denominator = p[5].toInt(),
                            always = p[6] == "1",
                        )
                    )
                else -> error("Unknown thieving line: $raw")
            }
        }
        flush()
        return Parsed(pickpockets, stalls)
    }
}
