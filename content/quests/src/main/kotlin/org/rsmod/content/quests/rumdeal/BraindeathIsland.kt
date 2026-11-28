package org.rsmod.content.quests.rumdeal

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.events.interact.NpcUEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.COMPLETE
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.MET
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.PATCH_GROWN
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.PATCH_PLANTED
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.PATCH_RAKED
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.PICKED
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.RUM_DEAL
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.WATER
import org.rsmod.content.quests.rumdeal.RumDeal.Companion.WATER_FETCHED
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Braindeath Island (OSRS Wiki), the side content around [RumDeal]:
 * - the brewery's ladders and wooden stairs, the volcano gate past 50% Luke (Wiki "Using the gate
 *   near 50% Luke": a random distraction going out, a telling-off coming back; closed until
 *   Braindeath asks for stagnant water) and the Stagnant Lake (bucket -> bucket of water);
 * - the blindweed patch (`deal_farming`): rake (Farming's rake / dibber / pick animations, a weeds
 *   item per stage), plant with a seed dibber and Farming 40, grow for three minutes (soft timer
 *   `rumdeal_blindweed`, restarted on login), pick; the trashed patches only refuse;
 * - the basement cupboard (rake, seed dibber, watering can);
 * - the island fishing spots (`deal_squid`): fishbowl and net, Fishing 50, no xp; sluglings, or a
 *   karamthulhu one time in four (authored rate); untangling and re-tangling the fishbowl and net;
 * - the crew: brewers 1-8 (quest and post-quest lines), zombie protesters, zombie swabs (Talk-to
 *   refuses, Intimidate plays that swab's Wiki insult once, `deal_multi_pirate_*`), 50% Luke (ring
 *   of Charos(a) story, the tinderbox).
 *
 * Simplified: intimidated swabs still attack (only the varbit and the dialogue are tracked); the
 * kitten censor is the Wiki's text box, not the picture; Luke's tinderbox "500 hit combo" is a
 * message box; stairs land on the nearest free tile past the stair (no per-stair table).
 */
