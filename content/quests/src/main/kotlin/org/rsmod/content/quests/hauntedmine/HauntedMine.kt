package org.rsmod.content.quests.hauntedmine

import jakarta.inject.Inject
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Haunted Mine (OSRS Wiki). The quest varp is the cache's `hauntedmine` (0-11); every puzzle bit
 * lives on `hauntedmine_bits` (varp 383, all server-side quest bits: levers, lift, fungus carts,
 * heard-about-key; no client script writes any of them).
 * - The Zealot (outside the Abandoned Mine, Mort Ridge) starts it (Priest in Peril required);
 *   pickpocket him for the zealot's key.
 * - Climb over the mine cart (15 Agility) and crawl down the cart tunnel; level 1 west, out of the
 *   west tunnel and back in by the southern one; ladders down to level 4.
 * - Pick a glowing fungus (it crumbles if dropped or taken outside), put it in the searchable
 *   cart, set the points levers (A-E and I-K; F-H are flooded) and press Start on the points
 *   settings; the fungus travels to the cart by ladder 10, reached from the northern tunnel.
 * - The zealot's key on the water valve powers the lift; down to the flooded level 5; east stairs
 *   to the crane chamber. Taking the innocent-looking key raises Treus Dayth; killed, the key is
 *   the crystal-mine key. West stairs (dark: the fungus is the only light) to the crystal room;
 *   chisel a crystal outcrop (35 Crafting) for a salve shard. 2 QP, 22,000 Strength xp.
 *
 * Stages: 1 started, 2 inside the mine, 4 down the lift, 6 Treus raised, 9 Treus killed, 11 done.
 *
 * Simplified (Authored, not Wiki): the pickpocket always succeeds; the lever target below is
 * Authored (the Wiki gives the solution only as a picture, Ryan-test: set the levers to match the
 * Wiki's "Haunted Mine - Track map" image, and if the cart still sinks read the eight
 * `hauntedmine_lever_*` varbits and put them in [TARGET]); a wrong route always sinks the cart
 * (no "comes right back" outcome), with an Authored count of the wrong points; Start is offered
 * from a menu as well as the interface button; the moving ghost carts, the cranes, the mischievous
 * ghost and the valve race are left out (the lift stays powered); Treus is an ordinary fight; the
 * fungus also crumbles if carried back in through a tunnel (stands in for teleporting out); the
 * southern tunnel's inside end is not in the cache, so its tiles are Authored; every ladder,
 * stairs, lift and tunnel arrival tile is a best guess beside the destination loc (Ryan-test).
 */
