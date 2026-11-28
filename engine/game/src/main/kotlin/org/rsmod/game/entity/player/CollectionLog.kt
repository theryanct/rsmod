package org.rsmod.game.entity.player

/**
 * A player's collection log: how many of each logged obj they have obtained (keyed by the obj id
 * the client's collection log looks up), and kill counts by lowercase npc name. Persisted by the
 * account collection-log pipeline (migration V10).
 */
public class CollectionLog {
    public val obtained: MutableMap<Int, Int> = LinkedHashMap()
    public val kills: MutableMap<String, Int> = HashMap()

    public fun clear() {
        obtained.clear()
        kills.clear()
    }
}
