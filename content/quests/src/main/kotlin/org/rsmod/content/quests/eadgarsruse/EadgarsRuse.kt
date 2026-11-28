package org.rsmod.content.quests.eadgarsruse

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.druidicritual.DruidicRitual.Companion.DRUIDIC_RITUAL
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.trollstronghold.TrollStronghold.Companion.TROLL_STRONGHOLD
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Eadgar's Ruse (OSRS Wiki). Cache quest varp `eadgar_quest`, end state 110 (needs Druidic Ritual,
 * Troll Stronghold and 31 Herblore, boostable). No cache multis key on the varp, so the stages are
 * ours; supplies and side flags live in server varp `er_progress`:
 * - 10 Sanfew (Taverley) wants the trolls' goutweed; his friend Eadgar lives in a cave at the top of
 *   Trollheim. 20 Burntmeat, the stronghold's head cook, wants a tasty human for his stew. 30 Eadgar
 *   has a plan and needs a parrot.
 * - Pineapple chunks on vodka make alco-chunks (after Parroty Pete, Ardougne Zoo, says what they
 *   eat); on the aviary they catch a drunk parrot. 40 Eadgar explains the fake man: logs, 10
 *   grain, 5 raw chickens, dirty clothes (Sanfew sends you to Tegid's laundry), and the parrot must
 *   learn what humans say: 50 hidden under the torture rack in the troll prison.
 * - Supplies handed over (any order, partial hand-ins kept) -> 60: the troll truth potion. A troll
 *   thistle (Trollheim's grass), dried on a fire, ground with a pestle and mortar, into a ranarr
 *   potion (unf) at 31 Herblore -> 70 given to Eadgar. 80 the parrot fetched back from the rack;
 *   90 Eadgar hands over the fake man; 100 Burntmeat takes it (burnt meat) and tells of the key in
 *   the kitchen drawers' false bottom. The storeroom key opens the storeroom; the goutweed crate
 *   is watched by guards (caught: a rock and back to the door).
 * - 110 the goutweed to Sanfew: 1 QP, 11,000 Herblore XP.
 *
 * Simplified: the guards' patrol is a flat 1 in 3 chance of being spotted per search; a lost drunk
 * parrot isn't tracked (Eadgar always has one); the parrot learns as soon as the potion is made.
 */
class EadgarsRuse
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(EadgarsRuse::class.java, "eadgars_ruse") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Eadgar" to ErNpcs.eadgar,
            "Parroty Pete" to ErNpcs.pete,
            "Parrot" to ErNpcs.parrots,
            "Fake man" to ErNpcs.fake_man,
            "Guard" to ErNpcs.guard,
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, ErNpcs.sanfew, EADGARS_RUSE, wants = { drDone() && state < COMPLETE }) {
                talkSanfew(it)
            }
            register(npcTypes, ErNpcs.eadgar, EADGARS_RUSE, wants = { state in STARTED until COMPLETE }) {
                talkEadgar(it)
            }
            default(npcTypes, ErNpcs.eadgar) {
                runQuestSection(it, dialogue, "Talking to Eadgar after finishing the quest")
            }
            default(npcTypes, ErNpcs.burntmeat) { talkBurntmeat(it) }
            default(npcTypes, ErNpcs.tegid) { talkTegid(it) }
            default(npcTypes, ErNpcs.pete) { talkPete(it) }
        }

        onLocOpLabel(locTypes, ErLocs.cave_entrance, "Enter") { telejump(CAVE_INSIDE) }
        onLocOpLabel(locTypes, ErLocs.cave_exit, "Exit") { telejump(CAVE_OUTSIDE) }
        onLocOpLabel(locTypes, ErLocs.laundry_basket, "Search") {
            runQuestSection(null, dialogue, "Searching the laundry basket")
        }

        onOpHeldU(ErObjs.pineapple_chunks, ErObjs.vodka) { makeAlcoChunks() }
        for (aviary in ErLocs.aviary) {
            onOpLocU(aviary, ErObjs.vodka) { runQuestSection(null, dialogue, "Using vodka on the aviary hatch") }
            onOpLocU(aviary, ErObjs.pineapple_chunks) {
                runQuestSection(null, dialogue, "Using pineapple chunks on the aviary hatch")
            }
            onOpLocU(aviary, ErObjs.alco_chunks) { catchParrot() }
        }

        onLocOpLabel(locTypes, ErLocs.rack, "Search") { searchRack() }
        onOpLocU(ErLocs.rack, ErObjs.drunk_parrot) { hideParrot() }

        onNpcOpLabel(npcTypes, ErNpcs.thistle, "Pick") { pickThistle(it) }
        for (type in locTypes.values) {
            if (type.name.equals("Fire", ignoreCase = true)) onOpLocU(type, ErObjs.thistle) { dryThistle() }
        }
        onOpHeldU(ErObjs.thistle, ErObjs.ranarr_unf) { mes("I need to dry it over a fire first.") }
        onOpHeldU(ErObjs.dried_thistle, ErObjs.ranarr_unf) { mes("It's too big to fit in the vial.") }
        onOpHeldU(ErObjs.dried_thistle, ErObjs.pestle) { grindThistle() }
        onOpHeldU(ErObjs.ground_thistle, ErObjs.ranarr_unf) { mixPotion() }

        onLocOpLabel(locTypes, ErLocs.drawers, "Open") { locRepo.change(it, ErLocs.drawers_open, DRAWER_TICKS) }
        onLocOpLabel(locTypes, ErLocs.drawers_open, "Close") { locRepo.change(it, ErLocs.drawers, DRAWER_TICKS) }
        onLocOpLabel(locTypes, ErLocs.drawers_open, "Search") { searchDrawers() }
        onLocOpLabel(locTypes, ErLocs.storeroom_door, "Open") { storeroomDoor(it) }
        onOpLocU(ErLocs.storeroom_door, ErObjs.storeroom_key) { storeroomDoor(it.loc) }
        onLocOpLabel(locTypes, ErLocs.goutweed_crate, "Search") { searchCrate() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(EADGARS_RUSE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(EADGARS_RUSE, value)
    }

    private fun ProtectedAccess.drDone(): Boolean = player.isQuestComplete(DRUIDIC_RITUAL)

    private fun ProtectedAccess.progress(): Int = player.vars[ErVarps.progress]

    private fun ProtectedAccess.setProgress(value: Int) {
        VarPlayerIntMapSetter.set(player, ErVarps.progress, value)
    }

    private fun ProtectedAccess.hasFlag(bit: Int): Boolean = (progress() and bit) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        setProgress(progress() or bit)
    }

    private fun ProtectedAccess.give(obj: ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    /* Sanfew, Tegid and the laundry */

    private suspend fun ProtectedAccess.talkSanfew(npc: Npc) {
        when {
            state == 0 -> startQuest(npc)
            state >= GIVEN && carries(ErObjs.goutweed) -> {
                runQuestSection(npc, dialogue, "Talking to Sanfew after getting goutweed")
                invDel(inv, ErObjs.goutweed)
                statAdvance(stats.herblore, HERBLORE_XP * xpMods.get(player, stats.herblore))
                completeQuest(EADGARS_RUSE)
            }
            state in SHOWN until FAKE_MAN && !hasFlag(ROBE) && !carries(ErObjs.dirty_robe) ->
                runQuestSection(npc, dialogue, "Talking to Sanfew about the robes")
            else -> runQuestSection(npc, dialogue, "Talking to Sanfew after starting the quest")
        }
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        val quests = player.isQuestComplete(TROLL_STRONGHOLD)
        val herblore = stat(stats.herblore) >= HERBLORE_LEVEL
        var start = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have the requirements to start Eadgar's Ruse." to { !quests },
                        "If the player has the requirements for Eadgar's Ruse except Herblore level:" to {
                            !herblore
                        },
                        "If the player has the requirements to start Eadgar's Ruse." to { true },
                        "If the player boosted Herblore to start the quest." to {
                            statBase(stats.herblore) < HERBLORE_LEVEL
                        },
                    ),
                options = mapOf("Yes." to { start = true }),
            )
        runQuestSection(npc, dialogue, "Talking to Sanfew", hooks)
        if (start && quests && herblore) setState(STARTED)
    }

    private suspend fun ProtectedAccess.talkTegid(npc: Npc) {
        val wants = state in SHOWN until FAKE_MAN && !hasFlag(ROBE) && !carries(ErObjs.dirty_robe)
        if (!wants) {
            runQuestSection(npc, dialogue, "Talking to Tegid")
            return
        }
        val hooks = SimpleQuestHooks(actions = mapOf("Drops to ground with full inventory" to { give(ErObjs.dirty_robe) }))
        runQuestSection(npc, dialogue, "Talking to Tegid about the robes", hooks)
    }

    /* Burntmeat */

    private suspend fun ProtectedAccess.talkBurntmeat(npc: Npc) {
        when {
            state == 0 -> // Authored (not Wiki): the quest's own lines start with it.
                startDialogue(npc) { chatNpc(neutral, "Human go away. Burntmeat busy cooking.") }
            state == STARTED -> {
                runQuestSection(npc, dialogue, "Talking to Burntmeat")
                setState(BURNTMEAT)
            }
            state == FAKE_MAN && carries(ErObjs.fake_man) -> {
                runQuestSection(npc, dialogue, "Talking to Burntmeat while holding the fake man", speakers = speakers)
                invDel(inv, ErObjs.fake_man)
                give(ErObjs.burnt_meat)
                setState(GIVEN)
            }
            state >= GIVEN -> runQuestSection(npc, dialogue, "Talking to Burntmeat after giving him the fake man")
            else -> runQuestSection(npc, dialogue, "Talking to Burntmeat again")
        }
    }

    /* Eadgar */

    private suspend fun ProtectedAccess.talkEadgar(npc: Npc) {
        when (state) {
            STARTED -> {
                runQuestSection(npc, dialogue, "Taking to Eadgar before talking to Burntmeat")
                setFlag(MET_EADGAR)
            }
            BURNTMEAT -> {
                val met = hasFlag(MET_EADGAR)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player talked to Eadgar before talking to Burntmeat." to { met },
                                "If the player did not talk to Eadgar before talking to Burntmeat." to { !met },
                            )
                    )
                runQuestSection(npc, dialogue, "Taking to Eadgar after talking to Burntmeat", hooks)
                setState(PARROT)
            }
            PARROT ->
                if (carries(ErObjs.drunk_parrot)) {
                    runQuestSection(npc, dialogue, "Talking to Eadgar while holding the parrot", speakers = speakers)
                    setState(SHOWN)
                } else {
                    runQuestSection(npc, dialogue, "Taking to Eadgar again before getting a parrot")
                }
            SHOWN -> {
                if (!carries(ErObjs.drunk_parrot)) setFlag(PARROT_FLEW)
                runQuestSection(npc, dialogue, "Talking to Eadgar before preparing the fake man")
            }
            HIDDEN -> supplies(npc)
            SUPPLIES ->
                if (carries(ErObjs.troll_potion)) {
                    runQuestSection(npc, dialogue, "Talking to Eadgar with the troll potion")
                    invDel(inv, ErObjs.troll_potion)
                    setState(POTION)
                } else {
                    runQuestSection(npc, dialogue, "Talking to Eadgar after learning to make troll potion")
                }
            POTION -> runQuestSection(npc, dialogue, "Talking to Eadgar with the troll potion")
            PARROT_BACK -> {
                val has = carries(ErObjs.drunk_parrot)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player lost the parrot:" to { !has }, "If the player still has the parrot:" to { has })
                    )
                runQuestSection(npc, dialogue, "Talking to Eadgar after taking the parrot out from the rack", hooks, speakers)
                if (has) invDel(inv, ErObjs.drunk_parrot)
                give(ErObjs.fake_man)
                setState(FAKE_MAN)
            }
            FAKE_MAN -> {
                val has = carries(ErObjs.fake_man)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player still has the fake man:" to { has }, "If the player lost the fake man:" to { !has }),
                        actions = mapOf("Drops to ground with full inventory" to { if (!has) give(ErObjs.fake_man) }),
                    )
                runQuestSection(npc, dialogue, "Talking to Eadgar after getting the fake man", hooks)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Eadgar after getting access to the storeroom")
        }
    }

    /** Hidden parrot: hand over what the player carries, then report what's still needed. */
    private suspend fun ProtectedAccess.supplies(npc: Npc) {
        val before = progress()
        if (!hasFlag(LOGS) && carries(ErObjs.logs)) {
            invDel(inv, ErObjs.logs)
            setFlag(LOGS)
        }
        val chickens = (progress() shr CHICKEN_SHIFT) and CHICKEN_MASK
        val takeChickens = minOf(CHICKENS - chickens, invTotal(inv, ErObjs.raw_chicken))
        if (takeChickens > 0) {
            invDel(inv, ErObjs.raw_chicken, takeChickens)
            setProgress((progress() and (CHICKEN_MASK shl CHICKEN_SHIFT).inv()) or ((chickens + takeChickens) shl CHICKEN_SHIFT))
        }
        val grain = (progress() shr GRAIN_SHIFT) and GRAIN_MASK
        val takeGrain = minOf(GRAIN - grain, invTotal(inv, ErObjs.grain))
        if (takeGrain > 0) {
            invDel(inv, ErObjs.grain, takeGrain)
            setProgress((progress() and (GRAIN_MASK shl GRAIN_SHIFT).inv()) or ((grain + takeGrain) shl GRAIN_SHIFT))
        }
        if (!hasFlag(ROBE) && carries(ErObjs.dirty_robe)) {
            invDel(inv, ErObjs.dirty_robe)
            setFlag(ROBE)
        }
        val done = suppliesDone()
        val rewrites = { text: String ->
            when {
                text.startsWith("I have [no/the] logs") -> "I have ${supplyList(given = true)}."
                text.startsWith("You now need [no/the] logs") -> "You now need ${supplyList(given = false)}."
                else -> text
            }
        }
        if (progress() == before && !done) {
            val hooks = SimpleQuestHooks(rewrites = rewrites)
            runQuestSection(npc, dialogue, "Talking to Eadgar after putting the parrot under the rack", hooks)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player has not yet given all the supplies:" to { !done }),
                rewrites = rewrites,
            )
        runQuestSection(npc, dialogue, "Talking to Eadgar with the supplies", hooks)
        if (done) setState(SUPPLIES)
    }

    private fun ProtectedAccess.suppliesDone(): Boolean =
        hasFlag(LOGS) &&
            hasFlag(ROBE) &&
            ((progress() shr CHICKEN_SHIFT) and CHICKEN_MASK) >= CHICKENS &&
            ((progress() shr GRAIN_SHIFT) and GRAIN_MASK) >= GRAIN

    /** "the logs, two chickens, ten bundles of grain and no dirty clothes" (given, or still needed). */
    private fun ProtectedAccess.supplyList(given: Boolean): String {
        val chickens = (progress() shr CHICKEN_SHIFT) and CHICKEN_MASK
        val grain = (progress() shr GRAIN_SHIFT) and GRAIN_MASK
        val c = if (given) chickens else CHICKENS - chickens
        val g = if (given) grain else GRAIN - grain
        fun has(flag: Int) = if (hasFlag(flag) == given) "the" else "no"
        val chickenWord = if (c == 1) "chicken" else "chickens"
        val grainWord = if (g == 1) "bundle" else "bundles"
        val grainText = "${NUMBERS[g]} $grainWord of grain"
        return "${has(LOGS)} logs, ${NUMBERS[c]} $chickenWord, $grainText and ${has(ROBE)} dirty clothes"
    }

    /* The parrot */

    private suspend fun ProtectedAccess.talkPete(npc: Npc) {
        val hooks = SimpleQuestHooks(options = mapOf("What do you feed them?" to { setFlag(ASKED_PETE) }))
        runQuestSection(npc, dialogue, "Talking to Parroty Pete", hooks)
    }

    private fun ProtectedAccess.makeAlcoChunks() {
        if (state < PARROT || !hasFlag(ASKED_PETE)) {
            mes("Why would you want to do that?")
            return
        }
        invDel(inv, ErObjs.vodka)
        invDel(inv, ErObjs.pineapple_chunks)
        give(ErObjs.alco_chunks)
        mes("You mix the pineapple chunks with the vodka.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.catchParrot() {
        if (state < PARROT) {
            mes("Why would you want to do that?")
            return
        }
        val early = state < SHOWN
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not spoken to Eadgar with the drunk parrot in their inventory:" to { early },
                        "If the player has spoken to Eadgar with the drunk parrot in their inventory:" to { !early },
                    )
            )
        if (early && carries(ErObjs.drunk_parrot)) return
        invDel(inv, ErObjs.alco_chunks)
        runQuestSection(null, dialogue, "Using alco-chunks on the hatch", hooks, speakers)
        if (early) give(ErObjs.drunk_parrot) else mes("The parrott flew off, you need to find it again.")
    }

    private suspend fun ProtectedAccess.hideParrot() {
        if (state != SHOWN) {
            mes("Why would you want to do that?")
            return
        }
        invDel(inv, ErObjs.drunk_parrot)
        mes("You hide the parrot under the torture rack.")
        setState(HIDDEN)
    }

    private suspend fun ProtectedAccess.searchRack() {
        when (state) {
            HIDDEN,
            SUPPLIES -> {
                val nodes = pickDialogue(dialogue.section("Search rack after putting parrot under it"), random.of(0, 2))
                startDialogue { QuestDialogueRunner(SimpleQuestHooks(), speakers).run(this, nodes) }
            }
            POTION -> {
                runQuestSection(null, dialogue, "Taking the parrot from under the rack", speakers = speakers)
                give(ErObjs.drunk_parrot)
                setState(PARROT_BACK)
            }
            else -> runQuestSection(null, dialogue, "Searching the rack")
        }
    }

    /** The section with only the "Dialogue [pick + 1]" option's lines kept (the Wiki's variants). */
    private fun pickDialogue(section: List<Node>, pick: Int): List<Node> {
        val out = ArrayList<Node>()
        for (node in section) {
            if (node is Node.Option && node.label.startsWith("Dialogue ")) {
                if (node.label == "Dialogue ${pick + 1}") out += node.children
                continue
            }
            out += node
        }
        return out
    }

    /* The troll truth potion */

    private fun ProtectedAccess.pickThistle(thistle: Npc) {
        if (state !in STARTED until COMPLETE) return
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        npcRepo.del(thistle, THISTLE_RESPAWN)
        give(ErObjs.thistle)
        mes("You pick the Troll Thistle.")
    }

    private suspend fun ProtectedAccess.dryThistle() {
        delay(1)
        invReplace(inv, ErObjs.thistle, 1, ErObjs.dried_thistle)
        mes("You dry the troll thistle over the fire.")
    }

    private fun ProtectedAccess.grindThistle() {
        invReplace(inv, ErObjs.dried_thistle, 1, ErObjs.ground_thistle)
        mes("You grind the Troll Thistle.")
    }

    private fun ProtectedAccess.mixPotion() {
        if (stat(stats.herblore) < HERBLORE_LEVEL) {
            mes("You need a Herblore level of 31 to make this potion.") // Authored (not Wiki).
            return
        }
        invDel(inv, ErObjs.ground_thistle)
        invReplace(inv, ErObjs.ranarr_unf, 1, ErObjs.troll_potion)
        mes("You add the ground Troll Thistle to the potion.")
    }

    /* The storeroom */

    private fun ProtectedAccess.searchDrawers() {
        mes("You search the drawers...")
        when {
            state < GIVEN || carries(ErObjs.storeroom_key) -> mes("You don't find anything.")
            inv.isFull() ->
                mes("You open the fake bottom of the drawer and see a key, but don't have space to take it.")
            else -> {
                give(ErObjs.storeroom_key)
                mes("You open the fake bottom of the drawer and find the storeroom key.")
            }
        }
    }

    private suspend fun ProtectedAccess.storeroomDoor(door: BoundLocInfo) {
        if (!carries(ErObjs.storeroom_key)) {
            mes("This door is locked.") // Authored (not Wiki).
            return
        }
        mes("You unlock the door.")
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.searchCrate() {
        if (random.of(1, SPOT_ODDS) == 1) {
            mes("A guard spots you and throws a rock!") // Authored (not Wiki): the guard's "!" overhead.
            player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(0, 6))
            telejump(STOREROOM_ENTRANCE)
            return
        }
        mesbox("You've found some goutweed!")
        give(ErObjs.goutweed)
    }

    companion object {
        const val STARTED = 10
        const val BURNTMEAT = 20
        const val PARROT = 30
        const val SHOWN = 40
        const val HIDDEN = 50
        const val SUPPLIES = 60
        const val POTION = 70
        const val PARROT_BACK = 80
        const val FAKE_MAN = 90
        const val GIVEN = 100
        const val COMPLETE = 110

        // er_progress bits.
        private const val MET_EADGAR = 1 shl 0
        private const val LOGS = 1 shl 1
        private const val CHICKEN_SHIFT = 2 // bits 2-4: raw chickens handed over (0-5)
        private const val CHICKEN_MASK = 7
        private const val GRAIN_SHIFT = 5 // bits 5-8: grain handed over (0-10)
        private const val GRAIN_MASK = 15
        private const val ROBE = 1 shl 9
        private const val ASKED_PETE = 1 shl 10
        private const val PARROT_FLEW = 1 shl 11

        private const val CHICKENS = 5
        private const val GRAIN = 10
        private val NUMBERS = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

        private const val HERBLORE_LEVEL = 31
        private const val HERBLORE_XP = 11_000.0
        private const val DRAWER_TICKS = 100
        private const val THISTLE_RESPAWN = 50
        private const val SPOT_ODDS = 3

        private val CAVE_INSIDE = CoordGrid(2893, 10074, 0)
        private val CAVE_OUTSIDE = CoordGrid(2893, 3671, 0)
        /** The corridor outside the goutweed room's door (where caught players are sent). */
        private val STOREROOM_ENTRANCE = CoordGrid(2862, 10092, 0)

        val EADGARS_RUSE =
            QuestDef(
                name = "Eadgar's Ruse",
                varp = { ErVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("11,000 Herblore XP", "Goutweed (trade it to Sanfew for herbs)"),
                scrollObj = { ErObjs.goutweed },
                listRow = "quest_eadgarsruse",
            )
    }
}

