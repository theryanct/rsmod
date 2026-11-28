package org.rsmod.content.other.trails

import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.dbrow.UnpackedDbRowType
import org.rsmod.map.CoordGrid

/** Clue tiers; [difficulty] is the cluehelper tables' `difficulty` column. */
internal enum class ClueTier(val key: String, val difficulty: Int, val steps: IntRange) {
    Beginner("beginner", 0, 1..3),
    Easy("easy", 1, 2..4),
    Medium("medium", 2, 3..5),
    Hard("hard", 3, 4..6),
    Elite("elite", 4, 5..7),
    Master("master", 5, 6..8);

    val display: String
        get() = name.lowercase()

    companion object {
        fun byKey(key: String): ClueTier? = entries.firstOrNull { it.key == key }

        fun byDifficulty(difficulty: Int): ClueTier? =
            entries.firstOrNull { it.difficulty == difficulty }
    }
}

internal enum class ClueKind(val table: String) {
    Anagram("cluehelper_clue_anagram"),
    Cryptic("cluehelper_clue_cryptic"),
    Emote("cluehelper_clue_emote"),
    Map("cluehelper_clue_map"),
    HotCold("cluehelper_clue_hotcold"),
    /** Charlie the Tramp's item requests (beginner). */
    SkillChallenge("cluehelper_clue_skillchallenge"),
    Music("cluehelper_clue_music"),
    Cipher("cluehelper_clue_cipher"),
    Coordinate("cluehelper_clue_coordinate"),
}

/** Where a step is solved (the cluehelper_target_* row it points at). */
internal sealed class ClueTarget {
    data class Npc(val npcs: Set<Int>, val coord: CoordGrid, val description: String) :
        ClueTarget()

    data class Loc(val locs: Set<Int>, val coord: CoordGrid, val description: String) :
        ClueTarget()

    /** Dig here (map, coordinate, cryptic "dig") or perform an emote here. */
    data class Coord(val coord: CoordGrid, val description: String) : ClueTarget()

    data class Zone(val sw: CoordGrid, val ne: CoordGrid, val description: String) :
        ClueTarget() {
        fun contains(coords: CoordGrid): Boolean =
            coords.level == sw.level &&
                coords.x in sw.x..ne.x &&
                coords.z in sw.z..ne.z
    }

    /** Kill one of [npcs] for [key], then open [locs] near [coord] with it. */
    data class Key(
        val locs: Set<Int>,
        val coord: CoordGrid,
        val npcs: Set<Int>,
        val key: Int,
        val description: String,
    ) : ClueTarget()

    /** A target kind not implemented yet (kill steps of higher tiers). */
    data object Unsupported : ClueTarget()
}

internal class ClueStep(
    /** The step's cluehelper dbrow id: what clue objs' param 623 and the beginner varp hold. */
    val row: Int,
    val kind: ClueKind,
    val tier: ClueTier,
    val text: String,
    val target: ClueTarget,
    /** Emote steps: the emote list slots (enum `emote_names`) to perform, in order. */
    val emotes: List<Int> = emptyList(),
    /** Emote steps: objs that must be worn (one per slot), empty if none. */
    val outfit: List<Int> = emptyList(),
    /** Easy+ emote steps: the STASH unit loc and its coord. */
    val stashLoc: Int? = null,
    val stashCoord: CoordGrid? = null,
    /** Music steps: the `music` dbrow of the track to play (text = where it's unlocked). */
    val music: Int? = null,
    /** The npc's challenge question and its numeric answer, if any. */
    val question: Pair<String, Int>? = null,
    /** The npc hands out a puzzle box / light box first (not implemented yet). */
    val boxChallenge: Boolean = false,
    /** A fight before the step completes (double agents, wizards; not implemented yet). */
    val combat: Boolean = false,
) {
    /** Implemented: a supported target and no box challenge or fight. */
    val isSupported: Boolean
        get() = target != ClueTarget.Unsupported && !boxChallenge && !combat
}

/**
 * Every clue step, read at startup from the 243 cache's clue-helper db tables (Jagex's own data:
 * texts, targets, emotes, outfits, STASH units), keyed by dbrow id.
 */
internal class ClueSteps(val byRow: Map<Int, ClueStep>) {
    fun ofTier(tier: ClueTier, kinds: Set<ClueKind>): List<ClueStep> =
        byRow.values.filter { it.tier == tier && it.kind in kinds && it.isSupported }

