package org.rsmod.content.quests.gertrudescat

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
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
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Gertrude's Cat (OSRS Wiki). `fluffs` varp (cache db end state 6; the cache swaps Gertrude to
 * her kitten-selling form at 6). Ours: 1 Gertrude's lost cat, 2 her sons told you about the
 * Lumber Yard (100 coins), 3 Fluffs had her milk, 4 her seasoned sardine (doogle leaves rubbed
 * on a raw sardine), 5 her lost kitten found in the yard's crates and returned, 6 complete (a
 * kitten, a chocolate cake, a stew, 1,525 Cooking xp, 1 QP).
 *
 * Not done: the mewing crate npcs (any of the yard's crates can hold the kitten, 1 in 4 per
 * search); Gertrude's post-quest kitten shop; the kitten is a plain obj (no growing pet).
 */
class GertrudesCat
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(GertrudesCat::class.java, "gertrudes_cat") }

    override fun ScriptContext.startup() {
        // Gertrude is shared (QuestTalk): A Ruff Situation talks to her too.
        with(QuestTalk) {
            default(npcTypes, GcNpcs.gertrude) { talkGertrude(it) }
            default(npcTypes, GcNpcs.gertrude_post) {
                startDialogue(it) { chatNpc(happy, "Fluffs and her kitten are doing wonderfully!") }
            }
        }
        for (son in GcNpcs.sons) {
            onNpcOpLabel(npcTypes, son, "Talk-to") { talkSons(it) }
        }
        onNpcOpLabel(npcTypes, GcNpcs.fluffs, "Pick-up") { pickUpFluffs(it) }
        onNpcOpLabel(npcTypes, GcNpcs.fluffs, "Stroke") {
            runQuestSection(it, dialogue, "Stroke Fluffs")
        }
        onNpcOpLabel(npcTypes, GcNpcs.fluffs, "Talk-to") {
            runQuestSection(it, dialogue, "Talk-to Fluffs")
        }
        onOpNpcU(GcNpcs.fluffs, GcObjs.bucket_milk) { feedMilk(it.npc) }
        onOpNpcU(GcNpcs.fluffs, GcObjs.seasoned_sardine) { feedSardine(it.npc) }
        onOpNpcU(GcNpcs.fluffs, GcObjs.kitten) { returnKitten(it.npc) }
        onOpHeldU(GcObjs.doogle_leaves, GcObjs.raw_sardine) { seasonSardine() }
        onLocOpLabel(locTypes, GcLocs.fence, "Climb-over") { climbFence(it) }
        onLocOpLabel(locTypes, GcLocs.crate, "Search") { searchCrate() }
        onLocOpLabel(locTypes, GcLocs.barrel, "Search") { mes("The barrel is empty.") }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(GERTRUDES_CAT)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(GERTRUDES_CAT, value)
    }

    /* Gertrude and her sons */

    private suspend fun ProtectedAccess.talkGertrude(npc: Npc) {
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
        val section =
            when (state) {
                0 -> "Starting off"
                STARTED -> "Talking to Gertrude again"
                TOLD -> "Talking to Gertrude again (2)"
                MILKED -> "Talking to Gertrude again (3)"
                FED -> "Talking to Gertrude after giving Fluffs the sardine"
                else -> "Finishing up"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (section != "Finishing up") return
        invAddOrDrop(objRepo, GcObjs.kittens.random())
        invAddOrDrop(objRepo, GcObjs.chocolate_cake)
        invAddOrDrop(objRepo, GcObjs.stew)
        statAdvance(stats.cooking, REWARD_XP * xpMods.get(player, stats.cooking))
        completeQuest(GERTRUDES_CAT)
    }

    private suspend fun ProtectedAccess.talkSons(npc: Npc) {
        if (state == 0 || state == COMPLETE) {
            startDialogue(npc) { chatNpc(neutral, "What do you want? We're busy playing.") }
            return
        }
        if (state > STARTED) {
            val section =
                if (state == TOLD) {
                    "Talking to them again"
                } else {
                    "Talking to Shilop and Wilough again"
                }
            runQuestSection(npc, dialogue, section)
            return
        }
        var pay = false
        val hooks = SimpleQuestHooks(options = mapOf("Okay then, I'll pay." to { pay = true }))
        runQuestSection(npc, dialogue, "Talking to Shilop and Wilough", hooks)
        if (!pay) return
        if (!carries(GcObjs.coins, TIP)) {
            mes("You don't have $TIP coins.")
            return
        }
        invDel(inv, GcObjs.coins, TIP)
        mes("The boys tell you their secret playground is the Lumber Yard, north-east of Varrock.")
        setState(TOLD)
    }

    /* Fluffs */

    private suspend fun ProtectedAccess.pickUpFluffs(npc: Npc) {
        val section =
            when (state) {
                MILKED -> "Pick-up Fluffs (2)"
                FED -> "Pick-up Fluffs (3)"
                else -> "Pick-up Fluffs"
            }
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.feedMilk(npc: Npc) {
        if (state != TOLD) {
            mes("The cat doesn't want that right now.")
            return
        }
        invDel(inv, GcObjs.bucket_milk)
        invAdd(inv, GcObjs.bucket)
        runQuestSection(npc, dialogue, "Giving Fluffs milk")
        setState(MILKED)
    }

    private suspend fun ProtectedAccess.feedSardine(npc: Npc) {
        if (state != MILKED) {
            mes("The cat doesn't want that right now.")
            return
        }
        invDel(inv, GcObjs.seasoned_sardine)
        runQuestSection(npc, dialogue, "Giving the seasoned sardines to Fluffs")
        setState(FED)
    }

    private suspend fun ProtectedAccess.returnKitten(npc: Npc) {
        if (state != FED) return
        invDel(inv, GcObjs.kitten)
        runQuestSection(npc, dialogue, "Returning the kitten to Fluffs")
        setState(RETURNED)
    }

    private suspend fun ProtectedAccess.seasonSardine() {
        invDel(inv, GcObjs.doogle_leaves)
        invDel(inv, GcObjs.raw_sardine)
        invAdd(inv, GcObjs.seasoned_sardine)
        runQuestSection(null, dialogue, "Making seasoned sardines")
    }

    /* The Lumber Yard */

    private suspend fun ProtectedAccess.climbFence(fence: BoundLocInfo) {
        val at = fence.coords
        telejump(if (player.coords.z <= at.z) at.translate(0, 1) else at.translate(0, -1))
    }

    private suspend fun ProtectedAccess.searchCrate() {
        if (state != FED || carries(GcObjs.kitten)) {
            mes("You search the crate... but find nothing.")
            return
        }
        if (random.of(1, KITTEN_ODDS) != 1) {
            mes("You search the crate... You can hear kittens mewing nearby.")
            return
        }
        if (inv.isFull()) {
            mes("You find a kitten, but you don't have room to carry it.")
            return
        }
        runQuestSection(null, dialogue, "Searching crates")
        invAdd(inv, GcObjs.kitten)
    }

    companion object {
        const val STARTED = 1
        const val TOLD = 2
        const val MILKED = 3
        const val FED = 4
        const val RETURNED = 5
        const val COMPLETE = 6

        private const val TIP = 100
        private const val KITTEN_ODDS = 4
        private const val REWARD_XP = 1525.0

        val GERTRUDES_CAT =
            QuestDef(
                name = "Gertrude's Cat",
                varp = { GcVarps.fluffs },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,525 Cooking XP", "A kitten", "Chocolate cake", "Stew"),
                scrollObj = { GcObjs.kitten },
            )
    }
}

// Every cache ref Gertrude's Cat uses (re-check after cache updates).

internal object GcNpcs : NpcReferences() {
    /* Children of the gertrude multinpc (fluffs varp: quest form until 6). */
    val gertrude = find("gertrude_quest")
    val gertrude_post = find("gertrude_post")
    val sons = listOf(find("shilop"), find("wilough"))
    val fluffs = find("gertrudescat")
}

internal object GcLocs : LocReferences() {
    val fence = find("gertrudefence")
    val crate = find("gertrudeempty_crate")
    val barrel = find("gertrudeempty_barrel")
}

internal object GcObjs : ObjReferences() {
    val kitten = find("gertrudekittens")
    val seasoned_sardine = find("seasoned_sardine")
    val doogle_leaves = find("doogleleaves")
    val raw_sardine = find("raw_sardine")
    val bucket_milk = find("bucket_milk")
    val bucket = find("bucket_empty")
    val coins = find("coins")
    val chocolate_cake = find("chocolate_cake")
    val stew = find("stew")

    /** The reward kitten's colours. */
    val kittens: List<ObjType> =
        listOf(
            find("kittenobject"),
            find("kittenobject_light"),
            find("kittenobject_brown"),
            find("kittenobject_black"),
            find("kittenobject_browngrey"),
            find("kittenobject_bluegrey"),
        )
}

internal object GcVarps : VarpReferences() {
    val fluffs = find("fluffs")
}
