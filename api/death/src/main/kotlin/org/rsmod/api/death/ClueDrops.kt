package org.rsmod.api.death

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.UnpackedObjType

/**
 * Clue scroll drops (`clue_drops.tsv`, generated from the OSRS Wiki by
 * tools/clues/wiki_clue_drops_to_tsv.py): each npc rolls each clue tier it drops independently.
 * A hit is turned into an actual clue by the treasure trails content, which sets [resolver]; it
 * returns `null` when the player can't get another clue of that tier (one per tier at a time).
 */
@Singleton
public class ClueDrops @Inject constructor(private val random: GameRandom) {
    public fun interface Resolver {
        /** The clue obj [hero] should get for a [tier] ("beginner", "easy", ...) drop, if any. */
        public fun resolve(hero: Player, tier: String): UnpackedObjType?
    }

    private class Chance(val tier: String, val num: Int, val den: Int)

    private val byNpc: Map<Int, List<Chance>> by lazy { load() }

    /** The clue drops [hero] gets from killing an npc of [npcType]. */
    public fun roll(npcType: Int, hero: Player): List<Pair<UnpackedObjType, Int>> {
        val resolver = Companion.resolver ?: return emptyList()
        val chances = byNpc[npcType] ?: return emptyList()
        val drops = ArrayList<Pair<UnpackedObjType, Int>>(1)
        for (chance in chances) {
            if (random.of(chance.den) >= chance.num) {
                continue
            }
            val obj = resolver.resolve(hero, chance.tier) ?: continue
            drops += obj to 1
        }
        return drops
    }

    private fun load(): Map<Int, List<Chance>> {
        val input =
            ClueDrops::class.java.getResourceAsStream(RESOURCE)
                ?: error("Clue drop resource not found: $RESOURCE")
        val byNpc = HashMap<Int, MutableList<Chance>>()
        input.bufferedReader().use { reader ->
            for (raw in reader.readLines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) {
                    continue
                }
                val parts = line.split('\t').map(String::trim)
                if (parts[0] != "clue") {
                    continue
                }
                val chance = Chance(parts[1], parts[3].toInt(), parts[4].toInt())
                for (npc in parts[2].split(',')) {
                    byNpc.getOrPut(npc.toInt()) { ArrayList() } += chance
                }
            }
        }
        return byNpc
    }

    public companion object {
        private const val RESOURCE = "clue_drops.tsv"

        /** Set by the treasure trails content at startup; no clue drops until it is. */
        @Volatile public var resolver: Resolver? = null
    }
}
