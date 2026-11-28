package org.rsmod.content.quests.trollstronghold

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.deathplateau.DeathPlateau.Companion.DEATH_PLATEAU
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Troll Stronghold (OSRS Wiki). Cache quest varp `troll_quest`, end state 50 (needs Death Plateau;
 * 15 Agility for the climbing rocks). No cache multis key on the varp, so the stages are ours:
 * - 1 Denulth (Burthorpe) says the trolls took Godric. Tenzing sells climbing boots (12 coins) once
 *   Death Plateau is done; the rocks north of the plateau need 15 Agility and worn boots.
 * - 2 Dad, the troll champion in the arena, is beaten (killing him also counts, per the Wiki); the
 *   arena's north gate stays shut until then. The troll pass cave leads on to Trollheim.
 * - The stronghold's top floor has the troll generals; one drops the prison key. Down the stairs to
 *   Burntmeat's kitchen, the prison door at the north end (3) and down again to the jail.
 * - Twig and Berry (30 Thieving to pickpocket, or kill them) carry cell keys 1 and 2: Godric's cell
 *   (4) and Eadgar's (`troll_freed_eadgar`). The jail's back exit opens the secret door outside
 *   (`troll_opened_back_exit`).
 * - 50 Dunstan, once both are free: 1 QP, 2,000 Agility XP, a law talisman (a lost one costs
 *   1,000 coins from him afterwards).
 *
 * Simplified: Dad doesn't stop to beg (beating him is the kill); freed prisoners stay in their
 * cells; the climbing-rock slip chance is a flat 1 in 10.
 */
