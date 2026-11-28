package org.rsmod.content.quests.ghostsahoy

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Ghosts Ahoy (OSRS Wiki). Cache varbit `ahoy_questvar` (end 8; the barrier turns toll-free at 8,
 * Ak-Haranu appears from 4), with the cache's own sub-quest varbits in `ahoy_varbits_1`:
 * - 1 Velorina asks you to plead with Necrovarus, 2 he refuses, 3 Velorina tells of his old
 *   disciple, 4 the old crone remembers (over milky nettle tea in her porcelain cup) and wants the
 *   Book of Haricanto, something to translate it, and the Robes of Necrovarus; 5 all three given, 6
 *   she enchants your ghostspeak amulet, 7 Necrovarus commanded to let the ghosts go, 8 Velorina's
 *   Ectophial (complete: 2 QP).
 * - The book: the crone's toy boat, repaired and dyed like the shipwreck's flag, gets the cabin
 *   boy's chest key; three map scraps (his chest, the rocky island, the lobster chest) make the map
 *   that finds the book on Dragontooth Island (see [GhostsAhoyBook]).
 * - The manual: Ak-Haranu trades it for an oak longbow signed by Robin, who signs once he owes you
 *   100 coins at Runedraw (see [GhostsAhoyManual]).
 * - The robes: Gravingas's petition, signed by 10 ghost villagers while you wear a slimy bedsheet,
 *   so enrages Necrovarus that he drops the key to his tomb (see [GhostsAhoyRobes]).
 */
