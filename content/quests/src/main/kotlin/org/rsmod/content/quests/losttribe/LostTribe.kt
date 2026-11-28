package org.rsmod.content.quests.losttribe

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.dragonslayer.DragonSlayer.Companion.DRAGON_SLAYER
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.goblindiplomacy.GoblinDiplomacy.Companion.GOBLIN_DIPLOMACY
import org.rsmod.content.quests.runemysteries.RuneMysteries.Companion.RUNE_MYSTERIES
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Lost Tribe (OSRS Wiki). Cache quest varbit `lost_tribe_quest`, end state 11 (needs Goblin
 * Diplomacy and Rune Mysteries). The cache multis fix the stages: the cellar wall is shelves at 0,
 * rubble 1-3 and a hole from 4; Mistag gets his escort op at 9; castle Sigmund leaves at 10.
 * - 1 Sigmund (Lumbridge Castle) wants to know what happened in the cellar; one of the castle folk
 *   (the Cook, Hans, Bob, Father Aereck, Donie or Gee: random, server varp `lt_progress` bits 0-2)
 *   saw a goblin (2); 3 the Duke agrees to an investigation; 4 a pickaxe on the cellar rubble
 *   reopens the tunnel and turns up a brooch; 5 the Duke sends you to Reldo; 6 Reldo's bookcase
 *   has 'A History of the Goblin Race' (the Dorgeshuun symbol); 7 the goblin generals teach the
 *   old greetings; 8 the Goblin Bow greets Mistag in the mines (9). Still at 9: the Duke learns of
 *   them and says the silverware is missing (lt_progress bit 4); castle Sigmund's pickpocketed key
 *   opens his chest (H.A.M. robes); showing the Duke the robes -> 10 (Sigmund flees to the H.A.M.
 *   hideout, per the cache multis); the hideout crate holds the silverware, the Duke gives the
 *   treaty, Mistag takes it -> 11 (dbrow end state): 1 QP.
 *
 * Simplified: the greetings are chosen from a menu (OSRS checks the emote you perform), and the
 * leaders' meeting cutscene is its dialogue.
 */
