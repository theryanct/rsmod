package org.rsmod.content.areas.misc.worldspawns

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

/**
 * F2P npc spawns generated from OSRS Wiki `LocLine` data (see
 * tools/wiki-spawns/wiki_npcs_to_toml.py). Map squares owned by area modules are excluded; skipped
 * and unmatched npcs are logged in tools/wiki-spawns/npc_spawns_skipped.tsv. `npcs_infobox.toml` adds
 * npcs with no LocLine (unique npcs from infobox maps, fishing spots from ObjectLocLine), from
 * tools/wiki-spawns/infobox_npc_spawns.py (log: npc_spawns_infobox.tsv).
 */
object WorldNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<WorldNpcSpawns>("npcs.toml")
        resourceFile<WorldNpcSpawns>("npcs_infobox.toml")
    }
}
