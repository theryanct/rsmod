package org.rsmod.content.interfaces.ge.scripts

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onApNpc1
import org.rsmod.api.script.onApNpc2
import org.rsmod.api.script.onApNpc3
import org.rsmod.api.script.onApNpc4
import org.rsmod.api.script.onApNpc5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.content.interfaces.ge.openGeHistory
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Clerk "History": the trade history, usable across the booth (ap range 2) like Exchange. */
class GeHistoryScript @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for (type in npcTypes.values) {
            if (!type.name.equals("Grand Exchange Clerk", ignoreCase = true)) {
                continue
            }
            val slot = type.op.indexOfFirst { it.equals("History", ignoreCase = true) }
            if (slot != -1) {
                bindClerk(type, slot + 1)
            }
        }
    }

    private fun ScriptContext.bindClerk(type: NpcType, slot: Int) {
        val ap: suspend ProtectedAccess.(Npc) -> Unit = { npc ->
            if (isWithinApRange(npc, CLERK_RANGE)) {
                openGeHistory()
            }
        }
        when (slot) {
            1 -> {
                onApNpc1(type) { ap(it.npc) }
                onOpNpc1(type) { openGeHistory() }
            }
            2 -> {
                onApNpc2(type) { ap(it.npc) }
                onOpNpc2(type) { openGeHistory() }
            }
            3 -> {
                onApNpc3(type) { ap(it.npc) }
                onOpNpc3(type) { openGeHistory() }
            }
            4 -> {
                onApNpc4(type) { ap(it.npc) }
                onOpNpc4(type) { openGeHistory() }
            }
            5 -> {
                onApNpc5(type) { ap(it.npc) }
                onOpNpc5(type) { openGeHistory() }
            }
        }
    }

    private companion object {
        private const val CLERK_RANGE = 2
    }
}
