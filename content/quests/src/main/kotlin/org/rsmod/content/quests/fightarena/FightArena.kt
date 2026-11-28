package org.rsmod.content.quests.fightarena

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fight Arena (OSRS Wiki). Cache quest varp `arenaquest`, end state 14. The arena's multinpcs fix
 * the late stages: Sammy's cell shows until 5, the Khazard Ogre's cage until 7, the scorpion's
 * until 9, the Bouncer's until 10, General Khazard until 12:
 * - 1 Lady Servil asks you to free her son; 2 the off-duty guard's chest gives Khazard armour;
 *   3 the Head Guard (armour worn) mentions Khali brew; 4 the Khazard barman sells it (5 coins);
 *   5 the drunk Head Guard hands over the cell keys; 6 the keys free Sammy and you're thrown into
 *   the arena: the Khazard Ogre (8), then the jail with Hengrad (9), the Khazard Scorpion (10),
 *   the Bouncer (11) and General Khazard himself (13), each spawned for you; 14 Lady Servil: 2 QP,
 *   12,175 Attack and 2,175 Thieving xp, 1,000 coins.
 *
 * Simplified: no cutscenes (the arena entrances are telejumps), and losing a fight just means
 * leaving through the arena door and re-entering.
 */
class FightArena
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(FightArena::class.java, "fight_arena") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "General Khazard" to FaNpcs.khazard_talk,
            "Hengrad" to FaNpcs.hengrad,
            "Sammy Servil" to FaNpcs.sammy,
            "Lady Servil" to FaNpcs.lady,
        )
    }

    /** Each player's current arena opponent, by player uid. */
    private val opponents = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, FaNpcs.lady, FIGHT_ARENA, wants = { state < COMPLETE }) { talkLady(it) }
            register(npcTypes, FaNpcs.head_guard, FIGHT_ARENA, wants = { state in STARTED until COMPLETE }) {
                talkHeadGuard(it)
            }
            register(npcTypes, FaNpcs.barman, FIGHT_ARENA, wants = { state >= HEARD_BREW && state < COMPLETE }) {
                talkBarman(it)
            }
            register(npcTypes, FaNpcs.sammy, FIGHT_ARENA, wants = { state in STARTED until COMPLETE }) {
                runQuestSection(it, dialogue, "Talking to Sammy Servil")
            }
            register(npcTypes, FaNpcs.hengrad, FIGHT_ARENA, wants = { state in STARTED until COMPLETE }) {
                talkHengrad(it)
            }
        }
        onLocOpLabel(locTypes, FaLocs.chest, "Search") { searchChest() }
        onOpLocU(FaLocs.sammy_cell, FaObjs.keys) { freeSammy() }
        onLocOpLabel(locTypes, FaLocs.sammy_cell, "Open") {
            runQuestSection(null, dialogue, "Opening a prison gate")
        }
        onLocOpLabel(locTypes, FaLocs.arena_door, "Open") { arenaDoor(it) }
        onEvent<NpcKilledEvent> { killed(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(FIGHT_ARENA)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(FIGHT_ARENA, value)
    }

    private fun ProtectedAccess.armoured(): Boolean =
        invTotal(worn, FaObjs.helmet) > 0 && invTotal(worn, FaObjs.platebody) > 0

    private fun ProtectedAccess.armourHooks(extra: Map<String, () -> Boolean> = emptyMap()) =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has the Khazard armour equipped:" to { armoured() },
                    "If the player does not have the Khazard armour equipped:" to { !armoured() },
                    "If the player doesn't have the Khazard armour equipped:" to { !armoured() },
                    "With the Khazard armour equipped:" to { armoured() },
                    "Without the Khazard armour equipped:" to { !armoured() },
                    "If the player has a full inventory:" to { inv.freeSpace() < 2 },
                ) + extra
        )

    /* Lady Servil, the armour and the Head Guard */

    private suspend fun ProtectedAccess.talkLady(npc: Npc) {
        when {
            state == 0 -> {
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                runQuestSection(npc, dialogue, "Talking to Lady Servil", hooks)
            }
            state == STARTED -> runQuestSection(npc, dialogue, "Talking to Lady Servil after starting the quest")
            state < SAMMY_FREE -> runQuestSection(npc, dialogue, "Talking to Lady Servil after getting armour")
            state < KHAZARD_DOWN -> runQuestSection(npc, dialogue, "Talking to Lady Servil after freeing Sammy from the jail")
            else -> {
                runQuestSection(npc, dialogue, "Talking to Lady Servil after defeating Khazard")
                statAdvance(stats.attack, 12175.0 * xpMods.get(player, stats.attack))
                statAdvance(stats.thieving, 2175.0 * xpMods.get(player, stats.thieving))
                invAddOrDrop(objRepo, objs.coins, 1000)
                completeQuest(FIGHT_ARENA)
            }
        }
    }

    private suspend fun ProtectedAccess.searchChest() {
        if (state < STARTED) {
            runQuestSection(null, dialogue, "Searching the armour chest")
            return
        }
        if (carries(FaObjs.helmet) || armoured()) {
            mes("The chest is empty.")
            return
        }
        runQuestSection(null, dialogue, "Searching the armour chest (2)", armourHooks())
        if (inv.freeSpace() >= 2) {
            invAdd(inv, FaObjs.helmet)
            invAdd(inv, FaObjs.platebody)
            if (state == STARTED) setState(ARMOUR)
        }
    }

    private suspend fun ProtectedAccess.talkHeadGuard(npc: Npc) {
        when {
            !armoured() -> runQuestSection(npc, dialogue, "Talking to the Head Guard without Khazard armour equipped")
            state == ARMOUR -> {
                runQuestSection(npc, dialogue, "Talking to the Head Guard with Khazard armour equipped")
                setState(HEARD_BREW)
            }
            state == HEARD_BREW && !carries(FaObjs.brew) -> runQuestSection(npc, dialogue, "Talking to the Head Guard again")
            state <= BREW && carries(FaObjs.brew) -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "You hand a bottle of Khali brew to the Head Guard and he quickly drinks it all." to {
                                    invDel(inv, FaObjs.brew)
                                    false
                                },
                                "The Head Guard gives you some keys." to {
                                    invAddOrDrop(objRepo, FaObjs.keys)
                                    false
                                },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to the Head Guard while holding Khali brew", hooks)
                setState(KEYS)
            }
            state == KEYS && !carries(FaObjs.keys) -> {
                runQuestSection(npc, dialogue, "Talking to the Head Guard after losing the keys")
                invAddOrDrop(objRepo, FaObjs.keys)
            }
            else -> runQuestSection(npc, dialogue, "Talking to the Head Guard after getting the keys")
        }
    }

    private suspend fun ProtectedAccess.talkBarman(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have 5 coins:" to { invTotal(inv, objs.coins) < BREW_PRICE },
                        "If the player has a full inventory:" to { inv.freeSpace() == 0 },
                    ),
                boxes =
                    mapOf(
                        "The barman gives you a Khali brew in exchange for five coins." to {
                            invDel(inv, objs.coins, BREW_PRICE)
                            invAdd(inv, FaObjs.brew)
                            if (state == HEARD_BREW) setState(BREW)
                            false
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Khazard barman after learning about the Khali brew", hooks)
    }

    /* Sammy, the arena and the fights */

    private suspend fun ProtectedAccess.freeSammy() {
        if (state != KEYS) return
        runQuestSection(null, dialogue, "Talking to Sammy Servil after getting the keys", speakers = speakers)
        setState(SAMMY_FREE)
        enterArena()
    }

    private suspend fun ProtectedAccess.arenaDoor(door: BoundLocInfo) {
        val inside = player.coords.x < door.coords.x
        if (inside) {
            if (state == OGRE_DOWN) {
                telejump(CELL)
                return
            }
            if (state in SAMMY_FREE until KHAZARD_DOWN) {
                runQuestSection(null, dialogue, "Trying to exit the arena through the door")
                return
            }
            passThroughWall(door)
            return
        }
        if (state in SAMMY_FREE until KHAZARD_DOWN) {
            runQuestSection(null, dialogue, "Re-entering the arena")
            enterArena()
            return
        }
        runQuestSection(null, dialogue, "Opening door to the arena")
    }

    private suspend fun ProtectedAccess.enterArena() {
        telejump(ARENA)
        val type =
            when (state) {
                SAMMY_FREE -> FaNpcs.ogre
                JAILED -> FaNpcs.scorpion
                SCORPION_DOWN -> FaNpcs.bouncer
                BOUNCER_DOWN -> FaNpcs.khazard
                else -> return
            }
        val current = opponents[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        val npc = Npc(npcTypes[type], ARENA_OPPONENT)
        npcRepo.add(npc, OPPONENT_TICKS)
        opponents[player.uid.packed] = npc
        npc.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.talkHengrad(npc: Npc) {
        if (state == OGRE_DOWN) {
            runQuestSection(npc, dialogue, "Talking to Hengrad in the jail cell", speakers = speakers)
            setState(JAILED)
            enterArena()
            return
        }
        runQuestSection(npc, dialogue, "Talking to Hengrad")
    }

    private fun killed(event: NpcKilledEvent) {
        val player = event.hero
        val state = player.questState(FIGHT_ARENA)
        val next =
            when (event.npc.type.id) {
                FaNpcs.ogre.id -> if (state == SAMMY_FREE) OGRE_DOWN else return
                FaNpcs.scorpion.id -> if (state == JAILED) SCORPION_DOWN else return
                FaNpcs.bouncer.id -> if (state == SCORPION_DOWN) BOUNCER_DOWN else return
                FaNpcs.khazard.id -> if (state == BOUNCER_DOWN) KHAZARD_DOWN else return
                else -> return
            }
        opponents.remove(player.uid.packed)
        player.setQuestState(FIGHT_ARENA, next)
        when (next) {
            OGRE_DOWN -> {
                // Authored line (not Wiki): Khazard's guards lead you to the cells.
                player.mes("Khazard's guards are waiting at the arena door to take you to the cells.")
            }
            SCORPION_DOWN, BOUNCER_DOWN -> {
                // Authored line (not Wiki): Khazard's next beast is released straight away.
                player.mes("General Khazard sends in another opponent!")
                val type = if (next == SCORPION_DOWN) FaNpcs.bouncer else FaNpcs.khazard
                val npc = Npc(npcTypes[type], ARENA_OPPONENT)
                npcRepo.add(npc, OPPONENT_TICKS)
                opponents[player.uid.packed] = npc
                npc.opPlayer2(player, aiInteractions)
            }
            KHAZARD_DOWN -> player.mes("General Khazard is defeated! Return to Lady Servil.")
        }
    }

    companion object {
        const val STARTED = 1
        const val ARMOUR = 2
        const val HEARD_BREW = 3
        const val BREW = 4
        const val KEYS = 5
        const val SAMMY_FREE = 6
        const val OGRE_DOWN = 8
        const val JAILED = 9
        const val SCORPION_DOWN = 10
        const val BOUNCER_DOWN = 11
        const val KHAZARD_DOWN = 13
        const val COMPLETE = 14

        private const val BREW_PRICE = 5
        private const val OPPONENT_TICKS = 1000

        /** The arena floor (Sammy's arena spot 2602,3154), and Hengrad's cell (2600,3144). */
        private val ARENA = CoordGrid(2602, 3155, 0)
        private val ARENA_OPPONENT = CoordGrid(2597, 3156, 0)
        private val CELL = CoordGrid(2600, 3144, 0)

        val FIGHT_ARENA =
            QuestDef(
                name = "Fight Arena",
                varp = { FaVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("12,175 Attack XP", "2,175 Thieving XP", "1,000 coins"),
                scrollObj = { FaObjs.helmet },
                listRow = "quest_fightarena",
            )
    }
}

// Every cache ref Fight Arena uses (re-check after cache updates).

internal object FaNpcs : NpcReferences() {
    val lady = find("lady_servil_vis")
    val head_guard = find("arena_guard2")
    val barman = find("khazard_barman")
    val sammy = find("sammy_servil_vis")
    val hengrad = find("hengrad")
    val ogre = find("arena_ogre")
    val scorpion = find("arena_scorpion")
    val bouncer = find("arena_bouncer")
    val khazard = find("general_khazard")
    val khazard_talk = find("general_khazard_vis")
}

internal object FaLocs : LocReferences() {
    val chest = find("arena_guard_chest_shut")
    val sammy_cell = find("arena_jeremydoor")
    val arena_door = find("fightarena_door2")
}

internal object FaObjs : ObjReferences() {
    val helmet = find("khazard_helmet")
    val platebody = find("khazard_platemail")
    val keys = find("khazard_cellkeys")
    val brew = find("khali_brew")
}

internal object FaVarps : VarpReferences() {
    val quest = find("arenaquest")
}
