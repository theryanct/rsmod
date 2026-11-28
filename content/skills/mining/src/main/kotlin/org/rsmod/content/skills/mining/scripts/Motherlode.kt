package org.rsmod.content.skills.mining.scripts

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeAnim
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeTicks
import org.rsmod.game.MapClock
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varp.VarpType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Motherlode Mine (OSRS Wiki "Motherlode Mine", "Pay-dirt", "Prospector Percy's Nugget Shop"):
 * - The map places only depleted ore veins; the first login after boot makes every vein minable
 *   and fixes the struts and wheels ([MotherlodeData], from tools/mining/motherlode_scan.py).
 * - Ore veins (30 Mining, 57 on the upper level): any pickaxe, success chance 60-105/256, one
 *   pay-dirt and 60 xp per success. A vein depletes 23-27 s (upper level 36-40 s) after its first
 *   pay-dirt and returns [VEIN_RESPAWN] ticks later.
 * - Hopper: "Deposit" / use pay-dirt. Each pay-dirt rolls its ore by the Wiki's cascade (nugget,
 *   runite, adamantite, mithril, gold, else coal; each needs its rock's Mining level) and gives
 *   the bonus xp. The sack holds 108 (189 with the bigger sack). Ores arrive straight away while
 *   a water wheel turns; with both wheels broken, deposits wait until a strut is fixed.
 * - Struts break now and then on deposits (both struts of a wheel stop it); "Hammer" with a
 *   hammer repairs at 12% (level 1) to 27% (level 99 Smithing) per tick for 1.5x Smithing level xp.
 * - Sack "Search": ores into free inventory slots (best first), nuggets always.
 * - Rockfalls: mine them away (10 xp); they come back later.
 * - Prospector Percy "Trade": the nugget shop (prospector kit, coal bag, gem bag, bigger sack).
 * Not included: the upper level ladder/unlock, gems from veins, shortcuts, the Falador diary.
 */
