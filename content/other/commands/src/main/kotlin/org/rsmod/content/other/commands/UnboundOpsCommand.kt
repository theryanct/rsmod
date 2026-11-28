package org.rsmod.content.other.commands

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import org.rsmod.api.player.events.interact.LocContentEvents
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.events.interact.LocDefaultEvents
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.events.interact.NpcContentEvents
import org.rsmod.api.player.events.interact.NpcDefaultEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.events.interact.NpcUnimplementedEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.ui.IfModalButton
import org.rsmod.api.player.ui.IfOverlayButton
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocZoneKey
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.map.ZoneLocMap
import org.rsmod.game.type.comp.ComponentTypeList
import org.rsmod.game.type.comp.UnpackedComponentType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.CoordGrid
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
 * An op no script binds but an [OpInterceptors] interceptor may claim (door / ladder fallbacks,
 * clue steps, ...: their `auditCovers`) or that `unbound-ops-notes.tsv` (this module's resources)
 * explains goes to `.data/logs/unbound-ops-covered.tsv` with who covers it, not the unbound list.
 * Notes are how world/skilling mark false positives: `kind  id-or-name  op  note`, op `*` = all,
 * or `region  x1,z1,x2,z2  *  note` for every op in a box (POH templates, instance copies).
 * A multiloc / multinpc child also counts as handled when its base is (the interaction code falls
 * back to the base type's script), and `*UnimplementedEvents` scripts count as handled.
 *
 * It also writes `.data/logs/unbound-ifbuttons.tsv`: every `UNHANDLED` button the server logged in
 * `.data/logs/gameplay*.log` (real clicks, with counts), then the components of the interfaces
 * open for the player running it (or, from a trigger file, for every online player) that have op
 * labels but no button script. The second part can
 * include buttons the client handles itself (`client_onop` = 1 means the component has a cs2 op
 * listener), so the clicked list is the stronger signal.
 */
class UnboundOpsCommand
@Inject
constructor(
    private val eventBus: EventBus,
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val locZones: LocZoneStorage,
    private val npcList: NpcList,
    private val componentTypes: ComponentTypeList,
    private val playerList: PlayerList,
) : PluginScript() {
    private val componentsByInterface: Map<Int, List<UnpackedComponentType>> by lazy {
        componentTypes.values.groupBy { it.interfaceId }
    }

    override fun ScriptContext.startup() {
        onCommand("unboundops", "Dump unhandled npc/loc ops to .data/logs/unbound-ops.tsv", ::dump)
    }

    private class Row(val kind: String, val id: Int, val name: String, val op: Int, val label: String) {
        var count = 0
        var sample = ""
        var coveredBy = ""
    }

    /** `kind<TAB>id-or-name<TAB>op` (op `opN` or `*`) -> note, from the notes resource. */
    private val notes: Map<String, String> by lazy { loadNotes() }

    private class Region(val x1: Int, val z1: Int, val x2: Int, val z2: Int, val note: String) {
        fun contains(c: CoordGrid): Boolean = c.x in x1..x2 && c.z in z1..z2
    }

    private val regions: List<Region> by lazy {
        notes.entries.mapNotNull { (key, note) ->
            val cols = key.split('\t')
            if (cols[0] != "region") return@mapNotNull null
            val n = cols[1].split(',').mapNotNull { it.trim().toIntOrNull() }
            if (n.size != 4) return@mapNotNull null
            Region(minOf(n[0], n[2]), minOf(n[1], n[3]), maxOf(n[0], n[2]), maxOf(n[1], n[3]), note)
        }
    }

    private fun regionNote(coords: CoordGrid): String? = regions.firstOrNull { it.contains(coords) }?.note

    private fun loadNotes(): Map<String, String> {
        val stream = javaClass.getResourceAsStream("unbound-ops-notes.tsv") ?: return emptyMap()
        val out = HashMap<String, String>()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 4) continue
                val key = "${cols[0].trim().lowercase()}\t${cols[1].trim().lowercase()}"
                out["$key\t${cols[2].trim().lowercase()}"] = cols[3].trim()
            }
        }
        return out
    }

    private fun note(kind: String, id: Int, name: String, op: Int): String? {
        for (key in listOf(id.toString(), name.lowercase())) {
            notes["$kind\t$key\top$op"]?.let { return it }
            notes["$kind\t$key\t*"]?.let { return it }
        }
        return null
    }

    private fun dump(cheat: Cheat) {
        cheat.player.mes(dumpAll(cheat.player))
    }

    /**
     * Writes both TSVs and returns the summary line. [player] adds the interfaces open for that
     * player to the button TSV; without one (e.g. a devops trigger file) only the logged clicks
     * are listed.
     */
    fun dumpAll(player: Player? = null): String {
        val rows = LinkedHashMap<Long, Row>()
        val covered = LinkedHashMap<Long, Row>()
        fun add(kind: String, id: Int, name: String, op: Int, label: String, coords: String, by: String?) {
            val key = (if (kind == "npc") 1L shl 40 else 0L) or (id.toLong() shl 3) or op.toLong()
            val cover = by ?: note(kind, id, name, op)?.let { "note: $it" }
            val into = if (cover != null && key !in rows) covered else rows
            val row = into.getOrPut(key) { Row(kind, id, name, op, label) }
            if (row.count++ == 0) row.sample = coords
            if (cover != null && row.coveredBy.isEmpty()) row.coveredBy = cover
        }

        var locs = 0
        for (map in listOf(locZones.mapLocs, locZones.spawnedLocs)) {
            forEachLoc(map) { entity, coords ->
                locs++
                val base = locTypes.getOrDefault(entity.id, null) ?: return@forEachLoc
                val sample = "${coords.x},${coords.z},${coords.level}"
                val region = regionNote(coords)?.let { "note: $it" }
                for (type in locVariants(base)) {
                    for (op in 1..OPS) {
                        val label = type.op.getOrNull(op - 1) ?: continue
                        if (label.isBlank() || label.equals("hidden", ignoreCase = true)) continue
                        if (locHandled(type, op) || (type !== base && locHandled(base, op))) continue
                        val by = region ?: OpInterceptors.auditLoc(type, entity.shape, coords, op)
                        add("loc", type.id, type.name, op, label, sample, by)
                    }
                }
            }
        }

        var npcs = 0
        for (npc in npcList) {
            npcs++
            val coords = npc.coords.let { "${it.x},${it.z},${it.level}" }
            val region = regionNote(npc.coords)?.let { "note: $it" }
            for (type in npcVariants(npc.type)) {
                for (op in 1..OPS) {
                    val label = type.op.getOrNull(op - 1) ?: continue
                    if (label.isBlank() || label.equals("hidden", ignoreCase = true)) continue
                    if (npcHandled(type, op) || (type !== npc.type && npcHandled(npc.type, op))) continue
                    val by = region ?: OpInterceptors.auditNpc(type, npc.coords, op)
                    add("npc", type.id, type.name, op, label, coords, by)
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
        val coveredOut = out.resolveSibling("unbound-ops-covered.tsv")
        Files.newBufferedWriter(coveredOut).use { w ->
            w.write("type\tid\tname\top\tlabel\tspawn_count\tcovered_by\tsample_coord\n")
            val byCover = covered.values.sortedWith(compareBy<Row> { it.coveredBy }.thenByDescending { it.count })
            for (r in byCover) {
                w.write("${r.kind}\t${r.id}\t${r.name}\top${r.op}\t${r.label}\t${r.count}\t${r.coveredBy}\t${r.sample}\n")
            }
        }
        val summary =
            "unboundops: ${sorted.size} unhandled ops (${sorted.count { it.kind == "loc" }} loc, " +
                "${sorted.count { it.kind == "npc" }} npc) across $locs locs and $npcs npcs -> $out " +
                "(${covered.size} covered by fallbacks/claims/notes -> $coveredOut)"
        val buttons = dumpButtons(player)
        val all = "$summary; $buttons interface button rows -> .data/logs/unbound-ifbuttons.tsv"
        logger.info { all }
        return all
    }

    private fun dumpButtons(player: Player?): Int {
        val clicked = HashMap<String, Int>()
        val lastSeen = HashMap<String, String>()
        val logs = Path.of(".data", "logs")
        if (Files.isDirectory(logs)) {
            Files.list(logs).use { files ->
                files
                    .filter { it.fileName.toString().matches(GAMEPLAY_LOG) }
                    .sorted()
                    .forEach { file ->
                        Files.readAllLines(file).forEach { line ->
                            val m = UNHANDLED.find(line) ?: return@forEach
                            val key = "${m.groupValues[2]}\t${m.groupValues[3]}\top${m.groupValues[4]}"
                            clicked[key] = (clicked[key] ?: 0) + 1
                            lastSeen[key] = m.groupValues[1]
                        }
                    }
            }
        }
        var rows = 0
        val out = logs.resolve("unbound-ifbuttons.tsv")
        Files.createDirectories(logs)
        Files.newBufferedWriter(out).use { w ->
            w.write("source\tkind\tcomponent\top\tlabel\tcount\tlast_seen\tclient_onop\n")
            for ((key, count) in clicked.entries.sortedByDescending { it.value }) {
                w.write("clicked\t$key\t\t$count\t${lastSeen[key]}\t\n")
                rows++
            }
            val players = if (player != null) listOf(player) else playerList.toList()
            val open =
                players.flatMap { p ->
                    p.ui.overlays.values.map { it to true } + p.ui.modals.values.map { it to false }
                }
            for ((interfaceId, overlay) in open.distinct()) {
                for (comp in componentsByInterface[interfaceId].orEmpty()) {
                    val handled =
                        if (overlay) eventBus.keyed[IfOverlayButton::class.java, comp.packed] != null
                        else eventBus.contains(IfModalButton::class.java, comp.packed)
                    if (handled) continue
                    for ((i, label) in comp.op.withIndex()) {
                        if (label.isBlank()) continue
                        val name = comp.internalName ?: "${comp.interfaceId}:${comp.packed and 0xFFFF}"
                        val kind = if (overlay) "overlay-button" else "modal-button"
                        val onop = if (comp.onOp != null) 1 else 0
                        w.write("open\t$kind\t$name\top${i + 1}\t$label\t\t\t$onop\n")
                        rows++
                    }
                }
            }
        }
        return rows
    }

    /** Every loc in [map]: brute force over the zone grid (ZoneLocMap has no iterator). */
    private fun forEachLoc(map: ZoneLocMap, action: (LocEntity, CoordGrid) -> Unit) {
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
                        action(LocEntity(entry.intValue), CoordGrid(x, z, level))
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
            has(LOC_UNIMPLEMENTED[i], type.id) ||
            has(LOC_DEFAULT_OP[i], DEFAULT_KEY) ||
            has(LOC_DEFAULT_AP[i], DEFAULT_KEY)
    }

    private fun npcHandled(type: UnpackedNpcType, op: Int): Boolean {
        val i = op - 1
        return has(NPC_OP[i], type.id) ||
            has(NPC_AP[i], type.id) ||
            has(NPC_CONTENT_OP[i], type.contentGroup) ||
            has(NPC_CONTENT_AP[i], type.contentGroup) ||
            has(NPC_UNIMPLEMENTED[i], type.id) ||
            has(NPC_DEFAULT_OP[i], DEFAULT_KEY) ||
            has(NPC_DEFAULT_AP[i], DEFAULT_KEY)
    }

    private companion object {
        const val OPS = 5
        const val LEVELS = 4
        const val ZONES = 2048
        const val ZONE_SIZE = 8
        const val DEFAULT_KEY = -1

        /** gameplay.log and its dated rolls (not gameplay-problems, which repeats lines). */
        val GAMEPLAY_LOG = Regex("""gameplay(\.[0-9-]+\.[0-9]+)?\.log""")
        /** `<time> UNHANDLED player=<p> <kind>=<component> sub=<n> op=<n>` (GameplayLog). */
        val UNHANDLED =
            Regex(
                """^(\S+ \S+) UNHANDLED player=\S+ ((?:overlay|modal)-button)=(\S+) sub=\S+ op=(\d+)"""
            )

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
        val LOC_UNIMPLEMENTED =
            listOf(
                LocUnimplementedEvents.Op1::class.java,
                LocUnimplementedEvents.Op2::class.java,
                LocUnimplementedEvents.Op3::class.java,
                LocUnimplementedEvents.Op4::class.java,
                LocUnimplementedEvents.Op5::class.java,
            )
        val NPC_UNIMPLEMENTED =
            listOf(
                NpcUnimplementedEvents.Op1::class.java,
                NpcUnimplementedEvents.Op2::class.java,
                NpcUnimplementedEvents.Op3::class.java,
                NpcUnimplementedEvents.Op4::class.java,
                NpcUnimplementedEvents.Op5::class.java,
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
