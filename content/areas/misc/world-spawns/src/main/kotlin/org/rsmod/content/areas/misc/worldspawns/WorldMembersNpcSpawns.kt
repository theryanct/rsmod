package org.rsmod.content.areas.misc.worldspawns

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

/**
 * Members-world npc spawns generated from OSRS Wiki `LocLine` data (`wiki_npcs_to_toml.py
 * --members`). Bosses, minigame, instance and quest npcs are excluded; skips are logged in
 * tools/wiki-spawns/npc_spawns_members_skipped.tsv. `npcs_members_resolved.toml` adds the skipped
 * npcs a second pass could match by cache combat level or model (`resolve_skipped_npcs.py`; the
 * still-ambiguous ones are logged in tools/wiki-spawns/npc_spawns_resolved.tsv).
 */
object WorldMembersNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<WorldMembersNpcSpawns>("npcs_members.toml")
        resourceFile<WorldMembersNpcSpawns>("npcs_members_resolved.toml")
    }
}
