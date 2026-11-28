package org.rsmod.api.game.process.npc

import jakarta.inject.Inject
import org.rsmod.api.game.process.npc.hunt.NpcHuntProcessor
import org.rsmod.api.game.process.npc.mode.NpcModeProcessor
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.repo.NpcRevealProcessor
import org.rsmod.api.utils.logging.GameExceptionHandler
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.util.EntityFaceTarget

public class NpcMainProcess
@Inject
constructor(
    private val npcList: NpcList,
    private val playerList: PlayerList,
    private val registry: NpcRegistry,
    private val reveal: NpcRevealProcessor,
    private val hunt: NpcHuntProcessor,
    private val regen: NpcRegenProcessor,
    private val aiTimers: AiTimerProcessor,
    private val timers: NpcTimerProcessor,
    private val aiQueues: AiQueueProcessor,
    private val queues: NpcQueueProcessor,
    private val modes: NpcModeProcessor,
    private val interactions: NpcInteractionProcessor,
    private val mapClock: MapClock,
    private val exceptionHandler: GameExceptionHandler,
) {
    /** Packed (mapsquare x, z) keys within one map square of any player this tick. */
    private val awakeSquares = HashSet<Int>()

    public fun process() {
        val sleepEnabled = REGION_SLEEP_ENABLED
        if (sleepEnabled) markAwakeSquares()
        for (npc in npcList) {
            npc.processedMapClock = mapClock.cycle
            npc.previousCoords = npc.coords
            npc.tryOrDespawn {
                if (sleepEnabled && isAsleep()) {
                    // Asleep: only respawn/reveal timers keep counting.
                    revealProcess()
                    return@tryOrDespawn
                }
                resumePausedProcess()
                revealProcess()
                processHunt()
                processRegen()
                processAiTimer()
                processAiQueues()
                processQueues()
                processTimers()
                processModes()
                processInteractions()
            }
        }
    }

    /**
     * Region sleep: npcs far from every player skip AI/movement/timers/interactions (their state
     * is kept). Map squares within 1 of a player's square are awake (>= 64 tiles of margin, beyond
     * the client's view), so login/teleport/zone entry wakes the area before it can be seen.
     * Npcs interacting with or facing an entity (e.g. chasing a player), or mid-script (delayed),
     * always stay awake. Off switch: -Drsmod.npc.regionSleep=false (e.g. JAVA_OPTS for the
     * installDist start script).
     */
    private fun markAwakeSquares() {
        awakeSquares.clear()
        for (player in playerList) {
            val sx = player.coords.x shr 6
            val sz = player.coords.z shr 6
            for (dx in -1..1) for (dz in -1..1) awakeSquares.add(squareKey(sx + dx, sz + dz))
        }
    }

    private fun Npc.isAsleep(): Boolean {
        if (interaction != null || faceEntity != EntityFaceTarget.NULL || isDelayed) return false
        return squareKey(coords.x shr 6, coords.z shr 6) !in awakeSquares
    }

    private fun squareKey(sx: Int, sz: Int): Int = (sx shl 16) or (sz and 0xFFFF)

    private fun Npc.resumePausedProcess() {
        if (canProcess) {
            advanceActiveCoroutine()
        }
    }

    private fun Npc.revealProcess() {
        reveal.process(this)
    }

    private fun Npc.processHunt() {
        if (canProcess) {
            hunt.process(this)
        }
    }

    private fun Npc.processRegen() {
        if (canProcess) {
            regen.process(this)
        }
    }

    private fun Npc.processAiTimer() {
        if (canProcess) {
            aiTimers.process(this)
        }
    }

    private fun Npc.processAiQueues() {
        if (canProcess) {
            aiQueues.process(this)
        }
    }

    private fun Npc.processQueues() {
        if (canProcess) {
            queues.process(this)
        }
    }

    private fun Npc.processTimers() {
        if (canProcess) {
            timers.process(this)
        }
    }

    private fun Npc.processModes() {
        if (canProcess) {
            modes.process(this)
        }
    }

    private fun Npc.processInteractions() {
        if (canProcess) {
            interactions.process(this)
        }
    }

    private inline fun Npc.tryOrDespawn(block: Npc.() -> Unit) =
        try {
            block(this)
        } catch (e: Exception) {
            registry.del(this)
            exceptionHandler.handle(e) { "Error processing main cycle for npc: $this." }
        } catch (e: NotImplementedError) {
            registry.del(this)
            exceptionHandler.handle(e) { "Error processing main cycle for npc: $this." }
        }
}

/** Region sleep toggle (default on); see [NpcMainProcess]. */
private val REGION_SLEEP_ENABLED: Boolean =
    System.getProperty("rsmod.npc.regionSleep", "true").toBoolean()