// Every cache ref Eadgar's Ruse uses (re-check after cache updates).

internal object ErNpcs : NpcReferences() {
    val sanfew = find("sanfew")
    val eadgar = find("troll_eadgar")
    val burntmeat = find("eadgar_troll_chief_cook")
    val tegid = find("eadgar_druid_washing")
    val pete = find("eadgar_zoo_keeper_aviary")
    val parrots = find("eadgar_parrotts")
    val fake_man = find("eadgar_fake_man")
    val thistle = find("eadgar_troll_thistle")
    val guard = find("troll_sguard1")
}

internal object ErLocs : LocReferences() {
    val cave_entrance = find("troll_mad_eadgar_entrance")
    val cave_exit = find("troll_mad_eadgar_exit")
    val laundry_basket = find("eadgar_laundry_basket")
    val aviary = listOf(find("eadgar_aviary_wall"), find("eadgar_aviary_wall_hatch"))
    val rack = find("eadgar_rack")
    val drawers = find("eadgar_kitchen_drawers")
    val drawers_open = find("eadgar_kitchen_drawers_open")
    val storeroom_door = find("eadgar_storeroomdoor")
    val goutweed_crate = find("eadgar_crate_goutweed")
}

internal object ErObjs : ObjReferences() {
    val vodka = find("vodka")
    val pineapple_chunks = find("pineapple_chunks")
    val alco_chunks = find("eadgar_alco_chunks")
    val drunk_parrot = find("eadgar_drunk_parrot")
    val dirty_robe = find("eadgar_dirty_druid_robe")
    val fake_man = find("eadgar_fake_man")
    val logs = find("logs")
    val raw_chicken = find("raw_chicken")
    val grain = find("grain")
    val thistle = find("eadgar_troll_thistle")
    val dried_thistle = find("eadgar_dried_troll_thistle")
    val ground_thistle = find("eadgar_ground_troll_thistle")
    val troll_potion = find("eadgar_ground_troll_thistle_potion")
    val ranarr_unf = find("ranarrvial")
    val pestle = find("pestle_and_mortar")
    val burnt_meat = find("burnt_meat")
    val storeroom_key = find("eadgar_troll_storeroom_key")
    val goutweed = find("eadgar_goutweed_herb")
}

internal object ErVarps : VarpReferences() {
    val quest = find("eadgar_quest")

    /** Server varp (65796): see [EadgarsRuse] (er_progress bits in its companion). */
    val progress = find("er_progress")
}

internal object ErVarpBuilds : VarpBuilder() {
    init {
        build("er_progress")
    }
}
