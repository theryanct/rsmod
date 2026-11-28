package org.rsmod.content.skills.construction

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sawmill operator (Wiki "Sawmill operator" / "Sawmill"): Buy-plank turns logs into planks for a
 * fee per plank (plank 100, oak 250, teak 500, mahogany 1,500 coins). Unnoted logs only. Spawned
 * at the Varrock Lumber Yard and the Woodcutting Guild by [SawmillNpcSpawns]. Talk-to offers the
 * same. Not yet: the Construction supplies shop (Trade), sailing-wood planks, Plank Make.
 */
class Sawmill @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    private class Plank(val log: ObjType, val plank: ObjType, val fee: Int, val name: String)

    private val planks by lazy {
        listOf(
            Plank(SawmillObjs.logs, SawmillObjs.plank, 100, "Plank"),
            Plank(SawmillObjs.oak_logs, SawmillObjs.plank_oak, 250, "Oak plank"),
            Plank(SawmillObjs.teak_logs, SawmillObjs.plank_teak, 500, "Teak plank"),
            Plank(SawmillObjs.mahogany_logs, SawmillObjs.plank_mahogany, 1500, "Mahogany plank"),
        )
    }

    override fun ScriptContext.startup() {
        val ops = npcTypes[SawmillNpcs.operator].op
        val talk = ops.indexOfFirst { it.equals("Talk-to", ignoreCase = true) } + 1
        val buy = ops.indexOfFirst { it.equals("Buy-plank", ignoreCase = true) } + 1
        bind(talk) { talkTo(it) }
        bind(buy) { buyPlanks(it) }
    }

    private fun ScriptContext.bind(op: Int, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val npc = SawmillNpcs.operator
        // A quest (e.g. Dragon Slayer's Lady Lumbridge planks) may own the op; boot order must not matter.
        if (op <= 0 || isNpcOpBound(npc, op)) return
        when (op) {
            1 -> onOpNpc1(npc) { action(it.npc) }
            2 -> onOpNpc2(npc) { action(it.npc) }
            3 -> onOpNpc3(npc) { action(it.npc) }
        }
    }

    private suspend fun ProtectedAccess.talkTo(npc: Npc) {
        var wants = false
        // Authored (not Wiki): short greeting in the operator's voice.
        startDialogue(npc) {
            chatNpc(neutral, "Do you want me to make some planks for you? Or would you be interested in some other housing supplies?")
            wants = choice2("Planks please!", true, "No thanks.", false)
        }
        if (wants) buyPlanks(npc)
    }

    private suspend fun ProtectedAccess.buyPlanks(npc: Npc) {
        val carried = planks.filter { invTotal(inv, it.log) > 0 }
        if (carried.isEmpty()) {
            startDialogue(npc) {
                chatNpc(neutral, "You'll need to bring me some logs to make planks. Not noted ones, mind.")
            }
            return
        }
        var chosen: Plank? = null
        startDialogue(npc) {
            chosen =
                when (carried.size) {
                    1 -> carried[0]
                    2 -> choice2(label(carried[0]), carried[0], label(carried[1]), carried[1])
                    3 ->
                        choice3(
                            label(carried[0]), carried[0],
                            label(carried[1]), carried[1],
                            label(carried[2]), carried[2],
                        )
                    else ->
                        choice4(
                            label(carried[0]), carried[0],
                            label(carried[1]), carried[1],
                            label(carried[2]), carried[2],
                            label(carried[3]), carried[3],
                        )
                }
        }
        val plank = chosen ?: return
        val logs = invTotal(inv, plank.log)
        val wanted = if (logs == 1) 1 else countDialog("How many ${plank.name.lowercase()}s? (max $logs)")
        val affordable = invTotal(inv, SawmillObjs.coins) / plank.fee
        val count = minOf(wanted, logs, affordable)
        if (count <= 0) {
            if (affordable <= 0) {
                startDialogue(npc) { chatNpc(neutral, "You'll need ${plank.fee} coins a plank, I'm afraid.") }
            }
            return
        }
        invDel(inv, SawmillObjs.coins, count * plank.fee)
        invDel(inv, plank.log, count)
        invAdd(inv, plank.plank, count)
        mes(
            if (count < wanted && count == affordable) {
                "You can only afford $count ${plank.name.lowercase()}${if (count == 1) "" else "s"}."
            } else {
                "The sawmill operator makes you $count ${plank.name.lowercase()}${if (count == 1) "" else "s"}."
            }
        )
    }

    private fun label(plank: Plank): String = "${plank.name} - ${"%,d".format(plank.fee)}gp"
}

internal object SawmillNpcs : NpcReferences() {
    val operator = find("poh_sawmill_opp")
}

internal object SawmillObjs : ObjReferences() {
    val coins = find("coins")
    val logs = find("logs")
    val oak_logs = find("oak_logs")
    val teak_logs = find("teak_logs")
    val mahogany_logs = find("mahogany_logs")
    val plank = find("woodplank")
    val plank_oak = find("plank_oak")
    val plank_teak = find("plank_teak")
    val plank_mahogany = find("plank_mahogany")
}

/** Varrock Lumber Yard (3302, 3492) and the Woodcutting Guild (1623, 3500), Wiki map coords. */
object SawmillNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<Sawmill>("sawmill_npcs.toml")
    }
}
