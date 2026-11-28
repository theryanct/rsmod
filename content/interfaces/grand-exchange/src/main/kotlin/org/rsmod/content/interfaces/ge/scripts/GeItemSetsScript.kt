package org.rsmod.content.interfaces.ge.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onApNpc1
import org.rsmod.api.script.onApNpc2
import org.rsmod.api.script.onApNpc3
import org.rsmod.api.script.onApNpc4
import org.rsmod.api.script.onApNpc5
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.content.interfaces.ge.configs.GeItemSetComponents
import org.rsmod.content.interfaces.ge.configs.GeItemSetEnums
import org.rsmod.content.interfaces.ge.configs.GeItemSetInterfaces
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.enums.EnumTypeList
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Grand Exchange item sets (clerk "Sets", rev 243 `itemsets` + `itemsets_side`).
 *
 * The client lists the sets from enum 1033 and decides "Pack" (all pieces carried, noted or not)
 * or "Contents"; the side panel shows "Unpack" on set objs (enum 1034). The server does the
 * exchange: Pack takes one of each piece (unnoted first) and gives the set (noted only if every
 * piece used was noted); Unpack gives the pieces back the same way.
 */
class GeItemSetsScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val npcTypes: NpcTypeList,
    private val enumTypes: EnumTypeList,
) : PluginScript() {
    /** Set obj id + piece obj ids, in client list order. */
    private class ItemSet(val set: Int, val pieces: List<Int>)

    private lateinit var sets: List<ItemSet>
    private lateinit var setsByObj: Map<Int, ItemSet>

    override fun ScriptContext.startup() {
        sets = loadSets()
        setsByObj = sets.associateBy { it.set }
        logger.info { "Grand Exchange item sets: ${sets.size} sets." }

        var clerks = 0
        for (type in npcTypes.values) {
            if (!type.name.equals("Grand Exchange Clerk", ignoreCase = true)) {
                continue
            }
            val slot = type.op.indexOfFirst { it.equals("Sets", ignoreCase = true) }
            if (slot != -1) {
                bindClerk(type, slot + 1)
                clerks++
            }
        }
        logger.info { "Grand Exchange item sets: bound $clerks clerks." }

        onIfModalButton(GeItemSetComponents.itemlist) { setOp(it.comsub, it.op) }
        onIfModalButton(GeItemSetComponents.side_items) { sideOp(it.comsub, it.op) }
    }

    private fun loadSets(): List<ItemSet> {
        val list = enumTypes[GeItemSetEnums.SET_LIST] ?: return emptyList()
        return list.primitiveMap.entries
            .sortedBy { (it.key as Int) }
            .mapNotNull { (_, value) ->
                val setEnum = enumTypes[value as Int] ?: return@mapNotNull null
                val entries = setEnum.primitiveMap.mapKeys { it.key as Int }
                val setObj = entries[-1] as? Int ?: return@mapNotNull null
                val pieces =
                    entries.filterKeys { it >= 0 }.toSortedMap().values.mapNotNull { it as? Int }
                ItemSet(setObj, pieces)
            }
    }

    /** Clerks stand behind the booth: approach trigger at range 2 plus the adjacent op trigger. */
    private fun ScriptContext.bindClerk(type: NpcType, slot: Int) {
        val ap: suspend ProtectedAccess.(Npc) -> Unit = { npc ->
            if (isWithinApRange(npc, CLERK_RANGE)) {
                openSets()
            }
        }
        when (slot) {
            1 -> { onApNpc1(type) { ap(it.npc) }; onOpNpc1(type) { openSets() } }
            2 -> { onApNpc2(type) { ap(it.npc) }; onOpNpc2(type) { openSets() } }
            3 -> { onApNpc3(type) { ap(it.npc) }; onOpNpc3(type) { openSets() } }
            4 -> { onApNpc4(type) { ap(it.npc) }; onOpNpc4(type) { openSets() } }
            5 -> { onApNpc5(type) { ap(it.npc) }; onOpNpc5(type) { openSets() } }
        }
    }

    private fun ProtectedAccess.openSets() {
        ifOpenMainSidePair(GeItemSetInterfaces.itemsets, GeItemSetInterfaces.itemsets_side)
        if (sets.isNotEmpty()) {
            ifSetEvents(GeItemSetComponents.itemlist, sets.indices, IfEvent.Op1, IfEvent.Op10)
        }
        ifSetEvents(GeItemSetComponents.side_items, 0 until inv.size, IfEvent.Op1, IfEvent.Op10)
    }

    private fun ProtectedAccess.setOp(index: Int, op: IfButtonOp) {
        val set = sets.getOrNull(index) ?: return
        when (op) {
            IfButtonOp.Op1 -> pack(set)
            IfButtonOp.Op10 -> mes(objTypes[set.set]?.desc ?: return)
            else -> return
        }
    }

    private fun ProtectedAccess.sideOp(slot: Int, op: IfButtonOp) {
        val obj = inv[slot] ?: return
        val type = objTypes[obj.id] ?: return
        when (op) {
            IfButtonOp.Op1 -> unpack(type)
            IfButtonOp.Op10 -> mes(type.desc)
            else -> return
        }
    }

    private fun ProtectedAccess.pack(set: ItemSet) {
        val pieces = set.pieces.mapNotNull { objTypes[it] }
        val missing =
            pieces.filter { invTotal(inv, it) == 0 && invTotal(inv, objTypes.cert(it)) == 0 }
        if (missing.isNotEmpty()) {
            val names = pieces.joinToString(", ") { it.name }
            mes("This set contains: $names.")
            return
        }
        // Every unnoted piece used frees a slot; an all-noted pack needs room for the set.
        val allNoted = pieces.all { invTotal(inv, it) == 0 }
        val setType = objTypes[set.set] ?: return
        val result = if (allNoted) objTypes.cert(setType) else setType
        if (allNoted && inv.freeSpace() == 0 && invTotal(inv, result) == 0) {
            mes("You don't have enough inventory space.")
            return
        }
        for (piece in pieces) {
            if (invTotal(inv, piece) > 0) {
                invDel(inv, piece, 1)
            } else {
                invDel(inv, objTypes.cert(piece), 1)
            }
        }
        invAdd(inv, result, 1)
        mes("You exchange the items for ${setType.name.withArticle()}.")
    }

    private fun ProtectedAccess.unpack(type: UnpackedObjType) {
        val base = objTypes.uncert(type)
        val set = setsByObj[base.id] ?: return
        val noted = type.isCert
        val pieces =
            set.pieces.mapNotNull { objTypes[it] }.map { if (noted) objTypes.cert(it) else it }
        val freedBySet = if (!noted || invTotal(inv, type) == 1) 1 else 0
        val needed = pieces.count { !it.isStackable || invTotal(inv, it) == 0 }
        if (needed > inv.freeSpace() + freedBySet) {
            mes("You don't have enough inventory space to unpack that set.")
            return
        }
        invDel(inv, type, 1)
        for (piece in pieces) {
            invAdd(inv, piece, 1)
        }
        mes("You unpack the ${base.name.lowercase()}.")
    }

    private fun String.withArticle(): String {
        val article = if (first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $this"
    }

    private companion object {
        private const val CLERK_RANGE = 2
    }
}
