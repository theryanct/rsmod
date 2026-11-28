package org.rsmod.content.other.trails

import kotlin.math.abs
import kotlin.math.roundToInt
import org.rsmod.map.CoordGrid

/**
 * Coordinate clue notation: degrees and minutes north/south and east/west of the Observatory
 * reference point, one minute = 1.875 tiles (OSRS Wiki, Treasure Trails/Guide/Coordinates).
 */
internal object Coordinates {
    private val ORIGIN = CoordGrid(2440, 3161)
    private const val TILES_PER_MINUTE = 1.875

    /** "00.05S 01.13E" (the clue-helper text) -> the clue scroll's wording. */
    fun describe(text: String): String {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2) {
            return text
        }
        return parts.joinToString("<br>") { part(it) ?: it }
    }

    private fun part(token: String): String? {
        val match = Regex("""^(\d+)\.(\d+)([NSEW])$""").matchEntire(token) ?: return null
        val (degrees, minutes, dir) = match.destructured
        val direction =
            when (dir) {
                "N" -> "north"
                "S" -> "south"
                "E" -> "east"
                else -> "west"
            }
        return "$degrees degrees $minutes minutes $direction"
    }

    /** Where [coords] is, as a coordinate clue would put it (the sextant reading). */
    fun of(coords: CoordGrid): String {
        val north = describeAxis(coords.z - ORIGIN.z, "north", "south")
        val east = describeAxis(coords.x - ORIGIN.x, "east", "west")
        return "$north, $east"
    }

    private fun describeAxis(delta: Int, positive: String, negative: String): String {
        val totalMinutes = (abs(delta) / TILES_PER_MINUTE).roundToInt()
        val degrees = totalMinutes / 60
        val minutes = totalMinutes % 60
        val dir = if (delta >= 0) positive else negative
        return "%02d degrees %02d minutes %s".format(degrees, minutes, dir)
    }
}
