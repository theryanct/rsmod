package org.rsmod.content.minigames.gotr

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.player.stat.baseRunecraftingLvl
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.player.stat.runecraftingLvl
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerQueue
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.lostcity.LostCity
import org.rsmod.content.quests.runemysteries.RuneMysteries
import org.rsmod.content.skills.mining.configs.MiningParams
import org.rsmod.content.skills.mining.scripts.Mining
import org.rsmod.content.skills.runecraft.RunecraftData
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Guardians of the Rift, phase 1: the solo loop (OSRS Wiki: Guardians of the Rift). Each player
 * runs their own game (this server is single-player): pass the barrier to start one.
 *
 * - Prep: [PREP_TICKS]; then one elemental and one catalytic portal guardian are open at a time,
 *   rotating every [ROTATE_TICKS]. A portal to the huge guardian remains opens every
 *   [HUGE_PORTAL_EVERY] for [HUGE_PORTAL_OPEN] ticks.
 * - Mine guardian parts / remains for fragments (large remains behind 56 Agility rubble), huge
 *   remains for essence; 5 Mining xp each for the first [MINING_XP_CAP] per game. The workbench
 *   turns fragments into guardian essence 1:1 (Crafting xp).
 * - Portal guardians take you to the runic altar (Runecraft level; Lost City for cosmic with the
 *   QuestGates toggle). There, Craft-rune on guardian essence (claimed through [OpInterceptors]
 *   only while you're in a game) makes guardian stones plus the runes and xp pure essence would
 *   give ([RunecraftData]). The altar's exit portal brings you back.
 * - Power-up on the Great Guardian: 1 power and 2 energy per stone ([SOLO_POWER] stones = 100%;
 *   the Wiki's 250 per player is scaled down for one player - logged for Ryan).
 * - At 100%: elemental / catalytic points (energy / 100, the remainder as a chance), capped at
 *   1000 per type and 1200 total; Runecraft xp = level x 45 with 300+ energy.
 * - Fragments, essence and portal talismans are removed when a game starts and when you leave.
 * - HUD: gotr_hud, filled by the client's own update script (cs2 5980).
 *
 * Not in phase 1: abyssal creatures, cells, barriers and rift guardians (phase 2); the Rewards
 * Guardian and the shop (phase 3); combination runes; Raiments; portal talismans.
 * With QuestGates on, entering the temple needs Temple of the Eye, which isn't built: Rune
 * Mysteries stands in. Law / death / blood quest gates aren't built either (open for now).
 */
class GotrScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val names: NameMapping,
    private val random: GameRandom,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    /** A portal guardian: its loc, the altar it leads to, and its HUD key (enums 4270 / 4271). */
    private class Guardian(val loc: LocType, val altarKey: String, val elemental: Boolean, val hud: Int)

    private val guardians by lazy {
        listOf(
            Guardian(GotrLocs.portal_air, "air", true, 1),
            Guardian(GotrLocs.portal_water, "water", true, 2),
            Guardian(GotrLocs.portal_earth, "earth", true, 3),
            Guardian(GotrLocs.portal_fire, "fire", true, 4),
            Guardian(GotrLocs.portal_mind, "mind", false, 1),
            Guardian(GotrLocs.portal_body, "body", false, 2),
            Guardian(GotrLocs.portal_cosmic, "cosmic", false, 3),
            Guardian(GotrLocs.portal_chaos, "chaos", false, 4),
            Guardian(GotrLocs.portal_nature, "nature", false, 5),
            Guardian(GotrLocs.portal_law, "law", false, 6),
            Guardian(GotrLocs.portal_death, "death", false, 7),
            Guardian(GotrLocs.portal_blood, "blood", false, 8),
        )
    }

    private val altarsByKey by lazy { RunecraftData.altars.associateBy { it.key } }

    /** Altar loc id -> (altar data, elemental?), for the altars a portal guardian leads to. */
    private val gameAltars by lazy {
        guardians.mapNotNull { g ->
            val altar = altarsByKey[g.altarKey] ?: return@mapNotNull null
            val id = names.locs[altar.altar] ?: return@mapNotNull null
            id to (altar to g.elemental)
        }.toMap()
    }

    private val altarExits by lazy {
        guardians.mapNotNull { g -> altarsByKey[g.altarKey]?.portal?.let { names.locs[it] } }.toSet()
    }

    /** One player's game. */
    private class Game {
        var tick = 0
        var power = 0
        var elementalEnergy = 0
        var catalyticEnergy = 0
        var minedXp = 0
        var openElemental: Guardian? = null
        var openCatalytic: Guardian? = null
        var nextRotate = PREP_TICKS
        var hugeOpensAt = PREP_TICKS
        var hugeClosesAt = -1
        var hugeSpot = 0
        val hugeLocs = ArrayList<LocInfo>()
        /** Away at a runic altar through a portal guardian (still in the game). */
        var atAltar: CoordGrid? = null
        var over = false
    }

    private val games = WeakHashMap<Player, Game>()

    override fun ScriptContext.startup() {
        bindLoc(GotrLocs.tower_portal_parent, 1) { enterTemple() }
        bindLoc(GotrLocs.tower_portal, 1) { enterTemple() }
        bindLoc(GotrLocs.temple_exit, 1) { leaveTemple() }
        bindLoc(GotrLocs.barrier, 1) { passBarrier() }
        bindLoc(GotrLocs.barrier, 3) { passBarrier() }
        bindLoc(GotrLocs.workbench, 1) { workbench() }
        bindLoc(GotrLocs.rubble_top, 1) { climbRubble() }
        bindLoc(GotrLocs.rubble_bottom, 1) { climbRubble() }
        bindLoc(GotrLocs.huge_portal, 1) { hugePortal() }
        for (tier in listOf(GotrLocs.parts, GotrLocs.parts_b, GotrLocs.remains, GotrLocs.remains_b)) {
            bindLoc(tier, 1) { mine(Remains.Fragments13) }
        }
        bindLoc(GotrLocs.large_remains, 1) { mine(Remains.Fragments24) }
        bindLoc(GotrLocs.huge_remains, 1) { mine(Remains.Essence) }
        for (guardian in guardians) {
            bindLoc(guardian.loc, 1) { enterGuardian(guardian) }
        }
        if (!isNpcOpBound(GotrNpcs.great_guardian, 1)) {
            onOpNpc1(GotrNpcs.great_guardian) { powerUp() }
        }
        onPlayerQueue(GotrQueues.mine) { games[player]?.let { mineTick() } }
        onPlayerQueue(GotrQueues.workbench) { workbench() }
        onPlayerSoftTimer(GotrTimers.tick) { player.gameTick() }
        onPlayerLogout { player.endGame(silent = true) }
        onPlayerLogin { player.loginCleanup() }
        OpInterceptors.register(AltarClaim())
    }

    private fun ScriptContext.bindLoc(
        type: LocType,
        op: Int,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        if (isLocOpBound(type, op)) {
            return
        }
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
        }
    }

    /* Entering and leaving */

    private fun ProtectedAccess.enterTemple() {
        if (player.isFeatureOn(Feature.QuestGates) && !player.isQuestComplete(RuneMysteries.RUNE_MYSTERIES)) {
            // Temple of the Eye isn't built; Rune Mysteries stands in.
            mes("You need to complete Temple of the Eye to enter the temple.")
            return
        }
        telejump(LOBBY)
    }

    private fun ProtectedAccess.leaveTemple() {
        player.endGame(silent = false)
        telejump(TOWER_BASEMENT)
    }

    private fun ProtectedAccess.passBarrier() {
        if (coords.z <= BARRIER_Z) {
            telejump(CoordGrid(coords.x, BARRIER_Z + 2, 0))
            player.startGame()
        } else {
            player.endGame(silent = false)
            telejump(CoordGrid(coords.x, BARRIER_Z - 2, 0))
        }
    }

    private fun Player.startGame() {
        if (games[this]?.over == false) {
            return
        }
        clearGameItems()
        games[this] = Game()
        softTimer(GotrTimers.tick, 1)
        ifOpenOverlay(GotrInterfaces.hud, components.toplevel_target_overlay_hud, eventBus)
        mes("The Great Guardian needs your help! Gather guardian essence while the portals open.")
    }

    private fun Player.endGame(silent: Boolean) {
        val game = games.remove(this) ?: return
        clearSoftTimer(GotrTimers.tick)
        closeHugePortal(game)
        ifCloseOverlay(GotrInterfaces.hud, eventBus)
        clearGameItems()
        if (!silent && !game.over) {
            mes("You leave the game; your progress is lost.")
        }
    }

    /** After a logout mid-game: back to the lobby with the game items gone. */
    private fun Player.loginCleanup() {
        if (coords.inArena()) {
            clearGameItems()
            mes("The rift has closed while you were away.")
        }
    }

    private fun Player.clearGameItems() {
        val gameObjs = listOf(GotrObjs.fragment, GotrObjs.essence) + GotrObjs.talismans
        for (type in gameObjs) {
            val id = type.id
            for (slot in inv.indices) {
                if (inv[slot]?.id == id) {
                    inv[slot] = null
                }
            }
        }
    }

    /* The clock */

    private fun Player.gameTick() {
        val game = games[this] ?: return clearSoftTimer(GotrTimers.tick)
        if (game.over) {
            return
        }
        val away = game.atAltar
        val stillHere =
            if (away != null) coords.chebyshevDistance(away) <= ALTAR_RANGE else coords.inTemple()
        if (!stillHere) {
            endGame(silent = false)
            return
        }
        game.tick++
        if (game.tick == PREP_TICKS) {
            mes("The portal guardians awaken!")
        }
        if (game.tick >= game.nextRotate) {
            game.openElemental = guardians.filter { it.elemental }.let { it[random.of(it.size)] }
            game.openCatalytic = guardians.filter { !it.elemental }.let { it[random.of(it.size)] }
            game.nextRotate = game.tick + ROTATE_TICKS
        }
        if (game.tick >= game.hugeOpensAt && game.hugeClosesAt < 0) {
            openHugePortal(game)
        } else if (game.hugeClosesAt in 0..game.tick) {
            closeHugePortal(game)
            game.hugeOpensAt = game.tick + HUGE_PORTAL_EVERY
        }
        if (game.tick % HUD_TICKS == 0) {
            updateHud(game)
        }
    }

    private fun Player.updateHud(game: Game) {
        val rotate = if (game.tick < PREP_TICKS) PREP_TICKS - game.tick else game.nextRotate - game.tick
        val huge = if (game.hugeClosesAt >= 0) game.hugeClosesAt - game.tick else -1
        // cs2 5980 args: elemental / catalytic energy, power current / max, huge portal spot
        // (enum 4254: 1..4 = E / SE / SW / S), open elemental / catalytic guardian (enums 4270 /
        // 4271), guardians current / max, ticks to the next rotation, ticks the huge portal stays.
        runClientScript(
            HUD_SCRIPT,
            game.elementalEnergy,
            game.catalyticEnergy,
            game.power,
            SOLO_POWER,
            if (game.hugeClosesAt >= 0) game.hugeSpot else 0,
            game.openElemental?.hud ?: 0,
            game.openCatalytic?.hud ?: 0,
            0,
            MAX_RIFT_GUARDIANS,
            rotate,
            huge,
        )
    }

    private fun Player.openHugePortal(game: Game) {
        val spot = random.of(HUGE_SPOTS.size)
        game.hugeSpot = spot + 1
        game.hugeClosesAt = game.tick + HUGE_PORTAL_OPEN
        val type = locTypes[GotrLocs.huge_portal]
        for (at in listOf(HUGE_SPOTS[spot], HUGE_EXIT)) {
            game.hugeLocs +=
                locRepo.add(at, type, HUGE_PORTAL_OPEN + 2, LocAngle.West, LocShape.CentrepieceStraight)
        }
        mes("A portal to the huge guardian remains has opened to the ${HUGE_NAMES[spot]}!")
    }

    private fun closeHugePortal(game: Game) {
        for (loc in game.hugeLocs) {
            locRepo.del(loc, Int.MAX_VALUE)
        }
        game.hugeLocs.clear()
        game.hugeClosesAt = -1
    }

    private fun ProtectedAccess.hugePortal() {
        if (coords.x < HUGE_AREA_X) {
            telejump(HUGE_RETURN)
        } else {
            telejump(HUGE_ARRIVAL)
        }
    }

    /* Gathering */

    private enum class Remains { Fragments13, Fragments24, Essence }

    private val mining = WeakHashMap<Player, Remains>()

    private fun ProtectedAccess.mine(remains: Remains) {
        if (games[player] == null) {
            mes("The remains are inert; the rift has to be open.")
            return
        }
        val pickaxe = Mining.findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine the guardian remains.")
            return
        }
        mining[player] = remains
        anim(objTypes[pickaxe].param(params.skill_anim))
        weakQueue(GotrQueues.mine, pickaxeTicks(objTypes[pickaxe].id))
    }

    private fun pickaxeTicks(id: Int): Int =
        objTypes[id]?.paramOrNull(MiningParams.pickaxe_ticks) ?: DEFAULT_PICK_TICKS

    private fun ProtectedAccess.mineTick() {
        val game = games[player] ?: return
        val remains = mining[player] ?: return
        val pickaxe = Mining.findPickaxe(player, objTypes) ?: return
        if (remains == Remains.Essence && inv.isFull()) {
            mes("Your inventory is too full to hold any more essence.")
            return
        }
        anim(objTypes[pickaxe].param(params.skill_anim))
        if (random.of(MINE_ROLL) < MINE_BASE + player.miningLvl) {
            val (obj, count) =
                when (remains) {
                    Remains.Fragments13 -> GotrObjs.fragment to random.of(1, 3)
                    Remains.Fragments24 -> GotrObjs.fragment to random.of(2, 4)
                    Remains.Essence -> GotrObjs.essence to 1
                }
            if (invAdd(inv, obj, count).failure) {
                mes("Your inventory is too full to hold any more.")
                return
            }
            if (game.minedXp < MINING_XP_CAP) {
                game.minedXp += count
                statAdvance(stats.mining, MINE_XP * count * xpMods.get(player, stats.mining))
            }
        }
        weakQueue(GotrQueues.mine, pickaxeTicks(pickaxe.id))
    }

    private fun ProtectedAccess.climbRubble() {
        if (player.agilityLvl < RUBBLE_AGILITY) {
            mes("You need an Agility level of $RUBBLE_AGILITY to climb this rubble.")
            return
        }
        telejump(if (coords.x <= RUBBLE_X) RUBBLE_EAST else RUBBLE_WEST)
    }

    private fun ProtectedAccess.workbench() {
        if (invTotal(inv, GotrObjs.fragment) <= 0) {
            mes("You have no guardian fragments to work with.")
            return
        }
        if (inv.isFull()) {
            mes("You need a free inventory space to make guardian essence.")
            return
        }
        anim(GotrSeqs.workbench)
        invDel(inv, GotrObjs.fragment, 1)
        invAdd(inv, GotrObjs.essence, 1)
        val xp = (player.craftingLvl / CRAFT_XP_LEVELS + 1).coerceIn(1, 5).toDouble()
        statAdvance(stats.crafting, xp * xpMods.get(player, stats.crafting))
        weakQueue(GotrQueues.workbench, WORKBENCH_TICKS)
    }

    /* Altars */

    private suspend fun ProtectedAccess.enterGuardian(guardian: Guardian) {
        val game = games[player]
        if (game == null) {
            mes("The portal guardian is dormant.")
            return
        }
        if (game.openElemental !== guardian && game.openCatalytic !== guardian) {
            mes("This portal guardian isn't open right now.")
            return
        }
        val altar = altarsByKey[guardian.altarKey]
        if (altar == null) {
            mes("This altar can't be reached yet.")
            return
        }
        if (player.baseRunecraftingLvl < altar.level) {
            mes("You need a Runecraft level of ${altar.level} to enter this portal.")
            return
        }
        val gated = player.isFeatureOn(Feature.QuestGates)
        if (gated && guardian.altarKey == "cosmic" && !player.isQuestComplete(LostCity.LOST_CITY)) {
            mes("You need to complete Lost City to enter this portal.")
            return
        }
        game.atAltar = altar.inside
        telejump(altar.inside)
    }

    /** Craft-rune and the exit portal at a runic altar, claimed only for a player in a game. */
    private inner class AltarClaim : OpInterceptors.LocOp {
        override fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean {
            if (op != InteractionOp.Op1 || games[player]?.atAltar == null) {
                return false
            }
            return loc.id in altarExits ||
                (loc.id in gameAltars && player.inv.any { it?.id == GotrObjs.essence.id })
        }

        override suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp) {
            val game = games[player] ?: return
            if (loc.id in altarExits) {
                game.atAltar = null
                telejump(TEMPLE_RETURN)
                return
            }
            val (altar, elemental) = gameAltars[loc.id] ?: return
            imbue(altar, elemental)
        }
    }

    private fun ProtectedAccess.imbue(altar: RunecraftData.Altar, elemental: Boolean) {
        val essence = invTotal(inv, GotrObjs.essence)
        if (essence <= 0) {
            return
        }
        val runeId = names.objs[altar.rune] ?: return
        val rune = objTypes[runeId] ?: return
        invDel(inv, GotrObjs.essence, essence)
        val perEssence = 1 + player.runecraftingLvl / altar.multipleEvery
        val stone = if (elemental) GotrObjs.stone_elemental else GotrObjs.stone_catalytic
        invAdd(inv, stone, essence)
        invAdd(inv, rune, essence * perEssence)
        statAdvance(stats.runecrafting, essence * altar.xp * xpMods.get(player, stats.runecrafting))
        mes("You bind the temple's power into ${altar.key} runes and guardian stones.")
    }

    /* Powering the Great Guardian */

    private fun ProtectedAccess.powerUp() {
        val game = games[player]
        if (game == null || game.over) {
            mes("The Great Guardian doesn't need your help right now.")
            return
        }
        val kinds =
            listOf(
                Triple(GotrObjs.stone_elemental, true, ENERGY_PER_STONE),
                Triple(GotrObjs.stone_catalytic, false, ENERGY_PER_STONE),
                Triple(GotrObjs.stone_polyelemental, true, ENERGY_PER_POLY),
                Triple(GotrObjs.stone_polycatalytic, false, ENERGY_PER_POLY),
            )
        var used = 0
        for ((obj, elemental, energy) in kinds) {
            val count = invTotal(inv, obj)
            if (count <= 0) {
                continue
            }
            invDel(inv, obj, count)
            used += count
            if (elemental) {
                game.elementalEnergy = (game.elementalEnergy + count * energy).coerceAtMost(TYPE_CAP)
            } else {
                game.catalyticEnergy = (game.catalyticEnergy + count * energy).coerceAtMost(TYPE_CAP)
            }
        }
        if (used == 0) {
            mes("You need guardian stones to power up the Great Guardian.")
            return
        }
        game.power = (game.power + used).coerceAtMost(SOLO_POWER)
        statAdvance(stats.runecrafting, POWER_XP * used * xpMods.get(player, stats.runecrafting))
        player.updateHud(game)
        if (game.power >= SOLO_POWER) {
            win(game)
        }
    }

    private fun ProtectedAccess.win(game: Game) {
        game.over = true
        closeHugePortal(game)
        val elemental = game.elementalEnergy
        val catalytic = game.catalyticEnergy
        val total = (elemental + catalytic).coerceAtMost(TOTAL_CAP)
        val scale = if (elemental + catalytic > 0) total.toDouble() / (elemental + catalytic) else 0.0
        val ePoints = points((elemental * scale).toInt())
        val cPoints = points((catalytic * scale).toInt())
        VarPlayerIntMapSetter.set(player, GotrVarps.points_elemental, vars[GotrVarps.points_elemental] + ePoints)
        VarPlayerIntMapSetter.set(player, GotrVarps.points_catalytic, vars[GotrVarps.points_catalytic] + cPoints)
        if (total >= XP_ENERGY_THRESHOLD) {
            val xp = player.baseRunecraftingLvl * XP_PER_LEVEL
            statAdvance(stats.runecrafting, xp * xpMods.get(player, stats.runecrafting))
        }
        mes("The Great Guardian seals the rift! You earn $ePoints elemental and $cPoints catalytic points.")
        mes(
            "You have ${vars[GotrVarps.points_elemental]} elemental and " +
                "${vars[GotrVarps.points_catalytic]} catalytic points. Pass the barrier to play again."
        )
    }

    /** Energy / 100 points, the remainder as a percent chance of one more. */
    private fun points(energy: Int): Int =
        energy / 100 + if (random.of(100) < energy % 100) 1 else 0

    /* Areas */

    private fun CoordGrid.inTemple(): Boolean =
        level == 0 && x in TEMPLE_X && z in TEMPLE_Z

    private fun CoordGrid.inArena(): Boolean = inTemple() && z > BARRIER_Z

    private companion object {
        val TEMPLE_X = 3580..3650
        val TEMPLE_Z = 9465..9530
        const val BARRIER_Z = 9483
        val LOBBY = CoordGrid(3615, 9476, 0)
        val TOWER_BASEMENT = CoordGrid(3104, 9571, 0)
        /** Back from a runic altar: just south of the Great Guardian. */
        val TEMPLE_RETURN = CoordGrid(3614, 9497, 0)
        const val ALTAR_RANGE = 40

        // Timings (OSRS Wiki): 2-minute prep, portal pairs every ~19 s, huge portal every ~2
        // minutes for 25 s.
        const val PREP_TICKS = 200
        const val ROTATE_TICKS = 32
        const val HUGE_PORTAL_EVERY = 200
        const val HUGE_PORTAL_OPEN = 42
        const val HUD_TICKS = 2
        const val HUD_SCRIPT = 5980
        const val MAX_RIFT_GUARDIANS = 10

        // Huge remains portal spots (HUD enum 4254: E / SE / SW / S) and the west area.
        val HUGE_SPOTS =
            listOf(
                CoordGrid(3628, 9503, 0),
                CoordGrid(3624, 9492, 0),
                CoordGrid(3604, 9492, 0),
                CoordGrid(3614, 9488, 0),
            )
        val HUGE_NAMES = listOf("east", "south-east", "south-west", "south")
        const val HUGE_AREA_X = 3600
        val HUGE_ARRIVAL = CoordGrid(3592, 9503, 0)
        val HUGE_EXIT = CoordGrid(3591, 9500, 0)
        val HUGE_RETURN = CoordGrid(3614, 9497, 0)

        const val RUBBLE_AGILITY = 56
        const val RUBBLE_X = 3635
        val RUBBLE_EAST = CoordGrid(3637, 9503, 0)
        val RUBBLE_WEST = CoordGrid(3633, 9503, 0)

        const val MINE_ROLL = 256
        /** Success chance on the 1/256 scale: [MINE_BASE] + Mining level (authored). */
        const val MINE_BASE = 100
        const val MINE_XP = 5.0
        const val MINING_XP_CAP = 250
        const val DEFAULT_PICK_TICKS = 4
        const val WORKBENCH_TICKS = 1
        /** Workbench Crafting xp: 1..5 by Crafting level (one more per [CRAFT_XP_LEVELS]). */
        const val CRAFT_XP_LEVELS = 20

        /**
         * Guardian stones for 100% power. The Wiki's 250 per player is a group target; one
         * player fills 150 (logged as a solo-tuning decision for Ryan).
         */
        const val SOLO_POWER = 150
        const val ENERGY_PER_STONE = 2
        const val ENERGY_PER_POLY = 3
        const val POWER_XP = 5.0
        const val TYPE_CAP = 1000
        const val TOTAL_CAP = 1200
        const val XP_ENERGY_THRESHOLD = 300
        const val XP_PER_LEVEL = 45
    }
}
