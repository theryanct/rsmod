package org.rsmod.content.quests.gettingahead

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Getting Ahead (OSRS Wiki). Progress lives in the cache quest varbit `ga` (varp `ga_main` bits
 * 0-5, db end state 34) and its sibling varbits (`ga_gordon_met`, `ga_journal`, `ga_nails_given`,
 * `ga_reward`, `ga_mary_dialogue`). Stages are ours but follow the cache multilocs: the cattle gate
 * shows flour at 8-12, the flour trail at 10-12, the Mounted Head Space hotspot at 30 and the
 * mounted head from 31.
 * - 2 Gordon asks for the beast's head (Crafting 30, Construction 26); 4 Mary's flour idea; 6
 *   Gordon names the cattle gate; 10 a pot of flour on the gate: the trail leads to the cave by the
 *   Kebos Lowlands mine.
 * - 12 the Headless Beast killed (spawned in its lair while the trail is fresh); the skeleton
 *   holds Neilan's journal. 14 Gordon refuses to pay; 16 Mary suggests a fake head.
 * - Knife on soft clay: clay head (18), shown 20; fur (any of three) on it with needle and thread:
 *   fur head (22), shown 24; red dye on it: bloody head (26); shown: 30, mount it (hammer, saw, 2
 *   planks, 6 nails of any kind): 32; Gordon: 34, 1 QP, 3,000 coins, 4,000 Crafting and 3,200
 *   Construction XP (db stat_xp_awarded 40000/32000 tenths), Mary's tanning.
 *
 * Side content: soft clay (bucket of water on clay, bucket filled at the house's sink), the
 * house doors and spiral stairs, the shelves' dyes, the workbench nails, the mine's pickaxe and
 * the barrel of flour; the cave and its exit; Mary's tanning (soft/hard leather and dragonhides at
 * the usual tanner prices).
 *
 * Simplified: the Headless Beast is plain NPC melee (no falling-rock special); the completion
 * cutscene is skipped; tanning is a chat menu (tans every hide of the picked kind) rather than the
 * tanning interface; the Sergeant isn't spawned (no Wiki position), his dialogue is bound in case.
 */
