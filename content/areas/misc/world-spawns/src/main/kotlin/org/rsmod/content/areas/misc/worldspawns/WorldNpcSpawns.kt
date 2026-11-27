package org.rsmod.content.areas.misc.worldspawns

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

/**
 * F2P npc spawns generated from OSRS Wiki `LocLine` data (see
 * tools/wiki-spawns/wiki_npcs_to_toml.py). Map squares owned by area modules are excluded; skipped
 * and unmatched npcs are logged in tools/wiki-spawns/npc_spawns_skipped.tsv.
 */
object WorldNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<WorldNpcSpawns>("npcs.toml")
    }
}
