package org.rsmod.content.quests.seaslug

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sea Slug (OSRS Wiki). `seaslugquest` varp (cache db end state 12). The cache keys the rowboat
 * (fixed from 3), Kennith's badly repaired wall (broken from 9) and Kennith and Kent back home
 * (from 11) on it; the steps in between are ours: 1 Caroline's worry, 2 Holgart wants swamp
 * paste, 3 boat fixed, 4 met Bailey on the platform, 5 found Kennith, 6 Holgart sails for Kent,
 * 7 found Kent (and he pulls a sea slug off you), 8 Bailey's damp torch, 9 kicked the wall,
 * 10 told Kennith, 11 the crane lowered him to Holgart, 12 complete (Caroline: oyster pearls,
 * 7,175 Fishing xp, 1 QP).
 *
 * The torch: broken glass on damp sticks dries them, and rubbing the dry sticks (30 Firemaking)
 * lights Bailey's torch. Climbing to the upper deck with the torch unlit (after Bailey hands it
 * over) gets you hurt and turned back.
 *
 * Not done: possessed fishermen (the transcript's torch-on-fisherman branches); sea slugs and the
 * fishing platform's other npcs beyond the ones spawned here.
 */
class SeaSlug
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(SeaSlug::class.java, "sea_slug") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, SsNpcs.caroline, "Talk-to") { talkCaroline(it) }
        for (holgart in SsNpcs.holgart_land) {
            onNpcOpLabel(npcTypes, holgart, "Talk-to") { talkHolgartLand(it) }
        }
        onNpcOpLabel(npcTypes, SsNpcs.holgart_platform, "Talk-to") { talkHolgartPlatform(it) }
        onNpcOpLabel(npcTypes, SsNpcs.holgart_platform, "Travel") { talkHolgartPlatform(it) }
        onNpcOpLabel(npcTypes, SsNpcs.holgart_island, "Talk-to") { talkHolgartIsland(it) }
        onNpcOpLabel(npcTypes, SsNpcs.bailey, "Talk-to") { talkBailey(it) }
        onNpcOpLabel(npcTypes, SsNpcs.kennith, "Talk-to") { talkKennith(it) }
        onNpcOpLabel(npcTypes, SsNpcs.kent, "Talk-to") { talkKent(it) }
        onNpcOpLabel(npcTypes, SsNpcs.sea_slug, "Take") {
            runQuestSection(null, dialogue, "Taking a Sea slug")
            queueHit(1, HitType.Typeless, SLUG_DAMAGE)
        }

        onLocOpLabel(locTypes, SsLocs.ladder, "Climb-up") { climbUp() }
        onLocOpLabel(locTypes, SsLocs.ladder_top, "Climb-down") {
            telejump(player.coords.translateLevel(-1))
        }
        onLocOpLabel(locTypes, SsLocs.wall_closed, "Kick") { kickWall() }
        onLocOpLabel(locTypes, SsLocs.crane, "Rotate") { rotateCrane() }

        onOpHeldU(SsObjs.broken_glass, SsObjs.damp_sticks) { drySticks() }
        onHeldOpLabel(objTypes, SsObjs.dry_sticks, "Rub-together") { rubSticks() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SEA_SLUG)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(SEA_SLUG, value)
    }

    private fun ProtectedAccess.hasTorch(): Boolean =
        carries(SsObjs.torch_unlit) || carries(SsObjs.torch_lit)

    /* Witchaven */

    private suspend fun ProtectedAccess.talkCaroline(npc: Npc) {
        when (state) {
            0 -> {
                val low = statBase(stats.firemaking) < REQUIRED_FIREMAKING
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player's Firemaking level is less than 30:" to { low },
                                // The start choice sits under ">= 30" in the transcript; offer it
                                // to everyone after the warning, like the other quest starts.
                                "If the player's Firemaking level is 30 or above:" to { true },
                            ),
                        options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                    )
                runQuestSection(npc, dialogue, "Starting off", hooks)
            }
            RESCUED -> {
                runQuestSection(npc, dialogue, "Talking to Caroline")
                invAddOrDrop(objRepo, SsObjs.oyster_pearls)
                statAdvance(stats.fishing, REWARD_XP * xpMods.get(player, stats.fishing))
                completeQuest(SEA_SLUG)
            }
            COMPLETE ->
                startDialogue(npc) {
                    chatNpc(happy, "Thank you again for bringing my family home safely.")
                }
            else -> runQuestSection(npc, dialogue, "Talking to Caroline again")
        }
    }

    private suspend fun ProtectedAccess.talkHolgartLand(npc: Npc) {
        if (state == 0) {
            startDialogue(npc) { chatNpc(neutral, "Hello m'hearty. I've a boat to mend.") }
            return
        }
        if (state < BOAT_FIXED) {
            val paste = carries(SsObjs.swamp_paste)
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player already has one swamp paste in their inventory:" to {
                                paste
                            },
                            "If the player does not have swamp tar:" to { !paste },
                            "If the player has swamp tar:" to { paste },
                        )
                )
            val section = if (state == STARTED) "Talking to Holgart" else "Talking to Holgart again"
            runQuestSection(npc, dialogue, section, hooks)
            if (paste) {
                invDel(inv, SsObjs.swamp_paste)
                setState(BOAT_FIXED)
            } else if (state == STARTED) {
                setState(ASKED_PASTE)
            }
            return
        }
        var sail = false
        val hooks = SimpleQuestHooks(options = mapOf("Will you take me there?" to { sail = true }))
        runQuestSection(npc, dialogue, "Talking to Holgart after fixing the boat", hooks)
        if (sail) telejump(PLATFORM)
    }

    /* The fishing platform */

    private suspend fun ProtectedAccess.talkHolgartPlatform(npc: Npc) {
        when (state) {
            KENNITH -> {
                runQuestSection(npc, dialogue, "Talking to Holgart after talking to Kennith")
                setState(SAILED)
                telejump(ISLAND)
            }
            RESCUED,
            COMPLETE -> {
                runQuestSection(npc, dialogue, "Talking to Holgart (3)")
                telejump(WITCHAVEN)
            }
            else -> {
                var back = false
                val hooks =
                    SimpleQuestHooks(
                        actions =
                            mapOf("Holgart takes the player back to Witchaven." to { back = true })
                    )
                runQuestSection(npc, dialogue, HOLGART_PLATFORM, hooks)
                if (back) telejump(WITCHAVEN)
            }
        }
    }

    private suspend fun ProtectedAccess.talkBailey(npc: Npc) {
        val section =
            when {
                state >= RESCUED -> "Talking to Bailey after freeing Kennith"
                state < KENT -> "Talking to Bailey"
                state == KENT || !hasTorch() -> "Talking to Bailey (2)"
                carries(SsObjs.torch_lit) -> "Talking to Bailey with the lit torch"
                else -> "Talking to Bailey again with the unlit torch"
            }
        val full = inv.isFull()
        val lost = state >= TORCH
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has no space in their inventory:" to { !lost && full },
                        "If the player has one free space in their in their inventory:" to {
                            !lost && !full
                        },
                        "If the player loses their unlit torch:" to { lost },
                    )
            )
        runQuestSection(npc, dialogue, section, hooks)
        when (section) {
            "Talking to Bailey" -> if (state == BOAT_FIXED) setState(BAILEY)
            "Talking to Bailey (2)" -> {
                invAddOrDrop(objRepo, SsObjs.torch_unlit)
                if (state == KENT) setState(TORCH)
            }
        }
    }

    private suspend fun ProtectedAccess.talkKennith(npc: Npc) {
        when {
            state == BAILEY -> {
                runQuestSection(npc, dialogue, "Talking to Kennith")
                setState(KENNITH)
            }
            state < TORCH -> runQuestSection(npc, dialogue, "Subsequent dialogue with Kennith")
            state == TORCH -> runQuestSection(npc, dialogue, "Talking to Kennith (2)")
            state == WALL_BROKEN -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If there are sea slugs or fishermen nearby:" to { false },
                                "If there are no sea slugs or fishermen nearby:" to { true },
                            )
                    )
                runQuestSection(npc, dialogue, KENNITH_HOLE, hooks)
                setState(TOLD_KENNITH)
            }
            else -> startDialogue(npc) { chatNpc(sad, "Ok, when you have I'll come out.") }
        }
    }

    private suspend fun ProtectedAccess.climbUp() {
        if (state in TORCH until RESCUED && !carries(SsObjs.torch_lit)) {
            runQuestSection(null, dialogue, "Attempting to go upstairs with an unlit torch")
            queueHit(1, HitType.Typeless, LADDER_DAMAGE)
            mes("The fishermen won't let you near the ladder without a lit torch.")
            return
        }
        telejump(player.coords.translateLevel(1))
    }

    private suspend fun ProtectedAccess.kickWall() {
        if (state < TORCH) {
            mes("The wall is badly repaired, but you'd best not damage it yet.")
            return
        }
        if (state == TORCH) {
            mes("You kick the loose panel. It gives way, leaving a hole in the wall.")
            setState(WALL_BROKEN)
        }
    }

    private suspend fun ProtectedAccess.rotateCrane() {
        if (state != TOLD_KENNITH) {
            mes("You rotate the crane. Nothing interesting happens.")
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is too far away from the crane:" to { false },
                        "If the player is one tile from the crane:" to { true },
                    )
            )
        runQuestSection(null, dialogue, "Using the crane", hooks)
        setState(RESCUED)
    }

    /* The torch */

    private suspend fun ProtectedAccess.drySticks() {
        invDel(inv, SsObjs.damp_sticks)
        invAdd(inv, SsObjs.dry_sticks)
        mes("You use the broken glass to focus the sun's rays on the damp sticks. They dry out.")
    }

    private suspend fun ProtectedAccess.rubSticks() {
        if (statBase(stats.firemaking) < REQUIRED_FIREMAKING) {
            mes("You need a Firemaking level of $REQUIRED_FIREMAKING to light a fire this way.")
            return
        }
        if (!carries(SsObjs.torch_unlit)) {
            mes("You rub the sticks together but have nothing to light with them.")
            return
        }
        invDel(inv, SsObjs.dry_sticks)
        invDel(inv, SsObjs.torch_unlit)
        invAdd(inv, SsObjs.torch_lit)
        mes("You rub the dry sticks together until they smoulder, and light your torch.")
    }

    /* Kent's island */

    private suspend fun ProtectedAccess.talkHolgartIsland(npc: Npc) {
        if (state == KENT) {
            runQuestSection(npc, dialogue, HOLGART_RETURN)
            telejump(PLATFORM)
            return
        }
        runQuestSection(npc, dialogue, "Talking to Holgart (2)")
    }

    private suspend fun ProtectedAccess.talkKent(npc: Npc) {
        if (state == SAILED) {
            runQuestSection(npc, dialogue, "Talking to Kent")
            setState(KENT)
            return
        }
        runQuestSection(npc, dialogue, "Subsequent dialogue with Kent")
    }

    companion object {
        const val STARTED = 1
        const val ASKED_PASTE = 2
        const val BOAT_FIXED = 3
        const val BAILEY = 4
        const val KENNITH = 5
        const val SAILED = 6
        const val KENT = 7
        const val TORCH = 8
        const val WALL_BROKEN = 9
        const val TOLD_KENNITH = 10
        const val RESCUED = 11
        const val COMPLETE = 12

        private const val REQUIRED_FIREMAKING = 30
        private const val REWARD_XP = 7175.0
        private const val SLUG_DAMAGE = 3
        private const val LADDER_DAMAGE = 4

        private val WITCHAVEN = CoordGrid(2722, 3302, 0)
        private val PLATFORM = CoordGrid(2782, 3284, 1)
        private val ISLAND = CoordGrid(2798, 3317, 0)

        private const val HOLGART_PLATFORM = "Talking to Holgart at the Fishing Platform"
        private const val KENNITH_HOLE = "Talking to Kennith after making a hole in the wall"
        private const val HOLGART_RETURN =
            "Talking to Holgart again before returning to the fishing platform"

        val SEA_SLUG =
            QuestDef(
                name = "Sea Slug",
                varp = { SsVarps.seaslugquest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf("7,175 Fishing XP", "Oyster pearls", "Access to the Fishing Platform"),
                scrollObj = { SsObjs.sea_slug },
            )
    }
}

