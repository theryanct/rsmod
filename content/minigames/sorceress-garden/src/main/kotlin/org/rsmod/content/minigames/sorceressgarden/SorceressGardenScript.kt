package org.rsmod.content.minigames.sorceressgarden

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.thievingLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.princeali.PrinceAliRescue
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sorceress's Garden (Al Kharid), solo. OSRS Wiki: Sorceress's Garden, sq'irk pages.
 *
 * - The apprentice's Teleport (after Prince Ali Rescue with QuestGates on) puts you in the central
 *   garden; the fountain takes you back. Each seasonal gate needs its Thieving level (winter 1,
 *   spring 25, autumn 45, summer 65).
 * - Elementals patrol between the gate and the sq'irk tree; get next to one and you're thrown
 *   back to the centre. The patrols are authored (the cache has no spawn data) and solo-tuned.
 * - Pick-fruit: one sq'irk (Farming xp 30/40/50/60) and you're teleported out; Pick on the
 *   herbs: two clean herbs (Wiki tables), 50 Farming xp, teleported out.
 * - Pestle and mortar on a sq'irk with an empty beer glass: sq'irkjuice from 5/4/3/2 fruit (5
 *   Cooking xp). Give juice to Osman (use it on him, or Talk-to while holding it): Thieving xp
 *   350 / 1350 / 2350 / 3000. Shelves in the sorceress's house give beer glasses.
 */
