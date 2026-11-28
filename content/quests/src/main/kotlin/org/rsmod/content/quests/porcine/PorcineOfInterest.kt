package org.rsmod.content.quests.porcine

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
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
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * A Porcine of Interest (OSRS Wiki). Quest varbit `porcine` (bits 0-5 of `porcine_main`), end
 * state 40. The cache multis fix two thresholds: the trail (cart, produce, broken trees) shows from
 * 10, the cave skeleton and its rope from 15; Spria's multinpc hides at 36-39, so 35 is the
 * last stage before the end.
 * - 5 the Draynor notice board's bounty; 10 Sarah (South Falador farm) lost her cart to a monster
 *   near the Draynor crossroads. The trail leads north-east to a strange hole: a rope ties on (15)
 *   and drops into the Sourhog Cave. The skeleton at the far end: the Sourhog knocks you out and
 *   Spria carries you to her house in Draynor (20).
 * - 25 Spria's reinforced goggles; with them on, over the blockage, the Sourhog (level 37) is
 *   fought to the death (30) and a knife or blade cuts off its foot (`porcine_footcut`). 35 Sarah
 *   pays 5,000 coins for the foot; 40 Spria: 1 QP, 1,000 Slayer XP.
 *
 * Simplified: the knock-out is its dialogue plus a teleport; the hog is spawned when you climb
 * the blockage; any knife or scimitar cuts the foot; Spria's slayer tasks/points aren't wired.
 */
