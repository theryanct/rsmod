package org.rsmod.content.quests.templeofikov

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerTimer
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.templeofikov.TempleOfIkov.Companion.GATE_OPEN
import org.rsmod.content.quests.templeofikov.TempleOfIkov.Companion.STARTED
import org.rsmod.content.quests.templeofikov.TempleOfIkov.Companion.TEMPLE_OF_IKOV
import org.rsmod.content.quests.templeofikov.TempleOfIkov.Companion.THIEVING_LEVEL
import org.rsmod.content.quests.templeofikov.TempleOfIkov.Companion.WARRIOR_DEAD
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * The Temple of Ikov dungeon (see [TempleOfIkov] for the stages): the boots cellar, the Chamber of
 * Fear (pendant gate, unsteady bridge, lever), the south gate and ice chamber chests, the trap
 * lever, the Fire Warrior of Lesarkus and the shortcuts.
 *
 * Positions are from the rev 243 map (tools/agility/maplocs.py plus a terrain decode): the bridge is
 * the level-1 pier at x 2646-2652, z 9828-9829 whose middle tile is boxed in by server-side
 * invisible walls, so a 1-tick timer (armed at the north gate) carries you across when you step on
 * an end tile (2648 from the west, 2650 from the east). The boots, web and knife are already world
 * spawns / handled (web slashing is WildernessAccess', the brick wall ClockTower's).
 *
 * Simplified: the weight check sums inventory + worn obj weights itself, counting worn boots of
 * lightness as their worn form (-4.535 kg; the cache has no transform so the weight shown doesn't
 * change) - other weight-reducing gear counts if its worn form is what's worn; the bridge is crossed
 * by a teleport, not a walk; the dark-stairs check keeps you upstairs instead of the dark cellar copy;
 * the Fire Warrior fights with plain npc combat (no fire bolt spell or knockback animation), lasts 2
 * minutes and boasts on a no-damage hit without ending the fight; the trap-lever state and the arrow
 * chest are per login; the fitted lever stays 5 minutes (Wiki: leave and it must be fetched again).
 */
