package org.rsmod.content.interfaces.ge

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder

/**
 * Grand Exchange clerks and bankers. The wiki spawn generator skips them (the clerk page only has
 * an area polygon), so they are placed one tile inside the booths read from cache map square
 * l49_54: Exchange booths (loc 10061) north and south, bank booths (loc 10060) west and east.
 */
object GeNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<GeNpcSpawns>("npcs.toml")
    }
}