class GettingAhead
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(GettingAhead::class.java, "getting_ahead") }

    /** The Headless Beast spawned for each player (single player, keyed for safety). */
    private val beasts = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, GaNpcs.gordon, GETTING_AHEAD, wants = { true }) { talkGordon(it) }
            for (mary in GaNpcs.marys) register(npcTypes, mary, GETTING_AHEAD, wants = { true }) { talkMary(it) }
            register(npcTypes, GaNpcs.sergeant, GETTING_AHEAD, wants = { true }) {
                runQuestSection(it, dialogue, "Talking to the Sergeant")
            }
        }
        onNpcOpLabel(npcTypes, GaNpcs.mary_trade, "Trade") { if (state >= COMPLETE) tan(it) }

        // Items shown to Gordon.
        onOpNpcU(GaNpcs.gordon, GaObjs.clay_head) { showHead(it.npc, GaObjs.clay_head) }
        onOpNpcU(GaNpcs.gordon, GaObjs.fur_head) { showHead(it.npc, GaObjs.fur_head) }
        onOpNpcU(GaNpcs.gordon, GaObjs.bloody_head) { showHead(it.npc, GaObjs.bloody_head) }
        onOpNpcU(GaNpcs.gordon, GaObjs.journal) { showOther(it.npc, "Neilan's journal") }
        onOpNpcU(GaNpcs.gordon, GaObjs.big_bones) { showOther(it.npc, "Big bones") }
        onOpNpcU(GaNpcs.gordon, GaObjs.ensouled_bear) { showOther(it.npc, "Ensouled bear head") }
        onOpNpcU(GaNpcs.gordon, GaObjs.bearhead) { showOther(it.npc, "Bearhead") }

        // The flour trail.
        for (gate in GaLocs.flour_gates) onOpLocU(gate, GaObjs.pot_flour) { flourGate() }
        for (gate in GaLocs.gate_halves) onLocOpLabel(locTypes, gate, "Open") { passThroughWall(it) }

        // The lair.
        onLocOpLabel(locTypes, GaLocs.cave, "Enter") { enterCave() }
        onLocOpLabel(locTypes, GaLocs.cave_out, "Exit") { leaveCave() }
        onLocOpLabel(locTypes, GaLocs.cave_out_instance, "Escape") { leaveCave() }
        onLocOpLabel(locTypes, GaLocs.skeleton, "Search") { searchSkeleton() }
        onEvent<NpcKilledEvent> { beastKilled(this) }
        onHeldOpLabel(objTypes, GaObjs.journal, "Read") { readJournal() }

        // The fake heads.
        onOpHeldU(GaObjs.knife, GaObjs.soft_clay) { makeClayHead() }
        for (fur in GaObjs.furs) onOpHeldU(GaObjs.clay_head, fur) { makeFurHead(fur) }
        onOpHeldU(GaObjs.fur_head, GaObjs.red_dye) { makeBloodyHead() }
        for (blood in GaObjs.bloods) {
            onOpHeldU(GaObjs.fur_head, blood) {
                runQuestSection(null, dialogue, "Using a vial of blood or a blood pint on the fur head")
            }
        }
        onOpHeldU(GaObjs.fur_head, GaObjs.raw_bear_meat) {
            mes("Whatever you're trying to do here definitely won't work.")
        }
        onLocOpLabel(locTypes, GaLocs.head_hotspot, "Build") { mountHead() }

        // The farm.
        onLocOpLabel(locTypes, GaLocs.shelves, "Search") { searchShelves() }
        onLocOpLabel(locTypes, GaLocs.workbench, "Search") { searchWorkbench() }
        onLocOpLabel(locTypes, GaLocs.rocks_pickaxe, "Take-pickaxe") { takePickaxe() }
        onLocOpLabel(locTypes, GaLocs.flour_barrel, "Take-from") { takeFlour() }
        onOpLocU(GaLocs.sink, GaObjs.bucket_empty) { fillBucket() }
        onOpHeldU(GaObjs.bucket_water, GaObjs.clay) { softenClay() }
        for (door in GaLocs.doors) bindOpenIfFree(door)
        onLocOpLabel(locTypes, GaLocs.stairs_bottom, "Climb-up") { climbStairs(it, up = true) }
        onLocOpLabel(locTypes, GaLocs.stairs_top, "Climb-down") { climbStairs(it, up = false) }
    }

    private fun ScriptContext.bindOpenIfFree(type: LocType) {
        val op = locTypes[type].op.indexOfFirst { it.equals("Open", ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(type, op)) return
        onLocOpLabel(locTypes, type, "Open") { passThroughWall(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(GETTING_AHEAD)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(GETTING_AHEAD, value)
    }

    private fun ProtectedAccess.advance(value: Int) {
        if (state < value) setState(value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /** Transcript hooks: conditions by exact Wiki text, option side effects, and box/actions. */
    private class Hooks(
        private val conditions: Map<String, () -> Boolean> = emptyMap(),
        private val options: Map<String, () -> Unit> = emptyMap(),
    ) : QuestDialogueHooks {
        var tan = false

        override fun condition(text: String): Boolean = conditions[text]?.invoke() ?: false

        override suspend fun Dialogue.onOption(label: String) {
            options[label]?.invoke()
        }

        override suspend fun Dialogue.onAction(tag: String) {
            when {
                tag == "tan" -> tan = true
                tag.startsWith("It really doesn't") -> mesbox(tag)
            }
        }
    }

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: QuestDialogueHooks = Hooks()) {
        runQuestSection(npc, dialogue, title, hooks)
    }

    /* Gordon */

    private suspend fun ProtectedAccess.talkGordon(npc: Npc) {
        when {
            state == 0 -> startQuest(npc)
            state >= COMPLETE -> gordonAfterQuest(npc)
            state == STARTED -> section(npc, "Talking to Gordon again after starting the quest")
            state == MARY || state == CATTLE -> {
                section(npc, "Talking to Gordon after talking to Mary")
                advance(CATTLE)
            }
            state == FLOURED -> section(npc, "Talking to Gordon after pouring the flour")
            state == KILLED -> {
                section(npc, "Talking to Gordon after killing the Headless Beast")
                setState(REFUSED)
            }
            state == MOUNTED -> finish(npc)
            state >= MOUNT -> section(npc, "Talking to Gordon again after making the bloody head")
            carries(GaObjs.bloody_head) -> showHead(npc, GaObjs.bloody_head, used = false)
            carries(GaObjs.fur_head) -> showHead(npc, GaObjs.fur_head, used = false)
            carries(GaObjs.clay_head) -> showHead(npc, GaObjs.clay_head, used = false)
            state >= FUR_MADE -> section(npc, "Talking to Gordon again after making the fur head")
            state >= CLAY_MADE -> section(npc, "Talking to Gordon again after making the clay head")
            else -> section(npc, "Talking to Gordon again after killing the Headless Beast")
        }
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        val eligible =
            statBase(stats.crafting) >= CRAFTING_REQ && statBase(stats.construction) >= CONSTRUCTION_REQ
        var start = false
        val hooks =
            Hooks(
                conditions =
                    mapOf(
                        "If the player does not have the requirements to start the quest:" to { !eligible },
                        "If the player has the requirements to start the quest:" to { eligible },
                    ),
                options = mapOf("Yes." to { start = true }),
            )
        val title =
            if (bit(GaVarBits.gordon_met) == 0) "Talking to Gordon" else "Talking to Gordon again before starting the quest"
        setBit(GaVarBits.gordon_met, 1)
        section(npc, title, hooks)
        if (start && eligible) setState(STARTED)
    }

    /** A head shown to Gordon, by Talk-to ([used] false) or by using it on him. */
    private suspend fun ProtectedAccess.showHead(npc: Npc, head: ObjType, used: Boolean = true) {
        if (state !in FAKE until MOUNT) {
            if (used) mes("Nothing interesting happens.")
            return
        }
        val name =
            when (head.id) {
                GaObjs.bloody_head.id -> "bloody"
                GaObjs.fur_head.id -> "fur"
                else -> "clay"
            }
        val talk = dialogue.section("Talking to Gordon after making the $name head")
        // Using the head skips Gordon's greeting, the box and the player's boast (the use section
        // has its own box and boast).
        val nodes = if (used) dialogue.section("Using the $name head on Gordon") + talk.drop(3) else talk
        val hooks = Hooks()
        startDialogue(npc) { QuestDialogueRunner(hooks).run(this, nodes) }
        when (head.id) {
            GaObjs.bloody_head.id -> advance(MOUNT)
            GaObjs.fur_head.id -> advance(FUR_SHOWN)
            else -> advance(CLAY_SHOWN)
        }
    }

    private suspend fun ProtectedAccess.showOther(npc: Npc, title: String) {
        if (state < KILLED || state >= MOUNT) {
            mes("Nothing interesting happens.")
            return
        }
        section(npc, title)
    }

    private suspend fun ProtectedAccess.finish(npc: Npc) {
        section(npc, "Talking to Gordon after mounting the bloody head")
        if (inv.freeSpace() > 0 || carries(GaObjs.coins)) {
            invAdd(inv, GaObjs.coins, REWARD_COINS)
            setBit(GaVarBits.reward, 1)
        }
        statAdvance(stats.crafting, CRAFTING_XP * xpMods.get(player, stats.crafting))
        statAdvance(stats.construction, CONSTRUCTION_XP * xpMods.get(player, stats.construction))
        completeQuest(GETTING_AHEAD)
    }

    private suspend fun ProtectedAccess.gordonAfterQuest(npc: Npc) {
        val paid = bit(GaVarBits.reward) == 1
        val hooks =
            Hooks(
                conditions =
                    mapOf(
                        "If the player received a 3,000 coins reward from the quest:" to { paid },
                        "If the player didn't receive a 3,000 coins reward from the quest:" to { !paid },
                    ),
            )
        if (!paid && inv.freeSpace() == 0 && !carries(GaObjs.coins)) {
            mes("You need some inventory space to take your reward.") // Authored (not Wiki).
            return
        }
        section(npc, "Talking to Gordon after the quest", hooks)
        if (!paid) {
            invAdd(inv, GaObjs.coins, REWARD_COINS)
            setBit(GaVarBits.reward, 1)
        }
    }

    /* Mary */

    private suspend fun ProtectedAccess.talkMary(npc: Npc) {
        val title =
            when {
                state == 0 -> "Talking to Mary before the quest"
                state == STARTED -> "Talking to Mary"
                state < FLOURED -> "Talking to Mary again"
                state == FLOURED -> "Talking to Mary after pouring the flour"
                state == KILLED -> "Talking to Mary after killing the beast but before talking to Gordon"
                state == REFUSED -> "Talking to Mary after killing the beast"
                state == FAKE -> "Talking to Mary again after killing the beast"
                state == CLAY_MADE -> "Talking to Mary after making the clay head"
                state == CLAY_SHOWN -> "Talking to Mary after showing Gordon the clay head"
                state == FUR_MADE -> "Talking to Mary after making the fur head"
                state == FUR_SHOWN -> "Talking to Mary after showing Gordon the fur head"
                state == BLOODY_MADE -> "Talking to Mary after making the bloody head"
                state == MOUNT -> "Talking to Mary after showing Gordon the bloody head"
                state == MOUNTED -> "Talking to Mary after mounting the bloody head"
                bit(GaVarBits.mary_dialogue) == 0 -> "Talking to Mary after the quest"
                else -> "Talking to Mary after unlocking tanning"
            }
        val hooks = Hooks()
        section(npc, title, hooks)
        when (state) {
            STARTED -> setState(MARY)
            REFUSED -> setState(FAKE)
            COMPLETE -> setBit(GaVarBits.mary_dialogue, 1)
        }
        if (hooks.tan) tan(npc)
    }

    private class Tan(val label: String, val hide: ObjType, val leather: ObjType, val price: Int)

    private suspend fun ProtectedAccess.tan(npc: Npc) {
        val offers =
            listOf(
                Tan("Soft leather (1 coin)", GaObjs.cow_hide, GaObjs.leather, 1),
                Tan("Hard leather (3 coins)", GaObjs.cow_hide, GaObjs.hard_leather, 3),
                Tan("Green d'hide (20 coins)", GaObjs.green_hide, GaObjs.green_leather, 20),
                Tan("Blue d'hide (20 coins)", GaObjs.blue_hide, GaObjs.blue_leather, 20),
                Tan("Red d'hide (20 coins)", GaObjs.red_hide, GaObjs.red_leather, 20),
                Tan("Black d'hide (20 coins)", GaObjs.black_hide, GaObjs.black_leather, 20),
            ).filter { carries(it.hide) }.take(MAX_CHOICES)
        if (offers.isEmpty()) {
            startDialogue(npc) { chatNpc(neutral, "You don't have any hides I can tan.") } // Authored.
            return
        }
        var picked = 0
        if (offers.size > 1) {
            startDialogue(npc) { picked = menu(offers.map { it.label }) }
        }
        val offer = offers.getOrNull(picked) ?: return
        val count = minOf(invTotal(inv, offer.hide), invTotal(inv, GaObjs.coins) / offer.price)
        if (count == 0) {
            startDialogue(npc) { chatNpc(neutral, "You don't have enough coins for that.") } // Authored.
            return
        }
        invDel(inv, GaObjs.coins, count * offer.price)
        invDel(inv, offer.hide, count)
        invAdd(inv, offer.leather, count)
        mes("Mary tans your hides.") // Authored (not Wiki).
    }

    private suspend fun Dialogue.menu(labels: List<String>): Int =
        when (labels.size) {
            2 -> choice2(labels[0], 0, labels[1], 1)
            3 -> choice3(labels[0], 0, labels[1], 1, labels[2], 2)
            4 -> choice4(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3)
            else -> choice5(labels[0], 0, labels[1], 1, labels[2], 2, labels[3], 3, labels[4], 4)
        }

    /* The flour trail and the lair */

    private suspend fun ProtectedAccess.flourGate() {
        if (state != MARY && state != CATTLE) {
            mes("Nothing interesting happens.")
            return
        }
        invReplace(inv, GaObjs.pot_flour, 1, GaObjs.pot_empty)
        section(null, "Pouring the flour on the gate")
        setState(FLOURED)
    }

    private suspend fun ProtectedAccess.enterCave() {
        telejump(LAIR_ARRIVAL)
        delay(1)
        if (state != FLOURED) return
        val existing = beasts[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) return
        val beast = Npc(npcTypes[GaNpcs.beast], BEAST_SPAWN)
        npcRepo.add(beast, BEAST_TICKS)
        beasts[player.uid.packed] = beast
        beast.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.leaveCave() {
        telejump(CAVE_EXIT)
        delay(1)
    }

    private fun beastKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != GaNpcs.beast.id) return
        val player = event.hero
        beasts.remove(player.uid.packed)
        if (player.questState(GETTING_AHEAD) != FLOURED) return
        player.setQuestState(GETTING_AHEAD, KILLED)
        // The Wiki's line on killing it, as a message (no dialogue access from the death event).
        player.mes(
            "Ah... a headless beast. Can't really bring back the head if there isn't one. Let's " +
                "hope Gordon is still happy to reward me..."
        )
    }

    private suspend fun ProtectedAccess.searchSkeleton() {
        if (state < KILLED || carries(GaObjs.journal)) {
            mes("You search the skeleton but find nothing of interest.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, GaObjs.journal)
        setBit(GaVarBits.journal, 1)
        mes("You search the skeleton and find a journal.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.readJournal() {
        startDialogue { for (page in JOURNAL_PAGES) mesbox(page) }
    }

    /* The fake heads */

    private fun ProtectedAccess.ownsAnyHead(): Boolean =
        carries(GaObjs.clay_head) || carries(GaObjs.fur_head) || carries(GaObjs.bloody_head)

    private suspend fun ProtectedAccess.makeClayHead() {
        if (state !in FAKE until MOUNTED) {
            mes("Nothing interesting happens.")
            return
        }
        if (ownsAnyHead()) {
            mes("You already have a head.") // Authored (not Wiki).
            return
        }
        invReplace(inv, GaObjs.soft_clay, 1, GaObjs.clay_head)
        section(null, "Making the clay head")
        advance(CLAY_MADE)
    }

    private suspend fun ProtectedAccess.makeFurHead(fur: ObjType) {
        if (state !in FAKE until MOUNTED) {
            mes("Nothing interesting happens.")
            return
        }
        if (carries(GaObjs.fur_head)) {
            mes("You already have a fur head.")
            return
        }
        if (!carries(GaObjs.needle) || !carries(GaObjs.thread)) {
            mes(
                "You do not have the required items to make a fur head. You need a clay head, some " +
                    "fur, a needle and some thread."
            )
            return
        }
        invDel(inv, fur)
        invDel(inv, GaObjs.thread)
        invReplace(inv, GaObjs.clay_head, 1, GaObjs.fur_head)
        section(null, "Making the fur head")
        advance(FUR_MADE)
    }

    private suspend fun ProtectedAccess.makeBloodyHead() {
        if (state !in FAKE until MOUNTED) {
            mes("Nothing interesting happens.")
            return
        }
        if (carries(GaObjs.bloody_head)) {
            mes("You already have a bloody head.")
            return
        }
        invDel(inv, GaObjs.red_dye)
        invReplace(inv, GaObjs.fur_head, 1, GaObjs.bloody_head)
        section(null, "Making the bloody head")
        advance(BLOODY_MADE)
    }

    private suspend fun ProtectedAccess.mountHead() {
        val hammer = GaObjs.hammers.any { carries(it) || invTotal(worn, it) > 0 }
        val saw = GaObjs.saws.any { carries(it) || invTotal(worn, it) > 0 }
        val nails = GaObjs.nails.firstOrNull { carries(it, NAILS_NEEDED) }
        if (state != MOUNT || !hammer || !saw || nails == null || !carries(GaObjs.bloody_head) ||
            !carries(GaObjs.plank, PLANKS_NEEDED)
        ) {
            mes(
                "You do not have the required items to do that. You need a Saw, a Hammer, a Bloody " +
                    "Head, 2 Planks and 6 Nails of any kind."
            )
            return
        }
        if (statBase(stats.construction) < CONSTRUCTION_REQ) {
            mes("You need a Construction level of $CONSTRUCTION_REQ to do that.") // Authored.
            return
        }
        invDel(inv, GaObjs.bloody_head)
        invDel(inv, GaObjs.plank, PLANKS_NEEDED)
        invDel(inv, nails, NAILS_NEEDED)
        delay(2)
        setState(MOUNTED)
        section(null, "Mounting the bloody head")
    }

    /* The farm */

    private suspend fun ProtectedAccess.searchShelves() {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        var dye: ObjType? = null
        startDialogue {
            dye =
                choice2(
                    "Take a red dye.",
                    GaObjs.red_dye,
                    "Take a yellow dye.",
                    GaObjs.yellow_dye,
                    title = "Select an option",
                )
        }
        val picked = dye ?: return
        if (carries(picked)) {
            mes("You already have one of those.") // Authored (not Wiki).
            return
        }
        invAdd(inv, picked)
    }

    private suspend fun ProtectedAccess.searchWorkbench() {
        if (bit(GaVarBits.nails_given) == 1) {
            mes("You search the workbench but find nothing of interest.") // Authored (not Wiki).
            return
        }
        if (inv.isFull() && !carries(GaObjs.iron_nails)) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, GaObjs.iron_nails, WORKBENCH_NAILS)
        setBit(GaVarBits.nails_given, 1)
        mes("You search the workbench and find some nails.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.takePickaxe() {
        if (carries(GaObjs.bronze_pickaxe)) {
            mes("You already have a pickaxe.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        invAdd(inv, GaObjs.bronze_pickaxe)
        mes("You take the pickaxe from the rocks.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.takeFlour() {
        if (!carries(GaObjs.pot_empty)) {
            mes("You need an empty pot to hold the flour.") // Authored (not Wiki).
            return
        }
        invReplace(inv, GaObjs.pot_empty, 1, GaObjs.pot_flour)
        mes("You fill your pot with flour.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.fillBucket() {
        invReplace(inv, GaObjs.bucket_empty, 1, GaObjs.bucket_water)
        mes("You fill the bucket from the sink.")
    }

    private suspend fun ProtectedAccess.softenClay() {
        invReplace(inv, GaObjs.clay, 1, GaObjs.soft_clay)
        invReplace(inv, GaObjs.bucket_water, 1, GaObjs.bucket_empty)
        mes("You mix the clay and water.")
        mes("You now have some soft workable clay.")
    }

    /** The farmhouse's 2x2 spiral stairs (the generic spiral staircase translation, angle 3). */
    private suspend fun ProtectedAccess.climbStairs(loc: BoundLocInfo, up: Boolean) {
        arriveDelay()
        val at = loc.coords
        val dest: CoordGrid =
            if (up) {
                at.translate(loc.adjustedWidth - 1, loc.adjustedLength, 1)
            } else {
                at.translate(loc.adjustedWidth, loc.adjustedLength - 1, -1)
            }
        telejump(dest)
    }

    companion object {
        const val STARTED = 2
        const val MARY = 4
        const val CATTLE = 6
        const val FLOURED = 10
        const val KILLED = 12
        const val REFUSED = 14
        const val FAKE = 16
        const val CLAY_MADE = 18
        const val CLAY_SHOWN = 20
        const val FUR_MADE = 22
        const val FUR_SHOWN = 24
        const val BLOODY_MADE = 26
        const val MOUNT = 30
        const val MOUNTED = 32
        const val COMPLETE = 34

        private const val CRAFTING_REQ = 30
        private const val CONSTRUCTION_REQ = 26
        private const val CRAFTING_XP = 4000.0
        private const val CONSTRUCTION_XP = 3200.0
        private const val REWARD_COINS = 3000
        private const val PLANKS_NEEDED = 2
        private const val NAILS_NEEDED = 6
        private const val WORKBENCH_NAILS = 20
        private const val BEAST_TICKS = 1000
        private const val MAX_CHOICES = 5

        /** Inside the lair, by its exit (1189,10029). */
        private val LAIR_ARRIVAL = CoordGrid(1191, 10026, 0)
        private val BEAST_SPAWN = CoordGrid(1190, 10020, 0)
        /** On the flour trail outside the cave (1211,3646). */
        private val CAVE_EXIT = CoordGrid(1213, 3649, 0)

        /** Neilan's journal (Wiki "Transcript:Neilan's journal"). */
        private val JOURNAL_PAGES =
            listOf(
                "Ire of Phyrrys 29th<br>Blast those pompous Arceuus mages! They say I don't have " +
                    "what it takes. I'll show them! They think you have to be immortal to be good at " +
                    "anything. They're wrong.",
                "Ire of Phyrrys 32nd<br>I may have taken a little trip to the library last night. I " +
                    "managed to make off with a few of their restricted tomes without them even " +
                    "knowing. Now the fun begins.",
                "Ire of Phyrrys 39th<br>The cities are too busy for my little experiments, so I've " +
                    "found somewhere a bit quieter. A nice cave off the beaten track. Best of all, " +
                    "there's no shortage of corpses in the swamps for me to work with.",
                "Novtumber 4th<br>Success! I've managed to fully reanimate the body of a rat. Before " +
                    "long, Neilan the Necromancer will be a name known across all the land!",
                "Novtumber 10th<br>On my way to the cave this morning I went past the body of a " +
                    "bear. Probably the work of the lizardman. If I can get it to the cave, it will " +
                    "make for a great new test subject.",
            )

        val GETTING_AHEAD =
            QuestDef(
                name = "Getting Ahead",
                varbit = { GaVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "3,000 Coins",
                        "4,000 Crafting XP",
                        "3,200 Construction XP",
                        "Access to Mary's tannery",
                    ),
                scrollObj = { GaObjs.bloody_head },
            )
    }
}

// Every cache ref Getting Ahead uses (re-check after cache updates).
internal object GaNpcs : NpcReferences() {
    val gordon = find("ga_gordon")
    /** Children of the `ga_mary` multi on `ga_mary_dialogue` (0: Talk-to; 1: Talk-to + Trade). */
    val mary_talk = find("ga_mary_1op")
    val mary_trade = find("ga_mary_2op")
    val marys = listOf(mary_talk, mary_trade)
    val sergeant = find("ga_sergeant")
    val beast = find("ga_beast")
}

internal object GaLocs : LocReferences() {
    /** The cattle gate south of the house: multilocs on `ga` and their unfloured children. */
    val flour_gates =
        listOf(
            find("ga_fencegate_l"),
            find("ga_fencegate_r"),
            find("ga_fencegate_l_normal"),
            find("ga_fencegate_r_normal"),
        )
    val gate_halves =
        listOf(
            find("ga_fencegate_l_normal"),
            find("ga_fencegate_r_normal"),
            find("ga_fencegate_l_flour"),
            find("ga_fencegate_r_flour"),
        )
    val cave = find("ga_cave")
    val cave_out = find("ga_cave_out")
    val cave_out_instance = find("ga_cave_out_instance")
    val skeleton = find("ga_skeleton")
    val head_hotspot = find("ga_mounted_head_hotspot")
    val shelves = find("ga_shelves")
    val workbench = find("ga_workbench")
    val rocks_pickaxe = find("ga_rocks_pickaxe")
    val flour_barrel = find("ga_flourbarrel")
    val sink = find("fai_varrock_cooking_kitchen_sink")
    /** The farmhouse doors (also used elsewhere: bound only if nothing else owns them). */
    val doors = listOf(find("canafis_door_ground"), find("farmdoor"))
    val stairs_bottom = find("kebos_spiralstairs_bottom")
    val stairs_top = find("kebos_spiralstairs_top")
}

internal object GaObjs : ObjReferences() {
    val clay_head = find("ga_clayhead")
    val fur_head = find("ga_furhead")
    val bloody_head = find("ga_bloodhead")
    val journal = find("ga_journal")
    val big_bones = find("big_bones")
    val ensouled_bear = find("arceuus_corpse_bear_initial")
    val bearhead = find("mdaughter_bear_helmet")
    val pot_flour = find("pot_flour")
    val pot_empty = find("pot_empty")
    val knife = find("knife")
    val clay = find("clay")
    val soft_clay = find("softclay")
    val bucket_empty = find("bucket_empty")
    val bucket_water = find("bucket_water")
    val needle = find("needle")
    val thread = find("thread")
    val red_dye = find("reddye")
    val yellow_dye = find("yellowdye")
    val furs = listOf(find("fur"), find("grey_wolf_fur"), find("werewolve_fur"))
    val bloods = listOf(find("vial_blood"), find("blood_pint"))
    val raw_bear_meat = find("raw_bear_meat")
    val plank = find("woodplank")
    val iron_nails = find("nails_iron")
    val nails =
        listOf(
            find("nails"),
            find("nails_bronze"),
            iron_nails,
            find("nails_black"),
            find("nails_mithril"),
            find("nails_adamant"),
            find("nails_rune"),
        )
    val hammers = listOf(find("hammer"), find("imcando_hammer"), find("imcando_hammer_offhand"))
    val saws = listOf(find("poh_saw"), find("wearable_saw"), find("wearable_saw_offhand"))
    val bronze_pickaxe = find("bronze_pickaxe")
    val coins = find("coins")
    val cow_hide = find("cow_hide")
    val leather = find("leather")
    val hard_leather = find("hard_leather")
    val green_hide = find("dragonhide_green")
    val blue_hide = find("dragonhide_blue")
    val red_hide = find("dragonhide_red")
    val black_hide = find("dragonhide_black")
    val green_leather = find("dragon_leather")
    val blue_leather = find("dragon_leather_blue")
    val red_leather = find("dragon_leather_red")
    val black_leather = find("dragon_leather_black")
}

internal object GaVarBits : VarBitReferences() {
    val quest = find("ga")
    val reward = find("ga_reward")
    val journal = find("ga_journal")
    val mary_dialogue = find("ga_mary_dialogue")
    val gordon_met = find("ga_gordon_met")
    val nails_given = find("ga_nails_given")
}