class SorceressGardenScript
@Inject
constructor(
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val npcRepo: NpcRepository,
    private val npcTypes: NpcTypeList,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private class Herb(val obj: ObjType, val weight: Int)

    private class Season(
        val name: String,
        val level: Int,
        val gate: LocType,
        val outside: CoordGrid,
        val inside: CoordGrid,
        val tree: LocType,
        val treeAt: CoordGrid,
        val herbs: LocType,
        val fruit: ObjType,
        val juice: ObjType,
        val fruitPerJuice: Int,
        val pickXp: Double,
        val osmanXp: Double,
        val elementals: List<NpcType>,
        val herbTable: List<Herb>,
        val contains: (CoordGrid) -> Boolean,
    )

    private val seasons by lazy {
        val h = SgObjs
        listOf(
            Season(
                "winter", 1, SgLocs.gate_winter, CoordGrid(2904, 5470), CoordGrid(2902, 5470),
                SgLocs.tree_winter, CoordGrid(2891, 5475), SgLocs.herbs_winter,
                SgObjs.sqirk_winter, SgObjs.juice_winter, 5, 30.0, 350.0, SgNpcs.winter.take(2),
                listOf(Herb(h.guam, 16), Herb(h.marrentill, 14), Herb(h.tarromin, 13), Herb(h.harralander, 11)),
            ) { it.x < 2903 },
            Season(
                "spring", 25, SgLocs.gate_spring, CoordGrid(2919, 5473), CoordGrid(2921, 5473),
                SgLocs.tree_spring, CoordGrid(2931, 5463), SgLocs.herbs_spring,
                SgObjs.sqirk_spring, SgObjs.juice_spring, 4, 40.0, 1350.0, SgNpcs.spring.take(3),
                listOf(
                    Herb(h.guam, 16), Herb(h.marrentill, 14), Herb(h.tarromin, 13), Herb(h.harralander, 12),
                    Herb(h.irit, 11), Herb(h.avantoe, 9),
                ),
            ) { it.x > 2920 },
            Season(
                "autumn", 45, SgLocs.gate_autumn, CoordGrid(2913, 5464), CoordGrid(2913, 5462),
                SgLocs.tree_autumn, CoordGrid(2912, 5450), SgLocs.herbs_autumn,
                SgObjs.sqirk_autumn, SgObjs.juice_autumn, 3, 50.0, 2350.0, SgNpcs.autumn.take(4),
                listOf(
                    Herb(h.guam, 16), Herb(h.marrentill, 14), Herb(h.tarromin, 13), Herb(h.harralander, 12),
                    Herb(h.irit, 11), Herb(h.avantoe, 10), Herb(h.ranarr, 9), Herb(h.kwuarm, 8), Herb(h.cadantine, 6),
                ),
            ) { it.z < 5463 },
            Season(
                "summer", 65, SgLocs.gate_summer, CoordGrid(2910, 5479), CoordGrid(2910, 5481),
                SgLocs.tree_summer, CoordGrid(2915, 5491), SgLocs.herbs_summer,
                SgObjs.sqirk_summer, SgObjs.juice_summer, 2, 60.0, 3000.0, SgNpcs.summer.take(5),
                listOf(
                    Herb(h.guam, 16), Herb(h.marrentill, 14), Herb(h.tarromin, 13), Herb(h.harralander, 12),
                    Herb(h.irit, 11), Herb(h.avantoe, 10), Herb(h.ranarr, 9), Herb(h.kwuarm, 8), Herb(h.cadantine, 7),
                    Herb(h.lantadyme, 6), Herb(h.dwarf_weed, 5),
                ),
            ) { it.z > 5480 },
        )
    }

    /** A patrolling elemental: walks between [a] and [b]. */
    private class Patrol(val npc: Npc, val a: CoordGrid, val b: CoordGrid) {
        var toB = true
    }

    private class Visit(val season: Season) {
        val patrols = ArrayList<Patrol>()
    }

    private val visits = WeakHashMap<Player, Visit>()

    override fun ScriptContext.startup() {
        val apprentice = SgNpcs.apprentice
        if (!isNpcOpBound(apprentice, 4)) onOpNpc4(apprentice) { teleportIn() }
        bindLoc(SgLocs.fountain) { leaveGarden("You drink from the fountain and feel yourself pulled away.") }
        bindLoc(SgLocs.shelves) { searchShelves() }
        for (season in seasons) {
            bindLoc(season.gate) { enterGate(season) }
            bindLoc(season.tree) { pickFruit(season) }
            bindLoc(season.herbs) { pickHerbs(season) }
            onOpHeldU(SgObjs.pestle, season.fruit) { makeJuice(season) }
            for (osman in SgNpcs.osmans) {
                onOpNpcU(osman, season.juice) { giveJuice() }
            }
        }
        onPlayerSoftTimer(SgTimers.tick) { player.patrolTick() }
        onPlayerLogout { player.endVisit() }
        OpInterceptors.register(OsmanClaim())
    }

    private fun ScriptContext.bindLoc(type: LocType, action: suspend ProtectedAccess.() -> Unit) {
        if (!isLocOpBound(type, 1)) onOpLoc1(type) { action() }
    }

    /* Getting in and out */

    private fun ProtectedAccess.teleportIn() {
        if (player.isFeatureOn(Feature.QuestGates) && !player.isQuestComplete(PrinceAliRescue.PRINCE_ALI_RESCUE)) {
            mes("You need to complete Prince Ali Rescue first.")
            return
        }
        telejump(CENTRE)
    }

    private fun ProtectedAccess.leaveGarden(message: String) {
        player.endVisit()
        telejump(HOUSE)
        mes(message)
    }

    private fun ProtectedAccess.searchShelves() {
        if (inv.isFull()) {
            mes("You don't have room for a beer glass.")
            return
        }
        invAdd(inv, SgObjs.beer_glass, 1)
        mes("You find an empty beer glass.")
    }

    private fun ProtectedAccess.enterGate(season: Season) {
        if (season.contains(coords)) {
            player.endVisit()
            telejump(season.outside)
            return
        }
        if (player.thievingLvl < season.level) {
            mes("You need a Thieving level of ${season.level} to enter the ${season.name} garden.")
            return
        }
        telejump(season.inside)
        player.startVisit(season)
    }

    private fun Player.startVisit(season: Season) {
        endVisit()
        val visit = Visit(season)
        val count = season.elementals.size
        for ((i, type) in season.elementals.withIndex()) {
            // Spread them along the gate-tree path so there are gaps to slip through.
            val start = lerp(season.inside, season.treeAt, (i + 1).toDouble() / (count + 1))
            val npc = Npc(npcTypes[type], start).apply { mode = NpcMode.None }
            npcRepo.add(npc, PATROL_TICKS)
            val patrol = Patrol(npc, season.inside.translate(0, 0), season.treeAt)
            patrol.toB = i % 2 == 0
            npc.walk(if (patrol.toB) patrol.b else patrol.a)
            visit.patrols += patrol
        }
        visits[this] = visit
        softTimer(SgTimers.tick, 1)
    }

    private fun lerp(a: CoordGrid, b: CoordGrid, t: Double): CoordGrid =
        CoordGrid(a.x + ((b.x - a.x) * t).toInt(), a.z + ((b.z - a.z) * t).toInt(), a.level)

    private fun Player.endVisit() {
        val visit = visits.remove(this) ?: return
        clearSoftTimer(SgTimers.tick)
        for (patrol in visit.patrols) {
            if (patrol.npc.isSlotAssigned) npcRepo.del(patrol.npc, Int.MAX_VALUE)
        }
    }

    private fun Player.patrolTick() {
        val visit = visits[this] ?: return clearSoftTimer(SgTimers.tick)
        if (!visit.season.contains(coords)) {
            endVisit()
            return
        }
        for (patrol in visit.patrols) {
            val npc = patrol.npc
            if (!npc.isSlotAssigned) continue
            val target = if (patrol.toB) patrol.b else patrol.a
            if (npc.coords.chebyshevDistance(target) <= 1) {
                patrol.toB = !patrol.toB
                npc.walk(if (patrol.toB) patrol.b else patrol.a)
            }
            if (npc.coords.chebyshevDistance(coords) <= CATCH_RANGE) {
                npc.say("Oi! You're not supposed to be here!")
                endVisit()
                protectedAccess.launchLenient(this) {
                    telejump(CENTRE)
                    mes("An elemental throws you out of the garden!")
                }
                return
            }
        }
    }

    /* Picking */

    private fun ProtectedAccess.pickFruit(season: Season) {
        if (inv.isFull()) {
            mes("You need a free inventory space to pick a sq'irk.")
            return
        }
        invAdd(inv, season.fruit, 1)
        statAdvance(stats.farming, season.pickXp * xpMods.get(player, stats.farming))
        leaveGardenToCentre("You pick a sq'irk fruit and are teleported out of the garden.")
    }

    private fun ProtectedAccess.pickHerbs(season: Season) {
        if (inv.freeSpace() < 2) {
            mes("You need two free inventory spaces to pick herbs.")
            return
        }
        repeat(2) { invAdd(inv, rollHerb(season), 1) }
        statAdvance(stats.farming, HERB_XP * xpMods.get(player, stats.farming))
        leaveGardenToCentre("You pick some herbs and are teleported out of the garden.")
    }

    private fun ProtectedAccess.leaveGardenToCentre(message: String) {
        player.endVisit()
        telejump(CENTRE)
        mes(message)
    }

    private fun rollHerb(season: Season): ObjType {
        var roll = random.of(season.herbTable.sumOf { it.weight })
        for (herb in season.herbTable) {
            if (roll < herb.weight) return herb.obj
            roll -= herb.weight
        }
        return season.herbTable.last().obj
    }

    /* Juice and Osman */

    private fun ProtectedAccess.makeJuice(season: Season) {
        if (invTotal(inv, season.fruit) < season.fruitPerJuice) {
            mes("You need ${season.fruitPerJuice} ${season.name} sq'irks to fill a glass.")
            return
        }
        if (invTotal(inv, SgObjs.beer_glass) <= 0) {
            mes("You need an empty beer glass to hold the juice.")
            return
        }
        invDel(inv, season.fruit, season.fruitPerJuice)
        invDel(inv, SgObjs.beer_glass, 1)
        invAdd(inv, season.juice, 1)
        statAdvance(stats.cooking, CRUSH_XP * xpMods.get(player, stats.cooking))
        mes("You squeeze the sq'irks into the beer glass.")
    }

    private suspend fun ProtectedAccess.giveJuice() {
        var total = 0.0
        var count = 0
        for (season in seasons) {
            val n = invTotal(inv, season.juice)
            if (n <= 0) continue
            invDel(inv, season.juice, n)
            total += n * season.osmanXp
            count += n
        }
        if (count == 0) return
        statAdvance(stats.thieving, total * xpMods.get(player, stats.thieving))
        mes("Osman takes the sq'irkjuice and teaches you a thing or two about thieving.")
    }

    /** Talk-to Osman while holding sq'irkjuice hands it over (quest dialogue otherwise). */
    private inner class OsmanClaim : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op1 &&
                SgNpcs.osmans.any { it.id == npc.id || it.id == npc.visType.id } &&
                seasons.any { s -> player.inv.any { it?.id == s.juice.id } }

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            giveJuice()
        }
    }

    private companion object {
        val CENTRE = CoordGrid(2913, 5471, 0)
        val HOUSE = CoordGrid(3321, 3139, 0)
        const val CATCH_RANGE = 1
        const val PATROL_TICKS = 6000
        const val HERB_XP = 50.0
        const val CRUSH_XP = 5.0
    }
}

