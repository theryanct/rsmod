package org.rsmod.content.quests.blackknights

import jakarta.inject.Inject
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questPoints
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Black Knights' Fortress (OSRS Wiki). `spy` varp (cache db end state 4): 0, 1 Sir Amik's spy
 * mission, 2 overheard the witch's invincibility potion at the grill, 3 ruined it with a normal
 * cabbage through the hole, 4 complete. Reward: 2,500 coins, 3 QP.
 *
 * Fortress: the guards' sturdy door needs the guard disguise worn (bronze med helm + iron
 * chainbody); fake walls Push through; the upstairs sturdy door is the Black Knights' meeting
 * (going in sets them on you). Needs 12 QP to start when the QuestGates toggle is on.
 *
 * Not done: the dossier (no such obj in the rev 233 cache - add it after the 243 cutover).
 */
class BlackKnightsFortress
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(BlackKnightsFortress::class.java, "black_knights_fortress")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Fortress Guard" to BkfNpcs.guard,
            "Black Knight Captain" to BkfNpcs.captain,
            "Witch" to BkfNpcs.witch,
            "Greldo" to BkfNpcs.greldo,
            "Black Cat" to BkfNpcs.cat,
            "Black Knight" to BkfNpcs.black_knight,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, BkfNpcs.amik, "Talk-to") { talkAmik(it) }
        for (guard in BkfNpcs.guards) {
            onNpcOpLabel(npcTypes, guard, "Talk-to") { talkGuard(it) }
        }
        onLocOpLabel(locTypes, BkfLocs.guard_door, "Open") { guardDoor(it) }
        onLocOpLabel(locTypes, BkfLocs.inner_door, "Open") { passThroughWall(it) }
        onLocOpLabel(locTypes, BkfLocs.meeting_door, "Open") { meetingDoor(it) }
        onLocOpLabel(locTypes, BkfLocs.fake_wall, "Push") { pushWall(it) }
        onLocOpLabel(locTypes, BkfLocs.grill, "Listen-at") { listen() }
        onOpLocU(BkfLocs.hole, BkfObjs.cabbage) { cabbage(magic = false) }
        onOpLocU(BkfLocs.hole, BkfObjs.magic_cabbage) { cabbage(magic = true) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BLACK_KNIGHTS_FORTRESS)

    private fun ProtectedAccess.disguised(): Boolean =
        invTotal(worn, BkfObjs.helm) > 0 && invTotal(worn, BkfObjs.chainbody) > 0

    private suspend fun ProtectedAccess.talkAmik(npc: Npc) {
        var paid = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has no inventory space:" to { false },
                        "If the player lost their dossier:" to { false },
                    ),
                options = mapOf("Yes." to { if (state == 0) start() }),
                boxes =
                    mapOf(
                        "Sir Amik hands you 2500 coins." to {
                            paid = true
                            false
                        },
                    ),
            )
        when (state) {
            0 -> {
                if (player.isFeatureOn(Feature.QuestGates) && player.questPoints < REQUIRED_QP) {
                    startDialogue(npc) {
                        chatNpc(
                            neutral,
                            "I am the leader of the White Knights of Falador. I'm afraid I only " +
                                "trust adventurers with at least $REQUIRED_QP quest points.",
                        )
                    }
                    return
                }
                runQuestSection(npc, dialogue, "Starting the quest", hooks)
            }
            1 -> runQuestSection(npc, dialogue, "Speaking to Sir Amik Varze again", hooks)
            2 -> runQuestSection(npc, dialogue, "Reporting back to Sir Amik Varze", hooks)
            3 -> {
                runQuestSection(npc, dialogue, "Returning to Sir Amik Varze", hooks)
                if (paid) {
                    invAddOrDrop(objRepo, BkfObjs.coins, REWARD_COINS)
                    completeQuest(BLACK_KNIGHTS_FORTRESS)
                }
            }
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Thank you again for dealing with the Black Knights.")
                }
        }
    }

    private fun ProtectedAccess.start() {
        player.setQuestState(BLACK_KNIGHTS_FORTRESS, 1)
    }

    private suspend fun ProtectedAccess.talkGuard(npc: Npc) {
        val section =
            if (disguised()) {
                "Talking to the guards while wearing a disguise"
            } else {
                "Talking to the guards while not wearing a disguise"
            }
        runQuestSection(npc, dialogue, section, speakers = speakers)
    }

    private suspend fun ProtectedAccess.guardDoor(door: BoundLocInfo) {
        if (disguised()) {
            passThroughWall(door)
            return
        }
        runQuestSection(
            null,
            dialogue,
            "Trying to open the sturdy door while not wearing a disguise",
            speakers = speakers,
        )
    }

    private suspend fun ProtectedAccess.meetingDoor(door: BoundLocInfo) {
        var goIn = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("I don't care. I'm going in anyway." to { goIn = true }),
            )
        runQuestSection(
            null,
            dialogue,
            "Interrupting the Black Knights' meeting",
            hooks,
            speakers,
        )
        if (!goIn) return
        passThroughWall(door)
        runQuestSection(null, dialogue, "Entering the room", speakers = speakers)
        val knight = Npc(npcTypes[BkfNpcs.black_knight], player.coords.translate(1, 0))
        npcRepo.add(knight, KNIGHT_TICKS)
        knight.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.pushWall(wall: BoundLocInfo) {
        mes("You push against the wall... and it swings open.")
        passThroughWall(wall)
    }

    private suspend fun ProtectedAccess.listen() {
        when {
            state == 1 -> {
                runQuestSection(null, dialogue, "Listen-at Grill", speakers = speakers)
                player.setQuestState(BLACK_KNIGHTS_FORTRESS, 2)
            }
            state == 2 -> runQuestSection(null, dialogue, "Listen-at Grill", speakers = speakers)
            state >= 3 ->
                runQuestSection(
                    null,
                    dialogue,
                    "Listen-at Grill after ruining the potion",
                    speakers = speakers,
                )
            else -> mes("You can hear voices, but can't make out what they're saying.")
        }
    }

    private suspend fun ProtectedAccess.cabbage(magic: Boolean) {
        val section =
            when {
                state != 2 -> TOO_EARLY
                magic -> "Using a Draynor cabbage on the hole"
                else -> "Using a normal cabbage on the hole"
            }
        if (state == 2 && !magic) {
            invDel(inv, BkfObjs.cabbage)
            player.setQuestState(BLACK_KNIGHTS_FORTRESS, 3)
        }
        runQuestSection(null, dialogue, section, rewrites, speakers)
    }

    /** The Wiki transcript glues an editor's note onto the cat's line. */
    private val rewrites =
        SimpleQuestHooks(
            rewrites = { text ->
                if (text.startsWith("Meow!")) "Meow!" else text
            },
        )

    companion object {
        private const val REQUIRED_QP = 12
        private const val REWARD_COINS = 2500
        private const val KNIGHT_TICKS = 200
        private const val TOO_EARLY =
            "Using any item on the hole, or a cabbage before overhearing the witch"

        val BLACK_KNIGHTS_FORTRESS =
            QuestDef(
                name = "Black Knights' Fortress",
                varp = { BkfVarps.spy },
                complete = 4,
                questPoints = 3,
                rewards = listOf("2,500 coins"),
                scrollObj = { BkfObjs.cabbage },
            )
    }
}