class Motherlode
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val mapClock: MapClock,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private var initialised = false

    /** Strut tiles in [MotherlodeData.struts] order, fixed or not (world-wide, as in OSRS). */
    private val strutFixed = BooleanArray(MotherlodeData.struts.size) { true }

    /** Vein tile -> map clock at which it depletes (set by its first pay-dirt). */
    private val depleteAt = HashMap<CoordGrid, Int>()

    /** Depleted veins waiting to come back: (vein, respawn clock). */
    private val respawns = ArrayList<Pair<MotherlodeData.Vein, Int>>()

    private val veinsByCoords by lazy { MotherlodeData.veins.associateBy { it.coords } }

    override fun ScriptContext.startup() {
        onPlayerLogin { setUpMine() }
        for (active in MotherlodeLocs.active.values.distinct()) {
            bindLoc1(active) { mineVein(it) }
        }
        bindLoc1(MotherlodeLocs.hopper) { deposit() }
        onOpLocU(MotherlodeLocs.hopper, MotherlodeObjs.paydirt) { deposit() }
        bindLoc1(MotherlodeLocs.strut_broken) { repairStrut(it) }
        bindLoc1(MotherlodeLocs.sack) { searchSack() }
        bindLoc1(MotherlodeLocs.rockfall_1) { clearRockfall(it) }
        bindLoc1(MotherlodeLocs.rockfall_2) { clearRockfall(it) }
        bindLoc1(MotherlodeLocs.entrance) { telejump(arrival(INSIDE)) }
        bindLoc1(MotherlodeLocs.exit) { telejump(arrival(OUTSIDE)) }
        bindLoc1(MotherlodeLocs.entrance_guild) { enterFromGuild() }
        bindLoc1(MotherlodeLocs.exit_guild) { telejump(arrival(GUILD_OUTSIDE)) }
        if (!isNpcOpBound(MotherlodeNpcs.percy, 3)) onOpNpc3(MotherlodeNpcs.percy) { shop() }
        if (!isNpcOpBound(MotherlodeNpcs.percy, 1)) onOpNpc1(MotherlodeNpcs.percy) { shop() }
    }

    private fun ScriptContext.bindLoc1(type: LocType, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (isLocOpBound(type, 1)) return
        onOpLoc1(type) { action(it.loc) }
    }

    /* World set-up */

    private fun setUpMine() {
        if (initialised) return
        initialised = true
        for (vein in MotherlodeData.veins) {
            val loc = locRepo.findExact(vein.coords, MotherlodeLocs.depleted.getValue(vein.depleted)) ?: continue
            locRepo.change(loc, MotherlodeLocs.active.getValue(vein.depleted), PERMANENT)
        }
        for (index in MotherlodeData.struts.indices) setStrut(index, fixed = true)
    }

    private fun setStrut(index: Int, fixed: Boolean) {
        strutFixed[index] = fixed
        val coords = MotherlodeData.struts[index]
        val current =
            locRepo.findExact(coords, MotherlodeLocs.strut_broken)
                ?: locRepo.findExact(coords, MotherlodeLocs.strut_fixed)
        if (current != null) {
            val into = if (fixed) MotherlodeLocs.strut_fixed else MotherlodeLocs.strut_broken
            locRepo.change(current, into, PERMANENT)
        }
        for ((wheel, coordsOfWheel) in MotherlodeData.wheels.withIndex()) {
            val turning = wheelTurns(wheel)
            val loc =
                locRepo.findExact(coordsOfWheel, MotherlodeLocs.wheel_broken)
                    ?: locRepo.findExact(coordsOfWheel, MotherlodeLocs.wheel_fixed)
                    ?: continue
            val into = if (turning) MotherlodeLocs.wheel_fixed else MotherlodeLocs.wheel_broken
            locRepo.change(loc, into, PERMANENT)
        }
    }

    /** A wheel turns while both of its struts (same row, z next to the wheel) are fixed. */
    private fun wheelTurns(wheel: Int): Boolean {
        val wheelCoords = MotherlodeData.wheels[wheel]
        return MotherlodeData.struts.withIndex().filter { (_, s) -> abs(s.z - wheelCoords.z) <= 2 }.all {
            strutFixed[it.index]
        }
    }

    private fun waterFlows(): Boolean = MotherlodeData.wheels.indices.any { wheelTurns(it) }

    private fun processRespawns() {
        val due = respawns.filter { it.second <= mapClock.cycle }
        if (due.isEmpty()) return
        respawns.removeAll(due.toSet())
        for ((vein, _) in due) {
            val loc = locRepo.findExact(vein.coords, MotherlodeLocs.depleted.getValue(vein.depleted)) ?: continue
            locRepo.change(loc, MotherlodeLocs.active.getValue(vein.depleted), PERMANENT)
        }
    }

    /* Veins */

    private fun ProtectedAccess.mineVein(loc: BoundLocInfo) {
        processRespawns()
        val vein = veinsByCoords[loc.coords] ?: return
        val upper = vein.coords.level > 0
        val required = if (upper) UPPER_LEVEL else MINE_LEVEL
        if (player.miningLvl < required) {
            mes("You need a Mining level of $required to mine here.")
            return
        }
        val pickaxe = Mining.findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine this rock.")
            return
        }
        if (inv.isFull()) {
            mes("Your inventory is too full to hold any more pay-dirt.")
            return
        }
        val carried = invTotal(inv, MotherlodeObjs.paydirt)
        if (sackTotal() + pending() + carried >= capacity()) {
            mes("You already have enough pay-dirt to fill the sack.")
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
            if (statRandom(stats.mining, PAYDIRT_LOW, PAYDIRT_HIGH, invisibleLvls)) {
                invAdd(inv, MotherlodeObjs.paydirt)
                statAdvance(stats.mining, PAYDIRT_XP * xpMods.get(player, stats.mining))
                val at = depleteAt.getOrPut(vein.coords) {
                    mapClock.cycle + if (upper) random.of(60, 66) else random.of(38, 45)
                }
                if (mapClock.cycle >= at) {
                    depleteAt.remove(vein.coords)
                    val current = locRepo.findExact(vein.coords, MotherlodeLocs.active.getValue(vein.depleted))
                    if (current != null) {
                        locRepo.change(current, MotherlodeLocs.depleted.getValue(vein.depleted), PERMANENT)
                        respawns += vein to (mapClock.cycle + VEIN_RESPAWN)
                    }
                    resetAnim()
                    return
                }
            }
            actionDelay = mapClock + pickaxeType.pickaxeTicks
        }
        opLoc1(loc)
    }

    /* Hopper and sack */

    private fun ProtectedAccess.capacity(): Int =
        if ((vars[MotherlodeVarps.flags] and FLAG_BIGGER_SACK) != 0) BIGGER_SACK else SACK

    private fun ProtectedAccess.sackTotal(): Int = MotherlodeVarps.sack.sumOf { vars[it] }

    private fun ProtectedAccess.pending(): Int = vars[MotherlodeVarps.pending]

    private fun ProtectedAccess.deposit() {
        val carried = invTotal(inv, MotherlodeObjs.paydirt)
        if (carried == 0) {
            mes("You don't have any pay-dirt to put in the hopper.")
            return
        }
        val space = capacity() - sackTotal() - pending()
        val count = minOf(carried, space)
        if (count <= 0) {
            mes("The sack is full; search it before adding more pay-dirt.")
            return
        }
        invDel(inv, MotherlodeObjs.paydirt, count)
        anim(MotherlodeSeqs.deposit)
        vars[MotherlodeVarps.pending] = pending() + count
        if (random.of(1, STRUT_BREAK_CHANCE) == 1) {
            val fixed = strutFixed.indices.filter { strutFixed[it] }
            if (fixed.isNotEmpty()) setStrut(fixed[random.of(0, fixed.size - 1)], fixed = false)
        }
        if (waterFlows()) {
            flush()
            mes("You add the pay-dirt to the hopper.")
        } else {
            mes("You add the pay-dirt to the hopper, but the water wheels have stopped.")
        }
    }

    /** Pay-dirt waiting in the water circuit reaches the sack (ores rolled, bonus xp given). */
    private fun ProtectedAccess.flush() {
        val count = pending()
        if (count == 0 || !waterFlows()) return
        vars[MotherlodeVarps.pending] = 0
        var xp = 0.0
        val counts = IntArray(MotherlodeData.ores.size)
        repeat(count) {
            val ore = rollOre()
            counts[ore]++
            xp += MotherlodeData.ores[ore].bonusXp
        }
        for ((i, varp) in MotherlodeVarps.sack.withIndex()) vars[varp] = vars[varp] + counts[i]
        if (xp > 0) statAdvance(stats.mining, xp * xpMods.get(player, stats.mining))
        syncSack()
        if (sackTotal() >= capacity()) mes("Some ore is ready to be collected from the sack. It's getting full.")
    }

    /** Wiki cascade: try nugget, runite, adamantite, mithril, gold in turn; coal otherwise. */
    private fun ProtectedAccess.rollOre(): Int {
        val level = player.miningLvl
        for ((index, ore) in MotherlodeData.ores.withIndex()) {
            if (ore.low == null || level < ore.level) continue
            if (statRandom(stats.mining, ore.low, ore.high!!, invisibleLvls)) return index
        }
        return MotherlodeData.COAL
    }

    private fun ProtectedAccess.syncSack() {
        vars[MotherlodeVarBits.sack_transmit] = minOf(sackTotal(), 255)
    }

    private fun ProtectedAccess.searchSack() {
        flush()
        if (sackTotal() == 0) {
            mes(if (pending() > 0) "The water wheels need fixing before the pay-dirt reaches the sack." else "The sack is empty.")
            return
        }
        // Nuggets stack; ores fill the free slots, best ore first.
        val nuggets = vars[MotherlodeVarps.sack[MotherlodeData.NUGGET]]
        if (nuggets > 0) {
            invAdd(inv, MotherlodeObjs.nugget, nuggets)
            vars[MotherlodeVarps.sack[MotherlodeData.NUGGET]] = 0
        }
        var taken = 0
        for (index in MotherlodeData.collectOrder) {
            val varp = MotherlodeVarps.sack[index]
            val type = MotherlodeObjs.ores.getValue(MotherlodeData.ores[index].obj)
            while (vars[varp] > 0 && !inv.isFull()) {
                invAdd(inv, type)
                vars[varp] = vars[varp] - 1
                taken++
            }
        }
        syncSack()
        if (taken == 0 && nuggets == 0) {
            mes("You don't have any inventory space.")
        } else {
            mes("You collect your ore from the sack.")
        }
    }

    /* Struts and rockfalls */

    private suspend fun ProtectedAccess.repairStrut(loc: BoundLocInfo) {
        val index = MotherlodeData.struts.indexOf(loc.coords)
        if (index < 0 || strutFixed[index]) return
        if (MotherlodeObjs.hammer !in inv && MotherlodeObjs.imcando_hammer !in inv) {
            mes("You need a hammer to repair the strut.")
            return
        }
        mes("You start repairing the strut.")
        while (!strutFixed[index]) {
            anim(MotherlodeSeqs.hammer)
            delay(1)
            val level = player.smithingLvl
            val chance = 12 + (15 * (level - 1)) / 98
            if (random.of(1, 100) <= chance) {
                setStrut(index, fixed = true)
                statAdvance(stats.smithing, 1.5 * level * xpMods.get(player, stats.smithing))
                mes("You repair the strut.")
                flush()
            }
        }
    }

    private fun ProtectedAccess.clearRockfall(loc: BoundLocInfo) {
        if (Mining.findPickaxe(player, objTypes) == null) {
            mes("You need a pickaxe to clear the rockfall.")
            return
        }
        val current =
            locRepo.findExact(loc.coords, MotherlodeLocs.rockfall_1)
                ?: locRepo.findExact(loc.coords, MotherlodeLocs.rockfall_2)
                ?: return
        locRepo.del(current, ROCKFALL_RESPAWN)
        statAdvance(stats.mining, ROCKFALL_XP * xpMods.get(player, stats.mining))
        mes("You clear the rockfall.")
    }

    /* Travel */

    private fun ProtectedAccess.enterFromGuild() {
        if (player.miningLvl < GUILD_LEVEL) {
            mes("You need a Mining level of $GUILD_LEVEL to use this entrance.")
            return
        }
        telejump(arrival(GUILD_INSIDE))
    }

    private fun arrival(pin: CoordGrid): CoordGrid {
        if ((collision[pin.x, pin.z, pin.level] and BLOCKED) == 0) return pin
        for (r in 1..ARRIVAL_RADIUS) {
            for (dz in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val tile = pin.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) == 0) return tile
                }
            }
        }
        return pin
    }

    /* Nugget shop */

    private suspend fun ProtectedAccess.shop() {
        val bigger = (vars[MotherlodeVarps.flags] and FLAG_BIGGER_SACK) != 0
        val stock = MotherlodeData.shop.filter { !(it.obj == null && bigger) }
        val labels = stock.map { "${it.name}: ${it.nuggets} nuggets" }
        val picked = menu("Prospector Percy's Nugget Shop", hotkeys = false, choices = labels + "Nothing")
        val item = stock.getOrNull(picked) ?: return
        if (invTotal(inv, MotherlodeObjs.nugget) < item.nuggets) {
            mes("You need ${item.nuggets} golden nuggets for that.")
            return
        }
        if (item.obj != null && inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        invDel(inv, MotherlodeObjs.nugget, item.nuggets)
        if (item.obj == null) {
            vars[MotherlodeVarps.flags] = vars[MotherlodeVarps.flags] or FLAG_BIGGER_SACK
            vars[MotherlodeVarBits.biggersack] = 1
            mes("Prospector Percy upgrades your sack.")
        } else {
            invAdd(inv, MotherlodeObjs.shop.getValue(item.obj))
        }
    }

    private companion object {
        const val PERMANENT = Int.MAX_VALUE
        const val MINE_LEVEL = 30
        const val UPPER_LEVEL = 57
        const val GUILD_LEVEL = 60
        const val PAYDIRT_LOW = 60
        const val PAYDIRT_HIGH = 105
        const val PAYDIRT_XP = 60.0
        const val ROCKFALL_XP = 10.0
        const val SACK = 108
        const val BIGGER_SACK = 189
        const val FLAG_BIGGER_SACK = 1
        /** Not on the Wiki: veins come back ~45 s after depleting. */
        const val VEIN_RESPAWN = 75
        /** Not on the Wiki: rockfalls come back after ~2 minutes. */
        const val ROCKFALL_RESPAWN = 200
        /** Not on the Wiki: one deposit in this many breaks a strut. */
        const val STRUT_BREAK_CHANCE = 30
        const val ARRIVAL_RADIUS = 6
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC

        /** Just inside the north entrance's exit tunnel / outside the Dwarven Mine cave. */
        val INSIDE = CoordGrid(3728, 5691, 0)
        val OUTSIDE = CoordGrid(3060, 9767, 0)
        val GUILD_INSIDE = CoordGrid(3718, 5678, 0)
        val GUILD_OUTSIDE = CoordGrid(3054, 9743, 0)
    }
}

