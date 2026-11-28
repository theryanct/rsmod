package org.rsmod.api.player.tools

import org.rsmod.api.config.refs.invs
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.inv.isType
import org.rsmod.game.type.content.ContentGroupType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType

/*
 * Tool checks shared by every skill: a tool counts as carried when it is worn, in the
 * inventory, or on the tool belt (content/interfaces/tool-belt). Skills must use these instead of
 * looking at `inv` / `worn` directly, so a belted tool works everywhere.
 */

/** The player's tool belt, or `null` if they have never put anything on it. */
public val Player.toolBelt: Inventory?
    get() = invMap[invs.toolbelt]

/** Every obj that counts as a carried tool: worn items first, then inventory, then tool belt. */
public fun Player.carriedTools(): Sequence<InvObj> = sequence {
    for (obj in worn) {
        if (obj != null) yield(obj)
    }
    for (obj in inv) {
        if (obj != null) yield(obj)
    }
    val belt = toolBelt ?: return@sequence
    for (obj in belt) {
        if (obj != null) yield(obj)
    }
}

/** `true` if [tool] is worn, carried or on the tool belt. */
public fun Player.hasTool(tool: ObjType): Boolean = carriedTools().any { it.isType(tool) }

/** `true` if any of [tools] is worn, carried or on the tool belt. */
public fun Player.hasAnyTool(tools: Iterable<ObjType>): Boolean = tools.any { hasTool(it) }

/** `true` if a tool in [content] group (e.g. `content.woodcutting_axe`) is carried anywhere. */
public fun Player.hasToolOf(objTypes: ObjTypeList, content: ContentGroupType): Boolean =
    carriedTools().any { objTypes[it].isContentType(content) }

/**
 * The best carried tool: among objs passing [usable], the one with the highest [rank] (e.g. the
 * highest-level axe the player can use). Ties go to worn, then inventory, then tool belt.
 */
public fun Player.bestTool(
    objTypes: ObjTypeList,
    usable: (UnpackedObjType) -> Boolean,
    rank: (UnpackedObjType) -> Int,
): InvObj? {
    var best: InvObj? = null
    var bestRank = Int.MIN_VALUE
    for (obj in carriedTools()) {
        val type = objTypes[obj]
        if (!usable(type)) {
            continue
        }
        val r = rank(type)
        if (best == null || r > bestRank) {
            best = obj
            bestRank = r
        }
    }
    return best
}