class PorcineOfInterest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(PorcineOfInterest::class.java, "porcine_of_interest") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Spria" to PoiNpcs.spria, "Pig Thing" to PoiNpcs.hog_cutscene)
    }

    override fun ScriptContext.startup() {
        onLocOpLabel(locTypes, PoiLocs.noticeboard, "Check") { noticeBoard() }
        with(QuestTalk) {
            register(npcTypes, PoiNpcs.sarah, PORCINE, wants = { state in BOARD until COMPLETE }) { talkSarah(it) }
        }
        // Spria's multinpc has no ops of its own.
        onOpNpc1(PoiNpcs.spria_multi) { talkSpria(it.npc) }

        onLocOpLabel(locTypes, PoiLocs.cart, "Investigate") { runQuestSection(null, dialogue, "Damaged cart") }
        onLocOpLabel(locTypes, PoiLocs.cabbage, "Investigate") { runQuestSection(null, dialogue, "Cabbages") }
        onLocOpLabel(locTypes, PoiLocs.carrot, "Investigate") { runQuestSection(null, dialogue, "Carrots") }
        onLocOpLabel(locTypes, PoiLocs.potatoes, "Investigate") { runQuestSection(null, dialogue, "Potatoes") }
        onLocOpLabel(locTypes, PoiLocs.tree, "Investigate") { runQuestSection(null, dialogue, "Broken dead tree") }

        onLocOpLabel(locTypes, PoiLocs.hole_norope, "Investigate") { holeNoRope() }
        onOpLocU(PoiLocs.hole_norope, PoiObjs.rope) { holeNoRope() }
        onLocOpLabel(locTypes, PoiLocs.hole_rope, "Climb-down") { enterCave() }
        onLocOpLabel(locTypes, PoiLocs.cave_rope, "Climb-up") { leaveCave() }
        onLocOpLabel(locTypes, PoiLocs.fallen_rope, "Investigate") {
            runQuestSection(null, dialogue, "Investigating pile of rope")
        }
        onLocOpLabel(locTypes, PoiLocs.skeleton, "Investigate") { skeleton() }
        onLocOpLabel(locTypes, PoiLocs.blockage, "Climb-over") { blockage(it) }

        onEvent<NpcKilledEvent> { hogKilled(this) }
        // Loc ops are keyed on the visible multiloc child.
        onLocOpLabel(locTypes, PoiLocs.dead_hog_withfoot, "Cut-foot") { cutFoot() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(PORCINE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(PORCINE, value)
    }

    private fun ProtectedAccess.wearingGoggles(): Boolean = invTotal(worn, PoiObjs.goggles) > 0

    /* The notice board, Sarah, Spria */

    private suspend fun ProtectedAccess.noticeBoard() {
        if (state != 0) {
            runQuestSection(null, dialogue, "Searching the notice board again")
            return
        }
        var start = false
        runQuestSection(null, dialogue, "Searching the notice board", SimpleQuestHooks(options = mapOf("Yes." to { start = true })))
        if (start) setState(BOARD)
    }

    private suspend fun ProtectedAccess.talkSarah(npc: Npc) {
        when {
            state == BOARD -> {
                runQuestSection(npc, dialogue, "Initial conversation")
                setState(SARAH)
            }
            state == KILLED -> {
                val has = carries(PoiObjs.foot)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have the sourhog foot:" to { !has },
                                "If the player has the sourhog foot:" to { has },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Sarah", hooks)
                if (has) {
                    invDel(inv, PoiObjs.foot)
                    invAddOrDrop(objRepo, PoiObjs.coins, BOUNTY)
                    setState(BOUNTY_PAID)
                }
            }
            state == BOUNTY_PAID ->
                runQuestSection(npc, dialogue, "Talking to Sarah before telling Spria about the bounty completion")
            state >= HOLE -> runQuestSection(npc, dialogue, "Talking to Sarah after finding the cave")
            else -> runQuestSection(npc, dialogue, "Subsequent conversation")
        }
    }

    private suspend fun ProtectedAccess.talkSpria(npc: Npc) {
        when (state) {
            ATTACKED -> {
                var given = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player's inventory is full:" to { inv.isFull() },
                                "If the player's inventory is not full:" to { !inv.isFull() },
                            ),
                        boxes =
                            mapOf(
                                "Spria hands you a pair of Reinforced Goggles." to {
                                    invAddOrDrop(objRepo, PoiObjs.goggles)
                                    given = true
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Initial conversation (2)", hooks, speakers)
                if (given) setState(GOGGLES)
            }
            GOGGLES -> {
                val lost = !carries(PoiObjs.goggles) && !wearingGoggles()
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has a pair equipped:" to { wearingGoggles() },
                                "If the player has a pair in their inventory:" to { carries(PoiObjs.goggles) },
                                "If a player does not have a pair in their inventory or bank:" to { lost },
                            ),
                        actions =
                            mapOf("\"Spria hands you another pair of Reinforced Goggles.\"" to { invAddOrDrop(objRepo, PoiObjs.goggles) }),
                    )
                runQuestSection(npc, dialogue, "After acquiring the Reinforced goggles", hooks, speakers)
            }
            KILLED -> runQuestSection(npc, dialogue, "Talking to Spria", speakers = speakers)
            BOUNTY_PAID -> {
                runQuestSection(npc, dialogue, "Talking to Spria (2)", SimpleQuestHooks(), speakers)
                statAdvance(stats.slayer, SLAYER_XP * xpMods.get(player, stats.slayer))
                completeQuest(PORCINE)
            }
            else ->
                // Authored (not Wiki): before the cave, and after the quest (her tasks aren't built).
                startDialogue(npc) { chatNpc(neutral, "'Ello, and what are you after then?") }
        }
    }

    /* The trail and the Sourhog Cave */

    private suspend fun ProtectedAccess.holeNoRope() {
        if (state < SARAH) return
        val hasRope = carries(PoiObjs.rope)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have a rope in their inventory:" to { !hasRope },
                        "If the player has a rope in their inventory:" to { hasRope },
                    )
            )
        runQuestSection(null, dialogue, "Strange hole", hooks)
        if (hasRope && state == SARAH) {
            invDel(inv, PoiObjs.rope)
            setState(HOLE)
        }
    }

    private suspend fun ProtectedAccess.enterCave() {
        when {
            state == ATTACKED ->
                runQuestSection(null, dialogue, "Attempting to enter the Sourhog cave after being attacked by the Sourhog")
            state in ATTACKED until KILLED && !wearingGoggles() ->
                runQuestSection(null, dialogue, "Attempting to enter the hole without reinforced goggles equipped")
            state == KILLED && carries(PoiObjs.foot) ->
                runQuestSection(
                    null,
                    dialogue,
                    "Attempting to re-enter the Sourhog Cave with a Sourhog foot in the player's inventory",
                )
            else -> telejump(CAVE_START)
        }
    }

    private fun ProtectedAccess.leaveCave() {
        telejump(HOLE_OUTSIDE)
    }

    private suspend fun ProtectedAccess.skeleton() {
        if (state != HOLE) return
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is not wearing Gnome goggles:" to { true },
                        "If the player is wearing Gnome goggles:" to { false },
                    )
            )
        runQuestSection(null, dialogue, "Investigating the skeleton", hooks, speakers)
        telejump(SPRIA_HOUSE)
        setState(ATTACKED)
    }

    private suspend fun ProtectedAccess.blockage(loc: BoundLocInfo) {
        val north = player.coords.z > loc.coords.z
        if (north && state == GOGGLES) {
            var go = false
            val hooks = SimpleQuestHooks(options = mapOf("Yes" to { go = true }))
            runQuestSection(null, dialogue, "Climbing the blockage", hooks)
            if (!go) return
            telejump(BLOCKAGE_SOUTH)
            val hog = Npc(npcTypes[PoiNpcs.hog], HOG_SPAWN)
            npcRepo.add(hog, HOG_TICKS)
            hog.opPlayer2(player, aiInteractions)
            return
        }
        telejump(if (north) BLOCKAGE_SOUTH else BLOCKAGE_NORTH)
    }

    private fun hogKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != PoiNpcs.hog.id) return
        val player = event.hero
        if (player.questState(PORCINE) != GOGGLES) return
        player.setQuestState(PORCINE, KILLED)
        VarPlayerIntMapSetter.set(player, PoiVarBits.footcut, 0)
        locRepo.add(event.npc.coords, PoiLocs.dead_hog, CARCASS_TICKS, LocAngle.South, LocShape.CentrepieceStraight)
    }

    private suspend fun ProtectedAccess.cutFoot() {
        if (state != KILLED || carries(PoiObjs.foot) || player.vars[PoiVarBits.footcut] != 0) {
            if (carries(PoiObjs.foot)) runQuestSection(null, dialogue, "Taking the Sourhog's foot", footHooks(has = true))
            return
        }
        val blade = PoiObjs.blades.any { carries(it) || invTotal(worn, it) > 0 }
        runQuestSection(null, dialogue, "Taking the Sourhog's foot", footHooks(has = false, blade = blade))
        if (!blade) return
        invAddOrDrop(objRepo, PoiObjs.foot)
        VarPlayerIntMapSetter.set(player, PoiVarBits.footcut, 1)
    }

    private fun footHooks(has: Boolean, blade: Boolean = false): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has a Sourhog foot in their inventory:" to { has },
                    "If the player doesn't have a knife or slash weapon:" to { !has && !blade },
                    "If the player has a knife or slash weapon:" to { !has && blade },
                )
        )

    companion object {
        const val BOARD = 5
        const val SARAH = 10
        const val HOLE = 15
        const val ATTACKED = 20
        const val GOGGLES = 25
        const val KILLED = 30
        const val BOUNTY_PAID = 35
        const val COMPLETE = 40

        private const val BOUNTY = 5_000
        private const val SLAYER_XP = 1_000.0
        private const val HOG_TICKS = 500
        private const val CARCASS_TICKS = 300

        private val HOLE_OUTSIDE = CoordGrid(3151, 3346, 0)
        private val CAVE_START = CoordGrid(3157, 9712, 0)
        private val BLOCKAGE_NORTH = CoordGrid(3156, 9705, 0)
        private val BLOCKAGE_SOUTH = CoordGrid(3156, 9703, 0)
        private val HOG_SPAWN = CoordGrid(3165, 9680, 0)
        private val SPRIA_HOUSE = CoordGrid(3092, 3266, 0)

        val PORCINE =
            QuestDef(
                name = "A Porcine of Interest",
                varbit = { PoiVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("5,000 coins", "1,000 Slayer XP", "Spria as a Slayer master"),
                scrollObj = { PoiObjs.foot },
                listRow = "quest_porcineofinterest",
            )
    }
}

