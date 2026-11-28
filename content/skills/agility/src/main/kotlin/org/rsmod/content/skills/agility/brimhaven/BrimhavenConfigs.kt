package org.rsmod.content.skills.agility.brimhaven

import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.type.loc.LocType
import org.rsmod.map.CoordGrid

/** Fail tiers (Wiki: easy/medium/hard obstacles share a fail rate per tier; nothing fails at 99). */
internal enum class ArenaTier(val maxFail: Double, val damage: Int) {
    Easy(0.10, 2),
    Medium(0.15, 3),
    Hard(0.20, 4),
}

/** Per-family stats (Wiki obstacle table: level, xp, speed in ticks). */
internal class ArenaFamily(
    val level: Int,
    val xp: Double,
    val ticks: Int,
    val anim: String,
    val tier: ArenaTier,
)

internal class ArenaCross(val loc: String, val tile: CoordGrid, val family: String, val landing: CoordGrid, val fall: CoordGrid)

internal class ArenaWall(val loc: String, val tile: CoordGrid, val a: CoordGrid, val b: CoordGrid)

internal class ArenaClimb(val loc: String, val tile: CoordGrid, val dest: CoordGrid)

internal class ArenaDispenser(val loc: String, val tile: CoordGrid)

internal class ArenaTrap(val id: Int, val kind: String, val tile: CoordGrid, val tiles: List<CoordGrid>)

/** Rows of `brimhaven.tsv` (see its header). */
internal object BrimhavenData {
    class Parsed(
        val crosses: List<ArenaCross>,
        val walls: List<ArenaWall>,
        val climbs: List<ArenaClimb>,
        val dispensers: List<ArenaDispenser>,
        val traps: List<ArenaTrap>,
    )

    val parsed: Parsed by lazy { load() }

    private fun coords(text: String): CoordGrid {
        val p = text.split(',')
        return CoordGrid(p[0].toInt(), p[1].toInt(), p[2].toInt())
    }

    private fun load(): Parsed {
        val input =
            BrimhavenData::class.java.getResourceAsStream("brimhaven.tsv")
                ?: error("Agility resource not found: brimhaven.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val crosses = ArrayList<ArenaCross>()
        val walls = ArrayList<ArenaWall>()
        val climbs = ArrayList<ArenaClimb>()
        val dispensers = ArrayList<ArenaDispenser>()
        val traps = ArrayList<ArenaTrap>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            when (p[0]) {
                "cross" -> crosses.add(ArenaCross(p[1], coords(p[2]), p[3], coords(p[4]), coords(p[5])))
                "wall" -> walls.add(ArenaWall(p[1], coords(p[2]), coords(p[3]), coords(p[4])))
                "climb" -> climbs.add(ArenaClimb(p[1], coords(p[2]), coords(p[3])))
                "dispenser" -> dispensers.add(ArenaDispenser(p[1], coords(p[2])))
                "trap" -> {
                    val tile = coords(p[2])
                    val tiles =
                        p[3].split(';').map {
                            val xz = it.split(',')
                            CoordGrid(xz[0].toInt(), xz[1].toInt(), tile.level)
                        }
                    traps.add(ArenaTrap(traps.size, p[1], tile, tiles))
                }
                else -> error("Unknown brimhaven line: $raw")
            }
        }
        return Parsed(crosses, walls, climbs, dispensers, traps)
    }
}

internal object BrimhavenTuning {
    const val ENTRY_FEE = 200
    /** Wiki: a new dispenser activates every minute. */
    const val ROTATE_TICKS = 100
    const val LOOP_TICKS = 1
    /** Wiki: 30 xp per 10 Agility levels (current level, boosts count). */
    const val TAG_XP_PER_10 = 30
    /** Wiki (May 2024): 345 xp per ticket at Pirate Jackie. */
    const val TICKET_XP = 345.0
    /**
     * Wiki changelog: the 9 May 2024 hotfix made tickets need two dispensers in a row, but the
     * dispenser page does not mention it. Off: every timely tag pays.
     */
    val REQUIRE_CONSECUTIVE = false
    /** Wiki: obstacles cannot fail at 99 (and any plank is safe at 99). */
    const val NO_FAIL_LEVEL = 99
    /** Wiki: pressure pads pay twice in a row, then nothing for 8 ticks. */
    const val PAD_STREAK = 2
    const val PAD_COOLDOWN = 8
    /** Ticks off the arena before the run is dropped (teleports, death). */
    const val OFF_ARENA_TICKS = 5
    const val LAND_RADIUS = 2
    /** Hint arrow height over the active dispenser (guess). */
    const val HINT_HEIGHT = 100