internal object MotherlodeData {
    class Vein(val depleted: String, val coords: CoordGrid)

    /** [low]/[high] = the Wiki success chart (out of 256); null for the coal fallback. */
    class Ore(val obj: String, val level: Int, val bonusXp: Double, val low: Int?, val high: Int?)

    class ShopItem(val name: String, val nuggets: Int, val obj: String?)

    /** Sack slots (and roll order): nugget, runite, adamantite, mithril, gold, coal. */
    val ores: List<Ore> =
        listOf(
            Ore("motherlode_nugget", 30, 0.0, 7, 7),
            Ore("runite_ore", 85, 75.0, -20, 5),
            Ore("adamantite_ore", 70, 45.0, -90, 50),
            Ore("mithril_ore", 55, 30.0, -19, 90),
            Ore("gold_ore", 40, 15.0, -40, 126),
            Ore("coal", 30, 15.0, null, null),
        )

    const val NUGGET = 0
    const val COAL = 5

    /** Best ore first when collecting (nuggets are handled separately). */
    val collectOrder: List<Int> = listOf(1, 2, 3, 4, 5)

    val shop: List<ShopItem> =
        listOf(
            ShopItem("Prospector helmet", 40, "motherlode_reward_hat"),
            ShopItem("Prospector jacket", 60, "motherlode_reward_top"),
            ShopItem("Prospector legs", 50, "motherlode_reward_legs"),
            ShopItem("Prospector boots", 30, "motherlode_reward_boots"),
            ShopItem("Coal bag", 100, "coal_bag"),
            ShopItem("Gem bag", 100, "gem_bag"),
            ShopItem("Bigger sack (holds 189)", 200, null),
        )

