package org.rsmod.content.other.starterkit

import jakarta.inject.Inject
import org.rsmod.api.config.refs.invs
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.game.entity.Player
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Gives new accounts the items a player leaves Tutorial Island with (Learning the Ropes rewards,
 * per the OSRS Wiki): the starter inventory plus 25 coins in the bank. `::starter` re-grants the
 * inventory items (no bank coins) for testing or replacing lost gear.
 */
class StarterKitScript @Inject constructor(private val invTypes: InvTypeList) : PluginScript() {
    private val Player.newAccount by boolVarBit(varbits.new_player_account)

    override fun ScriptContext.startup() {
        onPlayerLogin { player.giveNewAccountKit() }
        onCommand("starter") {
            modLevel = modlevels.admin
            desc = "Give yourself the Tutorial Island starter kit"
            cheat { player.giveStarterInv() }
        }
    }

    private fun Player.giveNewAccountKit() {
        if (!newAccount) {
            return
        }
        giveStarterInv()
        val bank = invMap.getOrPut(invTypes[invs.bank])
        invAdd(bank, starter_objs.coins, BANK_COINS)
    }

    private fun Player.giveStarterInv() {
        val free = inv.freeSpace()
        if (free < STARTER_INV.size) {
            mes("You need ${STARTER_INV.size} free inventory slots for the starter kit.")
            return
        }
        for ((obj, count) in STARTER_INV) {
            invAdd(inv, obj, count)
        }
        mes("You receive the Tutorial Island starter kit.")
    }

    private companion object {
        const val BANK_COINS = 25

        /** Order matches the Learning the Ropes reward list. */
        val STARTER_INV: List<Pair<ObjType, Int>> =
            listOf(
                starter_objs.bronze_axe to 1,
                starter_objs.bronze_pickaxe to 1,
                starter_objs.tinderbox to 1,
                starter_objs.small_fishing_net to 1,
                starter_objs.shrimps to 1,
                starter_objs.bronze_dagger to 1,
                starter_objs.bronze_sword to 1,
                starter_objs.wooden_shield to 1,
                starter_objs.shortbow to 1,
                starter_objs.bronze_arrow to 25,
                starter_objs.air_rune to 25,
                starter_objs.mind_rune to 15,
                starter_objs.bucket to 1,
                starter_objs.pot to 1,
                starter_objs.bread to 1,
                starter_objs.water_rune to 6,
                starter_objs.earth_rune to 4,
                starter_objs.body_rune to 2,
            )
    }
}
