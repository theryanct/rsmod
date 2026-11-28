package org.rsmod.content.quests.xmarksthespot

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * X Marks the Spot (OSRS Wiki). `cluequest` varbit (cache db end state 8): 0, 1 Veos's treasure
 * scroll (dig by Bob's Brilliant Axes), 2 the map (behind Lumbridge Castle's kitchen), 3 the
 * mysterious orb (hot / cold; east of Draynor), 4 the cipher scroll (Draynor pig pen), 5 dug up
 * the ancient casket, 8 complete (casket to Veos at Port Sarim). Reward: 200 coins, an antique
 * lamp, 1 QP.
 *
 * Veos is a multinpc in Lumbridge (`veos_lumbridge_vis`) and at Port Sarim (`veos_sarim_vis`); both
 * are set on login. Lost clue items are handed back by Veos.
 *
 * Not done: the map clue's interface (read shows a text description), the lamp's skill picker
 * (no lamp script yet), scroll boxes / clue caps, the Kourend travel ops.
 */
class XMarksTheSpot
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(XMarksTheSpot::class.java, "x_marks_the_spot")
    }

    /** Last orb distance per player, for "warmer" / "colder". */
    private val lastOrbDistance = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        for (veos in XMarksNpcs.veos) {
            onNpcOpLabel(npcTypes, veos, "Talk-to") { talkVeos(it) }
        }
        onPlayerLogin { player.showVeos() }
        for (clue in XMarksObjs.scrolls) {
            onHeldOpLabel(objTypes, clue, "Read") { readClue(clue) }
        }
        onHeldOpLabel(objTypes, XMarksObjs.orb, "Feel") { feelOrb() }
        onHeldOpLabel(objTypes, XMarksObjs.casket, "Open") {
            runQuestSection(null, dialogue, "Attempting to open the ancient casket")
        }
        QuestDigSpots.register { dig() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(X_MARKS_THE_SPOT)

    private fun Player.showVeos() {
        val done = isQuestComplete(X_MARKS_THE_SPOT)
        VarPlayerIntMapSetter.set(this, XMarksVarBits.lumbridge_vis, if (done) 0 else VEOS_SHOWN)
        VarPlayerIntMapSetter.set(this, XMarksVarBits.sarim_vis, SARIM_SHOWN)
    }

    /** The clue item the player should hold at [state], if any. */
    private fun clueFor(state: Int): ObjType? =
        when (state) {
            1 -> XMarksObjs.clue1
            2 -> XMarksObjs.clue2
            3 -> XMarksObjs.orb
            4 -> XMarksObjs.clue4
            5 -> XMarksObjs.casket
            else -> null
        }

    private fun ProtectedAccess.inLumbridge(): Boolean = player.coords.x >= LUMBRIDGE_MIN_X

    private suspend fun ProtectedAccess.talkVeos(npc: Npc) {
        val clue = clueFor(state)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have any free inventory space:" to {
                            inv.isFull()
                        },
                        "If the player has a free inventory space:" to { !inv.isFull() },
                        "If the player talks to Veos in Lumbridge:" to { inLumbridge() },
                        "If the player talks to Veos in Port Sarim:" to { !inLumbridge() },
                    ),
                boxes =
                    mapOf(
                        "Veos has given you a scroll." to {
                            if (!inv.isFull()) {
                                invAdd(inv, XMarksObjs.clue1)
                                player.setQuestState(X_MARKS_THE_SPOT, 1)
                            }
                            false
                        },
                        "You give Veos the Ancient Casket." to {
                            invDel(inv, XMarksObjs.casket)
                            false
                        },
                    ),
                rewrites = { text ->
                    text.replace("[scroll/orb]", if (clue == XMarksObjs.orb) "orb" else "scroll")
                },
            )
        when {
            state == 0 -> runQuestSection(npc, dialogue, START_SECTION, hooks)
            state == 5 && carries(XMarksObjs.casket) -> {
                runQuestSection(npc, dialogue, "Finishing up", hooks)
                invAddOrDrop(objRepo, XMarksObjs.coins, REWARD_COINS)
                invAddOrDrop(objRepo, XMarksObjs.lamp)
                completeQuest(X_MARKS_THE_SPOT)
                player.showVeos()
            }
            clue != null && !carries(clue) -> {
                runQuestSection(npc, dialogue, "Talking to Veos after losing an item", hooks)
                invAddOrDrop(objRepo, clue)
            }
            state in 1..4 -> runQuestSection(npc, dialogue, HELP_SECTIONS[state - 1], hooks)
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "There's always more treasure to be found, friend.")
                }
        }
    }

    private suspend fun ProtectedAccess.readClue(clue: ObjType) {
        val text =
            when (clue) {
                XMarksObjs.clue1 ->
                    "Within the town of Lumbridge lives a man named Bob. He walks out of his " +
                        "door and takes 1 step east, 7 steps north, 5 steps west and 1 step " +
                        "south. Once he arrives, he digs a hole and buries his treasure."
                XMarksObjs.clue2 ->
                    "The scroll shows a map: an X marked outside the back door of Lumbridge " +
                        "Castle's kitchen, beside a large crate."
                else -> "ESBZOPS QJH QFO"
            }
        startDialogue { mesbox(text) }
    }

    /** Hot / cold by Chebyshev distance to the third dig spot. */
    private fun ProtectedAccess.feelOrb() {
        val here = player.coords
        if (here.level != DIG_ORB.level) {
            mes("The orb is ice cold.")
            return
        }
        val distance = here.chebyshevDistance(DIG_ORB)
        val temperature =
            when {
                distance == 0 -> "The orb is visibly shaking."
                distance < 5 -> "The orb is incredibly hot."
                distance < 10 -> "The orb is very hot."
                distance < 30 -> "The orb is hot."
                distance < 70 -> "The orb is warm."
                distance < 150 -> "The orb is cold."
                distance < 300 -> "The orb is very cold."
                else -> "The orb is ice cold."
            }
        mes(temperature)
        val last = lastOrbDistance.put(player.uid.packed, distance)
        when {
            last == null || distance == 0 -> {}
            distance < last -> mes("It's getting warmer.")
            distance > last -> mes("It's getting colder.")
            else -> mes("It's the same temperature as before.")
        }
    }

    /** The four dig spots (via [QuestDigSpots]); `false` anywhere else. */
    private suspend fun ProtectedAccess.dig(): Boolean {
        val (spot, clue, next) =
            when (state) {
                1 -> Triple(DIG_BOB, XMarksObjs.clue1, XMarksObjs.clue2)
                2 -> Triple(DIG_CASTLE, XMarksObjs.clue2, XMarksObjs.orb)
                3 -> Triple(DIG_ORB, XMarksObjs.orb, XMarksObjs.clue4)
                4 -> Triple(DIG_PIGPEN, XMarksObjs.clue4, XMarksObjs.casket)
                else -> return false
            }
        val here = player.coords
        if (here.level != spot.level || here.chebyshevDistance(spot) > DIG_RADIUS) return false
        if (!carries(clue)) return false
        invDel(inv, clue)
        invAdd(inv, next)
        player.setQuestState(X_MARKS_THE_SPOT, state + 1)
        if (next == XMarksObjs.casket) {
            lastOrbDistance.remove(player.uid.packed)
            runQuestSection(null, dialogue, "Digging the last clue")
        } else {
            mes("You dig a hole and find another clue!")
        }
        return true
    }

    companion object {
        private const val VEOS_SHOWN = 2
        private const val SARIM_SHOWN = 3
        private const val LUMBRIDGE_MIN_X = 3150
        private const val DIG_RADIUS = 1
        private const val REWARD_COINS = 200

        /** OSRS Wiki "Map:X Marks the Spot" pins. */
        private val DIG_BOB = CoordGrid(3230, 3210, 0)
        private val DIG_CASTLE = CoordGrid(3203, 3212, 0)
        private val DIG_ORB = CoordGrid(3108, 3264, 0)
        private val DIG_PIGPEN = CoordGrid(3077, 3260, 0)

        /* The Wiki's "visited Kourend" variant is the one whose scroll hand-over is a box. */
        private const val START_SECTION = "If the player has visited Great Kourend before"

        private val HELP_SECTIONS =
            listOf(
                "Talking to Veos after receiving the first clue",
                "Talking to Veos after receiving the second clue",
                "Talking to Veos after receiving the third clue",
                "Talking to Veos after receiving the fourth clue",
            )

        val X_MARKS_THE_SPOT =
            QuestDef(
                name = "X Marks the Spot",
                varbit = { XMarksVarBits.cluequest },
                complete = 8,
                questPoints = 1,
                rewards = listOf("200 coins", "Antique lamp (300 XP)"),
                scrollObj = { XMarksObjs.casket },
            )
    }
}

// Every cache ref X Marks the Spot uses (re-check after the rev 243 cutover).

internal object XMarksNpcs : NpcReferences() {
    /* Multinpc children of veos_lumbridge / veos_sarim. */
    val veos = listOf(find("veos_visible"), find("veos_visible_travel"))
}

internal object XMarksObjs : ObjReferences() {
    val clue1 = find("cluequest_clue1")
    val clue2 = find("cluequest_clue2")
    val orb = find("cluequest_clue3")
    val clue4 = find("cluequest_clue4")
    val casket = find("cluequest_casket")
    val lamp = find("cluequest_lamp")
    val coins = find("coins")

    val scrolls = listOf(clue1, clue2, clue4)
}

internal object XMarksVarBits : VarBitReferences() {
    val cluequest = find("cluequest")
    val lumbridge_vis = find("veos_lumbridge_vis")
    val sarim_vis = find("veos_sarim_vis")
}
