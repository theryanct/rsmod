package org.rsmod.content.quests.princeali

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.princeali.PrinceAliRescue.Companion.PRINCE_ALI_RESCUE
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Lumbridge - Al Kharid toll gate (x 3268, z 3227-3228). The gate halves are multilocs on
 * `princequest`: the "_2op" variants (Open, Pay-toll(10gp)) until Prince Ali Rescue is done, then
 * "_1op" (Open, free). Open on a paid gate talks to the border guard; Pay-toll pays 10 coins and
 * walks through. Either way, both directions.
 */
class TollGate
@Inject
constructor(private val npcTypes: NpcTypeList, private val locTypes: LocTypeList) :
    PluginScript() {
    override fun ScriptContext.startup() {
        for (gate in TollGateLocs.paid) {
            onLocOpLabel(locTypes, gate, "Open") { guardDialogue(it) }
            onLocOpLabel(locTypes, gate, "Pay-toll(10gp)") { payToll(it) }
        }
        for (gate in TollGateLocs.free) {
            onLocOpLabel(locTypes, gate, "Open") { passThroughWall(it) }
        }
        for (guard in TollGateNpcs.guards) {
            onNpcOpLabel(npcTypes, guard, "Talk-to") { guardDialogue(null) }
        }
    }

    /** Talk to a border guard (or open the gate): pay the toll, or free after Prince Ali Rescue. */
    private suspend fun ProtectedAccess.guardDialogue(gate: BoundLocInfo?) {
        val guard = TollGateNpcs.guards.first()
        val name = npcTypes[guard].name
        if (player.isQuestComplete(PRINCE_ALI_RESCUE)) {
            startDialogue {
                chatNpcSpecific(name, guard, happy, "You may pass for free, friend of Al Kharid.")
            }
            if (gate != null) passThroughWall(gate) else crossGate()
            return
        }
        var pay = false
        startDialogue {
            chatPlayer(quiz, "Can I come through this gate?")
            chatNpcSpecific(name, guard, neutral, "You must pay a toll of $TOLL gold coins.")
            pay = choice2("Okay, I'll pay.", true, "No thank you, I'll walk around.", false)
            if (!pay) {
                chatPlayer(neutral, "No thank you, I'll walk around.")
                chatNpcSpecific(name, guard, neutral, "Ok suit yourself.")
            }
        }
        if (pay) payToll(gate)
    }

    private suspend fun ProtectedAccess.payToll(gate: BoundLocInfo?) {
        if (!carries(TollGateObjs.coins, TOLL)) {
            mes("You need $TOLL coins to pass through the gate.")
            return
        }
        invDel(inv, TollGateObjs.coins, TOLL)
        mes("You pay the guard.")
        if (gate != null) passThroughWall(gate) else crossGate()
    }

    /**
     * Paid or waved through by a guard (no gate clicked): cross the gate line (west edge of x
     * 3268) at the gate half nearest the player.
     */
    private suspend fun ProtectedAccess.crossGate() {
        val here = player.coords
        val z = here.z.coerceIn(GATE_Z_SOUTH, GATE_Z_SOUTH + 1)
        val x = if (here.x < GATE_X) GATE_X else GATE_X - 1
        telejump(CoordGrid(x, z, 0))
        delay(1)
    }

    private companion object {
        const val TOLL = 10
        const val GATE_X = 3268
        const val GATE_Z_SOUTH = 3227
    }
}

// Every cache ref the toll gate uses (re-check after the rev 243 cutover).

internal object TollGateLocs : LocReferences() {
    val paid = listOf(find("kharidmetalgateclosedl_2op"), find("kharidmetalgateclosedr_2op"))
    val free = listOf(find("kharidmetalgateclosedl_1op"), find("kharidmetalgateclosedr_1op"))
}

internal object TollGateNpcs : NpcReferences() {
    val guards = listOf(find("borderguard1"), find("borderguard2"))
}

internal object TollGateObjs : ObjReferences() {
    val coins = find("coins")
}
