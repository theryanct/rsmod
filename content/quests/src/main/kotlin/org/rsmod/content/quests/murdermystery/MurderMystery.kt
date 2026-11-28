package org.rsmod.content.quests.murdermystery

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
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
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Murder Mystery (OSRS Wiki). `murderquest` varp (cache db end state 2): 1 investigating, 2 solved
 * (2,000 coins, 1,406 Crafting xp, 3 QP). The culprit is one of the six Sinclair children, rolled
 * per player when the guard takes you on.
 *
 * Server varp `murder_progress` (65781): bits 0-2 the culprit (1-6: Anna, Bob, Carol, David,
 * Elizabeth, Frank), bit 3 the poison salesman's story, bit 4 the culprit's poison lie exposed,
 * bit 5 the window thread taken, bit 6 the killer's print matched.
 *
 * The three proofs the guard wants: the window thread (its colour is the culprit's clothes: red for
 * Bob and Carol, green for Anna and David, blue for Elizabeth and Frank), the place the culprit
 * claims they poisoned left untouched, and their print (flour and flypaper on their silver item)
 * matching the unknown print lifted from the criminal's dagger.
 */
class MurderMystery
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MurderMystery::class.java, "murder_mystery") }

    private val fixes =
        SimpleQuestHooks(
            rewrites = {
                it.replace("womenno ellipsis", "women...")
                    .replace(Regex("""Note: With only one piece of evidence.*$"""), "")
                    .replace("missing punctuation}}", "You show David the thread.")
            }
        )

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, MmNpcs.guard, "Talk-to") { talkGuard(it) }
        onNpcOpLabel(npcTypes, MmNpcs.gossip, "Talk-to") { talkGossip(it) }
        // Talk-to is shared through QuestTalk (The Fremennik Trials sells his low alcohol keg).
        with(QuestTalk) {
            register(npcTypes, MmNpcs.salesman, MURDER_MYSTERY, wants = { state in STARTED until COMPLETE }) {
                talkSalesman(it)
            }
            default(npcTypes, MmNpcs.salesman) { talkSalesman(it) }
        }
        for ((index, member) in MmNpcs.family.withIndex()) {
            onNpcOpLabel(npcTypes, member, "Talk-to") { talkFamily(it, index) }
        }
        for ((index, servant) in MmNpcs.servants.withIndex()) {
            onNpcOpLabel(npcTypes, servant, "Talk-to") { talkServant(it, index) }
        }
        for (window in MmLocs.windows) {
            onLocOpLabel(locTypes, window, "Investigate") { investigateWindow() }
        }
        for ((index, barrel) in MmLocs.barrels.withIndex()) {
            onLocOpLabel(locTypes, barrel, "Search") { searchBarrel(index) }
        }
        for ((index, spot) in MmLocs.poisoned.withIndex()) {
            onLocOpLabel(locTypes, spot, "Investigate") { investigatePoisoned(index) }
        }
        onLocOpLabel(locTypes, MmLocs.sacks, "Investigate") { searchSacks() }
        for (gate in MmLocs.dog_gates) {
            onLocOpLabel(locTypes, gate, "Investigate") {
                runQuestSection(null, dialogue, "Investigate sturdy wooden gate")
            }
        }
        onLocOpLabel(locTypes, MmLocs.flour_barrel, "Take-from") { takeFlour() }
        onOpLocU(MmLocs.flour_barrel, MmObjs.pot) { takeFlour() }
        for (evidence in listOf(MmObjs.pungent_pot, MmObjs.silver[FRANK])) {
            onOpLocU(MmLocs.flour_barrel, evidence) {
                runQuestSection(null, dialogue, "Using silver pot or pungent pot on flour barrel")
            }
        }

        // Flour on the dagger and the silver items, flypaper on the dusted ones.
        onOpHeldU(MmObjs.pot_flour, MmObjs.dagger) { dust(MmObjs.dagger, MmObjs.dagger_dusted) }
        onOpHeldU(MmObjs.paper, MmObjs.dagger_dusted) {
            lift(MmObjs.unknown_print)
        }
        for (i in 0 until SUSPECTS) {
            onOpHeldU(MmObjs.pot_flour, MmObjs.silver[i]) {
                dust(MmObjs.silver[i], MmObjs.silver_dusted[i])
            }
            onOpHeldU(MmObjs.paper, MmObjs.silver_dusted[i]) {
                lift(MmObjs.prints[i])
            }
            onOpHeldU(MmObjs.prints[i], MmObjs.unknown_print) { comparePrints(i) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(MURDER_MYSTERY)

    private val ProtectedAccess.progress: Int
        get() = player.vars[MmVarps.progress]

    private fun ProtectedAccess.flag(bit: Int): Boolean = progress and (1 shl bit) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, MmVarps.progress, progress or (1 shl bit))
    }

    /** The culprit (0-5), or -1 before the guard takes you on. */
    private val ProtectedAccess.culprit: Int
        get() = (progress and CULPRIT_MASK) - 1

    private fun ProtectedAccess.culpritHooks(extra: SimpleQuestHooks? = null): SimpleQuestHooks {
        val c = culprit
        val conds = HashMap<String, () -> Boolean>()
        for ((i, name) in NAMES.withIndex()) {
            conds["If $name is the murderer:"] = { c == i }
            conds["If $name is innocent:"] = { c != i }
        }
        val colour = THREAD_COLOUR.getOrElse(c) { -1 }
        conds["If the criminal's thread is green:"] = { colour == GREEN }
        conds["If the criminal's thread is not green:"] = { colour != GREEN }
        conds["If the criminal's thread is red:"] = { colour == RED }
        conds["If the criminal's thread isn't red:"] = { colour != RED }
        return SimpleQuestHooks(
            conditions = conds,
            rewrites = { fixes.rewrite(it) },
        )
    }

    /* The guard and the case */

    private suspend fun ProtectedAccess.talkGuard(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "Talking to Guard", hooks)
                if (!start) return
                VarPlayerIntMapSetter.set(player, MmVarps.progress, random.of(1, SUSPECTS))
                player.setQuestState(MURDER_MYSTERY, STARTED)
            }
            STARTED -> {
                val thread = carries(MmObjs.threads[THREAD_COLOUR[culprit]])
                val lie = flag(LIE_FOUND)
                val print = carries(MmObjs.killers_print)
                if (thread && lie && print) {
                    var accused = false
                    val base = culpritHooks()
                    val hooks =
                        SimpleQuestHooks(
                            conditions = mapOf(),
                            defaultCondition = { base.condition(it) },
                            options = mapOf("I know who did it!" to { accused = true }),
                            rewrites = { base.rewrite(it) },
                        )
                    runQuestSection(npc, dialogue, ALL_EVIDENCE, hooks)
                    if (accused) solve()
                    return
                }
                val section =
                    if (thread || lie || print) {
                        "Talking to the Guard with some but not all evidence"
                    } else {
                        "Talking to the Guard after starting the quest"
                    }
                runQuestSection(npc, dialogue, section, culpritHooks())
            }
            else -> runQuestSection(npc, dialogue, "Talking to the Guard after the quest")
        }
    }

    private fun ProtectedAccess.solve() {
        for (thread in MmObjs.threads) invDel(inv, thread, invTotal(inv, thread))
        invDel(inv, MmObjs.killers_print)
        invAddOrDrop(objRepo, MmObjs.coins, REWARD_COINS)
        statAdvance(stats.crafting, REWARD_XP * xpMods.get(player, stats.crafting))
        completeQuest(MURDER_MYSTERY)
    }

    /* Questioning */

    private suspend fun ProtectedAccess.talkFamily(npc: Npc, index: Int) {
        val name = NAMES[index]
        val section =
            when {
                state == 0 ->
                    if (index in WOMEN) {
                        "Talking to Anna, Carol, Mary, or Elizabeth"
                    } else {
                        "Talking to Frank, David, or Bob"
                    }
                state == COMPLETE -> "Talking to $name after the quest"
                flag(SALESMAN) -> "Talking to $name after learning about the poison"
                else -> "Talking to $name"
            }
        var earlier = false
        val base = culpritHooks()
        val hooks =
            SimpleQuestHooks(
                defaultCondition = { base.condition(it) },
                options =
                    mapOf(
                        "Who do you think was responsible?" to { earlier = true },
                        "Where were you when the murder happened?" to { earlier = true },
                        "Do you recognise this thread?" to { earlier = true },
                    ),
                rewrites = { base.rewrite(it) },
            )
        runQuestSection(npc, dialogue, section, hooks)
        // The after-poison menu's first three answers are the earlier conversation's.
        if (earlier && section.endsWith("after learning about the poison")) {
            runQuestSection(npc, dialogue, "Talking to $name", base)
        }
    }

    private suspend fun ProtectedAccess.talkServant(npc: Npc, index: Int) {
        val name = SERVANT_NAMES[index]
        val section =
            when {
                state == 0 ->
                    if (name == "Mary") {
                        "Talking to Anna, Carol, Mary, or Elizabeth"
                    } else {
                        "Talking to $name"
                    }
                state == COMPLETE && name != "Pierre" -> "Talking to $name after the quest"
                state == STARTED && flag(SALESMAN) ->
                    "Talking to $name after learning about the poison"
                name == "Mary" -> "Talking to Mary"
                else -> "Talking to $name (2)"
            }
        var earlier = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Who do you think is responsible?" to { earlier = true },
                        "Where were you at the time of the murder?" to { earlier = true },
                        "Did you hear any suspicious noises at all?" to { earlier = true },
                    )
            )
        runQuestSection(npc, dialogue, section, hooks)
        val repeats = name != "Pierre" && name != "Stanford"
        if (earlier && repeats && section.endsWith("after learning about the poison")) {
            val first = if (name == "Mary") "Talking to Mary" else "Talking to $name (2)"
            runQuestSection(npc, dialogue, first)
        }
    }

    private suspend fun ProtectedAccess.talkGossip(npc: Npc) {
        if (state != STARTED) {
            startDialogue(npc) { chatNpc(neutral, "Heard the latest about the Sinclairs?") }
            return
        }
        runQuestSection(npc, dialogue, "Talking to Gossip")
    }

    private suspend fun ProtectedAccess.talkSalesman(npc: Npc) {
        if (state == COMPLETE) {
            runQuestSection(npc, dialogue, "Talking to the Poison Salesman after the quest")
            return
        }
        var told = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Who did you sell Poison to at the house?" to { told = true })
            )
        runQuestSection(npc, dialogue, "Talking to the Poison Salesman", hooks)
        if (told && state == STARTED) setFlag(SALESMAN)
    }

    /* Clues around the mansion */

    private suspend fun ProtectedAccess.investigateWindow() {
        if (state != STARTED) {
            runQuestSection(null, dialogue, "Investigating the smashed window")
            return
        }
        val thread = MmObjs.threads[THREAD_COLOUR[culprit]]
        val holding = carries(thread)
        if (!flag(THREAD_TAKEN)) {
            if (inv.isFull()) {
                mes("Some thread is caught on a loose nail, but you have no room to take it.")
                return
            }
            mes("Some thread seems to have been caught on a loose nail on the window.")
            invAdd(inv, thread)
            setFlag(THREAD_TAKEN)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player lost the thread:" to { !holding },
                        "If the player is still holding the thread:" to { holding },
                    )
            )
        runQuestSection(null, dialogue, "Investigating the smashed window again", hooks)
        if (!holding) invAddOrDrop(objRepo, thread)
    }

    private suspend fun ProtectedAccess.searchBarrel(index: Int) {
        val has = carries(MmObjs.silver[index]) || carries(MmObjs.silver_dusted[index])
        val item = SILVER_NAMES[index]
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player already has the $item:" to { has })
            )
        runQuestSection(null, dialogue, "${NAMES[index]}'s barrel", hooks)
        if (!has) invAddOrDrop(objRepo, MmObjs.silver[index])
    }

    private suspend fun ProtectedAccess.searchSacks() {
        var take = false
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes, it might be useful." to { take = true }))
        runQuestSection(null, dialogue, "Searching the sacks", hooks)
        if (take) invAddOrDrop(objRepo, MmObjs.paper)
    }

    private suspend fun ProtectedAccess.takeFlour() {
        val hasPot = carries(MmObjs.pot)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have an empty pot:" to { !hasPot },
                        "If the player has an empty pot:" to { hasPot },
                    )
            )
        runQuestSection(null, dialogue, "Taking from the flour barrel", hooks)
        if (!hasPot) {
            mes("You need an empty pot to hold the flour.")
            return
        }
        invDel(inv, MmObjs.pot)
        invAdd(inv, MmObjs.pot_flour)
        mes("You fill your pot with flour.")
    }

    private suspend fun ProtectedAccess.dust(item: ObjType, dusted: ObjType) {
        invDel(inv, MmObjs.pot_flour)
        invAdd(inv, MmObjs.pot)
        invDel(inv, item)
        invAdd(inv, dusted)
        mes("You sprinkle a small amount of flour on it. Some fingerprints appear.")
    }

    private suspend fun ProtectedAccess.lift(print: ObjType) {
        if (carries(print)) {
            mes("You already have that print.")
            return
        }
        invDel(inv, MmObjs.paper)
        invAdd(inv, print)
        mes("You use the flypaper to lift the fingerprint.")
    }

    private suspend fun ProtectedAccess.comparePrints(index: Int) {
        val section = "Using ${NAMES[index]}'s print on unknown print"
        runQuestSection(null, dialogue, section, culpritHooks())
        invDel(inv, MmObjs.prints[index])
        if (culprit != index) return
        invDel(inv, MmObjs.unknown_print)
        invAdd(inv, MmObjs.killers_print)
        mes("The prints match exactly. This must be the killer's print!")
        setFlag(PRINT_MATCHED)
    }

    private suspend fun ProtectedAccess.investigatePoisoned(index: Int) {
        if (state != STARTED || !flag(SALESMAN)) {
            mes("You notice nothing unusual about it.")
            return
        }
        runQuestSection(null, dialogue, POISONED_SECTIONS[index], culpritHooks())
        if (culprit == index) setFlag(LIE_FOUND)
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 2

        private const val SUSPECTS = 6
        private const val CULPRIT_MASK = 0b111
        private const val SALESMAN = 3
        private const val LIE_FOUND = 4
        private const val THREAD_TAKEN = 5
        private const val PRINT_MATCHED = 6

        private const val FRANK = 5
        private const val RED = 0
        private const val GREEN = 1
        private const val BLUE = 2

        private const val ALL_EVIDENCE = "Talking to the Guard with all evidence"
        private const val REWARD_COINS = 2000
        private const val REWARD_XP = 1406.0

        private val NAMES = listOf("Anna", "Bob", "Carol", "David", "Elizabeth", "Frank")
        private val WOMEN = setOf(0, 2, 4)
        private val SILVER_NAMES =
            listOf(
                "silver necklace",
                "silver cup",
                "silver bottle",
                "silver book",
                "silver needle",
                "silver pot",
            )

        /** The window thread's colour for each culprit (their clothes). */
        private val THREAD_COLOUR = listOf(GREEN, RED, RED, GREEN, BLUE, BLUE)
        private val SERVANT_NAMES =
            listOf("Donovan", "Pierre", "Hobbes", "Louisa", "Mary", "Stanford")
        private val POISONED_SECTIONS =
            listOf(
                "Investigating compost heap after questioning the Sinclairs",
                "Investigating beehive after questioning the Sinclairs",
                "Investigating kitchen drain after questioning the Sinclairs",
                "Investigating the spiders nest after questioning the Sinclairs",
                "Investigating fountain after questioning the Sinclairs",
                "Investigating family crest after questioning the Sinclairs",
            )

        val MURDER_MYSTERY =
            QuestDef(
                name = "Murder Mystery",
                varp = { MmVarps.murderquest },
                complete = COMPLETE,
                questPoints = 3,
                rewards = listOf("2,000 Coins", "1,406 Crafting XP"),
                scrollObj = { MmObjs.dagger },
            )
    }
}

