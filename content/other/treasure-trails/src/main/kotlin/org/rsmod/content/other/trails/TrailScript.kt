package org.rsmod.content.other.trails

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.invs
import org.rsmod.api.config.refs.varps
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.death.ClueDrops
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.music.MusicRepository
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dig.DigSpots
import org.rsmod.api.player.events.CollectionLogObtained
import org.rsmod.api.player.events.EmoteSelected
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.UpdateInventory
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.enums.EnumTypeList
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.interf.InterfaceType
import org.rsmod.game.type.interf.InterfaceTypeList
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
 * Treasure trails, every tier. Hard adds puzzle boxes and fights (wizards on coordinate
 * steps, double agents on emote steps) and fairy ring steps; elite adds guard fights and kill
 * steps. Light boxes are built but only master steps use them (still gated off), like master
 * fights. Elite skill challenges (Sherlock) are not done yet.
 * Step data is Jagex's own: the 243 cache's
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
    private val npcList: NpcList,
    private val musicRepo: MusicRepository,
    private val interfaceTypes: InterfaceTypeList,
    private val locRepo: LocRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val enumTypes: EnumTypeList,
) : PluginScript() {
    private val steps by lazy {
        val boxes = BOXES.mapNotNullTo(HashSet()) { names.dbRows[it] }
        val encounters = ENCOUNTERS.mapNotNullTo(HashSet()) { names.dbRows[it] }
        ClueSteps.load(dbRows, names.dbTables, boxes, encounters)
    }

    private val lightBoxRow by lazy { names.dbRows["cluehelper_challenge_box_light"] }
    private val eitherBoxRow by lazy { names.dbRows["cluehelper_challenge_box_puzzle_or_light"] }

    /** Master puzzle boxes and their piece set (varp `if1`; enum 1864 keys). */
    private val masterBoxSets by lazy {
        MASTER_BOXES.mapNotNull { (name, set) -> names.objs[name]?.let { it to set } }.toMap()
    }

    private val rewards by lazy { ClueTier.entries.associateWith { TrailRewards.load(it) } }

    /** Clue objs of every tier but beginner, by step dbrow (obj param 623), and the reverse. */
    private val objByStep by lazy {
        val out = HashMap<Int, Int>()
        for (type in objTypes.values) {
            if (type.isCert || type.isPlaceholder || !type.name.startsWith("Clue scroll (")) {
                continue
            }
            val row = type.paramMap?.primitiveMap?.get(STEP_PARAM) as? Int ?: continue
            val tier = steps.byRow[row]?.tier ?: continue
            if (tier != ClueTier.Beginner) {
                out.putIfAbsent(row, type.id)
            }
        }
        out
    }
    private val stepByObj by lazy { objByStep.entries.associate { (row, obj) -> obj to row } }

    /** Clue obj symbol names (map clues open the interface of the same name, if any). */
    private val objNames by lazy { names.objs.entries.associate { (name, id) -> id to name } }

    /**
     * Steps a new trail can start on / continue with (sub-steps excluded), minus steps nobody can
     * solve yet: npcs not spawned in this world, locs that aren't on the map. Built on first use,
     * after the world (npc spawns) has loaded.
     */
    private val pools by lazy {
        val spawned = npcList.mapTo(HashSet()) { it.id }
        val beginnerKinds = setOf(ClueKind.Anagram, ClueKind.Cryptic, ClueKind.Emote, ClueKind.Map)
        val kinds =
            setOf(
                ClueKind.Anagram,
                ClueKind.Cipher,
                ClueKind.Coordinate,
                ClueKind.Cryptic,
                ClueKind.Emote,
                ClueKind.Map,
                ClueKind.Music,
                ClueKind.FairyRing,
            )
        val all =
            ClueTier.entries.associateWith { tier ->
                when (tier) {
                    ClueTier.Beginner -> steps.ofTier(tier, beginnerKinds)
                    // Master is one obj too (trail_clue_master); its step lives in a varp.
                    ClueTier.Master ->
                        steps.ofTier(tier, kinds).filter {
                            it.box == null || boxObjs(it).isNotEmpty()
                        }
                    else ->
                        steps.ofTier(tier, kinds).filter {
                            it.row in objByStep && (it.box == null || boxObjs(it).isNotEmpty())
                        }
                }
            }
        val reachable = all.mapValues { (_, list) -> list.filter { it.isReachable(spawned) } }
        logger.warn {
            "Treasure trails: solvable steps " +
                all.keys.joinToString { tier ->
                    "${tier.display} ${reachable.getValue(tier).size}/${all.getValue(tier).size}"
                }
        }
        reachable
    }

    private fun ClueStep.isReachable(spawned: Set<Int>): Boolean =
        when (val target = target) {
            is ClueTarget.Npc -> target.npcs.any { it in spawned }
            is ClueTarget.Key -> target.npcs.any { it in spawned }
            is ClueTarget.Kill -> target.npcs.any { it in spawned }
            is ClueTarget.Loc ->
                (-1..1).any { dx ->
                    (-1..1).any { dz ->
                        locRepo.findAll(target.coord.translate(dx, dz)).any { it.id in target.locs }
                    }
                }
            else -> true
        }

    private val reldoRow by lazy { names.dbRows["cluehelper_cryptic_beginner_reldo"] }
    private val charlieRow by lazy { names.dbRows["cluehelper_cryptic_beginner_charlie_the_tramp"] }
    private val jorralRow by lazy { names.dbRows["cluehelper_cryptic_master_jorral"] }
    private val faloRow by lazy { names.dbRows["cluehelper_cryptic_master_falo_the_bard"] }
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

    /** Emote steps with several emotes: (step dbrow, emotes done so far) per player. */
    private val emoteProgress = WeakHashMap<Player, Pair<Int, Int>>()

    /** Open puzzle / light boxes: (step dbrow, state) per player. Lost on logout (re-scrambled). */
    private val puzzles = WeakHashMap<Player, Pair<Int, SlidePuzzle>>()
    private val lightBoxes = WeakHashMap<Player, Pair<Int, LightBox>>()

    /** A step's fight: the npcs still alive, and whether the owner won it. */
    private class Encounter(val row: Int, val npcs: MutableList<Npc>) {
        var won = false
    }

    private val encounters = WeakHashMap<Player, Encounter>()

    /**
     * The boxes a step's npc may hand out (one is picked): the light box; the step's own puzzle box
     * (the clue obj's symbol + `_puzzlebox`; these objs have no params linking them); or, for
     * master "puzzle or light" steps, a master puzzle box or the light box. Empty if none found.
     */
    private fun boxObjs(step: ClueStep): List<Int> {
        val box = step.box ?: return emptyList()
        val light = objTypes[TrailObjs.light_box].id
        return when (box) {
            lightBoxRow -> listOf(light)
            eitherBoxRow -> masterBoxSets.keys.toList() + light
            else -> {
                val clueName = objByStep[step.row]?.let(objNames::get) ?: return emptyList()
                listOfNotNull(names.objs["${clueName}_puzzlebox"])
            }
        }
    }

    override fun ScriptContext.startup() {
        logger.info {
            "Treasure trails: ${steps.byRow.size} steps; easy clue objs ${objByStep.size}."
        }

        bindClue(TrailObjs.clue_beginner)
        bindClue(TrailObjs.clue_master)
        for (obj in stepByObj.keys) {
            bindClue(objTypes[obj] ?: continue)
        }
        for ((tier, casket) in CASKETS) {
            onOpHeld1(casket) { openCasket(tier, it.type) }
        }
        onOpHeld1(TrailObjs.strange_device) { feelDevice() }
        onOpHeld1(TrailObjs.master_device) { feelDevice() }
        onOpHeld1(TrailObjs.sextant) { lookThroughSextant() }
        onEvent<NpcKilledEvent> {
            hero.dropClueKey(npc)
            encounterKilled(npc, hero)
            hero.killStep(npc)
        }
        val boxObjs = steps.byRow.values.flatMapTo(HashSet()) { boxObjs(it) }
        for (box in boxObjs) {
            val type = objTypes[box] ?: continue
            if (!isHeldOpBound(type, 1)) {
                onOpHeld1(type) { openBox(it.type) }
            }
        }
        onIfModalButton(TrailComponents.puzzle_pieces) { movePiece(it.comsub) }
        for ((index, button) in TrailComponents.light_buttons.withIndex()) {
            onIfModalButton(button) { pressLight(index) }
        }

        OpInterceptors.register(NpcClaim())
        OpInterceptors.register(LocClaim())
        DigSpots.register { dig() }
        onEvent<EmoteSelected> { player.emote(slot) }

        ClueDrops.resolver = ClueDrops.Resolver { hero, tier -> resolveDrop(hero, tier) }
    }

    private fun ScriptContext.bindClue(obj: ObjType) {
        if (!isHeldOpBound(obj, 1)) {
            onOpHeld1(obj) { read(it.type) }
        }
        if (!isHeldOpBound(obj, 2)) {
            onOpHeld2(obj) { checkSteps(it.type) }
        }
    }

    /* State */

    private fun tierOf(obj: Int): ClueTier? =
        when {
            obj == objTypes[TrailObjs.clue_beginner].id -> ClueTier.Beginner
            obj == objTypes[TrailObjs.clue_master].id -> ClueTier.Master
            else -> stepByObj[obj]?.let { steps.byRow[it]?.tier }
        }

    /** Beginner and master are one clue obj each, keeping the step in a varp. */
    private fun singleObj(tier: ClueTier): ObjType? =
        when (tier) {
            ClueTier.Beginner -> TrailObjs.clue_beginner
            ClueTier.Master -> TrailObjs.clue_master
            else -> null
        }

    private fun stepVarp(tier: ClueTier): VarpType? =
        when (tier) {
            ClueTier.Beginner -> TrailVarps.beginner_step
            ClueTier.Master -> TrailVarps.master_step
            else -> null
        }

    private fun remainingVarp(tier: ClueTier): VarpType =
        when (tier) {
            ClueTier.Beginner -> TrailVarps.beginner_remaining
            ClueTier.Easy -> TrailVarps.easy_remaining
            ClueTier.Medium -> TrailVarps.medium_remaining
            ClueTier.Hard -> TrailVarps.hard_remaining
            ClueTier.Elite -> TrailVarps.elite_remaining
            ClueTier.Master -> TrailVarps.master_remaining
        }

    private fun completedVarp(tier: ClueTier): VarpType =
        when (tier) {
            ClueTier.Beginner -> TrailVarps.beginner_completed
            ClueTier.Easy -> TrailVarps.easy_completed
            ClueTier.Medium -> TrailVarps.medium_completed
            ClueTier.Hard -> TrailVarps.hard_completed
            ClueTier.Elite -> TrailVarps.elite_completed
            ClueTier.Master -> TrailVarps.master_completed
        }

    private fun Player.setVar(varp: VarpType, value: Int) =
        VarPlayerIntMapSetter.set(this, varp, value)

    /** The step for a held clue [obj], choosing the varp step / trail length on first use. */
    private fun Player.stepOf(obj: Int): ClueStep? {
        val tier = tierOf(obj) ?: return null
        val remaining = remainingVarp(tier)
        if (vars[remaining] <= 0) {
            setVar(remaining, random.of(tier.steps))
        }
        val varp = stepVarp(tier) ?: return steps.byRow[stepByObj[obj] ?: return null]
        val current = steps.byRow[vars[varp]]
        if (current != null && current.tier == tier) {
            return current
        }
        val pick = pools.getValue(tier).pick() ?: return null
        setVar(varp, pick.row)
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
        hero.setVar(remainingVarp(tier), 0)
        val single = singleObj(tier)
        if (single != null) {
            stepVarp(tier)?.let { hero.setVar(it, 0) }
            return objTypes[single]
        }
        val step = pools.getValue(tier).pick() ?: return null
        return objTypes[objByStep.getValue(step.row)]
    }

    /* Reading */

    private suspend fun ProtectedAccess.read(obj: UnpackedObjType) {
        val step = player.stepOf(obj.id) ?: return mes("Nothing interesting happens.")
        when (step.kind) {
            ClueKind.Map -> {
                val byName = objNames[obj.id]?.let { names.interfaces[it] }
                val map = MapInterfaces.forDig(step.target) ?: byName?.let(::interfaceRef)
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
            ClueKind.Coordinate -> showText(Coordinates.describe(step.text))
            ClueKind.Music -> {
                val who = (step.target as? ClueTarget.Npc)?.description ?: "the listener"
                showText(
                    "Play the music track <col=ffffff>${trackName(step)}</col> and speak to " +
                        "the person ${who.replaceFirstChar { it.lowercase() }}<br><br>" +
                        "(Unlocked: ${step.text})"
                )
            }
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
        val left = player.vars[remainingVarp(tier)]
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
            step.row == jorralRow -> {
                // Authored line (not Wiki).
                startDialogue(npc) {
                    chatNpc(neutral, "So you're after the buried treasure? Take this device; " +
                        "it'll grow hotter the closer you get.")
                }
                invAdd(inv, TrailObjs.master_device)
                val hotcold = steps.ofTier(ClueTier.Master, setOf(ClueKind.HotCold)).pick()
                if (hotcold != null) {
                    player.setVar(TrailVarps.master_step, hotcold.row)
                }
            }
            step.row == faloRow -> {
                val song = steps.ofTier(ClueTier.Master, setOf(ClueKind.Falo)).pick()
                    ?: return advance(clue, step)
                player.setVar(TrailVarps.master_step, song.row)
                startDialogue(npc) { chatNpc(neutral, song.text) }
            }
            step.kind == ClueKind.Falo -> {
                if (!player.hasFaloItem(step)) {
                    startDialogue(npc) { chatNpc(neutral, step.text) }
                    return
                }
                startDialogue(npc) { chatNpc(happy, "That's the one! Here, take this.") }
                advance(clue, step)
            }
            step.row == charlieRow -> {
                val request = charlieItems.keys.pick() ?: return advance(clue, step)
                player.setVar(TrailVarps.beginner_step, request)
                val wants = steps.byRow[request]?.text.orEmpty()
                startDialogue(npc) {
                    chatNpc(neutral, wants.replace("I need to give Charlie", "Could you bring me"))
                }
            }
            step.kind == ClueKind.Music -> {
                val track = step.music?.let { dbRows[it] }?.let(musicRepo::forRow)
                if (track == null || player.vars[varbits.music_curr_id] != track.id) {
                    startDialogue(npc) {
                        chatNpc(neutral, "Could you play me ${trackName(step)}? I do love it.")
                    }
                    return
                }
                startDialogue(npc) { chatNpc(happy, "What a lovely tune! Here, take this.") }
                advance(clue, step)
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
            step.box != null -> talkBoxNpc(npc, clue, step)
            step.question != null -> {
                val (question, answer) = step.question
                startDialogue(npc) { chatNpc(neutral, question) }
                if (countDialog("Your answer:") != answer) {
                    startDialogue(npc) { chatNpc(neutral, "That isn't right. Have another think.") }
                    return
                }
                startDialogue(npc) { chatNpc(happy, "Spot on! Here, take this.") }
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
            val keyTarget = step.target as? ClueTarget.Key
            if (keyTarget != null) {
                val keyType = objTypes[keyTarget.key] ?: return
                if (invTotal(inv, keyType) <= 0) {
                    mes("It's locked. ${keyTarget.description}")
                    return
                }
                invDel(inv, keyType, 1)
            }
            mes("You search the ${locTypes[loc].name.lowercase()}...")
            advance(clue, step)
        }
    }

    private fun trackName(step: ClueStep): String =
        step.music?.let { dbRows[it] }?.let(musicRepo::forRow)?.displayName ?: "the song"

    private fun <T> List<T>.pick(): T? = if (isEmpty()) null else this[random.of(size)]

    private fun <T> Set<T>.pick(): T? = toList().pick()

    private fun ClueStep.isSolvedBy(loc: BoundLocInfo): Boolean =
        when (val target = target) {
            is ClueTarget.Loc ->
                loc.id in target.locs && loc.coords.chebyshevDistance(target.coord) <= 1
            is ClueTarget.Key ->
                loc.id in target.locs && loc.coords.chebyshevDistance(target.coord) <= 1
            else -> false
        }

    /** Key steps: killing one of the step's npcs drops its key. */
    private fun Player.dropClueKey(npc: Npc) {
        for ((_, step) in heldSteps()) {
            val target = step.target as? ClueTarget.Key ?: continue
            if (npc.id !in target.npcs && npc.visType.id !in target.npcs) {
                continue
            }
            val key = objTypes[target.key] ?: continue
            if (inv.any { it?.id == key.id }) {
                continue
            }
            objRepo.add(key, npc.coords, GROUND_DURATION, this)
        }
    }

    /** Sextant + watch + chart: where am I, in coordinate-clue terms. */
    private fun ProtectedAccess.lookThroughSextant() {
        if (invTotal(inv, TrailObjs.watch) <= 0 || invTotal(inv, TrailObjs.chart) <= 0) {
            mes("You need a watch and a chart to work out where you are.")
            return
        }
        mes("You are at ${Coordinates.of(coords)}.")
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
            if (player.fightPending(step)) {
                return true
            }
            if (step.kind == ClueKind.HotCold) {
                val device =
                    if (step.tier == ClueTier.Master) TrailObjs.master_device
                    else TrailObjs.strange_device
                invDel(inv, device, 1)
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
        val step = heldSteps().map { it.second }.firstOrNull { it.kind == ClueKind.Emote } ?: return
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
        // Several emotes are done in order; a wrong one starts over.
        val done = emoteProgress[this]?.takeIf { it.first == step.row }?.second ?: 0
        if (step.emotes.getOrNull(done) != slot) {
            emoteProgress.remove(this)
            if (step.emotes.size > 1 && step.emotes.first() == slot) {
                emoteProgress[this] = step.row to 1
            }
            return
        }
        if (done + 1 < step.emotes.size) {
            emoteProgress[this] = step.row to done + 1
            return
        }
        emoteProgress.remove(this)
        if (fightPending(step)) {
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

    /* Boxes (dialogue lines here are authored, not Wiki) */

    private suspend fun ProtectedAccess.talkBoxNpc(npc: Npc, clue: InvObj, step: ClueStep) {
        val boxes = boxObjs(step).mapNotNull { objTypes[it] }
        if (boxes.isEmpty()) {
            return advance(clue, step)
        }
        val held = boxes.firstOrNull { invTotal(inv, it) > 0 }
        val holding = held != null
        val solved = player.vars[TrailVarps.box_solved] == step.row
        when {
            held != null && solved -> {
                invDel(inv, held, 1)
                player.setVar(TrailVarps.box_solved, 0)
                startDialogue(npc) { chatNpc(happy, "You've solved it! Here, take this.") }
                advance(clue, step)
            }
            holding ->
                startDialogue(npc) {
                    chatNpc(neutral, "Have you solved that puzzle yet? Bring it back when you have.")
                }
            else -> {
                if (invAdd(inv, boxes[random.of(boxes.size)]).failure) {
                    mes("You need a free inventory space for the puzzle.")
                    return
                }
                player.setVar(TrailVarps.box_solved, 0)
                puzzles.remove(player)
                lightBoxes.remove(player)
                startDialogue(npc) {
                    chatNpc(neutral, "Solve this puzzle for me and I'll give you your next clue.")
                }
            }
        }
    }

    private fun ProtectedAccess.openBox(obj: UnpackedObjType) {
        val step =
            player.heldSteps().map { it.second }.firstOrNull { obj.id in boxObjs(it) }
                ?: return mes("This doesn't seem to belong to any clue you're holding.")
        if (player.vars[TrailVarps.box_solved] == step.row) {
            mes("You've already solved this. Take it back to whoever gave it to you.")
            return
        }
        if (obj.id == objTypes[TrailObjs.light_box].id) {
            openLightBox(step)
        } else {
            openPuzzle(step, masterBoxSets[obj.id] ?: random.of(SlidePuzzle.SETS))
        }
    }

    private fun ProtectedAccess.openPuzzle(step: ClueStep, set: Int) {
        val puzzle =
            puzzles[player]?.takeIf { it.first == step.row && it.second.set == set }?.second
                ?: SlidePuzzle.scrambled(set, random).also { puzzles[player] = step.row to it }
        // The onload (cs2 688/690) reads the piece set from if1 and the pieces from the inv.
        player.setVar(varps.if1, puzzle.set)
        sendPuzzle(puzzle)
        ifOpenMainModal(TrailInterfaces.slidepuzzle)
        val slots = SlidePuzzle.SIZE * SlidePuzzle.SIZE
        ifSetEvents(TrailComponents.puzzle_pieces, 0 until slots, IfEvent.Op1)
    }

    @OptIn(UncheckedType::class)
    private fun ProtectedAccess.sendPuzzle(puzzle: SlidePuzzle) {
        val display = Inventory.create(invTypes[TrailInvs.puzzle])
        // Enum 1864: piece set -> enum of piece index -> piece obj (what cs2 690 draws).
        val setEnum = enumTypes[PUZZLE_SETS_ENUM]?.primitiveMap?.get(puzzle.set) as? Int
        val pieces = setEnum?.let { enumTypes[it]?.primitiveMap }.orEmpty()
        for ((slot, piece) in puzzle.slots.withIndex()) {
            if (piece == SlidePuzzle.EMPTY || slot >= display.size) {
                continue
            }
            val id = pieces[piece] as? Int ?: continue
            display[slot] = InvObj(id, 1)
        }
        UpdateInventory.updateInvFull(player, display)
    }

    /** The client already slid the piece (cs2 691); mirror it, or resync if it was illegal. */
    private fun ProtectedAccess.movePiece(slot: Int) {
        val (row, puzzle) = puzzles[player] ?: return
        if (!puzzle.move(slot)) {
            sendPuzzle(puzzle)
            return
        }
        if (puzzle.isSolved) {
            puzzles.remove(player)
            player.setVar(TrailVarps.box_solved, row)
            mes("You've solved the puzzle!")
        }
    }

    private fun ProtectedAccess.openLightBox(step: ClueStep) {
        val box =
            lightBoxes[player]?.takeIf { it.first == step.row }?.second
                ?: LightBox.random(random).also { lightBoxes[player] = step.row to it }
        player.setVar(TrailVarps.lights, box.lights)
        ifOpenMainModal(TrailInterfaces.light_puzzle)
        for (button in TrailComponents.light_buttons) {
            ifSetEvents(button, -1..-1, IfEvent.Op1)
        }
    }

    private fun ProtectedAccess.pressLight(button: Int) {
        val (row, box) = lightBoxes[player] ?: return
        box.press(button)
        player.setVar(TrailVarps.lights, box.lights)
        if (box.isSolved) {
            lightBoxes.remove(player)
            player.setVar(TrailVarps.box_solved, row)
            mes("All the lights are on. You've solved the light box!")
        }
    }

    /* Fights: wizards on coordinate steps, double agents on emote steps (messages authored) */

    /**
     * True while [step]'s fight is still owed: spawns its npcs to attack the player if none are
     * up. After the kill, the same action (dig / emote) completes the step.
     */
    private fun Player.fightPending(step: ClueStep): Boolean {
        if (step.encounterNpcs.isEmpty()) {
            return false
        }
        val current = encounters[this]?.takeIf { it.row == step.row }
        if (current?.won == true) {
            return false
        }
        if (current != null && current.npcs.any { it.isSlotAssigned }) {
            mes("You'll have to deal with your attacker first.")
            return true
        }
        val spawned = ArrayList<Npc>()
        // Several rows: one of them (elite: an Armadylean or a Bandosian guard).
        val option = step.encounterNpcs[random.of(step.encounterNpcs.size)]
        for (id in option) {
            val type = npcTypes[id] ?: continue
            val npc = Npc(type, coords.translate(1, 0))
            npcRepo.add(npc, ENCOUNTER_DURATION)
            npc.opPlayer2(this, aiInteractions)
            spawned += npc
        }
        if (spawned.isEmpty()) {
            return false
        }
        encounters[this] = Encounter(step.row, spawned)
        mes("You've been ambushed!")
        return true
    }

    private fun encounterKilled(npc: Npc, hero: Player) {
        for ((owner, fight) in encounters) {
            if (!fight.npcs.removeIf { it === npc }) {
                continue
            }
            if (fight.npcs.isEmpty() && hero === owner) {
                fight.won = true
                owner.mes("You've defeated your attacker. Carry on with your clue.")
            }
            return
        }
    }

    /** Falo: an obj in the inventory or worn that is one of the step's items / its group. */
    private fun Player.hasFaloItem(step: ClueStep): Boolean {
        val objs = inv.asSequence() + worn.asSequence()
        return objs.any { obj ->
            obj ?: return@any false
            if (obj.id in step.items) {
                return@any true
            }
            val group = step.itemGroup ?: return@any false
            val type = objTypes[obj.id] ?: return@any false
            type.paramMap?.primitiveMap?.get(TRAIL_ITEM_PARAM) == group
        }
    }

    /* Progress */

    /** Moves the trail on from [step]: a new clue, or the reward casket after the last step. */
    private suspend fun ProtectedAccess.advance(clue: InvObj, step: ClueStep) {
        val (obj, text) = player.advanceTrail(clue, step) ?: return
        objbox(obj, text)
    }

    /**
     * Swaps [clue] for the next clue (or the casket after the last step) in its slot. Returns the
     * obj and line to show, or null if the clue isn't held.
     */
    private fun Player.advanceTrail(clue: InvObj, step: ClueStep): Pair<ObjType, String>? {
        val tier = step.tier
        encounters.remove(this)
        val remainingVarp = remainingVarp(tier)
        val left = vars[remainingVarp] - 1
        val slot = inv.indexOfFirst { it == clue }.takeIf { it >= 0 } ?: return null
        if (left <= 0) {
            setVar(remainingVarp, 0)
            stepVarp(tier)?.let { setVar(it, 0) }
            val casket = objTypes[CASKETS.getValue(tier)]
            inv[slot] = InvObj(casket)
            return casket to "Well done, you've completed the Treasure Trail!"
        }
        setVar(remainingVarp, left)
        val next = pools.getValue(tier).filter { it.row != step.row }.pick() ?: return null
        val single = singleObj(tier)
        val varp = stepVarp(tier)
        if (single != null && varp != null) {
            setVar(varp, next.row)
            return objTypes[single] to "You've been given a new clue!"
        }
        val obj = objTypes[objByStep.getValue(next.row)] ?: return null
        inv[slot] = InvObj(obj)
        return obj to "You've been given a new clue!"
    }

    /** Elite / master kill steps: killing one of the step's npcs moves the trail on. */
    private fun Player.killStep(npc: Npc) {
        val held =
            heldSteps().firstOrNull { (_, step) ->
                val target = step.target as? ClueTarget.Kill
                target != null && (npc.id in target.npcs || npc.visType.id in target.npcs)
            } ?: return
        val (_, text) = advanceTrail(held.first, held.second) ?: return
        mes(text)
    }

    /* Caskets */

    @OptIn(UncheckedType::class)
    private suspend fun ProtectedAccess.openCasket(tier: ClueTier, casket: UnpackedObjType) {
        val table = rewards[tier] ?: return mes("This casket is empty.")
        val completedVarp = completedVarp(tier)
        if (invDel(inv, casket, 1).failure) {
            return
        }
        val completed = player.vars[completedVarp] + 1
        player.setVar(completedVarp, completed)
        // The collection log's "<Tier> clues completed" lines read this (kill_counts.tsv).
        player.collectionLog.kills["clue:${tier.key}"] = completed

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

    /** An interface id from the symbol table as a type (map clues opened by obj name). */
    private fun interfaceRef(id: Int): InterfaceType? = interfaceTypes[id]

    private companion object {
        private val CASKETS =
            mapOf(
                ClueTier.Beginner to TrailObjs.casket_beginner,
                ClueTier.Easy to TrailObjs.casket_easy,
                ClueTier.Medium to TrailObjs.casket_medium,
                ClueTier.Hard to TrailObjs.casket_hard,
                ClueTier.Elite to TrailObjs.casket_elite,
                ClueTier.Master to TrailObjs.casket_master,
            )

        /** Obj param holding a clue obj's cluehelper step dbrow (unnamed in the symbols). */
        private const val STEP_PARAM = 623
        private const val DIG_RANGE = 1
        private const val HOTCOLD_DIG_RANGE = 3
        /** RuneLite only gives an emote spot; Ryan agreed 5 tiles (2026-11-28). */
        private const val EMOTE_RANGE = 5
        private const val URI_DURATION = 100
        private const val GROUND_DURATION = 200
        private const val ENCOUNTER_DURATION = 500
        /** Enum: puzzle piece set (varp if1) -> enum of piece objs. */
        private const val PUZZLE_SETS_ENUM = 1864
        /** Obj param: Falo's item groups (cluehelper_requirement_obj_param_trail_item). */
        private const val TRAIL_ITEM_PARAM = 258
        /** Master puzzle boxes and their piece sets (enum 1864: 4..6, 9). */
        private val MASTER_BOXES =
            listOf(
                "trail_master_zulrah_puzzlebox" to 4,
                "trail_master_cerberus_puzzlebox" to 5,
                "trail_master_gnomechild_puzzlebox" to 6,
                "trail_master_tob_puzzlebox" to 9,
            )
        /** Box challenges that are implemented. */
        private val BOXES =
            listOf(
                "cluehelper_challenge_box_puzzle",
                "cluehelper_challenge_box_light",
                "cluehelper_challenge_box_puzzle_or_light",
            )
        /** Fights that are implemented (all tiers). */
        private val ENCOUNTERS =
            listOf(
                "cluehelper_combat_encounter_double_agent_65",
                "cluehelper_combat_encounter_double_agent_108",
                "cluehelper_combat_encounter_saradomin_wizard",
                "cluehelper_combat_encounter_zamorak_wizard",
                "cluehelper_combat_encounter_armadylean_guard",
                "cluehelper_combat_encounter_bandosian_guard",
                "cluehelper_combat_encounter_brassican_mage",
                "cluehelper_combat_encounter_3_ancient_wizards",
                "cluehelper_combat_encounter_double_agent_141",
            )
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
