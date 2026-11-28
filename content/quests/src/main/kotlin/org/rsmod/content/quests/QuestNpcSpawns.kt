package org.rsmod.content.quests

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

/** Quest npcs the world spawn data doesn't place yet (OSRS Wiki map positions; `npcs.toml`). */
internal object QuestNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<QuestNpcSpawns>("npcs.toml")
    }
}
