package org.rsmod.content.skills.agility.brimhaven

import jakarta.inject.Inject
import kotlin.math.abs
import net.rsprot.protocol.game.outgoing.misc.client.HintArrow
import org.rsmod.api.config.Constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.agility.AgilityBas
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * Brimhaven Agility Arena (Wiki "Brimhaven Agility Arena", May-2024 rules; data: `brimhaven.tsv`).
 * - Cap'n Izzy No-Beard takes the 200 coin fee (varbit `agilityarena_canenter`); the hut ladder
 *   then leads down once. Logging out inside puts the player back in the hut.
 * - One of the 24 ticket dispensers is active (hint arrow) and moves every minute. Tagging it
 *   gives 30 xp per 10 Agility levels, an Agility arena ticket and a Brimhaven voucher.
 * - Obstacles between pillars give the Wiki xp; failing (authored tier rates, none at 99) drops
 *   the player to the floor with some damage, and the floor ropes climb back up. Of the three
 *   planks per crossing only the unbroken one is safe (fixed; OSRS swaps it every minute). Floor spikes, pressure pads and spinning blades pay when
 *   walked over (polled each tick); pressure pads pay twice, then rest for 8 ticks.
 * - Pirate Jackie the Fruit: tickets for 345 xp each; vouchers for noted toadflax/snapdragon,
 *   the pirate's hook, the dark blue graceful recolour and amylase (as 100 crystals). A dialogue
 *   menu stands in for the store interface.
 * Not yet: the dart traps, Karamja gloves/diary bonuses, re-entry delay after teleporting out.
 */
