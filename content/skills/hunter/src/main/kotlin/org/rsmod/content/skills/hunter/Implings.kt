package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Implings (OSRS Wiki "Impling", "<Name> impling jar"; data in `implings.tsv` from
 * tools/skills/implings.py):
 * - "Catch" with a butterfly / magic butterfly net and an impling jar, or barehanded at the
 *   level + 10. Puro-Puro implings give the Puro-Puro xp, Gielinor ones the (higher) world xp.
 * - Jar "Loot": the Wiki loot (always lines + one weighted roll); the jar comes back 9/10.
 * - Puro-Puro: entered from the Zanaris crop circle, left by the central portal; its maze
 *   implings are spawned on the first login and respawn after being caught.
 * - Gielinor: now and then a low-tier impling appears near a player outside Puro-Puro and
 *   wanders off after a while (an approximation of the Wiki's roaming spawns).
 * Not included: crystal implings, lucky impling loot (clue tables), impling tracker, Elnock.
 */
class Implings
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val collision: CollisionFlagMap,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private var spawned = false

    override fun ScriptContext.startup() {
        for (imp in ImplingData.imps) {
            for ((type, puro) in listOf(ImplingNpcs.world[imp.type - 1] to false, ImplingNpcs.maze[imp.type - 1] to true)) {
                if (!isNpcOpBound(type, 1)) onOpNpc1(type) { catch(it.npc, imp, puro) }
            }
            val jar = ImplingObjs.jars[imp.type - 1]
            val op = objTypes[jar].iop.indexOfFirst { it.equals("Loot", ignoreCase = true) } + 1
            if (op > 0 && !isHeldOpBound(jar, op)) {
                when (op) {
                    1 -> onOpHeld1(jar) { loot(imp, jar) }
                    2 -> onOpHeld2(jar) { loot(imp, jar) }
                    3 -> onOpHeld3(jar) { loot(imp, jar) }
                }
            }
        }
        for (circle in ImplingLocs.crop_circles) {
            if (!isLocOpBound(circle, 1)) onOpLoc1(circle) { telejump(walkable(PURO_ARRIVAL)) }
        }
        if (!isLocOpBound(ImplingLocs.puro_portal, 1)) onOpLoc1(ImplingLocs.puro_portal) { telejump(walkable(ZANARIS)) }
        onPlayerLogin {
            spawnPuroPuro()
            player.softTimer(ImplingTimers.world_spawn, WORLD_SPAWN_TICKS)
        }
        onPlayerSoftTimer(ImplingTimers.world_spawn) { maybeWorldSpawn(player) }
    }

    /* Catching */

    private suspend fun ProtectedAccess.catch(npc: Npc, imp: ImplingData.Imp, puro: Boolean) {
        val net = ImplingObjs.nets.any { it in player.worn || it in inv }
        val required = if (net) imp.level else imp.level + BAREHANDED_EXTRA
        if (player.hunterLvl < required) {
            val how = if (net) "" else " barehanded"
            mes("You need a Hunter level of $required to catch this impling$how.")
            return
        }
        if (ImplingObjs.empty_jar !in inv) {
            mes("You need an empty impling jar to hold the impling.")
            return
        }
        anim(HunterSeqs.net_swing)
        delay(2)
        val chance = (CATCH_BASE + (player.hunterLvl - required) * CATCH_PER_LEVEL).coerceAtMost(CATCH_MAX)
        if (random.of(1, 100) > chance) {
            mes("You fail to catch the impling.")
            return
        }
        invDel(inv, ImplingObjs.empty_jar, 1)
        invAdd(inv, ImplingObjs.jars[imp.type - 1])
        val xp = if (puro) imp.puroXp else imp.worldXp
        statAdvance(stats.hunter, xp * xpMods.get(player, stats.hunter))
        mes("You manage to catch the impling and squeeze it into a jar.")
        if (puro) npcRepo.despawn(npc, PURO_RESPAWN) else npcRepo.del(npc, Int.MAX_VALUE)
    }

    private fun ProtectedAccess.loot(imp: ImplingData.Imp, jar: ObjType) {
        if (inv.freeSpace() < 2) {
            mes("You need two free inventory spaces to loot the jar.")
            return
        }
        invDel(inv, jar, 1)
        for (entry in imp.loot) if (entry.always) give(entry)
        val weighted = imp.loot.filter { !it.always }
        if (weighted.isNotEmpty()) {
            val roll = random.of(PPM)
            var cumulative = 0L
            for (entry in weighted) {
                cumulative += entry.numerator.toLong() * PPM / entry.denominator
                if (roll < cumulative) {
                    give(entry)
                    break
                }
            }
        }
        if (random.of(1, 10) <= 9) invAdd(inv, ImplingObjs.empty_jar) else mes("The jar breaks as you open it.")
    }

    private fun ProtectedAccess.give(entry: ImplingData.Loot) {
        val count = if (entry.max > entry.min) random.of(entry.min, entry.max) else entry.min
        invAddGathered(ImplingObjs.byName.getValue(entry.obj), count)
    }

    /* Spawns */

    /** Puro-Puro: a fixed population per type (rarer types fewer), on random free maze tiles. */
    private fun spawnPuroPuro() {
        if (spawned) return
        spawned = true
        for ((index, count) in PURO_COUNTS.withIndex()) {
            val type = npcTypes[ImplingNpcs.maze[index]]
            repeat(count) {
                val tile = randomFreeTile(PURO_SW, PURO_NE) ?: return@repeat
                npcRepo.add(Npc(type, tile), Int.MAX_VALUE)
            }
        }
    }

    /** Gielinor: [WORLD_CHANCE] per check to put a low-tier impling near the player for a while. */
    private fun maybeWorldSpawn(player: Player) {
        player.softTimer(ImplingTimers.world_spawn, WORLD_SPAWN_TICKS)
        if (inPuroPuro(player.coords) || kotlin.random.Random.nextInt(WORLD_CHANCE) != 0) return
        val total = WORLD_WEIGHTS.sum()
        var roll = kotlin.random.Random.nextInt(total)
        val index = WORLD_WEIGHTS.indexOfFirst { w -> (roll < w).also { roll -= w } }.coerceAtLeast(0)
        val c = player.coords
        val tile =
            randomFreeTile(c.translate(-WORLD_RADIUS, -WORLD_RADIUS), c.translate(WORLD_RADIUS, WORLD_RADIUS))
                ?: return
        npcRepo.add(Npc(npcTypes[ImplingNpcs.world[index]], tile), WORLD_LIFETIME)
    }

    private fun inPuroPuro(c: CoordGrid): Boolean =
        c.level == 0 && c.x in PURO_SW.x..PURO_NE.x && c.z in PURO_SW.z..PURO_NE.z

    private fun randomFreeTile(sw: CoordGrid, ne: CoordGrid): CoordGrid? {
        repeat(40) {
            val x = kotlin.random.Random.nextInt(sw.x, ne.x + 1)
            val z = kotlin.random.Random.nextInt(sw.z, ne.z + 1)
            if ((collision[x, z, sw.level] and BLOCKED) == 0) return CoordGrid(x, z, sw.level)
        }
        return null
    }

    private fun walkable(pin: CoordGrid): CoordGrid {
        for (r in 1..4) {
            for (dz in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dz)) != r) continue
                val t = pin.translate(dx, dz)
                if ((collision[t.x, t.z, t.level] and BLOCKED) == 0) return t
            }
        }
        return pin
    }

    private companion object {
        const val BAREHANDED_EXTRA = 10
        const val CATCH_BASE = 50
        const val CATCH_PER_LEVEL = 3
        const val CATCH_MAX = 95
        const val PURO_RESPAWN = 100
        const val PPM = 1_000_000
        const val WORLD_SPAWN_TICKS = 300
        const val WORLD_CHANCE = 6
        const val WORLD_RADIUS = 8
        const val WORLD_LIFETIME = 500
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
        /** Puro-Puro population, baby..lucky (approximation of the Wiki's spawn rarity). */
        val PURO_COUNTS = listOf(6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1)
        /** Gielinor spawn weights, baby..eclectic. */
        val WORLD_WEIGHTS = listOf(40, 25, 15, 10, 6, 4)
        val PURO_SW = CoordGrid(2563, 4291, 0)
        val PURO_NE = CoordGrid(2620, 4348, 0)
        /** Beside the central portal (cache map: 2591-2592, 4319-4320). */
        val PURO_ARRIVAL = CoordGrid(2591, 4317, 0)
        /** Beside the Zanaris crop circle (cache map: 2427, 4446). */
        val ZANARIS = CoordGrid(2427, 4444, 0)
    }
}

