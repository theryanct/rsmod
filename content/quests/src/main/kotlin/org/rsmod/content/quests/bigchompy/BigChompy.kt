package org.rsmod.content.quests.bigchompy

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
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
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Big Chompy Bird Hunting (OSRS Wiki). Cache quest varp `chompybird`, end state 65 (Rantz's
 * post-quest variant shows only at exactly 65, so nothing else is packed into it); the rest lives
 * in server varp `bc_progress`. Stages are ours (the client only checks 0 / started / 65):
 * - 10 Rantz wants 'stabbers' (ogre arrows: skilling's recipes); Bugs sells a chisel and knife
 *   (10 coins), Fycie 25 feathers (50 coins).
 * - 20 six ogre arrows handed over; he explains the 'fatsy toadies'.
 * - Unlock the chest in Rantz's cave (fails drain Strength), take the ogre bellows, fill them at the
 *   swamp bubbles (3 loads), inflate swamp toads (up to 3 bloated toads).
 * - 30 Rantz shows where to put the bait (the clearing south of him). Dropping a bloated toad there
 *   lures a chompy; the first time Rantz shoots and misses (40). He lends you his ogre bow (50).
 * - Kill a chompy (ogre bows only), pluck it; 60 Rantz wants it cooked with his ingredient and the
 *   children's (one of two each, chosen per player); the spit-roast north of Rantz cooks it with all
 *   three (30 Cooking) into a seasoned chompy; 65 handed to Rantz: 2 QP, 262 Fletching, 1,470
 *   Cooking, 735 Ranged XP, and chompy hunting.
 *
 * Simplified: any ogre arrows are accepted (OSRS wants your own; skilling's recipe doesn't track
 * who made them) and the arrowtips aren't gated on starting the quest; the chompy appears after a
 * short wait instead of flying in from afar; Rantz's lost-bow price is a flat 500 coins.
 */
class BigChompy
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locRepo: LocRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(BigChompy::class.java, "big_chompy") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Rantz" to BcNpcs.rantzes.first(), "Bugs" to BcNpcs.bugs, "Fycie" to BcNpcs.fycie)
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (rantz in BcNpcs.rantzes) {
                register(npcTypes, rantz, BIG_CHOMPY, wants = { state < COMPLETE }) { talkRantz(it) }
            }
            register(npcTypes, BcNpcs.bugs, BIG_CHOMPY, wants = { state < COMPLETE }) { talkBugs(it) }
            register(npcTypes, BcNpcs.fycie, BIG_CHOMPY, wants = { state < COMPLETE }) { talkFycie(it) }
        }
        for (rantz in BcNpcs.rantzes) {
            onOpNpcU(rantz, BcObjs.ogre_arrow) { arrowsOnRantz(it.npc) }
            onOpNpcU(rantz, BcObjs.bloated_toad) { toadOnRantz(it.npc) }
            onOpNpcU(rantz, BcObjs.raw_chompy) { if (state == BOW) chompyToRantz(it.npc, used = true) }
            onOpNpcU(rantz, BcObjs.ogre_bow) { runQuestSection(it.npc, dialogue, "Using the ogre bow on Rantz") }
        }

        onLocOpLabel(locTypes, BcLocs.chest, "Unlock") { unlockChest(it) }
        onLocOpLabel(locTypes, BcLocs.chest_open, "Search") { searchChest() }
        for (bellows in BcObjs.bellows) onOpLocU(BcLocs.bubbles, bellows) { fillBellows(bellows) }
        onNpcOpLabel(npcTypes, BcNpcs.toad, "Inflate") { inflateToad(it) }
        for (bellows in BcObjs.bellows) onOpNpcU(BcNpcs.toad, bellows) { inflateToad(it.npc) }

        onOpHeld1(BcObjs.bloated_toad) { placeBait() }
        onModifyNpcHit(BcNpcs.chompy) { ogreBowsOnly(hit) }
        onEvent<NpcKilledEvent> { chompyKilled(this) }
        onNpcOpLabel(npcTypes, BcNpcs.chompy_dead, "Pluck") { pluck(it) }

        onOpLocU(BcLocs.spit, BcObjs.raw_chompy) { cookOnSpit() }
        // Ingredient on the raw chompy: the Wiki's reminder (they go in while cooking).
        for (ingredient in BcObjs.allIngredients) {
            onOpHeldU(BcObjs.raw_chompy, ingredient) {
                runQuestSection(null, dialogue, "Attempting to use the ingredients on the raw chompy")
            }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BIG_CHOMPY)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(BIG_CHOMPY, value)
    }

    private fun ProtectedAccess.flags(): Int = player.vars[BcVarps.progress]

    private fun ProtectedAccess.hasFlag(bit: Int): Boolean = (flags() and bit) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, BcVarps.progress, flags() or bit)
    }

    /* Rantz */

    private suspend fun ProtectedAccess.talkRantz(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "Starting off", hooks, speakers)
                if (start) setState(STARTED)
            }
            STARTED -> handInArrows(npc)
            ARROWS ->
                if (carries(BcObjs.bloated_toad)) {
                    runQuestSection(npc, dialogue, "Returning to Rantz with the bloated toad", speakers = speakers)
                    setState(SPOT)
                } else {
                    runQuestSection(npc, dialogue, "Returning to Rantz without a bloated toad", speakers = speakers)
                }
            SPOT ->
                if (hasFlag(BAIT_PLACED)) {
                    runQuestSection(npc, dialogue, "Talking to Rantz after placing the toad", speakers = speakers)
                } else {
                    runQuestSection(npc, dialogue, "Talking to Rantz again", speakers = speakers)
                }
            MISSED -> {
                var lend = false
                val hooks =
                    SimpleQuestHooks(options = mapOf("I'm actually quite strong... please let me try." to { lend = true }))
                runQuestSection(npc, dialogue, "Talking to Rantz after he failed", hooks, speakers)
                if (lend) {
                    invAddOrDrop(objRepo, BcObjs.ogre_bow)
                    setState(BOW)
                }
            }
            BOW ->
                when {
                    carries(BcObjs.raw_chompy) && hasFlag(KILLED_OWN) -> chompyToRantz(npc, used = false)
                    !carries(BcObjs.ogre_bow) && invTotal(worn, BcObjs.ogre_bow) == 0 -> reclaimBow(npc)
                    else -> runQuestSection(npc, dialogue, "Talking to Rantz before hunting the chompy", speakers = speakers)
                }
            COOK ->
                if (carries(BcObjs.seasoned_chompy)) {
                    runQuestSection(npc, dialogue, "Handing the chompy to Rantz or trying to cook another raw chompy", speakers = speakers)
                    invDel(inv, BcObjs.seasoned_chompy)
                    statAdvance(stats.fletching, FLETCHING_XP * xpMods.get(player, stats.fletching))
                    statAdvance(stats.cooking, COOKING_XP * xpMods.get(player, stats.cooking))
                    statAdvance(stats.ranged, RANGED_XP * xpMods.get(player, stats.ranged))
                    completeQuest(BIG_CHOMPY)
                } else {
                    val nodes = pickDialogue(dialogue.section("Talking to Rantz again before cooking the chompy"), rantzPick())
                    startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes) }
                }
        }
    }

    private suspend fun ProtectedAccess.handInArrows(npc: Npc) {
        val count = invTotal(inv, BcObjs.ogre_arrow)
        if (count == 0) {
            val hooks = SimpleQuestHooks(conditions = mapOf("Using a raw chompy on Rantz:" to { false }))
            runQuestSection(npc, dialogue, "Returning to Rantz without ogre arrows", hooks, speakers)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Using a raw chompy on Rantz:" to { false },
                        "If the player more than six ogre arrows not made by themselves:" to { false },
                        "If the player has less than six ogre arrows:" to { count < ARROWS_NEEDED },
                        "If the player has six or more ogre arrows:" to { count >= ARROWS_NEEDED },
                    )
            )
        runQuestSection(npc, dialogue, "Returning to Rantz with six or more ogre arrows", hooks, speakers)
        if (count >= ARROWS_NEEDED) {
            invDel(inv, BcObjs.ogre_arrow, ARROWS_NEEDED)
            setState(ARROWS)
        }
    }

    private suspend fun ProtectedAccess.arrowsOnRantz(npc: Npc) {
        when {
            state > STARTED -> runQuestSection(npc, dialogue, "Using ogre arrows on Rantz after giving him some already", speakers = speakers)
            state < STARTED -> talkRantz(npc)
            invTotal(inv, BcObjs.ogre_arrow) < ARROWS_NEEDED ->
                runQuestSection(npc, dialogue, "Using less than 6 ogre arrows on Rantz", speakers = speakers)
            else -> {
                runQuestSection(npc, dialogue, "Using ogre arrows made by the player on Rantz", speakers = speakers)
                invDel(inv, BcObjs.ogre_arrow, ARROWS_NEEDED)
                setState(ARROWS)
            }
        }
    }

    private suspend fun ProtectedAccess.toadOnRantz(npc: Npc) {
        when (state) {
            ARROWS -> {
                runQuestSection(npc, dialogue, "Using the bloated toad on Rantz", speakers = speakers)
                setState(SPOT)
            }
            in SPOT until COMPLETE -> runQuestSection(npc, dialogue, "Using another toad on Rantz", speakers = speakers)
            else -> talkRantz(npc)
        }
    }

    private suspend fun ProtectedAccess.reclaimBow(npc: Npc) {
        val hasCoins = carries(BcObjs.coins, BOW_PRICE)
        var buy = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have enough coins:" to { !hasCoins },
                        "If the player has enough coins:" to { hasCoins },
                    ),
                options = mapOf("Yes, I'll buy the bow." to { buy = true }),
                rewrites = { it.replace("[500-549]", "$BOW_PRICE") },
            )
        runQuestSection(npc, dialogue, "Reclaiming a lost bow", hooks, speakers)
        if (buy && hasCoins) {
            invDel(inv, BcObjs.coins, BOW_PRICE)
            invAddOrDrop(objRepo, BcObjs.ogre_bow)
        }
    }

    private suspend fun ProtectedAccess.chompyToRantz(npc: Npc, used: Boolean) {
        if (!hasFlag(KILLED_OWN)) {
            // Authored line (not Wiki): it must be one you hunted.
            mes("Rantz wants a chompy you hunted yourself.")
            return
        }
        assignIngredients()
        val hooks = SimpleQuestHooks(rewrites = { it.replace("Me's wants Onion. wiv", "Me's wants ${rantzName()} wiv") })
        val section = if (used) "Using a raw chompy on Rantz" else "Talking to Rantz with a raw chompy"
        runQuestSection(npc, dialogue, section, hooks, speakers)
        setState(COOK)
    }

    /* Bugs and Fycie */

    private suspend fun ProtectedAccess.talkBugs(npc: Npc) {
        when {
            state < STARTED -> runQuestSection(npc, dialogue, "Talking to Bugs (3)", speakers = speakers)
            state == STARTED && !hasFlag(BOUGHT_TOOLS) -> {
                val coins = carries(BcObjs.coins, TOOLS_PRICE)
                val room = inv.freeSpace() >= 2
                var bought = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have 10 coins:" to { !coins },
                                "If the player has 10 coins and inventory space:" to { coins && room },
                            ),
                        options = mapOf("Ok, I'll give you 10 bright pretties." to { bought = coins && room }),
                    )
                runQuestSection(npc, dialogue, "Talking to Bugs without a chisel and knife", hooks, speakers)
                if (bought) {
                    invDel(inv, BcObjs.coins, TOOLS_PRICE)
                    invAdd(inv, BcObjs.chisel)
                    invAdd(inv, BcObjs.knife)
                    setFlag(BOUGHT_TOOLS)
                }
            }
            state == STARTED -> runQuestSection(npc, dialogue, "Talking to Bugs after buying his chisel and knife", speakers = speakers)
            state < SPOT -> runQuestSection(npc, dialogue, "Talking to Bugs", speakers = speakers)
            state < COOK -> runQuestSection(npc, dialogue, "Talking to Bugs (2)", speakers = speakers)
            else -> {
                val nodes = pickDialogue(dialogue.section("Getting the ingredient order from Bugs"), bugsPick())
                startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes) }
            }
        }
    }

    private suspend fun ProtectedAccess.talkFycie(npc: Npc) {
        when {
            state < STARTED -> runQuestSection(npc, dialogue, "Talking to Fycie (4)", speakers = speakers)
            state == STARTED && !hasFlag(BOUGHT_FEATHERS) -> {
                val coins = carries(BcObjs.coins, FEATHERS_PRICE)
                val room = inv.freeSpace() >= 1 || carries(BcObjs.feather)
                var bought = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have 50 coins:" to { !coins },
                                "If the player has 50 coins and inventory space:" to { coins && room },
                            ),
                        options = mapOf("Ok, I'll give you 50 bright pretties." to { bought = coins && room }),
                    )
                runQuestSection(npc, dialogue, "Talking to Fycie", hooks, speakers)
                if (bought) {
                    invDel(inv, BcObjs.coins, FEATHERS_PRICE)
                    invAdd(inv, BcObjs.feather, FEATHERS)
                    setFlag(BOUGHT_FEATHERS)
                }
            }
            state == STARTED -> runQuestSection(npc, dialogue, "Talking to Fycie after buying her feathers", speakers = speakers)
            state < SPOT -> runQuestSection(npc, dialogue, "Talking to Fycie (2)", speakers = speakers)
            state < COOK -> runQuestSection(npc, dialogue, "Talking to Fycie (3)", speakers = speakers)
            else -> {
                val nodes = pickDialogue(dialogue.section("Getting the ingredient order from Fycie"), fyciePick())
                startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes) }
            }
        }
    }

    /**
     * The Wiki records each npc's two possible lines as options "Dialogue 1" / "Dialogue 2"; keep
     * the picked one's children in place of the pair (dropping its "continues").
     */
    private fun pickDialogue(section: List<Node>, pick: Int): List<Node> {
        val out = ArrayList<Node>()
        for (node in section) {
            if (node is Node.Option && node.label.startsWith("Dialogue ")) {
                if (node.label == "Dialogue ${pick + 1}") {
                    out += node.children.filterNot { it is Node.Action && it.tag == "continues" }
                }
                continue
            }
            out += node
        }
        return out
    }

    /* The chest, the bellows and the toads */

    private suspend fun ProtectedAccess.unlockChest(chest: BoundLocInfo) {
        if (state < ARROWS) {
            // Authored line (not Wiki): no reason to rummage yet.
            mes("This chest belongs to Rantz. You'd better leave it alone.")
            return
        }
        runQuestSection(null, dialogue, "Getting the ogre bellows")
        if (random.of(0, 2) == 0) {
            // Authored line (not Wiki): the Wiki only says failed attempts drain Strength.
            mes("You strain to lift the huge rock, but it won't budge.")
            statSub(stats.strength, 1, 0)
            return
        }
        mes("You managed to lift the huge rock off the chest.")
        locRepo.change(chest, BcLocs.chest_open, CHEST_OPEN_TICKS)
    }

    private suspend fun ProtectedAccess.searchChest() {
        if (BcObjs.bellows.any { carries(it) }) {
            mes("You already have some ogre bellows.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            runQuestSection(null, dialogue, "Searching the unlocked chest with a full inventory")
            return
        }
        runQuestSection(null, dialogue, "Searching the unlocked chest")
        invAdd(inv, BcObjs.empty_bellows)
    }

    private fun ProtectedAccess.fillBellows(held: ObjType) {
        if (held == BcObjs.full_bellows) {
            mes("The bellows are already full of swamp gas.") // Authored (not Wiki).
            return
        }
        invReplace(inv, held, 1, BcObjs.full_bellows)
        mes("You collect some gas from the swamp.")
    }

    private suspend fun ProtectedAccess.inflateToad(toad: Npc) {
        val charged = BcObjs.chargedBellows.firstOrNull { carries(it) }
        if (charged == null) {
            // Authored line (not Wiki): the bellows need gas.
            mes(
                if (carries(BcObjs.empty_bellows)) "Your bellows are empty. Fill them at the swamp bubbles."
                else "You need some ogre bellows full of swamp gas to inflate the toad."
            )
            return
        }
        runQuestSection(null, dialogue, "Getting a bloated toad")
        invReplace(inv, charged, 1, BcObjs.bellowsAfter.getValue(charged))
        npcRepo.del(toad, TOAD_RESPAWN)
        mes("You manage to catch the toad and inflate it with the swamp gas.")
        if (invTotal(inv, BcObjs.bloated_toad) < MAX_TOADS) {
            invAdd(inv, BcObjs.bloated_toad)
            mes("You add the bloated toad to you inventory.")
        }
    }

    /* The bait and the hunt */

    private suspend fun ProtectedAccess.placeBait() {
        if (state < SPOT) {
            runQuestSection(null, dialogue, "Attempting to place the toad before Rantz tells you where")
            return
        }
        if (player.coords.level != BAIT_CENTRE.level || player.coords.chebyshevDistance(BAIT_CENTRE) > BAIT_RADIUS) {
            mes("This is too far away for Rantz to shoot the chompy bird.")
            return
        }
        val at = player.coords
        invDel(inv, BcObjs.bloated_toad)
        mes("You carefully place the bloated toad bait.")
        setFlag(BAIT_PLACED)
        val bait = Npc(npcTypes[BcNpcs.bloated_toad], at)
        npcRepo.add(bait, BAIT_TICKS)
        delay(BAIT_WAIT)
        // OSRS: the lure sometimes fails and the bait just disappears.
        if (random.of(0, 3) == 0) {
            if (bait.isSlotAssigned) npcRepo.del(bait, Int.MAX_VALUE)
            mes("The bloated toad has gone. Perhaps you should place another.") // Authored.
            return
        }
        val chompy = Npc(npcTypes[BcNpcs.chompy], at.translate(1, 0))
        if (bait.isSlotAssigned) npcRepo.del(bait, Int.MAX_VALUE)
        if (state == SPOT) {
            npcRepo.add(chompy, MISS_TICKS)
            chompy.say("Sqwark!")
            mes("Rantz: Hey, dere's da chompy, I's gonna shoot it.")
            mes("Rantz keeps missing the chompy bird...")
            mes("Rantz: Grrr...de'ese arrows are rubbish.")
            setState(MISSED)
            return
        }
        npcRepo.add(chompy, CHOMPY_TICKS)
        chompy.say("Sqwirk!")
    }

    private fun ogreBowsOnly(hit: HitBuilder) {
        if (hit.isFromPlayer && BcObjs.ogreBows.none { hit.isRighthandObj(it) }) {
            hit.damage = 0
        }
    }

    private fun chompyKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != BcNpcs.chompy.id) return
        val player = event.hero
        val dead = Npc(npcTypes[BcNpcs.chompy_dead], event.npc.coords)
        npcRepo.add(dead, CARCASS_TICKS)
        if (player.questState(BIG_CHOMPY) == BOW) {
            VarPlayerIntMapSetter.set(player, BcVarps.progress, player.vars[BcVarps.progress] or KILLED_OWN)
        }
    }

    private suspend fun ProtectedAccess.pluck(carcass: Npc) {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        mes("You start plucking the chompy bird.")
        npcRepo.del(carcass, Int.MAX_VALUE)
        invAdd(inv, BcObjs.raw_chompy)
        invAddOrDrop(objRepo, BcObjs.feather, random.of(10, 25))
        invAddOrDrop(objRepo, BcObjs.bones)
    }

    /* Cooking */

    private suspend fun ProtectedAccess.cookOnSpit() {
        if (stat(stats.cooking) < COOK_LEVEL) {
            mes("You need a Cooking level of $COOK_LEVEL to cook a chompy.") // Authored (not Wiki).
            return
        }
        if (state == COOK) {
            val wanted = listOf(rantzObj(), fycieObj(), bugsObj())
            val rewrite = { text: String -> ingredientText(text) }
            if (wanted.any { !carries(it) }) {
                runQuestSection(null, dialogue, "Attempting to cook the chompy without the requested ingredients", SimpleQuestHooks(rewrites = rewrite))
                return
            }
            mes("You carefully place the chompy bird on the spit-roast.")
            mes("You add the other ingredients and cook the food.")
            for (obj in wanted) invDel(inv, obj)
            invReplace(inv, BcObjs.raw_chompy, 1, BcObjs.seasoned_chompy)
            runQuestSection(null, dialogue, "Cooking the chompy", SimpleQuestHooks(rewrites = rewrite))
            return
        }
        // After the quest (or before the order): plain spit-roasting. Authored burn curve.
        mes("You carefully place the chompy bird on the spit-roast.")
        delay(SPIT_TICKS)
        val burnChance = (SPIT_BURN_BASE - (stat(stats.cooking) - COOK_LEVEL) * 2).coerceAtLeast(0)
        if (random.of(0, 99) < burnChance) {
            invReplace(inv, BcObjs.raw_chompy, 1, BcObjs.ruined_chompy)
            mes("You accidentally burn the chompy.") // Authored (not Wiki).
            return
        }
        invReplace(inv, BcObjs.raw_chompy, 1, BcObjs.cooked_chompy)
        statAdvance(stats.cooking, SPIT_XP * xpMods.get(player, stats.cooking))
        mes("You roast the chompy bird.") // Authored (not Wiki).
    }

    /* Ingredient picks (bc_progress bits 0-2): Rantz potato/onion, Fycie tomato/doogle leaves,
     * Bugs equa leaves/cabbage, in the Wiki's "Dialogue 1/2" order. */

    private fun ProtectedAccess.assignIngredients() {
        if (hasFlag(PICKED)) return
        val bits = random.of(0, 7)
        VarPlayerIntMapSetter.set(player, BcVarps.progress, (flags() and 0x7.inv()) or bits or PICKED)
    }

    private fun ProtectedAccess.rantzPick(): Int = flags() and 1

    private fun ProtectedAccess.fyciePick(): Int = (flags() shr 1) and 1

    private fun ProtectedAccess.bugsPick(): Int = (flags() shr 2) and 1

    private fun ProtectedAccess.rantzObj(): ObjType = BcObjs.rantzIngredients[rantzPick()]

    private fun ProtectedAccess.fycieObj(): ObjType = BcObjs.fycieIngredients[fyciePick()]

    private fun ProtectedAccess.bugsObj(): ObjType = BcObjs.bugsIngredients[bugsPick()]

    private fun ProtectedAccess.rantzName(): String = RANTZ_NAMES[rantzPick()]

    private fun ProtectedAccess.ingredientText(text: String): String {
        val r = RANTZ_NAMES[rantzPick()].lowercase()
        val f = FYCIE_NAMES[fyciePick()]
        val b = BUGS_NAMES[bugsPick()]
        return text
            .replace("[potato/onion]", r)
            .replace("[tomato/doogle leaves]]", f)
            .replace("[tomato/doogle leaves]", f)
            .replace("[cabbage/equa leaves]", b)
            .replace("[equa leaves/cabbage]", b)
    }

    companion object {
        const val STARTED = 10
        const val ARROWS = 20
        const val SPOT = 30
        const val MISSED = 40
        const val BOW = 50
        const val COOK = 60
        const val COMPLETE = 65

        private const val PICKED = 1 shl 3
        private const val BOUGHT_TOOLS = 1 shl 4
        private const val BOUGHT_FEATHERS = 1 shl 5
        private const val BAIT_PLACED = 1 shl 6
        private const val KILLED_OWN = 1 shl 7

        private const val ARROWS_NEEDED = 6
        private const val TOOLS_PRICE = 10
        private const val FEATHERS_PRICE = 50
        private const val FEATHERS = 25
        private const val BOW_PRICE = 500
        private const val MAX_TOADS = 3
        private const val TOAD_RESPAWN = 30
        private const val CHEST_OPEN_TICKS = 50
        private const val BAIT_TICKS = 30
        private const val BAIT_WAIT = 4
        private const val MISS_TICKS = 6
        private const val CHOMPY_TICKS = 100
        private const val CARCASS_TICKS = 100
        private const val COOK_LEVEL = 30
        private const val SPIT_XP = 140.0 // Wiki Cooked chompy recipe: spit-roast 140 xp, 5 ticks.
        private const val SPIT_BURN_BASE = 40
        private const val SPIT_TICKS = 5

        private const val FLETCHING_XP = 262.0
        private const val COOKING_XP = 1470.0
        private const val RANGED_XP = 735.0

        /** The clearing south of Rantz (2630,2981) where the bait goes (Wiki: "the chompy area"). */
        private val BAIT_CENTRE = CoordGrid(2632, 2968, 0)
        private const val BAIT_RADIUS = 10

        private val RANTZ_NAMES = listOf("Potato", "Onion")
        private val FYCIE_NAMES = listOf("tomato", "doogle leaves")
        private val BUGS_NAMES = listOf("equa leaves", "cabbage")

        val BIG_CHOMPY =
            QuestDef(
                name = "Big Chompy Bird Hunting",
                varp = { BcVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("262 Fletching XP", "1,470 Cooking XP", "735 Ranged XP", "Chompy bird hunting"),
                scrollObj = { BcObjs.seasoned_chompy },
                listRow = "quest_bigchompybirdhunting",
            )
    }
}