class GhostsAhoy
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val speakers: Map<String, NpcType>
        get() = GaNpcs.speakers

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, GaNpcs.velorina, "Talk-to") { talkVelorina(it) }
        onNpcOpLabel(npcTypes, GaNpcs.necrovarus, "Talk-to") { talkNecrovarus(it) }
        with(QuestTalk) {
            register(npcTypes, GaNpcs.crone, GHOSTS_AHOY, wants = { questStarted() }) {
                talkCrone(it)
            }
            default(npcTypes, GaNpcs.crone) {
                startDialogue(it) {
                    chatNpc(neutral, "Hello, dearie. My memory isn't what it was...")
                }
            }
        }

        onOpHeldU(GaObjs.bowl_tea, GaObjs.cup) { pour(GaObjs.bowl_tea, GaObjs.cup_tea) }
        onOpHeldU(GaObjs.bowl_tea_milky, GaObjs.cup) {
            pour(GaObjs.bowl_tea_milky, GaObjs.cup_tea_milky)
        }
        onOpHeldU(GaObjs.milk, GaObjs.cup_tea) { addMilk(GaObjs.cup_tea, GaObjs.cup_tea_milky) }
        onOpHeldU(GaObjs.milk, GaObjs.bowl_tea) { addMilk(GaObjs.bowl_tea, GaObjs.bowl_tea_milky) }
    }

    private fun ProtectedAccess.questStarted(): Boolean = player.questState(GHOSTS_AHOY) > 0

    /* Velorina */

    private suspend fun ProtectedAccess.talkVelorina(npc: Npc) {
        val state = gaState()
        when {
            state == 0 -> {
                var start = false
                val hooks = ghostHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "Talking to Velorina", hooks, speakers)
                if (start && wearingGhostspeak()) setGaState(STARTED)
            }
            state == STARTED -> gaRun(npc, "Talking to Velorina again")
            state == REFUSED -> {
                gaRun(npc, "Returning to Velorina after talking to Necrovarus")
                if (wearingGhostspeak()) setGaState(DISCIPLE)
            }
            state == DISCIPLE -> gaRun(npc, "Talking to Velorina again (2)")
            state < ENCHANTED -> gaRun(npc, "Talking to Velorina after talking to the Old crone")
            state == ENCHANTED -> {
                val enchanted = invTotal(worn, GaObjs.ghostspeak_enchanted) > 0
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                ENCHANTED_NOT_HELD to { !enchanted },
                                ENCHANTED_WORN to { enchanted },
                            )
                    )
                if (!enchanted) {
                    gaRun(npc, "Talking to Velorina after talking to the Old crone")
                    return
                }
                runQuestSection(npc, dialogue, "Talking to Velorina (2)", hooks, speakers)
            }
            state == COMMANDED -> {
                if (inv.isFull()) {
                    mes("You need a free inventory space for Velorina's gift.")
                    return
                }
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Velorina (3)",
                    SimpleQuestHooks(),
                    speakers,
                )
                invAdd(inv, GaObjs.ectophial)
                completeQuest(GHOSTS_AHOY)
            }
            else -> {
                var another = false
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(
                                "I know, but can I have another one anyway?" to { another = true }
                            )
                    )
                runQuestSection(npc, dialogue, "Velorina", hooks, speakers)
                if (another) invAddOrDrop(objRepo, GaObjs.ectophial)
            }
        }
    }

    /* Necrovarus (the petition and bone key are in GhostsAhoyRobes) */

    private suspend fun ProtectedAccess.talkNecrovarus(npc: Npc) {
        val state = gaState()
        when {
            state == STARTED -> {
                gaRun(npc, "Talking to Necrovarus")
                if (wearingGhostspeak()) setGaState(REFUSED)
            }
            state in REFUSED until ENCHANTED -> {
                if (GhostsAhoyRobes.petitionTalk(this, npc)) return
                gaRun(npc, "Talking to Necrovarus again")
            }
            state == ENCHANTED -> command(npc)
            state > ENCHANTED ->
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Necrovarus after freeing Port Phasmatys",
                    SimpleQuestHooks(),
                    speakers,
                )
            else -> gaRun(npc, "Talking to Necrovarus again")
        }
    }

    private suspend fun ProtectedAccess.command(npc: Npc) {
        val enchanted = invTotal(worn, GaObjs.ghostspeak_enchanted) > 0
        var freed = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has a regular ghostspeak amulet:" to { !enchanted },
                        "If the player has the enchanted ghostspeak amulet:" to { enchanted },
                    ),
                options =
                    mapOf(
                        "Let any ghost who so wishes pass on into the next world." to
                            {
                                freed = true
                            }
                    ),
            )
        if (!wearingGhostspeak()) {
            gaRun(npc, "Talking to Necrovarus again")
            return
        }
        runQuestSection(npc, dialogue, "Commanding Necrovarus to stand down", hooks, speakers)
        if (!freed || !enchanted) return
        invReplace(worn, GaObjs.ghostspeak_enchanted, 1, GaObjs.ghostspeak)
        setGaState(COMMANDED)
    }

    /* The old crone */

    private suspend fun ProtectedAccess.talkCrone(npc: Npc) {
        val state = gaState()
        when {
            state < DISCIPLE ->
                startDialogue(npc) {
                    chatNpc(neutral, "Hello, dearie. My memory isn't what it was...")
                }
            state == DISCIPLE -> croneTea(npc)
            state < ENCHANTED && GhostsAhoyRobes.hasMaterials(this) ->
                GhostsAhoyRobes.handInMaterials(this, npc)
            state < ENCHANTED && bit(GaVarBits.toyboat) == BOAT_RETURNED -> {
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to the Old crone after finding the old man",
                    SimpleQuestHooks(),
                    speakers,
                )
                setBit(GaVarBits.toyboat, BOAT_TOLD)
            }
            state < ENCHANTED -> croneMenu(npc)
            state < COMPLETE -> {
                val has =
                    carries(GaObjs.ghostspeak_enchanted) ||
                        invTotal(worn, GaObjs.ghostspeak_enchanted) > 0
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the enchanted ghostspeak amulet:" to { has },
                                "If the player lost the enchanted ghostspeak amulet:" to { !has },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to the Old crone again (2)",
                    hooks,
                    speakers,
                )
                if (!has && state == ENCHANTED && carries(GaObjs.ghostspeak)) {
                    invDel(inv, GaObjs.ghostspeak)
                    invAdd(inv, GaObjs.ghostspeak_enchanted)
                }
            }
            else -> runQuestSection(npc, dialogue, "Old crone", SimpleQuestHooks(), speakers)
        }
    }

    private suspend fun ProtectedAccess.croneTea(npc: Npc) {
        val cupMilky = carries(GaObjs.cup_tea_milky)
        val cupPlain = !cupMilky && carries(GaObjs.cup_tea)
        val bowl =
            !cupMilky && !cupPlain && (carries(GaObjs.bowl_tea) || carries(GaObjs.bowl_tea_milky))
        val wearing = wearingGhostspeak()
        val carrying = carries(GaObjs.ghostspeak)
        var boat = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player already has a bowl of nettle tea:" to { bowl },
                        "If the player does not already have a bowl of nettle tea:" to
                            {
                                !bowl && !cupPlain && !cupMilky
                            },
                        "If the player has regular nettle tea:" to { cupPlain },
                        "If the player has nettle tea with milk:" to { cupMilky },
                        "If the player does not have a ghostspeak amulet in their bag:" to
                            {
                                !carrying && !wearing
                            },
                        "If the player has a ghostspeak amulet in their bag:" to { carrying },
                        "If the player is wearing the ghostspeak amulet:" to { wearing },
                    ),
                options =
                    mapOf(
                        "You are doing so much for me - is there anything I can do for you?" to
                            {
                                boat = true
                            }
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to the Old crone", hooks, speakers)
        when {
            bowl && !carries(GaObjs.cup) && !carries(GaObjs.cup_tea) ->
                invAddOrDrop(objRepo, GaObjs.cup)
            cupMilky -> {
                invDel(inv, GaObjs.cup_tea_milky)
                invAddOrDrop(objRepo, GaObjs.cup)
                setGaState(REMEMBERED)
                if (boat) giveToyBoat()
            }
        }
    }

    private suspend fun ProtectedAccess.croneMenu(npc: Npc) {
        var boat = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "You are doing so much for me - is there anything I can do for you?" to
                            {
                                boat = true
                            }
                    )
            )
        runQuestSection(npc, dialogue, "Talking to the Old crone again", hooks, speakers)
        val lostBoat =
            bit(GaVarBits.toyboat) == BOAT_GIVEN &&
                !carries(GaObjs.toy_boat) &&
                !carries(GaObjs.toy_boat_repaired)
        if (boat || lostBoat || bit(GaVarBits.toyboat) == 0) giveToyBoat()
    }

    /** The crone's son's toy boat (her son is the shipwreck's old cabin boy). */
    private fun ProtectedAccess.giveToyBoat() {
        if (bit(GaVarBits.toyboat) >= BOAT_RETURNED) return
        val has = carries(GaObjs.toy_boat) || carries(GaObjs.toy_boat_repaired)
        if (!has) invAddOrDrop(objRepo, GaObjs.toy_boat)
        if (bit(GaVarBits.toyboat) == 0) setBit(GaVarBits.toyboat, BOAT_GIVEN)
    }

    /* Nettle tea (picking and brewing are Cooking's; the crone's cup and milk are here) */

    private fun ProtectedAccess.pour(bowl: ObjType, into: ObjType) {
        invDel(inv, bowl)
        invDel(inv, GaObjs.cup)
        invAdd(inv, GaObjs.bowl)
        invAdd(inv, into)
        mes("You pour the nettle tea into the porcelain cup.")
    }

    private fun ProtectedAccess.addMilk(tea: ObjType, into: ObjType) {
        invDel(inv, GaObjs.milk)
        invDel(inv, tea)
        invAdd(inv, GaObjs.bucket)
        invAdd(inv, into)
        mes("You add milk to the nettle tea.")
    }

    companion object {
        const val STARTED = 1
        const val REFUSED = 2
        const val DISCIPLE = 3
        const val REMEMBERED = 4
        const val MATERIALS_GIVEN = 5
        const val ENCHANTED = 6
        const val COMMANDED = 7
        const val COMPLETE = 8

        const val BOAT_GIVEN = 1
        const val BOAT_RETURNED = 2
        const val BOAT_TOLD = 3

        private const val ENCHANTED_NOT_HELD =
            "If the player does not have the enchanted ghostspeak amulet:"
        private const val ENCHANTED_WORN =
            "If the player is wearing the enchanted ghostspeak amulet:"

        val GHOSTS_AHOY =
            QuestDef(
                name = "Ghosts Ahoy",
                varbit = { GaVarBits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("Ectophial", "Free passage through the Port Phasmatys barrier"),
                scrollObj = { GaObjs.ectophial },
            )
    }
}