class LostTribe
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(LostTribe::class.java, "lost_tribe") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Sigmund" to LtNpcs.sigmund,
            "Duke Horacio" to LtNpcs.duke,
            "Mistag" to LtNpcs.mistags.first(),
            "Ur-tag" to LtNpcs.urtag,
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (sigmund in LtNpcs.sigmunds) {
                register(npcTypes, sigmund, LOST_TRIBE, wants = { state < COMPLETE }) { talkSigmund(it) }
            }
            register(npcTypes, LtNpcs.duke, LOST_TRIBE, wants = { state in STARTED until COMPLETE }) { talkDuke(it) }
            for ((index, witness) in LtNpcs.witnesses.withIndex()) {
                register(npcTypes, witness.second, LOST_TRIBE, wants = { state == STARTED }) {
                    askWitness(it, index)
                }
            }
            for (reldo in LtNpcs.reldos) {
                register(npcTypes, reldo, LOST_TRIBE, wants = { state == BROOCH_SHOWN }) {
                    runQuestSection(it, dialogue, "Talking to Reldo")
                }
            }
            for (general in LtNpcs.generals) {
                register(npcTypes, general, LOST_TRIBE, wants = { state == SYMBOL }) {
                    runQuestSection(it, dialogue, "The Goblin Generals")
                    setState(GENERALS)
                }
            }
            for (mistag in LtNpcs.mistags) {
                register(npcTypes, mistag, LOST_TRIBE, wants = { state in GENERALS until COMPLETE }) {
                    talkMistag(it)
                }
            }
        }
        for (pickaxe in LtObjs.pickaxes) onOpLocU(LtLocs.rubble, pickaxe) { clearRubble() }
        onLocOpLabel(locTypes, LtLocs.cellar_hole, "Squeeze-through") { squeezeCellar(it.coords) }
        onLocOpLabel(locTypes, LtLocs.bookcase, "Search") { searchBookcase() }
        onNpcOpLabel(npcTypes, LtNpcs.sigmund, "Pickpocket") { pickpocketSigmund() }
        onLocOpLabel(locTypes, LtLocs.chest, "Open") { openChest() }
        onLocOpLabel(locTypes, LtLocs.crate, "Search") { searchCrate() }
        for (mistag in LtNpcs.mistags) {
            onOpNpcU(mistag, LtObjs.brooch) {
                if (state < MET) runQuestSection(it.npc, dialogue, "Using Mistag's brooch on him before the peace treaty")
            }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(LOST_TRIBE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(LOST_TRIBE, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[LtVarps.progress]

    private fun ProtectedAccess.setFlags(value: Int) {
        VarPlayerIntMapSetter.set(player, LtVarps.progress, value)
    }

    private fun ProtectedAccess.witness(): Int = (flags() and WITNESS_MASK) - 1

    private fun ProtectedAccess.give(obj: org.rsmod.game.type.obj.ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    private fun ProtectedAccess.witnessName(): String = LtNpcs.witnesses.getOrNull(witness())?.first ?: "The cook"

    private fun ProtectedAccess.nameHooks(base: SimpleQuestHooks = SimpleQuestHooks()) =
        object : org.rsmod.content.quests.framework.QuestDialogueHooks by base {
            override fun rewrite(text: String): String = text.replace("[NPC]", witnessName())
        }

    /* Sigmund and the Duke */

    private suspend fun ProtectedAccess.talkSigmund(npc: Npc) {
        when (state) {
            0 -> {
                if (!player.isQuestComplete(GOBLIN_DIPLOMACY) || !player.isQuestComplete(RUNE_MYSTERIES)) {
                    startDialogue(npc) {
                        // Authored line (not Wiki): the quest's requirements aren't met.
                        chatNpc(neutral, "I'm far too busy for idle chat, I'm afraid.")
                    }
                    return
                }
                var start = false
                runQuestSection(npc, dialogue, "The Duke's Adviser", SimpleQuestHooks(options = mapOf("Yes." to { start = true })))
                if (start) {
                    setState(STARTED)
                    setFlags((flags() and WITNESS_MASK.inv()) or (random.of(0, LtNpcs.witnesses.size - 1) + 1))
                }
            }
            STARTED ->
                if ((flags() and WITNESSED) != 0) {
                    runQuestSection(npc, dialogue, "Talking to Sigmund after talking to who saw the goblin", nameHooks())
                } else {
                    runQuestSection(npc, dialogue, "Talking to Sigmund again after starting the quest")
                }
            CONVINCED -> runQuestSection(npc, dialogue, "Talking to Sigmund again")
            TUNNEL -> runQuestSection(npc, dialogue, "Talking to Sigmund after opening the tunnel")
            BROOCH_SHOWN, BOOK -> runQuestSection(npc, dialogue, "Talking to Sigmund")
            SYMBOL, GENERALS -> runQuestSection(npc, dialogue, "Speaking to Sigmund after the Goblin generals")
            MET -> runQuestSection(npc, dialogue, "Talking to Sigmund (2)")
            else -> runQuestSection(npc, dialogue, "Talking to Sigmund before returning the silverware")
        }
    }

    private suspend fun ProtectedAccess.talkDuke(npc: Npc) {
        when (state) {
            STARTED ->
                if ((flags() and WITNESSED) != 0) {
                    val hooks =
                        SimpleQuestHooks(
                            conditions =
                                mapOf(
                                    "If the player has completed Dragon Slayer I:" to {
                                        player.isQuestComplete(DRAGON_SLAYER)
                                    },
                                    "If the player has completed Rune Mysteries:" to {
                                        !player.isQuestComplete(DRAGON_SLAYER)
                                    },
                                )
                        )
                    runQuestSection(npc, dialogue, "Convincing Horacio", nameHooks(hooks), speakers)
                    setState(CONVINCED)
                } else {
                    runQuestSection(npc, dialogue, "Asking the Duke")
                }
            CONVINCED -> runQuestSection(npc, dialogue, "Talking to the Duke again")
            TUNNEL ->
                if (carries(LtObjs.brooch)) {
                    runQuestSection(npc, dialogue, "Showing the brooch to the Duke", speakers = speakers)
                    setState(BROOCH_SHOWN)
                } else {
                    runQuestSection(npc, dialogue, "Talking to the Duke after opening the tunnel")
                }
            BOOK -> {
                runQuestSection(npc, dialogue, "Talking to the Duke", speakers = speakers)
                setState(SYMBOL)
            }
            SYMBOL, GENERALS -> runQuestSection(npc, dialogue, "Speaking to Duke Horacio after the Goblin generals")
            MET ->
                when {
                    (flags() and INFORMED) == 0 -> {
                        runQuestSection(npc, dialogue, "Informing Duke Horacio/Sigmund", speakers = speakers)
                        setFlags(flags() or INFORMED)
                    }
                    // Castle Sigmund leaves for the H.A.M. hideout at 10 (cache multis).
                    carries(LtObjs.robes) -> {
                        runQuestSection(npc, dialogue, "Telling the Duke about Sigmund's H.A.M.-loving ways", speakers = speakers)
                        setState(SILVERWARE)
                    }
                    else -> runQuestSection(npc, dialogue, "Talking to the Duke again (2)")
                }
            SILVERWARE ->
                when {
                    carries(LtObjs.silverware) -> {
                        val hooks =
                            SimpleQuestHooks(
                                boxes =
                                    mapOf(
                                        "The Duke writes a document and signs it." to {
                                            give(LtObjs.treaty)
                                            false
                                        }
                                    )
                            )
                        runQuestSection(npc, dialogue, "Returning the silverware", hooks, speakers)
                        invDel(inv, LtObjs.silverware)
                        give(LtObjs.treaty)
                    }
                    else -> runQuestSection(npc, dialogue, "Talking to the Duke again (2)")
                }
            else -> runQuestSection(npc, dialogue, "Talking to Duke Horacio again")
        }
    }

    /* The witness */

    private suspend fun ProtectedAccess.askWitness(npc: Npc, index: Int) {
        val name = LtNpcs.witnesses[index].first
        if (index != witness()) {
            runQuestSection(npc, dialogue, ASK_SECTIONS.getValue(name))
            return
        }
        if ((flags() and WITNESSED) != 0) {
            runQuestSection(npc, dialogue, "Talking to them again")
            return
        }
        val nodes = witnessNodes()
        startDialogue(npc) { QuestDialogueRunner(nameHooks()).run(this, nodes) }
        setFlags(flags() or WITNESSED)
    }

    /** The witness's account: the "Do you know..." option whose lines are spoken by [NPC]. */
    private fun witnessNodes(): List<QuestDialogue.Node> {
        val options = dialogue.section("Asking Donie or Gee").filterIsInstance<QuestDialogue.Node.Option>()
        val account =
            options.firstOrNull { option ->
                option.children.any { it is QuestDialogue.Node.Line && it.speaker == "NPC" }
            }
        return account?.children.orEmpty()
    }

    /* The cellar, the book and Mistag */

    private suspend fun ProtectedAccess.clearRubble() {
        if (state != CONVINCED) return
        runQuestSection(null, dialogue, "Opening the tunnel")
        // Authored line (not Wiki): the brooch is found in the cleared rubble.
        mes("You clear the rubble, revealing a tunnel. Something glints among the stones: a brooch.")
        give(LtObjs.brooch)
        setState(TUNNEL)
    }

    private suspend fun ProtectedAccess.squeezeCellar(hole: CoordGrid) {
        telejump(if (player.coords.x < hole.x) TUNNEL_SIDE else CELLAR_SIDE)
    }

    private suspend fun ProtectedAccess.searchBookcase() {
        if (state != BROOCH_SHOWN) {
            mes("You search the bookcase but find nothing of interest.")
            return
        }
        runQuestSection(null, dialogue, "A History of the Goblin Race")
        give(LtObjs.book)
        setState(BOOK)
    }

    private suspend fun ProtectedAccess.talkMistag(npc: Npc) {
        when (state) {
            GENERALS -> {
                runQuestSection(npc, dialogue, "Greetings", speakers = speakers)
                var bow = false
                startDialogue(npc) {
                    // Authored stand-in (not Wiki): OSRS checks which emote you perform.
                    bow = choice2("Perform the Goblin Bow.", true, "Perform the Goblin Salute.", false, title = "Greet Mistag")
                }
                val cond = if (bow) "If the player performs the Goblin Bow emote:" else "If the player performs the Goblin Salute emote:"
                val nodes =
                    dialogue.section("Greetings").filterIsInstance<QuestDialogue.Node.Cond>().firstOrNull { it.text == cond }?.children.orEmpty()
                startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes) }
                if (bow) setState(MET)
            }
            MET ->
                if ((flags() and INFORMED) != 0) {
                    runQuestSection(npc, dialogue, "Asking Mistag about the silverware")
                } else {
                    runQuestSection(npc, dialogue, "Talking to Mistag after first contact")
                }
            SILVERWARE ->
                if (carries(LtObjs.treaty)) {
                    runQuestSection(npc, dialogue, "Delivering the treaty", speakers = speakers)
                    runQuestSection(null, dialogue, "Cut scene: A meeting of leaders", speakers = speakers)
                    invDel(inv, LtObjs.treaty)
                    completeQuest(LOST_TRIBE)
                } else {
                    runQuestSection(npc, dialogue, "Talking to Mistag after first contact")
                }
        }
    }

    /* Sigmund's key, the chest and the H.A.M. hideout */

    private suspend fun ProtectedAccess.pickpocketSigmund() {
        if (state != MET || (flags() and INFORMED) == 0 || carries(LtObjs.key)) {
            mes("You find nothing of interest.")
            return
        }
        runQuestSection(null, dialogue, "Pickpocketing Sigmund's key")
        give(LtObjs.key)
    }

    private suspend fun ProtectedAccess.openChest() {
        val hasKey = carries(LtObjs.key)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the key:" to { hasKey },
                        "If the player doesn't have the key:" to { !hasKey },
                    )
            )
        runQuestSection(null, dialogue, "Opening Sigmund's chest", hooks)
        if (hasKey && state == MET && (flags() and INFORMED) != 0) give(LtObjs.robes)
    }

    private suspend fun ProtectedAccess.searchCrate() {
        if (state != SILVERWARE || carries(LtObjs.silverware)) {
            mes("The crate is empty.")
            return
        }
        runQuestSection(null, dialogue, "Searching the H.A.M. hideout")
        give(LtObjs.silverware)
    }

    companion object {
        const val STARTED = 1
        const val CONVINCED = 3
        const val TUNNEL = 4
        const val BROOCH_SHOWN = 5
        const val BOOK = 6
        const val SYMBOL = 7
        const val GENERALS = 8
        const val MET = 9
        const val SILVERWARE = 10
        const val COMPLETE = 11

        private const val WITNESS_MASK = 0x7
        private const val WITNESSED = 1 shl 3
        /** The Duke has told you about the missing silverware (still stage 9). */
        private const val INFORMED = 1 shl 4

        /** Either side of the cellar's hole (3220,9618). */
        private val CELLAR_SIDE = CoordGrid(3218, 9618, 0)
        private val TUNNEL_SIDE = CoordGrid(3222, 9618, 0)

        private val ASK_SECTIONS =
            mapOf(
                "The cook" to "Asking the Cook",
                "Hans" to "Asking Hans",
                "Bob" to "Asking Bob",
                "Father Aereck" to "Asking Father Aereck",
                "Donie" to "Asking Donie or Gee",
                "Gee" to "Asking Donie or Gee",
            )

        val LOST_TRIBE =
            QuestDef(
                name = "The Lost Tribe",
                varbit = { LtVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("Access to the Dorgesh-Kaan mine", "The Goblin Bow and Salute emotes"),
                scrollObj = { LtObjs.brooch },
                listRow = "quest_losttribe",
            )
    }
}

