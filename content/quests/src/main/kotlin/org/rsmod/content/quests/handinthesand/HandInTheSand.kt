package org.rsmod.content.quests.handinthesand

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Hand in the Sand (OSRS Wiki). The quest varbit is the cache's `handsand_quest` (0-160;
 * Bert's multinpc shows his Sand op at 160). `handsand_serum` holds the truth serum's progress and
 * `handsand_question1-3` Sandy's answers; `handsand_counter_multi` puts Betty's vial on her
 * counter.
 * - Bert (Yanille) gives the sandy hand; a beer for the Guard Captain (Dragon Inn) soaks it.
 * - Zavistic Rarve (Wizards' Guild, or its bell) takes it; Bert gives his rota.
 * - Sandy's desk (Brimhaven): Sandy's rota; pickpocket Sandy for sand. Bert swaps the rotas for a
 *   magic scroll; Zavistic swaps it for the magical orb (and, with a vial, sends you to Betty).
 * - Betty (Port Sarim) fills the vial: bottled water. Redberries then white berries: pink dye;
 *   dye on a lantern lens: the rose-tinted lens. Betty puts a vial on her counter; the lens on the
 *   counter: truth serum; Betty adds the sand.
 * - Distract Sandy and pour the serum in his coffee; activate the orb and ask Sandy everything.
 * - The orb to Zavistic; 5 earth runes and a bucket of sand; Mazion (Entrana sandpit) has the
 *   wizard's head; show it to Zavistic. 1 QP, 1,000 Thieving and 9,000 Crafting xp.
 *
 * Simplified (Authored, not Wiki): Zavistic talks the same through the bell as inside the guild;
 * the lens works from anywhere in the shop; a distraction works half the time; Bert's daily sand
 * delivery (his Sand op) isn't built yet.
 */
class HandInTheSand
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(HandInTheSand::class.java, "hand_in_the_sand") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Zavistic Rarve" to npcTypes[HandNpcs.zavistic])
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (bert in HandNpcs.berts) {
                register(npcTypes, bert, HAND_IN_THE_SAND, wants = { !complete }) { talkBert(it) }
            }
            register(npcTypes, HandNpcs.captain, HAND_IN_THE_SAND, wants = { true }) { talkCaptain(it) }
            register(npcTypes, HandNpcs.zavistic, HAND_IN_THE_SAND, wants = { inProgress }) {
                talkZavistic(it)
            }
            default(npcTypes, HandNpcs.zavistic) {
                startDialogue(it) {
                    // Authored line (not Wiki): outside the quest.
                    chatNpc(neutral, "We're very busy here. Please don't disturb the wizards.")
                }
            }
            register(npcTypes, HandNpcs.sandy, HAND_IN_THE_SAND, wants = { true }) { talkSandy(it) }
            register(npcTypes, HandNpcs.mazion, HAND_IN_THE_SAND, wants = { true }) { talkMazion(it) }
            register(npcTypes, HandNpcs.betty, HAND_IN_THE_SAND, wants = { wantsBetty() }) {
                talkBetty(it)
            }
        }
        onLocOpLabel(locTypes, HandLocs.bell, "Ring") { ringBell() }
        onNpcOpLabel(npcTypes, HandNpcs.sandy, "Pickpocket") { pickpocketSandy(it) }
        onLocOpLabel(locTypes, HandLocs.desk, "Search") { searchDesk() }
        onOpLocU(HandLocs.coffee, HandObjs.truth_serum) { pourSerum() }
        onOpLocU(HandLocs.counter, HandObjs.rose_lens) { focusLens() }

        onOpHeldU(HandObjs.redberries, HandObjs.bottled_water) { addRedberries() }
        onOpHeldU(HandObjs.white_berries, HandObjs.redberry_juice) { addWhiteBerries() }
        onOpHeldU(HandObjs.white_berries, HandObjs.bottled_water) {
            // Authored line (not Wiki): the order matters.
            mes("You should add the redberries first.")
        }
        onOpHeldU(HandObjs.pink_dye, HandObjs.lantern_lens) { dyeLens() }
        onOpHeldU(HandObjs.sand, HandObjs.truth_serum) {
            runQuestSection(null, dialogue, "Trying to put the sand in the truth serum yourself")
        }
        onOpHeld1(HandObjs.orb) { activateOrb() }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(HAND_IN_THE_SAND)

    private val ProtectedAccess.complete: Boolean
        get() = stage >= COMPLETE

    private val ProtectedAccess.inProgress: Boolean
        get() = stage in HAND until COMPLETE

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(HAND_IN_THE_SAND, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.fullHooks(text: String, extra: Map<String, () -> Boolean> = emptyMap()) =
        SimpleQuestHooks(conditions = mapOf(text to { inv.isFull() }) + extra)

    /* Bert */

    private suspend fun ProtectedAccess.talkBert(npc: Npc) {
        when {
            stage == 0 -> {
                var given = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { given = true }))
                runQuestSection(npc, dialogue, "Talking to Bert", hooks)
                if (given) {
                    invAddOrDrop(objRepo, HandObjs.sandy_hand)
                    advance(HAND)
                }
            }
            stage == HAND -> {
                if (!carries(HandObjs.sandy_hand)) {
                    invAddOrDrop(objRepo, HandObjs.sandy_hand)
                    mes("Bert hands you the sandy hand again.") // Authored (not Wiki).
                    return
                }
                runQuestSection(npc, dialogue, "Talking to Bert again")
            }
            stage < TOLD_WIZARD -> {
                val lost = !carries(HandObjs.beer_hand)
                val full = inv.isFull()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has lost the sandy hand:" to { lost },
                                "If the player doesn't have enough inventory space:" to { full },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Bert afterwards", hooks)
                if (lost && !full) invAdd(inv, HandObjs.beer_hand)
            }
            stage == TOLD_WIZARD -> {
                val full = inv.isFull()
                runQuestSection(npc, dialogue, "Getting Bert's rota", fullHooks(NO_SPACE))
                if (!full) {
                    invAdd(inv, HandObjs.bert_rota)
                    advance(BERT_ROTA)
                }
            }
            stage == BERT_ROTA && carries(HandObjs.sandy_rota) -> {
                runQuestSection(npc, dialogue, "Returning to Bert with his original rota")
                invDel(inv, HandObjs.sandy_rota)
                if (carries(HandObjs.bert_rota)) invDel(inv, HandObjs.bert_rota)
                invAddOrDrop(objRepo, HandObjs.scroll)
                advance(SCROLL)
            }
            stage == BERT_ROTA -> {
                val lost = !carries(HandObjs.bert_rota)
                val full = inv.isFull()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player has lost the rota:" to { lost }, NO_SPACE to { full })
                    )
                runQuestSection(npc, dialogue, "Talking to Bert afterwards (2)", hooks)
                if (lost && !full) invAdd(inv, HandObjs.bert_rota)
            }
            else -> runQuestSection(npc, dialogue, "Talking afterwards")
        }
    }

    /* The Guard Captain */

    private suspend fun ProtectedAccess.talkCaptain(npc: Npc) {
        when {
            stage == HAND && carries(HandObjs.beer) && carries(HandObjs.sandy_hand) -> {
                runQuestSection(npc, dialogue, "Talking again with a beer")
                invDel(inv, HandObjs.beer)
                invReplace(inv, HandObjs.sandy_hand, 1, HandObjs.beer_hand)
                advance(BEER_HAND)
            }
            stage == BEER_HAND && !carries(HandObjs.beer_hand) -> {
                val full = inv.isFull()
                runQuestSection(
                    npc,
                    dialogue,
                    "Reclaiming the beer soaked hand from the captain",
                    fullHooks("If the player doesn't have enough inventory space:"),
                )
                if (!full) invAdd(inv, HandObjs.beer_hand)
            }
            stage >= BEER_HAND -> runQuestSection(npc, dialogue, "Talking to Guard Captain afterwards")
            else -> runQuestSection(npc, dialogue, "Talking to Guard Captain")
        }
    }

    /* Zavistic Rarve */

    private suspend fun ProtectedAccess.ringBell() {
        if (!inProgress) {
            // Authored line (not Wiki): nobody answers.
            mes("You ring the bell, but nobody seems to be coming.")
            return
        }
        talkZavistic(null)
    }

    private suspend fun ProtectedAccess.zavistic(npc: Npc?, title: String, hooks: SimpleQuestHooks) {
        runQuestSection(npc, dialogue, title, hooks, speakers)
    }

    private suspend fun ProtectedAccess.talkZavistic(npc: Npc?) {
        when {
            stage < TOLD_WIZARD -> {
                val hand = carries(HandObjs.beer_hand)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "Speaking to Zavistic Rarve inside the guild:" to { true },
                                "If the player does not have the beer soaked hand in their inventory:" to
                                    { !hand },
                                "If the player has the beer soaked hand in their inventory:" to { hand },
                            )
                    )
                zavistic(npc, "Talking to Zavistic Rarve", hooks)
                if (hand) {
                    invDel(inv, HandObjs.beer_hand)
                    advance(TOLD_WIZARD)
                }
            }
            stage < SCROLL -> zavistic(npc, "Talking to Zavistic afterwards", SimpleQuestHooks())
            stage == SCROLL -> {
                if (!carries(HandObjs.scroll)) {
                    // Authored line (not Wiki).
                    mes("You should bring Zavistic the scroll Bert gave you.")
                    return
                }
                var swapped = false
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(SANDY_PROBLEM to { swapped = true })
                    )
                zavistic(npc, "Returning back to Zavistic", hooks)
                if (swapped) {
                    invDel(inv, HandObjs.scroll)
                    invAddOrDrop(objRepo, HandObjs.orb)
                    advance(ORB)
                }
            }
            stage == ORB -> {
                val lost = !carries(HandObjs.orb)
                val vial = carries(HandObjs.vial)
                val full = inv.isFull()
                var teleport = false
                var regive = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has lost the magical orb:" to { lost },
                                NO_SPACE to { full },
                                "If the player does not have a vial with them:" to { !vial },
                                "If the player has a vial with them:" to { vial },
                            ),
                        options =
                            mapOf(
                                "Yes, that would be great!" to { teleport = true },
                                "I've lost my magical scrying orb!" to { regive = !full },
                            ),
                    )
                zavistic(npc, "Talking to Zavistic again", hooks)
                if (regive) invAdd(inv, HandObjs.orb)
                if (teleport) {
                    setFlag(HandVarBits.tele, 1)
                    telejump(BETTY_SHOP)
                }
            }
            stage < RECORDED -> zavistic(npc, "Talking to Zavistic Rarve again", SimpleQuestHooks())
            stage == RECORDED -> {
                val orb = carries(HandObjs.orb_recording)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the orb is lost or isn't in the player's inventory:" to { !orb },
                                "Else, with the orb in hand:" to { orb },
                            )
                    )
                zavistic(npc, "Returning back to Zavistic with the magical orb", hooks)
                if (orb) invDel(inv, HandObjs.orb_recording)
                advance(TOLD_STORY)
            }
            stage == TOLD_STORY -> {
                val items = carries(HandObjs.earth_rune, EARTH_RUNES) && carries(HandObjs.bucket_sand)
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player doesn't have the required items:" to { !items })
                    )
                zavistic(npc, "Talking to Zavistic Rarve again (2)", hooks)
                if (items) {
                    invDel(inv, HandObjs.earth_rune, EARTH_RUNES)
                    invDel(inv, HandObjs.bucket_sand)
                    advance(SANDPIT)
                }
            }
            stage == HEAD && carries(HandObjs.wizard_head) -> {
                zavistic(npc, "If the player has checked the Entrana sandpit", SimpleQuestHooks())
                invDel(inv, HandObjs.wizard_head)
                statAdvance(stats.thieving, THIEVING_XP * xpMods.get(player, stats.thieving))
                statAdvance(stats.crafting, CRAFTING_XP * xpMods.get(player, stats.crafting))
                completeQuest(HAND_IN_THE_SAND)
            }
            else -> zavistic(npc, "If the player has not checked the Entrana sandpit", SimpleQuestHooks())
        }
    }

    /* Sandy */

    private suspend fun ProtectedAccess.talkSandy(npc: Npc) {
        val serum = flag(HandVarBits.serum)
        when {
            stage >= RECORDED -> runQuestSection(npc, dialogue, "Talking to Sandy again")
            stage >= POURED -> askSandy(npc)
            stage == SERUM_DONE && serum == SERUM_COMPLETE -> distractSandy(npc)
            stage == SERUM_DONE && serum == SANDY_DISTRACTED ->
                // Authored line (not Wiki): Sandy is still staring out of the window.
                mes("Sandy is staring out of the window. Now's your chance to get at his coffee!")
            carries(HandObjs.sandy_rota) ->
                runQuestSection(npc, dialogue, "Talking to Sandy with Sandy's rota in the inventory")
            else -> runQuestSection(npc, dialogue, "Talking to Sandy")
        }
    }

    private suspend fun ProtectedAccess.distractSandy(npc: Npc) {
        var success = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If distracting Sandy succeeds:" to { success },
                        "If distracting Sandy fails:" to { !success },
                    ),
                options =
                    DISTRACTIONS.associateWith<String, suspend () -> Unit> {
                        { success = random.randomBoolean(DISTRACT_CHANCE) }
                    },
            )
        runQuestSection(npc, dialogue, "Distracting Sandy", hooks)
        if (success) setFlag(HandVarBits.serum, SANDY_DISTRACTED)
    }

    private suspend fun ProtectedAccess.askSandy(npc: Npc) {
        val active = stage >= ORB_ACTIVE
        fun asked(): Boolean = HandVarBits.questions.all { flag(it) == 1 }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the magical orb is not activated:" to { !active },
                        "If the magical orb is activated:" to { active },
                        "If all questions have been asked:" to { asked() },
                        "If not all questions have been asked:" to { !asked() },
                        "If all the available questions have not been asked:" to { !asked() },
                    ),
                options =
                    QUESTIONS.withIndex().associate<IndexedValue<String>, String, suspend () -> Unit> {
                        (i, q) ->
                        q to { setFlag(HandVarBits.questions[i], 1) }
                    },
            )
        runQuestSection(npc, dialogue, "Talking to Sandy afterwards", hooks)
        if (active && asked() && carries(HandObjs.orb)) {
            invReplace(inv, HandObjs.orb, 1, HandObjs.orb_recording)
            advance(RECORDED)
        }
    }

    private suspend fun ProtectedAccess.pickpocketSandy(npc: Npc) {
        if (stage < BERT_ROTA || carries(HandObjs.sand)) {
            mes("You find nothing of interest in Sandy's pockets.") // Authored (not Wiki).
            return
        }
        val success = random.randomBoolean(PICKPOCKET_CHANCE)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        NO_SPACE to { inv.isFull() },
                        "If the pickpocket attempt fails:" to { !success },
                        "If the pickpocket attempt succeeds:" to { success },
                    )
            )
        val full = inv.isFull()
        runQuestSection(null, dialogue, "Pickpocketing Sandy", hooks)
        if (success && !full) invAdd(inv, HandObjs.sand)
    }

    private suspend fun ProtectedAccess.searchDesk() {
        if (stage < BERT_ROTA) {
            mes("You find nothing of interest on the desk.") // Authored (not Wiki).
            return
        }
        val has = carries(HandObjs.sandy_rota) || stage >= SCROLL
        val full = inv.isFull()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        NO_SPACE to { full && !has },
                        "If the player does not have Sandy's rota:" to { !has },
                        "If the player already has Sandy's rota:" to { has },
                    )
            )
        runQuestSection(null, dialogue, "Searching Sandy's Desk", hooks)
        if (!has && !full) invAdd(inv, HandObjs.sandy_rota)
    }

    private suspend fun ProtectedAccess.pourSerum() {
        val serum = flag(HandVarBits.serum)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the the truth serum isn't complete:" to { serum < SERUM_COMPLETE },
                        "If Sandy has not been distracted:" to { serum == SERUM_COMPLETE },
                        "If sandy has been distracted:" to { serum == SANDY_DISTRACTED },
                    )
            )
        runQuestSection(null, dialogue, "Attempting to pour the truth serum inside Sandy's Coffee Mug", hooks)
        if (serum == SANDY_DISTRACTED) {
            invDel(inv, HandObjs.truth_serum)
            setFlag(HandVarBits.serum, SERUM_POURED)
            advance(POURED)
        }
    }

    private suspend fun ProtectedAccess.activateOrb() {
        if (stage < POURED) {
            // Authored line (not Wiki): too early.
            mes("Zavistic said to activate the orb when you're ready to question Sandy.")
            return
        }
        runQuestSection(null, dialogue, "Activating the magical orb")
        advance(ORB_ACTIVE)
    }

    /* Betty */

    private fun ProtectedAccess.wantsBetty(): Boolean = stage in ORB until COMPLETE

    private suspend fun ProtectedAccess.talkBetty(npc: Npc) {
        var shop = false
        val shopOption: Map<String, suspend () -> Unit> = mapOf(SHOP to { shop = true })
        when {
            stage >= RECORDED -> runQuestSection(npc, dialogue, "Returning to Betty", SimpleQuestHooks(options = shopOption))
            stage < WATER -> {
                val vial = carries(HandObjs.vial)
                var talked = false
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player doesn't have an empty vial:" to { !vial }),
                        options = mapOf(SHOP to { shop = true }, HITS to { talked = true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Betty", hooks)
                if (talked && vial) {
                    invReplace(inv, HandObjs.vial, 1, HandObjs.bottled_water)
                    advance(WATER)
                }
            }
            stage == WATER && carries(HandObjs.rose_lens) -> {
                var talked = false
                val hooks = SimpleQuestHooks(options = mapOf(SHOP to { shop = true }, HITS to { talked = true }))
                runQuestSection(npc, dialogue, "Talking to Betty with the lens", hooks)
                if (talked) {
                    setFlag(HandVarBits.counter, COUNTER_VIAL)
                    advance(COUNTER)
                }
            }
            stage <= COUNTER ->
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Betty without the rose tinted lens",
                    SimpleQuestHooks(options = shopOption),
                )
            stage == SERUM -> {
                val sand = carries(HandObjs.sand)
                var talked = false
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player doesn't have the sand:" to { !sand }),
                        options = mapOf(SHOP to { shop = true }, HITS to { talked = true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Betty afterwards", hooks)
                if (talked && sand) {
                    invDel(inv, HandObjs.sand)
                    setFlag(HandVarBits.serum, SERUM_COMPLETE)
                    advance(SERUM_DONE)
                }
            }
            else -> {
                val lost = !carries(HandObjs.truth_serum) && stage < POURED
                val full = inv.isFull()
                var regive = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player has lost the truth serum:" to { lost }, NO_SPACE to { full }),
                        options = mapOf(SHOP to { shop = true }, "I've lost it!" to { regive = lost && !full }),
                    )
                runQuestSection(npc, dialogue, "Talking to Betty again", hooks)
                if (regive) {
                    invAdd(inv, HandObjs.truth_serum)
                    setFlag(HandVarBits.serum, SERUM_COMPLETE)
                }
            }
        }
        if (shop) {
            startDialogue(npc) {
                // Authored line (not Wiki): her shop is behind the Trade option.
                chatNpc(happy, "Have a look at my wares - just ask to trade.")
            }
        }
    }

    private suspend fun ProtectedAccess.focusLens() {
        if (flag(HandVarBits.counter) != COUNTER_VIAL) return
        runQuestSection(null, dialogue, "Using the lens on the counter")
        invDel(inv, HandObjs.rose_lens)
        setFlag(HandVarBits.counter, 0)
        invAddOrDrop(objRepo, HandObjs.truth_serum)
        setFlag(HandVarBits.serum, SERUM_MADE)
        advance(SERUM)
    }

    private suspend fun ProtectedAccess.addRedberries() {
        invDel(inv, HandObjs.redberries)
        invReplace(inv, HandObjs.bottled_water, 1, HandObjs.redberry_juice)
        mesbox("Now you just need to add the white berries to make the pink dye.")
    }

    private fun ProtectedAccess.addWhiteBerries() {
        invDel(inv, HandObjs.white_berries)
        invReplace(inv, HandObjs.redberry_juice, 1, HandObjs.pink_dye)
        mes("You add the white berries and make a pink dye.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.dyeLens() {
        invDel(inv, HandObjs.pink_dye)
        invReplace(inv, HandObjs.lantern_lens, 1, HandObjs.rose_lens)
        mesbox("You have successfully made the rose tinted lens!")
    }

    /* Mazion */

    private suspend fun ProtectedAccess.talkMazion(npc: Npc) {
        val full = inv.isFull()
        when {
            stage == SANDPIT -> {
                runQuestSection(npc, dialogue, "Talking to Mazion", fullHooks(NO_SPACE))
                if (!full) {
                    invAdd(inv, HandObjs.wizard_head)
                    advance(HEAD)
                }
            }
            stage == HEAD && carries(HandObjs.wizard_head) ->
                runQuestSection(npc, dialogue, "Talking to Mazion with the head")
            stage == HEAD -> {
                var regive = false
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf(NO_SPACE to { full }),
                        options = mapOf("I've lost my head!" to { regive = !full }),
                    )
                runQuestSection(npc, dialogue, "Reclaiming the Wizard's head", hooks)
                if (regive) invAdd(inv, HandObjs.wizard_head)
            }
            else ->
                startDialogue(npc) {
                    // Authored line (not Wiki): the Entrana sandpit worker.
                    chatNpc(neutral, "Sand, sand, and more sand. Can't stop to chat, sorry!")
                }
        }
    }

    companion object {
        const val HAND = 10
        const val BEER_HAND = 20
        const val TOLD_WIZARD = 30
        const val BERT_ROTA = 40
        const val SCROLL = 60
        const val ORB = 70
        const val WATER = 90
        const val COUNTER = 100
        const val SERUM = 110
        const val SERUM_DONE = 120
        const val POURED = 130
        const val ORB_ACTIVE = 135
        const val RECORDED = 140
        const val TOLD_STORY = 150
        const val SANDPIT = 155
        const val HEAD = 158
        const val COMPLETE = 160

        /** `handsand_serum`. */
        private const val SERUM_MADE = 1
        private const val SERUM_COMPLETE = 2
        private const val SANDY_DISTRACTED = 3
        private const val SERUM_POURED = 4

        /** `handsand_counter_multi`: the counter with Betty's vial. */
        private const val COUNTER_VIAL = 1

        private const val EARTH_RUNES = 5
        private const val DISTRACT_CHANCE = 2
        private const val PICKPOCKET_CHANCE = 2
        private const val THIEVING_XP = 1000.0
        private const val CRAFTING_XP = 9000.0

        private const val NO_SPACE = "If the player doesn't have inventory space:"
        private const val SANDY_PROBLEM = "I have a rather sandy problem that I'd like to palm off on you."
        private const val HITS = "Talk to Betty about the Hand in the Sand."
        private const val SHOP = "Talk to Betty about her shop."
        private val DISTRACTIONS =
            listOf(
                "There's a herd of huge mutant herring about to drop from the sky!",
                "But the pygmy shrews have eaten all the sand!",
                "A small parrot with a pink banana is sitting outside your window!",
            )
        private val QUESTIONS =
            listOf(
                "Why is Bert's rota different from the original?",
                "Why doesn't Bert remember the change in his hours?",
                "What happened to the wizard?",
            )

        /** Authored (Ryan-test): inside Betty's shop, Port Sarim. */
        private val BETTY_SHOP = CoordGrid(3013, 3257, 0)

        val HAND_IN_THE_SAND =
            QuestDef(
                name = "The Hand in the Sand",
                varbit = { HandVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,000 Thieving XP", "9,000 Crafting XP"),
                scrollObj = { HandObjs.wizard_head },
                listRow = "quest_handinthesand",
            )
    }
}

// Every cache ref The Hand in the Sand uses (re-check after cache updates).

internal object HandNpcs : NpcReferences() {
    /** The children of `handsand_bert` (handsand_quest; Sand op at 160). */
    val berts = listOf(find("handsand_bert_1op"), find("handsand_bert_2ops"))
    val captain = find("handsand_guard_captain")
    val zavistic = find("zogre_human_zavistic_rarve")
    /** Child 0 of `handsand_sandy` (handsand_sandy_multi). */
    val sandy = find("handsand_sandy0")
    val mazion = find("handsand_naziom")
    /** The Port Sarim magic shop's Betty (a generic-shops vendor). */
    val betty = find("betty")
}

internal object HandLocs : LocReferences() {
    /** Outside the Wizards' Guild's east doors. */
    val bell = find("zogre_outdoor_bell")
    val desk = find("handsand_desk")
    /** Child 0 of handsand_coffee_multiloc. */
    val coffee = find("handsand_coffee")
    /** Child 1 of handsand_counter_multiloc (Betty's vial on the counter). */
    val counter = find("handsand_counter")
}

internal object HandObjs : ObjReferences() {
    val sandy_hand = find("handsand_sandyhand")
    val beer_hand = find("handsand_beerhand")
    val bert_rota = find("handsand_rota_bert")
    val sandy_rota = find("handsand_rota_sandy")
    val scroll = find("handsand_scroll_magic")
    val orb = find("handsand_orb_storage")
    val orb_recording = find("handsand_orb_recording")
    val truth_serum = find("handsand_truthserum")
    val bottled_water = find("handsand_bottle_water")
    val redberry_juice = find("handsand_redberry_juice")
    val pink_dye = find("handsand_pink_dye")
    val rose_lens = find("handsand_rose_lens")
    val wizard_head = find("handsand_wizhead")
    val sand = find("handsand_sand")
    val beer = find("beer")
    val vial = find("vial_empty")
    val redberries = find("redberries")
    val white_berries = find("white_berries")
    val lantern_lens = find("bullseye_lantern_lens")
    val earth_rune = find("earthrune")
    val bucket_sand = find("bucket_sand")
}

internal object HandVarBits : VarBitReferences() {
    val quest = find("handsand_quest")
    val tele = find("handsand_tele")
    val serum = find("handsand_serum")
    val counter = find("handsand_counter_multi")
    val questions =
        listOf(find("handsand_question1"), find("handsand_question2"), find("handsand_question3"))
}
