package org.rsmod.content.quests.fairytale1

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.lostcity.LostCity.Companion.LOST_CITY
import org.rsmod.content.quests.naturespirit.NatureSpirit.Companion.NATURE_SPIRIT
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fairytale I - Growing Pains (OSRS Wiki). Cache quest varbit `fairy_farmers_quest`, end state 90
 * (the Gatekeeper multi shows only at multiples of 10, so the stages are 10..90):
 * - 10 Martin the Master Gardener (Draynor) asks you to question five members of the Group of
 *   Advanced Gardeners (any farming gardener but Ayesha); who you asked is a bit mask in the server
 *   varp `ft1_progress`. 20 Martin, told about fairies, sends you to Zanaris (the Fairy Queen leaves
 *   her throne for the Godfather: `fairy_queen_check` / `fairy_godfather_check` = 1).
 * - 30 the Fairy Godfather explains; 40 Fairy Nuff writes the symptoms list; 50 Zandar Horfyre
 *   (Dark Wizards' Tower) reads it and names Malignius Mortifer; 60 Malignius takes a Draynor skull
 *   (dug from the grave behind Draynor Manor) and names three consecutive items of the Wiki's
 *   30-item list (`fairy_nature_item1..3`); 70 the Nature Spirit turns secateurs and the three
 *   items into magic secateurs; 80 the Tanglefoot (spawned for you at the end of the shady grove
 *   beyond the gap west of the Cosmic Altar; only magic secateurs hurt it) drops the Queen's
 *   secateurs; 90 the Godfather takes them: 3,500 Farming, 2,000 Attack, 1,000 Magic xp, 2 QP.
 * - Authored lines (not Wiki) are marked; the Wiki leaves the gardeners' other options, Martin's
 *   skillcape chat and Malignius's small talk blank.
 *
 * Simplified: the Tanglefoot isn't instanced and its max hit doesn't use Farming.
 */
class FairytaleI
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(FairytaleI::class.java, "fairytale_1") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Gate Keeper" to FtNpcs.gatekeeper,
            "Gatekeeper" to FtNpcs.gatekeeper,
            "Fat Rocco" to FtNpcs.rocco,
        )
    }

    /** Each player's Tanglefoot, by player uid. */
    private val tanglefoots = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, FtNpcs.martin, FAIRYTALE_I, wants = { state < GODFATHER }) {
                talkMartin(it)
            }
            default(npcTypes, FtNpcs.martin) { talkMartinAfter(it) }
            for ((index, gardener) in FtNpcs.gardeners.withIndex()) {
                default(npcTypes, gardener) { talkGardener(it, index) }
            }
            register(npcTypes, FtNpcs.godfather, FAIRYTALE_I, wants = { state in GODFATHER until COMPLETE }) {
                talkGodfather(it)
            }
            register(npcTypes, FtNpcs.nuff, FAIRYTALE_I, wants = { wantsNuff() }) { talkNuff(it) }
            register(npcTypes, FtNpcs.zandar, FAIRYTALE_I, wants = { state == LIST }) { talkZandar(it) }
            register(npcTypes, FtNpcs.malignius, FAIRYTALE_I, wants = { state >= ZANDAR }) {
                talkMalignius(it)
            }
            register(npcTypes, FtNpcs.spirit, FAIRYTALE_I, wants = { wantsSpirit() }) { talkSpirit(it) }
            register(npcTypes, FtNpcs.gatekeeper, FAIRYTALE_I, wants = { state in STARTED until COMPLETE }) {
                runQuestSection(it, dialogue, "Shady grove", SimpleQuestHooks(), speakers)
            }
        }
        for (gap in FtLocs.gaps) onLocOpLabel(locTypes, gap, "Squeeze-through") { squeezeGap(it) }
        QuestDigSpots.register { digSkull() }
        onModifyNpcHit(FtNpcs.tanglefoot) { secateursOnly(hit) }
        onEvent<NpcKilledEvent> { tanglefootDown(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(FAIRYTALE_I)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(FAIRYTALE_I, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[FtVarps.progress]

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, FtVarps.progress, flags() or (1 shl bit))
    }

    private fun ProtectedAccess.gardenersAsked(): Int =
        Integer.bitCount(flags() and GARDENER_MASK)

    private fun ProtectedAccess.meetsRequirements(): Boolean =
        player.isQuestComplete(LOST_CITY) && player.isQuestComplete(NATURE_SPIRIT)

    /* Martin and the gardeners */

    private suspend fun ProtectedAccess.talkMartin(npc: Npc) {
        when {
            state == 0 && !meetsRequirements() ->
                startDialogue(npc) {
                    // Authored line (not Wiki): the quest's requirements aren't met.
                    chatNpc(
                        neutral,
                        "I can't chat now, I have too many things to worry about. Perhaps once " +
                            "you've seen a bit more of the world, the Lost City and the swamps of " +
                            "Morytania.",
                    )
                }
            state == 0 -> runQuestSection(npc, dialogue, "Starting the quest", martinHooks())
            gardenersAsked() >= GARDENERS_NEEDED -> {
                runQuestSection(npc, dialogue, "Returning to Martin", martinHooks())
                VarPlayerIntMapSetter.set(player, FtVarBits.queen_check, 1)
                VarPlayerIntMapSetter.set(player, FtVarBits.godfather_check, 1)
                setState(GODFATHER)
            }
            gardenersAsked() == 0 -> runQuestSection(npc, dialogue, "Talking to Martin again")
            else -> {
                val n = gardenersAsked()
                val section = if (n == 1) "Reporting to Martin" else "Reporting to Martin ($n)"
                runQuestSection(npc, dialogue, section)
            }
        }
    }

    private fun ProtectedAccess.martinHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has pickpocketed Martin before:" to { false },
                    "If the player hasn't pickpocketed Martin before:" to { true },
                    "If the player has trained some Farming:" to { trainedFarming() },
                    "If the player hasn't trained their Farming:" to { !trainedFarming() },
                ),
            options =
                mapOf(
                    "Yes." to { if (state == 0) setState(STARTED) },
                    "Ask about the Skillcape of Farming." to { },
                ),
            boxes = mapOf(REQUIREMENT_BOX to { true }),
        )

    private fun ProtectedAccess.trainedFarming(): Boolean = statBase(stats.farming) > 1

    private suspend fun ProtectedAccess.talkMartinAfter(npc: Npc) {
        startDialogue(npc) {
            // Authored line (not Wiki): Martin after the quest's Draynor part.
            chatNpc(
                neutral,
                "Hello again, ${player.displayName}. My roses are looking healthier already. " +
                    "Thank you for looking into our problem.",
            )
        }
    }

    private suspend fun ProtectedAccess.talkGardener(npc: Npc, index: Int) {
        val asked = (flags() and (1 shl index)) != 0
        val section =
            when {
                state != STARTED -> "Talking to the gardeners"
                asked -> "If the player has already talked to them"
                gardenersAsked() >= GARDENERS_NEEDED ->
                    "Talking to other gardeners after having already asked five"
                else -> "Gardener ${gardenersAsked() + 1}"
            }
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        MEMBER_OPTION to {
                            if (section.startsWith("Gardener ")) setFlag(index)
                        },
                    ),
            )
        startDialogue(npc) {
            val runner = QuestDialogueRunner(gardenerHooks(hooks), speakers)
            runner.run(this, dialogue.section(section))
        }
    }

    /** The Wiki leaves the gardeners' everyday options blank; these replies are authored. */
    private fun gardenerHooks(quest: SimpleQuestHooks) =
        object : org.rsmod.content.quests.framework.QuestDialogueHooks by quest {
            override suspend fun Dialogue.onOption(label: String) {
                with(quest) { onOption(label) }
                when (label) {
                    "Would you look after my crops for me?" ->
                        chatNpc(neutral, "Plant something in my patches first, then use Pay on me.")
                    "Can you give me any farming advice?" ->
                        chatNpc(
                            neutral,
                            "Rake the weeds, add some compost and water your seedlings. Healthy " +
                                "plants are far less likely to catch a disease.",
                        )
                    "Can you sell me something?" ->
                        chatNpc(neutral, "I can sell you a few things; use Trade on me.")
                    "I'll come back another time." -> chatPlayer(neutral, "I'll come back another time.")
                }
            }
        }

    /* Zanaris */

    private suspend fun ProtectedAccess.talkGodfather(npc: Npc) {
        when {
            state == GODFATHER ->
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to the Fairy Godfather",
                    SimpleQuestHooks(options = mapOf("Where's the Fairy Queen?" to { setState(NUFF) })),
                    speakers,
                )
            state == KILLED && carries(FtObjs.queen_secateurs) -> finish(npc)
            else -> runQuestSection(npc, dialogue, "Trying to talk to the Fairy Godfather again", speakers = speakers)
        }
    }

    private fun ProtectedAccess.wantsNuff(): Boolean =
        state == NUFF || (state in LIST until ZANDAR && !carries(FtObjs.symptoms)) ||
            (state == KILLED && carries(FtObjs.queen_secateurs))

    private suspend fun ProtectedAccess.talkNuff(npc: Npc) {
        when (state) {
            NUFF -> {
                val hooks = SimpleQuestHooks(actions = mapOf("" to { giveList() }))
                runQuestSection(npc, dialogue, "Talking to Fairy Nuff", hooks)
                if (!carries(FtObjs.symptoms)) giveList()
                if (state == NUFF) setState(LIST)
            }
            KILLED -> runQuestSection(npc, dialogue, "Returning to Fairy Nuff first")
            else -> {
                runQuestSection(npc, dialogue, "Talking to Fairy Nuff again")
                giveList()
            }
        }
    }

    private fun ProtectedAccess.giveList() {
        if (!carries(FtObjs.symptoms)) invAddOrDrop(objRepo, FtObjs.symptoms)
    }

    private suspend fun ProtectedAccess.talkZandar(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the symptoms list:" to { carries(FtObjs.symptoms) },
                        "If the player doesn't have the symptoms list:" to { !carries(FtObjs.symptoms) },
                    ),
                actions =
                    mapOf(
                        "The symptoms list is taken." to {
                            invDel(inv, FtObjs.symptoms)
                            setState(ZANDAR)
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Zandar Horfyre", hooks)
    }

    /* Malignius, the skull and the ceremony items */

    private suspend fun ProtectedAccess.talkMalignius(npc: Npc) {
        val hasSkull = carries(FtObjs.skull)
        var takeSkull = false
        var tellRest = false
        val hooks =
            object : org.rsmod.content.quests.framework.QuestDialogueHooks {
                override fun condition(text: String): Boolean =
                    when (text) {
                        "If the player already has the skull in their inventory:",
                        "If the player has the skull:" -> hasSkull.also { takeSkull = it }
                        "If the player does not have the skull in their inventory:",
                        "If the player does not have the skull:" -> !hasSkull
                        else -> false
                    }

                override suspend fun Dialogue.onAction(tag: String) {
                    if (tag == "The rest is as above.") tellRest = true
                }

                override suspend fun Dialogue.onOption(label: String) {
                    when (label) {
                        // Authored lines (not Wiki): the Wiki leaves these options blank.
                        "Who are you and what are you doing here?" -> {
                            chatPlayer(neutral, label)
                            chatNpc(
                                neutral,
                                "I am Malignius Mortifer, master of the dark arts. My followers " +
                                    "and I study the line between life and death. Undisturbed, " +
                                    "usually.",
                            )
                        }
                        "Where can I get clothes like those?" -> {
                            chatPlayer(neutral, label)
                            chatNpc(neutral, "These robes are earned, not bought. Run along.")
                        }
                    }
                }

                override fun rewrite(text: String): String = itemsRewrite(text)
            }
        val section =
            when {
                state >= ITEMS -> "Talking to Malignius again after learning how to kill the Tanglefoot"
                (flags() and (1 shl ASKED_MALIGNIUS)) != 0 -> "Talking to Malignius again before getting the skull"
                else -> "Talking to Malignius Mortifer"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (state == ZANDAR) setFlag(ASKED_MALIGNIUS)
        if (state == ZANDAR && takeSkull && carries(FtObjs.skull)) {
            invDel(inv, FtObjs.skull)
            pickItems()
            setState(ITEMS)
            if (tellRest) {
                // "The rest is as above.": the first conversation's lines after the skull is given.
                val rest = skullBranch().drop(2)
                startDialogue(npc) { QuestDialogueRunner(hooks).run(this, rest) }
            }
        }
    }

    private fun skullBranch(): List<QuestDialogue.Node> {
        val option =
            dialogue.section("Talking to Malignius Mortifer").filterIsInstance<QuestDialogue.Node.Option>()
                .first { it.label == "I need help with fighting a Tanglefoot." }
        return option.children.filterIsInstance<QuestDialogue.Node.Cond>()
            .first { it.text == "If the player already has the skull in their inventory:" }
            .children
    }

    /** Three consecutive entries of the Wiki's list (1-30), kept in `fairy_nature_item1..3`. */
    private fun ProtectedAccess.pickItems() {
        val first = random.of(1, FtObjs.ceremony.size - 2)
        VarPlayerIntMapSetter.set(player, FtVarBits.item1, first)
        VarPlayerIntMapSetter.set(player, FtVarBits.item2, first + 1)
        VarPlayerIntMapSetter.set(player, FtVarBits.item3, first + 2)
    }

    private fun ProtectedAccess.ceremonyItems(): List<Int> =
        listOf(FtVarBits.item1, FtVarBits.item2, FtVarBits.item3)
            .map { player.vars[it] }
            .filter { it in 1..FtObjs.ceremony.size }

    private fun ProtectedAccess.itemsRewrite(text: String): String {
        val items = ceremonyItems()
        if (items.size < 3) return text
        val names = items.map { CEREMONY_NAMES[it - 1] }
        return text
            .replace("[item 1]", names[0])
            .replace("[item 2]", names[1])
            .replace(
                Regex("\\[item 3\\]\\..*$"),
                names[2] + ".",
            )
            .replace("[item 3]", names[2])
    }

    private suspend fun ProtectedAccess.digSkull(): Boolean {
        if (player.coords.chebyshevDistance(GRAVE) > 1) return false
        if (carries(FtObjs.skull)) {
            mes("You dig but find nothing more.")
            return true
        }
        invAddOrDrop(objRepo, FtObjs.skull)
        mesbox("You dig in the grave and find a skull.")
        return true
    }

    /* The Nature Spirit's ceremony */

    private fun ProtectedAccess.hasMagicSecateurs(): Boolean =
        carries(FtObjs.magic_secateurs) || invTotal(worn, FtObjs.magic_secateurs) > 0

    private fun ProtectedAccess.wantsSpirit(): Boolean =
        state == ITEMS || (state >= SECATEURS && !hasMagicSecateurs())

    private fun ProtectedAccess.hasCeremonyItems(): Boolean {
        val items = ceremonyItems()
        return items.size == 3 && carries(FtObjs.secateurs) &&
            items.all { carries(FtObjs.ceremony[it - 1]) }
    }

    private suspend fun ProtectedAccess.talkSpirit(npc: Npc) {
        val ghostspeak =
            invTotal(worn, FtObjs.ghostspeak) > 0 || invTotal(worn, FtObjs.ghostspeak_e) > 0
        if (!ghostspeak) {
            runQuestSection(npc, dialogue, "Without a ghostspeak amulet")
            return
        }
        val ready = hasCeremonyItems()
        if (state == ITEMS) {
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player has the required items:" to { ready },
                            "If the player does not have the required items:" to { !ready },
                        )
                )
            runQuestSection(npc, dialogue, "Talking to the Nature Spirit", hooks)
        } else {
            runQuestSection(npc, dialogue, "Talking to the Nature Spirit after losing the secateurs")
            startDialogue(npc) {
                if (ready) {
                    chatPlayer(neutral, "Yes, I have everything right here.")
                    chatNpc(neutral, "Then let us begin...")
                    chatNpc(neutral, "It is done!")
                } else {
                    chatPlayer(neutral, "No, I still need to collect some things.")
                    chatNpc(
                        neutral,
                        "Alright, my friend. Return to me when you have all that is required. I " +
                            "shall be waiting.",
                    )
                }
            }
        }
        if (!ready || !hasCeremonyItems()) return
        invDel(inv, FtObjs.secateurs)
        for (item in ceremonyItems()) invDel(inv, FtObjs.ceremony[item - 1])
        invAddOrDrop(objRepo, FtObjs.magic_secateurs)
        mes("Your secateurs glow green: they are now magic secateurs.")
        if (state == ITEMS) setState(SECATEURS)
    }

    /* The shady grove and the Tanglefoot */

    private suspend fun ProtectedAccess.squeezeGap(gap: BoundLocInfo) {
        val inside = player.coords.x < gap.coords.x
        if (inside) {
            telejump(GROVE_OUTSIDE)
            return
        }
        if (state < SECATEURS) {
            // Authored line (not Wiki): too early for the grove.
            mes("The Gatekeeper won't let you through without a way to harm the Tanglefoot.")
            return
        }
        val lostSecateurs = state >= KILLED && state < COMPLETE && !carries(FtObjs.queen_secateurs)
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the Queen's secateurs have been lost:" to { lostSecateurs }),
                boxes =
                    mapOf(
                        "You find the Fairy Queen's secateurs!" to {
                            if (lostSecateurs) invAddOrDrop(objRepo, FtObjs.queen_secateurs)
                            false
                        }
                    ),
            )
        val section =
            if (state >= KILLED) "Re-entering the shady grove after obtaining the Queen's secateurs"
            else "Entering the shady grove"
        runQuestSection(null, dialogue, section, hooks, speakers)
        telejump(GROVE_INSIDE)
        if (state == SECATEURS) summonTanglefoot()
    }

    private fun ProtectedAccess.summonTanglefoot() {
        val current = tanglefoots[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        val tanglefoot = Npc(npcTypes[FtNpcs.tanglefoot], TANGLEFOOT_SPOT)
        npcRepo.add(tanglefoot, TANGLEFOOT_TICKS)
        tanglefoots[player.uid.packed] = tanglefoot
        tanglefoot.opPlayer2(player, aiInteractions)
    }

    /** Only magic secateurs wielded hurt the Tanglefoot (OSRS Wiki "Tanglefoot"). */
    private fun secateursOnly(hit: HitBuilder) {
        if (hit.isFromPlayer && !hit.isRighthandObj(FtObjs.magic_secateurs)) {
            hit.damage = 0
        }
    }

    private fun tanglefootDown(event: NpcKilledEvent) {
        if (event.npc.type.id != FtNpcs.tanglefoot.id) return
        val player = event.hero
        tanglefoots.remove(player.uid.packed)
        if (player.questState(FAIRYTALE_I) != SECATEURS) return
        player.setQuestState(FAIRYTALE_I, KILLED)
        val drop = Obj.fromOwner(player, event.npc.coords, FtObjs.queen_secateurs, 1)
        objRepo.add(drop, constants.lootdrop_duration)
    }

    private suspend fun ProtectedAccess.finish(npc: Npc) {
        runQuestSection(npc, dialogue, "Returning to the Fairy Godfather")
        invDel(inv, FtObjs.queen_secateurs)
        statAdvance(stats.farming, 3500.0 * xpMods.get(player, stats.farming))
        statAdvance(stats.attack, 2000.0 * xpMods.get(player, stats.attack))
        statAdvance(stats.magic, 1000.0 * xpMods.get(player, stats.magic))
        completeQuest(FAIRYTALE_I)
    }

    companion object {
        const val STARTED = 10
        const val GODFATHER = 20
        const val NUFF = 30
        const val LIST = 40
        const val ZANDAR = 50
        const val ITEMS = 60
        const val SECATEURS = 70
        const val KILLED = 80
        const val COMPLETE = 90

        private const val GARDENERS_NEEDED = 5
        private const val GARDENER_MASK = 0x3FFFFFF
        private const val ASKED_MALIGNIUS = 27
        private const val TANGLEFOOT_TICKS = 500
        private const val MEMBER_OPTION = "Are you a member of the Group of Advanced Gardeners?"
        private const val REQUIREMENT_BOX =
            "Before starting this quest, be aware that your combat level is lower than the " +
                "recommended level of 50. One or more of your skill levels are also lower than " +
                "recommended."

        /** The grave behind Draynor Manor (fairy_draynor_gravestone 3106,3384; mound 3106,3382). */
        private val GRAVE = CoordGrid(3106, 3382, 0)
        /** Either side of the gap west of the Cosmic Altar, and the shady grove's far chamber. */
        private val GROVE_OUTSIDE = CoordGrid(2401, 4380, 0)
        private val GROVE_INSIDE = CoordGrid(2396, 4379, 0)
        private val TANGLEFOOT_SPOT = CoordGrid(2375, 4392, 0)

        /** Wiki "Blessed secateurs" list, in order (the dialogue's [item N] names). */
        private val CEREMONY_NAMES =
            listOf(
                "some white berries",
                "a Mort Myre pear",
                "a Mort Myre stem",
                "a Mort Myre fungus",
                "a nature talisman",
                "a clean avantoe",
                "a clean irit leaf",
                "a blue dragon scale",
                "a proboscis",
                "some jangerberries",
                "a potato cactus",
                "some crushed gem",
                "a clean snapdragon",
                "a bucket of supercompost",
                "some volencia moss",
                "some babydragon bones",
                "an uncut diamond",
                "a raw cave eel",
                "some edible seaweed",
                "an oyster",
                "some charcoal",
                "a red vine worm",
                "a fat snail",
                "some red spiders' eggs",
                "a raw slimy eel",
                "some grapes",
                "an uncut ruby",
                "some Jogre bones",
                "a king worm",
                "some snape grass",
            )

        val FAIRYTALE_I =
            QuestDef(
                name = "Fairytale I - Growing Pains",
                varbit = { FtVarBits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "Magic secateurs",
                        "3,500 Farming XP",
                        "2,000 Attack XP",
                        "1,000 Magic XP",
                    ),
                scrollObj = { FtObjs.magic_secateurs },
                listRow = "quest_fairytale1",
            )
    }
}

