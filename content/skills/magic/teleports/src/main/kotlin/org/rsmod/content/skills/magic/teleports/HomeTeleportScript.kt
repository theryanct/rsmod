package org.rsmod.content.skills.magic.teleports

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.isInCombat
import org.rsmod.api.player.ui.ifClose
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.content.skills.magic.teleports.configs.home_tele_components
import org.rsmod.content.skills.magic.teleports.configs.home_tele_queues
import org.rsmod.content.skills.magic.teleports.configs.home_tele_seqs
import org.rsmod.content.skills.magic.teleports.configs.home_tele_spots
import org.rsmod.content.skills.magic.teleports.configs.home_tele_varps
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.seq.SeqType
import org.rsmod.game.type.spot.SpotanimType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Lumbridge Home Teleport (standard spellbook): a 24-tick, five-stage cast that walking or combat
 * interrupts, with a 30-minute cooldown shared through `aide_tele_timer`.
 *
 * Each stage runs from a weak queue, so any click that clears pending actions (walking, opening
 * something else) cancels the rest of the cast, as in OSRS.
 */
class HomeTeleportScript
@Inject
constructor(private val eventBus: EventBus, private val protectedAccess: ProtectedAccessLauncher) :
    PluginScript() {
    private var Player.lastCastMinute by intVarp(home_tele_varps.last_cast)

    override fun ScriptContext.startup() {
        onIfOverlayButton(home_tele_components.standard) { player.selectHomeTeleport() }
        onPlayerQueueWithArgs<Int>(home_tele_queues.stage) { runStage(it.args) }
    }

    private fun Player.selectHomeTeleport() {
        if (isInCombat()) {
            mes("You can't use this teleport while you are in combat.")
            return
        }
        val minutesLeft = cooldownMinutesLeft()
        if (minutesLeft > 0) {
            val unit = if (minutesLeft == 1) "minute" else "minutes"
            mes("You need to wait another $minutesLeft $unit to cast this spell.")
            return
        }
        ifClose(eventBus)
        val started = protectedAccess.launch(this) { runStage(0) }
        if (!started) {
            mes("Please finish what you're doing first.")
        }
    }

    private fun ProtectedAccess.runStage(index: Int) {
        if (player.isInCombat()) {
            resetAnim()
            spotanim(null)
            return
        }
        val stage = STAGES.getOrNull(index)
        if (stage == null) {
            resetAnim()
            player.lastCastMinute = currentMinute()
            telejump(LUMBRIDGE)
            return
        }
        anim(stage.seq())
        spotanim(stage.spot())
        weakQueue(home_tele_queues.stage, stage.ticks, index + 1)
    }

    private fun Player.cooldownMinutesLeft(): Int {
        val last = lastCastMinute
        if (last <= 0) {
            return 0
        }
        val elapsed = currentMinute() - last
        return (COOLDOWN_MINUTES - elapsed).coerceAtLeast(0)
    }

    private fun currentMinute(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    private class Stage(
        val seq: () -> SeqType,
        val spot: () -> SpotanimType?,
        val ticks: Int,
    )

    private companion object {
        private const val COOLDOWN_MINUTES = 30

        private val LUMBRIDGE = CoordGrid(x = 3221, z = 3218)

        // Durations sum to the wiki's 24-tick cast.
        private val STAGES =
            listOf(
                Stage({ home_tele_seqs.chalk_circle }, { home_tele_spots.chalk_circle }, 7),
                Stage({ home_tele_seqs.sit_down }, { null }, 5),
                Stage({ home_tele_seqs.get_book }, { home_tele_spots.book_get }, 4),
                Stage({ home_tele_seqs.incantation }, { home_tele_spots.book_portal }, 5),
                Stage({ home_tele_seqs.teleporting }, { home_tele_spots.teleport }, 3),
            )
    }
}
