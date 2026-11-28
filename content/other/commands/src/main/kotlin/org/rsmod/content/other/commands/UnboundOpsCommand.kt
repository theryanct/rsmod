package org.rsmod.content.other.commands

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import org.rsmod.api.player.events.interact.LocContentEvents
import org.rsmod.api.player.events.interact.LocDefaultEvents
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.NpcContentEvents
import org.rsmod.api.player.events.interact.NpcDefaultEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.output.mes
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.NpcList
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocZoneKey
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.map.ZoneLocMap
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * `::unboundops` (manager's audit request): every op1-op5 with a label on a spawned npc or a map /
 * spawned loc that no script handles (the "Nothing interesting happens." ops), written to
 * `.data/logs/unbound-ops.tsv`, most common first.
 *
 * "Handled" is asked of the live [EventBus] the same way the interaction code asks it: a type
 * script, a content-group script, or a default script for that op (op or ap). A multiloc /
 * multinpc's children are checked as their own rows (any child can be the one a player sees).
 * Quest-state [org.rsmod.api.player.interact.OpInterceptors] claims are per player and not
 * counted, so an op only an interceptor handles is listed.
 */
class UnboundOpsCommand
@Inject
constructor(
    private val eventBus: EventBus,
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val locZones: LocZoneStorage,
    private val npcList: NpcList,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("unboundops", "Dump unhandled npc/loc ops to .data/logs/unbound-ops.tsv", ::dump)
    }

    private class Row(val kind: String, val id: Int, val name: String, val op: Int, val label: String) {
        var count = 0
        var sample = ""
    }

    private fun dump(cheat: Cheat) {
        val rows = LinkedHashMap<Long, Row>()
        fun add(kind: String, id: Int, name: String, op: Int, label: String, coords: String) {
            val key = (if (kind == "npc") 1L shl 40 else 0L) or (id.toLong() shl 3) or op.toLong()
            val row = rows.getOrPut(key) { Row(kind, id, name, op, label) }
            if (row.count++ == 0) row.sample = coords
        }

        var locs = 0
        for (map in listOf(locZones.mapLocs, locZones.spawnedLocs)) {
            forEachLoc(map) { entity, coords ->
                locs++
                val base = locTypes.getOrDefault(entity.id, null) ?: return@forEachLoc
                for (type in locVariants(base)) {
                    for (op in 1..OPS) {
                        val label = type.op.getOrNull(op - 1) ?: continue
                        if (label.isBlank() || label.equals("hidden", ignoreCase = true)) continue
                        if (!locHandled(type, op)) add("loc", type.id, type.name, op, label, coords)
                    }
                }
            }
        }

        var npcs = 0
        for (npc in npcList) {
            npcs++
            val coords = npc.coords.let { "${it.x},${it.z},${it.level}" }
            for (type in npcVariants(npc.type)) {
                for (op in 1..OPS) {
                    val label = type.op.getOrNull(op - 1) ?: continue
                    if (label.isBlank() || label.equals("hidden", ignoreCase = true)) continue
                    if (!npcHandled(type, op)) add("npc", type.id, type.name, op, label, coords)
                }
            }
        }

        val labelCounts = rows.values.groupingBy { it.label.lowercase() }.eachCount()
        val sorted =
            rows.values.sortedWith(
                compareByDescending<Row> { it.count }
                    .thenByDescending { labelCounts[it.label.lowercase()] ?: 0 }
                    .thenBy { it.kind }
                    .thenBy { it.id }
            )
        val out = Path.of(".data", "logs", "unbound-ops.tsv")
        Files.createDirectories(out.parent)
        Files.newBufferedWriter(out).use { w ->
            w.write("type\tid\tname\top\tlabel\tspawn_count\tlabel_rows\tsample_coord\n")
            for (r in sorted) {
                val labelRows = labelCounts[r.label.lowercase()] ?: 0
                w.write("${r.kind}\t${r.id}\t${r.name}\top${r.op}\t${r.label}\t${r.count}\t$labelRows\t${r.sample}\n")
            }
        }
        val summary =
            "unboundops: ${sorted.size} unhandled ops (${sorted.count { it.kind == "loc" }} loc, " +
                "${sorted.count { it.kind == "npc" }} npc) across $locs locs and $npcs npcs -> $out"
        logger.info { summary }
        cheat.player.mes(summary)
    }

    /** Every loc in [map]: brute force over the zone grid (ZoneLocMap has no iterator). */
    private fun forEachLoc(map: ZoneLocMap, action: (LocEntity, String) -> Unit) {
        for (level in 0 until LEVELS) {
            for (zx in 0 until ZONES) {
                for (zz in 0 until ZONES) {
                    val zone = ZoneKey(zx, zz, level)
                    val locs = map[zone] ?: continue
                    val iter = locs.byte2IntEntrySet().iterator()
                    while (iter.hasNext()) {
                        val entry = iter.next()
                        val key = LocZoneKey(entry.byteKey)
                        val x = zx * ZONE_SIZE + key.x
                        val z = zz * ZONE_SIZE + key.z
                        action(LocEntity(entry.intValue), "$x,$z,$level")
                    }
                }
            }
        }
    }

    private fun locVariants(type: UnpackedLocType): List<UnpackedLocType> {
        if (type.multiLoc.isEmpty() && type.multiLocDefault <= 0) return listOf(type)
        val ids = type.multiLoc.map { it.toInt() and 0xFFFF } + type.multiLocDefault
        return listOf(type) + ids.distinct().mapNotNull { locTypes.getOrDefault(it, null) }
    }

    private fun npcVariants(type: UnpackedNpcType): List<UnpackedNpcType> {
        if (type.multiNpc.isEmpty() && type.multiNpcDefault <= 0) return listOf(type)
        val ids = type.multiNpc.map { it.toInt() and 0xFFFF } + type.multiNpcDefault
        return listOf(type) + ids.distinct().mapNotNull { npcTypes.getOrDefault(it, null) }
    }

    private fun has(type: Class<out SuspendEvent<*>>, key: Int): Boolean =
        @Suppress("UNCHECKED_CAST") eventBus.contains(type as Class<SuspendEvent<*>>, key)

    private fun locHandled(type: UnpackedLocType, op: Int): Boolean {
        val i = op - 1
        return has(LOC_OP[i], type.id) ||
            has(LOC_AP[i], type.id) ||
            has(LOC_CONTENT_OP[i], type.contentGroup) ||
            has(LOC_CONTENT_AP[i], type.contentGroup) ||
            has(LOC_DEFAULT_OP[i], DEFAULT_KEY) ||
            has(LOC_DEFAULT_AP[i], DEFAULT_KEY)
    }

    private fun npcHandled(type: UnpackedNpcType, op: Int): Boolean {
        val i = op - 1
        return has(NPC_OP[i], type.id) ||
            has(NPC_AP[i], type.id) ||
            has(NPC_CONTENT_OP[i], type.contentGroup) ||
            has(NPC_CONTENT_AP[i], type.contentGroup) ||
            has(NPC_DEFAULT_OP[i], DEFAULT_KEY) ||
            has(NPC_DEFAULT_AP[i], DEFAULT_KEY)
    }

    private companion object {
        const val OPS = 5
        const val LEVELS = 4
        const val ZONES = 2048
        const val ZONE_SIZE = 8
        const val DEFAULT_KEY = -1

        val LOC_OP =
            listOf(
                LocEvents.Op1::class.java,
                LocEvents.Op2::class.java,
                LocEvents.Op3::class.java,
                LocEvents.Op4::class.java,
                LocEvents.Op5::class.java,
            )
        val LOC_AP =
            listOf(
                LocEvents.Ap1::class.java,
                LocEvents.Ap2::class.java,
                LocEvents.Ap3::class.java,
                LocEvents.Ap4::class.java,
                LocEvents.Ap5::class.java,
            )
        val LOC_CONTENT_OP =
            listOf(
                LocContentEvents.Op1::class.java,
                LocContentEvents.Op2::class.java,
                LocContentEvents.Op3::class.java,
                LocContentEvents.Op4::class.java,
                LocContentEvents.Op5::class.java,
            )
        val LOC_CONTENT_AP =
            listOf(
                LocContentEvents.Ap1::class.java,
                LocContentEvents.Ap2::class.java,
                LocContentEvents.Ap3::class.java,
                LocContentEvents.Ap4::class.java,
                LocContentEvents.Ap5::class.java,
            )
        val LOC_DEFAULT_OP =
            listOf(
                LocDefaultEvents.Op1::class.java,
                LocDefaultEvents.Op2::class.java,
                LocDefaultEvents.Op3::class.java,
                LocDefaultEvents.Op4::class.java,
                LocDefaultEvents.Op5::class.java,
            )
        val LOC_DEFAULT_AP =
            listOf(
                LocDefaultEvents.Ap1::class.java,
                LocDefaultEvents.Ap2::class.java,
                LocDefaultEvents.Ap3::class.java,
                LocDefaultEvents.Ap4::class.java,
                LocDefaultEvents.Ap5::class.java,
            )
        val NPC_OP =
            listOf(
                NpcEvents.Op1::class.java,
                NpcEvents.Op2::class.java,
                NpcEvents.Op3::class.java,
                NpcEvents.Op4::class.java,
                NpcEvents.Op5::class.java,
            )
        val NPC_AP =
            listOf(
                NpcEvents.Ap1::class.java,
                NpcEvents.Ap2::class.java,
                NpcEvents.Ap3::class.java,
                NpcEvents.Ap4::class.java,
                NpcEvents.Ap5::class.java,
            )
        val NPC_CONTENT_OP =
            listOf(
                NpcContentEvents.Op1::class.java,
                NpcContentEvents.Op2::class.java,
                NpcContentEvents.Op3::class.java,
                NpcContentEvents.Op4::class.java,
                NpcContentEvents.Op5::class.java,
            )
        val NPC_CONTENT_AP =
            listOf(
                NpcContentEvents.Ap1::class.java,
                NpcContentEvents.Ap2::class.java,
                NpcContentEvents.Ap3::class.java,
                NpcContentEvents.Ap4::class.java,
                NpcContentEvents.Ap5::class.java,
            )
        val NPC_DEFAULT_OP =
            listOf(
                NpcDefaultEvents.Op1::class.java,
                NpcDefaultEvents.Op2::class.java,
                NpcDefaultEvents.Op3::class.java,
                NpcDefaultEvents.Op4::class.java,
                NpcDefaultEvents.Op5::class.java,
            )
        val NPC_DEFAULT_AP =
            listOf(
                NpcDefaultEvents.Ap1::class.java,
                NpcDefaultEvents.Ap2::class.java,
                NpcDefaultEvents.Ap3::class.java,
                NpcDefaultEvents.Ap4::class.java,
                NpcDefaultEvents.Ap5::class.java,
            )
    }
}