// Every cache ref Fairytale I uses (re-check after cache updates).

internal object FtNpcs : NpcReferences() {
    val martin = find("martin_the_master_farmer")
    val godfather = find("fairy_godfather")
    val rocco = find("fairy_henchman2")
    val nuff = find("fairy_nuff")
    val zandar = find("zandar_horfyre")
    val malignius = find("elemental_wizard_boss")
    val spirit = find("filliman_tarlock_ns")
    val gatekeeper = find("fairy_mindslayer_guard")
    val tanglefoot = find("fairy_tanglefoot")

    /** Group of Advanced Gardeners: every farming gardener (2663-2689) but the combat dummy. */
    val gardeners =
        listOf(
            "elstan",
            "dantaera",
            "kragen",
            "lyra",
            "francis",
            "garth",
            "farming_gardener_fruit_4",
            "farming_gardener_hops_1",
            "farming_gardener_hops_3",
            "farming_gardener_hops_4",
            "farming_gardener_bush_1",
            "farming_gardener_bush_2",
            "farming_gardener_bush_3",
            "farming_gardener_bush_4",
            "farming_gardener_tree_1",
            "farming_gardener_tree_2",
            "farming_gardener_tree_3",
            "farming_gardener_tree_4",
            "farming_gardener_fruit_1",
            "farming_gardener_fruit_2",
            "farming_gardener_spirit_tree_1",
            "farming_gardener_spirit_tree_2",
            "farming_gardener_spirit_tree_3",
            "farming_gardener_tree_gnome",
            "farming_gardener_calquat",
            "farming_gardener_fruit_tree_5",
        ).map { find(it) }
}