// Every cache ref The Lost Tribe uses (re-check after cache updates).

internal object LtNpcs : NpcReferences() {
    val sigmund = find("lost_tribe_sigmund_there")
    val sigmunds = listOf(sigmund, find("lost_tribe_sigmund_ham_there"))
    val duke = find("duke_of_lumbridge")
    val urtag = find("dorgesh_urtaq")
    val reldos = listOf(find("reldo_normal"), find("reldo_withbook"))
    val generals = listOf(find("general_wartface_green"), find("general_bentnoze_red"))
    val mistags =
        listOf(find("lost_tribe_mistag_1op"), find("lost_tribe_mistag_2ops"), find("lost_tribe_mistag_3ops"))

    /** Who may have seen the goblin (one, chosen per player): the Wiki's "Asking ..." sections. */
    val witnesses: List<Pair<String, NpcType>> =
        listOf(
            "The cook" to find("cook"),
            "Hans" to find("hans"),
            "Bob" to find("bob"),
            "Father Aereck" to find("father_aereck"),
            "Donie" to find("lumbridge_guide2_woman"),
            "Gee" to find("lumbridge_guide2_man"),
        )
}

internal object LtLocs : LocReferences() {
    val rubble = find("lost_tribe_cellar_hole_blocking")
    val cellar_hole = find("lost_tribe_cavewall_hole_walldecor")
    val bookcase = find("lost_tribe_bookcase")
    val chest = find("lost_tribe_chest")
    val crate = find("lost_tribe_crate")
}

internal object LtObjs : ObjReferences() {
    val brooch = find("lost_tribe_brooch")
    val book = find("lost_tribe_book")
    val key = find("lost_tribe_chest_key")
    val silverware = find("lost_tribe_silverware")
    val treaty = find("lost_tribe_treaty")
    val robes = find("ham_robe")
    val pickaxes =
        listOf(
            "bronze_pickaxe", "iron_pickaxe", "steel_pickaxe", "black_pickaxe", "mithril_pickaxe",
            "adamant_pickaxe", "rune_pickaxe", "dragon_pickaxe", "infernal_pickaxe", "crystal_pickaxe",
        ).map { find(it) }
}

internal object LtVarBits : VarBitReferences() {
    val quest = find("lost_tribe_quest")
}

internal object LtVarps : VarpReferences() {
    /** Server varp (65794): bits 0-2 the witness (1-6), bit 3 the witness heard, bit 4 informed. */
    val progress = find("lt_progress")
}

internal object LtVarpBuilds : VarpBuilder() {
    init {
        build("lt_progress")
    }
}