// Every cache ref A Porcine of Interest uses (re-check after cache updates).

internal object PoiNpcs : NpcReferences() {
    val sarah = find("farming_shopkeeper_1")
    /** Multinpc on varbit `porcine`: Spria at 0-35, hidden 36-39, the active slayer master from 40. */
    val spria_multi = find("slayer_master_9")
    val spria = find("porcine_spria")
    val hog = find("porcine_sourhog_second")
    val hog_cutscene = find("porcine_sourhog_cutscene")
}

internal object PoiLocs : LocReferences() {
    val noticeboard = find("porcine_noticeboard")
    val cart = find("porcine_tracking_cart_visible")
    val cabbage = find("porcine_tracking_cabbage_visible")
    val carrot = find("porcine_tracking_carrot_visible")
    val potatoes = find("porcine_tracking_potatoes_visible")
    val tree = find("porcine_tracking_tree_visible")
    /** Children of the `porcine_hole` multiloc (no rope, rope tied). */
    val hole_norope = find("porcine_hole_norope")
    val hole_rope = find("porcine_hole_rope")
    val cave_rope = find("porcine_cave_exit_rope")
    val fallen_rope = find("porcine_fallen_rope_visible")
    val skeleton = find("porcine_skeleton_visible_op")
    val blockage = find("porcine_cave_blockage")
    /** Multiloc on `porcine_footcut`: with foot at 0, without at 1. */
    val dead_hog = find("porcine_dead_sourhog")
    val dead_hog_withfoot = find("porcine_dead_sourhog_withfoot")
}

internal object PoiObjs : ObjReferences() {
    val rope = find("rope")
    val goggles = find("slayer_reinforced_goggles")
    val foot = find("porcine_sourhog_trophy")
    val coins = find("coins")
    val blades = listOf(find("knife"), find("bronze_scimitar"))
}

internal object PoiVarBits : VarBitReferences() {
    val quest = find("porcine")
    val footcut = find("porcine_footcut")
}
