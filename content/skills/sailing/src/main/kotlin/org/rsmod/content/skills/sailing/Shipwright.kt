package org.rsmod.content.skills.sailing

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.game.entity.Npc
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shipwright Sam (Port Sarim). The raft is Pandemonium's reward (Junior Jim hands it over during the
 * quest); Sam points players without one to Will and Anne, and replaces the raft for anyone who
 * finished the quest without one (e.g. it predates the quest on this server). The raft is recorded
 * the way the client expects - boat slot 1 owned, type 0 (sailing_boat raft), moored at a
 * sailing_dock - through [SailingBoats], which [Voyages] sails.
 */
class Shipwright : PluginScript() {
    override fun ScriptContext.startup() {
        // Cache ops: Talk-to (1), Buy-boat (3); Customise-boat (4) and Recover-boat (5) need boats.
        onOpNpc1(SailingNpcs.shipwright_port_sarim) { talk(it.npc) }
        onOpNpc3(SailingNpcs.shipwright_port_sarim) { talk(it.npc) }
    }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        if (SailingBoats.ownsBoat(player)) {
            val dock = VoyageData.byId[SailingBoats.dockOf(player)]?.name ?: "Port Sarim"
            startDialogue(npc) { chatNpc(neutral, "Your boat is moored at $dock, safe and sound.") }
            return
        }
        if (vars[SailingVarBits.pandemonium] < PANDEMONIUM_COMPLETE) {
            // Authored (not Wiki): Sam's pointer to the Sailing intro quest.
            startDialogue(npc) {
                chatNpc(
                    neutral,
                    "A boat? You'll want some sea legs first. Will and Anne down on the docks are " +
                        "looking for a new hand - go and talk to them.",
                )
            }
            return
        }
        startDialogue(npc) {
            chatNpc(happy, "Lost your raft, have you? Here, take this one - it's not much, but she floats.")
        }
        SailingBoats.grantRaft(player, SailingBoats.PORT_SARIM_DOCK)
        mes("You now own a raft. It is moored at Port Sarim.")
    }

    private companion object {
        /** `quest_pandemonium` endstate (dbrow); quest varbit `sailing_intro`. */
        const val PANDEMONIUM_COMPLETE = 50
    }
}
