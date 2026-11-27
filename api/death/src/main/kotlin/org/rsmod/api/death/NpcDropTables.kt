package org.rsmod.api.death

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.random.GameRandom
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType

/**
 * Npc drop tables generated from the OSRS Wiki (`tools/drop-tables/wiki_drops_to_tsv.py`) into the
 * `drop_tables.tsv` resource. Each table has:
 * - **always** drops, given on every kill (e.g. bones),
 * - a **main** table rolled once per kill, where each slot is an item or a shared table (gem table,
 *   rare drop table, mega-rare table) and the remaining chance is "nothing",
 * - **tertiary** drops, each rolled independently.
 *
 * Chances are stored as fractions and converted to parts per million for rolling.
 */
@Singleton
public class NpcDropTables
@Inject
constructor(private val objTypes: ObjTypeList, private val random: GameRandom) {
    private val data: DropData by lazy { DropData.load() }

    /** Returns `true` if [npcType] has a drop table. */
    public fun hasTable(npcType: Int): Boolean = npcType in data.byNpc

    /**
     * Rolls the drop table for [npcType]. Returns `null` when the npc has no table, otherwise the
     * objs to drop as (type, count) pairs - possibly empty if the main roll landed on "nothing".
     */
    public fun roll(npcType: Int): List<Pair<UnpackedObjType, Int>>? {
        val table = data.byNpc[npcType] ?: return null
        val drops = ArrayList<Pair<UnpackedObjType, Int>>()
        for (entry in table.always) {
            drops.addDrop(entry)
        }
        rollMain(table.main, drops, depth = 0)
        for (slot in table.tertiary) {
            if (random.of(PPM) < slot.ppm) {
                drops.addDrop(slot.entry)
            }
        }
        return drops
    }

    private fun rollMain(
        slots: List<MainSlot>,
        drops: MutableList<Pair<UnpackedObjType, Int>>,
        depth: Int,
    ) {
        if (slots.isEmpty() || depth > MAX_SHARED_DEPTH) {
            return
        }
        val roll = random.of(PPM)
        var cumulative = 0
        for (slot in slots) {
            cumulative += slot.ppm
            if (roll >= cumulative) {
                continue
            }
            val entry = slot.entry
            if (entry != null) {
                drops.addDrop(entry)
            } else {
                val key = slot.shared ?: return
                val shared = data.shared[key] ?: return
                rollMain(shared, drops, depth + 1)
            }
            return
        }
    }

    private fun MutableList<Pair<UnpackedObjType, Int>>.addDrop(entry: DropEntry) {
        val base = objTypes[entry.obj] ?: return
        val type = if (entry.noted) objTypes.cert(base) else base
        val count = if (entry.max > entry.min) random.of(entry.min, entry.max) else entry.min
        if (count > 0) {
            add(type to count)
        }
    }

    internal class DropEntry(val obj: Int, val min: Int, val max: Int, val noted: Boolean)

    /** A main-table slot: either an [entry] or a reference to a [shared] table. */
    internal class MainSlot(val ppm: Int, val entry: DropEntry?, val shared: String?)

    internal class TertiarySlot(val ppm: Int, val entry: DropEntry)

    internal class DropTable(
        val always: List<DropEntry>,
        val main: List<MainSlot>,
        val tertiary: List<TertiarySlot>,
    )

    internal class DropData(val byNpc: Map<Int, DropTable>, val shared: Map<String, List<MainSlot>>) {
        companion object {
            fun load(): DropData {
                val input =
                    NpcDropTables::class.java.getResourceAsStream(RESOURCE)
                        ?: error("Drop table resource not found: $RESOURCE")
                val lines = input.bufferedReader().use { it.readLines() }
                return parse(lines)
            }

            fun parse(lines: List<String>): DropData {
                val byNpc = HashMap<Int, DropTable>()
                val shared = HashMap<String, List<MainSlot>>()

                var npcs: List<Int>? = null
                var sharedKey: String? = null
                var always = ArrayList<DropEntry>()
                var main = ArrayList<MainSlot>()
                var tertiary = ArrayList<TertiarySlot>()

                fun flush() {
                    val table = DropTable(always, main, tertiary)
                    npcs?.forEach { byNpc[it] = table }
                    sharedKey?.let { shared[it] = main }
                    npcs = null
                    sharedKey = null
                    always = ArrayList()
                    main = ArrayList()
                    tertiary = ArrayList()
                }

                for (raw in lines) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) {
                        continue
                    }
                    val parts = line.split('\t').map(String::trim)
                    when (parts[0]) {
                        "table" -> {
                            flush()
                            npcs = parts[1].split(',').map(String::toInt)
                        }
                        "shared" -> {
                            flush()
                            sharedKey = parts[1]
                        }
                        "always" -> always.add(entry(parts))
                        "main" -> main.add(MainSlot(ppm(parts[5], parts[6]), entry(parts), null))
                        "tertiary" -> tertiary.add(TertiarySlot(ppm(parts[5], parts[6]), entry(parts)))
                        "sharedref" -> main.add(MainSlot(ppm(parts[2], parts[3]), null, parts[1]))
                        else -> error("Unknown drop table line: $raw")
                    }
                }
                flush()
                return DropData(byNpc, shared)
            }

            private fun entry(parts: List<String>): DropEntry =
                DropEntry(
                    obj = parts[1].toInt(),
                    min = parts[2].toInt(),
                    max = parts[3].toInt(),
                    noted = parts[4] == "1",
                )

            private fun ppm(numerator: String, denominator: String): Int =
                (numerator.toLong() * PPM / denominator.toLong()).toInt()
        }
    }

    private companion object {
        const val RESOURCE = "drop_tables.tsv"
        const val PPM = 1_000_000
        const val MAX_SHARED_DEPTH = 3
    }
}
