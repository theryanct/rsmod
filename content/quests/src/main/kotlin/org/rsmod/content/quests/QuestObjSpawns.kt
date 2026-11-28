package org.rsmod.content.quests

import org.rsmod.api.type.builders.map.obj.MapObjSpawnBuilder

/** Quest ground items the world spawn data doesn't place (OSRS Wiki positions; `objs.toml`). */
internal object QuestObjSpawns : MapObjSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<QuestObjSpawns>("objs.toml")
    }
}
