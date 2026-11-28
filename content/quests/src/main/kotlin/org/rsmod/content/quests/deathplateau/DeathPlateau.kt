package org.rsmod.content.quests.deathplateau

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
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
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Death Plateau (OSRS Wiki). Cache quest varp `death_equiproom`, end state 80:
 * - 10 Denulth (Burthorpe) needs the equipment room's combination; 20 Eohric points to Harold,
 *   last night's guard, in the Toad and Chicken; 30 an Asgarnian ale gets him talking; gambling
 *   (dice) until he's out of money wins his IOU, which holds the combination; 40 the five stone balls
 *   on the castle's mechanism (blue bottom-left, yellow bottom-right, red middle-left, purple
 *   middle-right, green top-right) open the equipment room.
 * - 50 Saba (hermit cave) and Tenzing the Sherpa: his climbing boots; 60 Dunstan spikes them (an
 *   iron bar) if his son can join the Imperial Guard (Denulth's certificate); 70 Tenzing, given the
 *   spiked boots, 10 bread and 10 trout, hands over the map of the secret way; 80 Denulth takes the
 *   map and the combination: 1 QP, 3,000 Attack xp, steel claws.
 * - Server varp `dp_progress` (65793): bits 0-4 the balls placed, 5 IOU read, 6 Harold drunk,
 *   8-15 Harold's money lost (coins).
 *
 * Simplified: the dice are a coin flip (Harold drunk: you always win), and walking up the trail is
 * not required before Denulth.
 */
class DeathPlateau
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DeathPlateau::class.java, "death_plateau") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, DpNpcs.denulth, DEATH_PLATEAU, wants = { state < COMPLETE }) { talkDenulth(it) }
            register(npcTypes, DpNpcs.eohric, DEATH_PLATEAU, wants = { state in STARTED until COMPLETE }) {
                talkEohric(it)
            }
            register(npcTypes, DpNpcs.harold, DEATH_PLATEAU, wants = { state in EOHRIC until COMPLETE }) {
                talkHarold(it)
            }
            register(npcTypes, DpNpcs.saba, DEATH_PLATEAU, wants = { state >= EQUIPMENT }) {
                runQuestSection(it, dialogue, "Talking to Saba")
                if (state == EQUIPMENT) setState(SABA)
            }
            register(npcTypes, DpNpcs.tenzing, DEATH_PLATEAU, wants = { state >= SABA && state < COMPLETE }) {
                talkTenzing(it)
            }
            register(npcTypes, DpNpcs.dunstan, DEATH_PLATEAU, wants = { state >= SABA && state < COMPLETE }) {
                talkDunstan(it)
            }
        }
        onOpNpcU(DpNpcs.harold, DpObjs.ale) { talkHarold(it.npc) }
        onLocOpLabel(locTypes, DpLocs.harold_door, "Open") { haroldDoor(it) }
        onHeldOpLabel(objTypes, DpObjs.iou, "Read") { readIou() }
        for ((index, ball) in DpObjs.balls.withIndex()) {
            for (stone in DpLocs.mechanism) onOpLocU(stone, ball) { placeBall(index, it.loc.coords) }
        }
        onLocOpLabel(locTypes, DpLocs.equipment_door, "Open") { equipmentDoor(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(DEATH_PLATEAU)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(DEATH_PLATEAU, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[DpVarps.progress]

    private fun ProtectedAccess.hasFlag(bit: Int): Boolean = (flags() and (1 shl bit)) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, DpVarps.progress, flags() or (1 shl bit))
    }

    private fun ProtectedAccess.haroldLost(): Int = (flags() shr LOST_SHIFT) and LOST_MASK

    private fun ProtectedAccess.setHaroldLost(value: Int) {
        val cleared = flags() and (LOST_MASK shl LOST_SHIFT).inv()
        VarPlayerIntMapSetter.set(player, DpVarps.progress, cleared or (value.coerceIn(0, LOST_MASK) shl LOST_SHIFT))
    }

    /* Denulth and Eohric */

    private suspend fun ProtectedAccess.talkDenulth(npc: Npc) {
        when {
            state == 0 -> {
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                runQuestSection(npc, dialogue, "Talking to Denulth", hooks)
            }
            state < EQUIPMENT -> runQuestSection(npc, dialogue, "Talking to Denulth again")
            state == SABA && carries(DpObjs.climbing_boots) && !carries(DpObjs.certificate) -> {
                val hooks =
                    SimpleQuestHooks(boxes = mapOf("Denulth has given you a certificate." to { give(DpObjs.certificate); false }))
                runQuestSection(npc, dialogue, "Talking to Denulth after talking to Dunstan", hooks)
            }
            state == TENZING_MAP -> {
                val hasMap = carries(DpObjs.map)
                val hasCombination = carries(DpObjs.iou) || carries(DpObjs.combination)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have the map:" to { !hasMap },
                                "If the player has the map:" to { hasMap },
                                "If the player doesn't have the combination:" to { !hasCombination },
                                "If the player has the combination:" to { hasCombination },
                                "If the player has not turned in all the items:" to { !(hasMap && hasCombination) },
                                "If the player has turned in all the items:" to { hasMap && hasCombination },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Denulth after opening the equipment room and exploring the path", hooks)
                if (hasMap && hasCombination) {
                    invDel(inv, DpObjs.map)
                    if (carries(DpObjs.iou)) invDel(inv, DpObjs.iou) else invDel(inv, DpObjs.combination)
                    statAdvance(stats.attack, 3000.0 * xpMods.get(player, stats.attack))
                    invAddOrDrop(objRepo, DpObjs.steel_claws)
                    completeQuest(DEATH_PLATEAU)
                }
            }
            else -> runQuestSection(npc, dialogue, "Talking to Denulth after opening the equipment room")
        }
    }

    private suspend fun ProtectedAccess.talkEohric(npc: Npc) {
        when (state) {
            STARTED -> {
                runQuestSection(npc, dialogue, "Talking to Eohric")
                setState(EOHRIC)
            }
            EOHRIC -> runQuestSection(npc, dialogue, "Talking to Eohric again")
            HAROLD -> runQuestSection(npc, dialogue, "Talking to Eohric after talking to Harold")
            else -> runQuestSection(npc, dialogue, "Talking to Eohric after opening the equipment room")
        }
    }

    /* Harold */

    private suspend fun ProtectedAccess.haroldDoor(door: BoundLocInfo) {
        if (state < EOHRIC) {
            runQuestSection(null, dialogue, "Trying to enter Harold's room before talking to Eohric")
            return
        }
        runQuestSection(null, dialogue, "Entering Harold's room")
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkHarold(npc: Npc) {
        if (state == EOHRIC) {
            runQuestSection(npc, dialogue, "Talking to Harold")
            setState(HAROLD)
            return
        }
        if (state != HAROLD) {
            runQuestSection(npc, dialogue, "Talking to Harold again")
            return
        }
        var gamble = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have an Asgarnian ale:" to { !carries(DpObjs.ale) },
                        "If the player has an Asgarnian ale:" to { carries(DpObjs.ale) },
                        "If the player does not have a Blurberry special:" to {
                            DpObjs.blurberries.none { carries(it) }
                        },
                    ),
                options = mapOf("Would you like to gamble?" to { gamble = true }),
                boxes =
                    mapOf(
                        "You give Harold an Asgarnian Ale." to { invDel(inv, DpObjs.ale); false },
                        "You give Harold a Blurberry Special." to {
                            val drink = DpObjs.blurberries.first { carries(it) }
                            invDel(inv, drink)
                            setFlag(DRUNK)
                            false
                        },
                    ),
            )
        if (carries(DpObjs.iou)) {
            runQuestSection(
                npc,
                dialogue,
                if (hasFlag(DRUNK)) "Talking to Harold while still holding the IOU if Harold is drunk"
                else "Talking to Harold while still holding the IOU if Harold is not drunk",
            )
            return
        }
        val section =
            if (hasFlag(DRUNK)) "Talking to Harold after giving him a Blurberry special"
            else "Talking to Harold after learning his weakness"
        runQuestSection(npc, dialogue, section, hooks)
        if (gamble) gambleWith(npc)
    }

    private suspend fun ProtectedAccess.gambleWith(npc: Npc) {
        val bet = countDialog("How much do you want to bet?")
        val coins = invTotal(inv, objs.coins)
        if (bet <= 0 || bet > coins || bet > MAX_BET) {
            mes("That's not a bet Harold will take.")
            return
        }
        val drunk = hasFlag(DRUNK)
        val win = drunk || random.of(1, 2) == 1
        val lostBefore = haroldLost()
        val outOfMoney = win && lostBefore + bet >= HAROLD_PURSE
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If Harold is not drunk and Harold wins:" to { !drunk && !win },
                        "If Harold is not drunk and the player wins:" to { !drunk && win },
                        "If Harold is drunk:" to { drunk },
                        "If Harold still has money:" to { !outOfMoney },
                        "If Harold is out of money:" to { outOfMoney },
                    ),
                boxes =
                    mapOf(
                        "You give Harold his winnings." to { invDel(inv, objs.coins, bet); false },
                        "Harold has given you your winnings!" to { invAdd(inv, objs.coins, bet); false },
                        "Harold has given you some of your winnings!" to {
                            invAdd(inv, objs.coins, (HAROLD_PURSE - lostBefore).coerceAtLeast(1))
                            false
                        },
                        "Harold has given you an IOU scribbled on some paper." to { give(DpObjs.iou); false },
                    ),
            )
        runQuestSection(npc, dialogue, "Finishing a gamble", hooks)
        if (win) setHaroldLost(lostBefore + bet)
    }

    private fun ProtectedAccess.give(obj: org.rsmod.game.type.obj.ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    private suspend fun ProtectedAccess.readIou() {
        runQuestSection(null, dialogue, "Reading the IOU")
        setFlag(IOU_READ)
    }

    /* The stone balls and the equipment room */

    private fun ProtectedAccess.placeBall(index: Int, stone: CoordGrid) {
        if (state != HAROLD || hasFlag(index)) return
        val spot = BALL_SPOTS[index]
        if (stone != spot) {
            mes("That doesn't seem to be the right place for this ball.")
            return
        }
        invDel(inv, DpObjs.balls[index])
        setFlag(index)
        mes("You place the stone ball on the mechanism.")
    }

    private suspend fun ProtectedAccess.equipmentDoor(door: BoundLocInfo) {
        val solved = DpObjs.balls.indices.all { hasFlag(it) } && hasFlag(IOU_READ)
        if (state >= EQUIPMENT || (state == HAROLD && solved)) {
            if (state == HAROLD) {
                runQuestSection(null, dialogue, "Completing the ball combination")
                setState(EQUIPMENT)
            }
            passThroughWall(door)
            return
        }
        mes("The door is locked.")
    }

    /* Tenzing and Dunstan */

    private suspend fun ProtectedAccess.talkTenzing(npc: Npc) {
        val boots = carries(DpObjs.spiked_boots)
        val bread = invTotal(inv, DpObjs.bread) >= SUPPLIES
        val trout = invTotal(inv, DpObjs.trout) >= SUPPLIES
        when {
            state == SABA && !carries(DpObjs.climbing_boots) && !boots && !hasFlag(BOOTS_GIVEN) -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "Tenzing has given you his climbing boots." to {
                                    give(DpObjs.climbing_boots)
                                    setFlag(BOOTS_GIVEN)
                                    false
                                }
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Tenzing", hooks)
            }
            state == SPIKED || (state == SABA && boots) -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player is missing spiked boots, bread, and trout:" to { !boots && !bread && !trout },
                                "If the player is missing spiked boots and bread:" to { !boots && !bread && trout },
                                "If the player is missing spiked boots and trout:" to { !boots && bread && !trout },
                                "If the player is missing bread and trout:" to { boots && !bread && !trout },
                                "If the player is missing spiked boots:" to { !boots && bread && trout },
                                "If the player is missing bread:" to { boots && !bread && trout },
                                "If the player is missing trout:" to { boots && bread && !trout },
                            ),
                        boxes =
                            mapOf(
                                "You give Tenzing the Spiked boots." to { invDel(inv, DpObjs.spiked_boots); false },
                                "You give Tenzing the loaves of bread and the cooked trout." to {
                                    invDel(inv, DpObjs.bread, SUPPLIES)
                                    invDel(inv, DpObjs.trout, SUPPLIES)
                                    false
                                },
                                "Tenzing has given you a map of the secret way!" to {
                                    give(DpObjs.map)
                                    setState(TENZING_MAP)
                                    false
                                },
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to Tenzing while holding climbing or spiked boots", hooks)
            }
            state == TENZING_MAP && !carries(DpObjs.map) -> {
                runQuestSection(npc, dialogue, "Talking to Tenzing after losing the map")
                give(DpObjs.map)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Tenzing before fixing the boots")
        }
    }

    private suspend fun ProtectedAccess.talkDunstan(npc: Npc) {
        when {
            carries(DpObjs.certificate) -> {
                val ready = carries(DpObjs.climbing_boots) && carries(DpObjs.iron_bar)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the boots and an iron bar:" to { ready },
                                "If the player has neither climbing boots nor iron bar:" to {
                                    !carries(DpObjs.climbing_boots) && !carries(DpObjs.iron_bar)
                                },
                                "If the player does not have the climbing boots:" to { !carries(DpObjs.climbing_boots) },
                                "If the player does not have an iron bar:" to { !carries(DpObjs.iron_bar) },
                            ),
                        boxes =
                            mapOf(
                                "You give Dunstan the certificate." to { invDel(inv, DpObjs.certificate); false },
                                "Dunstan has given you the spiked boots." to {
                                    invDel(inv, DpObjs.climbing_boots)
                                    invDel(inv, DpObjs.iron_bar)
                                    give(DpObjs.spiked_boots)
                                    setState(SPIKED)
                                    false
                                },
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to Dunstan while holding the certificate", hooks)
            }
            state == SPIKED -> runQuestSection(npc, dialogue, "Talking to Dunstan after getting the spiked boots")
            else -> runQuestSection(npc, dialogue, "Talking to Dunstan")
        }
    }

    companion object {
        const val STARTED = 10
        const val EOHRIC = 20
        const val HAROLD = 30
        const val EQUIPMENT = 40
        const val SABA = 50
        const val SPIKED = 60
        const val TENZING_MAP = 70
        const val COMPLETE = 80

        private const val IOU_READ = 5
        private const val DRUNK = 6
        private const val BOOTS_GIVEN = 7
        private const val LOST_SHIFT = 8
        private const val LOST_MASK = 0xFF
        private const val HAROLD_PURSE = 200
        private const val MAX_BET = 1000
        private const val SUPPLIES = 10

        /** Ball spots on the mechanism (2894-2895,3562-3564): red, blue, yellow, purple, green. */
        private val BALL_SPOTS =
            listOf(
                CoordGrid(2894, 3563, 0),
                CoordGrid(2894, 3562, 0),
                CoordGrid(2895, 3562, 0),
                CoordGrid(2895, 3563, 0),
                CoordGrid(2895, 3564, 0),
            )

        val DEATH_PLATEAU =
            QuestDef(
                name = "Death Plateau",
                varp = { DpVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("3,000 Attack XP", "Steel claws", "The ability to make claws"),
                scrollObj = { DpObjs.steel_claws },
                listRow = "quest_deathplateau",
            )
    }
}