internal object SgLocs : LocReferences() {
    val fountain = find("100_osman_fountain")
    val shelves = find("sorceress_shelves")
    val gate_winter = find("100_osman_door_winter")
    val gate_spring = find("100_osman_door_spring")
    val gate_autumn = find("100_osman_door_autumn")
    val gate_summer = find("100_osman_door_summer")
    val tree_winter = find("100_osman_pomy_tree_winter")
    val tree_spring = find("100_osman_pomy_tree_spring")
    val tree_autumn = find("100_osman_pomy_tree_autumn")
    val tree_summer = find("100_osman_pomy_tree_summer")
    val herbs_winter = find("osman_herb_patch_winter")
    val herbs_spring = find("osman_herb_patch_spring")
    val herbs_autumn = find("osman_herb_patch_autumn")
    val herbs_summer = find("osman_herb_patch_summer")
}

internal object SgNpcs : NpcReferences() {
    val apprentice = find("arabian_sorceress_apprentice")
    val osmans = listOf(find("osman"), find("hundred_osman"))
    val winter = (1..6).map { find("100_osman_winter_elemental_$it") }
    val spring = (1..8).map { find("100_osman_spring_elemental_$it") }
    val autumn = (1..6).map { find("100_osman_autumn_elemental_$it") }
    val summer = (1..6).map { find("100_osman_summer_elemental_$it") }
}

internal object SgObjs : ObjReferences() {
    val pestle = find("pestle_and_mortar")
    val beer_glass = find("beer_glass")
    val sqirk_winter = find("osman_squirk_winter")
    val sqirk_spring = find("osman_squirk_spring")
    val sqirk_autumn = find("osman_squirk_autumn")
    val sqirk_summer = find("osman_squirk_summer")
    val juice_winter = find("osman_squirk_j_winter")
    val juice_spring = find("osman_squirk_j_spring")
    val juice_autumn = find("osman_squirk_j_autumn")
    val juice_summer = find("osman_squirk_j_summer")
    val guam = find("guam_leaf")
    val marrentill = find("marentill")
    val tarromin = find("tarromin")
    val harralander = find("harralander")
    val irit = find("irit_leaf")
    val avantoe = find("avantoe")
    val ranarr = find("ranarr_weed")
    val kwuarm = find("kwuarm")
    val cadantine = find("cadantine")
    val lantadyme = find("lantadyme")
    val dwarf_weed = find("dwarf_weed")
}

internal object SgTimers : TimerReferences() {
    /** Custom (ui range 65303): elemental patrols while you're in a seasonal garden. */
    val tick = find("sorceress_garden_tick")
}