    private val lines: List<List<String>> by lazy {
        val input =
            MotherlodeData::class.java.getResourceAsStream("/org/rsmod/content/skills/mining/motherlode.tsv")
                ?: error("Mining resource not found: motherlode.tsv")
        input.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
    }

    private fun List<String>.coords(): CoordGrid = CoordGrid(this[2].toInt(), this[3].toInt(), this[4].toInt())

    val veins: List<Vein> by lazy { lines.filter { it[0] == "vein" }.map { Vein(it[1], it.coords()) } }

    val struts: List<CoordGrid> by lazy { lines.filter { it[0] == "strut" }.map { it.coords() } }

    val wheels: List<CoordGrid> by lazy { lines.filter { it[0] == "wheel" }.map { it.coords() } }

    val depletedNames: List<String> =
        listOf("single", "left", "middle", "right").map { "motherlode_depleted_$it" }
}

internal object MotherlodeLocs : LocReferences() {
    val depleted: Map<String, LocType> = MotherlodeData.depletedNames.associateWith { find(it) }
    val active: Map<String, LocType> =
        MotherlodeData.depletedNames.associateWith { find(it.replace("_depleted_", "_ore_")) }
    val hopper = find("motherlode_hopper")
    val sack = find("motherlode_sack")
    val strut_broken = find("motherlode_wheel_strut_broken")
    val strut_fixed = find("motherlode_wheel_strut_fixed")
    val wheel_broken = find("motherlode_wheel_broken")
    val wheel_fixed = find("motherlode_wheel_fixed")
    val rockfall_1 = find("motherlode_rockfall_1")
    val rockfall_2 = find("motherlode_rockfall_2")
    val entrance = find("motherlode_entrance")
    val exit = find("motherlode_exit")
    val entrance_guild = find("motherlode_entrance_guild")
    val exit_guild = find("motherlode_exit_guild")
}

