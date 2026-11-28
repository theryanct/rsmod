package org.rsmod.content.quests.daddyshome

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Daddy's Home (OSRS Wiki). Quest varbit `daddyshome_status`, end state 13; the furniture
 * multilocs read one 2-bit varbit each (`daddyshome_stool_1` ... `daddyshome_carpet`): 0 broken,
 * 1 broken with its Demolish/Remove op, 2 an empty Build hotspot, 3 the new piece. Stages ours:
 * - 1 Marlo (Varrock estate agent's) wants his dad's squat redone off the books. 2 Old Man Yarlo
 *   (south-east Varrock) wants the old furniture out (the pieces get their remove ops). With all
 *   seven gone, 4 his Construction talk: two stools, two tables, a chair (planks + nails), a
 *   carpet (3 bolts of cloth) and a bed of waxwood (3 waxwood planks + 2 cloth; the kitchen crates
 *   hold the logs and the Lumber Yard's sawmill operator planks them for free).
 * - 11 everything built and Yarlo told; 13 Marlo: 1 QP, 400 Construction XP (544 more from the
 *   furniture), 1,000 coins (everyone has a house here) and Marlo's crate of supplies.
 *
 * Simplified: building takes the materials and gives the xp at once (no build interface); nails
 * never bend.
 */
class DaddysHome
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DaddysHome::class.java, "daddys_home") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (marlo in DhNpcs.marlos) {
                register(npcTypes, marlo, DADDYS_HOME, wants = { wantsMarlo() }) { talkMarlo(it) }
            }
            default(npcTypes, DhNpcs.yarlo) { talkYarlo(it) }
            register(npcTypes, DhNpcs.sawmill, DADDYS_HOME, wants = { state >= BUILDING && carries(DhObjs.waxwood_logs, 3) }) {
                waxwood(it)
            }
        }
        for (piece in DhLocs.removable) {
            onLocOpLabel(locTypes, piece, "Demolish") { remove(it) }
            onLocOpLabel(locTypes, piece, "Remove") { remove(it) }
        }
        for (spot in DhLocs.hotspots) onLocOpLabel(locTypes, spot, "Build") { build(it) }
        onLocOpLabel(locTypes, DhLocs.crates, "Search") { searchCrates() }
        onHeldOpLabel(objTypes, DhObjs.reward_crate, "Open") { openCrate() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(DADDYS_HOME)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(DADDYS_HOME, value)
    }

    private fun ProtectedAccess.wantsMarlo(): Boolean =
        state in STARTED until COMPLETE || (state == 0 && player.vars[DhVarps.mh_contract] == 0)

    /** The furniture varbit a (base) furniture loc reads. */
    private fun pieceOf(loc: BoundLocInfo): Piece? = PIECES.firstOrNull { p -> p.locs().any { it.id == loc.id } }

    private fun ProtectedAccess.pieceState(piece: Piece): Int = player.vars[piece.varbit()]

    private fun ProtectedAccess.setPiece(piece: Piece, value: Int) {
        VarPlayerIntMapSetter.set(player, piece.varbit(), value)
    }

    /* Marlo */

    private suspend fun ProtectedAccess.talkMarlo(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Tell me where he lives, and I'll do the job." to { start = true }))
                runQuestSection(npc, dialogue, "Talking to Marlo", hooks)
                if (start) setState(STARTED)
            }
            BUILT -> {
                runQuestSection(npc, dialogue, "Talking to Marlo after building everything for his dad")
                invAddOrDrop(objRepo, DhObjs.reward_crate)
                invAddOrDrop(objRepo, DhObjs.coins, REWARD_COINS)
                statAdvance(stats.construction, QUEST_XP * xpMods.get(player, stats.construction))
                completeQuest(DADDYS_HOME)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Marlo again")
        }
    }

    /* Old Man Yarlo */

    private suspend fun ProtectedAccess.talkYarlo(npc: Npc) {
        when {
            state == STARTED -> {
                runQuestSection(npc, dialogue, "Talking to Old Man Yarlo")
                for (piece in PIECES) setPiece(piece, REMOVABLE)
                setState(MET)
            }
            state == MET -> {
                val removed = PIECES.count { pieceState(it) >= HOTSPOT }
                when (removed) {
                    0 -> runQuestSection(npc, dialogue, "Talking to Old Man Yarlo again if none of the old furniture was removed")
                    PIECES.size -> {
                        val hooks =
                            SimpleQuestHooks(conditions = mapOf("If the player already has a player-owned house:" to { false }))
                        runQuestSection(npc, dialogue, "Talking to Old Man Yarlo again if all the old furniture was removed", hooks)
                        setState(BUILDING)
                    }
                    else -> stillToRemove(npc)
                }
            }
            state == BUILDING && PIECES.all { pieceState(it) == FIXED } -> {
                runQuestSection(npc, dialogue, "Talking to Old Man Yarlo after building everything")
                setState(BUILT)
            }
            state == BUILDING -> yarloHelp(npc)
            state == BUILT -> runQuestSection(npc, dialogue, "Talking to Old Man Yarlo before claiming reward from Marlo")
            state >= COMPLETE ->
                // Authored (not Wiki).
                startDialogue(npc) { chatNpc(happy, "My new furniture is wonderful, young man. Thank you again!") }
            else ->
                // Authored (not Wiki): before Marlo sends you.
                startDialogue(npc) { chatNpc(neutral, "What brings you here, young man? I'm not expecting visitors.") }
        }
    }

    /** The Wiki lists all seven pieces; only those still in place are named. */
    private suspend fun ProtectedAccess.stillToRemove(npc: Npc) {
        val left = PIECES.filter { pieceState(it) < HOTSPOT }.joinToString(" ") { "- ${it.label}" }
        val nodes = dialogue.section("Talking to Old Man Yarlo again if some of the old furniture was removed")
        val head = nodes.take(2)
        val list = Node.Line("Old Man Yarlo", "You still need to remove: $left")
        startDialogue(npc) {
            QuestDialogueRunner(SimpleQuestHooks(), mapOf("Old Man Yarlo" to DhNpcs.yarlo)).run(this, head + list)
        }
    }

    private suspend fun ProtectedAccess.yarloHelp(npc: Npc) {
        val missing = listOf(DhObjs.hammer, DhObjs.saw).filter { !carries(it) }
        val word = if (missing.firstOrNull() == DhObjs.saw) "saw" else "hammer"
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player already has a saw and hammer:" to { missing.isEmpty() },
                        "If the player doesn't have a saw or hammer:" to { missing.isNotEmpty() },
                    ),
                boxes =
                    mapOf(
                        "Yarlo gives you a [hammer/saw.]" to {
                            missing.firstOrNull()?.let { invAddOrDrop(objRepo, it) }
                            mesbox("Yarlo gives you a $word.")
                            true
                        }
                    ),
                rewrites = { it.replace("[hammer/saw]", word) },
            )
        runQuestSection(npc, dialogue, "Talking to Old Man Yarlo again", hooks)
    }

    /* Removing and building the furniture */

    private fun ProtectedAccess.remove(loc: BoundLocInfo) {
        val piece = pieceOf(loc) ?: return
        if (state != MET || pieceState(piece) != REMOVABLE) return
        setPiece(piece, HOTSPOT)
        mes("You remove the ${piece.removed}.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.build(loc: BoundLocInfo) {
        val piece = pieceOf(loc) ?: return
        if (state != BUILDING) {
            mes("You should ask Old Man Yarlo what he wants built here.") // Authored (not Wiki).
            return
        }
        if (pieceState(piece) != HOTSPOT) return
        if (!carries(DhObjs.hammer) || !carries(DhObjs.saw)) {
            mes("You need a hammer and a saw to build furniture.") // Authored (not Wiki).
            return
        }
        val needs = piece.needs()
        val nails = DhObjs.nails.firstOrNull { carries(it, piece.nails) }
        if (needs.any { (obj, count) -> !carries(obj, count) } || (piece.nails > 0 && nails == null)) {
            mes("You need ${piece.materials} to build the ${piece.built}.") // Authored (not Wiki).
            return
        }
        anim(DhSeqs.build)
        delay(BUILD_TICKS)
        for ((obj, count) in needs) invDel(inv, obj, count)
        if (nails != null && piece.nails > 0) invDel(inv, nails, piece.nails)
        setPiece(piece, FIXED)
        statAdvance(stats.construction, piece.xp * xpMods.get(player, stats.construction))
        mes("You build the ${piece.built}.") // Authored (not Wiki).
    }

    /* Waxwood */

    private suspend fun ProtectedAccess.searchCrates() {
        if (state < BUILDING || carries(DhObjs.waxwood_logs) || carries(DhObjs.waxwood_plank) || pieceState(BED) == FIXED) {
            mes("You search the crates but find nothing you need.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Searching the crate of waxwood")
        invAddOrDrop(objRepo, DhObjs.waxwood_logs, 3)
    }

    private suspend fun ProtectedAccess.waxwood(npc: Npc) {
        val section = dialogue.section("Talking to the sawmill operator at the Lumberyard")
        val option = section.first { it is Node.Option && it.label.startsWith("I need some waxwood") }
        val nodes = section.take(1) + option.children
        startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), mapOf("Sawmill operator" to DhNpcs.sawmill)).run(this, nodes) }
        invDel(inv, DhObjs.waxwood_logs, 3)
        invAddOrDrop(objRepo, DhObjs.waxwood_plank, 3)
    }

    /* Marlo's crate (Wiki contents) */

    private fun ProtectedAccess.openCrate() {
        invDel(inv, DhObjs.reward_crate)
        for ((obj, count) in DhObjs.crateContents) invAddOrDrop(objRepo, obj, count)
        mes("You open Marlo's crate.") // Authored (not Wiki).
    }

    /** One piece of furniture: its varbit, loc(s), names, materials and Wiki xp. */
    private class Piece(
        val varbit: () -> VarBitType,
        val locs: () -> List<LocType>,
        val label: String,
        val removed: String,
        val built: String,
        val needs: () -> List<Pair<ObjType, Int>>,
        val nails: Int,
        val materials: String,
        val xp: Double,
    )

    companion object {
        const val STARTED = 1
        const val MET = 2
        const val BUILDING = 4
        const val BUILT = 11
        const val COMPLETE = 13

        private const val REMOVABLE = 1
        private const val HOTSPOT = 2
        private const val FIXED = 3

        private const val QUEST_XP = 400.0
        private const val REWARD_COINS = 1_000
        private const val BUILD_TICKS = 3

        private fun stool(varbit: () -> VarBitType, loc: () -> LocType, where: String) =
            Piece(varbit, { listOf(loc()) }, "A broken stool in the $where", "broken stool", "stool",
                { listOf(DhObjs.plank to 1) }, 2, "a plank and 2 nails", 30.0)

        private fun table(varbit: () -> VarBitType, loc: () -> LocType, where: String) =
            Piece(varbit, { listOf(loc()) }, "A broken table in the $where", "broken table", "table",
                { listOf(DhObjs.plank to 3) }, 4, "3 planks and 4 nails", 87.0)

        private val BED =
            Piece({ DhVarBits.bed }, { listOf(DhLocs.bed) }, "The old campbed", "old campbed", "waxwood bed",
                { listOf(DhObjs.waxwood_plank to 3, DhObjs.cloth to 2) }, 0, "3 waxwood planks and 2 bolts of cloth", 207.0)

        private val PIECES =
            listOf(
                stool({ DhVarBits.stool_1 }, { DhLocs.stool_1 }, "kitchen"),
                stool({ DhVarBits.stool_2 }, { DhLocs.stool_2 }, "bedroom"),
                Piece({ DhVarBits.chair }, { listOf(DhLocs.chair) }, "A broken chair in the bedroom", "broken chair", "chair",
                    { listOf(DhObjs.plank to 2) }, 2, "2 planks and 2 nails", 58.0),
                table({ DhVarBits.table_1 }, { DhLocs.table_1 }, "kitchen"),
                table({ DhVarBits.table_2 }, { DhLocs.table_2 }, "bedroom"),
                BED,
                Piece({ DhVarBits.carpet }, { DhLocs.carpets }, "The rotten carpet", "rotten carpet", "carpet",
                    { listOf(DhObjs.cloth to 3) }, 0, "3 bolts of cloth", 45.0),
            )

        val DADDYS_HOME =
            QuestDef(
                name = "Daddy's Home",
                varbit = { DhVarBits.status },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("400 Construction XP", "1,000 coins", "Marlo's crate of supplies"),
                scrollObj = { DhObjs.reward_crate },
                listRow = "quest_daddyshome",
            )
    }
}

