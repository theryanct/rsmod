package org.rsmod.api.game.process.player

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.forceDisconnect
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.ui.closeSubs
import org.rsmod.api.player.ui.ifClose
import org.rsmod.api.utils.logging.GameExceptionHandler
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.ShuffledPlayerList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.ui.Component

public class PlayerMainProcess
@Inject
constructor(
    private val mapClock: MapClock,
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val players: ShuffledPlayerList,
    private val queues: PlayerQueueProcessor,
    private val timers: PlayerTimerProcessor,
    private val areas: PlayerAreaProcessor,
    private val engineQueues: PlayerEngineQueueProcessor,
    private val interact: PlayerInteractionProcessor,
    private val exceptionHandler: GameExceptionHandler,
) {
    public fun process() {
        processAll()
    }

    private fun processAll() {
        for (player in players) {
            // Skip players who are no longer considered online. They remain in the player list
            // temporarily until their account data has been saved.
            if (!player.canProcess) {
                continue
            }
            player.processedMapClock = mapClock.cycle
            player.tryOrRecover {
                resumePausedProcess()
                refreshFaceEntity()
                processIfCloseQueue()
                processIfCloseModal()
                processQueues()
                processTimers()
                processAreas()
                processEngineQueues()
                processInteractions()
                processIfCloseDisconnect()
            }
        }
    }

    private fun Player.resumePausedProcess() {
        if (isNotDelayed) {
            advanceActiveCoroutine()
        }
    }

    private fun Player.refreshFaceEntity() {
        val interaction = interaction
        if (interaction == null) {
            resetFaceEntity()
        }
    }

    private fun Player.processIfCloseQueue() {
        for (target in ui.closeQueue.intIterator()) {
            val component = Component(target)
            closeSubs(component, eventBus)
        }
        ui.closeQueue.clear()
    }

    private fun Player.processIfCloseModal() {
        if (ui.closeModal) {
            ui.closeModal = false
            ifClose(eventBus)
        }
    }

    private fun Player.processQueues() {
        queues.process(this)
    }

    private fun Player.processTimers() {
        timers.process(this)
    }

    private fun Player.processAreas() {
        areas.process(this)
    }

    private fun Player.processEngineQueues() {
        engineQueues.process(this)
    }

    // Interactions implicitly handle movement processing as well.
    private fun Player.processInteractions() {
        // Do not process interactions while "fake-logged."
        if (pendingLogout) {
            clearInteraction()
            return
        }
        interact.process(this)
    }

    private fun Player.processIfCloseDisconnect() {
        if (isPendingLogout()) {
            ifClose(eventBus)
        }
    }

    /** Per-player recent script-error cycles, for the loop guard. */
    private val recentErrors = HashMap<Int, ArrayDeque<Int>>()

    /**
     * A script error must never kick the player (Ryan rule): log it as SCRIPT_ERROR (WARN+ goes to
     * gameplay-problems.log), cancel the failing coroutine/queues/interaction, close modals and tell
     * the player. Only a player erroring [LOOP_LIMIT] times within [LOOP_WINDOW_CYCLES] is
     * disconnected (a loop guard against an error repeating every tick).
     */
    private inline fun Player.tryOrRecover(block: Player.() -> Unit) =
        try {
            block(this)
        } catch (e: Exception) {
            recover(e)
        } catch (e: NotImplementedError) {
            recover(e)
        }

    private fun Player.recover(e: Throwable) {
        val now = mapClock.cycle
        val errors = recentErrors.getOrPut(slotId) { ArrayDeque() }
        errors.addLast(now)
        while (errors.isNotEmpty() && now - errors.first() > LOOP_WINDOW_CYCLES) errors.removeFirst()
        val frames =
            e.stackTrace
                .filter { it.className.startsWith("org.rsmod.content") }
                .take(4)
                .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        logger.error(e) {
            "SCRIPT_ERROR player=$username coords=$coords interaction=$interaction " +
                "count=${errors.size} ex=${e::class.simpleName}: ${e.message} frames=[$frames]"
        }
        if (errors.size >= LOOP_LIMIT) {
            errors.clear()
            forceDisconnect()
            exceptionHandler.handle(e) { "Repeated script errors for player: $this; disconnected." }
            return
        }
        runCatching { cancelActiveCoroutine() }
        runCatching { queueList.clear() }
        runCatching { weakQueueList.clear() }
        runCatching { clearInteraction() }
        runCatching { ifClose(eventBus) }
        runCatching { mes("Something went wrong - this has been logged.") }
    }

    private companion object {
        private val logger = InlineLogger()
        private const val LOOP_LIMIT = 5
        private const val LOOP_WINDOW_CYCLES = 10
    }
}
