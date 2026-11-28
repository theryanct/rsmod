package org.rsmod.content.quests.fremenniktrials

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.content.travel.ships.sailTo
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * The Fremennik Trials (OSRS Wiki). Cache quest varp `viking`: 1 started (Brundt), 10 complete
 * (dbrow endstate); the trials live in server varps 65955-65962 (see FtState.kt). Brundt asks for
 * seven of the twelve council votes; each trial is its own file:
 * - Manni (FtManni): lose a drinking contest, then swap your keg for the Poison Salesman's low
 *   alcohol keg while the council workman's strange object, lit in the longhall drain pipe, makes
 *   a bang.
 * - Olaf (FtOlaf): cut the swaying tree, carve a lyre, string it with Lalli's golden fleece (rock
 *   soup with Askeladden's pet rock), spin it at a spinning wheel outside Rellekka, have
 *   Fossegrimen bless it with a raw shark/manta ray/sea turtle/bass, and play on the longhall
 *   stage. Lanzig, Borrokar, Lensa and Freidir drop a lyre 1/17 while the trial is on.
 * - Sigli (FtSigli): track the Draugen with the hunters' talisman and defeat it.
 * - Sigmund (FtSigmund): the thirteen-person chain of favours for his exotic flower.
 * - Swensen (FtSwensen): the static portal maze under his house (S, W, E, N, S, E, N).
 * - Peer (FtPeer): enter his house with nothing, solve the door riddle and the puzzle room.
 * - Thorvald (FtThorvald): beat Koschei the deathless three times without weapons or armour.
 * With seven votes Brundt welcomes you: 3 QP, 2,812.4 xp in ten skills, a herring and a Fremennik
 * name (16 x 16, OSRS Wiki); the Rellekka sailor takes you to Miscellania and back.
 */