internal object ImplingData {
    class Loot(val obj: String, val min: Int, val max: Int, val numerator: Int, val denominator: Int, val always: Boolean)

    class Imp(val type: Int, val name: String, val level: Int, val puroXp: Double, val worldXp: Double, val loot: List<Loot>)

    val imps: List<Imp> by lazy {
        val input =
            ImplingData::class.java.getResourceAsStream("implings.tsv") ?: error("Hunter resource not found: implings.tsv")
        val out = ArrayList<Imp>()
        var head: List<String>? = null
        var loot = ArrayList<Loot>()
        fun flush() {
            val h = head ?: return
            out += Imp(h[1].toInt(), h[2], h[3].toInt(), h[4].toDouble(), h[5].toDouble(), loot)
            head = null
            loot = ArrayList()
        }
        for (raw in input.bufferedReader().use { it.readLines() }) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val p = raw.trimEnd().split('\t')
            when (p[0]) {
                "imp" -> {
                    flush()
                    head = p
                }
                "loot" -> loot += Loot(p[1], p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6] == "1")
            }
        }
        flush()
        out
    }
}

internal object ImplingNpcs : NpcReferences() {
    val world = (1..11).map { find("ii_impling_type_$it") }
    val maze = (1..11).map { find("ii_impling_type_${it}_maze") }
}

internal object ImplingObjs : ObjReferences() {
    val empty_jar = find("ii_impling_jar")
    val jars = (1..11).map { find("ii_captured_impling_$it") }
    val nets = listOf(find("hunting_butterfly_net"), find("ii_magic_butterfly_net"))
    val byName: Map<String, ObjType> =
        ImplingData.imps.flatMap { i -> i.loot.map { it.obj } }.distinct().associateWith { find(it) }
}

internal object ImplingLocs : LocReferences() {
    val crop_circles = listOf(find("ii_magic_wheat_m"), find("ii_magic_wheat_m_zanaris"))
    val puro_portal = find("ii_rocks_floor_hard_02")
}

internal object ImplingTimers : TimerReferences() {
    /** Custom timer (65502, skilling range). */
    val world_spawn = find("hunter_impling_spawn")
}