class TrollStronghold
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TrollStronghold::class.java, "troll_stronghold") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Dad" to TsNpcs.dad,
            "Twig" to TsNpcs.twig,
            "Berry" to TsNpcs.berry,
            "Godric" to TsNpcs.godric,
            "Eadgar" to TsNpcs.eadgar,
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, TsNpcs.denulth, TROLL_STRONGHOLD, wants = { dpDone() && state < COMPLETE }) {
                talkDenulth(it)
            }
            register(npcTypes, TsNpcs.dunstan, TROLL_STRONGHOLD, wants = { dpDone() && state in STARTED until COMPLETE }) {
                talkDunstan(it)
            }
            default(npcTypes, TsNpcs.dunstan) { dunstanAfter(it) }
            default(npcTypes, TsNpcs.tenzing) { talkTenzing(it) }
            default(npcTypes, TsNpcs.dad) { talkDad(it) }
        }
        onEvent<NpcKilledEvent> { npcKilled(this) }

        for (rock in TsLocs.climbingRocks) onLocOpLabel(locTypes, rock, "Climb") { climbRocks(it) }

        onLocOpLabel(locTypes, TsLocs.arena_entrance_left, "Open") { passThroughWall(it) }
        onLocOpLabel(locTypes, TsLocs.arena_entrance_right, "Open") { passThroughWall(it) }
        onLocOpLabel(locTypes, TsLocs.arena_exit_left, "Open") { arenaExit(it) }
        onLocOpLabel(locTypes, TsLocs.arena_exit_right, "Open") { arenaExit(it) }

        onLocOpLabel(locTypes, TsLocs.pass_entrance, "Enter") { jumpFrom(it, PASS_ENTRANCES) }
        onLocOpLabel(locTypes, TsLocs.pass_exit, "Exit") { jumpFrom(it, PASS_EXITS) }

        onLocOpLabel(locTypes, TsLocs.stronghold_door, "Enter") { enterStronghold() }
        // The middle and right exits say "Leave"; the left one's op is "Use".
        for (exit in TsLocs.topExits) {
            if (!onLocOpLabel(locTypes, exit, "Leave") { telejump(STRONGHOLD_OUTSIDE) }) {
                onLocOpLabel(locTypes, exit, "Use") { telejump(STRONGHOLD_OUTSIDE) }
            }
        }
        onLocOpLabel(locTypes, TsLocs.stairs, "Climb-up") { jumpFrom(it, STAIRS_UP) }
        onLocOpLabel(locTypes, TsLocs.stairstop, "Climb-down") { jumpFrom(it, STAIRS_DOWN) }
        onLocOpLabel(locTypes, TsLocs.interior_door, "Open") { passThroughWall(it) }

        onLocOpLabel(locTypes, TsLocs.prison_door, "Unlock") { prisonDoor(it) }
        onOpLocU(TsLocs.prison_door, TsObjs.prison_key) { prisonDoor(it.loc) }

        onNpcOpLabel(npcTypes, TsNpcs.twig, "Pickpocket") { pickpocket(it, TsNpcs.twig_awake, TsObjs.godric_key, "Twig") }
        onNpcOpLabel(npcTypes, TsNpcs.berry, "Pickpocket") {
            pickpocket(it, TsNpcs.berry_awake, TsObjs.eadgar_key, "Berry")
        }

        onLocOpLabel(locTypes, TsLocs.godric_cell, "Unlock") { cellDoor(godric = true, used = null) }
        onLocOpLabel(locTypes, TsLocs.eadgar_cell, "Unlock") { cellDoor(godric = false, used = null) }
        for (key in TsObjs.cellKeys) {
            onOpLocU(TsLocs.godric_cell, key) { cellDoor(godric = true, used = key) }
            onOpLocU(TsLocs.eadgar_cell, key) { cellDoor(godric = false, used = key) }
        }

        onLocOpLabel(locTypes, TsLocs.back_exit, "Open") { backExit() }
        onLocOpLabel(locTypes, TsLocs.secret_door, "Open") { secretDoor() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TROLL_STRONGHOLD)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(TROLL_STRONGHOLD, value)
    }

    private fun ProtectedAccess.dpDone(): Boolean = player.isQuestComplete(DEATH_PLATEAU)

    private fun ProtectedAccess.flag(varbit: VarBitType): Boolean = player.vars[varbit] != 0

    private fun ProtectedAccess.setFlag(varbit: VarBitType) {
        VarPlayerIntMapSetter.set(player, varbit, 1)
    }

    private fun ProtectedAccess.give(obj: ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    /* Denulth, Dunstan, Tenzing */

    private suspend fun ProtectedAccess.talkDenulth(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val weak = player.appearance.combatLevel < RECOMMENDED_COMBAT || stat(stats.agility) < AGILITY_LEVEL
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player's combat level is lower than 50 or have lower than the required skills:" to
                                    { weak }
                            ),
                        options = mapOf("Yes." to { start = true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Denulth", hooks)
                if (start) setState(STARTED)
            }
            STARTED,
            DAD -> runQuestSection(npc, dialogue, "Talking to Denulth after starting the quest")
            PRISON -> runQuestSection(npc, dialogue, "Talking to Denulth again before freeing the prisoners")
            else -> runQuestSection(npc, dialogue, "Talking to Denulth (2)")
        }
    }

    private suspend fun ProtectedAccess.talkDunstan(npc: Npc) {
        if (state < FREED || !flag(TsVarBits.freed_eadgar)) {
            runQuestSection(npc, dialogue, "Talking to Dunstan")
            return
        }
        runQuestSection(npc, dialogue, "Talking to Dunstan (2)")
        give(TsObjs.law_talisman)
        statAdvance(stats.agility, AGILITY_XP * xpMods.get(player, stats.agility))
        completeQuest(TROLL_STRONGHOLD)
    }

    /** After the quest: a replacement law talisman (Wiki: 1,000 coins). Lines authored (not Wiki). */
    private suspend fun ProtectedAccess.dunstanAfter(npc: Npc) {
        if (!player.isQuestComplete(TROLL_STRONGHOLD) || carries(TsObjs.law_talisman)) {
            startDialogue(npc) { chatNpc(happy, "Hello again, friend. Godric is doing well, thanks to you.") }
            return
        }
        var buy = false
        startDialogue(npc) {
            chatNpc(happy, "Lost the heirloom? I found another in the attic. You can have it for 1,000 coins.")
            buy = choice2("Yes, please.", true, "No, thanks.", false)
        }
        if (!buy) return
        if (!carries(TsObjs.coins, TALISMAN_PRICE)) {
            mes("You don't have enough coins.")
            return
        }
        invDel(inv, TsObjs.coins, TALISMAN_PRICE)
        give(TsObjs.law_talisman)
    }

    private suspend fun ProtectedAccess.talkTenzing(npc: Npc) {
        if (!dpDone()) {
            // Authored (not Wiki): Death Plateau's own Tenzing dialogue covers the quest.
            startDialogue(npc) { chatNpc(neutral, "Hello traveller.") }
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has 12 gp:" to { carries(TsObjs.coins, BOOTS_PRICE) },
                        "If the player does not have enough money:" to { !carries(TsObjs.coins, BOOTS_PRICE) },
                    ),
                boxes =
                    mapOf(
                        "Tenzing has given you some Climbing boots." to {
                            if (carries(TsObjs.coins, BOOTS_PRICE)) {
                                invDel(inv, TsObjs.coins, BOOTS_PRICE)
                                give(TsObjs.climbing_boots)
                            }
                            false
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Tenzig", hooks)
    }

    /* The climbing rocks */

    private suspend fun ProtectedAccess.climbRocks(rock: BoundLocInfo) {
        val crossing = CROSSINGS.firstOrNull { rock.coords in it.rocks } ?: return
        if (stat(stats.agility) < AGILITY_LEVEL) {
            mes("You need an Agility level of at least 15 to attempt this.")
            return
        }
        if (TsObjs.boots.none { invTotal(worn, it) > 0 }) {
            mes("You'll need some climbing boots to go that way.")
            return
        }
        mes("You climb onto the rock...")
        delay(2)
        if (random.of(1, SLIP_ODDS) == 1) {
            mes("...but you slip back down.")
            say("Ouch!")
            return
        }
        telejump(crossing.otherSide(player.coords))
        mes("...and you step down the other side.")
    }

    /* Dad and the arena */

    private suspend fun ProtectedAccess.talkDad(npc: Npc) {
        if (state >= DAD) {
            mes("He doesn't seem interested in talking right now.")
            return
        }
        var accept = false
        val hooks = SimpleQuestHooks(options = mapOf("I accept your challenge!" to { accept = true }))
        runQuestSection(npc, dialogue, "Entering Dad's Arena", hooks)
        if (!accept) return
        if (state == STARTED) setFlag(TsVarBits.accepted_challenge)
        npc.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.arenaExit(gate: BoundLocInfo) {
        val leaving = player.coords.z < gate.coords.z
        if (leaving && state in STARTED until DAD) {
            // Authored (not Wiki).
            mes("Dad won't let you through until you have beaten him.")
            return
        }
        passThroughWall(gate)
    }

    /* Moving around Trollheim and the stronghold */

    private fun ProtectedAccess.jumpFrom(loc: BoundLocInfo, table: Map<CoordGrid, CoordGrid>) {
        val dest = table[loc.coords] ?: return
        telejump(dest)
    }

    private fun ProtectedAccess.enterStronghold() {
        if (state in STARTED until COMPLETE) setFlag(TsVarBits.entered_stronghold)
        telejump(STRONGHOLD_INSIDE)
    }

    private suspend fun ProtectedAccess.prisonDoor(door: BoundLocInfo) {
        if (!carries(TsObjs.prison_key) && !player.isQuestComplete(TROLL_STRONGHOLD)) {
            mes("You need a key to unlock this door.")
            return
        }
        val entering = player.coords.x < door.coords.x
        passThroughWall(door)
        if (entering && state == DAD) setState(PRISON)
    }

    private fun ProtectedAccess.backExit() {
        setFlag(TsVarBits.opened_back_exit)
        telejump(SECRET_DOOR_OUTSIDE)
    }

    private fun ProtectedAccess.secretDoor() {
        if (!flag(TsVarBits.opened_back_exit)) {
            // Authored (not Wiki).
            mes("The door won't open from this side.")
            return
        }
        telejump(BACK_EXIT_INSIDE)
    }

    /* The jail */

    private suspend fun ProtectedAccess.pickpocket(guard: Npc, awake: NpcType, key: ObjType, name: String) {
        if (stat(stats.thieving) < THIEVING_LEVEL) {
            // Authored (not Wiki).
            mes("You need a Thieving level of 30 to pick the guard's pocket.")
            return
        }
        mes("You attempt to pick the guard's pocket.")
        delay(2)
        val chance = (PICK_BASE + (stat(stats.thieving) - THIEVING_LEVEL)).coerceAtMost(PICK_MAX)
        if (random.of(1, 100) > chance) {
            runQuestSection(null, dialogue, "Retrieving the keys from $name", failHooks, speakers)
            wake(guard, awake)
            return
        }
        if (carries(key) || pastCell(key)) {
            mes("You pick the guard's pocket but find nothing.")
            return
        }
        mes("You pick the guard's pocket and find a key.")
        give(key)
    }

    /** Only the failed-pickpocket branch of the Twig/Berry sections has a line to show. */
    private val failHooks =
        SimpleQuestHooks(
            defaultCondition = { it.startsWith("If the player chooses") || it.startsWith("If the player fails") }
        )

    private fun ProtectedAccess.pastCell(key: ObjType): Boolean =
        if (key == TsObjs.godric_key) state >= FREED else flag(TsVarBits.freed_eadgar)

    private fun ProtectedAccess.wake(guard: Npc, awake: NpcType) {
        val at = guard.coords
        npcRepo.del(guard, GUARD_AWAKE_TICKS)
        val angry = Npc(npcTypes[awake], at)
        npcRepo.add(angry, GUARD_AWAKE_TICKS)
        angry.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.cellDoor(godric: Boolean, used: ObjType?) {
        val right = if (godric) TsObjs.godric_key else TsObjs.eadgar_key
        if (used != null && used != right) {
            mes("This key doesn't open this door.")
            return
        }
        if (!carries(right)) {
            mes("You need a key to unlock this door.")
            return
        }
        if (godric) {
            if (state < PRISON) return
            runQuestSection(null, dialogue, "Freeing Eadgar and Godric", cellHooks(godric = true), speakers)
            invDel(inv, right)
            if (state == PRISON) setState(FREED)
        } else {
            runQuestSection(null, dialogue, "Freeing Eadgar and Godric", cellHooks(godric = false), speakers)
            invDel(inv, right)
            setFlag(TsVarBits.freed_eadgar)
        }
    }

    private fun cellHooks(godric: Boolean): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player opens Godric's cell with Cell key 1 in their inventory:" to { godric },
                    "If the player opens Eadgar's cell with Cell key 2 in their inventory:" to { !godric },
                )
        )

    /* Kills: Dad, the generals' prison key, the guards' cell keys */

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        val id = event.npc.type.id
        val at = event.npc.coords
        val state = player.questState(TROLL_STRONGHOLD)
        if (state !in STARTED until COMPLETE) return
        when (id) {
            TsNpcs.dad.id ->
                if (state == STARTED) {
                    player.setQuestState(TROLL_STRONGHOLD, DAD)
                    player.mes("You have defeated Dad. The way north is open.") // Authored (not Wiki).
                }
            in TsNpcs.generals.map { it.id } -> objRepo.add(TsObjs.prison_key, at, DROP_TICKS, player)
            TsNpcs.twig.id,
            TsNpcs.twig_awake.id -> objRepo.add(TsObjs.godric_key, at, DROP_TICKS, player)
            TsNpcs.berry.id,
            TsNpcs.berry_awake.id -> objRepo.add(TsObjs.eadgar_key, at, DROP_TICKS, player)
        }
    }

    /** One climbing-rock crossing: the [rocks] tiles and the ground on either side ([a], [b]). */
    private class Crossing(val a: CoordGrid, val b: CoordGrid, val rocks: Set<CoordGrid>) {
        fun otherSide(from: CoordGrid): CoordGrid =
            if (from.chebyshevDistance(a) <= from.chebyshevDistance(b)) b else a
    }

    companion object {
        const val STARTED = 1
        const val DAD = 2
        const val PRISON = 3
        const val FREED = 4
        const val COMPLETE = 50

        private const val RECOMMENDED_COMBAT = 50
        private const val AGILITY_LEVEL = 15
        private const val THIEVING_LEVEL = 30
        private const val AGILITY_XP = 2_000.0
        private const val BOOTS_PRICE = 12
        private const val TALISMAN_PRICE = 1_000
        private const val SLIP_ODDS = 10
        private const val PICK_BASE = 60
        private const val PICK_MAX = 95
        private const val GUARD_AWAKE_TICKS = 100
        private const val DROP_TICKS = 200

        private fun c(x: Int, z: Int, level: Int = 0) = CoordGrid(x, z, level)

        // Landing tiles checked against the cache's terrain and loc collision.
        private val CROSSINGS =
            listOf(
                Crossing(c(2820, 3635), c(2822, 3635), setOf(c(2821, 3635))),
                Crossing(c(2833, 3627), c(2833, 3629), setOf(c(2833, 3628), c(2834, 3628))),
                Crossing(c(2856, 3611), c(2856, 3613), setOf(c(2856, 3612), c(2857, 3612))),
                Crossing(c(2909, 3686), c(2911, 3686), setOf(c(2910, 3686), c(2910, 3687))),
                Crossing(
                    c(2858, 3626),
                    c(2861, 3626),
                    setOf(c(2859, 3626), c(2859, 3627), c(2860, 3626), c(2860, 3627)),
                ),
                Crossing(
                    c(2877, 3622),
                    c(2880, 3622),
                    setOf(c(2878, 3622), c(2878, 3623), c(2879, 3622), c(2879, 3623)),
                ),
            )

        /** The troll pass: south cave mouth (by the arena) and north (towards Trollheim). */
        private val PASS_ENTRANCES = mapOf(c(2903, 3644) to c(2907, 10019), c(2907, 3652) to c(2907, 10035))
        private val PASS_EXITS = mapOf(c(2906, 10017) to c(2904, 3643), c(2906, 10036) to c(2908, 3653))

        private val STRONGHOLD_OUTSIDE = c(2840, 3690)
        private val STRONGHOLD_INSIDE = c(2837, 10090, 2)

        /** Staircase loc coords -> landing on the floor above / below. */
        private val STAIRS_UP =
            mapOf(
                c(2842, 10051, 1) to c(2843, 10053, 2),
                c(2842, 10108, 1) to c(2843, 10107, 2),
                c(2852, 10061, 0) to c(2852, 10060, 1),
                c(2852, 10106, 0) to c(2851, 10107, 1),
            )
        private val STAIRS_DOWN =
            mapOf(
                c(2843, 10051, 2) to c(2843, 10053, 1),
                c(2843, 10108, 2) to c(2843, 10106, 1),
                c(2852, 10061, 1) to c(2852, 10064, 0),
                c(2852, 10107, 1) to c(2851, 10107, 0),
            )

        private val SECRET_DOOR_OUTSIDE = c(2827, 3646)
        private val BACK_EXIT_INSIDE = c(2824, 10050)

        val TROLL_STRONGHOLD =
            QuestDef(
                name = "Troll Stronghold",
                varp = { TsVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("2,000 Agility XP", "Law talisman", "Access to Trollheim and the Troll Stronghold"),
                scrollObj = { TsObjs.law_talisman },
                listRow = "quest_trollstronghold",
            )
    }
}

// Every cache ref Troll Stronghold uses (re-check after cache updates).

internal object TsNpcs : NpcReferences() {
    val denulth = find("death_ig_commander")
    val dunstan = find("death_smithy")
    val tenzing = find("death_sherpa")
    val dad = find("troll_champion")
    val godric = find("troll_godric")
    val eadgar = find("troll_eadgar")
    val twig = find("troll_prison_guard1")
    val berry = find("troll_prison_guard2")
    val twig_awake = find("troll_prison_guard1_awake")
    val berry_awake = find("troll_prison_guard2_awake")
    val generals = listOf(find("troll_general"), find("troll_general2"), find("troll_general3"))
}

internal object TsLocs : LocReferences() {
    val climbingRocks =
        listOf(find("troll_climbingrocks"), find("troll_climbingrocks_top"), find("troll_climbingrocks_bottom"))
    val arena_entrance_left = find("troll_stronghold_arena_entrance_left")
    val arena_entrance_right = find("troll_stronghold_arena_entrance_right")
    val arena_exit_left = find("troll_stronghold_arena_exit_left")
    val arena_exit_right = find("troll_stronghold_arena_exit_right")
    val pass_entrance = find("troll_pass_entrance")
    val pass_exit = find("troll_pass_exit")
    val stronghold_door = find("troll_stronghold_door")
    val topExits =
        listOf(
            find("troll_stronghold_top_exit_left"),
            find("troll_stronghold_top_exit_mid"),
            find("troll_stronghold_top_exit_right"),
        )
    val stairs = find("troll_stronghold_stairs")
    val stairstop = find("troll_stronghold_stairstop")
    val interior_door = find("troll_stronghold_interior_door")
    val prison_door = find("troll_stronghold_prison_door_closed")
    val godric_cell = find("troll_celldoor_godric")
    val eadgar_cell = find("troll_celldoor_eadgar")
    val back_exit = find("troll_stronghold_exit")
    val secret_door = find("troll_stronghold_entrance")
}

internal object TsObjs : ObjReferences() {
    val climbing_boots = find("death_climbingboots")
    val boots = listOf(climbing_boots, find("death_spikedboots"))
    val prison_key = find("troll_key_prison")
    val godric_key = find("troll_key_godric")
    val eadgar_key = find("troll_key_eadgar")
    val cellKeys = listOf(godric_key, eadgar_key)
    val law_talisman = find("law_talisman")
    val coins = find("coins")
}

internal object TsVarps : VarpReferences() {
    val quest = find("troll_quest")
}

internal object TsVarBits : VarBitReferences() {
    val freed_eadgar = find("troll_freed_eadgar")
    val opened_back_exit = find("troll_opened_back_exit")
    val entered_stronghold = find("troll_entered_stronghold")
    val accepted_challenge = find("troll_accepted_challenge")
}