class BrimhavenArena
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val collision: CollisionFlagMap,
    private val gameRandom: GameRandom,
) : PluginScript() {
    private class Arena {
        var active = 0
        var rotateIn = BrimhavenTuning.ROTATE_TICKS
        var tagged = false
        var taggedLast = false
        var offArena = 0
        var lastTrap = -1
        var padStreak = 0
        var padRest = 0
    }

    private val arenas = HashMap<Int, Arena>()

    private val data = BrimhavenData.parsed
    private val crosses by lazy { data.crosses.associateBy { BrimhavenLocs.byName.getValue(it.loc).id to it.tile } }
    private val walls by lazy { data.walls.associateBy { BrimhavenLocs.byName.getValue(it.loc).id to it.tile } }
    private val climbs by lazy { data.climbs.associateBy { BrimhavenLocs.byName.getValue(it.loc).id to it.tile } }
    private val dispensers by lazy { data.dispensers.sortedBy { it.tile.z * 10_000 + it.tile.x } }
    private val dispenserKeys by lazy { dispensers.map { BrimhavenLocs.byName.getValue(it.loc).id to it.tile }.toSet() }
    private val trapTiles by lazy {
        val map = HashMap<CoordGrid, ArenaTrap>()
        for (trap in data.traps) for (tile in trap.tiles) map[tile] = trap
        map
    }

    override fun ScriptContext.startup() {
        val bound = HashSet<Int>()
        for (type in BrimhavenLocs.byName.values) {
            if (bound.add(type.id)) bindLoc(type) { placed(it) }
        }
        bindLoc(BrimhavenLocs.ladder_down) { enter() }
        bindLoc(BrimhavenLocs.ladder_up) { exit() }
        bindNpc(BrimhavenNpcs.izzy, "Talk-to") { izzy(it) }
        bindNpc(BrimhavenNpcs.izzy, "Pay") { pay() }
        for (jackie in listOf(BrimhavenNpcs.jackie_2ops, BrimhavenNpcs.jackie_3ops)) {
            bindNpc(jackie, "Talk-to") { jackie(it, greet = true) }
            bindNpc(jackie, "Trade") { jackie(it, greet = false) }
        }
        onPlayerSoftTimer(BrimhavenTimers.tick) { tick(player) }
        onPlayerLogout { kickOut(player) }
        onPlayerLogin { kickOut(player) }
    }

    /* Binding helpers (ops located by label; skip ops another script already owns). */

    private fun ScriptContext.bindLoc(type: LocType, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[type].op.indexOfFirst { it != null && !it.equals("Examine", ignoreCase = true) } + 1
        if (op <= 0 || isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
        }
    }

    private fun ScriptContext.bindNpc(type: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op <= 0 || isNpcOpBound(type, op)) return
        when (op) {
            1 -> onOpNpc1(type) { action(it.npc) }
            2 -> onOpNpc2(type) { action(it.npc) }
            3 -> onOpNpc3(type) { action(it.npc) }
        }
    }

    /* Entry and exit */

    private suspend fun ProtectedAccess.izzy(npc: Npc) {
        var wantsPay = false
        // Authored (not Wiki transcript): short lines in Izzy's voice.
        startDialogue(npc) {
            chatNpc(happy, "Ahoy! It's ${BrimhavenTuning.ENTRY_FEE} coins to enter the Agility Arena. Tag the pillar under the arrow for a ticket, and mind the traps!")
            wantsPay = choice2("Pay ${BrimhavenTuning.ENTRY_FEE} coins.", true, "No thanks.", false)
        }
        if (wantsPay) pay()
    }

    private fun ProtectedAccess.pay() {
        if (player.vars[BrimhavenVarBits.can_enter] == 1) {
            mes("You have already paid. Head down the ladder into the arena.")
            return
        }
        if (invTotal(inv, BrimhavenObjs.coins) < BrimhavenTuning.ENTRY_FEE) {
            mes("You need ${BrimhavenTuning.ENTRY_FEE} coins to enter the Agility Arena.")
            return
        }
        invDel(inv, BrimhavenObjs.coins, BrimhavenTuning.ENTRY_FEE)
        VarPlayerIntMapSetter.set(player, BrimhavenVarBits.can_enter, 1)
        mes("You pay Cap'n Izzy No-Beard ${BrimhavenTuning.ENTRY_FEE} coins.")
    }

    private suspend fun ProtectedAccess.enter() {
        if (player.vars[BrimhavenVarBits.can_enter] != 1) {
            mes("You need to pay Cap'n Izzy No-Beard ${BrimhavenTuning.ENTRY_FEE} coins before you can enter the arena.")
            return
        }
        VarPlayerIntMapSetter.set(player, BrimhavenVarBits.can_enter, 0)
        anim(BrimhavenSeqs.climb)
        delay(1)
        telejump(BrimhavenTuning.ARRIVAL)
        val arena = Arena()
        arena.active = gameRandom.of(dispensers.size)
        arenas[player.uid.packed] = arena
        player.softTimer(BrimhavenTimers.tick, BrimhavenTuning.LOOP_TICKS)
        pointAt(player, arena)
        mes("Tag the ticket dispenser under the flashing arrow to earn a ticket.")
    }

    private suspend fun ProtectedAccess.exit() {
        anim(BrimhavenSeqs.climb)
        delay(1)
        end(player)
        telejump(BrimhavenTuning.SURFACE)
    }

    private fun end(player: Player) {
        arenas.remove(player.uid.packed)
        player.clearSoftTimer(BrimhavenTimers.tick)
        player.client.write(HintArrow(HintArrow.ResetHintArrow))
    }

    /** Logout/login inside the arena: drop the run and put the player back in the hut (Wiki). */
    private fun kickOut(player: Player) {
        val had = arenas.remove(player.uid.packed) != null
        player.clearSoftTimer(BrimhavenTimers.tick)
        if (had || inArena(player.coords)) player.coords = BrimhavenTuning.SURFACE
    }

    private fun inArena(coords: CoordGrid): Boolean =
        coords.x in BrimhavenTuning.ARENA_X && coords.z in BrimhavenTuning.ARENA_Z

    /* Loop */

    private fun tick(player: Player) {
        val arena = arenas[player.uid.packed]
        if (arena == null) {
            player.clearSoftTimer(BrimhavenTimers.tick)
            return
        }
        arena.offArena = if (inArena(player.coords)) 0 else arena.offArena + 1
        if (arena.offArena >= BrimhavenTuning.OFF_ARENA_TICKS) {
            // Teleported away or died: drop the run quietly.
            end(player)
            return
        }
        if (arena.padRest > 0) arena.padRest--
        arena.rotateIn -= BrimhavenTuning.LOOP_TICKS
        if (arena.rotateIn <= 0) rotate(player, arena)
        checkTrap(player, arena)
    }

    private fun rotate(player: Player, arena: Arena) {
        arena.taggedLast = arena.tagged
        arena.tagged = false
        arena.rotateIn = BrimhavenTuning.ROTATE_TICKS
        var next = gameRandom.of(dispensers.size)
        if (next == arena.active) next = (next + 1) % dispensers.size
        arena.active = next
        pointAt(player, arena)
    }

    private fun pointAt(player: Player, arena: Arena) {
        val tile = dispensers[arena.active].tile
        player.client.write(
            HintArrow(
                HintArrow.TileHintArrow(
                    tile.x,
                    tile.z,
                    BrimhavenTuning.HINT_HEIGHT,
                    HintArrow.TileHintArrow.HintArrowTilePosition.CENTER,
                )
            )
        )
    }

    /** Walk-over traps: pays (or hurts) once each time the player steps onto a trap. */
    private fun checkTrap(player: Player, arena: Arena) {
        val trap = trapTiles[player.coords]
        if (trap == null) {
            arena.lastTrap = -1
            return
        }
        if (trap.id == arena.lastTrap) return
        arena.lastTrap = trap.id
        val trapStats = BrimhavenTuning.traps.getValue(trap.kind)
        if (player.agilityLvl < trapStats.level || failRoll(player.agilityLvl, trapStats.level, trapStats.tier)) {
            player.queueHit(1, HitType.Typeless, trapStats.tier.damage)
            player.mes(trapFailMessage(trap.kind))
            return
        }
        if (trapStats.xp <= 0.0) return
        if (trap.kind == "pad") {
            if (arena.padRest > 0) return
            arena.padStreak++
            if (arena.padStreak >= BrimhavenTuning.PAD_STREAK) {
                arena.padStreak = 0
                arena.padRest = BrimhavenTuning.PAD_COOLDOWN
            }
        }
        player.statAdvance(stats.agility, trapStats.xp * xpMods.get(player, stats.agility))
    }

    private fun trapFailMessage(kind: String): String =
        when (kind) {
            "pad" -> "You trigger the pressure pad and get hit!"
            "spikes" -> "The floor spikes catch you!"
            "blades" -> "The spinning blades cut you!"
            else -> "A blade slices you!"
        }

    private fun failRoll(level: Int, required: Int, tier: ArenaTier): Boolean {
        if (level >= BrimhavenTuning.NO_FAIL_LEVEL) return false
        val span = (BrimhavenTuning.NO_FAIL_LEVEL - required).coerceAtLeast(1)
        val chance = (tier.maxFail * (BrimhavenTuning.NO_FAIL_LEVEL - level) / span).coerceIn(0.0, tier.maxFail)
        return gameRandom.of(FAIL_ROLL) < (chance * FAIL_ROLL).toInt()
    }

    /* Locs */

    private suspend fun ProtectedAccess.placed(loc: BoundLocInfo) {
        val key = loc.id to loc.coords
        if (key in dispenserKeys) {
            tag(loc.coords)
            return
        }
        climbs[key]?.let {
            anim(BrimhavenSeqs.climb)
            delay(2)
            telejump(it.dest)
            return
        }
        walls[key]?.let {
            wall(it)
            return
        }
        val crossing = crosses[key]
        if (crossing == null) {
            mes("Nothing interesting happens.")
            return
        }
        cross(crossing)
    }

    private fun ProtectedAccess.tag(tile: CoordGrid) {
        val arena = arenas[player.uid.packed]
        if (arena == null || dispensers[arena.active].tile != tile) {
            mes("You can only get a ticket when the flashing arrow is above the pillar.")
            return
        }
        if (arena.tagged) {
            mes("You can only get one ticket at a time, wait till the arrow moves again.")
            return
        }
        arena.tagged = true
        statAdvance(stats.agility, (BrimhavenTuning.TAG_XP_PER_10 * (player.agilityLvl / 10)).toDouble())
        if (BrimhavenTuning.REQUIRE_CONSECUTIVE && !arena.taggedLast) {
            mes("Tag the next pillar too to start earning tickets.")
            return
        }
        invAddOrDrop(objRepo, BrimhavenObjs.ticket)
        invAddOrDrop(objRepo, BrimhavenObjs.voucher)
        mes("You have received an Agility Arena Ticket and Brimhaven Voucher!")
    }

    private suspend fun ProtectedAccess.cross(cross: ArenaCross) {
        val family = BrimhavenTuning.families.getValue(cross.family)
        if (player.agilityLvl < family.level) {
            mes("You need an Agility level of ${family.level} to attempt this.")
            return
        }
        val failed =
            if (cross.family == "plank") {
                cross.tile.z !in BrimhavenTuning.SAFE_PLANK_Z && player.agilityLvl < BrimhavenTuning.NO_FAIL_LEVEL
            } else {
                failRoll(player.agilityLvl, family.level, family.tier)
            }
        if (failed) {
            anim(BrimhavenSeqs.stumble)
            delay(2)
            telejump(land(cross.fall))
            queueHit(1, HitType.Typeless, family.tier.damage)
            mes("You lose your footing and fall into the pit below.")
            return
        }
        val end = land(cross.landing)
        move(family.anim, family.ticks, end)
        statAdvance(stats.agility, family.xp * xpMods.get(player, stats.agility))
    }

    private suspend fun ProtectedAccess.wall(wall: ArenaWall) {
        val family = BrimhavenTuning.WALL
        val here = player.coords
        val end = if (distance(here, wall.a) <= distance(here, wall.b)) wall.b else wall.a
        move(family.anim, family.ticks, end)
        statAdvance(stats.agility, family.xp * xpMods.get(player, stats.agility))
    }

    /** [pin] if walkable, else the nearest walkable tile within [BrimhavenTuning.LAND_RADIUS]. */
    private fun land(pin: CoordGrid): CoordGrid {
        for (r in 0..BrimhavenTuning.LAND_RADIUS) {
            for (dz in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val tile = pin.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) == 0) return tile
                }
            }
        }
        return pin
    }

    private suspend fun ProtectedAccess.move(animName: String, ticks: Int, end: CoordGrid) {
        anim(BrimhavenSeqs.byName.getValue(animName))
        val from = player.coords
        val crossingBas = AgilityBas.byAnim[animName]
        val previousBas = player.bas
        if (crossingBas != null) player.bas = crossingBas
        try {
            exactMove(from, end, 0, ticks * CLIENT_CYCLES_PER_TICK, direction(from, end))
            delay(ticks)
        } finally {
            if (crossingBas != null) player.bas = previousBas
        }
    }

    private fun distance(a: CoordGrid, b: CoordGrid): Int = abs(a.x - b.x) + abs(a.z - b.z)

    private fun direction(from: CoordGrid, to: CoordGrid): Int {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (abs(dz) >= abs(dx)) {
            if (dz > 0) Constants.em_face_north else Constants.em_face_south
        } else {
            if (dx > 0) Constants.em_face_east else Constants.em_face_west
        }
    }

    /* Pirate Jackie the Fruit */

    private enum class Ware(val label: String) {
        Xp("Agility experience (1 ticket each)"),
        Toadflax("Toadflax (${BrimhavenTuning.TOADFLAX} vouchers each)"),
        Snapdragon("Snapdragon (${BrimhavenTuning.SNAPDRAGON} vouchers each)"),
        Hook("Pirate's hook (${BrimhavenTuning.PIRATE_HOOK} vouchers)"),
        Graceful("Graceful recolour (${BrimhavenTuning.GRACEFUL} vouchers)"),
        Amylase("Amylase pack (${BrimhavenTuning.AMYLASE} vouchers)"),
    }

    private suspend fun ProtectedAccess.jackie(npc: Npc, greet: Boolean) {
        var picked: Ware? = null
        startDialogue(npc) {
            if (greet) {
                // Authored (not Wiki transcript).
                chatNpc(happy, "Arr! Bring me tickets and vouchers from the arena and I'll make it worth your while.")
            }
            val first =
                choice4(
                    Ware.Xp.label, Ware.Xp,
                    Ware.Toadflax.label, Ware.Toadflax,
                    Ware.Snapdragon.label, Ware.Snapdragon,
                    "More...", null,
                )
            picked =
                first
                    ?: choice4(
                        Ware.Hook.label, Ware.Hook,
                        Ware.Graceful.label, Ware.Graceful,
                        Ware.Amylase.label, Ware.Amylase,
                        "Nothing, thanks.", null,
                    )
        }
        when (picked ?: return) {
            Ware.Xp -> exchangeTickets()
            Ware.Toadflax -> buyMany(BrimhavenObjs.toadflax, BrimhavenTuning.TOADFLAX, "toadflax")
            Ware.Snapdragon -> buyMany(BrimhavenObjs.snapdragon, BrimhavenTuning.SNAPDRAGON, "snapdragon")
            Ware.Hook -> buyOne(BrimhavenObjs.pirate_hook, 1, BrimhavenTuning.PIRATE_HOOK, "a pirate's hook")
            Ware.Amylase -> buyOne(BrimhavenObjs.amylase, BrimhavenTuning.AMYLASE_COUNT, BrimhavenTuning.AMYLASE, "an amylase pack")
            Ware.Graceful -> recolour()
        }
    }

    private suspend fun ProtectedAccess.exchangeTickets() {
        val tickets = invTotal(inv, BrimhavenObjs.ticket)
        if (tickets == 0) {
            mes("You don't have any Agility arena tickets.")
            return
        }
        val count = countDialog("How many tickets? (You have $tickets.)").coerceIn(0, tickets)
        if (count <= 0) return
        invDel(inv, BrimhavenObjs.ticket, count)
        statAdvance(stats.agility, BrimhavenTuning.TICKET_XP * count * xpMods.get(player, stats.agility))
        mes("You exchange $count ticket${if (count == 1) "" else "s"} for Agility experience.")
    }

    private suspend fun ProtectedAccess.buyMany(obj: ObjType, price: Int, name: String) {
        val vouchers = invTotal(inv, BrimhavenObjs.voucher)
        val most = vouchers / price
        if (most == 0) {
            mes("You need $price Brimhaven vouchers for each $name.")
            return
        }
        val count = countDialog("How many? (You can afford $most.)").coerceIn(0, most)
        if (count <= 0) return
        if (!inv.hasFreeSpace() && invTotal(inv, obj) == 0 && vouchers != count * price) {
            mes("You don't have enough inventory space.")
            return
        }
        invDel(inv, BrimhavenObjs.voucher, count * price)
        invAdd(inv, obj, count)
        mes("You trade ${count * price} vouchers for $count noted $name.")
    }

    private fun ProtectedAccess.buyOne(obj: ObjType, count: Int, price: Int, name: String) {
        val vouchers = invTotal(inv, BrimhavenObjs.voucher)
        if (vouchers < price) {
            mes("You need $price Brimhaven vouchers for $name; you have $vouchers.")
            return
        }
        val stacks = obj.id == BrimhavenObjs.amylase.id && invTotal(inv, obj) > 0
        if (!stacks && !inv.hasFreeSpace() && vouchers != price) {
            mes("You don't have enough inventory space.")
            return
        }
        invDel(inv, BrimhavenObjs.voucher, price)
        invAdd(inv, obj, count)
        mes("You trade $price vouchers for $name.")
    }

    /** Wiki: the recolour takes a full uncoloured graceful outfit (held in the inventory here). */
    private fun ProtectedAccess.recolour() {
        val vouchers = invTotal(inv, BrimhavenObjs.voucher)
        if (vouchers < BrimhavenTuning.GRACEFUL) {
            mes("You need ${BrimhavenTuning.GRACEFUL} Brimhaven vouchers for the recolour; you have $vouchers.")
            return
        }
        if (BrimhavenObjs.graceful.any { invTotal(inv, it) == 0 }) {
            mes("Bring a full graceful outfit (hood, top, legs, gloves, boots and cape) in your inventory.")
            return
        }
        invDel(inv, BrimhavenObjs.voucher, BrimhavenTuning.GRACEFUL)
        for ((plain, arena) in BrimhavenObjs.graceful.zip(BrimhavenObjs.graceful_arena)) {
            invDel(inv, plain, 1)
            invAdd(inv, arena, 1)
        }
        mes("Pirate Jackie recolours your graceful outfit.")
    }

    private companion object {
        const val CLIENT_CYCLES_PER_TICK = 30
        const val FAIL_ROLL = 10_000
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
    }
}