// Every cache ref Murder Mystery uses (re-check after cache updates).

internal object MmNpcs : NpcReferences() {
    val guard = find("murderguard")
    val gossip = find("gossipy_man")
    val salesman = find("poison_salesman")

    /** Anna, Bob, Carol, David, Elizabeth, Frank (the culprit order). */
    val family =
        listOf(
            find("anna_sinclair"),
            find("bob_sinclair"),
            find("carol_sinclair"),
            find("david_sinclair"),
            find("elizabeth_sinclair"),
            find("frank_sinclair"),
        )

    /** Donovan, Pierre, Hobbes, Louisa, Mary, Stanford. */
    val servants =
        listOf(
            find("donovan_the_family_handyman"),
            find("pierre_the_family_dog_handler"),
            find("hobbes_the_butler"),
            find("louisa_the_cook"),
            find("mary_the_maid"),
            find("stanford_the_gardener"),
        )
}

internal object MmLocs : LocReferences() {
    /* Children of the kr_window multiloc (King's Ransom state). */
    val windows = listOf(find("kr_mansion_window_multi_01"), find("kr_mansion_window_multi_02"))

    /** Anna's .. Frank's barrels (culprit order). */
    val barrels =
        listOf(
            find("murderbarrela"),
            find("murderbarrelb"),
            find("murderbarrelc"),
            find("murderbarreld"),
            find("murderbarrele"),
            find("murderbarrelf"),
        )

