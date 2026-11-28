package org.rsmod.content.other.multicannon

import jakarta.inject.Inject
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.rsmod.api.combat.commons.npc.resolveCombatXpMultiplier
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.formulas.AccuracyFormulae
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.hit.modifier.StandardNpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.route.RayCastValidator
import org.rsmod.api.script.onAiConTimer
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.builders.controller.ControllerBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.proj.ProjAnimReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.proj.ProjAnim
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.proj.ProjAnimTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.util.Bounds
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * The dwarf multicannon (OSRS Wiki, Nov 2026).
 * - Set-up the cannon base (with stand, barrels and furnace carried) on a vacant 3x3: the cannon is
 *   built base, stand, barrels, then furnace, centred on the player. One cannon per player.
 * - Fire loads up to 30 cannonballs (steel, or granite for a 35 max hit) and starts it; it turns
 *   45 degrees a tick and fires one ball at the first attackable npc in line of sight within 8
 *   tiles in the direction it faces. Damage is 0-30 (granite 35) after a ranged-accuracy roll
 *   (player's ranged accuracy vs the target's heavy ranged defence), 2 Ranged xp per damage.
 * - It only fires while its owner is online and within 16 tiles. After 25 minutes it breaks (Fire
 *   repairs it); 10 minutes later a broken cannon disappears and is marked lost (Nulodion replaces
 *   it). Pick-up returns parts and balls; Empty returns the balls.
 *
 * State lives in memory (controllers don't survive restarts); server varp `mcannon_state` (65785)
 * holds bit 0 placed and bit 1 lost, so a cannon left out over a restart is lost on next login.
 *
 * Not done: the single-way rule (fire only at the npc attacking you), banned areas beyond the
 * nearby-banker check, granite loaded separately from steel, melee accuracy when wielding melee.
 */
class Multicannon
@Inject
constructor(
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val conRepo: ControllerRepository,
    private val worldRepo: WorldRepository,
    private val players: PlayerList,
    private val collision: CollisionFlagMap,
    private val accuracy: AccuracyFormulae,
    private val random: GameRandom,
    private val mapClock: MapClock,
    private val hitModifier: StandardNpcHitModifier,
    private val projTypes: ProjAnimTypeList,
    private val rayCast: RayCastValidator,
) : PluginScript() {
    private class Cannon(
        val owner: Long,
        val origin: CoordGrid,
        val controller: Controller,
        var setupCycle: Int,
    ) {
        val centre: CoordGrid
            get() = origin.translate(1, 1)

        var balls = 0
        var granite = false
        var dir = 0
        var broken = false
        var player: Player? = null
    }

    private val byOwner = HashMap<Long, Cannon>()
    private val byController = HashMap<Controller, Cannon>()

    override fun ScriptContext.startup() {
        onOpHeld1(CannonObjs.base) { setUp() }
        onOpLoc1(CannonLocs.cannon) { fire(it.loc) }
        onOpLoc2(CannonLocs.cannon) { pickUp(it.loc) }
        onOpLoc3(CannonLocs.cannon) { empty(it.loc) }
        onOpLoc4(CannonLocs.cannon) { fire(it.loc) }
        onAiConTimer(CannonControllers.cannon) { tick(controller) }
        onPlayerLogin { relink(player) }
    }

    /* Placing */

    private suspend fun ProtectedAccess.setUp() {
        val uuid = player.uuid ?: return
        if (byOwner.containsKey(uuid)) {
            mes("You already have a cannon set up.")
            return
        }
        if (CannonObjs.parts.any { invTotal(inv, it) == 0 }) {
            mes("You need the base, stand, barrels and furnace to set up a cannon.")
            return
        }
        val origin = player.coords.translate(-1, -1)
        if (!vacant(origin)) {
            mes("There isn't enough space to set up here.")
            return
        }
        if (nearBank(player.coords)) {
            mes("You can't set up a cannon here.")
            return
        }
        val stages =
            listOf(CannonLocs.base, CannonLocs.stand, CannonLocs.barrels, CannonLocs.cannon)
        var placed: LocInfo? = null
        for ((index, stage) in stages.withIndex()) {
            placed?.let { locRepo.del(it, LOC_DURATION) }
            val loc = locAt(origin, stage)
            locRepo.add(loc, LOC_DURATION)
            placed = loc
            invDel(inv, CannonObjs.parts[index])
            mes(STAGE_MESSAGES[index])
            delay(2)
        }
        val controller = Controller(CannonControllers.cannon, origin.translate(1, 1))
        conRepo.add(controller, CONTROLLER_DURATION)
        controller.aiTimer(1)
        val cannon = Cannon(uuid, origin, controller, mapClock.cycle).also { it.player = player }
        byOwner[uuid] = cannon
        byController[controller] = cannon
        setState(player, PLACED)
    }

    private fun vacant(origin: CoordGrid): Boolean {
        for (dx in 0..2) {
            for (dz in 0..2) {
                val c = origin.translate(dx, dz)
                if (collision[c.x, c.z, c.level] and BLOCKED != 0) return false
                if (conRepo.findAll(c).any { it.type.id == CannonControllers.cannon.id }) {
                    return false
                }
            }
        }
        return true
    }

    private fun nearBank(coords: CoordGrid): Boolean =
        npcRepo.findAll(ZoneKey.from(coords), 1).any {
            it.coords.chebyshevDistance(coords) <= BANK_RADIUS &&
                it.visType.name.equals("Banker", ignoreCase = true)
        }

    /* Ops on the placed cannon */

    private fun ProtectedAccess.owned(loc: BoundLocInfo): Cannon? {
        val cannon = byOwner[player.uuid ?: return null]
        if (cannon == null || cannon.origin != loc.coords) {
            mes("This isn't your cannon!")
            return null
        }
        return cannon
    }

    private suspend fun ProtectedAccess.fire(loc: BoundLocInfo) {
        val cannon = owned(loc) ?: return
        if (cannon.broken) {
            cannon.broken = false
            cannon.setupCycle = mapClock.cycle
            mes("You repair your cannon, restoring it to working order.")
            return
        }
        val room = MAX_BALLS - cannon.balls
        val granite = invTotal(inv, CannonObjs.granite)
        val steel = invTotal(inv, CannonObjs.steel)
        val (ammo, have) =
            if (steel > 0 && !(cannon.balls > 0 && cannon.granite)) CannonObjs.steel to steel
            else CannonObjs.granite to granite
        if (room > 0 && have > 0) {
            val load = min(room, have)
            invDel(inv, ammo, load)
            cannon.balls += load
            cannon.granite = ammo == CannonObjs.granite
            mes("You load the cannon with $load cannonball${if (load > 1) "s" else ""}.")
        } else if (cannon.balls == 0) {
            mes("Your cannon is out of ammo!")
            return
        }
        cannon.player = player
        delay(1)
    }

    private fun ProtectedAccess.pickUp(loc: BoundLocInfo) {
        val cannon = owned(loc) ?: return
        val stackable = if (cannon.balls > 0 && invTotal(inv, ammoOf(cannon)) == 0) 1 else 0
        if (inv.freeSpace() < CannonObjs.parts.size + stackable) {
            mes("You need ${CannonObjs.parts.size + stackable} free inventory spaces.")
            return
        }
        for (part in CannonObjs.parts) invAdd(inv, part)
        if (cannon.balls > 0) invAdd(inv, ammoOf(cannon), cannon.balls)
        remove(cannon, lost = false)
        mes("You pick up the cannon. It's really heavy.")
    }

    private fun ProtectedAccess.empty(loc: BoundLocInfo) {
        val cannon = owned(loc) ?: return
        if (cannon.balls == 0) {
            mes("Your cannon is already empty.")
            return
        }
        invAdd(inv, ammoOf(cannon), cannon.balls)
        cannon.balls = 0
        mes("You remove the cannonballs from your cannon.")
    }

    private fun locAt(origin: CoordGrid, type: LocType): LocInfo =
        LocInfo(LAYER, origin, LocEntity(type.id, SHAPE.id, LocAngle.West.id))

    private fun ammoOf(cannon: Cannon): ObjType =
        if (cannon.granite) CannonObjs.granite else CannonObjs.steel

    private fun remove(cannon: Cannon, lost: Boolean) {
        val loc = locAt(cannon.origin, CannonLocs.cannon)
        locRepo.del(loc, LOC_DURATION)
        conRepo.del(cannon.controller)
        byOwner.remove(cannon.owner)
        byController.remove(cannon.controller)
        val player = cannon.player?.takeIf { it.isOwner(cannon) }
        if (player != null) {
            setState(player, if (lost) LOST else 0)
        }
    }

    private fun Player.isOwner(cannon: Cannon): Boolean = isSlotAssigned && uuid == cannon.owner

    private fun setState(player: Player, value: Int) {
        VarPlayerIntMapSetter.set(player, CannonVarps.state, value)
    }

    private fun relink(player: Player) {
        val uuid = player.uuid ?: return
        val cannon = byOwner[uuid]
        if (cannon != null) {
            cannon.player = player
            return
        }
        val state = player.vars[CannonVarps.state]
        if (state and PLACED != 0) {
            setState(player, LOST)
            player.mes("Your cannon has decayed. Speak to Nulodion to obtain a new one.")
        }
    }

    /* Firing */

    private fun tick(controller: Controller) {
        val cannon = byController[controller] ?: return
        controller.aiTimer(1)
        val age = mapClock.cycle - cannon.setupCycle
        val owner = cannon.player?.takeIf { it.isOwner(cannon) }
        if (!cannon.broken && age >= BREAK_CYCLES) {
            cannon.broken = true
            owner?.mes("Your cannon has broken!")
        }
        if (cannon.broken && age >= BREAK_CYCLES + VANISH_CYCLES) {
            owner?.mes("Your cannon has decayed. Speak to Nulodion to obtain a new one.")
            remove(cannon, lost = true)
            if (owner == null) markLostOnLogin(cannon)
            return
        }
        if (cannon.broken || cannon.balls == 0 || owner == null) return
        if (owner.coords.level != cannon.centre.level) return
        if (owner.coords.chebyshevDistance(cannon.centre) > OWNER_RADIUS) return

        cannon.dir = (cannon.dir + 1) % DIRECTIONS
        val loc = locAt(cannon.origin, CannonLocs.cannon)
        val target = findTarget(cannon)
        if (target == null) {
            worldRepo.locAnim(loc, CannonSeqs.turn[cannon.dir])
            return
        }
        worldRepo.locAnim(loc, CannonSeqs.fire[cannon.dir])
        shoot(cannon, owner, target)
    }

    /** Without the owner online the varp is fixed on their next login (see [relink]). */
    private fun markLostOnLogin(cannon: Cannon) {
        cannon.player = null
    }

    private fun findTarget(cannon: Cannon): Npc? {
        val centre = cannon.centre
        return npcRepo
            .findAll(ZoneKey.from(centre), 1)
            .filter { npc ->
                val d = npc.coords.chebyshevDistance(centre)
                d in 1..RANGE &&
                    npc.coords.level == centre.level &&
                    sector(centre, npc.coords) == cannon.dir &&
                    npc.isValidTarget() &&
                    npc.visType.hasOp(InteractionOp.Op2) &&
                    rayCast.hasLineOfSight(centre, npc.coords, extraFlag = BLOCK_PLAYERS)
            }
            .minByOrNull { it.coords.chebyshevDistance(centre) }
    }

    /** 0 = north, then clockwise in 45 degree steps. */
    private fun sector(from: CoordGrid, to: CoordGrid): Int {
        val degrees = Math.toDegrees(atan2((to.x - from.x).toDouble(), (to.z - from.z).toDouble()))
        return ((degrees / 45.0).roundToInt() + DIRECTIONS) % DIRECTIONS
    }

    private fun shoot(cannon: Cannon, owner: Player, target: Npc) {
        cannon.balls--
        val spot = if (cannon.granite) CannonSpots.granite else CannonSpots.steel
        val proj =
            ProjAnim.fromBoundsToNpc(
                Bounds(cannon.centre),
                target,
                spot.id,
                projTypes[CannonProjAnims.arrow],
            )
        worldRepo.projAnim(proj)
        val hit =
            accuracy.rollRangedAccuracy(
                player = owner,
                target = target,
                attackType = RangedAttackType.Heavy,
                attackStyle = null,
                blockType = RangedAttackType.Heavy,
                specMultiplier = 1.0,
                random = random,
            )
        val maxHit = if (cannon.granite) GRANITE_MAX else STEEL_MAX
        val damage = if (hit) random.of(0, maxHit) else 0
        val delay = max(1, 1 + target.coords.chebyshevDistance(cannon.centre) / 3)
        target.queueHit(owner, delay, HitType.Ranged, damage, hitModifier)
        val capped = min(damage, target.hitpoints)
        if (capped > 0) {
            owner.statAdvance(
                stats.ranged,
                capped * XP_PER_DAMAGE * target.resolveCombatXpMultiplier(),
            )
        }
        if (cannon.balls == 0) owner.mes("Your cannon has run out of ammo!")
    }

    private companion object {
        const val MAX_BALLS = 30
        const val STEEL_MAX = 30
        const val GRANITE_MAX = 35
        const val XP_PER_DAMAGE = 2.0
        const val RANGE = 8
        const val OWNER_RADIUS = 16
        const val BANK_RADIUS = 8
        const val DIRECTIONS = 8

        /** 25 and 10 minutes in 0.6s ticks. */
        const val BREAK_CYCLES = 2500
        const val VANISH_CYCLES = 1000

        /** Locs stay until removed explicitly; the controller outlasts any cannon. */
        const val LOC_DURATION = Int.MAX_VALUE
        const val CONTROLLER_DURATION = 1_000_000

        const val PLACED = 1
        const val LOST = 2

        const val BLOCK_PLAYERS = CollisionFlag.BLOCK_PLAYERS
        const val BLOCKED =
            CollisionFlag.LOC or CollisionFlag.BLOCK_WALK or CollisionFlag.GROUND_DECOR

        val SHAPE = LocShape.CentrepieceStraight
        val LAYER = LocLayerConstants.of(SHAPE.id)

        val STAGE_MESSAGES =
            listOf(
                "You place the cannon base on the ground.",
                "You add the stand.",
                "You add the barrels.",
                "You add the furnace.",
            )
    }
}

internal object CannonObjs : ObjReferences() {
    val base = find("twpart1")
    val parts: List<ObjType> = listOf(base, find("twpart2"), find("twpart3"), find("twpart4"))
    val steel = find("mcannonball")
    val granite = find("granite_cannonball")
}

internal object CannonLocs : LocReferences() {
    val base: LocType = find("multicannon_base")
    val stand: LocType = find("multicannon_stand")
    val barrels: LocType = find("multicannon_barrels")
    val cannon: LocType = find("dwarf_multicannon1")
}

internal object CannonSeqs : SeqReferences() {
    private val names = listOf("n", "ne", "e", "se", "s", "sw", "w", "nw")
    val turn = names.map { find("mcannon_${it}_turn") }
    val fire = names.map { find("mcannon_${it}_fire") }
}

internal object CannonSpots : SpotanimReferences() {
    val steel = find("cannonball_travel")
    val granite = find("cannonball_travel_granite")
}

internal object CannonProjAnims : ProjAnimReferences() {
    val arrow = find("arrow")
}

internal object CannonVarps : VarpReferences() {
    /** Server varp (65785): bit 0 cannon placed, bit 1 cannon lost (Nulodion replaces it). */
    val state = find("mcannon_state")
}

internal object CannonVarpBuilds : VarpBuilder() {
    init {
        build("mcannon_state")
    }
}

internal object CannonControllers : ControllerBuilder() {
    /** Controller sym 65700: one per placed cannon, ticking every cycle. */
    val cannon = build("multicannon")
}