class HauntedMine
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
    private val rng: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(HauntedMine::class.java, "haunted_mine") }

    /** Player uid -> the Treus Dayth they raised. */
    private val bosses = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, HmNpcs.zealot, HAUNTED_MINE, wants = { true }) { talkZealot(it) }
        }
        OpInterceptors.register(PickpocketClaim())

        onLocOpLabel(locTypes, HmLocs.obstacle_cart, "Climb-over") { climbCart(it) }
        onLocOpLabel(locTypes, HmLocs.secondary_entrance, "Crawl-down") {
            enterMine(SECONDARY_INSIDE)
        }
        onLocOpLabel(locTypes, HmLocs.back_entrance1, "Crawl-down") { enterMine(BACK1_INSIDE) }
        onLocOpLabel(locTypes, HmLocs.back_entrance2, "Crawl-down") { enterMine(BACK2_INSIDE) }
        onLocOpLabel(locTypes, HmLocs.secondary_entrance_inside, "Crawl-through") {
            leaveByTunnel(SECONDARY_OUTSIDE)
        }
        onLocOpLabel(locTypes, HmLocs.back_entrance1_inside, "Crawl-through") {
            leaveByTunnel(BACK1_OUTSIDE)
        }
        onLocOpLabel(locTypes, HmLocs.back_entrance2_inside, "Crawl-through") {
            leaveByTunnel(BACK2_OUTSIDE)
        }
        onLocOpLabel(locTypes, HmLocs.main_entrance, "Walk-down") { walkDownMain() }
        onLocOpLabel(locTypes, HmLocs.main_entrance_inside, "Walk-up") { walkUpMain() }
        onLocOpLabel(locTypes, HmLocs.shortcut_entrance, "Crawl-through") { enterShortcut() }
        onLocOpLabel(locTypes, HmLocs.shortcut_exit, "Crawl-through") { leaveShortcut() }

        for ((down, up, delta) in LADDERS) {
            onLocOpLabel(locTypes, down, "Climb-down") { climbLadder(it.coords.translate(delta.first, delta.second)) }
            onLocOpLabel(locTypes, up, "Climb-up") { climbLadder(it.coords.translate(-delta.first, -delta.second)) }
        }

        for (fungus in HmLocs.fungi) onLocOpLabel(locTypes, fungus, "Pick") { pickFungus() }
        onHeldOpLabel(objTypes, HmObjs.glowing_fungus, "Drop") { dropFungus() }
        onOpLocU(HmLocs.puzzle_cart, HmObjs.glowing_fungus) { placeFungus(it.loc) }
        onLocOpLabel(locTypes, HmLocs.puzzle_cart, "Search") { searchCart(it) }
        onLocOpLabel(locTypes, HmLocs.points_info, "Check") { checkPoints() }
        onIfModalButton(HmComponents.start) { startCart() }
        for ((lever, varbit) in HmLocs.levers.zip(HmVarBits.levers)) {
            onLocOpLabel(locTypes, lever, "Pull") { pullLever(varbit) }
        }
        for (lever in HmLocs.flooded_levers) {
            onLocOpLabel(locTypes, lever, "Pull") {
                mes("The lever is stuck fast; the points beneath it are under water.") // Authored.
            }
        }
        onLocOpLabel(locTypes, HmLocs.chisel_crate, "Search") { searchCrate() }

        onLocOpLabel(locTypes, HmLocs.lift_valve, "Turn") { turnValve(usedKey = false) }
        onOpLocU(HmLocs.lift_valve, HmObjs.lift_key) { turnValve(usedKey = true) }
        for (lift in HmLocs.lifts) onLocOpLabel(locTypes, lift, "Go-down") { goDownLift() }
        onLocOpLabel(locTypes, HmLocs.lift_flooded, "Go-up") { telejump(LIFT_TOP) }

        onLocOpLabel(locTypes, HmLocs.dark_stairs_top, "Walk-down") { walkDownDarkStairs(it) }
        onLocOpLabel(locTypes, HmLocs.light_stairs_bottom, "Walk-up") { walkUpStairs(it) }
        onLocOpLabel(locTypes, HmLocs.boss_door, "Open") { passThroughWall(it) }
        onNpcOpLabel(npcTypes, HmNpcs.boss_key, "Take") { takeKey(it) }
        onEvent<NpcKilledEvent> { bossKilled(this) }
        for (door in HmLocs.reward_doors) onLocOpLabel(locTypes, door, "Open") { openRewardDoor(it) }

        for (crystal in HmLocs.crystals) {
            onLocOpLabel(locTypes, crystal, "Cut") { cutCrystal() }
            onOpLocU(crystal, HmObjs.chisel) { cutCrystal() }
        }
        onOpHeldU(HmObjs.ball_of_wool, HmObjs.salve_shard) { stringShard() }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(HAUNTED_MINE)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(HAUNTED_MINE, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /* The Zealot */

    private suspend fun ProtectedAccess.talkZealot(npc: Npc) {
        when {
            stage == 0 -> {
                val hooks = ZealotHooks(this)
                runQuestSection(npc, dialogue, "Starting out", hooks)
                if (hooks.start) {
                    if (player.isQuestComplete(PRIEST_IN_PERIL)) {
                        advance(STARTED)
                    } else {
                        // Authored (not Wiki): the quest requirement.
                        mes("You need to have completed Priest in Peril to start this quest.")
                    }
                }
                followUps(npc, hooks)
            }
            stage < INSIDE -> {
                val hooks = ZealotHooks(this)
                runQuestSection(npc, dialogue, ZEALOT_AGAIN, hooks)
                followUps(npc, hooks)
            }
            stage < ENCOUNTERED -> {
                val hooks = ZealotHooks(this)
                runQuestSection(npc, dialogue, "Talking to the zealot after finding the second entrance", hooks)
                if (hooks.askedForKey) setFlag(HmVarBits.heard_about_key, 1)
            }
            stage < KILLED -> runQuestSection(npc, dialogue, "Talking to the zealot after encountering Treus Dayth")
            else -> runQuestSection(npc, dialogue, "Talking to the zealot after killing Treus Dayth")
        }
    }

    /** Runs the branches the transcript marks "below" (they are told under "Talking to the zealot again"). */
    private suspend fun ProtectedAccess.followUps(npc: Npc, hooks: ZealotHooks) {
        repeat(MAX_FOLLOW_UPS) {
            val label = hooks.pending ?: return@repeat
            hooks.pending = null
            val nodes = followUp(label) ?: return@repeat
            startDialogue(npc) { QuestDialogueRunner(hooks).run(this, nodes) }
        }
        if (hooks.heard && stage >= STARTED) setFlag(HmVarBits.heard_about_key, 1)
    }

    /** The top-level option [label] of "Talking to the zealot again", else the first one with lines. */
    private fun followUp(label: String): List<Node>? {
        val again = dialogue.section(ZEALOT_AGAIN)
        again.firstOrNull { it is Node.Option && it.label == label }?.let { return it.children }
        fun find(nodes: List<Node>): List<Node>? {
            for (node in nodes) {
                if (node is Node.Option && node.label == label && node.children.any { it is Node.Line }) {
                    return node.children
                }
                find(node.children)?.let { return it }
            }
            return null
        }
        return find(again)
    }

    private inner class ZealotHooks(private val access: ProtectedAccess) : QuestDialogueHooks {
        var start = false
        var heard = false
        var askedForKey = false
        var pending: String? = null
        private var last: String? = null

        override fun condition(text: String): Boolean =
            text == "After turning the water valve and before going down the lift:" &&
                with(access) { flag(HmVarBits.lift_powered_once) == 0 && !carries(HmObjs.lift_key) }.also {
                    if (it) askedForKey = true
                }

        override suspend fun Dialogue.onOption(label: String) {
            last = label
            when (label) {
                "Yes." -> start = true
                "Is there any other way into the mines?" -> heard = true
            }
        }

        override suspend fun Dialogue.onAction(tag: String) {
            if (tag == "below") pending = last
        }
    }

    /** Pickpocketing the Zealot once the quest is started gives his key. */
    private inner class PickpocketClaim : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op3 &&
                npc.id == HmNpcs.zealot.id &&
                player.questState(HAUNTED_MINE) in STARTED until LIFT &&
                player.inv.none { it?.id == HmObjs.lift_key.id }

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            invAddOrDrop(objRepo, HmObjs.lift_key)
            mes("You pick the zealot's pocket and find a key.") // Authored (not Wiki).
        }
    }

    /* Into the mine */

    private suspend fun ProtectedAccess.climbCart(cart: BoundLocInfo) {
        if (stat(stats.agility) < AGILITY_LEVEL) {
            mes("You need an Agility level of $AGILITY_LEVEL to climb over the mine cart.") // Authored.
            return
        }
        val north = player.coords.z > cart.coords.z
        mes("You climb over the mine cart.") // Authored (not Wiki).
        telejump(CoordGrid(cart.coords.x, cart.coords.z + if (north) -2 else 2, 0))
        delay(1)
    }

    private suspend fun ProtectedAccess.enterMine(dest: CoordGrid) {
        crumbleFungus("Teleporting out with the glowing fungus")
        telejump(dest)
        delay(1)
        advance(INSIDE)
    }

    private suspend fun ProtectedAccess.leaveByTunnel(dest: CoordGrid) {
        crumbleFungus("Exiting the mine via the cart tunnel with the glowing fungus")
        telejump(dest)
        delay(1)
    }

    private suspend fun ProtectedAccess.walkDownMain() {
        telejump(MAIN_INSIDE)
        delay(1)
    }

    private suspend fun ProtectedAccess.walkUpMain() {
        crumbleFungus("Teleporting out with the glowing fungus")
        telejump(MAIN_OUTSIDE)
        delay(1)
    }

    private suspend fun ProtectedAccess.crumbleFungus(section: String) {
        val count = invTotal(inv, HmObjs.glowing_fungus)
        if (count == 0) return
        invDel(inv, HmObjs.glowing_fungus, count)
        runQuestSection(null, dialogue, section)
    }

    private suspend fun ProtectedAccess.enterShortcut() {
        if (stage < COMPLETE) {
            runQuestSection(null, dialogue, "Attempting to enter the shortcut by the entrance")
            return
        }
        telejump(SHORTCUT_BOTTOM)
        delay(1)
    }

    private suspend fun ProtectedAccess.leaveShortcut() {
        if (stage < COMPLETE) {
            runQuestSection(null, dialogue, "Trying to leave through the cart tunnel before cutting a shard")
            return
        }
        telejump(SHORTCUT_TOP)
        delay(1)
    }

    /** Arrives on the tile east of the destination ladder (Authored, Ryan-test). */
    private suspend fun ProtectedAccess.climbLadder(ladder: CoordGrid) {
        anim(seqs.human_reachforladder)
        delay(1)
        telejump(ladder.translate(1, 0))
    }

    /* Level 4: the fungus and the points */

    private suspend fun ProtectedAccess.pickFungus() {
        if (carries(HmObjs.glowing_fungus)) {
            mes("You already have a glowing fungus.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Picking the glowing fungus")
        invAdd(inv, HmObjs.glowing_fungus)
    }

    private suspend fun ProtectedAccess.dropFungus() {
        invDel(inv, HmObjs.glowing_fungus, invTotal(inv, HmObjs.glowing_fungus))
        runQuestSection(null, dialogue, "Dropping the glowing fungus")
    }

    private fun BoundLocInfo.isEndCart(): Boolean = coords.z >= END_CART_Z

    private suspend fun ProtectedAccess.placeFungus(cart: BoundLocInfo) {
        if (cart.isEndCart()) {
            runQuestSection(null, dialogue, "Attempting to place the fungus back in the minecart")
            return
        }
        if (flag(HmVarBits.begincart_fungus) == 1) {
            mes("There's already a glowing fungus in the cart.") // Authored (not Wiki).
            return
        }
        invDel(inv, HmObjs.glowing_fungus)
        setFlag(HmVarBits.begincart_fungus, 1)
        mes("You place the glowing fungus in the mine cart.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.searchCart(cart: BoundLocInfo) {
        if (!cart.isEndCart()) {
            if (flag(HmVarBits.begincart_fungus) == 1 && !inv.isFull()) {
                setFlag(HmVarBits.begincart_fungus, 0)
                invAdd(inv, HmObjs.glowing_fungus)
                mes("You take the glowing fungus back out of the cart.") // Authored (not Wiki).
            } else {
                mes("The mine cart is empty.") // Authored (not Wiki).
            }
            return
        }
        if (flag(HmVarBits.endcart_fungus) == 0) {
            mes("The mine cart is empty.") // Authored (not Wiki).
            return
        }
        var take = false
        runQuestSection(null, dialogue, "Retrieving the glowing fungus", SimpleQuestHooks(options = mapOf("Take it." to { take = true })))
        if (!take) return
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        setFlag(HmVarBits.endcart_fungus, 0)
        invAdd(inv, HmObjs.glowing_fungus)
    }

    private suspend fun ProtectedAccess.checkPoints() {
        if (flag(HmVarBits.puzzle_started) == 0) {
            // Each player's levers start scrambled (never already solved).
            for (varbit in HmVarBits.levers) setFlag(varbit, rng.of(0, 1))
            if (solved()) setFlag(HmVarBits.levers[0], 1 - TARGET[0])
            setFlag(HmVarBits.puzzle_started, 1)
        }
        var start = false
        startDialogue {
            // Authored (not Wiki): a menu beside the interface's own Start button.
            start = choice2("Look at the points settings.", 0, "Press Start.", 1) == 1
        }
        if (start) startCart() else ifOpenMainModal(HmInterfaces.controls)
    }

    private fun ProtectedAccess.solved(): Boolean = wrongPoints() == 0

    private fun ProtectedAccess.wrongPoints(): Int =
        HmVarBits.levers.indices.count { flag(HmVarBits.levers[it]) != TARGET[it] }

    private suspend fun ProtectedAccess.startCart() {
        ifClose()
        if (flag(HmVarBits.puzzle_started) == 0) return
        val fungus = flag(HmVarBits.begincart_fungus) == 1
        val wrong = wrongPoints()
        if (wrong > 0) {
            setFlag(HmVarBits.begincart_fungus, 0)
            mes("Oh dear, the mine cart seems to have sunk.")
            mes("$wrong of the points must be set the wrong way.") // Authored (not Wiki).
            return
        }
        if (!fungus) return
        setFlag(HmVarBits.begincart_fungus, 0)
        setFlag(HmVarBits.endcart_fungus, 1)
        runQuestSection(null, dialogue, "After moving the cart with glowing fungus to the correct position")
    }

    private suspend fun ProtectedAccess.pullLever(varbit: VarBitType) {
        anim(seqs.human_pickupfloor)
        setFlag(varbit, 1 - flag(varbit))
        runQuestSection(null, dialogue, "Pulling track levers")
    }

    private fun ProtectedAccess.searchCrate() {
        if (carries(HmObjs.chisel) || inv.isFull()) {
            mes("You search the crate but find nothing of interest.") // Authored (not Wiki).
            return
        }
        invAdd(inv, HmObjs.chisel)
        mes("You find a chisel on the crate.") // Authored (not Wiki).
    }

    /* The lift */

    private suspend fun ProtectedAccess.turnValve(usedKey: Boolean) {
        val unlocked = usedKey || flag(HmVarBits.lift_powered_once) == 1
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not used the zealot's key on the valve:" to { !unlocked },
                        "If the player has used the zealot's key on the valve:" to { unlocked },
                    )
            )
        runQuestSection(null, dialogue, "Turning the water valve", hooks)
        if (!unlocked) return
        setFlag(HmVarBits.lift_powered_once, 1)
        setFlag(HmVarBits.lift_powered_now, 1)
    }

    private suspend fun ProtectedAccess.goDownLift() {
        if (flag(HmVarBits.lift_powered_now) == 0 && flag(HmVarBits.lift_powered_once) == 0) {
            mes("The lift has no power.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Taking the lift")
        telejump(LIFT_BOTTOM)
        delay(1)
        if (carries(HmObjs.tinderbox)) {
            invReplace(inv, HmObjs.tinderbox, invTotal(inv, HmObjs.tinderbox), HmObjs.damp_tinderbox)
            runQuestSection(null, dialogue, "If the player has a tinderbox in their inventory")
        }
        if (stage >= INSIDE) advance(LIFT)
    }

    /* Levels 5 and 6 */

    private suspend fun ProtectedAccess.walkDownDarkStairs(stairs: BoundLocInfo) {
        val crystalSide = stairs.coords.x < STAIRS_SPLIT_X
        if (crystalSide && !carries(HmObjs.glowing_fungus)) {
            runQuestSection(null, dialogue, "Going down to the sixth level without the fungus")
            return
        }
        telejump(if (crystalSide) CRYSTAL_STAIRS_BOTTOM else BOSS_STAIRS_BOTTOM)
        delay(1)
    }

    private suspend fun ProtectedAccess.walkUpStairs(stairs: BoundLocInfo) {
        val crystalSide = stairs.coords.x < LIGHT_STAIRS_SPLIT_X
        telejump(if (crystalSide) CRYSTAL_STAIRS_TOP else BOSS_STAIRS_TOP)
        delay(1)
    }

    private suspend fun ProtectedAccess.takeKey(key: Npc) {
        when {
            carries(HmObjs.reward_key) -> runQuestSection(null, dialogue, "Trying to take the key again")
            stage >= KILLED -> {
                if (inv.isFull()) {
                    mes("You don't have enough inventory space.") // Authored (not Wiki).
                    return
                }
                runQuestSection(null, dialogue, "Taking the crystal-mine key")
                invAdd(inv, HmObjs.reward_key)
            }
            bosses[player.uid.packed]?.isSlotAssigned == true ->
                runQuestSection(null, dialogue, "Trying to take the key while Treus Dayth is present")
            stage < LIFT -> mes("The key slides away from your hand.") // Authored (not Wiki).
            else -> {
                // The transcript's box keeps the Wiki template's closing braces.
                val trimmed = SimpleQuestHooks(rewrites = { it.removeSuffix("}}") })
                runQuestSection(null, dialogue, "Attempting to take the innocent-looking key", trimmed)
                advance(ENCOUNTERED)
                raiseTreus(player, key.coords)
            }
        }
    }

    private fun raiseTreus(player: Player, at: CoordGrid) {
        val boss = Npc(npcTypes[HmNpcs.boss_ghost], at)
        npcRepo.add(boss, BOSS_TICKS)
        bosses[player.uid.packed] = boss
        boss.opPlayer2(player, aiInteractions)
    }

    private fun bossKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != HmNpcs.boss_ghost.id) return
        val player = event.hero
        bosses.remove(player.uid.packed)
        val stage = player.questState(HAUNTED_MINE)
        if (stage < ENCOUNTERED || stage >= KILLED) return
        player.setQuestState(HAUNTED_MINE, KILLED)
        player.mes("As you make the killing strike, the machinery within the room falls silent.")
    }

    private suspend fun ProtectedAccess.openRewardDoor(door: BoundLocInfo) {
        if (!carries(HmObjs.reward_key)) {
            val section =
                if (door.coords.z > LEVEL6_MAX_Z) "Attempting to open the large door"
                else "Attempting to open the locked larger doors without the key"
            runQuestSection(null, dialogue, section)
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.cutCrystal() {
        if (!carries(HmObjs.chisel)) {
            mes("You need a chisel to cut the crystal.") // Authored (not Wiki).
            return
        }
        if (stat(stats.crafting) < CRAFTING_LEVEL) {
            mes("You need a Crafting level of $CRAFTING_LEVEL to cut the crystal.") // Authored.
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        anim(seqs.human_pickupfloor)
        runQuestSection(null, dialogue, "Chiseling a crystal outcrop")
        invAdd(inv, HmObjs.salve_shard)
        if (stage in KILLED until COMPLETE) {
            statAdvance(stats.strength, STRENGTH_XP * xpMods.get(player, stats.strength))
            completeQuest(HAUNTED_MINE)
        }
    }

    private fun ProtectedAccess.stringShard() {
        invDel(inv, HmObjs.ball_of_wool)
        invReplace(inv, HmObjs.salve_shard, 1, HmObjs.salve_amulet)
        mes("You string the salve shard with the ball of wool.") // Authored (not Wiki).
    }

    companion object {
        const val STARTED = 1
        const val INSIDE = 2
        const val LIFT = 4
        const val ENCOUNTERED = 6
        const val KILLED = 9
        const val COMPLETE = 11

        private const val ZEALOT_AGAIN = "Talking to the zealot again"
        private const val MAX_FOLLOW_UPS = 4
        private const val AGILITY_LEVEL = 15
        private const val CRAFTING_LEVEL = 35
        private const val STRENGTH_XP = 22000.0
        private const val BOSS_TICKS = 1000

        /**
         * Authored target for levers B, A, C, D, E, I, J, K (the [HmVarBits.levers] order); the
         * Wiki shows the solution only as an image (Ryan-test, see the class KDoc).
         */
        private val TARGET = intArrayOf(1, 0, 1, 1, 0, 1, 0, 1)

        /** Level 4's begin cart is at z 4506, the end cart (by ladder 10) at z 4537. */
        private const val END_CART_Z = 4520
        /** Level 5's dark stairs: the west one (x 2692) leads to the crystals, the east to Treus. */
        private const val STAIRS_SPLIT_X = 2720
        /** Level 6's stairs up: x 2755 (crystal side), x 2812 (crane chamber). */
        private const val LIGHT_STAIRS_SPLIT_X = 2780
        /** Level 6 is south of z 4460; level 2's locked room is far north of it. */
        private const val LEVEL6_MAX_Z = 4460

        /**
         * Ladder pairs (Climb-down loc, Climb-up loc, offset from the top to the bottom), from the
         * cache positions: level 1 to 2, 2 to 3 and 3 to 4.
         */
        private val LADDERS: List<Triple<LocType, LocType, Pair<Int, Int>>> by lazy {
            listOf(
                Triple(HmLocs.laddertop, HmLocs.ladder, -640 to -5056),
                Triple(HmLocs.laddertop_1sw, HmLocs.ladder_1ne, -64 to -64),
                Triple(HmLocs.laddertop_1e, HmLocs.ladder_1w, 64 to 0),
            )
        }

        /** Authored arrival tiles (Ryan-test). */
        private val SECONDARY_INSIDE = CoordGrid(3435, 9637, 0)
        private val BACK1_INSIDE = CoordGrid(3406, 9631, 0)
        private val BACK2_INSIDE = CoordGrid(3421, 9624, 0)
        private val SECONDARY_OUTSIDE = CoordGrid(3442, 3232, 0)
        private val BACK1_OUTSIDE = CoordGrid(3432, 3233, 0)
        private val BACK2_OUTSIDE = CoordGrid(3431, 3225, 0)
        private val MAIN_INSIDE = CoordGrid(2791, 4590, 0)
        private val MAIN_OUTSIDE = CoordGrid(3454, 3243, 0)
        private val SHORTCUT_BOTTOM = CoordGrid(2800, 4430, 0)
        private val SHORTCUT_TOP = CoordGrid(3435, 9635, 0)
        private val LIFT_TOP = CoordGrid(2805, 4493, 0)
        private val LIFT_BOTTOM = CoordGrid(2725, 4454, 0)
        private val CRYSTAL_STAIRS_BOTTOM = CoordGrid(2757, 4452, 0)
        private val BOSS_STAIRS_BOTTOM = CoordGrid(2814, 4452, 0)
        private val CRYSTAL_STAIRS_TOP = CoordGrid(2693, 4439, 0)
        private val BOSS_STAIRS_TOP = CoordGrid(2744, 4436, 0)

        val HAUNTED_MINE =
            QuestDef(
                name = "Haunted Mine",
                varp = { HmVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "22,000 Strength XP",
                        "The ability to make the Salve amulet",
                        "The crystal-mine key",
                        "A shortcut to the crystal outcrops",
                        "Access to Tarn's Lair",
                    ),
                scrollObj = { HmObjs.salve_shard },
            )
    }
}

// Every cache ref Haunted Mine uses (re-check after cache updates).

internal object HmNpcs : NpcReferences() {
    val zealot = find("saradominist_zealot")
    /** Treus Dayth. */
    val boss_ghost = find("hauntedmine_boss_ghost")
    /** The innocent-looking key (world spawn in the crane chamber, 2788,4455). */
    val boss_key = find("hauntedmine_boss_key")
}

internal object HmLocs : LocReferences() {
    val obstacle_cart = find("hauntedmine_obstacle_cart")
    val secondary_entrance = find("hauntedmine_secondary_entrance")
    val back_entrance1 = find("hauntedmine_back_entrance1")
    val back_entrance2 = find("hauntedmine_back_entrance2")
    val secondary_entrance_inside = find("hauntedmine_secondary_entrance_inside")
    val back_entrance1_inside = find("hauntedmine_back_entrance1_inside")
    /** Not placed in the cache map. */
    val back_entrance2_inside = find("hauntedmine_back_entrance2_inside")
    val main_entrance = find("hauntedmine_main_entrance")
    val main_entrance_inside = find("hauntedmine_main_entrance_inside")
    val shortcut_entrance = find("hauntedmine_crystalshortcut_entrance")
    val shortcut_exit = find("hauntedmine_crystalshortcut_exit")
    val laddertop = find("hauntedmine_laddertop")
    val ladder = find("hauntedmine_ladder")
    val laddertop_1sw = find("hauntedmine_laddertop_1sw")
    val ladder_1ne = find("hauntedmine_ladder_1ne")
    val laddertop_1e = find("hauntedmine_laddertop_1e")
    val ladder_1w = find("hauntedmine_ladder_1w")
    val fungi = listOf(find("glowing_mushroom"), find("glowing_mushroom2"))
    /** Begin cart (2778,4506) and end cart (2774,4537). */
    val puzzle_cart = find("hauntedmine_puzzle_cart")
    val points_info = find("hauntedmine_points_info")
    /** Levers 1-8 are B, A, C, D, E, I, J, K (the Wiki's lever pictures). */
    val levers =
        listOf(
            find("hauntedmine_point_lever1"),
            find("hauntedmine_point_lever2"),
            find("hauntedmine_point_lever3"),
            find("hauntedmine_point_lever4"),
            find("hauntedmine_point_lever5"),
            find("hauntedmine_point_lever6"),
            find("hauntedmine_point_lever7"),
            find("hauntedmine_point_lever8"),
        )
    /** F, G and H, under water. */
    val flooded_levers =
        listOf(
            find("hauntedmine_point_lever_flooded9"),
            find("hauntedmine_point_lever_flooded10"),
            find("hauntedmine_point_lever_flooded11"),
        )
    val chisel_crate = find("hauntedmine_chisel_crate")
    val lift_valve = find("hauntedmine_lift_valve")
    val lifts = listOf(find("lift_side_l"), find("lift_side_r"), find("lift_back"))
    val lift_flooded = find("lift_flooded")
    val dark_stairs_top = find("hauntedmine_dark_stairs_top")
    val light_stairs_bottom = find("hauntedmine_light_stairs_bottom")
    val boss_door = find("hauntedmine_boss_door")
    /** Level 6's crystal room doors and level 2's locked room doors. */
    val reward_doors = listOf(find("hauntedmine_rewarddoor_l"), find("hauntedmine_rewarddoor_r"))
    val crystals = listOf(find("largecrystals"), find("crystalcorner"), find("crystaledging"))
}

internal object HmObjs : ObjReferences() {
    val glowing_fungus = find("glowing_fungus")
    /** The zealot's key. */
    val lift_key = find("hauntedmine_lift_key")
    /** The crystal-mine key. */
    val reward_key = find("hauntedmine_reward_key")
    val salve_shard = find("crystalshard_necklace_unstrung")
    val salve_amulet = find("crystalshard_necklace")
    val chisel = find("chisel")
    val ball_of_wool = find("ball_of_wool")
    val tinderbox = find("tinderbox")
    val damp_tinderbox = find("damp_tinderbox")
}

internal object HmVarps : VarpReferences() {
    val quest = find("hauntedmine")
}

internal object HmVarBits : VarBitReferences() {
    val puzzle_started = find("hauntedmine_pointspuzzlestarted")
    /** Levers B, A, C, D, E, I, J, K: the order of [HmLocs.levers] (point_lever1..8). */
    val levers =
        listOf(
            find("hauntedmine_lever_b"),
            find("hauntedmine_lever_a"),
            find("hauntedmine_lever_c"),
            find("hauntedmine_lever_d"),
            find("hauntedmine_lever_e"),
            find("hauntedmine_lever_i"),
            find("hauntedmine_lever_j"),
            find("hauntedmine_lever_k"),
        )
    val lift_powered_once = find("hauntedmine_liftpoweredonce")
    val lift_powered_now = find("hauntedmine_liftpowerednow")
    val begincart_fungus = find("hauntedmine_begincart_fungus")
    val endcart_fungus = find("hauntedmine_endcart_fungus")
    val heard_about_key = find("hauntedmine_heardaboutkey")
}

internal object HmInterfaces : InterfaceReferences() {
    val controls = find("hauntedmine_controls")
}

internal object HmComponents : ComponentReferences() {
    val start = find("hauntedmine_controls:start")
}
