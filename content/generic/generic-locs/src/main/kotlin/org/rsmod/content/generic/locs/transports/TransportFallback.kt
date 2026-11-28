package org.rsmod.content.generic.locs.transports

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Loc transports from data: caves, tunnels, ladders, stairs, gangplanks, rocks, crevices... whose
 * clicked op nothing handles move the player to the destination the RuneLite Shortest Path
 * plugin records for that loc and op (resources `loc_transports.tsv`, built by
 * tools/travel/transports_to_tsv.py; quest / item / varbit gated rows are left out, skill levels
 * are checked). Of several rows for one loc the one whose origin is nearest the player wins.
 *
 * "Open"/"Close" stay with the door and container fallbacks (they swap the loc instead). Runs as a
 * guarded [OpInterceptors.LocOp] like the door fallback, before the ladder fallback's guesswork
 * (which defers to these rows). Each use logs a `TRANSPORT` line.
 */
class TransportFallback
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val statTypes: StatTypeList,
    private val locInteractions: LocInteractions,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        logger.info { "Loc transports: ${LocTransports.size} rows for ${LocTransports.keys} loc ops." }
        OpInterceptors.register(Interceptor())
    }

    private inner class Interceptor : OpInterceptors.LocOp {
        override val auditName: String = "loc transport"

        override fun auditCovers(type: UnpackedLocType, shape: Int, coords: CoordGrid, op: Int): Boolean =
            LocTransports.near(type.id, type.op.getOrNull(op - 1), coords) != null

        override fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean {
            val type = locTypes[loc]
            if (LocTransports.near(type.id, type.op.getOrNull(op.slot - 1), loc.coords) == null) return false
            return locInteractions.opTrigger(player, loc, op, type) == null &&
                locInteractions.apTrigger(player, loc, op, type) == null
        }

        override suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp) {
            val type = locTypes[loc]
            val label = type.op.getOrNull(op.slot - 1)
            val row = LocTransports.best(type.id, label, loc.coords, player.coords) ?: return
            for ((level, skill) in row.skills) {
                val stat = statTypes.types.values.firstOrNull { it.displayName.equals(skill, ignoreCase = true) }
                if (stat != null && stat(stat) < level) {
                    mes("You need a ${stat.displayName} level of $level to do that.")
                    return
                }
            }
            logger.info { "TRANSPORT ${type.internalName ?: type.id} '$label' ${loc.coords}: ${player.coords} -> ${row.dest}" }
            delay(1)
            telejump(row.dest)
        }
    }
}

/** One loc transport row: stand near [origin], arrive at [dest]. */
internal class LocTransport(val origin: CoordGrid, val dest: CoordGrid, val skills: List<Pair<Int, String>>)

/** `loc_transports.tsv`, keyed by loc id and lower-case op label. */
internal object LocTransports {
    /** A loc's row must have its origin this close to the loc (rows are per origin tile). */
    private const val ORIGIN_RADIUS = 6

    private val rows: Map<String, List<LocTransport>> by lazy { load() }

    val size: Int
        get() = rows.values.sumOf { it.size }

    val keys: Int
        get() = rows.size

    private fun key(id: Int, label: String): String = "$id:${label.lowercase()}"

    private fun usable(label: String?): Boolean =
        label != null && !label.equals("Open", ignoreCase = true) && !label.equals("Close", ignoreCase = true)

    fun has(id: Int, label: String?): Boolean = usable(label) && rows.containsKey(key(id, label!!))

    /** Some row for this loc op whose origin is near the loc at [at], or null. */
    fun near(id: Int, label: String?, at: CoordGrid): LocTransport? {
        if (!usable(label)) return null
        val list = rows[key(id, label!!)] ?: return null
        return list.firstOrNull { it.origin.level == at.level && it.origin.chebyshevDistance(at) <= ORIGIN_RADIUS }
    }

    /** The row near the loc at [at] whose origin is closest to the player. */
    fun best(id: Int, label: String?, at: CoordGrid, player: CoordGrid): LocTransport? {
        if (!usable(label)) return null
        val list = rows[key(id, label!!)] ?: return null
        return list
            .filter { it.origin.level == at.level && it.origin.chebyshevDistance(at) <= ORIGIN_RADIUS }
            .minByOrNull { it.origin.chebyshevDistance(player) }
    }

    private fun load(): Map<String, List<LocTransport>> {
        val out = HashMap<String, MutableList<LocTransport>>()
        val stream = LocTransports::class.java.getResourceAsStream("loc_transports.tsv") ?: return emptyMap()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 9) continue
                val id = p[0].toIntOrNull() ?: continue
                val n = p.subList(2, 8).map { it.toIntOrNull() }
                if (n.any { it == null }) continue
                val origin = CoordGrid(n[0]!!, n[1]!!, n[2]!!)
                val dest = CoordGrid(n[3]!!, n[4]!!, n[5]!!)
                val skills =
                    p[8].split(';').filter { it.isNotBlank() }.mapNotNull { req ->
                        val parts = req.trim().split(' ')
                        parts.getOrNull(0)?.toIntOrNull()?.let { it to parts.drop(1).joinToString(" ") }
                    }
                out.getOrPut(key(id, p[1])) { ArrayList() } += LocTransport(origin, dest, skills)
            }
        }
        return out
    }
}
