package org.rsmod.content.skills.woodcutting.scripts

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Bird nests (OSRS Wiki "Bird nest"): every log cut has a 1/256 chance to drop a nest at the
 * player's feet; the nest type rolls 0-99 (0 red / 1 blue / 2 green egg, 3-34 ring, else seeds).
 * Search empties a nest into its contents plus an empty nest (Wiki seed and ring tables).
 * Not yet: strung rabbit foot, twitcher's gloves, Woodcutting cape +10%, clue nests.
 */
internal object BirdNestTables {
    const val NEST_CHANCE = 256

    fun rollNest(random: GameRandom): ObjType {
        val roll = random.of(100)
        return when {
            roll == 0 -> NestObjs.egg_red
            roll == 1 -> NestObjs.egg_blue
            roll == 2 -> NestObjs.egg_green
            roll <= 34 -> NestObjs.ring
            else -> NestObjs.seeds
        }
    }

    /** Wiki "Bird nest (seeds)", weights out of 1011. */
    val seeds: List<Pair<String, Int>> =
        listOf(
            "acorn" to 214,
            "apple_tree_seed" to 170,
            "banana_tree_seed" to 108,
            "orange_tree_seed" to 85,
            "willow_seed" to 135,
            "teak_seed" to 4,
            "curry_tree_seed" to 68,
            "maple_seed" to 54,
            "pineapple_tree_seed" to 42,
            "mahogany_seed" to 4,
            "papaya_tree_seed" to 34,
            "palm_tree_seed" to 22,
            "calquat_tree_seed" to 17,
            "yew_seed" to 27,
            "magic_tree_seed" to 5,
            "spirit_tree_seed" to 11,
            "celastrus_tree_seed" to 3,
            "redwood_tree_seed" to 2,
            "dragonfruit_tree_seed" to 6,
        )

    /** Wiki "Bird nest (ring)", weights out of 100. */
    val rings: List<Pair<String, Int>> =
        listOf(
            "gold_ring" to 35,
            "sapphire_ring" to 40,
            "emerald_ring" to 15,
            "ruby_ring" to 9,
            "diamond_ring" to 1,
        )

    fun pick(random: GameRandom, table: List<Pair<String, Int>>): ObjType {
        var roll = random.of(table.sumOf { it.second })
        for ((name, weight) in table) {
            if (roll < weight) return NestObjs.byName.getValue(name)
            roll -= weight
        }
        return NestObjs.byName.getValue(table.last().first)
    }
}

class BirdNestSearch @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        bindSearch(NestObjs.seeds) { BirdNestTables.pick(random, BirdNestTables.seeds) }
        bindSearch(NestObjs.seeds_old) { BirdNestTables.pick(random, BirdNestTables.seeds) }
        bindSearch(NestObjs.ring) { BirdNestTables.pick(random, BirdNestTables.rings) }
        bindSearch(NestObjs.egg_red) { NestObjs.bird_egg_red }
        bindSearch(NestObjs.egg_blue) { NestObjs.bird_egg_blue }
        bindSearch(NestObjs.egg_green) { NestObjs.bird_egg_green }
    }

    private fun ScriptContext.bindSearch(nest: ObjType, contents: ProtectedAccess.() -> ObjType) {
        val op = objTypes[nest].iop.indexOfFirst { it.equals("Search", ignoreCase = true) } + 1
        if (op <= 0 || isHeldOpBound(nest, op)) return
        val action: suspend ProtectedAccess.() -> Unit = { search(nest, contents()) }
        when (op) {
            1 -> onOpHeld1(nest) { action() }
            2 -> onOpHeld2(nest) { action() }
            3 -> onOpHeld3(nest) { action() }
            4 -> onOpHeld4(nest) { action() }
            5 -> onOpHeld5(nest) { action() }
        }
    }

    private fun ProtectedAccess.search(nest: ObjType, item: ObjType) {
        if (!inv.hasFreeSpace()) {
            mes("You don't have enough inventory space.")
            return
        }
        invDel(inv, nest)
        invAdd(inv, NestObjs.empty)
        invAdd(inv, item)
        mes("You take the ${objTypes[item].name.lowercase()} out of the bird's nest.")
    }
}

internal object NestObjs : ObjReferences() {
    val seeds = find("bird_nest_seeds_jan2019")
    val seeds_old = find("bird_nest_seeds")
    val ring = find("bird_nest_ring")
    val egg_red = find("bird_nest_egg_red")
    val egg_blue = find("bird_nest_egg_blue")
    val egg_green = find("bird_nest_egg_green")
    val empty = find("bird_nest_empty")
    val bird_egg_red = find("bird_egg_red")
    val bird_egg_blue = find("bird_egg_blue")
    val bird_egg_green = find("bird_egg_green")

    val byName: Map<String, ObjType> =
        (BirdNestTables.seeds + BirdNestTables.rings).map { it.first }.distinct().associateWith { find(it) }
}
