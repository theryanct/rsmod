package org.rsmod.content.other.trails

import kotlin.math.abs
import org.rsmod.api.random.GameRandom

/**
 * A puzzle box's sliding puzzle (interface trail_slidepuzzle). [slots] maps each of the 5x5 slots
 * to a piece (0..23, the index in the piece set's enum) or [EMPTY]. Solved = piece i in slot i,
 * the last slot empty. The client moves pieces itself (cs2 691); the server mirrors each move.
 */
internal class SlidePuzzle(val set: Int, val slots: IntArray) {
    val gap: Int
        get() = slots.indexOf(EMPTY)

    val isSolved: Boolean
        get() = (0 until LAST).all { slots[it] == it } && slots[LAST] == EMPTY

    /** Slides the piece in [slot] into the gap, if they are side by side. */
    fun move(slot: Int): Boolean {
        if (slot !in slots.indices || slots[slot] == EMPTY) {
            return false
        }
        val gap = gap
        if (!adjacent(slot, gap)) {
            return false
        }
        slots[gap] = slots[slot]
        slots[slot] = EMPTY
        return true
    }

    companion object {
        const val SIZE = 5
        const val EMPTY = -1
        private const val LAST = SIZE * SIZE - 1
        private const val SCRAMBLE_MOVES = 300
        /** Piece sets the hard puzzle boxes use: varp `if1` 1..3 (enums 1354..1356). */
        val SETS = 1..3

        fun adjacent(a: Int, b: Int): Boolean {
            val dx = abs(a % SIZE - b % SIZE)
            val dz = abs(a / SIZE - b / SIZE)
            return dx + dz == 1
        }

        /** A random legal scramble (so always solvable) of piece [set], not already solved. */
        fun scrambled(set: Int, random: GameRandom): SlidePuzzle {
            val slots = IntArray(SIZE * SIZE) { if (it == LAST) EMPTY else it }
            val puzzle = SlidePuzzle(set, slots)
            var previous = -1
            var moves = 0
            while (moves < SCRAMBLE_MOVES || puzzle.isSolved) {
                val gap = puzzle.gap
                val options = slots.indices.filter { adjacent(it, gap) && it != previous }
                puzzle.move(options[random.of(options.size)])
                previous = gap
                moves++
            }
            return puzzle
        }
    }
}

/**
 * A light box (interface light_puzzle): 8 buttons, each toggling a fixed set of the 25 lights.
 * Varp trail_lights holds the lights, bit set = unlit (cs2 1339), so solved = 0. Built by
 * pressing a random non-empty set of buttons on a solved box, so it can always be solved.
 */
internal class LightBox(val masks: IntArray, var lights: Int) {
    val isSolved: Boolean
        get() = lights == 0

    fun press(button: Int) {
        lights = lights xor masks[button]
    }

    companion object {
        const val BUTTONS = 8
        private const val LIGHTS = 25
        private const val ALL = (1 shl LIGHTS) - 1

        fun random(random: GameRandom): LightBox {
            val masks =
                IntArray(BUTTONS) {
                    var mask = 0
                    while (Integer.bitCount(mask) < 3) {
                        mask = mask or (1 shl random.of(LIGHTS))
                    }
                    mask and ALL
                }
            val box = LightBox(masks, 0)
            while (box.isSolved) {
                for (button in 0 until BUTTONS) {
                    if (random.of(2) == 1) {
                        box.press(button)
                    }
                }
            }
            return box
        }
    }
}
