package org.rsmod.content.other.trails

import org.rsmod.api.random.GameRandom

/**
 * A reward casket table (`rewards_<tier>.tsv`, generated from the OSRS Wiki by
 * tools/clues/wiki_rewards_to_tsv.py). A casket makes [rolls] main rolls, each a weighted pick over
 * every main line (uniques, standard items, shared treasure trail items: the Wiki's per-roll
 * rarities, which sum to 1), plus each tertiary line once, plus milestone rewards on the Nth
 * completion of the tier.
 */
internal class TrailRewards(
    val rolls: IntRange,
    private val main: List<Line>,
    private val tertiary: List<Line>,
    private val milestones: List<Milestone>,
) {
    /** [obj] is an obj internal name; [num]/[den] its chance. */
    class Line(
        val obj: String,
        val min: Int,
        val max: Int,
        val noted: Boolean,
        val num: Long,
        val den: Long,
    )

    class Milestone(val obj: String, val min: Int, val max: Int, val completion: Int)

    /** (obj internal name, count, noted) for one casket; [completion] counts this one. */
    fun roll(random: GameRandom, completion: Int): List<Reward> {
        val rewards = ArrayList<Reward>()
        repeat(random.of(rolls)) { pickMain(random)?.let { rewards += it.reward(random) } }
        for (line in tertiary) {
            if (chance(random, line.num, line.den)) {
                rewards += line.reward(random)
            }
        }
        for (milestone in milestones) {
            if (milestone.completion == completion) {
                rewards += Reward(milestone.obj, random.of(milestone.min, milestone.max), false)
            }
        }
        return rewards
    }

    data class Reward(val obj: String, val count: Int, val noted: Boolean)

    private fun Line.reward(random: GameRandom): Reward =
        Reward(obj, if (max > min) random.of(min, max) else min, noted)

    /** Weighted pick by exact fractions: scale every chance to a common 1-in-[SCALE] roll. */
    private fun pickMain(random: GameRandom): Line? {
        if (main.isEmpty()) {
            return null
        }
        val roll = random.of(SCALE)
        var cumulative = 0L
        for (line in main) {
            cumulative += line.num * SCALE / line.den
            if (roll < cumulative) {
                return line
            }
        }
        return main.last()
    }

    private fun chance(random: GameRandom, num: Long, den: Long): Boolean =
        random.of(SCALE) < num * SCALE / den

    companion object {
        /** Large enough for the Wiki's smallest per-roll chances (~1 in 10^7). */
        private const val SCALE = 1_000_000_000

        fun load(tier: ClueTier): TrailRewards? {
            val input =
                TrailRewards::class.java.getResourceAsStream("rewards_${tier.key}.tsv")
                    ?: return null
            var rolls = 1..1
            val main = ArrayList<Line>()
            val tertiary = ArrayList<Line>()
            val milestones = ArrayList<Milestone>()
            input.bufferedReader().use { reader ->
                for (raw in reader.readLines()) {
                    if (raw.isBlank() || raw.startsWith("#")) {
                        continue
                    }
                    val p = raw.split(TAB)
                    when (p[0]) {
                        "rolls" -> rolls = p[1].toInt()..p[2].toInt()
                        "roll",
                        "tertiary" -> {
                            val line =
                                Line(
                                    obj = p[1],
                                    min = p[2].toInt(),
                                    max = p[3].toInt(),
                                    noted = p[4] == "1",
                                    num = p[5].toLong(),
                                    den = p[6].toLong(),
                                )
                            if (p[0] == "roll") main += line else tertiary += line
                        }
                        "milestone" ->
                            milestones += Milestone(p[1], p[2].toInt(), p[3].toInt(), p[5].toInt())
                    }
                }
            }
            return TrailRewards(rolls, main, tertiary, milestones)
        }

        private const val TAB = '\u0009'
    }
}
