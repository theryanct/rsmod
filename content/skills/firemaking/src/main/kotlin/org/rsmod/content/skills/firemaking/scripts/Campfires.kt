package org.rsmod.content.skills.firemaking.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.firemakingLvl
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.content.skills.firemaking.configs.firemaking_seqs
import org.rsmod.game.MapClock
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Forester's campfires (OSRS Wiki "Forester's Campfire"), single-player:
 * - Use logs on a fire (with a tinderbox carried) to make it a forester's campfire; none may be
 *   within [MIN_SPACING] tiles of another.
 * - "Tend-to" / use logs: pick the log and amount (skillmulti); one log every [TEND_TICKS] ticks
 *   for the normal Firemaking xp, each adding the Wiki burn time (capped at [MAX_BURN] ticks).
 * - "Check" tells roughly how long it has left (the Wiki's five messages); "Rest" is cosmetic.
 * The Forestry group-event xp bonus is not part of campfires on the Wiki, so there is none here.
 */
class Campfires
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
    private val mapClock: MapClock,
) : PluginScript() {
    /** Campfire tile -> map clock when it burns out. */
    private val campfires = HashMap<CoordGrid, Int>()

    override fun ScriptContext.startup() {
        for (log in CampfireData.logs) {
            val type = CampfireObjs.byName.getValue(log.obj)
            onOpLocU(CampfireLocs.fire, type) { startCampfire(it.loc, log) }
            onOpLocU(CampfireLocs.campfire, type) { tend(it.loc, log) }
        }
        if (!isLocOpBound(CampfireLocs.campfire, 1)) onOpLoc1(CampfireLocs.campfire) { chooseAndTend(it.loc) }
        if (!isLocOpBound(CampfireLocs.campfire, 2)) onOpLoc2(CampfireLocs.campfire) { check(it.loc) }
        if (!isLocOpBound(CampfireLocs.campfire, 3)) onOpLoc3(CampfireLocs.campfire) { mes("You rest by the fire.") }
        onPlayerQueueWithArgs<TendJob>(CampfireQueues.tend) { tendOne(it.args) }
    }

    private fun remaining(coords: CoordGrid): Int = ((campfires[coords] ?: 0) - mapClock.cycle).coerceAtLeast(0)

    private suspend fun ProtectedAccess.startCampfire(loc: BoundLocInfo, log: CampfireData.Log) {
        if (CampfireObjs.tinderbox !in inv) {
            mes("You need a tinderbox to start a forester's campfire.")
            return
        }
        if (player.firemakingLvl < log.level) {
            mes("You need a Firemaking level of ${log.level} to burn these logs.")
            return
        }
        campfires.entries.removeIf { it.value <= mapClock.cycle }
        val near = campfires.keys.any { it.level == loc.coords.level && it.chebyshevDistance(loc.coords) < MIN_SPACING }
        if (near) {
            mes("There's a Forester's Campfire nearby, help tend to that one or move further away.")
            return
        }
        val type = CampfireObjs.byName.getValue(log.obj)
        invDel(inv, type, 1)
        anim(firemaking_seqs.human_createfire)
        delay(2)
        val current = locRepo.findExact(loc.coords, CampfireLocs.fire) ?: return
        campfires[loc.coords] = mapClock.cycle + log.startTicks
        locRepo.change(current, CampfireLocs.campfire, log.startTicks)
        statAdvance(stats.firemaking, log.xp * xpMods.get(player, stats.firemaking))
        mes("You add the logs and the fire becomes a forester's campfire.")
    }

    private suspend fun ProtectedAccess.chooseAndTend(loc: BoundLocInfo) {
        val carried = CampfireData.logs.filter { invTotal(inv, CampfireObjs.byName.getValue(it.obj)) > 0 }
        if (carried.isEmpty()) {
            mes("You don't have any logs to add to the fire.")
            return
        }
        val products =
            carried.map {
                val type = CampfireObjs.byName.getValue(it.obj)
                SkillMultiProduct(type, objTypes[type].name, available = player.firemakingLvl >= it.level)
            }
        val most = carried.maxOf { invTotal(inv, CampfireObjs.byName.getValue(it.obj)) }
        val choice =
            skillMulti(SkillMultiVerb.MAKE, "Which logs would you like to add?", products, most.coerceIn(1, 28))
        tendOne(TendJob(loc.coords, carried[choice.index], choice.quantity))
    }

    private suspend fun ProtectedAccess.tend(loc: BoundLocInfo, log: CampfireData.Log) {
        val count = invTotal(inv, CampfireObjs.byName.getValue(log.obj))
        val choice =
            skillMulti(
                SkillMultiVerb.MAKE,
                "How many logs would you like to add?",
                listOf(SkillMultiProduct(CampfireObjs.byName.getValue(log.obj), objTypes[CampfireObjs.byName.getValue(log.obj)].name, true)),
                count.coerceIn(1, 28),
            )
        tendOne(TendJob(loc.coords, log, choice.quantity))
    }

    private fun ProtectedAccess.tendOne(job: TendJob) {
        val log = job.log
        val type = CampfireObjs.byName.getValue(log.obj)
        if (player.firemakingLvl < log.level) {
            mes("You need a Firemaking level of ${log.level} to burn these logs.")
            return
        }
        if (remaining(job.coords) == 0) {
            mes("The fire has gone out.")
            return
        }
        val current = locRepo.findExact(job.coords, CampfireLocs.campfire) ?: return
        if (type !in inv) return
        invDel(inv, type, 1)
        anim(firemaking_seqs.human_createfire)
        faceSquare(job.coords)
        val burn = minOf(MAX_BURN, remaining(job.coords) + log.addTicks)
        campfires[job.coords] = mapClock.cycle + burn
        locRepo.change(current, CampfireLocs.campfire, burn)
        statAdvance(stats.firemaking, log.xp * xpMods.get(player, stats.firemaking))
        if (job.remaining > 1 && type in inv) {
            weakQueue(CampfireQueues.tend, TEND_TICKS, job.copy(remaining = job.remaining - 1))
        }
    }

    private fun ProtectedAccess.check(loc: BoundLocInfo) {
        val seconds = remaining(loc.coords) * 0.6
        mes(
            when {
                seconds < 35 -> "The embers glow softly."
                seconds < 71 -> "The flames flicker gently."
                seconds < 107 -> "The fire burns steadily."
                seconds < 143 -> "The fire burns brightly."
                else -> "The roaring fire crackles invitingly."
            }
        )
    }

    private data class TendJob(val coords: CoordGrid, val log: CampfireData.Log, val remaining: Int)

    private companion object {
        const val MIN_SPACING = 5
        const val MAX_BURN = 300
        /** ~975 logs an hour when tending straight away (Wiki). */
        const val TEND_TICKS = 6
    }
}