internal object MotherlodeObjs : ObjReferences() {
    val paydirt = find("paydirt")
    val nugget = find("motherlode_nugget")
    val hammer = find("hammer")
    val imcando_hammer = find("imcando_hammer")
    val ores: Map<String, ObjType> = MotherlodeData.ores.map { it.obj }.associateWith { find(it) }
    val shop: Map<String, ObjType> = MotherlodeData.shop.mapNotNull { it.obj }.associateWith { find(it) }
}

internal object MotherlodeNpcs : NpcReferences() {
    val percy = find("motherlode_percy")
}

internal object MotherlodeSeqs : SeqReferences() {
    val deposit = find("human_pickupfloor")
    val hammer = find("human_hammer_hit")
}

internal object MotherlodeVarBits : VarBitReferences() {
    val sack_transmit = find("motherlode_sack_transmit")
    val biggersack = find("motherlode_biggersack")
}

/** Saved per character (skilling symbol range 65666-65673). */
internal object MotherlodeVarpBuilds : VarpBuilder() {
    init {
        for (ore in SACK_NAMES) build("motherlode_sack_$ore")
        build("motherlode_pending")
        build("motherlode_flags")
    }
}

private val SACK_NAMES = listOf("nugget", "runite", "adamantite", "mithril", "gold", "coal")

internal object MotherlodeVarps : VarpReferences() {
    /** Ores waiting in the sack, in [MotherlodeData.ores] order. */
    val sack: List<VarpType> = SACK_NAMES.map { find("motherlode_sack_$it") }
    /** Pay-dirt in the water circuit while both wheels are stopped. */
    val pending = find("motherlode_pending")
    /** Bit 0: bigger sack bought. */
    val flags = find("motherlode_flags")
}
