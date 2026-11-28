package org.rsmod.content.skills.sailing

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.game.entity.Npc
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shipwright Sam (Port Sarim) hands out the raft that the Pandemonium quest normally rewards (the
 * quest is skipped). The raft is recorded the way the client expects - boat slot 1 owned, type 0
 * (sailing_boat raft), port 0 (sailing_dock Port Sarim) - so the sailing side panel and boat
 * selection see it; boarding and sailing it is tier 1 (world entities).
 */
class Shipwright : PluginScript() {
    override fun ScriptContext.startup() {
        // Cache ops: Talk-to (1), Buy-boat (3); Customise-boat (4) and Recover-boat (5) need boats.
        onOpNpc1(SailingNpcs.shipwright_port_sarim) { talk(it.npc) }
        onOpNpc3(SailingNpcs.shipwright_port_sarim) { talk(it.npc) }
    }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        if (vars[SailingVarBits.boat_1_owned] != 0) {
            startDialogue(npc) { chatNpc(neutral, "Your raft is moored here in Port Sarim, safe and sound.") }
            return
        }
        startDialogue(npc) {
            chatNpc(happy, "Ahoy! Every sailor needs a boat. Here, take this raft - it's not much, but she floats.")
        }
        vars[SailingVarBits.boat_1_owned] = 1
        vars[SailingVarBits.boat_1_type] = RAFT_TYPE
        vars[SailingVarBits.boat_1_port] = PORT_SARIM_DOCK
        mes("You now own a raft. It is moored at Port Sarim.")
    }

    private companion object {
        /** sailing_boat raft: type_id 0. */
        const val RAFT_TYPE = 0
        /** sailing_dock port_sarim: dock_id 0. */
        const val PORT_SARIM_DOCK = 0
    }
}
