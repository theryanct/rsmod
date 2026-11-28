package org.rsmod.content.skills.slayer

import jakarta.inject.Inject
import org.rsmod.api.npc.queueDeath
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.tools.hasAnyTool
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onNpcHit
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Gargoyles (OSRS Wiki): damage can't kill them; once they are at [SMASH_HP] or fewer Hitpoints
 * they must be finished with a rock hammer (or granite hammer) - use it on them. With the
 * Gargoyle Smasher unlock (slayer_unlock bit 0) and a hammer carried, they are smashed
 * automatically as soon as a hit takes them that low. Covers the Slayer Tower / cave gargoyles
 * and the marble gargoyle superior; the Grotesque Guardians boss is not included.
 */
class Gargoyles
@Inject
constructor(private val playerList: PlayerList, private val mapClock: MapClock) :
    PluginScript() {
    override fun ScriptContext.startup() {
        for (gargoyle in GargoyleNpcs.all) {
            // Hits never take a gargoyle below 1 Hitpoint.
            onModifyNpcHit(gargoyle) {
                if (hit.damage >= npc.hitpoints) {
                    hit.damage = (npc.hitpoints - 1).coerceAtLeast(0)
                }
            }
            onNpcHit(gargoyle) {
                refreshSuperiorLife(npc, mapClock)
                if (!hit.isFromPlayer || npc.hitpoints > SMASH_HP || npc.hitpoints <= 0) {
                    return@onNpcHit
                }
                val player = hit.resolvePlayerSource(playerList) ?: return@onNpcHit
                if (player.hasSlayerUnlock(GARGOYLE_SMASHER_BIT) && player.hasHammer()) {
                    smash(npc, player)
                }
            }
            for (hammer in GargoyleObjs.hammers) {
                onOpNpcU(gargoyle, hammer) { useHammer(it.npc) }
            }
        }
    }

    private fun ProtectedAccess.useHammer(npc: Npc) {
        if (npc.hitpoints <= 0) {
            return
        }
        if (npc.hitpoints > SMASH_HP) {
            mes("The gargoyle isn't weak enough to be harmed by the hammer.")
            return
        }
        smash(npc, player)
    }

    private fun smash(npc: Npc, player: Player) {
        npc.hitpoints = 0
        npc.queueDeath()
        player.mes("The gargoyle cracks apart.")
    }

    private fun Player.hasHammer(): Boolean = hasAnyTool(GargoyleObjs.hammers)

    private companion object {
        /** Wiki: gargoyles can be finished with 9 or fewer Hitpoints. */
        const val SMASH_HP = 9
        /** slayer_unlock "Gargoyle Smasher". */
        const val GARGOYLE_SMASHER_BIT = 0
    }
}

internal object GargoyleNpcs : NpcReferences() {
    val all =
        listOf(find("slayer_gargoyle_1"), find("slayer_cave_gargoyle"), find("superior_gargoyle"))
}

internal object GargoyleObjs : ObjReferences() {
    val hammers = listOf(find("slayer_rock_hammer"), find("granite_hammer"))
}