/* Helpers shared by the Ghosts Ahoy scripts. */

internal val dialogue: QuestDialogue by lazy {
    QuestDialogue.load(GhostsAhoy::class.java, "ghosts_ahoy")
}

internal fun ProtectedAccess.gaState(): Int = player.questState(GhostsAhoy.GHOSTS_AHOY)

internal fun ProtectedAccess.setGaState(value: Int) {
    player.setQuestState(GhostsAhoy.GHOSTS_AHOY, value)
}

internal fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

internal fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
    VarPlayerIntMapSetter.set(player, varbit, value)
}

internal fun ProtectedAccess.wearingGhostspeak(): Boolean =
    invTotal(worn, GaObjs.ghostspeak) > 0 || invTotal(worn, GaObjs.ghostspeak_enchanted) > 0

/** Every ghost section starts with "is (not) wearing the amulet of ghostspeak". */
internal fun ProtectedAccess.ghostHooks(
    conditions: Map<String, () -> Boolean> = emptyMap(),
    options: Map<String, suspend () -> Unit> = emptyMap(),
): SimpleQuestHooks {
    val wearing = wearingGhostspeak()
    return SimpleQuestHooks(
        conditions =
            mapOf(
                "If the player is not wearing the amulet of ghostspeak:" to { !wearing },
                "If the player is wearing the amulet of ghostspeak:" to { wearing },
                "If the player is not wearing an amulet of ghostspeak:" to { !wearing },
                "If the player is wearing an amulet of ghostspeak:" to { wearing },
                "If the player does not have the amulet of ghostspeak:" to { !wearing },
                "If the player has the amulet of ghostspeak:" to { wearing },
            ) + conditions,
        options = options,
    )
}

internal suspend fun ProtectedAccess.gaRun(npc: Npc?, section: String) {
    runQuestSection(npc, dialogue, section, ghostHooks(), GaNpcs.speakers)
}
