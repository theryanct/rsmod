package org.rsmod.content.skills.mining.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeAnim
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeTicks
import org.rsmod.game.MapClock
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rocks with more than one product (OSRS Wiki Mining info, drop tables and success charts):
 * - Gem rocks (40, 65 xp): opal 60, jade 30, red topaz 15, sapphire 9, emerald 5, ruby 5,
 *   diamond 4 (/128); the rock is empty for ~1 minute after each gem.
 * - Granite (45) and sandstone (35): the Wiki cascade - try the biggest size first; the rocks
 *   never deplete. Granite 5kg/2kg/500g 75/60/50 xp; sandstone 10/5/2/1kg 60/50/40/30 xp.
 * - Amethyst crystals (Mining Guild, 92, 240 xp): empty for ~75 seconds after each amethyst.
 * Depleted gem/amethyst rocks keep their look (no empty loc in the cache); mining them tells you
 * they're empty until they're back.
 */
class SpecialRocks
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val mapClock: MapClock,
) : PluginScript() {
    /** Rock tile -> map clock it can be mined again. */
    private val emptyUntil = HashMap<CoordGrid, Int>()

    override fun ScriptContext.startup() {
        for ((type, rock) in SpecialRockData.rocks()) {
            if (!isLocOpBound(type, 1)) onOpLoc1(type) { mine(it.loc, rock) }
        }
    }

    private fun ProtectedAccess.mine(loc: BoundLocInfo, rock: SpecialRockData.Rock) {
        if (player.miningLvl < rock.level) {
            mes("You need a Mining level of ${rock.level} to mine this rock.")
            return
        }
        val pickaxe = Mining.findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine this rock.")
            return
        }
        val until = emptyUntil[loc.coords]
        if (until != null && this@SpecialRocks.mapClock.cycle < until) {
            mes("There's nothing left to mine in this rock right now.")
            return
        }
        if (inv.isFull()) {
            mes("Your inventory is too full to hold any more.")
            return
        }
        val pickaxeType = objTypes[pickaxe]
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(pickaxeType.pickaxeAnim)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + pickaxeType.pickaxeTicks
            spam("You swing your pick at the rock.")
        } else if (actionDelay == mapClock) {
            val product = roll(rock)
            if (product != null) {
                invAddGathered(SpecialRockObjs.byName.getValue(product.obj))
                statAdvance(stats.mining, product.xp * xpMods.get(player, stats.mining))
                if (rock.respawn > 0) {
                    emptyUntil[loc.coords] = this@SpecialRocks.mapClock.cycle + rock.respawn
                    resetAnim()
                    return
                }
            }
            actionDelay = mapClock + pickaxeType.pickaxeTicks
        }
        opLoc1(loc)
    }

    /** Null = no success this roll. */
    private fun ProtectedAccess.roll(rock: SpecialRockData.Rock): SpecialRockData.Product? =
        when (rock.mode) {
            SpecialRockData.Mode.Single -> {
                val chart = rock.products.first()
                if (statRandom(stats.mining, chart.low, chart.high, invisibleLvls)) chart else null
            }
            // One success roll on the rock's chart, then a weighted product (gem rocks).
            SpecialRockData.Mode.Weighted -> {
                if (!statRandom(stats.mining, rock.low, rock.high, invisibleLvls)) {
                    null
                } else {
                    var pick = random.of(1, rock.products.sumOf { it.weight })
                    rock.products.first { p -> (pick <= p.weight).also { pick -= p.weight } }
                }
            }
            // Wiki cascade: biggest first, each with its own chart.
            SpecialRockData.Mode.Cascade ->
                rock.products.firstOrNull { statRandom(stats.mining, it.low, it.high, invisibleLvls) }
        }
}

internal object SpecialRockData {
    enum class Mode {
        Single,
        Weighted,
        Cascade,
    }

    class Product(val obj: String, val xp: Double, val low: Int = 0, val high: Int = 0, val weight: Int = 0)

    class Rock(
        val locs: List<String>,
        val level: Int,
        val mode: Mode,
        val products: List<Product>,
        /** Ticks the rock stays empty after a product (0 = never depletes). */
        val respawn: Int,
        val low: Int = 0,
        val high: Int = 0,
    )

    val list: List<Rock> =
        listOf(
            Rock(
                listOf("gemrock", "gemrock1"),
                40,
                Mode.Weighted,
                listOf(
                    Product("uncut_opal", 65.0, weight = 60),
                    Product("uncut_jade", 65.0, weight = 30),
                    Product("uncut_red_topaz", 65.0, weight = 15),
                    Product("uncut_sapphire", 65.0, weight = 9),
                    Product("uncut_emerald", 65.0, weight = 5),
                    Product("uncut_ruby", 65.0, weight = 5),
                    Product("uncut_diamond", 65.0, weight = 4),
                ),
                respawn = 99,
                low = 28,
                high = 70,
            ),
            Rock(
                listOf("enakh_granite_rocks"),
                45,
                Mode.Cascade,
                listOf(
                    Product("enakh_granite_medium", 75.0, 6, 64),
                    Product("enakh_granite_small", 60.0, 8, 75),
                    Product("enakh_granite_tiny", 50.0, 16, 100),
                ),
                respawn = 0,
            ),
            Rock(
                listOf("enakh_sandstone_rocks"),
                35,
                Mode.Cascade,
                listOf(
                    Product("enakh_sandstone_large", 60.0, 4, 50),
                    Product("enakh_sandstone_medium", 50.0, 8, 75),
                    Product("enakh_sandstone_small", 40.0, 16, 100),
                    Product("enakh_sandstone_tiny", 30.0, 25, 200),
                ),
                respawn = 0,
            ),
            Rock(
                listOf("amethystrock1", "amethystrock2"),
                92,
                Mode.Single,
                listOf(Product("amethyst", 240.0, -18, 10)),
                respawn = 125,
            ),
        )

    fun rocks(): List<Pair<LocType, Rock>> =
        list.flatMap { rock -> rock.locs.map { SpecialRockLocs.byName.getValue(it) to rock } }
}

internal object SpecialRockLocs : LocReferences() {
    val byName: Map<String, LocType> =
        SpecialRockData.list.flatMap { it.locs }.distinct().associateWith { find(it) }
}

internal object SpecialRockObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        SpecialRockData.list.flatMap { r -> r.products.map { it.obj } }.distinct().associateWith { find(it) }
}