    /** What each suspect claims to have poisoned (culprit order). */
    val poisoned =
        listOf(
            find("murdercompost"),
            find("murderhive"),
            find("murderdrain"),
            find("murderweb"),
            find("murderfountain"),
            find("murdersign"),
        )
    val sacks = find("murdersacks")
    val flour_barrel = find("flourbarrel")
    val dog_gates = listOf(find("murderdoggatel"), find("murderdoggater"))
}

internal object MmObjs : ObjReferences() {
    val dagger = find("murderweapon")
    val dagger_dusted = find("murderweapondust")
    val pungent_pot = find("murderpot2")
    val paper = find("murderpaper")
    val unknown_print = find("murderfingerprint1")
    val killers_print = find("murderfingerprint")
    val pot = find("pot_empty")
    val pot_flour = find("pot_flour")
    val coins = find("coins")

    /** Red, green, blue. */
    val threads = listOf(find("murderthreadr"), find("murderthreadg"), find("murderthreadb"))

    /** Anna's necklace .. Frank's pot (culprit order), plain and floured, and their prints. */
    val silver =
        listOf(
            find("murdernecklace"),
            find("murdercup"),
            find("murderbottle"),
            find("murderbook"),
            find("murderneedle"),
            find("murderpot"),
        )
    val silver_dusted =
        listOf(
            find("murdernecklacedust"),
            find("murdercupdust"),
            find("murderbottledust"),
            find("murderbookdust"),
            find("murderneedledust"),
            find("murderpotdust"),
        )
    val prints =
        listOf(
            find("murderfingerprinta"),
            find("murderfingerprintb"),
            find("murderfingerprintc"),
            find("murderfingerprintd"),
            find("murderfingerprinte"),
            find("murderfingerprintf"),
        )
}

internal object MmVarps : VarpReferences() {
    val murderquest = find("murderquest")

    /** Server varp (65781): culprit (bits 0-2) and the investigation flags. */
    val progress = find("murder_progress")
}

internal object MmVarpBuilds : VarpBuilder() {
    init {
        build("murder_progress")
    }
}
