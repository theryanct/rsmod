package org.rsmod.content.skills.agility

/** Agility courses and shortcuts from the hand-authored `agility.tsv` resource (see its header). */
internal object AgilityData {
    /** A coordinate where `null` x/z means "keep the player's current value". */
    class Target(val x: Int?, val z: Int?, val level: Int)

    class Course(
        val key: String,
        val title: String,
        val lapBonusXp: Double,
        val markChance: Int,
        val markSpawn: Target?,
    )

    class Obstacle(
        val course: String,
        val step: Int,
        val locs: List<String>,
        val level: Int,
        val xp: Double,
        val climb: Boolean,
        val anim: String,
        val ticks: Int,
        val end: Target,
        val start: Target?,
    )

    class Shortcut(
        val locs: List<String>,
        val level: Int,
        val xp: Double,
        val anim: String,
        val ticks: Int,
        val sideA: Target,
        val sideB: Target,
    )

    private class Parsed(
        val courses: List<Course>,
        val obstacles: List<Obstacle>,
        val shortcuts: List<Shortcut>,
    )

    private val parsed: Parsed by lazy { load() }

    val courses: List<Course>
        get() = parsed.courses

    val obstacles: List<Obstacle>
        get() = parsed.obstacles

    val shortcuts: List<Shortcut>
        get() = parsed.shortcuts

    private fun target(text: String): Target? {
        if (text == "-") {
            return null
        }
        val p = text.split(',')
        return Target(p[0].toIntOrNull(), p[1].toIntOrNull(), p[2].toInt())
    }

    private fun load(): Parsed {
        val input =
            AgilityData::class.java.getResourceAsStream("agility.tsv")
                ?: error("Agility resource not found: agility.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val courses = ArrayList<Course>()
        val obstacles = ArrayList<Obstacle>()
        val shortcuts = ArrayList<Shortcut>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val p = line.split('\t')
            when (p[0]) {
                "course" ->
                    courses.add(Course(p[1], p[2], p[3].toDouble(), p[4].toInt(), target(p[5])))
                "obstacle" ->
                    obstacles.add(
                        Obstacle(
                            course = p[1],
                            step = p[2].toInt(),
                            locs = p[3].split(','),
                            level = p[4].toInt(),
                            xp = p[5].toDouble(),
                            climb = p[6] == "climb",
                            anim = p[7],
                            ticks = p[8].toInt(),
                            end = checkNotNull(target(p[9])) { "Obstacle without end: $raw" },
                            start = target(p[10]),
                        )
                    )
                "shortcut" ->
                    shortcuts.add(
                        Shortcut(
                            locs = p[1].split(','),
                            level = p[2].toInt(),
                            xp = p[3].toDouble(),
                            anim = p[4],
                            ticks = p[5].toInt(),
                            sideA = checkNotNull(target(p[6])),
                            sideB = checkNotNull(target(p[7])),
                        )
                    )
                else -> error("Unknown agility line: $raw")
            }
        }
        return Parsed(courses, obstacles, shortcuts)
    }
}
