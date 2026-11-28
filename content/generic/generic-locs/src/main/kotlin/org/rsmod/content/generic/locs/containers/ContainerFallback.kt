package org.rsmod.content.generic.locs.containers

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Generic fallback for plain containers and furniture (chests, cupboards, drawers, wardrobes,
 * crates, boxes, barrels, sacks, bookcases, shelves, tables, desks, urns...), as OSRS does for the
 * ones that hold nothing:
 * - "Open" swaps the loc for its open partner (found by name: `cupboard` / `cupboardopen`,
 *   `shutcandlechest` / `opencandlechest`, `closedchest` / `openchest`, ...) for a while, like a
 *   door; with no partner it says the container is empty.
 * - "Close"/"Shut" swaps it back.
 * - "Search" says "You search the <name> but find nothing."
 *
 * Only ops that nothing else handles (no op or ap trigger: quest, clue and STASH locs keep
 * priority) and non-wall shapes (doors are the door fallback's). Each use logs a `GENERIC-LOC`
 * line, and `::unboundops` lists these ops as covered by "generic container" so the ones that hold something in
 * the real game still get real handlers.
 */
class ContainerFallback
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val locInteractions: LocInteractions,
) : PluginScript() {
    private val logger = InlineLogger()

    /** Closed loc id -> open partner id, and the reverse. */
    private val partners = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        buildPartners()
        OpInterceptors.register(Interceptor())
    }

    private fun buildPartners() {
        val byName = HashMap<String, UnpackedLocType>()
        for (type in locTypes.values) type.internalName?.let { byName[it] = type }
        for (type in locTypes.values) {
            val name = type.internalName ?: continue
            if (!type.isContainer() || type.op.none { action(it) == Action.Open }) continue
            val partner =
                candidates(name).firstNotNullOfOrNull { candidate ->
                    byName[candidate]?.takeIf { it.id != type.id && it.op.any { op -> action(op) == Action.Close } }
                } ?: continue
            partners.putIfAbsent(type.id, partner.id)
            partners.putIfAbsent(partner.id, type.id)
        }
    }

    private fun candidates(name: String): List<String> =
        listOf(
            name + "open",
            name + "_open",
            name.replace("closed", "open"),
            name.replace("shut", "open"),
            name.replace("_closed", "_open"),
            "open$name",
            "open_$name",
        ).filter { it != name }

    private inner class Interceptor : OpInterceptors.LocOp {
        override fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean {
            if (loc.shape == LocShape.WallStraight || loc.shape == LocShape.WallDiagonal) return false
            val type = locTypes[loc]
            if (!type.isContainer()) return false
            if (action(type.op.getOrNull(op.slot - 1)) == null) return false
            return locInteractions.opTrigger(player, loc, op, type) == null &&
                locInteractions.apTrigger(player, loc, op, type) == null
        }

        override val auditName: String = "generic container"

        override fun auditCovers(type: UnpackedLocType, shape: Int, coords: CoordGrid, op: Int): Boolean =
            shape != LocShape.WallStraight.id &&
                shape != LocShape.WallDiagonal.id &&
                type.isContainer() &&
                action(type.op.getOrNull(op - 1)) != null

        override suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp) {
            val type = locTypes[loc]
            val label = type.op.getOrNull(op.slot - 1)
            val name = type.name.lowercase()
            logger.info { "GENERIC-LOC ${type.internalName ?: type.id} op${op.slot} '$label' at ${loc.coords}" }
            when (action(label)) {
                Action.Open -> {
                    val into = partners[type.id]?.let { locTypes.types[it] }
                    if (into == null) {
                        mes("You open the $name but find nothing.")
                        return
                    }
                    locRepo.change(loc, into, DURATION)
                    mes("You open the $name.")
                }
                Action.Close -> {
                    val into = partners[type.id]?.let { locTypes.types[it] }
                    if (into != null) locRepo.change(loc, into, DURATION)
                    mes("You close the $name.")
                }
                Action.Search -> mes("You search the $name but find nothing.")
                null -> Unit
            }
        }
    }

    private enum class Action {
        Open,
        Close,
        Search,
    }

    private fun action(label: String?): Action? =
        when (label?.trim()?.lowercase()) {
            "open" -> Action.Open
            "close", "shut" -> Action.Close
            "search" -> Action.Search
            else -> null
        }

    private fun UnpackedLocType.isContainer(): Boolean {
        val n = name.lowercase()
        if (n.isBlank() || n == "null") return false
        return CONTAINER_WORDS.any { word -> n.split(' ', '\'').any { it == word || it == word + "s" } }
    }

    private companion object {
        /** How long an opened container stays open (ticks), like the doors. */
        const val DURATION = 500

        val CONTAINER_WORDS =
            setOf(
                "chest",
                "cupboard",
                "drawers",
                "drawer",
                "wardrobe",
                "crate",
                "box",
                "boxes",
                "barrel",
                "sack",
                "bookcase",
                "bookshelf",
                "bookshelves",
                "shelf",
                "shelves",
                "table",
                "desk",
                "urn",
                "cabinet",
                "dresser",
                "chests",
                "cupboards",
            )
    }
}