// Every cache ref Death Plateau uses (re-check after cache updates).

internal object DpNpcs : NpcReferences() {
    val denulth = find("death_ig_commander")
    val eohric = find("death_headservant")
    val harold = find("death_guard_equiproom")
    val saba = find("death_hermit")
    val tenzing = find("death_sherpa")
    val dunstan = find("death_smithy")
}

internal object DpLocs : LocReferences() {
    val harold_door = find("death_harold_door")
    val equipment_door = find("death_castledoor")
    val mechanism = listOf(find("death_stone_mechanism_corner"), find("death_stone_mechanism_side"))
}

internal object DpObjs : ObjReferences() {
    val ale = find("asgarnian_ale")
    val blurberries = listOf(find("blurberry_special"), find("premade_blurberry_special"))
    val iou = find("death_iou")
    val combination = find("death_combination")
    val map = find("death_secretwaymap")
    val climbing_boots = find("death_climbingboots")
    val spiked_boots = find("death_spikedboots")
    val certificate = find("death_entrancecert")
    val iron_bar = find("iron_bar")
    val bread = find("bread")
    val trout = find("trout")
    val steel_claws = find("steel_claws")

    /** red, blue, yellow, purple, green. */
    val balls =
        listOf(
            find("death_cannonball_red"),
            find("death_cannonball_blue"),
            find("death_cannonball_yellow"),
            find("death_cannonball_purple"),
            find("death_cannonball_green"),
        )
}

internal object DpVarps : VarpReferences() {
    val quest = find("death_equiproom")

    /** Server varp (65793): see [DeathPlateau]. */
    val progress = find("dp_progress")
}

internal object DpVarpBuilds : VarpBuilder() {
    init {
        build("dp_progress")
    }
}