class FremennikTrials
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locRepo: LocRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val collision: CollisionFlagMap,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val ft by lazy {
        FtContext(npcTypes, locTypes, objTypes, npcRepo, objRepo, locRepo, random, collision, aiInteractions)
    }

    private val manni by lazy { FtManni(ft) }
    private val olaf by lazy { FtOlaf(ft) }
    private val sigli by lazy { FtSigli(ft) }
    private val sigmund by lazy { FtSigmund(ft) }
    private val swensen by lazy { FtSwensen(ft) }
    private val peer by lazy { FtPeer(ft) }
    private val thorvald by lazy { FtThorvald(ft) }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            council(FtNpcs.brundt, null) { talkBrundt(it) }
            council(FtNpcs.manni, Trial.Manni) { with(manni) { talk(it) } }
            council(FtNpcs.olaf, Trial.Olaf) { with(olaf) { talk(it) } }
            council(FtNpcs.sigli, Trial.Sigli) { with(sigli) { talk(it) } }
            council(FtNpcs.swensen, Trial.Swensen) { with(swensen) { talk(it) } }
            council(FtNpcs.thorvald, Trial.Thorvald) { with(thorvald) { talk(it) } }
            council(FtNpcs.peer, Trial.Peer) { talkPeer(it) }
            register(npcTypes, FtNpcs.sigmund, FREMENNIK_TRIALS, wants = { !player.ftComplete }) {
                if (!player.ftStarted) notStarted(it) else with(sigmund) { talkSigmund(it) }
            }
            default(npcTypes, FtNpcs.sigmund) { runQuestSection(it, ft.dialogue, "Subsequent dialogue with Sigmund") }

            // The rest of Sigmund's chain, outside the council.
            chainOnly(FtNpcs.thora) { runQuestSection(it, ft.dialogue, "Thora the Barkeep") }
            chainOnly(FtNpcs.skulgrimen) { runQuestSection(it, ft.dialogue, "Skulgrimen") }
            chainOnly(FtNpcs.fisherman) { runQuestSection(it, ft.dialogue, "Fisherman") }
            chainOnly(FtNpcs.sailor) { otherOuterlander(it) }
            chainOnly(FtNpcs.yrsa) { otherOuterlander(it) }
            for (askeladden in FtNpcs.askeladdens) {
                chainOnly(askeladden) { with(olaf) { talkAskeladden(it) } }
            }

            // Non-council citizens (Wiki "The Non-Council Members").
            for ((npc, section) in
                listOf(
                    FtNpcs.freidir to "Freidir",
                    FtNpcs.borrokar to "Borrokar",
                    FtNpcs.lanzig to "Lanzig",
                    FtNpcs.lensa to "Lensa",
                )) {
                register(npcTypes, npc, FREMENNIK_TRIALS, wants = { player.ftStarted }) {
                    runQuestSection(it, ft.dialogue, section)
                }
            }
        }
        with(manni) { bind() }
        with(olaf) { bind() }
        with(sigli) { bind() }
        with(swensen) { bind() }
        with(peer) { bind() }
        with(thorvald) { bind() }

        // Rewards: the Rellekka sailor's passage to Miscellania (and back) once you are Fremennik.
        onNpcOpLabel(npcTypes, FtNpcs.sailor, "Miscellania") { sail(MISCELLANIA_DOCK) }
        onNpcOpLabel(npcTypes, FtNpcs.misc_sailor, "Rellekka") { sail(RELLEKKA_DOCK) }
    }

    /**
     * A council member: during the quest their own trial, behind the merchant's-trial menu while
     * Sigmund's chain is open (Wiki: "Ask about the Merchant's trial" / "Ask about becoming a
     * Fremennik"); after it, their subsequent dialogue.
     */
    private fun ScriptContext.council(
        npc: NpcType,
        trial: Trial?,
        own: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        with(QuestTalk) {
            register(npcTypes, npc, FREMENNIK_TRIALS, wants = { !player.ftComplete }) { target ->
                when {
                    !player.ftStarted && trial != null -> notStarted(target)
                    player.merchantChainActive && trial != Trial.Sigmund && sigmund.linkFor(npc) != null ->
                        if (merchantMenu(target)) with(sigmund) { merchant(target) } else own(target)
                    else -> own(target)
                }
            }
            default(npcTypes, npc) { target -> afterQuest(target, trial) }
        }
    }

    /** Chain-only npcs (the sailor, Yrsa, ...): the merchant's trial menu, else [other]. */
    private fun ScriptContext.chainOnly(npc: NpcType, other: suspend ProtectedAccess.(Npc) -> Unit) {
        with(QuestTalk) {
            register(npcTypes, npc, FREMENNIK_TRIALS, wants = { player.ftStarted }) { target ->
                if (player.merchantChainActive && merchantMenu(target)) {
                    with(sigmund) { merchant(target) }
                } else {
                    other(target)
                }
            }
        }
    }

    /** `true` for "Ask about the Merchant's trial". */
    private suspend fun ProtectedAccess.merchantMenu(npc: Npc): Boolean {
        var merchant = false
        startDialogue(npc) {
            merchant = choice2("Ask about the Merchant's trial", true, "Ask about becoming a Fremennik", false)
        }
        return merchant
    }

    private suspend fun ProtectedAccess.talkPeer(npc: Npc) {
        if (!player.merchantChainActive || sigmund.linkFor(FtNpcs.peer) == null) {
            with(peer) { talk(npc) }
            return
        }
        var picked = 0
        startDialogue(npc) {
            picked =
                choice3(
                    "Ask about the Merchant's trial",
                    1,
                    "Ask about becoming a Fremennik",
                    2,
                    "Ask about depositing your equipment",
                    3,
                )
        }
        when (picked) {
            1 -> with(sigmund) { merchant(npc) }
            2 -> with(peer) { talk(npc) }
            3 -> with(peer) { depositOffer(npc) }
        }
    }

    private suspend fun ProtectedAccess.notStarted(npc: Npc) {
        // Authored line (not Wiki): council members won't deal with an outerlander before Brundt.
        startDialogue(npc) {
            chatNpc(neutral, "I have no business with outerlanders. Speak to our chieftain, Brundt, if you seek something of the Fremennik.")
        }
    }

    private suspend fun ProtectedAccess.otherOuterlander(npc: Npc) {
        // Authored line (not Wiki): Yrsa and the sailor have no part in the vote.
        startDialogue(npc) {
            chatPlayer(neutral, "Are you a member of the council of elders?")
            chatNpc(neutral, "I am not, outerlander. Seek out the council members if it is votes you are after.")
        }
    }

    private suspend fun ProtectedAccess.afterQuest(npc: Npc, trial: Trial?) {
        val section =
            when (trial) {
                null -> {
                    // Authored line (not Wiki): Brundt greets you by your Fremennik name.
                    startDialogue(npc) {
                        chatNpc(happy, "Welcome back, ${player.fremennikName}! It is always good to see a fellow Fremennik in the longhall.")
                    }
                    return
                }
                Trial.Manni -> "Subsequent dialogue with Manni"
                Trial.Olaf -> "Subsequent dialogue with Olaf"
                Trial.Sigli -> "Subsequent dialogue with Sigli"
                Trial.Sigmund -> "Subsequent dialogue with Sigmund"
                Trial.Swensen -> "Talking to Swensen again (4)"
                Trial.Peer -> "Talking to Peer the Seer again (4)"
                Trial.Thorvald -> "Talking to Thorvald (3)"
            }
        if (trial == Trial.Thorvald) {
            runQuestSection(npc, ft.dialogue, section, SimpleQuestHooks(conditions = mapOf("If the player manages to defeat Koschei:" to { true })))
            return
        }
        runQuestSection(npc, ft.dialogue, section)
    }

    /* Brundt the Chieftain */

    private suspend fun ProtectedAccess.talkBrundt(npc: Npc) {
        if (player.ftState == 0) {
            startQuest(npc)
            return
        }
        val votes = player.voteCount
        if (votes >= VOTES_NEEDED) {
            val nodes = ft.condBranch("Talking to Brundt (3)", "If the player has gotten all 7 votes:")
            startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, nodes) }
            finish(npc)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not gotten only one vote:" to { votes == 1 },
                        "If the player has gotten 2-6 votes:" to { votes in 2..6 },
                    ),
                rewrites = { it.replace("[2-6]", "$votes") },
            )
        runQuestSection(npc, ft.dialogue, "Talking to Brundt (3)", hooks)
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        var start = false
        val missing =
            stat(stats.crafting) < 40 || stat(stats.woodcutting) < 40 || stat(stats.fletching) < 25
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player is missing at least one requirement before starting the quest:" to { missing }),
                options = mapOf("Yes." to { start = true }),
            )
        runQuestSection(npc, ft.dialogue, "Talking to Brundt the Chieftain", hooks)
        if (!start) return
        // A fresh start (also after ::questreset): clear the trials' server varps.
        for (varp in FtVarps.trials) VarPlayerIntMapSetter.set(player, varp, 0)
        player.setQuestState(FREMENNIK_TRIALS, FT_STARTED)
    }

    private suspend fun ProtectedAccess.finish(npc: Npc) {
        if (!player.ftHas(FtVarps.votes, NAME_SET)) {
            player.ftSetField(FtVarps.votes, NAME_PREFIX_SHIFT, 4, random.of(0, 15))
            player.ftSetField(FtVarps.votes, NAME_SUFFIX_SHIFT, 4, random.of(0, 15))
            player.ftSet(FtVarps.votes, NAME_SET)
        }
        val name = player.fremennikName
        runQuestSection(npc, ft.dialogue, "Post-quest dialogue", SimpleQuestHooks(rewrites = { it.replace("[Fremennik name]", name) }))
        for (stat in REWARD_STATS) {
            statAdvance(stat, REWARD_XP * xpMods.get(player, stat))
        }
        invAddOrDrop(objRepo, FtObjs.herring)
        completeQuest(FREMENNIK_TRIALS)
    }

    /* Rewards: Miscellania */

    private suspend fun ProtectedAccess.sail(dest: CoordGrid) {
        sailTo(
            collision.nearestWalkable(dest),
            fare = 0,
            message = "You board the longship.",
            collision = collision,
            canSail = {
                if (!player.ftComplete) {
                    // Authored line (not Wiki): only Fremennik may sail with the clan.
                    mes("Only members of the Fremennik may sail on this longship.")
                }
                player.ftComplete
            },
        )
    }

    companion object {
        private const val VOTES_NEEDED = 7
        private const val REWARD_XP = 2812.4

        private val REWARD_STATS =
            listOf(
                stats.agility,
                stats.attack,
                stats.crafting,
                stats.defence,
                stats.fishing,
                stats.fletching,
                stats.hitpoints,
                stats.strength,
                stats.thieving,
                stats.woodcutting,
            )

        /** The Rellekka sailor's pier (his spawn) and the Miscellania sailor's (hers). */
        private val RELLEKKA_DOCK = CoordGrid(2629, 3692, 0)
        private val MISCELLANIA_DOCK = CoordGrid(2581, 3846, 0)

        val FREMENNIK_TRIALS =
            QuestDef(
                name = "The Fremennik Trials",
                varp = { FtVarps.quest },
                complete = FT_COMPLETE,
                questPoints = 3,
                rewards =
                    listOf(
                        "2,812.4 XP in each of: Agility,",
                        "Attack, Crafting, Defence, Fishing,",
                        "Fletching, Hitpoints, Strength,",
                        "Thieving and Woodcutting",
                        "A Fremennik name",
                        "Passage to Miscellania",
                    ),
                scrollObj = { FtObjs.enchanted_lyre },
                listRow = "quest_fremenniktrials",
            )
    }
}