// Every cache ref Black Knights' Fortress uses (re-check after the rev 243 cutover).

internal object BkfNpcs : NpcReferences() {
    val amik = find("sir_amik_varze")
    val guard = find("fortressguard")
    val guards =
        listOf(
            guard,
            find("fortressguard_01"),
            find("fortressguard_02"),
            find("fortressguard_03"),
            find("fortressguard_04"),
        )
    val captain = find("grillknight")
    val witch = find("fortwitch")
    val greldo = find("greldo")
    val cat = find("bkf_cat")
    val black_knight = find("black_knight")
}

internal object BkfLocs : LocReferences() {
    /** Guards' entrance (ground floor, 3016,3514). */
    val guard_door = find("bkfortressdoor1")
    val inner_door = find("bkfortressdoor2")

    /** The Black Knights' meeting room (1st floor). */
    val meeting_door = find("bkfortressdoor3")
    val fake_wall = find("bksecretdoor")
    val grill = find("witchgrill")
    val hole = find("blackknighthole")
}

internal object BkfObjs : ObjReferences() {
    val helm = find("bronze_med_helm")
    val chainbody = find("iron_chainbody")
    val cabbage = find("cabbage")
    val magic_cabbage = find("magic_cabbage")
    val coins = find("coins")
}

internal object BkfVarps : VarpReferences() {
    val spy = find("spy")
}
