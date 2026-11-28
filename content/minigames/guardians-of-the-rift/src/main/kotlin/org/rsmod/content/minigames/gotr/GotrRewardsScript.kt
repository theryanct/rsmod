package org.rsmod.content.minigames.gotr

import jakarta.inject.Inject
import org.rsmod.api.player.events.CollectionLogObtained
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Guardians of the Rift, phase 3: the Rewards Guardian and Temple Supplies (OSRS Wiki: Rewards
 * Guardian, Temple Supplies).
 *
 * - Search costs 1 elemental + 1 catalytic point (or 25 abyssal pearls with no points); Big-search
 *   does up to [BIG_SEARCH] searches; Check shows points and searches. Each search rolls the rare
 *   table (Atlax's diary once, catalytic talisman, needle, lantern, dyes), then the main table out
 *   of 140 (a pouch you don't own, runes, the talisman sub-table, pearls, the intricate pouch,
 *   abyssal ashes, a needle), then the 1/4000 Abyssal protector. Rewards feed the collection log.
 * - Apprentice Felix's Trade: Temple Supplies for abyssal pearls. The shop interface only takes
 *   coins on this server, so it's a menu for now (later polish: a pearl currency shop).
 * - Not yet: opening the intricate pouch (its own table), dye swaps, Raiments' bonus runes.
 */
class GotrRewardsScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val names: NameMapping,
    private val random: GameRandom,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private var Player.diaryObtained by intVarBit(GotrRewardVarbits.book_obtained)

    private class Roll(val name: String, val weight: Int, val min: Int, val max: Int, val noted: Boolean = false)

    private val runes =
        listOf(
            Roll("airrune", 4, 400, 500),
            Roll("waterrune", 4, 400, 500),
            Roll("earthrune", 4, 400, 500),
            Roll("firerune", 4, 400, 500),
            Roll("mindrune", 4, 250, 400),
            Roll("bodyrune", 4, 80, 150),
            Roll("chaosrune", 10, 61, 150),
            Roll("cosmicrune", 10, 20, 30),
            Roll("naturerune", 10, 28, 150),
            Roll("lawrune", 10, 5, 120),
            Roll("deathrune", 10, 5, 120),
            Roll("bloodrune", 10, 5, 120),
        )

    /** The talisman sub-table (16/140 of the main table); weights out of 560. */
    private val talismans =
        listOf(
            Roll("air_talisman", 48, 1, 1, noted = true),
            Roll("water_talisman", 48, 1, 1, noted = true),
            Roll("earth_talisman", 48, 1, 1, noted = true),
            Roll("fire_talisman", 48, 1, 1, noted = true),
            Roll("mind_talisman", 64, 1, 1, noted = true),
            Roll("body_talisman", 64, 1, 1, noted = true),
            Roll("chaos_talisman", 64, 1, 1, noted = true),
            Roll("cosmic_talisman", 64, 1, 1, noted = true),
            Roll("nature_talisman", 64, 1, 1, noted = true),
            Roll("elemental_talisman", 16, 1, 1),
            Roll("law_talisman", 16, 1, 1),
            Roll("death_talisman", 16, 1, 1, noted = true),
        )

    private val pouches = listOf("rcu_pouch_small", "rcu_pouch_medium", "rcu_pouch_large", "rcu_pouch_giant")

    private class Ware(val name: String, val price: Int)

    private val wares =
        listOf(
            Ware("hat_of_the_eye", 400),
            Ware("robe_top_of_the_eye", 350),
            Ware("robe_bottom_of_the_eye", 350),
            Ware("boots_of_the_eye", 250),
            Ware("ring_of_elements_charged", 400),
            Ware("guardians_eye", 200),
            Ware("abyssal_lantern", 100),
            Ware("abyssal_needle", 750),
        )

    override fun ScriptContext.startup() {
        val rift = GotrRewardLocs.reward_rift
        if (!isLocOpBound(rift, 1)) onOpLoc1(rift) { search(1) }
        if (!isLocOpBound(rift, 2)) onOpLoc2(rift) { check() }
        if (!isLocOpBound(rift, 3)) onOpLoc3(rift) { search(BIG_SEARCH) }
        if (!isNpcOpBound(GotrRewardNpcs.felix, 3)) {
            onOpNpc3(GotrRewardNpcs.felix) { templeSupplies() }
        }
        loadCheck()
    }

    /** Fails loudly at boot if a table name doesn't resolve. */
    private fun loadCheck() {
        val all = runes.map { it.name } + talismans.map { it.name } + pouches + wares.map { it.name }
        val missing = all.filter { names.objs[it] == null }
        check(missing.isEmpty()) { "GotR reward objs missing from the symbols: $missing" }
    }

    private fun obj(name: String): UnpackedObjType = objTypes[names.objs.getValue(name)]!!

    private fun ProtectedAccess.check() {
        mes(
            "You have ${vars[GotrVarps.points_elemental]} elemental and " +
                "${vars[GotrVarps.points_catalytic]} catalytic energy points, and have searched " +
                "the Rewards Guardian ${vars[GotrRewardVarps.searches]} times."
        )
    }

    private fun ProtectedAccess.search(times: Int) {
        var done = 0
        while (done < times) {
            val usePoints =
                vars[GotrVarps.points_elemental] > 0 && vars[GotrVarps.points_catalytic] > 0
            val usePearls = !usePoints && invTotal(inv, GotrRewardObjs.pearl) >= PEARL_SEARCH
            if (!usePoints && !usePearls) {
                if (done == 0) {
                    mes("You need an elemental and a catalytic point (or $PEARL_SEARCH abyssal pearls) to search.")
                }
                break
            }
            if (inv.isFull()) {
                mes("You need a free inventory space to search the Rewards Guardian.")
                break
            }
            if (usePoints) {
                VarPlayerIntMapSetter.set(player, GotrVarps.points_elemental, vars[GotrVarps.points_elemental] - 1)
                VarPlayerIntMapSetter.set(player, GotrVarps.points_catalytic, vars[GotrVarps.points_catalytic] - 1)
            } else {
                invDel(inv, GotrRewardObjs.pearl, PEARL_SEARCH)
            }
            val searches = (vars[GotrRewardVarps.searches] + 1).coerceAtMost(MAX_SEARCHES)
            VarPlayerIntMapSetter.set(player, GotrRewardVarps.searches, searches)
            rollOnce()
            done++
        }
        if (done > 0) {
            mes("You search the Rewards Guardian $done time${if (done == 1) "" else "s"}.")
        }
    }

    private fun ProtectedAccess.rollOnce() {
        rollRare()
        val ownsAll = pouches.all { name -> player.owns(obj(name)) }
        val total = if (ownsAll) MAIN_TOTAL - POUCH_WEIGHT else MAIN_TOTAL
        var roll = random.of(total)
        if (!ownsAll) {
            if (roll < POUCH_WEIGHT) {
                val missing = pouches.map(::obj).filter { !player.owns(it) }
                give(missing[random.of(missing.size)], 1)
                return
            }
            roll -= POUCH_WEIGHT
        }
        val runeWeight = runes.sumOf { it.weight }
        if (roll < runeWeight) {
            return give(pick(runes, roll))
        }
        roll -= runeWeight
        if (roll < TALISMAN_WEIGHT) {
            return give(pick(talismans, random.of(talismans.sumOf { it.weight })))
        }
        roll -= TALISMAN_WEIGHT
        when {
            roll < PEARL_WEIGHT -> give(GotrRewardObjs.pearl, random.of(14, 16))
            roll < PEARL_WEIGHT + INTRICATE_WEIGHT -> give(GotrRewardObjs.intricate_pouch, 1)
            roll < PEARL_WEIGHT + INTRICATE_WEIGHT + 1 -> give(GotrRewardObjs.abyssal_ashes, 1)
            else -> give(GotrRewardObjs.needle, 1)
        }
    }

    private fun ProtectedAccess.rollRare() {
        if (player.diaryObtained == 0 && random.of(20) == 0) {
            player.diaryObtained = 1
            give(GotrRewardObjs.atlax_diary, 1)
        }
        if (random.of(200) == 0) give(GotrRewardObjs.catalytic_talisman, 1)
        if (random.of(300) == 0) give(GotrRewardObjs.abyssal_needle, 1)
        if (random.of(700) == 0) give(GotrRewardObjs.abyssal_lantern, 1)
        if (random.of(400) == 0) {
            val dyes = listOf(GotrRewardObjs.red_dye, GotrRewardObjs.green_dye, GotrRewardObjs.blue_dye)
            give(dyes[random.of(dyes.size)], 1)
        }
        if (random.of(PET_ODDS) == 0) {
            give(GotrRewardObjs.abyssal_protector, 1)
            mes("You have a funny feeling like you're being followed.")
        }
    }

    private fun pick(table: List<Roll>, roll: Int): Roll {
        var acc = 0
        for (entry in table) {
            acc += entry.weight
            if (roll < acc) {
                return entry
            }
        }
        return table.last()
    }

    private fun ProtectedAccess.give(roll: Roll) {
        val base = obj(roll.name)
        val type = if (roll.noted && base.canCert) objTypes.cert(base) else base
        give(type, random.of(roll.min, roll.max))
    }

    private fun ProtectedAccess.give(type: ObjType, count: Int) {
        if (invAdd(inv, type, count).failure) {
            objRepo.add(type, coords, GROUND_TICKS, player, count)
        }
        val unnoted = objTypes.uncert(objTypes[type])
        eventBus.publish(CollectionLogObtained(player, unnoted.id, count))
    }

    private fun Player.owns(type: ObjType): Boolean =
        inv.any { it?.id == type.id } || worn.any { it?.id == type.id }

    /* Temple Supplies (pearl menu) */

    private suspend fun ProtectedAccess.templeSupplies() {
        val pearls = invTotal(inv, GotrRewardObjs.pearl)
        val choices = wares.map { "${obj(it.name).name} (${it.price} pearls)" } + "Nothing, thanks."
        val picked = menu("Temple Supplies: you have $pearls abyssal pearls", hotkeys = false, choices = choices)
        val ware = wares.getOrNull(picked) ?: return
        if (pearls < ware.price) {
            mes("You need ${ware.price} abyssal pearls for that.")
            return
        }
        if (inv.isFull()) {
            mes("You need a free inventory space.")
            return
        }
        invDel(inv, GotrRewardObjs.pearl, ware.price)
        invAdd(inv, obj(ware.name), 1)
    }

    private companion object {
        const val BIG_SEARCH = 10
        const val PEARL_SEARCH = 25
        const val MAX_SEARCHES = 65535
        const val MAIN_TOTAL = 140
        const val POUCH_WEIGHT = 15
        const val TALISMAN_WEIGHT = 16
        const val PEARL_WEIGHT = 18
        const val INTRICATE_WEIGHT = 5
        const val PET_ODDS = 4000
        const val GROUND_TICKS = 200
    }
}

