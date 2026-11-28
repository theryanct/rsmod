package org.rsmod.content.quests.princeali

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Prince Ali Rescue (OSRS Wiki). `princequest` varp (cache db end state 110; the cache multinpcs
 * hide Lady Keli above 40, the jailed Prince above 90 and show him at the palace from 100):
 * 0, 10 Hassan sent you to Osman, 20 Osman explained the plan, 30 Osman got the key imprint and a
 * bronze bar (Leela holds the key), 40 Joe the guard is drunk (3 beers), 50 Keli is tied up, 100
 * the Prince escaped in his disguise, 110 complete. Reward: 700 coins, 3 QP.
 *
 * Disguise: blonde wig (Ned makes a wig from 3 balls of wool; yellow dye on it), skin paste (Aggie,
 * see `aggie/Aggie.kt`), pink skirt (Thessalia). Key imprint: soft clay while Keli shows you the
 * key. Server varp `prince_progress`: met Leela, Leela handed over the key.
 *
 * Not done: free passage through the Al Kharid toll gate (no toll gate script yet).
 */
class PrinceAliRescue
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(PrinceAliRescue::class.java, "prince_ali_rescue")
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, PrinceNpcs.hassan, "Talk-to") { talkHassan(it) }
        onNpcOpLabel(npcTypes, PrinceNpcs.osman, "Talk-to") { talkOsman(it) }
        onNpcOpLabel(npcTypes, PrinceNpcs.leela, "Talk-to") { talkLeela(it) }
        onNpcOpLabel(npcTypes, PrinceNpcs.ned, "Talk-to") { talkNed(it) }
        onNpcOpLabel(npcTypes, PrinceNpcs.keli, "Talk-to") { talkKeli(it, usedRope = false) }
        onOpNpcU(PrinceNpcs.keli, PrinceObjs.rope) { talkKeli(it.npc, usedRope = true) }
        onNpcOpLabel(npcTypes, PrinceNpcs.joe, "Talk-to") { talkJoe(it) }
        onOpNpcU(PrinceNpcs.joe, PrinceObjs.beer) { talkJoe(it.npc) }
        for (prince in PrinceNpcs.princes) {
            onNpcOpLabel(npcTypes, prince, "Talk-to") { talkPrince(it) }
        }
        onLocOpLabel(locTypes, PrinceLocs.cell_door, "Open") { cellDoor(it) }
        onOpLocU(PrinceLocs.cell_door, PrinceObjs.key) { cellDoor(it.loc) }
        onOpHeldU(PrinceObjs.yellow_dye, PrinceObjs.plain_wig) { dyeWig() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(PRINCE_ALI_RESCUE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(PRINCE_ALI_RESCUE, value)
    }

    private fun ProtectedAccess.flag(bit: Int): Boolean =
        player.vars[PrinceVarps.progress] and bit != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        val value = player.vars[PrinceVarps.progress] or bit
        VarPlayerIntMapSetter.set(player, PrinceVarps.progress, value)
    }

    private fun ProtectedAccess.hasDisguise(): Boolean =
        carries(PrinceObjs.blonde_wig) &&
            carries(PrinceObjs.skin_paste) &&
            carries(PrinceObjs.skirt)

    /* Al Kharid: Hassan and Osman */

    private suspend fun ProtectedAccess.talkHassan(npc: Npc) {
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
        when {
            state == 0 -> runQuestSection(npc, dialogue, "Talking to Chancellor Hassan", hooks)
            state == STARTED ->
                runQuestSection(npc, dialogue, "Talking to him again before meeting Osman")
            state < ESCAPED -> runQuestSection(npc, dialogue, HASSAN_AFTER_OSMAN)
            state == ESCAPED -> {
                startDialogue(npc) {
                    chatNpc(
                        happy,
                        "You have the eternal gratitude of the Emir for rescuing his son. I am " +
                            "authorised to offer you this reward.",
                    )
                    mesbox("Chancellor Hassan hands you $REWARD_COINS coins.")
                }
                invAddOrDrop(objRepo, PrinceObjs.coins, REWARD_COINS)
                completeQuest(PRINCE_ALI_RESCUE)
            }
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Al Kharid is in your debt, friend.")
                }
        }
    }

    private suspend fun ProtectedAccess.talkOsman(npc: Npc) {
        val imprint = PrinceObjs.key_print
        val bar = PrinceObjs.bronze_bar
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the key imprint but no bronze bar:" to {
                            state < KEY_MADE && carries(imprint) && !carries(bar)
                        },
                        "If the player has the key imprint and a bronze bar:" to {
                            state < KEY_MADE && carries(imprint) && carries(bar)
                        },
                        "If the player has not acquired the key imprint:" to {
                            state >= KEY_MADE || !carries(imprint)
                        },
                    ),
                boxes =
                    mapOf(
                        "You give Osman the imprint along with a bronze bar." to {
                            if (carries(imprint) && carries(bar)) {
                                invDel(inv, imprint)
                                invDel(inv, bar)
                                setState(KEY_MADE)
                            }
                            false
                        },
                    ),
            )
        when {
            state == 0 -> startDialogue(npc) { chatNpc(neutral, "What do you want?") }
            state == STARTED -> {
                runQuestSection(npc, dialogue, "Talking to Osman")
                setState(BRIEFED)
            }
            state < ESCAPED -> runQuestSection(npc, dialogue, "Talking to Osman again", hooks)
            else -> runQuestSection(npc, dialogue, "Talking to Osman (2)")
        }
    }

    /* Draynor: Leela, Ned, Lady Keli, Joe, the Prince */

    private suspend fun ProtectedAccess.talkLeela(npc: Npc) {
        val coins = PrinceObjs.coins
        var replaced = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't obtained the key print yet:" to {
                            !carries(PrinceObjs.key_print)
                        },
                        "If the player has the key print in their inventory:" to {
                            carries(PrinceObjs.key_print)
                        },
                        "If the player does not have 15 coins:" to {
                            !carries(coins, KEY_PRICE)
                        },
                        "If the player has 15 coins:" to { carries(coins, KEY_PRICE) },
                    ),
                boxes =
                    mapOf(
                        "Leela gives you a key." to {
                            invDel(inv, coins, KEY_PRICE)
                            replaced = true
                            false
                        },
                    ),
            )
        val haveKey = carries(PrinceObjs.key)
        when {
            state < STARTED ->
                startDialogue(npc) { chatNpc(neutral, "Hello there. Lovely day, isn't it?") }
            state >= ESCAPED -> runQuestSection(npc, dialogue, "Talking to Leela (2)")
            state >= KEY_MADE && flag(KEY_GIVEN) && !haveKey ->
                runQuestSection(npc, dialogue, LEELA_LOST_KEY, hooks)
            state >= JOE_DRUNK -> runQuestSection(npc, dialogue, LEELA_JOE_DRUNK)
            state >= KEY_MADE && hasDisguise() && !flag(KEY_GIVEN) -> {
                runQuestSection(npc, dialogue, LEELA_READY, hooks)
                startDialogue(npc) {
                    chatNpc(neutral, "Here's the key my father had made. Take good care of it.")
                }
                invAddOrDrop(objRepo, PrinceObjs.key)
                setFlag(KEY_GIVEN)
            }
            !flag(MET_LEELA) -> {
                runQuestSection(npc, dialogue, "Talking to Leela", hooks)
                setFlag(MET_LEELA)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Leela again", hooks)
        }
        if (replaced) {
            invAddOrDrop(objRepo, PrinceObjs.key)
        }
    }

    private suspend fun ProtectedAccess.talkNed(npc: Npc) {
        var wantsRope = false
        val wool = PrinceObjs.wool
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have three balls of wool:" to {
                            !carries(wool, WIG_WOOL)
                        },
                        "If the player has three balls of wool:" to { carries(wool, WIG_WOOL) },
                    ),
                options = mapOf("Yes, I would like some rope." to { wantsRope = true }),
                boxes =
                    mapOf(
                        "Ned gives you a pretty good wig." to {
                            if (carries(wool, WIG_WOOL)) {
                                invDel(inv, wool, WIG_WOOL)
                                invAddOrDrop(objRepo, PrinceObjs.plain_wig)
                            }
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Ned", hooks)
        if (wantsRope) {
            sellRope(npc)
        }
    }

    /** Ned's rope: 15 coins, or 4 balls of wool. */
    private suspend fun ProtectedAccess.sellRope(npc: Npc) {
        val wool = PrinceObjs.wool
        when {
            carries(wool, ROPE_WOOL) -> {
                invDel(inv, wool, ROPE_WOOL)
                startDialogue(npc) {
                    chatNpc(happy, "I see you have the wool. I'll make you some rope from that.")
                }
            }
            carries(PrinceObjs.coins, ROPE_PRICE) -> {
                invDel(inv, PrinceObjs.coins, ROPE_PRICE)
                startDialogue(npc) { chatNpc(happy, "That'll be $ROPE_PRICE coins. Here you go.") }
            }
            else -> {
                startDialogue(npc) {
                    chatNpc(
                        neutral,
                        "Rope is $ROPE_PRICE coins, or bring me $ROPE_WOOL balls of wool.",
                    )
                }
                return
            }
        }
        invAddOrDrop(objRepo, PrinceObjs.rope)
    }

    private fun ProtectedAccess.dyeWig() {
        invDel(inv, PrinceObjs.yellow_dye)
        invDel(inv, PrinceObjs.plain_wig)
        invAdd(inv, PrinceObjs.blonde_wig)
        mes("You dye the wig blonde.")
    }

    private fun ProtectedAccess.readyForKeli(): Boolean =
        state == JOE_DRUNK &&
            carries(PrinceObjs.rope) &&
            carries(PrinceObjs.key) &&
            hasDisguise()

    private suspend fun ProtectedAccess.talkKeli(npc: Npc, usedRope: Boolean) {
        val clay = PrinceObjs.soft_clay
        val imprint = PrinceObjs.key_print
        val ready = readyForKeli()
        if (usedRope || (ready && state < KELI_TIED)) {
            var tied = false
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player is not ready:" to { !ready },
                            "If the player used rope on Lady Keli:" to { ready && usedRope },
                            "If the player is ready and talks to Lady Keli:" to {
                                ready && !usedRope
                            },
                        ),
                    boxes =
                        mapOf(
                            TIE_BOX to {
                                tied = true
                                false
                            },
                        ),
                )
            runQuestSection(npc, dialogue, "Dealing with Lady Keli", hooks)
            if (tied) {
                invDel(inv, PrinceObjs.rope)
                setState(KELI_TIED)
            }
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have soft clay or already has the key print:" to {
                            !carries(clay) || carries(imprint) || state >= KEY_MADE
                        },
                    ),
                boxes =
                    mapOf(
                        IMPRINT_BOX to {
                            if (carries(clay)) {
                                invDel(inv, clay)
                                invAddOrDrop(objRepo, imprint)
                            }
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Lady Keli", hooks)
    }

    private suspend fun ProtectedAccess.talkJoe(npc: Npc) {
        when {
            state >= ESCAPED -> runQuestSection(npc, dialogue, JOE_AFTER)
            state >= JOE_DRUNK -> runQuestSection(npc, dialogue, "Talking to Joe once he is drunk")
            state < KEY_MADE || !carries(PrinceObjs.beer) ->
                runQuestSection(npc, dialogue, "Talking to Joe")
            else -> drinkWithJoe(npc)
        }
    }

    private suspend fun ProtectedAccess.drinkWithJoe(npc: Npc) {
        val beer = PrinceObjs.beer
        val had = invTotal(inv, beer)
        var drunk = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has less than three beers:" to { had < JOE_BEERS },
                        "If the player has two more beers:" to { had >= JOE_BEERS },
                    ),
                boxes =
                    mapOf(
                        "You hand a beer to the guard. He drinks it in seconds." to {
                            invDel(inv, beer)
                            false
                        },
                        JOE_BEERS_BOX to {
                            invDel(inv, beer, JOE_BEERS - 1)
                            drunk = true
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Joe (2)", hooks)
        if (drunk) {
            setState(JOE_DRUNK)
        }
    }

    private suspend fun ProtectedAccess.cellDoor(door: BoundLocInfo) {
        if (!carries(PrinceObjs.key)) {
            mes("The door is locked.")
            return
        }
        if (state < KELI_TIED) {
            startDialogue {
                mesbox("You'll need to deal with Lady Keli before freeing the Prince.")
            }
            return
        }
        mes("You unlock the door.")
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkPrince(npc: Npc) {
        if (state >= ESCAPED) {
            runQuestSection(npc, dialogue, "Talking to Prince Ali (2)")
            return
        }
        var escaped = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have a complete disguise and key:" to {
                            !hasDisguise() || !carries(PrinceObjs.key)
                        },
                        "If the player has a complete disguise and key:" to {
                            hasDisguise() && carries(PrinceObjs.key)
                        },
                    ),
                boxes =
                    mapOf(
                        "Prince Ali puts on the disguise and uses it to escape." to {
                            escaped = true
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Prince Ali", hooks)
        if (escaped && hasDisguise()) {
            invDel(inv, PrinceObjs.blonde_wig)
            invDel(inv, PrinceObjs.skin_paste)
            invDel(inv, PrinceObjs.skirt)
            invDel(inv, PrinceObjs.key)
            setState(ESCAPED)
        }
    }

    companion object {
        const val STARTED = 10
        const val BRIEFED = 20
        const val KEY_MADE = 30
        const val JOE_DRUNK = 40
        const val KELI_TIED = 50
        const val ESCAPED = 100
        const val COMPLETE = 110

        private const val MET_LEELA = 1
        private const val KEY_GIVEN = 1 shl 1

        private const val REWARD_COINS = 700
        private const val KEY_PRICE = 15
        private const val WIG_WOOL = 3
        private const val ROPE_WOOL = 4
        private const val ROPE_PRICE = 15
        private const val JOE_BEERS = 3

        private const val JOE_AFTER = "Talking to Joe after Prince Ali's escape"
        private const val HASSAN_AFTER_OSMAN = "Talking to him after meeting Osman"
        private const val LEELA_JOE_DRUNK = "Talking to Leela once Joe is drunk"
        private const val LEELA_LOST_KEY = "Talking to Leela again if the player lost the key"
        private const val LEELA_READY = "Talking to Leela after having all the required items"
        private const val TIE_BOX = "You overpower Keli, tie her up, and put her in a cupboard."
        private const val IMPRINT_BOX =
            "As you touch the key, you take an imprint of it using your soft clay."
        private const val JOE_BEERS_BOX =
            "You hand two more beers to the guard. He takes a sip of one, and then he quickly " +
                "drinks them both."

        val PRINCE_ALI_RESCUE =
            QuestDef(
                name = "Prince Ali Rescue",
                varp = { PrinceVarps.princequest },
                complete = COMPLETE,
                questPoints = 3,
                rewards = listOf("700 coins"),
                scrollObj = { PrinceObjs.key },
            )
    }
}

// Every cache ref Prince Ali Rescue uses (re-check after the rev 243 cutover).

internal object PrinceNpcs : NpcReferences() {
    val hassan = find("hassan")
    val osman = find("osman")
    val leela = find("leela")
    val ned = find("ned")

    /* Multinpc children on `princequest` (bases: lady_keli, joe, prince_ali_prison/_palace). */
    val keli = find("lady_keli_vis")
    val joe = find("joe_vis")
    val princes = listOf(find("prince_ali_vis_blackeye"), find("prince_ali_vis"))
}

internal object PrinceLocs : LocReferences() {
    /** The Prince's cell in Draynor jail. */
    val cell_door = find("alidoor")
}

internal object PrinceObjs : ObjReferences() {
    val wool = find("ball_of_wool")
    val plain_wig = find("plainwig")
    val blonde_wig = find("blondwig")
    val yellow_dye = find("yellowdye")
    val skin_paste = find("skinpaste")
    val skirt = find("pink_skirt")
    val soft_clay = find("softclay")
    val key_print = find("keyprint")
    val bronze_bar = find("bronze_bar")
    val key = find("princeskey")
    val rope = find("rope")
    val beer = find("beer")
    val coins = find("coins")
}

internal object PrinceVarps : VarpReferences() {
    val princequest = find("princequest")

    /** Server varp (65773): met Leela, key handed over. */
    val progress = find("prince_progress")
}

internal object PrinceVarpBuilds : VarpBuilder() {
    init {
        build("prince_progress")
    }
}