class IkovDungeon
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val playerList: PlayerList,
    private val collision: CollisionFlagMap,
    private val aiInteractions: AiPlayerInteractions,
    private val mapClock: MapClock,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TempleOfIkov::class.java, "temple_of_ikov") }

    /** Players who found the trap on the lever (until they pull it). */
    private val disarmed = HashSet<Int>()

    /** Players who pulled the disarmed trap lever (the door stays unlocked until logout). */
    private val trapOpen = HashSet<Int>()

    /** Which of the six chests holds the ice arrows, per player. */
    private val arrowChest = HashMap<Int, Int>()

    /** The Fire Warrior summoned for each player (single player, but keyed for safety). */
    private val warriors = HashMap<Int, Npc>()

    /** When each Fire Warrior last boasted (map clock), so the boast isn't spammed. */
    private val boasts = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        // The boots cellar.
        bindLoc(IkLocs.stairs_down, "Climb-down") { climbDown() }
        bindLoc(IkLocs.stairs_up, "Climb-up") { climbUp() }

        // The gates by the entrance.
        for (gate in IkLocs.fear_gates) bindLoc(gate, "Open") { openFearGate(it) }
        for (gate in IkLocs.lever_gates) bindLoc(gate, "Open") { openLeverGate(it) }
        bindLocU(IkLocs.lever_bracket, IkObjs.lever) { fitLever(it) }
        bindLoc(IkLocs.mended_lever, "Pull") { pullMendedLever(it) }
        onPlayerTimer(IkTimers.bridge) { bridgeTick() }

        // The ice chamber.
        bindLoc(IkLocs.chest_closed, "Open") { locRepo.change(it, IkLocs.chest_open, CHEST_TICKS) }
        bindLoc(IkLocs.chest_open, "Search") { searchChest(it) }
        bindLoc(IkLocs.chest_open, "Shut") { locRepo.change(it, IkLocs.chest_closed, 1) }

        // The trap lever and the Fire Warrior.
        bindLoc(IkLocs.trap_lever, "Search for traps") { searchTrapLever() }
        bindLoc(IkLocs.trap_lever, "Pull") { pullTrapLever() }
        bindLoc(IkLocs.trap_ladder, "Climb-up") { telejumpFree(TRAP_ALCOVE) }
        bindLoc(IkLocs.trap_lever_door, "Open") { openTrapLeverDoor(it) }
        bindLoc(IkLocs.fire_warrior_door, "Open") { openFireWarriorDoor(it) }
        val warrior = IkNpcs.fire_warrior
        val talk = npcTypes[warrior].op.indexOfFirst { it.equals("Talk-to", ignoreCase = true) } + 1
        if (talk > 0 && !isNpcOpBound(warrior, talk)) {
            onNpcOpLabel(npcTypes, warrior, "Talk-to") { talkFireWarrior(it) }
        }
        onModifyNpcHit(warrior) { iceArrowsOnly(npc, hit) }
        onEvent<NpcKilledEvent> { warriorKilled(this) }

        // The shortcuts.
        bindLoc(IkLocs.shiny_key_door, "Open") { openShinyKeyDoor(it) }
        bindLoc(IkLocs.surface_trapdoor, "Open") { telejumpFree(LAVA_LADDER_FOOT) }
    }

    /** Binds [loc]'s op [label] unless another script already has it (duplicates crash boot). */
    private fun ScriptContext.bindLoc(
        loc: LocType,
        label: String,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        val op = locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(loc, op)) return
        onLocOpLabel(locTypes, loc, label, action)
    }

    /** Binds [obj] used on [loc] unless another script has that pair (duplicates crash boot). */
    private fun ScriptContext.bindLocU(
        loc: LocType,
        obj: ObjType,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        if (eventBus.contains(LocUEvents.Op::class.java, EventBus.composeLongKey(loc.id, obj.id))) return
        onOpLocU(loc, obj) { action(it.loc) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TEMPLE_OF_IKOV)

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: IkHooks = IkHooks(this)) {
        runQuestSection(npc, dialogue, title, hooks, ikSpeakers)
    }

    /* The boots cellar */

    private fun ProtectedAccess.hasLight(): Boolean =
        IkObjs.lights.any { carries(it) || invTotal(worn, it) > 0 }

    private suspend fun ProtectedAccess.climbDown() {
        if (!hasLight()) {
            section(null, "Going downstairs without a light source")
            return
        }
        telejumpFree(CELLAR_LANDING)
    }

    private suspend fun ProtectedAccess.climbUp() {
        telejumpFree(CELLAR_STAIRS_TOP)
    }

    /* The Chamber of Fear */

    private suspend fun ProtectedAccess.openFearGate(gate: BoundLocInfo) {
        val outside = player.coords.z < gate.coords.z
        val free = player.isQuestComplete(TEMPLE_OF_IKOV) // Wiki: no pendant needed since 2024.
        if (outside && !free && invTotal(worn, IkObjs.pendant_lucien) == 0) {
            mes("As you reach to open the door a great terror overcomes you!") // Wiki {{mes}}
            return
        }
        passThroughWall(gate)
        if (inBridgeRoom(player.coords)) timer(IkTimers.bridge, 1)
    }

    private fun inBridgeRoom(at: CoordGrid): Boolean =
        at.level == 0 && at.x in BRIDGE_ROOM_X && at.z in BRIDGE_ROOM_Z

    private suspend fun ProtectedAccess.bridgeTick() {
        val at = player.coords
        if (!inBridgeRoom(at)) {
            clearTimer(IkTimers.bridge)
            return
        }
        if (at.z !in BRIDGE_Z) return
        when (at.x) {
            BRIDGE_EAST_END -> crossBridge(BRIDGE_WEST_LANDING)
            BRIDGE_WEST_END -> crossBridge(BRIDGE_EAST_LANDING)
        }
    }

    private suspend fun ProtectedAccess.crossBridge(landingX: Int) {
        val z = player.coords.z
        if (weightGrams() <= MAX_BRIDGE_GRAMS) {
            mes("You carefully make your way across the unsteady bridge.") // Authored (not Wiki).
            telejump(CoordGrid(landingX, z, 0))
            return
        }
        mes("The bridge gives way under your weight!") // Wiki {{mes}}
        telejumpFree(LAVA_LANDING)
        say("Ow! Hot! Hot!")
        player.queueHit(delay = 0, type = HitType.Typeless, damage = FALL_DAMAGE)
        mes("Good thing that lava was shallow!") // Wiki {{mes}}
    }

    /** Inventory + worn weight; worn boots of lightness count as their worn (-10lb) form. */
    private fun ProtectedAccess.weightGrams(): Int {
        var grams = 0
        for (obj in inv.objs) if (obj != null) grams += objTypes[obj].weight
        for (obj in worn.objs) {
            if (obj == null) continue
            grams += if (obj.id == IkObjs.boots.id) objTypes[IkObjs.boots_worn].weight else objTypes[obj].weight
        }
        return grams
    }

    private suspend fun ProtectedAccess.fitLever(bracket: BoundLocInfo) {
        if (!carries(IkObjs.lever)) return
        invDel(inv, IkObjs.lever)
        locRepo.change(bracket, IkLocs.mended_lever, LEVER_TICKS)
        mesbox("You fit the lever into the bracket.")
    }

    private suspend fun ProtectedAccess.pullMendedLever(lever: BoundLocInfo) {
        locRepo.change(lever, IkLocs.lever_down, LEVER_TICKS)
        section(null, "Pulling the lever")
        if (state == STARTED) player.setQuestState(TEMPLE_OF_IKOV, GATE_OPEN)
    }

    private suspend fun ProtectedAccess.openLeverGate(gate: BoundLocInfo) {
        val north = player.coords.z >= gate.coords.z
        if (north && state < GATE_OPEN && !player.isQuestComplete(TEMPLE_OF_IKOV)) {
            section(null, "Opening the south Gate while it's still locked")
            return
        }
        passThroughWall(gate)
    }

    /* The ice chamber */

    private suspend fun ProtectedAccess.searchChest(chest: BoundLocInfo) {
        val index = CHESTS.indexOf(chest.coords)
        val key = player.uid.packed
        val full = index >= 0 && arrowChest.getOrPut(key) { random.of(0, CHESTS.size - 1) } == index
        val hooks =
            IkHooks(
                this,
                conditions =
                    mapOf(
                        "If the chest has arrows:" to { full },
                        "If the chest is empty:" to { !full },
                    ),
            )
        if (full) {
            invAdd(inv, IkObjs.ice_arrow, random.of(1, MAX_ARROWS))
            arrowChest[key] = random.of(0, CHESTS.size - 1)
        }
        section(null, "Searching ice arrow chest", hooks)
    }

    /* The trap lever */

    private suspend fun ProtectedAccess.searchTrapLever() {
        val skilled = stat(stats.thieving) >= THIEVING_LEVEL
        val hooks =
            IkHooks(
                this,
                conditions =
                    mapOf(
                        "With 42 Thieving:" to { skilled },
                        "Without 42 Thieving:" to { !skilled },
                    ),
            )
        section(null, "Search for traps lever", hooks)
        if (skilled) disarmed += player.uid.packed
    }

    private suspend fun ProtectedAccess.pullTrapLever() {
        val key = player.uid.packed
        if (disarmed.remove(key)) {
            trapOpen += key
            mes("You pull the lever and hear a click from the door nearby.") // Authored (not Wiki).
            return
        }
        mes("You fall through a trapdoor onto some spikes!") // Authored (Wiki: the player falls).
        telejumpFree(TRAP_PIT)
        say("Ouch!")
        player.queueHit(delay = 0, type = HitType.Typeless, damage = FALL_DAMAGE)
    }

    private suspend fun ProtectedAccess.openTrapLeverDoor(door: BoundLocInfo) {
        val inside = player.coords.z > door.coords.z
        val open = player.uid.packed in trapOpen || state >= WARRIOR_DEAD || player.isQuestComplete(TEMPLE_OF_IKOV)
        if (!inside && !open) {
            mes("The door is locked.") // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    /* The Fire Warrior of Lesarkus */

    private suspend fun ProtectedAccess.openFireWarriorDoor(door: BoundLocInfo) {
        val beyond = player.coords.z > door.coords.z
        if (beyond || state >= WARRIOR_DEAD || player.isQuestComplete(TEMPLE_OF_IKOV)) {
            passThroughWall(door)
            return
        }
        val existing = warriors[player.uid.packed]
        val warrior =
            if (existing != null && existing.isSlotAssigned && existing.hitpoints > 0) {
                existing
            } else {
                Npc(npcTypes[IkNpcs.fire_warrior], freeTile(WARRIOR_SPAWN) ?: WARRIOR_SPAWN).also {
                    npcRepo.add(it, WARRIOR_TICKS)
                    warriors[player.uid.packed] = it
                }
            }
        warrior.say("You will not pass!")
        delay(1)
        warrior.say("Amitus! Setitii!")
        telejumpFree(player.coords.translate(0, -KNOCKBACK))
    }

    private suspend fun ProtectedAccess.talkFireWarrior(npc: Npc) {
        var attack = false
        val hooks = IkHooks(this, actions = mapOf("The Fire Warrior attacks." to { attack = true }))
        section(npc, "Talking to the Fire Warrior of Lesarkus", hooks)
        if (attack) npc.opPlayer2(player, aiInteractions)
    }

    /** Only ice arrows hurt him: fired, or worn while throwing (as the Wiki notes). */
    private fun iceArrowsOnly(npc: Npc, hit: HitBuilder) {
        if (!hit.isFromPlayer) return
        if (hit.isSecondaryObj(IkObjs.ice_arrow)) return
        val uid = hit.sourceUid ?: return
        val player = PlayerUid(uid).resolve(playerList)
        if (player != null && hit.type == HitType.Ranged && IkObjs.ice_arrow in player.worn) return
        hit.damage = 0
        val last = boasts[npc.slotId]
        if (last == null || mapClock.cycle - last >= BOAST_TICKS) {
            boasts[npc.slotId] = mapClock.cycle
            npc.say("Your puny weapons do nothing against me human! Come back when you can give me a real fight!")
        }
    }

    private fun warriorKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != IkNpcs.fire_warrior.id) return
        val player = event.hero
        warriors.remove(player.uid.packed)
        val state = player.questState(TEMPLE_OF_IKOV)
        if (state in STARTED until WARRIOR_DEAD) player.setQuestState(TEMPLE_OF_IKOV, WARRIOR_DEAD)
    }

    /* The shortcuts */

    private suspend fun ProtectedAccess.openShinyKeyDoor(door: BoundLocInfo) {
        // McGrubor's house (z 3489-3496) is south of the door (wall on the tile's north edge).
        val inside = player.coords.z <= door.coords.z
        if (!inside && !carries(IkObjs.shiny_key)) {
            mes("The door is locked.") // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    /* Moving */

    private suspend fun ProtectedAccess.telejumpFree(target: CoordGrid) {
        telejump(freeTile(target) ?: target)
        delay(1)
    }

    private fun freeTile(target: CoordGrid): CoordGrid? {
        for (radius in 0..FREE_RADIUS) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                    val tile = target.translate(dx, dz)
                    if (!collision.isZoneAllocated(tile.x, tile.z, tile.level)) continue
                    if (collision[tile.x, tile.z, tile.level] and BLOCKED == 0) return tile
                }
            }
        }
        return null
    }

    private companion object {
        const val CHEST_TICKS = 100
        const val LEVER_TICKS = 500
        const val WARRIOR_TICKS = 200
        const val BOAST_TICKS = 10
        const val MAX_ARROWS = 5
        const val FALL_DAMAGE = 20
        const val KNOCKBACK = 2
        const val FREE_RADIUS = 4
        /** Wiki: the bridge holds -1 kg or less. */
        const val MAX_BRIDGE_GRAMS = -1000
        const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC

        /* Positions from the rev 243 map (see the class doc). */
        val BRIDGE_ROOM_X = 2634..2673
        val BRIDGE_ROOM_Z = 9815..9834
        val BRIDGE_Z = 9828..9829
        const val BRIDGE_WEST_END = 2648
        const val BRIDGE_EAST_END = 2650
        const val BRIDGE_WEST_LANDING = 2647
        const val BRIDGE_EAST_LANDING = 2651
        /** The shallow lava strip below the bridge, north of the ladder (2637,9808) to the surface. */
        val LAVA_LANDING = CoordGrid(2641, 9814, 0)
        /** Beside the lava ladder, for the surface trapdoor (2637,3408). */
        val LAVA_LADDER_FOOT = CoordGrid(2637, 9809, 0)
        /** The boots cellar (stairs 2638,9763) and the top of its stairs (2650,9804). */
        val CELLAR_LANDING = CoordGrid(2641, 9764, 0)
        val CELLAR_STAIRS_TOP = CoordGrid(2648, 9806, 0)
        /** The trap lever's alcove (lever 2665,9855) and the spike pit (ladder 2682,9849). */
        val TRAP_ALCOVE = CoordGrid(2665, 9852, 0)
        val TRAP_PIT = CoordGrid(2682, 9851, 0)
        /** In the summoning room south of the Fire Warrior's door (2646,9870). */
        val WARRIOR_SPAWN = CoordGrid(2646, 9867, 0)
        /** The six ice chamber chests (`ikov_chestclosed` / `ikov_chestopen`). */
        val CHESTS =
            listOf(
                CoordGrid(2710, 9850, 0),
                CoordGrid(2719, 9838, 0),
                CoordGrid(2729, 9850, 0),
                CoordGrid(2738, 9835, 0),
                CoordGrid(2745, 9821, 0),
                CoordGrid(2747, 9848, 0),
            )
    }
}