    val ARRIVAL = CoordGrid(2805, 9589, 3)
    /** East of the entrance ladder in the hut (ladder forceapproach east). */
    val SURFACE = CoordGrid(2810, 3194, 0)
    val ARENA_X = 2755..2811
    val ARENA_Z = 9540..9596

    val families: Map<String, ArenaFamily> =
        mapOf(
            "swing" to ArenaFamily(1, 20.0, 4, "human_ropeswing_long", ArenaTier.Easy),
            "plank" to ArenaFamily(1, 6.0, 9, "human_walk_logbalance", ArenaTier.Easy),
            "rope" to ArenaFamily(1, 10.0, 9, "human_walk_logbalance", ArenaTier.Easy),
            "log" to ArenaFamily(1, 12.0, 9, "human_walk_logbalance", ArenaTier.Easy),
            "ledge" to ArenaFamily(1, 16.0, 9, "human_walk_sidestepl", ArenaTier.Easy),
            "bars" to ArenaFamily(1, 14.0, 13, "human_monkeybars_walk", ArenaTier.Easy),
            "pillar" to ArenaFamily(1, 18.0, 9, "human_jump_stones", ArenaTier.Easy),
            "hand" to ArenaFamily(20, 22.0, 10, "human_walk_sidestepl", ArenaTier.Medium),
        )
    val WALL = ArenaFamily(1, 8.0, 3, "human_walk_style", ArenaTier.Easy)

    /** Walk-over traps: level, xp, tier. Blades (timed floor blades) give no xp. */
    val traps: Map<String, ArenaFamily> =
        mapOf(
            "pad" to ArenaFamily(20, 26.0, 0, "", ArenaTier.Medium),
            "spikes" to ArenaFamily(20, 24.0, 0, "", ArenaTier.Medium),
            "blades" to ArenaFamily(40, 28.0, 0, "", ArenaTier.Hard),
            "blade" to ArenaFamily(1, 0.0, 0, "", ArenaTier.Easy),
        )

    /** Agility Arena Store voucher prices (Wiki). */
    const val TOADFLAX = 3
    const val SNAPDRAGON = 10
    const val PIRATE_HOOK = 800
    const val GRACEFUL = 250
    const val AMYLASE = 60
    /** Deviation (as at Grace): the amylase pack is handed over as the 100 crystals it holds. */
    const val AMYLASE_COUNT = 100
}

internal object BrimhavenLocs : LocReferences() {
    private val data = BrimhavenData.parsed
    val byName: Map<String, LocType> =
        (data.crosses.map { it.loc } + data.walls.map { it.loc } + data.climbs.map { it.loc } + data.dispensers.map { it.loc })
            .distinct()
            .associateWith { find(it) }
    val ladder_down = find("agilityarena_ladderdown")
    val ladder_up = find("agilityarena_ladderup")
}

internal object BrimhavenSeqs : SeqReferences() {
    val byName =
        (BrimhavenTuning.families.values.map { it.anim } + BrimhavenTuning.WALL.anim)
            .distinct()
            .associateWith { find(it) }
    val climb = find("human_reachforladder")
    val stumble = find("human_walk_logbalance_stumble")
}

internal object BrimhavenNpcs : NpcReferences() {
    val izzy = find("agilityarena_clerk")
    val jackie_2ops = find("agilityarena_tickettrader_2ops")
    val jackie_3ops = find("agilityarena_tickettrader_3ops")
}

internal object BrimhavenObjs : ObjReferences() {
    val coins = find("coins")
    val ticket = find("agilityarena_ticket_new")
    val voucher = find("agilityarena_voucher")
    val toadflax = find("cert_toadflax")
    val snapdragon = find("cert_snapdragon")
    val pirate_hook = find("piratehook")
    val amylase = find("amylase")
    val graceful = listOf(
        find("graceful_hood"),
        find("graceful_top"),
        find("graceful_legs"),
        find("graceful_gloves"),
        find("graceful_boots"),
        find("graceful_cape"),
    )
    /** Wiki "Graceful hood (Agility Arena)" is id 21061 = graceful_hood_skillcapecolour. */
    val graceful_arena = listOf(
        find("graceful_hood_skillcapecolour"),
        find("graceful_top_skillcapecolour"),
        find("graceful_legs_skillcapecolour"),
        find("graceful_gloves_skillcapecolour"),
        find("graceful_boots_skillcapecolour"),
        find("graceful_cape_skillcapecolour"),
    )
}

internal object BrimhavenVarBits : VarBitReferences() {
    val can_enter = find("agilityarena_canenter")
}

internal object BrimhavenTimers : TimerReferences() {
    /** Custom timer (65507, skilling range). */
    val tick = find("brimhaven_arena_tick")
}
