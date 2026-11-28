package org.rsmod.content.quests.birdsandwasps

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.currencies
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerTimer
import org.rsmod.api.shops.Shops
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueHooks
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
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Birds and the Wasps (OSRS Wiki, November 2026). Progress lives in the cache quest varbit
 * `baw` (varp `baw_primary` bits 0-6, db end state 55) and its siblings (`baw_had_cider`, the
 * wasp swarm states, `baw_birds_caught`, the spoken/notification flags). Stages are ours but follow
 * the cache multis: the bee dealer shows at 10-15, the equipment crate opens from 21, honey
 * buzzards show from 35, black honey buzzards from 45, Bea's post-quest Trade/Swap-darts at 55.
 * - 10 Bea wants her bees from the dealer by the Grand Exchange's east entrance (optional cider:
 *   full run energy). 15 the dealer hands over the bee box: shake it when the insects get
 *   agitated, or they sting (1 damage; shaking calm ones stings too).
 * - 25 the box delivered: the bees were wasps. The three swarms by the southern hive appear
 *   (`baw_wasp_N_state` 1); gear from the crate, net 50 wasp stings (hunter `Wasps.kt`).
 * - 35 stings shown: honey buzzards arrive; Bea tops you up to two bird snares; catch five
 *   (`baw_birds_caught`, counted by hunter `Hunter.kt`) and bring 50 brown feathers.
 * - 45 feathers shown: killer wasps and black honey buzzards at the new hives; fletch the stings
 *   and feathers into darts (Fletching gates them on this stage). 55 with 50 wasp darts: 1 QP,
 *   1,000 Hunter and 250 Fletching XP (db stat_xp_awarded 10000/2500 tenths).
 *
 * Side content: Bea's Hunting Gear (Trade), dart trade-ins (35 wasp or 20 killer wasp darts per
 * Bea's supplies; Swap-darts or the chat option) and the supplies' loot (Wiki drop log), Bea's
 * hunting tips, the farm gate, the equipment crate, inspecting the darts; hunting all four
 * creatures after the quest (hunter module).
 *
 * Simplified: the three cutscenes are their Wiki beats shown as boxes; the shop stock is held
 * server-side (the cache inv has none and cache types aren't edited) and restocks fully every
 * [RESTOCK_TICKS] when opened; the bee box doesn't hop to a new slot when its insects get
 * agitated (it stays put).
 */
class BirdsAndWasps
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val invTypes: InvTypeList,
    private val objRepo: ObjRepository,
    private val shops: Shops,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(BirdsAndWasps::class.java, "birds_and_wasps") }

    /** Players whose bee box insects are agitated (single player; keyed for safety). */
    private val agitated = HashSet<Int>()

    /** Bea's Hunting Gear: a server-side stock (the cache inv `baw_bea_post_quest_shop` has none). */
    private var shopInv: Inventory? = null
    private var lastRestock = Int.MIN_VALUE / 2

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            for (bea in BawNpcs.beas) register(npcTypes, bea, BIRDS_AND_WASPS, wants = { true }) { talkBea(it) }
            register(npcTypes, BawNpcs.dealer, BIRDS_AND_WASPS, wants = { true }) { talkDealer(it) }
        }
        onNpcOpLabel(npcTypes, BawNpcs.bea_complete, "Trade") { if (state >= COMPLETE) openShop() }
        onNpcOpLabel(npcTypes, BawNpcs.bea_complete, "Swap-darts") { if (state >= COMPLETE) swapDarts(it) }

        onHeldOpLabel(objTypes, BawObjs.bee_box, "Shake") { shakeBox() }
        onPlayerTimer(BawTimers.bee_box) { beeBoxTick() }
        onPlayerLogin { restoreOnLogin(player) }

        onLocOpLabel(locTypes, BawLocs.crate, "Take-from") { crateMenu() }
        onLocOpLabel(locTypes, BawLocs.crate, "Take-all") { takeAll() }
        for (gate in BawLocs.gates) bindOpenIfFree(gate)

        onHeldOpLabel(objTypes, BawObjs.wasp_dart, "Inspect") {
            objbox(BawObjs.wasp_dart, dialogue.section("Inspecting the darts").boxText())
        }
        onHeldOpLabel(objTypes, BawObjs.killer_wasp_dart, "Inspect") {
            // Authored (not Wiki): the killer wasp dart's inspect text, after the wasp dart's.
            objbox(
                BawObjs.killer_wasp_dart,
                "Some darts made using killer wasp stings. They don't look like the sort of thing " +
                    "you'd want to use yourself, but maybe Bea will have a use for them.",
            )
        }
        onHeldOpLabel(objTypes, BawObjs.supply_sack, "Open") { openSupplies() }
    }

    private fun ScriptContext.bindOpenIfFree(type: LocType) {
        val op = locTypes[type].op.indexOfFirst { it.equals("Open", ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(type, op)) return
        onLocOpLabel(locTypes, type, "Open") { passThroughWall(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BIRDS_AND_WASPS)

    private fun ProtectedAccess.advance(value: Int) {
        if (state < value) player.setQuestState(BIRDS_AND_WASPS, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /** Transcript hooks: conditions by exact Wiki text, option and box side effects. */
    private class Hooks(
        private val conditions: Map<String, () -> Boolean> = emptyMap(),
        private val options: Map<String, suspend Dialogue.() -> Unit> = emptyMap(),
        private val boxes: Map<String, suspend Dialogue.(String) -> Unit> = emptyMap(),
        private val rewrites: (String) -> String = { it },
    ) : QuestDialogueHooks {
        var restart = false

        override fun condition(text: String): Boolean = conditions[text]?.invoke() ?: false

        override suspend fun Dialogue.onOption(label: String) {
            options[label]?.invoke(this)
        }

        override suspend fun Dialogue.onBox(text: String): Boolean {
            val handler = boxes[text] ?: return false
            handler(text)
            return true
        }

        override suspend fun Dialogue.onAction(tag: String) {
            if (tag == "initial") restart = true
        }

        override fun rewrite(text: String): String = rewrites(text)
    }

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: QuestDialogueHooks = Hooks()) {
        runQuestSection(npc, dialogue, title, hooks)
    }

    /* Farmer Bea */

    private suspend fun ProtectedAccess.talkBea(npc: Npc) {
        when {
            state == 0 -> startQuest(npc)
            state < WASPS ->
                if (carries(BawObjs.bee_box)) deliverBees(npc) else section(npc, "Talking to Bea again")
            state < BIRDS ->
                if (carries(BawObjs.wasp_sting, STINGS_NEEDED)) {
                    showStings(npc)
                } else {
                    section(npc, "Talking to Farmer Bea again")
                    setBit(BawVarBits.spoken_wasps, 1)
                }
            state < DARTS ->
                if (bit(BawVarBits.birds_caught) >= BIRDS_NEEDED && carries(BawObjs.brown_feather, FEATHERS_NEEDED)) {
                    showFeathers(npc)
                } else {
                    section(npc, "Talking to Farmer Bea again (2)", snareHooks())
                    setBit(BawVarBits.spoken_birds, 1)
                }
            state < COMPLETE -> talkDarts(npc)
            else -> beaAfterQuest(npc)
        }
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        val hooks =
            Hooks(
                options = mapOf("Yes." to opt { access.advance(STARTED) }),
                boxes =
                    mapOf(
                        "Bea hands you a glass of cider. You drink it right away and find yourself feeling refreshed." to
                            box { text ->
                                player.runEnergy = constants.run_max_energy
                                VarPlayerIntMapSetter.set(player, BawVarBits.had_cider, 1)
                                objbox(BawObjs.cider, text)
                            }
                    ),
            )
        section(npc, "Starting off", hooks)
    }

    private suspend fun ProtectedAccess.deliverBees(npc: Npc) {
        val hooks =
            Hooks(
                boxes =
                    mapOf(
                        "You hand over the box of disgruntled insects." to
                            box { text ->
                                if (access.invDel(access.inv, BawObjs.bee_box, 1).success) {
                                    access.waspsMoveIn()
                                }
                                objbox(BawObjs.bee_box, text)
                            }
                    ),
            )
        section(npc, "Returning to Farmer Bea", hooks)
    }

    private fun ProtectedAccess.waspsMoveIn() {
        player.clearTimer(BawTimers.bee_box)
        agitated.remove(player.uid.packed)
        advance(WASPS)
        for (wasp in BawVarBits.wasp_states) if (bit(wasp) == 0) setBit(wasp, CALM)
    }

    private suspend fun ProtectedAccess.showStings(npc: Npc) {
        val hooks = snareHooks(
            extraBoxes =
                mapOf(
                    "You show the wasp stings to Bea." to
                        box { text ->
                            access.advance(BIRDS)
                            objbox(BawObjs.wasp_sting, text)
                        }
                ),
        )
        section(npc, "Returning to Farmer Bea (2)", hooks)
        advance(BIRDS)
    }

    /** Bea tops the player up to two bird snares (Wiki conditions). */
    private fun ProtectedAccess.snareHooks(
        extraBoxes: Map<String, suspend Dialogue.(String) -> Unit> = emptyMap(),
    ): Hooks {
        val snares = invTotal(inv, BawObjs.bird_snare)
        return Hooks(
            conditions =
                mapOf(
                    "If the player has no inventory space:" to { snares == 0 && inv.freeSpace() == 0 },
                    "If the player has no bird snares in their inventory:" to { snares == 0 },
                    "If the player has one bird snare in their inventory:" to { snares == 1 },
                ),
            boxes =
                extraBoxes +
                    mapOf(
                        "Bea hands you some bird snares." to
                            box { text ->
                                access.invAdd(access.inv, BawObjs.bird_snare, 2)
                                doubleobjbox(BawObjs.bird_snare, BawObjs.bird_snare, text)
                            },
                        "Bea hands you a bird snare." to
                            box { text ->
                                access.invAdd(access.inv, BawObjs.bird_snare, 1)
                                objbox(BawObjs.bird_snare, text)
                            },
                    ),
        )
    }

    private suspend fun ProtectedAccess.showFeathers(npc: Npc) {
        val hooks =
            Hooks(
                boxes =
                    mapOf(
                        "You head over to the new hives, excited to finally introduce some bees to their new homes..." to
                            box { text ->
                                access.killerWaspsArrive()
                                mesbox(text)
                            }
                    ),
            )
        section(npc, "Returning to Farmer Bea (3)", hooks)
        killerWaspsArrive()
    }

    private fun ProtectedAccess.killerWaspsArrive() {
        advance(DARTS)
        for (wasp in BawVarBits.killer_wasp_states) if (bit(wasp) == 0) setBit(wasp, CALM)
    }

    private suspend fun ProtectedAccess.talkDarts(npc: Npc) {
        val darts = invTotal(inv, BawObjs.wasp_dart)
        when {
            darts >= DARTS_NEEDED -> finish(npc)
            darts > 0 -> section(npc, "After crafting some wasp darts, but less than 50")
            else -> {
                section(npc, "Speaking to Farmer Bea again")
                setBit(BawVarBits.spoken_darts, 1)
            }
        }
    }

    private suspend fun ProtectedAccess.finish(npc: Npc) {
        section(npc, "After crafting 50 wasp darts")
        statAdvance(stats.hunter, HUNTER_XP * xpMods.get(player, stats.hunter))
        statAdvance(stats.fletching, FLETCHING_XP * xpMods.get(player, stats.fletching))
        completeQuest(BIRDS_AND_WASPS)
    }

    private suspend fun ProtectedAccess.beaAfterQuest(npc: Npc) {
        var shop = false
        var traded: Int? = null
        while (true) {
            val hooks =
                Hooks(
                    options =
                        mapOf(
                            "Do you have anything for sale?" to opt { shop = true },
                            "Can I trade in some darts?" to opt { traded = access.tradeDarts() },
                        ),
                    rewrites = { text ->
                        val result = traded
                        if (text.startsWith(NOT_ENOUGH_DARTS) && result != null) tradeReply(result) else text
                    },
                )
            section(npc, "Standard dialogue", hooks)
            if (!hooks.restart) break
        }
        if (shop) openShop()
    }

    /* Dart trade-ins */

    /**
     * Swaps every full lot of darts for Bea's supplies: the bags paid (> 0), [NO_SPACE], or 0 for
     * too few darts.
     */
    private fun ProtectedAccess.tradeDarts(): Int {
        val wasp = invTotal(inv, BawObjs.wasp_dart) / WASP_DARTS_PER_BAG
        val killer = invTotal(inv, BawObjs.killer_wasp_dart) / KILLER_DARTS_PER_BAG
        val bags = wasp + killer
        if (bags == 0) return 0
        if (!carries(BawObjs.supply_sack) && inv.freeSpace() == 0) return NO_SPACE
        if (wasp > 0) invDel(inv, BawObjs.wasp_dart, wasp * WASP_DARTS_PER_BAG)
        if (killer > 0) invDel(inv, BawObjs.killer_wasp_dart, killer * KILLER_DARTS_PER_BAG)
        invAdd(inv, BawObjs.supply_sack, bags)
        return bags
    }

    /** Bea's reply to a trade-in ([tradeDarts] result). */
    private fun tradeReply(bags: Int): String =
        when {
            bags > 0 -> dialogue.section("Trading in darts").lineText().replace("{bags}", bags.toString())
            bags == NO_SPACE -> dialogue.section("Trading in darts with no space").lineText()
            else -> dialogue.section("Standard dialogue").lineStartingWith(NOT_ENOUGH_DARTS) ?: NOT_ENOUGH_DARTS
        }

    private suspend fun ProtectedAccess.swapDarts(npc: Npc) {
        val text = tradeReply(tradeDarts())
        startDialogue(npc) { chatNpc(neutral, text) }
    }

    private fun ProtectedAccess.openSupplies() {
        if (!carries(BawObjs.supply_sack)) return
        var roll = random.of(0, SUPPLY_WEIGHT - 1)
        val entry = SUPPLIES.first { (roll - it.weight).also { r -> roll = r } < 0 }
        invDel(inv, BawObjs.supply_sack, 1)
        val count = random.of(entry.min, entry.max)
        invAddOrDrop(objRepo, BawObjs.supplies.getValue(entry.obj), count)
        mes("You open the sack and find some supplies.") // Authored (not Wiki).
    }

    /* Bea's Hunting Gear */

    private fun ProtectedAccess.openShop() {
        val type = invTypes[BawInvs.shop]
        val stock = shopInv ?: Inventory(type, arrayOfNulls<InvObj>(type.size)).also { shopInv = it }
        if (mapClock - lastRestock >= RESTOCK_TICKS) {
            lastRestock = mapClock
            for ((index, item) in SHOP_STOCK.withIndex()) {
                if (index >= stock.size) break
                val obj = BawObjs.shop.getValue(item.first)
                val slot = stock.objs.indexOfFirst { it?.id == obj.id }
                val at = if (slot >= 0) slot else stock.objs.indexOfFirst { it == null }
                if (at < 0) continue
                val have = stock[at]?.count ?: 0
                if (have < item.second) stock[at] = InvObj(obj, item.second)
            }
        }
        shops.open(
            player = player,
            title = SHOP_TITLE,
            shopInv = stock,
            sideInv = player.inv,
            currency = currencies.standard_gp,
            buyPercentage = SHOP_BUY,
            sellPercentage = SHOP_SELL,
            changePercentage = 0.0,
            subtext = Shops.DEFAULT_SUBTEXT,
        )
    }

    /* The bee dealer and the bee box */

    private suspend fun ProtectedAccess.talkDealer(npc: Npc) {
        if (state !in STARTED until WASPS) return
        val title =
            when {
                state == STARTED -> "Talking to the bee dealer"
                carries(BawObjs.bee_box) -> "Talking to the bee dealer again"
                else -> "Talking to the bee dealer again after losing the box"
            }
        val boxed = box { text ->
            if (access.invAdd(access.inv, BawObjs.bee_box, 1).success) access.gotBox()
            objbox(BawObjs.bee_box, text)
        }
        val hooks =
            Hooks(
                conditions = mapOf("If the player has a full inventory:" to { inv.freeSpace() == 0 }),
                boxes =
                    mapOf(
                        "Out of nowhere, the dealer produces a rather insecure looking box." to
                            box { text -> objbox(BawObjs.bee_box, text) },
                        "The dealer shoves the box into your hands." to boxed,
                        "The dealer shoves a box into your hands." to boxed,
                    ),
            )
        section(npc, title, hooks)
    }

    private fun ProtectedAccess.gotBox() {
        advance(HAVE_BOX)
        agitated.remove(player.uid.packed)
        player.timer(BawTimers.bee_box, BOX_TICKS)
    }

    private fun ProtectedAccess.beeBoxTick() {
        val key = player.uid.packed
        if (!carries(BawObjs.bee_box)) {
            player.clearTimer(BawTimers.bee_box)
            agitated.remove(key)
            return
        }
        if (key in agitated) {
            queueHit(1, HitType.Typeless, 1)
            val text = dialogue.section("If the bees are agitated too long").boxText()
            mes("<col=ff0000>$text</col>")
        } else if (random.of(0, 1) == 0) {
            agitated += key
            val text = dialogue.section("When the bees get agitated").boxText()
            mes("<col=ff0000>$text</col>")
        }
    }

    private suspend fun ProtectedAccess.shakeBox() {
        val key = player.uid.packed
        if (key in agitated) {
            agitated.remove(key)
            val text = dialogue.section("Shaking the box if the bees are agitated").boxText()
            objbox(BawObjs.bee_box, text)
            mes("<col=00ff00>$text</col>")
        } else {
            queueHit(1, HitType.Typeless, 1)
            objbox(BawObjs.bee_box, dialogue.section("Shaking the box if the bees are not agitated").boxText())
        }
    }

    /** Re-arms the bee box after a relog and keeps the swarms the player has unlocked visible. */
    private fun restoreOnLogin(player: Player) {
        val stage = player.questState(BIRDS_AND_WASPS)
        if (stage == HAVE_BOX) player.timer(BawTimers.bee_box, BOX_TICKS)
        if (stage >= WASPS) {
            for (wasp in BawVarBits.wasp_states) {
                if (player.vars[wasp] == 0) VarPlayerIntMapSetter.set(player, wasp, CALM)
            }
        }
        if (stage >= DARTS) {
            for (wasp in BawVarBits.killer_wasp_states) {
                if (player.vars[wasp] == 0) VarPlayerIntMapSetter.set(player, wasp, CALM)
            }
        }
    }

    /* The equipment crate */

    private suspend fun ProtectedAccess.crateMenu() {
        if (inv.freeSpace() == 0) {
            mesbox(dialogue.section("Searching the crate with a full inventory").boxText())
            return
        }
        while (true) {
            var picked = -1
            startDialogue {
                picked =
                    choice5(
                        "Insect net.",
                        0,
                        "Bee hunter's hat.",
                        1,
                        "Insect repellent.",
                        2,
                        "Take everything.",
                        3,
                        "Take nothing.",
                        4,
                        title = "What would you like to take?",
                    )
            }
            when (picked) {
                0 -> takeOne(BawObjs.insect_net, "You take an insect net from the crate.")
                1 -> takeOne(BawObjs.bee_hunter_hat, "You take a bee hunter's hat from the crate.")
                2 -> takeOne(BawObjs.insect_repellent, "You take some insect repellent from the crate.")
                3 -> {
                    takeAll()
                    return
                }
                else -> return
            }
            if (inv.freeSpace() == 0) return
        }
    }

    private suspend fun ProtectedAccess.takeOne(obj: ObjType, text: String) {
        if (inv.freeSpace() == 0) {
            mesbox(dialogue.section("Searching the crate with a full inventory").boxText())
            return
        }
        invAdd(inv, obj, 1)
        objbox(obj, text)
    }

    private suspend fun ProtectedAccess.takeAll() {
        if (inv.freeSpace() == 0) {
            mesbox(dialogue.section("Searching the crate with a full inventory").boxText())
            return
        }
        for (obj in listOf(BawObjs.insect_net, BawObjs.bee_hunter_hat, BawObjs.insect_repellent)) {
            if (inv.freeSpace() == 0) break
            invAdd(inv, obj, 1)
        }
        doubleobjbox(BawObjs.insect_net, BawObjs.bee_hunter_hat, "You take a full set of equipment from the crate.")
    }

    private class Supply(val obj: String, val weight: Int, val min: Int, val max: Int)

    companion object {
        const val STARTED = 10
        const val HAVE_BOX = 15
        /** Must match hunter `BawProgress.WASPS` (the crate opens from 21). */
        const val WASPS = 25
        /** Must match hunter `BawProgress.BIRDS` (honey buzzards show from 35). */
        const val BIRDS = 35
        /** Must match fletching `BAW_DARTS_STAGE` (black honey buzzards show from 45). */
        const val DARTS = 45
        const val COMPLETE = 55

        private const val CALM = 1
        private const val STINGS_NEEDED = 50
        private const val BIRDS_NEEDED = 5
        private const val FEATHERS_NEEDED = 50
        private const val DARTS_NEEDED = 50
        private const val WASP_DARTS_PER_BAG = 35
        private const val KILLER_DARTS_PER_BAG = 20
        private const val HUNTER_XP = 1000.0
        private const val FLETCHING_XP = 250.0
        /** Bee box check interval: calm insects get agitated half the time, agitated ones sting. */
        private const val BOX_TICKS = 16
        private const val RESTOCK_TICKS = 500
        private const val NO_SPACE = -1
        private const val NOT_ENOUGH_DARTS = "Yer can, but I'm 'fraid yer not got enough darts."

        /** Wiki store multipliers: sell 1300, buy 400 (x10), delta 20 (unused: fixed stock). */
        private const val SHOP_TITLE = "Bea's Hunting Gear"
        private const val SHOP_SELL = 130.0
        private const val SHOP_BUY = 40.0

        /** Wiki "Bea's Hunting Gear" stock (rabbit snare is `hunting_snare`). */
        val SHOP_STOCK =
            listOf(
                "butterfly_jar" to 100,
                "magic_imp_box" to 30,
                "noose_wand" to 50,
                "hunting_ojibway_bird_snare" to 50,
                "hunting_box_trap" to 25,
                "hunting_teasing_stick" to 5,
                "torch_unlit" to 20,
                "hunting_snare" to 10,
                "net_trap" to 25,
                "pack_ojibway_bird_snare" to 3,
                "pack_box_trap" to 3,
                "pack_magic_imp_box" to 3,
            )

        /** Wiki "Bea's supplies" drop log (weights out of 60). */
        private val SUPPLIES =
            listOf(
                Supply("headless_arrow", 16, 60, 140),
                Supply("coins", 8, 150, 300),
                Supply("bronze_arrowheads", 8, 60, 140),
                Supply("bronze_dart", 8, 30, 70),
                Supply("cert_leather", 5, 2, 6),
                Supply("iron_arrowheads", 5, 60, 140),
                Supply("iron_dart", 5, 30, 70),
                Supply("cert_hard_leather", 3, 2, 6),
                Supply("steel_arrowheads", 1, 60, 140),
                Supply("steel_dart", 1, 30, 70),
            )
        private val SUPPLY_WEIGHT = SUPPLIES.sumOf { it.weight }
        val SUPPLY_OBJS: List<String> = SUPPLIES.map { it.obj }

        val BIRDS_AND_WASPS =
            QuestDef(
                name = "The Birds and the Wasps",
                varbit = { BawVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "1,000 Hunter XP",
                        "250 Fletching XP",
                        "Hunting wasps and honey buzzards",
                        "Fletching wasp darts",
                        "Access to Bea's Hunting Gear",
                    ),
                scrollObj = { BawObjs.wasp_dart },
                listRow = "quest_birdsandthewasps",
            )
    }
}

/** Typed hook lambdas (a bare lambda in `mapOf(x to { ... })` can't infer a receiver). */
private fun opt(action: suspend Dialogue.() -> Unit): suspend Dialogue.() -> Unit = action

private fun box(action: suspend Dialogue.(String) -> Unit): suspend Dialogue.(String) -> Unit = action

private fun List<Node>.boxText(): String = filterIsInstance<Node.Box>().first().text

private fun List<Node>.lineText(): String = filterIsInstance<Node.Line>().first().text

/** The first line (searched depth-first) whose text starts with [prefix]. */
private fun List<Node>.lineStartingWith(prefix: String): String? {
    for (node in this) {
        if (node is Node.Line && node.text.startsWith(prefix)) return node.text
        node.children.lineStartingWith(prefix)?.let { return it }
    }
    return null
}

// Every cache ref The Birds and the Wasps uses (re-check after cache updates).
internal object BawNpcs : NpcReferences() {
    /** Children of `baw_bea_multi` on `baw` (0-50 Talk-to; 55 Talk-to, Trade, Swap-darts). */
    val bea = find("baw_bea")
    val bea_complete = find("baw_bea_3ops")
    val beas = listOf(bea, bea_complete)
    /** Child of `baw_bee_dealer_multi` (shown at `baw` 10-15). */
    val dealer = find("baw_bee_dealer")
}

internal object BawLocs : LocReferences() {
    /** Child of the `baw_equipment_crate` multiloc from `baw` 21. */
    val crate = find("baw_equipment_crate_quest")
    val gates = listOf(find("baw_fencegate_l"), find("baw_fencegate_r"))
}

internal object BawObjs : ObjReferences() {
    val bee_box = find("baw_bee_box")
    val bee_hunter_hat = find("baw_bee_hunter_hat")
    val insect_net = find("hunting_butterfly_net")
    val insect_repellent = find("insect_repellent")
    val wasp_sting = find("baw_wasp_sting")
    val brown_feather = find("baw_brown_feather")
    val wasp_dart = find("baw_wasp_dart")
    val killer_wasp_dart = find("baw_killer_wasp_dart")
    val supply_sack = find("baw_supply_sack")
    val bird_snare = find("hunting_ojibway_bird_snare")
    val cider = find("cider")
    val shop: Map<String, ObjType> =
        BirdsAndWasps.SHOP_STOCK.map { it.first }.associateWith { find(it) }
    val supplies: Map<String, ObjType> = BirdsAndWasps.SUPPLY_OBJS.associateWith { find(it) }
}

internal object BawInvs : InvReferences() {
    val shop = find("baw_bea_post_quest_shop")
}

internal object BawTimers : TimerReferences() {
    /** The bee box's agitation check while it's carried. */
    val bee_box = find("baw_bee_box")
}

internal object BawVarBits : VarBitReferences() {
    val quest = find("baw")
    val had_cider = find("baw_had_cider")
    val birds_caught = find("baw_birds_caught")
    val spoken_wasps = find("baw_spoken_about_wasps")
    val spoken_birds = find("baw_spoken_about_birds")
    val spoken_darts = find("baw_spoken_about_darts")
    /** Each swarm multinpc's state (0 hidden, 1 catchable, 2 furious); see hunter `Wasps.kt`. */
    val wasp_states = listOf(find("baw_wasp_1_state"), find("baw_wasp_2_state"), find("baw_wasp_3_state"))
    val killer_wasp_states =
        listOf(find("baw_killer_wasp_1_state"), find("baw_killer_wasp_2_state"), find("baw_killer_wasp_3_state"))
}
