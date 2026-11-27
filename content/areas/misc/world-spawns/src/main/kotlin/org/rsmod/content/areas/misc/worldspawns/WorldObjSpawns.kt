package org.rsmod.content.areas.misc.worldspawns

import org.rsmod.api.type.builders.map.obj.MapObjSpawnBuilder

/**
 * World-wide ground item spawns generated from OSRS Wiki `ItemSpawnLine` data (see
 * rsmod-tools/wiki_spawns_to_toml.py). Spawns already defined by area modules are excluded.
 */
object WorldObjSpawns : MapObjSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<WorldObjSpawns>("objs.toml")
    }
}
