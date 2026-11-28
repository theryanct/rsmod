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
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
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
        for (op in 1..4) {
            if (isNpcOpBound(ImplingNpcs.elnock, op)) continue
            when (op) {
                1 -> onOpNpc1(ImplingNpcs.elnock) { elnock(it.npc) }
                3 -> onOpNpc3(ImplingNpcs.elnock) { buyJars(it.npc) }
                4 -> onOpNpc4(ImplingNpcs.elnock) { exchange(it.npc) }
            }
        }
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
        // Wiki (Mod Ash): 12% at level 1 to 98% at 99, +8 points barehanded or with a magic net.
        val level = player.hunterLvl.coerceIn(1, 99)
        val magic = ImplingObjs.nets[1] in player.worn || ImplingObjs.nets[1] in inv
        val bonus = if (!net || magic) CATCH_BONUS else 0
        val chance = 12 + (86 * (level - 1)) / 98 + bonus
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

    /* Elnock Inquisitor (Wiki "Elnock Inquisitor", "Elnock's Exchange") */

    private suspend fun ProtectedAccess.elnock(npc: Npc) {
        if (vars[ImplingVarps.elnock] == 0) {
            if (inv.freeSpace() < 2) {
                startDialogue(npc) { chatNpc(neutral, "Make some room in your pack and I'll give you something to help.") }
                return
            }
            vars[ImplingVarps.elnock] = 1
            invAdd(inv, ImplingObjs.nets[0])
            invAdd(inv, ImplingObjs.empty_jar, STARTER_JARS)
            startDialogue(npc) {
                chatNpc(happy, "Here, take a net and some jars to start you off. Catch me some implings!")
            }
            return
        }
        val pick = menu("Elnock Inquisitor", hotkeys = true, choices = listOf("Buy impling jars", "Exchange impling jars", "Nothing"))
        when (pick) {
            0 -> buyJars(npc)
            1 -> exchange(npc)
        }
    }

    /** Wiki: impling jars for 2,000 coins each (the daily 10-jar limit is not modelled). */
    private suspend fun ProtectedAccess.buyJars(npc: Npc) {
        val amount = countDialog("How many jars? (2,000 coins each)").coerceIn(0, MAX_BUY)
        if (amount <= 0) return
        val cost = amount * JAR_PRICE
        if (invTotal(inv, ImplingObjs.coins) < cost) {
            startDialogue(npc) { chatNpc(neutral, "That'll be ${"%,d".format(cost)} coins, which you don't have.") }
            return
        }
        invDel(inv, ImplingObjs.coins, cost)
        invAdd(inv, ImplingObjs.empty_jar, amount)
    }

    private suspend fun ProtectedAccess.exchange(npc: Npc) {
        val labels = ImplingData.exchanges.map { it.label } + "Nothing"
        val pick = menu("Elnock's Exchange", hotkeys = true, choices = labels)
        val trade = ImplingData.exchanges.getOrNull(pick) ?: return
        if (trade.anyJar) {
            val jar = ImplingObjs.jars.firstOrNull { it in inv }
            if (jar == null) {
                startDialogue(npc) { chatNpc(neutral, "Bring me a jarred impling of any kind for three empty jars.") }
                return
            }
            invDel(inv, jar, 1)
            invAdd(inv, ImplingObjs.empty_jar, 3)
            return
        }
        val needs = trade.jars.map { (type, count) -> ImplingObjs.jars[type - 1] to count }
        if (needs.any { (jar, count) -> invTotal(inv, jar) < count }) {
            startDialogue(npc) { chatNpc(neutral, "You don't have the jars for that: ${trade.label}.") }
            return
        }
        for ((jar, count) in needs) invDel(inv, jar, count)
        invAdd(inv, ImplingObjs.byName.getValue(trade.reward))
        startDialogue(npc) { chatNpc(happy, "A pleasure doing business with you.") }
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
        const val CATCH_BONUS = 8
        const val STARTER_JARS = 7
        const val JAR_PRICE = 2000
        const val PURO_RESPAWN = 100
        const val MAX_BUY = 1000
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

    class Exchange(val label: String, val jars: List<Pair<Int, Int>>, val reward: String, val anyJar: Boolean = false)

    /** Wiki "Elnock's Exchange" (jar type 1 baby .. 7 nature). */
    val exchanges: List<Exchange> =
        listOf(
            Exchange("Imp repellent (3 baby, 2 young, 1 gourmet)", listOf(1 to 3, 2 to 2, 3 to 1), "ii_imp_repellent"),
            Exchange("Magic net (3 gourmet, 2 earth, 1 essence)", listOf(3 to 3, 4 to 2, 5 to 1), "ii_magic_butterfly_net"),
            Exchange("Jar generator (3 essence, 2 eclectic, 1 nature)", listOf(5 to 3, 6 to 2, 7 to 1), "ii_jar_generator"),
            Exchange("3 impling jars (any 1 jarred impling)", emptyList(), "ii_impling_jar", anyJar = true),
        )

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
    val elnock = find("ii_elnock")
    val world = (1..11).map { find("ii_impling_type_$it") }
    val maze = (1..11).map { find("ii_impling_type_${it}_maze") }
}

internal object ImplingObjs : ObjReferences() {
    val empty_jar = find("ii_impling_jar")
    val coins = find("coins")
    val jars = (1..11).map { find("ii_captured_impling_$it") }
    val nets = listOf(find("hunting_butterfly_net"), find("ii_magic_butterfly_net"))
    val byName: Map<String, ObjType> =
        (ImplingData.imps.flatMap { i -> i.loot.map { it.obj } } + ImplingData.exchanges.map { it.reward })
            .distinct()
            .associateWith { find(it) }
}

internal object ImplingLocs : LocReferences() {
    val crop_circles = listOf(find("ii_magic_wheat_m"), find("ii_magic_wheat_m_zanaris"))
    val puro_portal = find("ii_rocks_floor_hard_02")
}

internal object ImplingTimers : TimerReferences() {
    /** Custom timer (65502, skilling range). */
    val world_spawn = find("hunter_impling_spawn")
}

/** Saved per character (skilling range 65944). */
internal object ImplingVarpBuilds : org.rsmod.api.type.builders.varp.VarpBuilder() {
    init {
        build("hunter_elnock_starter")
    }
}

internal object ImplingVarps : org.rsmod.api.type.refs.varp.VarpReferences() {
    /** 1 once Elnock has handed out the starter net and jars. */
    val elnock = find("hunter_elnock_starter")
}