internal object GotrRewardLocs : LocReferences() {
    val reward_rift = find("gotr_reward_rift")
}

internal object GotrRewardNpcs : NpcReferences() {
    val felix = find("gotr_reward_trader")
}

internal object GotrRewardObjs : ObjReferences() {
    val pearl = find("abyssal_pearl")
    val intricate_pouch = find("gotr_intricate_pouch")
    val abyssal_ashes = find("abyssal_ashes")
    val needle = find("needle")
    val atlax_diary = find("gotr_book")
    val catalytic_talisman = find("catalytic_talisman")
    val abyssal_needle = find("abyssal_needle")
    val abyssal_lantern = find("abyssal_lantern")
    val red_dye = find("abyssal_red_dye")
    val green_dye = find("abyssal_green_dye")
    val blue_dye = find("abyssal_blue_dye")
    val abyssal_protector = find("abyssalpet")
}

internal object GotrRewardVarbits : VarBitReferences() {
    val book_obtained = find("gotr_book_obtained")
}

/** Custom (ui range, 65332): Rewards Guardian searches made. */
internal object GotrRewardVarps : VarpReferences() {
    val searches = find("gotr_searches")
}

internal object GotrRewardVarpBuilder : VarpBuilder() {
    init {
        build("gotr_searches")
    }
}
