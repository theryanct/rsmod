package org.rsmod.content.travel.minigames

import jakarta.inject.Inject
import org.rsmod.api.player.isInCombat
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.ui.ifOpenSub
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.interf.IfSubType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Grouping tab + Minigame Teleport. The chat-channel side panel's 4th tab (side_channels:tab_3)
 * opens the Grouping interface (76). Its Teleport button opens a server menu of the minigames
 * (stopgap: the real dropdown keeps its pick in client varc 64, and how that reaches the server
 * isn't decoded yet), then casts with the Wiki's 20-minute cooldown (custom varp
 * minigame_tele_last, minutes since the epoch). Destinations are the OSRS Wiki's Minigame
 * Teleport arrival spots (wiki-cache/Minigame_Teleport.md); Rat Pits asks which city.
 *
 * Later polish: the real dropdown selection, the cast's full animation stages and interruption.
 */
class MinigameTeleportScript
@Inject
constructor(private val eventBus: EventBus, private val protectedAccess: ProtectedAccessLauncher) :
    PluginScript() {
    private class Minigame(val name: String, val spots: List<Pair<String, CoordGrid>>)

    private val minigames =
        listOf(
            Minigame("Barbarian Assault", listOf("" to CoordGrid(2531, 3577))),
            Minigame("Blast Furnace", listOf("" to CoordGrid(2930, 10193))),
            Minigame("Burthorpe Games Room", listOf("" to CoordGrid(2899, 3553))),
            Minigame("Castle Wars", listOf("" to CoordGrid(2442, 3093))),
            Minigame("Clan Wars", listOf("" to CoordGrid(3149, 3634))),
            Minigame("Fishing Trawler", listOf("" to CoordGrid(2658, 3157))),
            Minigame("Giants' Foundry", listOf("" to CoordGrid(3361, 3149))),
            Minigame("Guardians of the Rift", listOf("" to CoordGrid(3615, 9477))),
            Minigame("Last Man Standing", listOf("" to CoordGrid(3149, 3634))),
            Minigame("Mage Training Arena", listOf("" to CoordGrid(3363, 3295))),
            Minigame("Mastering Mixology", listOf("" to CoordGrid(1388, 2918))),
            Minigame("Nightmare Zone", listOf("" to CoordGrid(2609, 3114))),
            Minigame("Pest Control", listOf("" to CoordGrid(2658, 2663))),
            Minigame(
                "Rat Pits",
                listOf(
                    "Ardougne" to CoordGrid(2561, 3320),
                    "Varrock" to CoordGrid(3267, 3400),
                    "Keldagrim" to CoordGrid(2914, 10193),
                    "Port Sarim" to CoordGrid(3018, 3232),
                ),
            ),
            Minigame("Shades of Mort'ton", listOf("" to CoordGrid(3506, 3304))),
            Minigame("Sorceress's Garden", listOf("" to CoordGrid(3321, 3138))),
            Minigame("Soul Wars", listOf("" to CoordGrid(2210, 2857))),
            Minigame("Tithe Farm", listOf("" to CoordGrid(1792, 3501))),
            Minigame("Trouble Brewing", listOf("" to CoordGrid(3816, 3024))),
            Minigame("TzHaar Fight Pit", listOf("" to CoordGrid(2399, 5177))),
        )

    private var Player.lastMinute: Int
        get() = vars[MinigameVarps.last]
        set(value) = VarPlayerIntMapSetter.set(this, MinigameVarps.last, value)

    override fun ScriptContext.startup() {
        onIfOverlayButton(MinigameComponents.grouping_tab) { player.openGrouping() }
        onIfOverlayButton(MinigameComponents.teleport) { player.chooseTeleport() }
    }

    private fun Player.openGrouping() {
        ifOpenSub(MinigameInterfaces.grouping, MinigameComponents.contents, IfSubType.Overlay, eventBus)
        ifSetEvents(MinigameComponents.teleport, -1..-1, IfEvent.Op1)
    }

    private fun Player.chooseTeleport() {
        if (isInCombat()) {
            mes("You can't use this teleport while you are in combat.")
            return
        }
        val left = COOLDOWN_MINUTES - (currentMinute() - lastMinute)
        if (lastMinute > 0 && left > 0) {
            val unit = if (left == 1) "minute" else "minutes"
            mes("You need to wait another $left $unit to use the minigame teleport.")
            return
        }
        val started =
            protectedAccess.launch(this, busyText = "You're busy right now.") { pickAndCast() }
        if (!started) {
            mes("Please finish what you're doing first.")
        }
    }

    private suspend fun ProtectedAccess.pickAndCast() {
        val picked =
            menu("Minigame Teleport", hotkeys = false, choices = minigames.map { it.name } + "Cancel")
        val minigame = minigames.getOrNull(picked) ?: return
        val spot =
            if (minigame.spots.size == 1) {
                minigame.spots.first().second
            } else {
                val which =
                    menu(minigame.name, hotkeys = false, choices = minigame.spots.map { it.first })
                minigame.spots.getOrNull(which)?.second ?: return
            }
        anim(MinigameSeqs.incantation)
        delay(CAST_TICKS)
        anim(MinigameSeqs.teleporting)
        spotanim(MinigameSpots.teleport)
        delay(TELEPORT_TICKS)
        player.lastMinute = currentMinute()
        telejump(spot)
        resetAnim()
    }

    private fun currentMinute(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    private companion object {
        const val COOLDOWN_MINUTES = 20
        const val CAST_TICKS = 8
        const val TELEPORT_TICKS = 4
    }
}

internal object MinigameInterfaces : InterfaceReferences() {
    val grouping = find("grouping")
}

internal object MinigameComponents : ComponentReferences() {
    /** The chat-channel side panel's Grouping tab button. */
    val grouping_tab = find("side_channels:tab_3")
    val contents = find("side_channels:contents")
    val teleport = find("grouping:teleport")
}

internal object MinigameSeqs : SeqReferences() {
    val incantation = find("aide_reciting_incantation")
    val teleporting = find("aide_player_teleporting")
}

internal object MinigameSpots : SpotanimReferences() {
    val teleport = find("aide_player_teleport")
}

/** Custom (ui range, 65329): minute (since the epoch) of the last minigame teleport, 0 = never. */
internal object MinigameVarps : VarpReferences() {
    val last = find("minigame_tele_last")
}

internal object MinigameVarpBuilder : VarpBuilder() {
    init {
        build("minigame_tele_last")
    }
}