// Every cache ref Daddy's Home uses (re-check after cache updates).

internal object DhNpcs : NpcReferences() {
    /** Marlo, the Varrock Mahogany Homes contractor (Mahogany Homes' Talk-to is the default). */
    val marlos = listOf(find("con_contractor_varrock_1op"), find("con_contractor_varrock_2op"))
    val yarlo = find("daddyshome_daddy")
    val sawmill = find("poh_sawmill_opp")
}

internal object DhLocs : LocReferences() {
    /** The base furniture multilocs (loc ops key on the visible child, `loc.id` is the base). */
    val stool_1 = find("daddyshome_stool_1")
    val stool_2 = find("daddyshome_stool_2")
    val chair = find("daddyshome_chair")
    val table_1 = find("daddyshome_table_1")
    val table_2 = find("daddyshome_table_2")
    val bed = find("daddyshome_bed")
    val carpets = listOf(find("daddyshome_carpet_middle"), find("daddyshome_carpet_side"), find("daddyshome_carpet_corner"))
    /** The children with a remove op (Demolish, or Remove for the bed and carpet). */
    val removable =
        listOf(
            find("daddyshome_stool_broken_1op"),
            find("daddyshome_chair_broken_1op"),
            find("daddyshome_table_broken_1op"),
            find("daddyshome_bed_broken_1op"),
            find("daddyshome_carpet_middle_broken_1op"),
        )
    val hotspots =
        listOf(
            find("daddyshome_stool_hotspot"),
            find("daddyshome_chair_hotspot"),
            find("daddyshome_table_hotspot"),
            find("daddyshome_bed_hotspot"),
            find("daddyshome_carpet_middle_hotspot"),
            find("daddyshome_carpet_side_hotspot"),
            find("daddyshome_carpet_corner_hotspot"),
        )
    val crates = find("daddyshome_crates")
}

