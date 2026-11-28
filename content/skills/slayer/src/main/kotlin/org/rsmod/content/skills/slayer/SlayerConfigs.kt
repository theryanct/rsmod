package org.rsmod.content.skills.slayer

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.npc.NpcType

object SlayerNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        (SlayerData.masters.map { it.npc } + SlayerData.categories.flatMap { it.npcs })
            .distinct()
            .associateWith { find(it) }
}

object SlayerObjs : ObjReferences() {
    val enchanted_gem = find("slayer_gem")
}

/** Saved per character (world symbol range 65700-65899). */
object SlayerVarpBuilds : VarpBuilder() {
    init {
        build("slayer_task")
        build("slayer_task_count")
        build("slayer_points")
        build("slayer_streak")
        build("slayer_task_master")
        build("slayer_gem_given")
    }
}

/** Blocked and stored tasks (skilling symbol range 65500-65699). Saved per character. */
object SlayerBlockVarpBuilds : VarpBuilder() {
    init {
        for (slot in 1..SLAYER_BLOCK_SLOTS) build("slayer_block_$slot")
        build("slayer_stored_task")
        build("slayer_stored_count")
        build("slayer_stored_master")
    }
}

const val SLAYER_BLOCK_SLOTS: Int = 6

object SlayerBlockVarps : VarpReferences() {
    /** [SlayerData.Category.id] per block slot, 0 = empty. */
    val blocks = (1..SLAYER_BLOCK_SLOTS).map { find("slayer_block_$it") }
    val stored_task = find("slayer_stored_task")
    val stored_count = find("slayer_stored_count")
    val stored_master = find("slayer_stored_master")
}

object SlayerVarps : VarpReferences() {
    /** [SlayerData.Category.id] of the current task, 0 = none. */
    val task = find("slayer_task")
    val count = find("slayer_task_count")
    val points = find("slayer_points")
    val streak = find("slayer_streak")
    /** [SlayerData.Master.id] that assigned the current task. */
    val master = find("slayer_task_master")
    /** 1 once the free enchanted gem from the first assignment has been given. */
    val gem_given = find("slayer_gem_given")
}

object SlayerNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<SlayerNpcSpawns>("npcs.toml")
    }
}