internal object CampfireData {
    /** Wiki burn table: level, xp, burn ticks when it becomes a campfire, ticks added per log. */
    class Log(val obj: String, val level: Int, val xp: Double, val startTicks: Int, val addTicks: Int)

    val logs: List<Log> =
        listOf(
            Log("logs", 1, 40.0, 102, 3),
            Log("achey_tree_logs", 1, 40.0, 102, 3),
            Log("oak_logs", 15, 60.0, 109, 10),
            Log("willow_logs", 30, 90.0, 116, 17),
            Log("teak_logs", 35, 105.0, 118, 19),
            Log("arctic_pine_log", 42, 125.0, 121, 22),
            Log("maple_logs", 45, 135.0, 123, 24),
            Log("mahogany_logs", 50, 157.5, 125, 26),
            Log("yew_logs", 60, 202.5, 130, 31),
            Log("magic_logs", 75, 303.8, 137, 38),
            Log("redwood_logs", 90, 350.0, 144, 45),
        )
}

internal object CampfireObjs : ObjReferences() {
    val tinderbox = find("tinderbox")
    val byName: Map<String, ObjType> = CampfireData.logs.associate { it.obj to find(it.obj) }
}

internal object CampfireLocs : LocReferences() {
    val fire = find("fire")
    val campfire = find("forestry_fire")
}

internal object CampfireQueues : QueueReferences() {
    /** Custom queue (65506, skilling range). */
    val tend = find("firemaking_campfire_tend")
}
