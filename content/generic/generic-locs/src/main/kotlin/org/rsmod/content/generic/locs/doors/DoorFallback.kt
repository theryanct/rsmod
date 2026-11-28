package org.rsmod.content.generic.locs.doors

import jakarta.inject.Inject
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.interact.OpInterceptors
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
 * Generic fallback for doors and gates (Ryan: Fred's farmhouse door, Lumbridge Castle's upstairs
 * doors and others did nothing; the door scripts only cover the few types given a content group).
 *
 * Any wall-shaped loc (straight or diagonal) whose clicked op reads "Open" or "Close" and that no
 * script handles (no op or ap trigger: quest doors and the configured doors keep priority) is
 * swapped for its open/closed partner, found by name (`poordoor` / `poordooropen`, `fencegate_l` /
 * `openfencegate_l`, `metalgateclosedl` / `metalgateopenl`, ...), and moved/turned the way the
 * single-door script does. A door with no partner is turned in place. Each leaf of a double door
 * opens on its own. The change reverts like the other doors. Multilocs are left alone (their
 * visible child decides what they are).
 *
 * Runs as an [OpInterceptors.LocOp] whose claim checks that nothing else would handle the op, so it
 * never competes with a bound script whatever the boot order.
 */
class DoorFallback
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val locInteractions: LocInteractions,
) : PluginScript() {
    /** Loc id -> its open (for closed doors) or closed (for open doors) partner's id. */
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
            if (type.op.getOrNull(0)?.equals(OPEN, ignoreCase = true) != true) continue
            val partner =
                candidates(name).firstNotNullOfOrNull { candidate ->
                    byName[candidate]?.takeIf {
                        it.id != type.id && it.op.getOrNull(0)?.equals(CLOSE, ignoreCase = true) == true
                    }
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
            LAST_WORD.replace(name) { match -> "open" + match.value },
            LAST_TOKEN.replace(name) { match -> "_open" + match.groupValues[1] },
            insertAtFirst(name, before = true),
            insertAtFirst(name, before = false),
        )

    /** `name` with "open" put before (or after) its first "door"/"gate". */
    private fun insertAtFirst(name: String, before: Boolean): String {
        val match = DOOR_OR_GATE.find(name) ?: return name
        val word = if (before) "open" + match.value else match.value + "open"
        return name.replaceRange(match.range, word)
    }

    private inner class Interceptor : OpInterceptors.LocOp {
        override fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean {
            if (loc.shape != LocShape.WallStraight && loc.shape != LocShape.WallDiagonal) return false
            val type = locTypes[loc]
            if (type.multiLoc.isNotEmpty() || type.multiLocDefault > 0) return false
            val label = type.op.getOrNull(op.slot - 1) ?: return false
            if (!label.equals(OPEN, ignoreCase = true) && !label.equals(CLOSE, ignoreCase = true)) return false
            return locInteractions.opTrigger(player, loc, op, type) == null &&
                locInteractions.apTrigger(player, loc, op, type) == null
        }

        override val auditName: String = "door fallback"

        override fun auditCovers(type: UnpackedLocType, shape: Int, coords: CoordGrid, op: Int): Boolean {
            if (shape != LocShape.WallStraight.id && shape != LocShape.WallDiagonal.id) return false
            if (type.multiLoc.isNotEmpty() || type.multiLocDefault > 0) return false
            val label = type.op.getOrNull(op - 1) ?: return false
            return label.equals(OPEN, ignoreCase = true) || label.equals(CLOSE, ignoreCase = true)
        }

        override suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp) {
            val type = locTypes[loc]
            val opening = type.op.getOrNull(op.slot - 1).equals(OPEN, ignoreCase = true)
            swing(loc, opening, partners[type.id]?.let { locTypes[it] } ?: type)
        }
    }

    private suspend fun ProtectedAccess.swing(door: BoundLocInfo, opening: Boolean, into: UnpackedLocType) {
        val coords =
            if (opening) {
                DoorTranslations.translateOpen(door.coords, door.shape, door.angle)
            } else {
                DoorTranslations.translateClose(door.coords, door.shape, door.angle)
            }
        val angle = if (opening) door.turnAngle(rotations = 1) else door.turnAngle(rotations = -1)
        locRepo.del(door, DoorConstants.DURATION)
        locRepo.add(coords, into, DoorConstants.DURATION, angle, door.shape)
        delay(1)
    }

    private companion object {
        const val OPEN = "Open"
        const val CLOSE = "Close"
        val LAST_WORD = Regex("[a-z]+$")
        val LAST_TOKEN = Regex("_([a-z]+)$")
        val DOOR_OR_GATE = Regex("door|gate")
    }
}
