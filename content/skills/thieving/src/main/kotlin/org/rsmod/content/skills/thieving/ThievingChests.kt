package org.rsmod.content.skills.thieving

import jakarta.inject.Inject
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.thievingLvl
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.skills.thieving.ThievingData.Loot
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Thievable chests (OSRS Wiki "Thieving" > Thievable chests and each chest's page; data in
 * `thieving_chests.tsv` from tools/skills/thieving_chests.py): Ardougne/Rellekka/Rogues' Den
 * 10-coin and nature rune chests, 50-coin, steel arrowtips, Chaos Druid Tower blood runes,
 * Ardougne Castle, Dorgesh-Kaan average/rich, the Lizardman Temple stone chest and the Rogues'
 * Castle chests.
 * - "Search for traps" / "Pick-lock" (Thieving level) loots the chest: the Wiki loot (always
 *   lines plus one weighted roll) and xp; the chest is then empty for its Wiki respawn time.
 * - "Open" on a trapped chest springs the trap instead (damage scales with Hitpoints).
 * Not included: Rogues' Castle diary loot versions, the Ardougne Castle trap teleport to
 * Witchaven, lockpick requirements.
 */
class ThievingChests
@Inject
constructor(private val locTypes: LocTypeList, private val xpMods: XpModifiers) : PluginScript() {
    /** Chest tile -> map clock when it can be looted again (shared, as in OSRS). */
    private val cooldowns = HashMap<CoordGrid, Int>()

    override fun ScriptContext.startup() {
        for (chest in ChestData.chests) {
            for (name in chest.locs) {
                val type = ChestLocs.byName.getValue(name)
                val ops = locTypes[type].op
                for ((index, text) in ops.withIndex()) {
                    val label = text?.lowercase()?.replace("-", "")?.replace(" ", "") ?: continue
                    when (label) {
                        "searchfortraps", "picklock" -> bind(type, index + 1) { loot(it, chest) }
                        "open" -> bind(type, index + 1) { springTrap() }
                    }
                }
            }
        }
    }

    private fun ScriptContext.bind(type: LocType, op: Int, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
            4 -> onOpLoc4(type) { action(it.loc) }
            5 -> onOpLoc5(type) { action(it.loc) }
        }
    }

    private suspend fun ProtectedAccess.loot(loc: BoundLocInfo, chest: ChestData.Chest) {
        if (player.thievingLvl < chest.level) {
            mes("You need a Thieving level of ${chest.level} to open this chest.")
            return
        }
        val readyAt = cooldowns[loc.coords]
        if (readyAt != null && mapClock < readyAt) {
            mes("The chest is empty.")
            return
        }
        if (inv.freeSpace() < chest.loot.count { it.always } + 1) {
            mes("You don't have enough inventory space.")
            return
        }
        mes("You search the chest for traps.")
        anim(seqs.human_pickuptable)
        delay(2)
        mes("You find a trap on the chest and disable it.")
        cooldowns[loc.coords] = mapClock + chest.respawnTicks
        giveLoot(chest.loot)
        statAdvance(stats.thieving, chest.xp * xpMods.get(player, stats.thieving))
        mes("You steal from the chest.")
    }

    private fun ProtectedAccess.springTrap() {
        mes("You have activated a trap on the chest.")
        queueHit(1, HitType.Typeless, maxOf(1, player.hitpoints / 10))
    }

    /** Always lines are always given; the rest is one weighted roll (remainder = nothing). */
    private fun ProtectedAccess.giveLoot(loot: List<Loot>) {
        for (entry in loot) if (entry.always) give(entry)
        val weighted = loot.filter { !it.always }
        if (weighted.isEmpty()) return
        val roll = random.of(PPM)
        var cumulative = 0L
        for (entry in weighted) {
            cumulative += entry.numerator.toLong() * PPM / entry.denominator
            if (roll < cumulative) {
                give(entry)
                return
            }
        }
    }

    private fun ProtectedAccess.give(entry: Loot) {
        val obj = ChestObjs.byName.getValue(entry.obj)
        val count = if (entry.max > entry.min) random.of(entry.min, entry.max) else entry.min
        invAddGathered(obj, count)
    }

    private companion object {
        const val PPM = 1_000_000
    }
}

internal object ChestData {
    class Chest(
        val title: String,
        val locs: List<String>,
        val level: Int,
        val xp: Double,
        val respawnTicks: Int,
        val loot: List<Loot>,
    )

    val chests: List<Chest> by lazy {
        val input =
            ChestData::class.java.getResourceAsStream("thieving_chests.tsv")
                ?: error("Thieving resource not found: thieving_chests.tsv")
        val out = ArrayList<Chest>()
        var header: List<String>? = null
        var loot = ArrayList<Loot>()
        fun flush() {
            val h = header ?: return
            out += Chest(h[1], h[2].split(','), h[3].toInt(), h[4].toDouble(), h[5].toInt(), loot)
            header = null
            loot = ArrayList()
        }
        for (raw in input.bufferedReader().use { it.readLines() }) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('\t')
            when (p[0]) {
                "chest" -> {
                    flush()
                    header = p
                }
                "loot" ->
                    loot +=
                        Loot(p[1], p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6] == "1")
                else -> error("Unknown chest line: $raw")
            }
        }
        flush()
        out
    }
}

internal object ChestLocs : LocReferences() {
    val byName: Map<String, LocType> =
        ChestData.chests.flatMap { it.locs }.distinct().associateWith { find(it) }
}

internal object ChestObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        ChestData.chests.flatMap { c -> c.loot.map { it.obj } }.distinct().associateWith { find(it) }
}
