package org.rsmod.content.quests.idesofmilk

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Ides of Milk (OSRS Wiki). Quest varbit `cowquest` (bits 0-6 of `cowquest_main`), end state
 * 22; no cache multis key on it, so the stages are ours:
 * - 2 Cassius (by the Lumbridge pond) wants the Groats' secrets: Gillie (`cowquest_gillie_
 *   information`) and Seth (`cowquest_seth_information`), whose book sits on his shelf. 6 the book
 *   to Cassius: a milk sample; 8 drunk (1 damage); a second sample for the Duke. 10 the Duke sends
 *   you to Gillie; 12 the second sample drunk beside her: an aggressive bull is penned in the field.
 * - 14 Brutus the bull beaten; 16 Gillie hears he was Cassius' partner; 22 Cassius confronted:
 *   1 QP. Afterwards Gillie gives a cowbell amulet and a lamp (1,000 XP; `cowquest_reward`).
 *
 * Simplified: the bull is released from Gillie's dialogue (the pen gate and the instance aren't in
 * this build's map) and fights like a normal npc, without his snort/charge specials; the lamp's
 * skill picker isn't limited to combat skills.
 */
class IdesOfMilk
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(IdesOfMilk::class.java, "ides_of_milk") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Gillie Groats" to IomNpcs.gillie, "Cassius" to IomNpcs.cassius)
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, IomNpcs.cassius, IDES_OF_MILK, wants = { true }) { talkCassius(it) }
            register(npcTypes, IomNpcs.gillie, IDES_OF_MILK, wants = { state >= STARTED }) { talkGillie(it) }
            register(npcTypes, IomNpcs.seth, IDES_OF_MILK, wants = { state in STARTED until BOOK_GIVEN }) {
                talkSeth(it)
            }
            register(npcTypes, IomNpcs.duke, IDES_OF_MILK, wants = { state in SAMPLED until GILLIE_ASKED }) {
                talkDuke(it)
            }
        }
        onLocOpLabel(locTypes, IomLocs.shelf, "Search") { searchShelf() }
        onHeldOpLabel(objTypes, IomObjs.sample_1, "Drink") { drinkFirst() }
        onHeldOpLabel(objTypes, IomObjs.sample_2, "Drink") { drinkSecond() }
        onEvent<NpcKilledEvent> { bullKilled(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(IDES_OF_MILK)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(IDES_OF_MILK, value)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Boolean = player.vars[varbit] != 0

    private fun ProtectedAccess.setFlag(varbit: VarBitType) {
        VarPlayerIntMapSetter.set(player, varbit, 1)
    }

    private fun ProtectedAccess.give(obj: org.rsmod.game.type.obj.ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    /* Cassius */

    private suspend fun ProtectedAccess.talkCassius(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player is on a quest speedrunning world:" to { false }),
                        options = mapOf("Yes." to { start = true }),
                        boxes =
                            mapOf(
                                "Before starting this quest, be aware that your combat level is lower than the recommended level of 15." to
                                    { player.appearance.combatLevel >= RECOMMENDED_COMBAT }
                            ),
                    )
                runQuestSection(npc, dialogue, "Starting off", hooks)
                if (start) setState(STARTED)
            }
            STARTED ->
                if (flag(IomVarBits.gillie_info) && flag(IomVarBits.seth_info)) {
                    val has = carries(IomObjs.book)
                    val nodes = returning(has)
                    startDialogue(npc) { QuestDialogueRunner(bookHooks(), speakers).run(this, nodes) }
                    if (has) {
                        invDel(inv, IomObjs.book)
                        give(IomObjs.sample_1)
                        setState(BOOK_GIVEN)
                    }
                } else {
                    runQuestSection(npc, dialogue, "Talking to Cassius again before talking to both Gillie and Seth")
                }
            BOOK_GIVEN -> {
                if (!carries(IomObjs.sample_1)) {
                    give(IomObjs.sample_1)
                    mes("Cassius glares as he hands you another sample of milk.")
                }
                runQuestSection(npc, dialogue, "Talking to Cassius before sampling the milk")
            }
            SAMPLED,
            DUKE_ASKED ->
                if (carries(IomObjs.sample_2)) {
                    // Authored (not Wiki): the Wiki's reply when the player still has a sample.
                    startDialogue(npc) {
                        chatNpc(neutral, "Has the Duke given us our permit yet?")
                        chatPlayer(neutral, "Not yet, no, but I am working on it!")
                    }
                } else {
                    runQuestSection(npc, dialogue, "Talking to Cassius again", sampleHooks())
                    if (!inv.isFull()) give(IomObjs.sample_2)
                }
            in GILLIE_ASKED until GILLIE_TOLD -> runQuestSection(npc, dialogue, "Talking to Cassius")
            GILLIE_TOLD -> {
                runQuestSection(npc, dialogue, "Returning to Cassius (2)")
                completeQuest(IDES_OF_MILK)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Cassius (2)")
        }
    }

    /** "Returning to Cassius": the Wiki nests the no-book branch under a line; keep the right one. */
    private fun returning(hasBook: Boolean): List<Node> {
        val section = dialogue.section("Returning to Cassius")
        val nested = section[2] as Node.Line
        val bare = Node.Line(nested.speaker, nested.text)
        return if (hasBook) section.take(2) + bare + section.drop(3) else section.take(2) + bare + nested.children
    }

    private fun bookHooks(): SimpleQuestHooks = SimpleQuestHooks(boxes = mapOf("Cassius snatches the book from your hands." to { false }))

    private fun ProtectedAccess.sampleHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "Without free inventory space:" to { inv.isFull() },
                    "With free inventory space:" to { !inv.isFull() },
                )
        )

    /* The Groats */

    private suspend fun ProtectedAccess.talkGillie(npc: Npc) {
        when {
            state == STARTED && !flag(IomVarBits.gillie_info) -> {
                val seth = flag(IomVarBits.seth_info)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has not yet talked to Seth Groats:" to { !seth },
                                "If the player has already talked to Seth Groats:" to { seth },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Gillie", hooks)
                setFlag(IomVarBits.gillie_info)
            }
            state == DUKE_ASKED -> {
                val nodes = dialogue.section("Talking to Gillie (2)")
                val has = carries(IomObjs.sample_2)
                // The Wiki gives both replies as nested lines: without / with the sample.
                val reply = nodes.last { it is Node.Line && it.children.isNotEmpty() }
                val branch = reply.children
                val split = branch.indexOfFirst { it is Node.Action }
                val chosen = if (has) branch.drop(split + 1) else branch.take(split + 1)
                val head = nodes.takeWhile { it !== reply } + listOf(Node.Line((reply as Node.Line).speaker, reply.text))
                startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, head + chosen) }
                if (has) setState(GILLIE_ASKED)
            }
            state == GILLIE_ASKED -> runQuestSection(npc, dialogue, "Talking to Gillie again")
            state == BULL_TASK -> releaseBull(npc)
            state == BULL_KILLED -> {
                runQuestSection(npc, dialogue, "Talking to Gillie (3)")
                setState(GILLIE_TOLD)
            }
            state == GILLIE_TOLD -> runQuestSection(npc, dialogue, "Talking to Gillie again (3)")
            player.isQuestComplete(IDES_OF_MILK) -> rewards(npc)
            else -> runQuestSection(npc, dialogue, "Talking to Gillie", gillieHooks())
        }
    }

    private fun ProtectedAccess.gillieHooks(): SimpleQuestHooks {
        val seth = flag(IomVarBits.seth_info)
        return SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has not yet talked to Seth Groats:" to { !seth },
                    "If the player has already talked to Seth Groats:" to { seth },
                )
        )
    }

    private suspend fun ProtectedAccess.releaseBull(npc: Npc) {
        runQuestSection(npc, dialogue, "Talking to Gillie again (2)")
        var release = false
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes." to { release = true }, "Yes, and don't ask again." to { release = true }))
        runQuestSection(null, dialogue, "Facing the bull", hooks)
        if (!release) return
        val bull = Npc(npcTypes[IomNpcs.brutus], BULL_SPAWN)
        npcRepo.add(bull, BULL_TICKS)
        bull.opPlayer2(player, aiInteractions)
    }

    private fun bullKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != IomNpcs.brutus.id) return
        val player = event.hero
        if (player.questState(IDES_OF_MILK) != BULL_TASK) return
        player.setQuestState(IDES_OF_MILK, BULL_KILLED)
        player.mes("You notice a strange tag float to the ground as the bull falls. It reads 'Brutus'.")
    }

    private suspend fun ProtectedAccess.rewards(npc: Npc) {
        if (flag(IomVarBits.reward)) {
            when {
                !carries(IomObjs.cowbell) && invTotal(worn, IomObjs.cowbell) == 0 -> {
                    runQuestSection(npc, dialogue, "Talking to Gillie without a cowbell amulet")
                    give(IomObjs.cowbell)
                }
                else -> runQuestSection(npc, dialogue, "Talking to Gillie (4)", fullHooks())
            }
            return
        }
        if (inv.freeSpace() < 2) {
            runQuestSection(npc, dialogue, "Talking to Gillie (4)", fullHooks())
            return
        }
        runQuestSection(npc, dialogue, "Talking to Gillie (4)", fullHooks())
        give(IomObjs.cowbell)
        give(IomObjs.lamp)
        setFlag(IomVarBits.reward)
    }

    private fun ProtectedAccess.fullHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "With a full inventory:" to { inv.freeSpace() < 2 },
                    "With free inventory space:" to { inv.freeSpace() >= 2 },
                ),
            boxes = mapOf("Gillie hands you an amulet with a bell on it." to { false }, "Gillie hands you a magical lamp." to { false }),
        )

    private suspend fun ProtectedAccess.talkSeth(npc: Npc) {
        if (!flag(IomVarBits.seth_info)) {
            val hooks =
                SimpleQuestHooks(
                    conditions = mapOf("ironman]]:" to { false }, "If the player is not an ironman:" to { true })
                )
            runQuestSection(npc, dialogue, "Talking to Seth", hooks)
            setFlag(IomVarBits.seth_info)
            return
        }
        val has = carries(IomObjs.book)
        val section = dialogue.section("Talking to Seth again")
        val last = section.last { it is Node.Line }
        val branch = last.children
        val split = branch.indexOfFirst { it is Node.Action }
        val chosen = if (has) branch.drop(split + 1) else branch.take(split + 1)
        val head = section.takeWhile { it !== last } + listOf(Node.Line((last as Node.Line).speaker, last.text))
        startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, head + chosen) }
    }

    private suspend fun ProtectedAccess.searchShelf() {
        val wants = state == STARTED && flag(IomVarBits.seth_info) && !carries(IomObjs.book)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Before learning about the book or while having the book already:" to { !wants },
                        "Without free inventory space:" to { wants && inv.isFull() },
                        "With free inventory space:" to { wants && !inv.isFull() },
                    ),
                options = emptyMap(),
            )
        if (!wants) {
            mes("You search the shelf but find nothing of interest.") // Authored: the Wiki's two variants.
            return
        }
        runQuestSection(null, dialogue, "Searching the shelf", hooks)
        if (!inv.isFull()) give(IomObjs.book)
    }

    /* The samples and the Duke */

    private suspend fun ProtectedAccess.drinkFirst() {
        if (state != BOOK_GIVEN) return
        invDel(inv, IomObjs.sample_1)
        runQuestSection(null, dialogue, "After sampling the milk", SimpleQuestHooks(), speakers)
        player.queueHit(delay = 0, type = HitType.Typeless, damage = 1)
        give(IomObjs.sample_2)
        setState(SAMPLED)
    }

    private suspend fun ProtectedAccess.drinkSecond() {
        if (state != GILLIE_ASKED) {
            runQuestSection(null, dialogue, "Attempting to drink the second sample")
            return
        }
        val gillie =
            npcRepo.findAll(ZoneKey.from(player.coords), 1).any {
                it.type.id == IomNpcs.gillie.id && it.coords.chebyshevDistance(player.coords) <= GILLIE_RANGE
            }
        if (!gillie) {
            runQuestSection(null, dialogue, "Attempting to drink the sample while not next to Gillie")
            return
        }
        invDel(inv, IomObjs.sample_2)
        runQuestSection(null, dialogue, "Drinking the second sample", SimpleQuestHooks(), speakers)
        setState(BULL_TASK)
    }

    private suspend fun ProtectedAccess.talkDuke(npc: Npc) {
        if (state == SAMPLED) {
            runQuestSection(npc, dialogue, "Talking to the Duke")
            setState(DUKE_ASKED)
        } else {
            runQuestSection(npc, dialogue, "Talking to the Duke again")
        }
    }

    companion object {
        const val STARTED = 2
        const val BOOK_GIVEN = 6
        const val SAMPLED = 8
        const val DUKE_ASKED = 10
        const val GILLIE_ASKED = 12
        const val BULL_TASK = 13
        const val BULL_KILLED = 14
        const val GILLIE_TOLD = 16
        const val COMPLETE = 22

        private const val RECOMMENDED_COMBAT = 15
        private const val BULL_TICKS = 300
        private const val GILLIE_RANGE = 3
        private val BULL_SPAWN = CoordGrid(3263, 3297, 0)

        val IDES_OF_MILK =
            QuestDef(
                name = "The Ides of Milk",
                varbit = { IomVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("Cowbell amulet (from Gillie)", "A lamp of 1,000 XP (from Gillie)"),
                scrollObj = { IomObjs.cowbell },
                listRow = "quest_idesofmilk",
            )
    }
}

// Every cache ref The Ides of Milk uses (re-check after cache updates).

internal object IomNpcs : NpcReferences() {
    val cassius = find("cowboss_farmer")
    val gillie = find("gillie_the_milkmaid")
    val seth = find("favour_seth_groats")
    val duke = find("duke_of_lumbridge")
    val brutus = find("cowboss")
}

internal object IomLocs : LocReferences() {
    val shelf = find("cowquest_seth_shelf")
}

internal object IomObjs : ObjReferences() {
    val book = find("cowquest_husbandry_book")
    val sample_1 = find("cowquest_milk_sample_1")
    val sample_2 = find("cowquest_milk_sample_2")
    val cowbell = find("cowbell_amulet")
    val lamp = find("cowboss_reward_lamp")
}

internal object IomVarBits : VarBitReferences() {
    val quest = find("cowquest")
    val gillie_info = find("cowquest_gillie_information")
    val seth_info = find("cowquest_seth_information")
    val reward = find("cowquest_reward")
}