    companion object {
        fun load(rows: DbRowTypeList, tables: Map<String, Int>): ClueSteps {
            val tableNames = tables.entries.associate { (name, id) -> id to name }
            fun table(row: UnpackedDbRowType?): String? = row?.let { tableNames[it.table] }

            val outfits = HashMap<Int, List<Int>>()
            fun outfit(rowId: Int?): List<Int> {
                rowId ?: return emptyList()
                return outfits.getOrPut(rowId) {
                    val row = rows[rowId] ?: return@getOrPut emptyList()
                    // cluehelper_outfit: 1..11 = wearpos_hat..wearpos_quiver (obj ids).
                    (1..11).mapNotNull { row.int(it) }
                }
            }

            fun target(rowId: Int?): ClueTarget {
                val id = rowId ?: return ClueTarget.Unsupported
                val row = rows[id] ?: return ClueTarget.Unsupported
                return when (table(row)) {
                    "cluehelper_target_npc" ->
                        ClueTarget.Npc(
                            npcs = row.ints(0).toSet() + row.ints(1),
                            coord = CoordGrid(row.int(2) ?: 0),
                            description = row.string(3).orEmpty(),
                        )
                    "cluehelper_target_loc" ->
                        ClueTarget.Loc(
                            locs = row.ints(0).toSet() + row.ints(1),
                            coord = CoordGrid(row.int(2) ?: 0),
                            description = row.string(3).orEmpty(),
                        )
                    "cluehelper_target_coord" ->
                        ClueTarget.Coord(CoordGrid(row.int(0) ?: 0), row.string(1).orEmpty())
                    "cluehelper_target_key" ->
                        ClueTarget.Key(
                            locs = row.ints(0).toSet(),
                            coord = CoordGrid(row.int(1) ?: 0),
                            npcs = row.ints(2).toSet(),
                            key = row.int(3) ?: -1,
                            description = row.string(7).orEmpty(),
                        )
                    "cluehelper_target_mapzone" ->
                        ClueTarget.Zone(
                            sw = CoordGrid(row.int(0) ?: 0),
                            ne = CoordGrid(row.int(1) ?: 0),
                            description = row.string(2).orEmpty(),
                        )
                    else -> ClueTarget.Unsupported
                }
            }

            /** A challenge row: (question, answer) for questions; boxes are flagged. */
            fun question(rowId: Int?): Pair<String, Int>? {
                val row = rows[rowId ?: return null] ?: return null
                if (table(row) != "cluehelper_challenge_question") {
                    return null
                }
                val values = row.data[0].orEmpty()
                val text = values.filterIsInstance<String>().firstOrNull() ?: return null
                val answer = values.filterIsInstance<Int>().firstOrNull() ?: return null
                return text to answer
            }
            fun isBox(rowId: Int?): Boolean {
                val id = rowId ?: return false
                return table(rows[id]) == "cluehelper_challenge_box"
            }

            val steps = HashMap<Int, ClueStep>()
            for ((id, row) in rows) {
                val kind = ClueKind.entries.firstOrNull { it.table == table(row) } ?: continue
                val tier = ClueTier.byDifficulty(row.int(DIFFICULTY) ?: continue) ?: continue
                // Column layouts: see .data/symbols/dbcol.sym (cluehelper_clue_*).
                steps[id] =
                    when (kind) {
                        ClueKind.Anagram ->
                            ClueStep(
                                id,
                                kind,
                                tier,
                                row.text(2),
                                target(row.int(3)),
                                question = question(row.int(4)),
                                boxChallenge = isBox(row.int(4)),
                            )
                        ClueKind.Cryptic ->
                            ClueStep(
                                id,
                                kind,
                                tier,
                                row.text(2),
                                target(row.int(3)),
                                question = question(row.int(5)),
                                boxChallenge = isBox(row.int(5)),
                            )
                        ClueKind.Cipher ->
                            ClueStep(
                                id,
                                kind,
                                tier,
                                row.text(2),
                                target(row.int(3)),
                                question = question(row.int(4)),
                                boxChallenge = isBox(row.int(4)),
                            )
                        ClueKind.Coordinate ->
                            ClueStep(
                                id,
                                kind,
                                tier,
                                row.text(2),
                                target(row.int(3)),
                                combat = row.int(5) != null,
                            )
                        ClueKind.SkillChallenge ->
                            ClueStep(id, kind, tier, row.text(2), target(row.int(4)))
                        ClueKind.Map ->
                            ClueStep(id, kind, tier, "", target(row.int(2)))
                        ClueKind.HotCold ->
                            ClueStep(
                                id,
                                kind,
                                tier,
                                "",
                                target(row.int(2)),
                                combat = row.int(4) != null,
                            )
                        ClueKind.Music ->
                            ClueStep(
                                id,
                                kind,
                                tier,
                                row.text(3),
                                target(row.int(4)),
                                music = row.int(2),
                            )
                        ClueKind.Emote ->
                            ClueStep(
                                row = id,
                                kind = kind,
                                tier = tier,
                                text = row.text(2),
                                target = target(row.int(3)),
                                emotes = row.ints(4),
                                outfit = outfit(row.int(6)),
                                stashLoc = row.int(7),
                                stashCoord = row.int(8)?.let(::CoordGrid),
                                combat = row.int(9) != null,
                            )
                    }
            }
            return ClueSteps(steps)
        }

        private const val DIFFICULTY = 1

        private fun UnpackedDbRowType.ints(column: Int): List<Int> =
            data[column].orEmpty().filterIsInstance<Int>()

        private fun UnpackedDbRowType.int(column: Int): Int? = ints(column).firstOrNull()

        private fun UnpackedDbRowType.string(column: Int): String? =
            data[column].orEmpty().filterIsInstance<String>().firstOrNull()

        private fun UnpackedDbRowType.text(column: Int): String = string(column).orEmpty()
    }
}
