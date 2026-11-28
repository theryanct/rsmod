package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.hunter.HunterData.Creature
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Hunter PR1 (OSRS Wiki levels, xp, catch charts and loot; data in `hunter.tsv`):
 * - **Bird snares**: "Lay" the snare on your tile (1 trap, +1 per 20 Hunter levels, max 5). A few
 *   ticks later the trap rolls against the best bird you can catch within 8 tiles using the wiki
 *   low/high chart: success shows the caught-bird trap, failure collapses it. "Check" a caught
 *   trap for xp + loot, "Dismantle" any of your traps to get the snare back. Unattended traps
 *   disappear after [TRAP_LIFE] ticks and drop the snare.
 * - **Box traps** (PR2): the same flow with a box trap for ferrets and grey/red/black chinchompas
 *   (Wiki levels, xp and catch charts; ferret has no Wiki chart - see hunter.tsv). Traps of both
 *   kinds share the per-level trap limit.
 * - **Butterflies**: "Catch" with a butterfly net wielded and an empty butterfly jar.
 */
class Hunter
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private enum class State {
        Laid,
        Caught,
        Collapsed,
    }

    /** A trap item, the loc it lays, and the loc a failed catch leaves. */
    private enum class TrapKind(val kind: HunterData.Kind) {
        Snare(HunterData.Kind.Snare),
        Box(HunterData.Kind.Box);

        fun item(): ObjType = if (this == Snare) HunterObjs.bird_snare else HunterObjs.box_trap

        fun laid(): LocType = if (this == Snare) HunterLocs.snare_laid else HunterLocs.box_laid

        fun failed(): LocType = if (this == Snare) HunterLocs.snare_broken else HunterLocs.box_failed
    }

    private class Trap(
        val owner: Player,
        val kind: TrapKind,
        var state: State,
        var creature: Creature?,
        var tries: Int,
    )

    private val traps = HashMap<CoordGrid, Trap>()

    /** Trap kind -> (npc id -> creature) for the creatures each trap kind catches. */
    private val trappedByNpcId by lazy {
        TrapKind.entries.associateWith { trapKind ->
            HunterData.creatures
                .filter { it.kind == trapKind.kind }
                .flatMap { c -> c.npcs.mapNotNull { HunterNpcs.byName[it]?.id }.map { it to c } }
                .toMap()
        }
    }

    override fun ScriptContext.startup() {
        for (trapKind in TrapKind.entries) {
            val item = trapKind.item()
            when (objTypes[item].iop.indexOfFirst { it.equals("Lay", ignoreCase = true) } + 1) {
                1 -> onOpHeld1(item) { layTrap(trapKind) }
                2 -> onOpHeld2(item) { layTrap(trapKind) }
                3 -> onOpHeld3(item) { layTrap(trapKind) }
                4 -> onOpHeld4(item) { layTrap(trapKind) }
                5 -> onOpHeld5(item) { layTrap(trapKind) }
            }
        }
        val trapLocs =
            (TrapKind.entries.flatMap { listOf(it.laid(), it.failed()) } + HunterLocs.byName.values)
                .distinctBy { it.id }
        for (loc in trapLocs) {
            registerTrapOps(loc)
        }
        for (creature in HunterData.creatures.filter { !it.trapped }) {
            for (name in creature.npcs) {
                val npc = HunterNpcs.byName.getValue(name)
                val op = npcTypes[npc].op.indexOfFirst { it.equals("Catch", ignoreCase = true) } + 1
                when (op) {
                    1 -> onOpNpc1(npc) { catchButterfly(it.npc, creature) }
                    2 -> onOpNpc2(npc) { catchButterfly(it.npc, creature) }
                    3 -> onOpNpc3(npc) { catchButterfly(it.npc, creature) }
                    4 -> onOpNpc4(npc) { catchButterfly(it.npc, creature) }
                    5 -> onOpNpc5(npc) { catchButterfly(it.npc, creature) }
                }
            }
        }
        onPlayerQueueWithArgs<CoordGrid>(HunterQueues.snare_check) { resolveTrap(it.args) }
    }

    /** Every trap loc's "Check" / "Dismantle" / "Take" op picks the trap up. */
    private fun ScriptContext.registerTrapOps(loc: LocType) {
        val ops = locTypes[loc].op
        for ((index, label) in ops.withIndex()) {
            val handled = TRAP_OPS.any { it.equals(label, ignoreCase = true) }
            if (!handled) continue
            when (index + 1) {
                1 -> onOpLoc1(loc) { pickUpTrap(it.loc) }
                2 -> onOpLoc2(loc) { pickUpTrap(it.loc) }
                3 -> onOpLoc3(loc) { pickUpTrap(it.loc) }
                4 -> onOpLoc4(loc) { pickUpTrap(it.loc) }
                5 -> onOpLoc5(loc) { pickUpTrap(it.loc) }
            }
        }
    }

    private suspend fun ProtectedAccess.layTrap(trapKind: TrapKind) {
        val limit = min(MAX_TRAPS, 1 + player.hunterLvl / 20)
        if (traps.values.count { it.owner === player } >= limit) {
            mes("You can't lay more than $limit traps at your Hunter level.")
            return
        }
        val coords = player.coords
        // Only solid objects block a trap; floor decoration (grass etc.) does not.
        val occupied = locRepo.findExact(coords, LocShape.CentrepieceStraight) != null
        if (traps.containsKey(coords) || occupied) {
            mes("You can't lay a trap here.")
            return
        }
        anim(HunterSeqs.lay_trap)
        delay(2)
        if (invTotal(inv, trapKind.item()) <= 0) {
            return
        }
        invDel(inv, trapKind.item(), 1)
        addTrapLoc(coords, trapKind.laid())
        traps[coords] = Trap(player, trapKind, State.Laid, creature = null, tries = 0)
        softQueue(HunterQueues.snare_check, random.of(8, 16), coords)
    }

    private fun ProtectedAccess.resolveTrap(coords: CoordGrid) {
        val trap = traps[coords] ?: return
        if (trap.owner !== player || trap.state != State.Laid) {
            return
        }
        if (locRepo.findExact(coords, trap.kind.laid()) == null) {
            // The trap timed out and despawned: leave the trap item on the ground.
            traps.remove(coords)
            objRepo.add(trap.kind.item(), coords, SNARE_DROP_DURATION, player)
            return
        }
        val creature = nearestCreature(coords, trap.kind)
        if (creature == null) {
            trap.tries++
            if (trap.tries < MAX_TRIES) {
                softQueue(HunterQueues.snare_check, random.of(10, 20), coords)
            }
            return
        }
        if (statRandom(stats.hunter, creature.low, creature.high, 0)) {
            addTrapLoc(coords, HunterLocs.byName.getValue(creature.fullLoc ?: return))
            trap.state = State.Caught
            trap.creature = creature
        } else {
            addTrapLoc(coords, trap.kind.failed())
            trap.state = State.Collapsed
        }
    }

    /** Best creature this trap kind catches, spawned within 8 tiles of [coords]. */
    private fun ProtectedAccess.nearestCreature(coords: CoordGrid, kind: TrapKind): Creature? =
        npcRepo
            .findAll(ZoneKey.from(coords), 1)
            .filter { abs(it.coords.x - coords.x) <= BIRD_RANGE && abs(it.coords.z - coords.z) <= BIRD_RANGE }
            .mapNotNull { trappedByNpcId.getValue(kind)[it.id] }
            .filter { it.level <= player.hunterLvl }
            .maxByOrNull { it.level }

    private suspend fun ProtectedAccess.pickUpTrap(loc: BoundLocInfo) {
        val trap = traps[loc.coords]
        if (trap != null && trap.owner !== player) {
            mes("This isn't your trap.")
            return
        }
        anim(HunterSeqs.pick_up)
        delay(1)
        locRepo.del(loc, Int.MAX_VALUE)
        traps.remove(loc.coords)
        val creature = trap?.creature
        if (trap?.state == State.Caught && creature != null) {
            for (entry in creature.loot) {
                val count = if (entry.max > entry.min) random.of(entry.min, entry.max) else entry.min
                invAddOrDrop(objRepo, HunterObjs.byName.getValue(entry.obj), count)
            }
            statAdvance(stats.hunter, creature.xp * xpMods.get(player, stats.hunter))
            mes("You've caught a ${creature.title.lowercase()}.")
        }
        invAddOrDrop(objRepo, (trap?.kind ?: trapKindOf(loc)).item())
    }

    /** Untracked trap locs (e.g. after a restart) give back the item their loc belongs to. */
    private fun trapKindOf(loc: BoundLocInfo): TrapKind {
        val boxLocs = setOf(HunterLocs.box_laid.id, HunterLocs.box_failed.id) +
            HunterData.creatures.filter { it.kind == HunterData.Kind.Box }
                .mapNotNull { c -> c.fullLoc?.let { HunterLocs.byName[it]?.id } }
        return if (locTypes[loc].id in boxLocs) TrapKind.Box else TrapKind.Snare
    }

    private suspend fun ProtectedAccess.catchButterfly(npc: Npc, creature: Creature) {
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to catch a ${creature.title.lowercase()}.")
            return
        }
        if (invTotal(worn, HunterObjs.butterfly_net) <= 0) {
            mes("You need to be wielding a butterfly net to catch butterflies.")
            return
        }
        if (invTotal(inv, HunterObjs.butterfly_jar) <= 0) {
            mes("You need an empty butterfly jar to catch butterflies.")
            return
        }
        anim(HunterSeqs.net_swing)
        delay(2)
        if (!statRandom(stats.hunter, creature.low, creature.high, 0)) {
            mes("You fail to catch the butterfly.")
            return
        }
        invDel(inv, HunterObjs.butterfly_jar, 1)
        for (entry in creature.loot) {
            invAddGathered(HunterObjs.byName.getValue(entry.obj), entry.min)
        }
        statAdvance(stats.hunter, creature.xp * xpMods.get(player, stats.hunter))
        npcRepo.despawn(npc, BUTTERFLY_RESPAWN)
    }

    private fun addTrapLoc(coords: CoordGrid, type: LocType) {
        locRepo.add(coords, type, TRAP_LIFE, LocAngle.West, LocShape.CentrepieceStraight)
    }

    private companion object {
        val TRAP_OPS = listOf("Check", "Dismantle", "Take", "Reset")
        const val MAX_TRAPS = 5
        const val MAX_TRIES = 10
        const val BIRD_RANGE = 8
        /** About three minutes, after which an unattended trap despawns. */
        const val TRAP_LIFE = 300
        const val SNARE_DROP_DURATION = 200
        const val BUTTERFLY_RESPAWN = 50
    }
}
