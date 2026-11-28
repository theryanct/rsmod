package org.rsmod.content.other.trails

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.invs
import org.rsmod.api.death.ClueDrops
import org.rsmod.api.player.dig.DigSpots
import org.rsmod.api.player.events.CollectionLogObtained
import org.rsmod.api.player.events.EmoteSelected
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.UpdateInventory
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.interf.InterfaceType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.util.UncheckedType
import org.rsmod.game.type.varp.VarpType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Treasure trails, Beginner and Easy (Medium+ later). Step data is Jagex's own: the 243 cache's
 * clue-helper db tables ([ClueSteps]), so texts match the game and RuneLite's clue helper.
 *
 * - Clues drop from npcs ([ClueDrops], one per tier: none while you hold one in inventory/bank).
 *   Easy clue objs are one obj per step (param 623 = the step's dbrow); the single beginner obj
 *   keeps its step in [TrailVarps.beginner_step].
 * - "Read" shows the step (trail_cluetext, or the map interface); "Check steps" the steps left.
 * - Solving: talk to the npc / search the loc ([OpInterceptors], so the npc's own script is
 *   untouched for everyone else), dig at the spot ([DigSpots]), or perform the emote at the
 *   spot wearing the outfit, then talk to Uri. Beginner extras: Reldo's strange device (hot/cold)
 *   and Charlie the Tramp's item requests.
 * - The last step gives a reward casket; "Open" rolls the Wiki tables ([TrailRewards]) and lists
 *   the rewards on trail_rewardscreen. Rewards feed the collection log.
 */
class TrailScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val dbRows: DbRowTypeList,
    private val names: NameMapping,
    private val invTypes: InvTypeList,
    private val npcTypes: NpcTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
    private val eventBus: EventBus,
    private val locTypes: LocTypeList,
) : PluginScript() {
    private val steps by lazy { ClueSteps.load(dbRows, names.dbTables) }

    private val rewards by lazy { ClueTier.entries.associateWith { TrailRewards.load(it) } }

    /** Easy clue objs by step dbrow, and the reverse. */
    private val easyObjByStep by lazy {
        val out = HashMap<Int, Int>()
        for (type in objTypes.values) {
            if (type.isCert || type.isPlaceholder || type.name != "Clue scroll (easy)") {
                continue
            }
            val row = type.paramMap?.primitiveMap?.get(STEP_PARAM) as? Int ?: continue
            if (steps.byRow[row]?.tier == ClueTier.Easy) {
                out.putIfAbsent(row, type.id)
            }
        }
        out
    }
    private val easyStepByObj by lazy {
        easyObjByStep.entries.associate { (row, obj) -> obj to row }
    }

    /** Steps a new trail can start on / continue with (sub-steps excluded). */
    private val pools by lazy {
        mapOf(
            ClueTier.Beginner to
                steps.ofTier(
                    ClueTier.Beginner,
                    setOf(ClueKind.Anagram, ClueKind.Cryptic, ClueKind.Emote, ClueKind.Map),
                ),
            ClueTier.Easy to
                steps.ofTier(ClueTier.Easy, setOf(ClueKind.Cryptic, ClueKind.Emote, ClueKind.Map))
                    .filter { it.row in easyObjByStep },
        )
    }

    private val reldoRow by lazy { names.dbRows["cluehelper_cryptic_beginner_reldo"] }
    private val charlieRow by lazy { names.dbRows["cluehelper_cryptic_beginner_charlie_the_tramp"] }
    private val charlieItems by lazy {
        val items =
            listOf(
                TrailObjs.trout,
                TrailObjs.pike,
                TrailObjs.raw_herring,
                TrailObjs.raw_trout,
                TrailObjs.iron_ore,
                TrailObjs.iron_dagger,
                TrailObjs.leather_body,
                TrailObjs.leather_chaps,
            )
        items.withIndex().mapNotNull { (index, obj) ->
            names.dbRows["cluehelper_skillchallenge_beginner_$index"]?.let { it to obj }
        }.toMap()
    }

    /** Uri, spawned for one player's emote step. */
    private val uris = WeakHashMap<Player, Npc>()

    override fun ScriptContext.startup() {
        logger.info {
            "Treasure trails: ${steps.byRow.size} steps; pools " +
                pools.entries.joinToString { "${it.key.display}=${it.value.size}" } +
                "; easy clue objs ${easyObjByStep.size}."
        }

        bindClue(TrailObjs.clue_beginner)
        for (obj in easyStepByObj.keys) {
            bindClue(objTypes[obj] ?: continue)
        }
        onOpHeld1(TrailObjs.casket_beginner) { openCasket(ClueTier.Beginner, it.obj) }
        onOpHeld1(TrailObjs.casket_easy) { openCasket(ClueTier.Easy, it.obj) }
        onOpHeld1(TrailObjs.strange_device) { feelDevice() }

        OpInterceptors.register(NpcClaim())
        OpInterceptors.register(LocClaim())
        DigSpots.register { dig() }
        onEvent<EmoteSelected> { player.emote(slot) }

        ClueDrops.resolver = ClueDrops.Resolver { hero, tier -> resolveDrop(hero, tier) }
    }

    private fun ScriptContext.bindClue(obj: ObjType) {
        if (!isHeldOpBound(obj, 1)) {
            onOpHeld1(obj) { read(it.obj) }
        }
        if (!isHeldOpBound(obj, 2)) {
            onOpHeld2(obj) { checkSteps(it.obj) }
        }
    }

    /* State */

    private fun tierOf(obj: Int): ClueTier? =
        when {
            obj == objTypes[TrailObjs.clue_beginner].id -> ClueTier.Beginner
            obj in easyStepByObj -> ClueTier.Easy
            else -> null
        }

    private fun remainingVarp(tier: ClueTier): VarpType? =
        when (tier) {
            ClueTier.Beginner -> TrailVarps.beginner_remaining
            ClueTier.Easy -> TrailVarps.easy_remaining
            else -> null
        }

    private fun completedVarp(tier: ClueTier): VarpType? =
        when (tier) {
            ClueTier.Beginner -> TrailVarps.beginner_completed
            ClueTier.Easy -> TrailVarps.easy_completed
            else -> null
        }

    private fun Player.setVar(varp: VarpType, value: Int) =
        VarPlayerIntMapSetter.set(this, varp, value)

    /** The step for a held clue [obj], choosing the beginner step / trail length on first use. */
    private fun Player.stepOf(obj: Int): ClueStep? {
        val tier = tierOf(obj) ?: return null
        val remaining = remainingVarp(tier) ?: return null
        if (vars[remaining] <= 0) {
            setVar(remaining, random.of(tier.steps))
        }
        if (tier == ClueTier.Easy) {
            return steps.byRow[easyStepByObj[obj] ?: return null]
        }
        val current = steps.byRow[vars[TrailVarps.beginner_step]]
        if (current != null && current.tier == ClueTier.Beginner) {
            return current
        }
        val pick = pools.getValue(ClueTier.Beginner).pick() ?: return null
        setVar(TrailVarps.beginner_step, pick.row)
        return pick
    }

    /** Every clue the player is holding, with its step. */
    private fun Player.heldSteps(): List<Pair<InvObj, ClueStep>> =
        inv.mapNotNull { obj -> obj?.let { o -> stepOf(o.id)?.let { o to it } } }

    private fun Player.hasClue(tier: ClueTier): Boolean {
        val bank = invMap[invs.bank]
        val inInv = inv.any { it != null && tierOf(it.id) == tier }
        val inBank = bank?.any { it != null && tierOf(it.id) == tier } == true
        return inInv || inBank
    }

    /* Drops */

    private fun resolveDrop(hero: Player, key: String): UnpackedObjType? {
        val tier = ClueTier.byKey(key) ?: return null
        if (hero.hasClue(tier)) {
            return null
        }
        return when (tier) {
            ClueTier.Beginner -> {
                hero.setVar(TrailVarps.beginner_step, 0)
                hero.setVar(TrailVarps.beginner_remaining, 0)
                objTypes[TrailObjs.clue_beginner]
            }
            ClueTier.Easy -> {
                hero.setVar(TrailVarps.easy_remaining, 0)
                val step = pools.getValue(ClueTier.Easy).pick() ?: return null
                objTypes[easyObjByStep.getValue(step.row)]
            }
            else -> null
        }
    }

    /* Reading */

    private suspend fun ProtectedAccess.read(obj: UnpackedObjType) {
        val step = player.stepOf(obj.id) ?: return mes("Nothing interesting happens.")
        when (step.kind) {
            ClueKind.Map -> {
                val map = MapInterfaces.forDig(step.target)
                if (map != null) {
                    ifOpenMainModal(map)
                } else {
                    showText("The map shows: ${(step.target as? ClueTarget.Coord)?.description}")
                }
            }
            ClueKind.Anagram ->
                showText(
                    "This anagram reveals<br>who to speak to next:<br><br>${step.text.uppercase()}"
                )
            ClueKind.HotCold ->
                showText("Use the strange device to find where the treasure is buried.")
            else -> showText(step.text)
        }
    }

    private fun ProtectedAccess.showText(text: String) {
        ifOpenMainModal(TrailInterfaces.cluetext)
        ifSetText(TrailComponents.cluetext, text)
    }

    private fun ProtectedAccess.checkSteps(obj: UnpackedObjType) {
        val tier = tierOf(obj.id) ?: return
        player.stepOf(obj.id)
        val left = player.vars[remainingVarp(tier) ?: return]
        mes(
            if (left <= 1) "This is the last step of this trail."
            else "This trail has $left steps left."
        )
    }

    /* Solving */

    private inner class NpcClaim : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean {
            if (op != InteractionOp.Op1) {
                return false
            }
            if (uris[player] === npc) {
                return true
            }
            return player.heldSteps().any { (_, step) -> step.isSolvedBy(npc) }
        }

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            if (uris[player] === npc) {
                talkUri(npc)
                return
            }
            val (clue, step) =
                player.heldSteps().firstOrNull { it.second.isSolvedBy(npc) } ?: return
            talkClueNpc(npc, clue, step)
        }
    }

    private fun ClueStep.isSolvedBy(npc: Npc): Boolean {
        val target = target as? ClueTarget.Npc ?: return false
        if (kind == ClueKind.Emote) {
            return false
        }
        return npc.id in target.npcs || npc.visType.id in target.npcs
    }

    private suspend fun ProtectedAccess.talkClueNpc(npc: Npc, clue: InvObj, step: ClueStep) {
        when {
            step.row == reldoRow -> {
                startDialogue(npc) {
                    chatNpc(neutral, "Ah, a treasure hunter! This strange device will lead you " +
                        "to the buried treasure.")
                }
                invAdd(inv, TrailObjs.strange_device)
                val hotcold = steps.ofTier(ClueTier.Beginner, setOf(ClueKind.HotCold)).pick()
                if (hotcold != null) {
                    player.setVar(TrailVarps.beginner_step, hotcold.row)
                }
            }
            step.row == charlieRow -> {
                val request = charlieItems.keys.pick() ?: return advance(clue, step)
                player.setVar(TrailVarps.beginner_step, request)
                val wants = steps.byRow[request]?.text.orEmpty()
                startDialogue(npc) {
                    chatNpc(neutral, wants.replace("I need to give Charlie", "Could you bring me"))
                }
            }
            step.kind == ClueKind.SkillChallenge -> {
                val item = charlieItems[step.row] ?: return advance(clue, step)
                if (invTotal(inv, item) <= 0) {
                    startDialogue(npc) { chatNpc(neutral, "Have you got it? ${step.text}") }
                    return
                }
                invDel(inv, item, 1)
                startDialogue(npc) { chatNpc(happy, "Thanks! Here's a clue for your trouble.") }
                advance(clue, step)
            }
            else -> {
                startDialogue(npc) { chatNpc(neutral, "Ah, a clue scroll! Here, take this.") }
                advance(clue, step)
            }
        }
    }

    private inner class LocClaim : OpInterceptors.LocOp {
        override fun claims(player: Player, loc: BoundLocInfo, op: InteractionOp): Boolean =
            op == InteractionOp.Op1 && player.heldSteps().any { (_, step) -> step.isSolvedBy(loc) }

        override suspend fun ProtectedAccess.run(loc: BoundLocInfo, op: InteractionOp) {
            val (clue, step) =
                player.heldSteps().firstOrNull { it.second.isSolvedBy(loc) } ?: return
            mes("You search the ${locTypes[loc].name.lowercase()}...")
            advance(clue, step)
        }
    }

    private fun <T> List<T>.pick(): T? = if (isEmpty()) null else this[random.of(size)]

    private fun <T> Set<T>.pick(): T? = toList().pick()

    private fun ClueStep.isSolvedBy(loc: BoundLocInfo): Boolean {
        val target = target as? ClueTarget.Loc ?: return false
        return loc.id in target.locs && loc.coords.chebyshevDistance(target.coord) <= 1
    }

    /** Spade dig: map / cryptic dig steps, and the beginner hot/cold spot. */
    private suspend fun ProtectedAccess.dig(): Boolean {
        for ((clue, step) in player.heldSteps()) {
            val target = step.target as? ClueTarget.Coord ?: continue
            if (target.coord.level != coords.level) {
                continue
            }
            val range = if (step.kind == ClueKind.HotCold) HOTCOLD_DIG_RANGE else DIG_RANGE
            if (coords.chebyshevDistance(target.coord) > range) {
                continue
            }
            if (step.kind == ClueKind.HotCold) {
                invDel(inv, TrailObjs.strange_device, 1)
            }
            advance(clue, step)
            return true
        }
        return false
    }

    private fun ProtectedAccess.feelDevice() {
        val step = player.heldSteps().map { it.second }.firstOrNull { it.kind == ClueKind.HotCold }
        val target = step?.target as? ClueTarget.Coord
        if (target == null || target.coord.level != coords.level) {
            mes("The device is ice cold.")
            return
        }
        val distance = coords.chebyshevDistance(target.coord)
        mes(
            when {
                distance <= 3 ->
                    "The device is visibly shaking and burns to the touch. This must be the spot."
                distance <= 29 -> "The device is incredibly hot."
                distance <= 69 -> "The device is very hot."
                distance <= 99 -> "The device is hot."
                distance <= 149 -> "The device is warm."
                distance <= 199 -> "The device is cold."
                distance <= 499 -> "The device is very cold."
                else -> "The device is ice cold."
            }
        )
    }

    /* Emotes */

    private fun Player.emote(slot: Int) {
        val step =
            heldSteps().map { it.second }.firstOrNull {
                it.kind == ClueKind.Emote && it.emote == slot
            }
                ?: return
        val here =
            when (val target = step.target) {
                is ClueTarget.Zone -> target.contains(coords)
                is ClueTarget.Coord ->
                    target.coord.level == coords.level &&
                        coords.chebyshevDistance(target.coord) <= EMOTE_RANGE
                else -> false
            }
        if (!here) {
            return
        }
        if (step.outfit.any { obj -> worn.none { it?.id == obj } }) {
            return
        }
        if (uris[this]?.isSlotAssigned == true) {
            return
        }
        val type = npcTypes[TrailNpcs.uri]
        val uri = Npc(type, coords.translate(1, 0)).apply { mode = NpcMode.None }
        npcRepo.add(uri, URI_DURATION)
        uris[this] = uri
        mes("Uri appears.")
    }

    private suspend fun ProtectedAccess.talkUri(npc: Npc) {
        uris.remove(player)
        val held = player.heldSteps().firstOrNull { it.second.kind == ClueKind.Emote }
        startDialogue(npc) {
            chatNpc(neutral, "I do not believe we have been made acquainted. Here, take this.")
        }
        if (npc.isSlotAssigned) {
            npcRepo.del(npc, Int.MAX_VALUE)
        }
        held?.let { (clue, step) -> advance(clue, step) }
    }

    /* Progress */

    /** Moves the trail on from [step]: a new clue, or the reward casket after the last step. */
    private suspend fun ProtectedAccess.advance(clue: InvObj, step: ClueStep) {
        val tier = step.tier
        val remainingVarp = remainingVarp(tier) ?: return
        val left = player.vars[remainingVarp] - 1
        val slot = inv.indexOfFirst { it == clue }.takeIf { it >= 0 } ?: return
        if (left <= 0) {
            player.setVar(remainingVarp, 0)
            if (tier == ClueTier.Beginner) {
                player.setVar(TrailVarps.beginner_step, 0)
            }
            val casket =
                if (tier == ClueTier.Beginner) TrailObjs.casket_beginner else TrailObjs.casket_easy
            inv[slot] = null
            invAdd(inv, casket)
            objbox(casket, "Well done, you've completed the Treasure Trail!")
            return
        }
        player.setVar(remainingVarp, left)
        val next = pools.getValue(tier).filter { it.row != step.row }.pick() ?: return
        if (tier == ClueTier.Beginner) {
            player.setVar(TrailVarps.beginner_step, next.row)
            objbox(TrailObjs.clue_beginner, "You've been given a new clue!")
        } else {
            val obj = objTypes[easyObjByStep.getValue(next.row)] ?: return
            inv[slot] = InvObj(obj)
            objbox(obj, "You've been given a new clue!")
        }
    }

    /* Caskets */

    @OptIn(UncheckedType::class)
    private suspend fun ProtectedAccess.openCasket(tier: ClueTier, casket: UnpackedObjType) {
        val table = rewards[tier] ?: return mes("This casket is empty.")
        val completedVarp = completedVarp(tier) ?: return
        if (invDel(inv, casket, 1).failure) {
            return
        }
        val completed = player.vars[completedVarp] + 1
        player.setVar(completedVarp, completed)

        val rolled =
            table.roll(random, completed).mapNotNull { reward ->
                val id = names.objs[reward.obj] ?: return@mapNotNull null
                val base = objTypes[id] ?: return@mapNotNull null
                val type = if (reward.noted && base.canCert) objTypes.cert(base) else base
                type to reward.count
            }
        val display = Inventory.create(invTypes[TrailInvs.reward])
        for ((index, reward) in rolled.withIndex()) {
            val (type, count) = reward
            if (index < display.size) {
                display[index] = InvObj(type.id, count)
            }
            if (invAdd(inv, type, count).failure) {
                objRepo.add(type, coords, GROUND_DURATION, player, count)
            }
            eventBus.publish(CollectionLogObtained(player, objTypes.uncert(type).id, count))
        }
        ifOpenMainModal(TrailInterfaces.rewardscreen)
        UpdateInventory.updateInvFull(player, display)
        val trails = if (completed == 1) "Trail" else "Trails"
        mes("You have completed $completed ${tier.display} Treasure $trails.")
    }

    private companion object {
        /** Obj param holding a clue obj's cluehelper step dbrow (unnamed in the symbols). */
        private const val STEP_PARAM = 623
        private const val DIG_RANGE = 1
        private const val HOTCOLD_DIG_RANGE = 3
        /** RuneLite only gives an emote spot; Ryan agreed 5 tiles (2026-11-28). */
        private const val EMOTE_RANGE = 5
        private const val URI_DURATION = 100
        private const val GROUND_DURATION = 200
    }
}

/**
 * Map clue interfaces by dig spot (the server opens them; the cache doesn't link clue -> map).
 * Matched from RuneLite's BeginnerMapClue / MapClue coordinates; unknown maps fall back to text.
 */
internal object MapInterfaces {
    private val spots: List<Pair<InterfaceType, CoordGrid>> by lazy {
        listOf(
            TrailInterfaces.map01 to CoordGrid(3167, 3360),
            TrailInterfaces.map02 to CoordGrid(3290, 3373),
            TrailInterfaces.map03 to CoordGrid(3092, 3226),
            TrailInterfaces.map06 to CoordGrid(3043, 3399),
            TrailInterfaces.map11 to CoordGrid(3109, 3153),
            TrailInterfaces.easy_map006 to CoordGrid(2970, 3415),
        )
    }

    fun forDig(target: ClueTarget): InterfaceType? {
        val coord = (target as? ClueTarget.Coord)?.coord ?: return null
        return spots.firstOrNull { (_, spot) -> spot.chebyshevDistance(coord) <= 2 }?.first
    }
}
