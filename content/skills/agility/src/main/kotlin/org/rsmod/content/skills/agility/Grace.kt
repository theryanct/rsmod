package org.rsmod.content.skills.agility

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
 * Grace (Wiki "Grace's Graceful Clothing", Rogues' Den): spend marks of grace on graceful pieces
 * (hood 35, top 55, legs 60, gloves 30, boots 40, cape 40) and amylase (10 marks). The shop
 * system only takes coins, so Trade is a dialogue menu here. Deviation: the amylase pack is
 * handed over as 100 amylase crystals (what the pack opens into). Spawned by [GraceNpcSpawns].
 * Not yet: recolours, Agility cape graceful.
 */
class Grace @Inject constructor(private val npcTypes: NpcTypeList) : PluginScript() {
    private class Ware(val name: String, val obj: ObjType, val count: Int, val marks: Int)

    private val wares by lazy {
        listOf(
            Ware("Graceful hood", GraceObjs.hood, 1, 35),
            Ware("Graceful top", GraceObjs.top, 1, 55),
            Ware("Graceful legs", GraceObjs.legs, 1, 60),
            Ware("Graceful gloves", GraceObjs.gloves, 1, 30),
            Ware("Graceful boots", GraceObjs.boots, 1, 40),
            Ware("Graceful cape", GraceObjs.cape, 1, 40),
            Ware("Amylase (100)", GraceObjs.amylase, 100, 10),
        )
    }

    override fun ScriptContext.startup() {
        val ops = npcTypes[GraceNpcs.grace].op
        val talk = ops.indexOfFirst { it.equals("Talk-to", ignoreCase = true) } + 1
        val trade = ops.indexOfFirst { it.equals("Trade", ignoreCase = true) } + 1
        bind(talk) { talkTo(it) }
        bind(trade) { shop(it) }
    }

    private fun ScriptContext.bind(op: Int, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val npc = GraceNpcs.grace
        if (op <= 0 || isNpcOpBound(npc, op)) return
        when (op) {
            1 -> onOpNpc1(npc) { action(it.npc) }
            2 -> onOpNpc2(npc) { action(it.npc) }
            3 -> onOpNpc3(npc) { action(it.npc) }
        }
    }

    private suspend fun ProtectedAccess.talkTo(npc: Npc) {
        var wants = false
        // Authored (not Wiki): short greeting in Grace's voice.
        startDialogue(npc) {
            chatNpc(happy, "Hello there! Bring me marks of grace from the rooftops and I'll kit you out in something lighter.")
            wants = choice2("Let's see what you have.", true, "Maybe later.", false)
        }
        if (wants) shop(npc)
    }

    private suspend fun ProtectedAccess.shop(npc: Npc) {
        val marks = invTotal(inv, GraceObjs.mark)
        var picked: Ware? = null
        startDialogue(npc) {
            val first =
                choice5(
                    label(wares[0]), 0,
                    label(wares[1]), 1,
                    label(wares[2]), 2,
                    label(wares[3]), 3,
                    "More...", -1,
                )
            picked =
                if (first >= 0) {
                    wares[first]
                } else {
                    val second =
                        choice4(
                            label(wares[4]), 4,
                            label(wares[5]), 5,
                            label(wares[6]), 6,
                            "Nothing, thanks.", -1,
                        )
                    wares.getOrNull(second)
                }
        }
        val ware = picked ?: return
        if (marks < ware.marks) {
            mes("You need ${ware.marks} marks of grace for that; you have $marks.")
            return
        }
        // Paying with the whole mark stack frees its slot; amylase stacks.
        val needsSlot = ware.obj.id != GraceObjs.amylase.id && marks != ware.marks
        if (needsSlot && !inv.hasFreeSpace()) {
            mes("You don't have enough inventory space.")
            return
        }
        invDel(inv, GraceObjs.mark, ware.marks)
        invAdd(inv, ware.obj, ware.count)
        mes("You trade ${ware.marks} marks of grace for ${ware.name.lowercase()}.")
    }

    private fun label(ware: Ware): String = "${ware.name} - ${ware.marks} marks"
}

internal object GraceNpcs : NpcReferences() {
    val grace = find("rooftops_grace")
}

internal object GraceObjs : ObjReferences() {
    val mark = find("grace")
    val hood = find("graceful_hood")
    val top = find("graceful_top")
    val legs = find("graceful_legs")
    val gloves = find("graceful_gloves")
    val boots = find("graceful_boots")
    val cape = find("graceful_cape")
    val amylase = find("amylase")
}

/** Rogues' Den, Wiki map (3050, 4961) on plane 1 (her dog is world-spawned at 3051, 4963, 1). */
object GraceNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<Grace>("grace_npcs.toml")
    }
}