// Every cache ref Big Chompy Bird Hunting uses (re-check after cache updates).

internal object BcNpcs : NpcReferences() {
    /** Children of the `rantz` multi (pre-quest, and post-quest at exactly 65). */
    val rantzes = listOf(find("rantz_pre_quest"), find("rantz_post_quest"))
    val bugs = find("bugs")
    val fycie = find("fycie")
    val toad = find("toad")
    val bloated_toad = find("bloated_toad")
    val chompy = find("chompybird")
    val chompy_dead = find("chompybird_dead")
}

internal object BcLocs : LocReferences() {
    val chest = find("chompybird_chest")
    val chest_open = find("chompybird_chest_open")
    val bubbles = find("swampbubbles")
    /** The spit north of Rantz: `multi_chompybird_spitroast_entity` shows this child at 0. */
    val spit = find("chompybird_spitroast_empty")
}

internal object BcObjs : ObjReferences() {
    val ogre_arrow = find("ogre_arrow")
    val bloated_toad = find("bloated_toad")
    val raw_chompy = find("raw_chompy")
    val cooked_chompy = find("cooked_chompy")
    val ruined_chompy = find("ruined_chompy")
    val seasoned_chompy = find("cooked_s_chompy")
    val ogre_bow = find("ogre_bow")
    val empty_bellows = find("empty_ogre_bellows")
    val full_bellows = find("filled_ogre_bellow3")
    private val bellows2 = find("filled_ogre_bellow2")
    private val bellows1 = find("filled_ogre_bellow1")
    val bellows = listOf(empty_bellows, bellows1, bellows2, full_bellows)
    val chargedBellows = listOf(bellows1, bellows2, full_bellows)
    val bellowsAfter: Map<ObjType, ObjType> =
        mapOf(full_bellows to bellows2, bellows2 to bellows1, bellows1 to empty_bellows)
    val ogreBows = listOf(ogre_bow, find("zogre_bow"))
    val coins = find("coins")
    val chisel = find("chisel")
    val knife = find("knife")
    val feather = find("feather")
    val bones = find("bones")
    val rantzIngredients = listOf(find("potato"), find("onion"))
    val fycieIngredients = listOf(find("tomato"), find("doogleleaves"))
    val bugsIngredients = listOf(find("equa_leaves"), find("cabbage"))
    val allIngredients = rantzIngredients + fycieIngredients + bugsIngredients
}

internal object BcVarps : VarpReferences() {
    val quest = find("chompybird")

    /**
     * Server varp (65795): bits 0-2 the ingredient picks (Rantz, Fycie, Bugs), 3 picked, 4 Bugs'
     * tools bought, 5 Fycie's feathers bought, 6 bait placed, 7 killed own chompy.
     */
    val progress = find("bc_progress")
}

internal object BcVarpBuilds : VarpBuilder() {
    init {
        build("bc_progress")
    }
}