// Every cache ref Sea Slug uses (re-check after cache updates).

internal object SsNpcs : NpcReferences() {
    val caroline = find("caroline")

    /* The holgartland multinpc's children (it shows the no-travel one throughout). */
    val holgart_land = listOf(find("holgartlandnotravel"), find("holgartlandtravel"))
    val holgart_platform = find("holgartplatform")
    val holgart_island = find("holgartoutwater")
    val bailey = find("bailey")

    /* Child of the kennith / kennith_land multinpcs. */
    val kennith = find("kennith_platform")

    /* Child of the kent_land multinpc (home from state 11); also spawned on the island. */
    val kent = find("kent")
    val sea_slug = find("seaslug")
}

internal object SsLocs : LocReferences() {
    val ladder = find("seaslug_ladder")
    val ladder_top = find("seaslug_ladder_top")

    /* Child of the slug_breakable_panel multiloc (seaslug_wall_open from state 9). */
    val wall_closed = find("seaslug_wall_closed")
    val crane = find("seaslug_crane")
}

internal object SsObjs : ObjReferences() {
    val swamp_paste = find("swamppaste")
    val damp_sticks = find("damp_sticks")
    val dry_sticks = find("dry_sticks")
    val broken_glass = find("broken_glass")
    val torch_unlit = find("torch_unlit")
    val torch_lit = find("torch_lit")
    val oyster_pearls = find("bigoysterpearls")
    val sea_slug = find("seasluginv")
}

internal object SsVarps : VarpReferences() {
    val seaslugquest = find("seaslugquest")
}
