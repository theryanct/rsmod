package org.rsmod.content.skills.fishing.tempoross

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseFishingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tempoross reward pool (OSRS Wiki "Reward pool"), Ruins of Unkah. Net = one reward per permit
 * every 3 ticks; Big-search = up to 5 at once; Check = permits left. Each reward: the unique roll
 * (flakes, casket, soaked pages, fish barrel, tackle box, big harpoonfish, tome of water, Tiny Tempor,
 * dragon harpoon; /8000), else 45/80 the fish table for the base Fishing level band (noted), else the
 * "other" table. 10 Fishing xp per reward. Not yet: Flakes 'n' Flotsam / outfit vendor / retired
 * sailor exchanges (coin-only shop system), duplicate barrel/tackle box -> soaked pages.
 */
class TemporossRewards
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private class Drop(val obj: String, val min: Int, val max: Int, val weight: Int, val noted: Boolean = false)

    private val uniques =
        listOf(
            Drop("spirit_flakes", 32, 64, 2000),
            Drop("tempoross_reward_casket", 1, 1, 400),
            Drop("soaked_page", 5, 9, 149),
            Drop("fish_barrel_closed", 1, 1, 20),
            Drop("tackle_box", 1, 1, 20),
            Drop("poh_trophydrop_harpoonfish", 1, 1, 5),
            Drop("tome_of_water_uncharged", 1, 1, 5),
            Drop("temporosspet", 1, 1, 1),
            Drop("dragon_harpoon", 1, 1, 1),
        )

    /** Wiki fish table bands (base Fishing level -> fish for weights 900/810/720/630/540 of 6400). */
    private val fishBands =
        listOf(
            35 to listOf("raw_herring", "raw_mackerel", "raw_pike", "raw_salmon", "raw_tuna"),
            40 to listOf("raw_mackerel", "raw_pike", "raw_salmon", "raw_tuna", "raw_lobster"),
            46 to listOf("raw_pike", "raw_salmon", "raw_tuna", "raw_lobster", "raw_bass"),
            50 to listOf("raw_salmon", "raw_tuna", "raw_lobster", "raw_bass", "raw_swordfish"),
            76 to listOf("raw_tuna", "raw_lobster", "raw_bass", "raw_swordfish", "raw_shark"),
            79 to listOf("raw_lobster", "raw_bass", "raw_swordfish", "raw_shark", "raw_seaturtle"),
            81 to listOf("raw_bass", "raw_swordfish", "raw_shark", "raw_seaturtle", "raw_mantaray"),
        )
    private val fishAmounts = listOf(30 to 40, 20 to 30, 10 to 20, 7 to 12, 5 to 10)
    private val fishWeights = listOf(900, 810, 720, 630, 540)

    /** Wiki "other" table, weights /800. */
    private val others =
        listOf(
            Drop("woodplank", 20, 30, 35, noted = true),
            Drop("plank_oak", 15, 25, 25, noted = true),
            Drop("seaweed", 20, 60, 20, noted = true),
            Drop("nails", 300, 500, 10),
            Drop("feather", 800, 1600, 10),
            Drop("fishing_bait", 800, 1600, 10),
        )

    override fun ScriptContext.startup() {
        for (pool in RewardLocs.pools) {
            bind(pool, "Net") { net(it, op(pool, "Net")) }
            bind(pool, "Big-search") { bigSearch() }
            bind(pool, "Check") { check() }
        }
    }

    private fun op(type: LocType, label: String): Int =
        locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1

    private fun ScriptContext.bind(type: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = op(type, label)
        if (op <= 0 || isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
        }
    }

    private fun ProtectedAccess.permits(): Int = player.vars[TemporossVarps.permits]

    private fun ProtectedAccess.setPermits(value: Int) {
        VarPlayerIntMapSetter.set(player, TemporossVarps.permits, value)
        VarPlayerIntMapSetter.set(player, TemporossVarBits.reward_permits, value)
    }

    private fun ProtectedAccess.check() {
        val left = permits()
        mes("You have $left reward permit${if (left == 1) "" else "s"} to spend at the pool.")
    }

    private fun ProtectedAccess.net(pool: BoundLocInfo, op: Int) {
        if (actionDelay <= mapClock) {
            if (!takeOne()) return
            actionDelay = mapClock + NET_TICKS
            if (permits() <= 0 || inv.isFull()) return
        }
        when (op) {
            1 -> opLoc1(pool)
            2 -> opLoc2(pool)
            3 -> opLoc3(pool)
        }
    }

    private fun ProtectedAccess.bigSearch() {
        repeat(BIG_SEARCH) { if (!takeOne()) return }
    }

    /** Spends a permit on one reward; false when out of permits or inventory space. */
    private fun ProtectedAccess.takeOne(): Boolean {
        val left = permits()
        if (left <= 0) {
            mes("You have no reward permits. Help defeat Tempoross to earn some.")
            return false
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return false
        }
        setPermits(left - 1)
        val (obj, count, noted) = roll()
        invAdd(inv, obj, count, cert = noted)
        statAdvance(stats.fishing, REWARD_XP * xpMods.get(player, stats.fishing))
        spam("You find ${if (count > 1) "$count x " else ""}${objTypes[obj].name.lowercase()} in the pool.")
        return true
    }

    private fun ProtectedAccess.roll(): Triple<ObjType, Int, Boolean> {
        val unique = random.of(UNIQUE_DENOMINATOR)
        var acc = 0
        for (drop in uniques) {
            acc += drop.weight
            if (unique < acc) return Triple(RewardObjs.byName.getValue(drop.obj), amount(drop.min, drop.max), false)
        }
        if (random.of(80) < 45) {
            val level = player.baseFishingLvl
            val band = fishBands.last { level >= it.first || it === fishBands.first() }.second
            var r = random.of(fishWeights.sum())
            for ((i, w) in fishWeights.withIndex()) {
                if (r < w) {
                    val (min, max) = fishAmounts[i]
                    return Triple(RewardObjs.byName.getValue(band[i]), amount(min, max), true)
                }
                r -= w
            }
        }
        var r = random.of(others.sumOf { it.weight })
        for (drop in others) {
            if (r < drop.weight) return Triple(RewardObjs.byName.getValue(drop.obj), amount(drop.min, drop.max), drop.noted)
            r -= drop.weight
        }
        val last = others.last()
        return Triple(RewardObjs.byName.getValue(last.obj), last.min, last.noted)
    }

    private fun ProtectedAccess.amount(min: Int, max: Int): Int = if (max <= min) min else random.of(min, max)

    private companion object {
        const val NET_TICKS = 3
        const val BIG_SEARCH = 5
        const val REWARD_XP = 10.0
        const val UNIQUE_DENOMINATOR = 8000
    }
}

internal object RewardLocs : LocReferences() {
    /** Multiloc children (tempoross_rewardpermits): _1.._8 carry Net / Check / Big-search. */
    val pools = (1..8).map { find("tempoross_rewardpool_$it") }
}

internal object RewardObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        listOf(
                "spirit_flakes", "tempoross_reward_casket", "soaked_page", "fish_barrel_closed", "tackle_box",
                "poh_trophydrop_harpoonfish", "tome_of_water_uncharged", "temporosspet", "dragon_harpoon",
                "raw_herring", "raw_mackerel", "raw_pike", "raw_salmon", "raw_tuna", "raw_lobster", "raw_bass",
                "raw_swordfish", "raw_shark", "raw_seaturtle", "raw_mantaray",
                "woodplank", "plank_oak", "seaweed", "nails", "feather", "fishing_bait",
            )
            .associateWith { find(it) }
}
