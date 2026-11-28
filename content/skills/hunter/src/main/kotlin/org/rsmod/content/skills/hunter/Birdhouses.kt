package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Bird houses (OSRS Wiki "Bird house trapping"):
 * - Craft: clockwork on logs (hammer + chisel): Crafting 5-90, 15-55 xp (Wiki table).
 * - Place on one of the four Fossil Island spaces ("Build", or use the bird house on it): Hunter
 *   5-89. Fill with 10 low-level or 5 high-level seeds; it catches birds for ~50 minutes.
 * - Empty / Dismantle when full: Hunter xp (112-1200), the clockwork back, 10 raw bird meat (to
 *   the ground, Wiki), 30/40/50/60 feathers, at most one seed nest (0% at 1 -> 78.5% at 99
 *   Hunter) and 5 rolls on the nest table (Mod Ash's per-tier chance at 99, half at 50 and below).
 *   Emptied early: only the clockwork comes back.
 * The nest table split (empty / ring / egg) is an approximation: the Wiki gives no weights.
 */
class Birdhouses
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    override fun ScriptContext.startup() {
        for (tier in BirdhouseData.tiers) {
            val logs = BirdhouseObjs.byName.getValue(tier.logs)
            onOpHeldU(BirdhouseObjs.clockwork, logs) { craft(tier) }
        }
        for ((index, spot) in BirdhouseLocs.spots.withIndex()) {
            // Child stage locs are shared by the four spaces; the bound loc's tile picks the spot.
            for (tier in BirdhouseData.tiers) {
                onOpLocU(spot, BirdhouseObjs.byName.getValue(tier.item)) { place(index, tier) }
            }
            for (seed in BirdhouseData.seeds.keys) onOpLocU(spot, BirdhouseObjs.byName.getValue(seed)) { addSeeds(index) }
        }
        for (child in BirdhouseLocs.children) {
            val ops = locTypes[child].op
            for ((i, text) in ops.withIndex()) {
                val label = text?.lowercase() ?: continue
                val action: (suspend ProtectedAccess.(Int) -> Unit)? =
                    when (label) {
                        "build" -> { spot -> buildBest(spot) }
                        "empty", "dismantle", "reset" -> { spot -> empty(spot) }
                        "seeds" -> { spot -> addSeeds(spot) }
                        else -> null
                    }
                if (action == null) continue
                when (i + 1) {
                    1 -> onOpLoc1(child) { spotAt(it.loc.coords)?.let { s -> action(s) } }
                    2 -> onOpLoc2(child) { spotAt(it.loc.coords)?.let { s -> action(s) } }
                    3 -> onOpLoc3(child) { spotAt(it.loc.coords)?.let { s -> action(s) } }
                    4 -> onOpLoc4(child) { spotAt(it.loc.coords)?.let { s -> action(s) } }
                }
            }
        }
        onPlayerLogin { refresh(player) }
        onPlayerSoftTimer(BirdhouseTimers.refresh) { refresh(player) }
    }

    private fun spotAt(coords: CoordGrid): Int? =
        BirdhouseData.spotTiles.indexOfFirst { it.chebyshevDistance(coords) <= 1 && it.level == coords.level }
            .takeIf { it >= 0 }

    /* State: [state varp] bits 0-3 tier + 1 (0 = empty space), bit 4 seeded; [time varp] the minute
     * the seeds went in. */

    private fun Player.state(spot: Int): Int = vars[BirdhouseVarps.state[spot]]

    private fun Player.seededAt(spot: Int): Int = vars[BirdhouseVarps.time[spot]]

    private fun Player.save(spot: Int, state: Int, seeded: Int? = null) {
        VarPlayerIntMapSetter.set(this, BirdhouseVarps.state[spot], state)
        if (seeded != null) VarPlayerIntMapSetter.set(this, BirdhouseVarps.time[spot], seeded)
        refresh(this)
    }

    private fun nowMinutes(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    private fun Player.full(spot: Int): Boolean =
        (state(spot) shr 4) and 1 == 1 && nowMinutes() - seededAt(spot) >= BirdhouseData.CATCH_MINUTES

    /** Transmit value: 0 space; tier t: 1 + 3t built, 2 + 3t seeded, 3 + 3t full. */
    private fun Player.displayValue(spot: Int): Int {
        val state = state(spot)
        val tier = (state and 15) - 1
        if (tier < 0) return 0
        val seeded = (state shr 4) and 1 == 1
        return 1 + 3 * tier + if (!seeded) 0 else if (full(spot)) 2 else 1
    }

    private fun refresh(player: Player) {
        player.softTimer(BirdhouseTimers.refresh, REFRESH_TICKS)
        if (BirdhouseData.spotTiles.none { it.chebyshevDistance(player.coords) <= VIEW_RADIUS }) return
        for (spot in BirdhouseVarBits.transmit.indices) {
            VarPlayerIntMapSetter.set(player, BirdhouseVarBits.transmit[spot], player.displayValue(spot))
        }
    }

    /* Crafting */

    private fun ProtectedAccess.craft(tier: BirdhouseData.Tier) {
        if (player.craftingLvl < tier.craftLevel) {
            mes("You need a Crafting level of ${tier.craftLevel} to make this bird house.")
            return
        }
        if (BirdhouseObjs.hammer !in inv || BirdhouseObjs.chisel !in inv) {
            mes("You need a hammer and a chisel to make a bird house.")
            return
        }
        invDel(inv, BirdhouseObjs.clockwork, 1)
        invDel(inv, BirdhouseObjs.byName.getValue(tier.logs), 1)
        invAdd(inv, BirdhouseObjs.byName.getValue(tier.item))
        statAdvance(stats.crafting, tier.craftXp * xpMods.get(player, stats.crafting))
        mes("You make a bird house.")
    }

    /* Spaces */

    private fun ProtectedAccess.buildBest(spot: Int) {
        val tier =
            BirdhouseData.tiers.lastOrNull {
                invTotal(inv, BirdhouseObjs.byName.getValue(it.item)) > 0 && player.hunterLvl >= it.hunterLevel
            }
        if (tier == null) {
            mes("You need a bird house you have the Hunter level to place.")
            return
        }
        place(spot, tier)
    }

    private fun ProtectedAccess.place(spot: Int, tier: BirdhouseData.Tier) {
        if (player.state(spot) != 0) {
            mes("There's already a bird house here.")
            return
        }
        if (player.hunterLvl < tier.hunterLevel) {
            mes("You need a Hunter level of ${tier.hunterLevel} to place this bird house.")
            return
        }
        invDel(inv, BirdhouseObjs.byName.getValue(tier.item), 1)
        player.save(spot, BirdhouseData.tiers.indexOf(tier) + 1)
        mes("You place the bird house. Add some seeds to attract birds.")
    }

    private fun ProtectedAccess.addSeeds(spot: Int) {
        val state = player.state(spot)
        if (state == 0) {
            mes("You need to build a bird house here first.")
            return
        }
        if ((state shr 4) and 1 == 1) {
            mes("The bird house is already full of seeds.")
            return
        }
        // 10 low-level or 5 high-level seeds; low-level seeds count double towards the 10.
        var need = SEED_POINTS
        val use = ArrayList<Pair<ObjType, Int>>()
        for ((name, points) in BirdhouseData.seeds) {
            if (need <= 0) break
            val type = BirdhouseObjs.byName.getValue(name)
            val count = minOf(invTotal(inv, type), (need + points - 1) / points)
            if (count > 0) {
                use += type to count
                need -= count * points
            }
        }
        if (need > 0) {
            mes("You need 10 low-level or 5 high-level seeds to fill the bird house.")
            return
        }
        for ((type, count) in use) invDel(inv, type, count)
        player.save(spot, state or (1 shl 4), seeded = nowMinutes())
        mes("You fill the bird house with seeds.")
    }

    private fun ProtectedAccess.empty(spot: Int) {
        val state = player.state(spot)
        val tierIndex = (state and 15) - 1
        val tier = BirdhouseData.tiers.getOrNull(tierIndex) ?: return
        val full = player.full(spot)
        invAdd(inv, BirdhouseObjs.clockwork)
        player.save(spot, 0, seeded = 0)
        if (!full) {
            mes("You dismantle the bird house and recover the clockwork.")
            return
        }
        statAdvance(stats.hunter, tier.hunterXp * xpMods.get(player, stats.hunter))
        objRepo.add(BirdhouseObjs.raw_bird_meat, player.coords, MEAT_DURATION, player, count = 10)
        invAdd(inv, BirdhouseObjs.feather, FEATHERS.random())
        val level = player.hunterLvl.coerceAtMost(99)
        if (random.of(1, 1000) <= (785 * (level - 1)) / 98) invAdd(inv, BirdhouseObjs.nest_seeds)
        val p99 = BirdhouseData.nestChance99[tierIndex]
        val chance = if (level <= 50) p99 / 2 else p99 * (0.5 + 0.5 * (level - 50) / 49.0)
        repeat(NEST_ROLLS) {
            if (random.of(1, 10_000) <= (chance * 100).toInt()) {
                val roll = random.of(1, 100)
                val nest =
                    when {
                        roll <= 70 -> BirdhouseObjs.nest_empty
                        roll <= 85 -> BirdhouseObjs.nest_ring
                        else -> BirdhouseObjs.nest_egg
                    }
                invAdd(inv, nest)
            }
        }
        mes("You dismantle and discard the trap, retrieving the clockwork and the bird house's loot.")
    }

    private companion object {
        const val REFRESH_TICKS = 50
        const val VIEW_RADIUS = 64
        const val SEED_POINTS = 10
        const val NEST_ROLLS = 5
        const val MEAT_DURATION = 200
        val FEATHERS = listOf(30, 40, 50, 60)
    }
}

internal object BirdhouseData {
    class Tier(
        val item: String,
        val logs: String,
        val craftLevel: Int,
        val craftXp: Double,
        val hunterLevel: Int,
        val hunterXp: Double,
    )

    /** Wiki "Bird house trapping/Table". */
    val tiers: List<Tier> =
        listOf(
            Tier("birdhouse_normal", "logs", 5, 15.0, 5, 112.0),
            Tier("birdhouse_oak", "oak_logs", 15, 20.0, 14, 168.0),
            Tier("birdhouse_willow", "willow_logs", 25, 25.0, 24, 224.0),
            Tier("birdhouse_teak", "teak_logs", 35, 30.0, 34, 280.0),
            Tier("birdhouse_maple", "maple_logs", 45, 35.0, 44, 369.0),
            Tier("birdhouse_mahogany", "mahogany_logs", 50, 40.0, 49, 480.0),
            Tier("birdhouse_yew", "yew_logs", 60, 45.0, 59, 612.0),
            Tier("birdhouse_magic", "magic_logs", 75, 50.0, 74, 969.0),
            Tier("birdhouse_redwood", "redwood_logs", 90, 55.0, 89, 1200.0),
        )

    /** Mod Ash: nest-table roll chance (%) at level 99 per tier. */
    val nestChance99: List<Double> = listOf(10.0, 12.5, 12.8, 13.0, 14.0, 15.0, 16.0, 17.0, 17.5)

    /** Seed -> points towards 10 (low-level 1, high-level 2: "10 low or 5 high"). */
    val seeds: Map<String, Int> =
        linkedMapOf(
            "barley_seed" to 1,
            "hammerstone_hop_seed" to 1,
            "asgarnian_hop_seed" to 1,
            "jute_seed" to 1,
            "yanillian_hop_seed" to 1,
            "potato_seed" to 1,
            "onion_seed" to 1,
            "cabbage_seed" to 1,
            "tomato_seed" to 1,
            "marigold_seed" to 1,
            "guam_seed" to 1,
            "redberry_bush_seed" to 1,
            "krandorian_hop_seed" to 2,
            "wildblood_hop_seed" to 2,
            "ranarr_seed" to 2,
        )

    /** The four Fossil Island spaces (cache map scan), in birdhouse_1..4 order. */
    val spotTiles: List<CoordGrid> =
        listOf(
            CoordGrid(3677, 3882, 0),
            CoordGrid(3679, 3815, 0),
            CoordGrid(3768, 3761, 0),
            CoordGrid(3763, 3755, 0),
        )

    const val CATCH_MINUTES = 50
}

internal object BirdhouseObjs : ObjReferences() {
    val clockwork = find("poh_clockwork_mechanism")
    val hammer = find("hammer")
    val chisel = find("chisel")
    val raw_bird_meat = find("spit_raw_bird_meat")
    val feather = find("feather")
    val nest_seeds = find("bird_nest_seeds")
    val nest_empty = find("bird_nest_empty")
    val nest_ring = find("bird_nest_ring")
    val nest_egg = find("bird_nest_egg_red")
    val byName: Map<String, ObjType> =
        (BirdhouseData.tiers.flatMap { listOf(it.item, it.logs) } + BirdhouseData.seeds.keys)
            .distinct()
            .associateWith { find(it) }
}

internal object BirdhouseLocs : LocReferences() {
    val spots = (1..4).map { find("birdhouse_$it") }
    val children =
        listOf(find("birdhouse_not_built")) +
            listOf("normal", "oak", "willow", "teak", "maple", "mahogany", "yew", "magic", "redwood")
                .flatMap { t -> listOf("built", "full", "bird").map { find("birdhouse_${t}_$it") } }
}

internal object BirdhouseVarBits : VarBitReferences() {
    val transmit = listOf("a", "b", "c", "d").map { find("birdhouse_transmit_$it") }
}

internal object BirdhouseTimers : TimerReferences() {
    /** Custom timer (65501, skilling range). */
    val refresh = find("hunter_birdhouse_refresh")
}

/** Saved per character (skilling range 65936-65943). */
internal object BirdhouseVarpBuilds : VarpBuilder() {
    init {
        for (i in 1..4) {
            build("birdhouse_${i}_state")
            build("birdhouse_${i}_time")
        }
    }
}

internal object BirdhouseVarps : VarpReferences() {
    val state = (1..4).map { find("birdhouse_${it}_state") }
    val time = (1..4).map { find("birdhouse_${it}_time") }
}