internal object FtLocs : LocReferences() {
    val gaps = listOf(find("fairy_wall_tangle_door_open"), find("fairy_wall_tangle_door_open1"))
}

internal object FtObjs : ObjReferences() {
    val symptoms = find("fairy_symptoms_list")
    val skull = find("fairy_skull")
    val secateurs = find("secateurs")
    val magic_secateurs = find("fairy_enchanted_secateurs")
    val queen_secateurs = find("fairy_queen_secateurs")
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_e = find("amulet_of_ghostspeak_enchanted")

    /** Wiki "Blessed secateurs" list, in order (1-30). */
    val ceremony: List<ObjType> =
        listOf(
            "white_berries",
            "mortmyrepear",
            "mortmyrebuddingstem",
            "mortmyremushroom",
            "nature_talisman",
            "avantoe",
            "irit_leaf",
            "blue_dragon_scale",
            "mosquito_proboscis",
            "jangerberries",
            "cactus_potato",
            "crushed_gemstone",
            "snapdragon",
            "bucket_supercompost",
            "volencia_moss",
            "babydragon_bones",
            "uncut_diamond",
            "raw_cave_eel",
            "edible_seaweed",
            "oystershell",
            "charcoal",
            "red_vine_worm",
            "snail_corpse3",
            "red_spiders_eggs",
            "mort_slimey_eel",
            "grapes",
            "uncut_ruby",
            "tbwt_jogre_bones",
            "king_worm",
            "snape_grass",
        ).map { find(it) }
}

internal object FtVarBits : VarBitReferences() {
    val quest = find("fairy_farmers_quest")
    val item1 = find("fairy_nature_item1")
    val item2 = find("fairy_nature_item2")
    val item3 = find("fairy_nature_item3")
    val queen_check = find("fairy_queen_check")
    val godfather_check = find("fairy_godfather_check")
}

internal object FtVarps : VarpReferences() {
    /** Server varp (65788): bits 0-25 gardeners asked (FtNpcs.gardeners order), 27 asked Malignius. */
    val progress = find("ft1_progress")
}

internal object FtVarpBuilds : VarpBuilder() {
    init {
        build("ft1_progress")
    }
}