class BraindeathIsland
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val collision: CollisionFlagMap,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(RumDeal::class.java, "rum_deal") }
    private val luke by lazy { QuestDialogue.load(RumDeal::class.java, "rum_deal_luke") }
    private val brewersAfter by lazy { QuestDialogue.load(RumDeal::class.java, "rum_deal_brewer") }

    override fun ScriptContext.startup() {
        // The crew.
        for ((index, brewer) in RdNpcs.brewers.withIndex()) npcOp(brewer, "Talk-to") { talkBrewer(it, index + 1) }
        for (protester in RdNpcs.protesters) npcOp(protester, "Talk-to") { runQuestSection(it, dialogue, "Talking to Zombie protesters") }
        for ((index, swab) in RdNpcs.swabs.withIndex()) {
            npcOp(swab, "Talk-to") { runQuestSection(null, dialogue, "Trying to talk to Zombie Swabs") }
            npcOp(swab, "Intimidate") { intimidate(it, index) }
        }
        npcOp(RdNpcs.luke, "Talk-to") { runQuestSection(it, luke, "Standard dialogue", lukeHooks(carries(RdObjs.tinderbox))) }
        npcU(RdNpcs.luke, RdObjs.tinderbox) { runQuestBranch(it, luke, "Standard dialogue", "How flammable are you?", lukeHooks(true)) }

        // Getting around.
        locOp(RdLocs.ladder_up, "Climb-up") { climbLadder(1) }
        locOp(RdLocs.ladder_down, "Climb-down") { climbLadder(-1) }
        locOp(RdLocs.stairs_up, "Climb-up") { climbStairs(it, 1) }
        locOp(RdLocs.stairs_down, "Climb-down") { climbStairs(it, -1) }
        locOp(RdLocs.gate, "Open") { openGate(it) }
        locU(RdLocs.stagnant, RdObjs.bucket) { fillStagnant() }

        // The blindweed patch and the trashed ones.
        for (weeds in RdLocs.patch_weeds) {
            locU(weeds, RdObjs.rake) { rakePatch() }
            locU(weeds, RdObjs.seed) { mes("This patch needs weeding first.") } // Authored (not Wiki).
        }
        for (patch in RdLocs.patch_weeds + listOf(RdLocs.patch_empty, RdLocs.patch_seed, RdLocs.patch_grown)) {
            locOp(patch, "Inspect") { inspectPatch() }
        }
        locU(RdLocs.patch_empty, RdObjs.seed) { plant() }
        locOp(RdLocs.patch_grown, "Pick") { pick() }
        locOp(RdLocs.trashed_patch, "Inspect") { runQuestSection(null, dialogue, "Inspecting trashed patches") }
        locU(RdLocs.trashed_patch, RdObjs.rake) { runQuestSection(null, dialogue, "Attempting to rake a trashed patch") }
        onPlayerSoftTimer(RdTimers.blindweed) { grow(player) }
        onPlayerLogin {
            if (player.vars[RdVarBits.farming] == PATCH_PLANTED) player.softTimer(RdTimers.blindweed, GROW_TICKS)
        }

        // The basement cupboard.
        locOp(RdLocs.cupboard, "Open") { locRepo.change(it, RdLocs.cupboard_open, CUPBOARD_TICKS) }
        locOp(RdLocs.cupboard_open, "Shut") { locRepo.change(it, RdLocs.cupboard, CUPBOARD_TICKS) }
        locOp(RdLocs.cupboard_open, "Search") { searchCupboard() }

        // Fishing for sluglings.
        npcOp(RdNpcs.squid_spot, "Fish") { fish() }
        onHeldOpLabel(objTypes, RdObjs.fishbowl_net, "Untangle") { untangle() }
        heldU(RdObjs.tiny_net, RdObjs.fishbowl) { retangle() }
        onHeldOpLabel(objTypes, RdObjs.stagnant_bucket, "Empty") {
            invReplace(inv, RdObjs.stagnant_bucket, 1, RdObjs.bucket)
            mes("You empty the bucket.") // Authored (not Wiki).
        }
    }

    /* Guarded binds */

    private fun ScriptContext.locOp(loc: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(loc, op)) return
        onLocOpLabel(locTypes, loc, label, action)
    }

    private fun ScriptContext.npcOp(npc: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[npc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isNpcOpBound(npc, op)) return
        onNpcOpLabel(npcTypes, npc, label, action)
    }

    private fun ScriptContext.locU(loc: LocType, obj: ObjType, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (eventBus.contains(LocUEvents.Op::class.java, EventBus.composeLongKey(loc.id, obj.id))) return
        onOpLocU(loc, obj) { action(it.loc) }
    }

    private fun ScriptContext.npcU(npc: NpcType, obj: ObjType, action: suspend ProtectedAccess.(Npc) -> Unit) {
        if (eventBus.contains(NpcUEvents.Op::class.java, EventBus.composeLongKey(npc.id, obj.id))) return
        onOpNpcU(npc, obj) { action(it.npc) }
    }

    private fun ScriptContext.heldU(a: ObjType, b: ObjType, action: suspend ProtectedAccess.() -> Unit) {
        if (eventBus.contains(HeldUEvents.Type::class.java, EventBus.composeLongKey(a.id, b.id))) return
        if (eventBus.contains(HeldUEvents.Type::class.java, EventBus.composeLongKey(b.id, a.id))) return
        onOpHeldU(a, b) { action() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(RUM_DEAL)

    private fun ProtectedAccess.hasFlag(flag: Int): Boolean = (player.vars[RdVarps.progress] and flag) != 0

    private fun ProtectedAccess.setFlag(flag: Int) {
        VarPlayerIntMapSetter.set(player, RdVarps.progress, player.vars[RdVarps.progress] or flag)
    }

    /* The crew */

    private suspend fun ProtectedAccess.talkBrewer(npc: Npc, number: Int) {
        if (state >= COMPLETE) runQuestSection(npc, brewersAfter, "Brewer $number") else runQuestSection(npc, dialogue, "Brewer $number")
    }

    private suspend fun ProtectedAccess.intimidate(npc: Npc, index: Int) {
        val varbit = RdVarBits.swabs[index]
        if (player.vars[varbit] != 0) {
            runQuestSection(null, dialogue, "Trying to re-intimidate a Zombie Swab")
            return
        }
        runQuestSection(npc, dialogue, "Zombie Swab ${index + 1}")
        VarPlayerIntMapSetter.set(player, varbit, 1)
    }

    private fun ProtectedAccess.lukeHooks(tinderbox: Boolean): SimpleQuestHooks {
        val ring = RdObjs.charos in player.worn
        return SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player is not wearing a Ring of Charos:" to { !ring },
                    "If the player is wearing a Ring of Charos:" to { ring },
                    "If the player does not have a tinderbox:" to { !tinderbox },
                    "If the player has a tinderbox:" to { tinderbox },
                ),
            actions = mapOf(LUKE_COMBO to { mesbox("One 500 hit combo later...") }),
        )
    }

    /* Getting around */

    private suspend fun ProtectedAccess.climbLadder(levels: Int) {
        delay(1)
        telejump(player.coords.translateLevel(levels))
    }

    /** Lands past the stair, away from where the player stood (no per-stair table). */
    private suspend fun ProtectedAccess.climbStairs(loc: BoundLocInfo, levels: Int) {
        val centre = loc.coords.translate((loc.adjustedWidth - 1) / 2, (loc.adjustedLength - 1) / 2)
        val dx = (centre.x - player.coords.x).coerceIn(-1, 1)
        val dz = (centre.z - player.coords.z).coerceIn(-1, 1)
        val target = centre.translate(dx * 2, dz * 2, levels)
        delay(1)
        telejump(collision.nearestWalkable(target, radius = 3))
    }

    private suspend fun ProtectedAccess.openGate(gate: BoundLocInfo) {
        // The gate is on the north edge of its tile: south is the compound, north the volcano path.
        val leaving = player.coords.z <= gate.coords.z
        if (leaving) {
            if (state < WATER) {
                runQuestSection(null, dialogue, "Trying to sneak past 50% Luke too early", speakers = lukeSpeakers())
                return
            }
            runNodes(pickRandom(luke.section("From the south side")))
        } else {
            runNodes(luke.section("From the north side"))
        }
        passThroughWall(gate)
    }

    private fun lukeSpeakers(): Map<String, NpcType> = mapOf("50% Luke" to npcTypes[RdNpcs.luke])

    private suspend fun ProtectedAccess.runNodes(nodes: List<Node>) {
        startDialogue { QuestDialogueRunner(SimpleQuestHooks(), lukeSpeakers()).run(this, nodes) }
    }

    /** A copy of [nodes] keeping one random option of each Wiki {{trandom}} "Dialogue N" group. */
    private fun pickRandom(nodes: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            if (node is Node.Option && node.label.startsWith(RANDOM_PREFIX)) {
                val group = nodes.drop(index).takeWhile { it is Node.Option && it.label.startsWith(RANDOM_PREFIX) }
                index += group.size
                val pick = group[random.of(0, group.size - 1)]
                // The picked branch runs inline (a lone option would also run, but be shown as one).
                out.addAll(pick.children)
                continue
            }
            out += node
            index++
        }
        return out
    }

    private suspend fun ProtectedAccess.fillStagnant() {
        if (state != WATER) {
            mes("You don't need any stagnant water right now.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Using a bucket on Stagnant Lake")
        invReplace(inv, RdObjs.bucket, 1, RdObjs.stagnant_bucket)
        setFlag(WATER_FETCHED)
    }

    /* The blindweed patch */

    private suspend fun ProtectedAccess.inspectPatch() {
        val stage = player.vars[RdVarBits.farming]
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not planted the seed yet:" to { stage < PATCH_PLANTED },
                        "If the player already planted the seed:" to { stage == PATCH_PLANTED },
                        "If the blindweed is fully grown:" to { stage >= PATCH_GROWN },
                    )
            )
        runQuestSection(null, dialogue, "Inspecting the Blindweed patch", hooks)
    }

    private suspend fun ProtectedAccess.rakePatch() {
        if (!player.hasTool(RdObjs.rake)) {
            mes("You need a rake to weed a farming patch.")
            return
        }
        while (player.vars[RdVarBits.farming] < PATCH_RAKED) {
            anim(RdSeqs.rake)
            delay(RAKE_TICKS)
            VarPlayerIntMapSetter.set(player, RdVarBits.farming, player.vars[RdVarBits.farming] + 1)
            if (!inv.isFull()) invAdd(inv, RdObjs.weeds)
        }
        resetAnim()
        mes("You've cleared the patch of weeds.")
    }

    private suspend fun ProtectedAccess.plant() {
        if (state != MET || hasFlag(PICKED)) {
            mes("You don't need to plant anything here now.") // Authored (not Wiki).
            return
        }
        if (!player.hasTool(RdObjs.dibber)) {
            mes("You need a seed dibber to plant seeds.") // Authored (not Wiki).
            return
        }
        val level = stat(stats.farming) >= FARMING_REQ
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have level 40 Farming:" to { !level },
                        "If the player has level 40 Farming:" to { level },
                    )
            )
        if (level) {
            anim(RdSeqs.dibbing)
            delay(PLANT_TICKS)
        }
        runQuestSection(null, dialogue, "Planting Blindweed", hooks)
        if (!level) return
        // Wiki: the whole seed stack goes in.
        invDel(inv, RdObjs.seed, invTotal(inv, RdObjs.seed))
        VarPlayerIntMapSetter.set(player, RdVarBits.farming, PATCH_PLANTED)
        player.softTimer(RdTimers.blindweed, GROW_TICKS)
    }

    private fun grow(player: Player) {
        player.clearSoftTimer(RdTimers.blindweed)
        if (player.vars[RdVarBits.farming] != PATCH_PLANTED) return
        VarPlayerIntMapSetter.set(player, RdVarBits.farming, PATCH_GROWN)
        player.mes("I wonder how my Blindweed is coming along...")
    }

    private suspend fun ProtectedAccess.pick() {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        anim(RdSeqs.pick)
        delay(1)
        invAdd(inv, RdObjs.blindweed)
        VarPlayerIntMapSetter.set(player, RdVarBits.farming, PATCH_RAKED)
        setFlag(PICKED)
        mes("You pick the Blindweed.") // Authored (not Wiki).
    }

    /* The basement cupboard */

    private suspend fun ProtectedAccess.searchCupboard() {
        val hasCan = RdObjs.watering_cans.any { carries(it) }
        val tools = listOf(RdObjs.rake to carries(RdObjs.rake), RdObjs.dibber to carries(RdObjs.dibber), RdObjs.watering_can to hasCan)
        val missing = tools.filter { !it.second }.map { it.first }
        var picked: ObjType? = null
        var all = false
        fun option(obj: ObjType): suspend () -> Unit = { picked = obj }
        val hooks =
            SimpleQuestHooks(
                defaultCondition = { text -> cupboardCondition(text, all, missing, hasCan) },
                options =
                    mapOf(
                        "A rake" to option(RdObjs.rake),
                        "A seed dibber" to option(RdObjs.dibber),
                        "A watering can" to option(RdObjs.watering_can),
                        "All of the above!" to { all = true },
                    ),
            )
        runQuestSection(null, dialogue, "Searching the basement cupboard for farming supplies", hooks)
        val one = picked
        when {
            all -> if (missing.isNotEmpty() && inv.freeSpace() >= missing.size) for (obj in missing) invAdd(inv, obj)
            one != null -> if (one in missing && !inv.isFull()) invAdd(inv, one)
        }
    }

    private fun ProtectedAccess.cupboardCondition(text: String, all: Boolean, missing: List<ObjType>, hasCan: Boolean): Boolean =
        when (text) {
            "If the player does not have inventory space:" ->
                if (all) missing.isNotEmpty() && inv.freeSpace() < missing.size else inv.isFull()
            "If the player already has a rake:" -> carries(RdObjs.rake)
            "If the player already has a seed dibber:" -> carries(RdObjs.dibber)
            "If the player already has a watering can:" -> hasCan
            "If the player already has all the tools:" -> missing.isEmpty()
            "If the player has inventory space:" -> true
            else -> false
        }

    /* Fishing */

    private suspend fun ProtectedAccess.fish() {
        if (state >= COMPLETE) {
            mes("You have no need to do that any more.")
            return
        }
        when {
            !carries(RdObjs.fishbowl_net) -> runQuestSection(null, dialogue, "Using a fishing spot without fishbowl and net")
            inv.isFull() -> runQuestSection(null, dialogue, "Using a fishing spot without inventory space")
            stat(stats.fishing) < FISHING_REQ -> runQuestSection(null, dialogue, "Using a fishing spot without level 50 Fishing")
            else -> {
                anim(RdSeqs.smallnet)
                delay(FISH_TICKS)
                val thulhu = random.of(0, KARAM_CHANCE - 1) == 0
                val hooks =
                    SimpleQuestHooks(
                        rewrites = { text ->
                            // Authored (not Wiki): the karamthulhu catch line.
                            if (thulhu) text.replace("some Sluglings!", "a Karamthulhu!") else text
                        }
                    )
                runQuestSection(null, dialogue, "Using a fishing spot with the correct requirements", hooks)
                resetAnim()
                invAdd(inv, if (thulhu) RdObjs.karamthulhu else RdObjs.slugling)
            }
        }
    }

    private fun ProtectedAccess.untangle() {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invReplace(inv, RdObjs.fishbowl_net, 1, RdObjs.fishbowl)
        invAdd(inv, RdObjs.tiny_net)
        mes("You untangle the fishbowl from the net.") // Authored (not Wiki).
    }

    private fun ProtectedAccess.retangle() {
        invDel(inv, RdObjs.tiny_net)
        invReplace(inv, RdObjs.fishbowl, 1, RdObjs.fishbowl_net)
        mes("You tangle the fishbowl back into the net.") // Authored (not Wiki).
    }

    private companion object {
        const val FARMING_REQ = 40
        const val FISHING_REQ = 50
        const val GROW_TICKS = 300 // Wiki: "wait a few minutes".
        const val RAKE_TICKS = 4
        const val PLANT_TICKS = 2
        const val FISH_TICKS = 4
        const val KARAM_CHANCE = 4
        const val CUPBOARD_TICKS = 300
        const val RANDOM_PREFIX = "Dialogue "
        const val LUKE_COMBO = "The screen turns black and a message appears saying \"One 500 hit combo later...\""
    }
}