internal object DhObjs : ObjReferences() {
    val hammer = find("hammer")
    val saw = find("poh_saw")
    val plank = find("woodplank")
    val cloth = find("cloth")
    val coins = find("coins")
    val waxwood_logs = find("daddyshome_waxwood_logs")
    val waxwood_plank = find("daddyshome_waxwood_plank")
    val reward_crate = find("daddyshome_reward")
    val nails =
        listOf(
            find("nails_bronze"),
            find("nails_iron"),
            find("nails"),
            find("nails_black"),
            find("nails_mithril"),
            find("nails_adamant"),
            find("nails_rune"),
        )
    private val oak_plank = find("plank_oak")
    private val steel_bar = find("steel_bar")
    private val house_tab = find("poh_tablet_teleporttohouse")
    private val falador_tab = find("poh_tablet_faladorteleport")
    private val mithril_nails = find("nails_mithril")
    val crateContents: List<Pair<ObjType, Int>> by lazy {
        listOf(
            plank to 25,
            mithril_nails to 50,
            steel_bar to 5,
            oak_plank to 10,
            cloth to 8,
            house_tab to 5,
            falador_tab to 1,
        )
    }
}

internal object DhVarBits : VarBitReferences() {
    val status = find("daddyshome_status")
    val stool_1 = find("daddyshome_stool_1")
    val stool_2 = find("daddyshome_stool_2")
    val chair = find("daddyshome_chair")
    val table_1 = find("daddyshome_table_1")
    val table_2 = find("daddyshome_table_2")
    val bed = find("daddyshome_bed")
    val carpet = find("daddyshome_carpet")
}

internal object DhVarps : VarpReferences() {
    /** Mahogany Homes' contract varp: Marlo keeps his contract talk while one is in progress. */
    val mh_contract = find("mh_contract")
}

internal object DhSeqs : SeqReferences() {
    val build = find("human_poh_build")
}