/** What the trial files share: injected services and the transcript. */
internal class FtContext(
    val npcTypes: NpcTypeList,
    val locTypes: LocTypeList,
    val objTypes: ObjTypeList,
    val npcRepo: NpcRepository,
    val objRepo: ObjRepository,
    val locRepo: LocRepository,
    val random: GameRandom,
    val collision: CollisionFlagMap,
    val aiInteractions: AiPlayerInteractions,
) {
    val dialogue: QuestDialogue by lazy { QuestDialogue.load(FremennikTrials::class.java, "fremennik_trials") }

    /** The nodes under [section]'s condition [cond] (searched depth-first). */
    fun condBranch(section: String, cond: String): List<Node> {
        fun find(nodes: List<Node>): List<Node>? {
            for (node in nodes) {
                if (node is Node.Cond && node.text == cond) return node.children
                find(node.children)?.let { return it }
            }
            return null
        }
        return find(dialogue.section(section)) ?: error("Section \"$section\" has no condition \"$cond\".")
    }

    /** Runs [section]'s branch under [option] (see [runQuestBranch]). */
    suspend fun ProtectedAccess.branch(
        npc: Npc?,
        section: String,
        option: String,
        hooks: SimpleQuestHooks = SimpleQuestHooks(),
        speakers: Map<String, NpcType> = emptyMap(),
    ) {
        runQuestBranch(npc, dialogue, section, option, hooks, speakers)
    }

    /** Runs the nodes under [cond] in [section]. */
    suspend fun ProtectedAccess.cond(
        npc: Npc?,
        section: String,
        cond: String,
        hooks: SimpleQuestHooks = SimpleQuestHooks(),
        speakers: Map<String, NpcType> = emptyMap(),
    ) {
        val nodes = condBranch(section, cond)
        if (npc != null) {
            startDialogue(npc) { QuestDialogueRunner(hooks, speakers).run(this, nodes) }
        } else {
            startDialogue { QuestDialogueRunner(hooks, speakers).run(this, nodes) }
        }
    }

    private val logger = InlineLogger()

    /** Runs an op-label binding and warns at startup if the op is missing or already taken. */
    inline fun bound(label: String, bind: () -> Boolean) {
        if (!bind()) warnUnbound(label)
    }

    fun warnUnbound(label: String) {
        logger.warn { "The Fremennik Trials: could not bind \"$label\" (missing or taken)." }
    }

    /** A walkable tile at (or next to) [x], [z] on [level]. */
    fun tile(x: Int, z: Int, level: Int): CoordGrid = collision.nearestWalkable(CoordGrid(x, z, level), 3)

    /** The level [loc]'s instance at [x], [z] was decoded on (bridged map tiles shift locs down). */
    fun levelOf(x: Int, z: Int, loc: LocType, levels: IntRange = 0..3, fallback: Int = 2): Int =
        levels.firstOrNull { locRepo.findExact(CoordGrid(x, z, it), loc) != null } ?: fallback
}
