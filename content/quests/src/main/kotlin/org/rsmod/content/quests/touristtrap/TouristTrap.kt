package org.rsmod.content.quests.touristtrap

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * The Tourist Trap (OSRS Wiki). Progress lives in the cache quest varp `desertrescue` (197; the
 * rev 243 dump omits the dbrow's varp column, inferred from the varp name and the db end state 30),
 * plus the cache varbits in `tourtrap_qip_var` that drive the multinpcs/multilocs (Irena happy,
 * Ana gone from the mine, the escaping slave, the bent cell bars, the winch-side barrel, Ana on the
 * wooden cart, Ana at the Shantay Pass), and our server varp `tourist_trap_progress` (side
 * branches: Al Zaba Bhasim, the evictions, the bet, Ana talked to). The stages are ours:
 * - Irena (Shantay Pass) asks you to find her daughter Ana (1).
 * - The Mercenary Captain: "It's a funny captain who can't fight his own battles!" (or the Al Zaba
 *   Bhasim errand) makes him fight (2); killing him gives the metal gate key (3).
 * - Inside the camp (no weapons or armour): the male slave by the mine doors wants his chains off
 *   (4), the lock clicks (5), desert clothes traded for his slave robes (6).
 * - In the mine, the cave guard wants a Tenti pineapple (7). Al Shabim (Bedabin Camp) gives the
 *   chest key (8); Captain Siad, distracted ("the cut of your jib"), loses his technical plans (9);
 *   Al Shabim lets you use the experimental anvil (10): bronze bar -> prototype dart tip (Smithing
 *   20), + 10 feathers -> prototype dart (Fletching 10); the dart buys the pineapple, six bronze
 *   darts and dart smithing (11). The pineapple gets you past the cave guard (12).
 * - Ana in a barrel (13), down the mine cart (14), out of the full barrel (15), onto the winch
 *   bucket with a "gregarious" guard (16), winched up (17), picked up (18), onto the wooden cart
 *   (19), the cart driver talked round (20), out of the camp on the cart (21).
 * - Irena: Ana gives the wrought iron key (22), the first reward skill (25), the second (30):
 *   2 QP, 4,650 xp in each pick of Agility/Fletching/Smithing/Thieving (Wiki; same skill twice is
 *   allowed).
 *
 * Simplified (all listed in TtCamp.kt's header too): no desert heat; no cutscenes (the mine cart,
 * the winch and the camp cart are telejumps); the guards' look-over is a periodic check, not
 * line-of-sight; the "guard nearby" rule for the slave's chains is any guard within 3 tiles at the
 * time you talk; Siad's dragon/fire/slaves distractions work 1 time in 4 (the jib always works) and
 * last 50 ticks; the bank check for a remembered gate key is skipped; the Bedabin Nomad's "What do
 * you have to sell?" doesn't open the shop (the nomad's Trade option does).
 */
class TouristTrap
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TouristTrap::class.java, "tourist_trap") }
    private val shabimDialogue by lazy { QuestDialogue.load(TouristTrap::class.java, "al_shabim") }

    /** Siad's distraction: player uid -> map clock until which he's looking away. */
    private val distracted = HashMap<Int, Int>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, TtNpcs.irena, TOURIST_TRAP, wants = { true }) { talkIrena(it) }
            register(npcTypes, TtNpcs.irena_happy, TOURIST_TRAP, wants = { true }) { talkIrena(it) }
            register(npcTypes, TtNpcs.captain, TOURIST_TRAP, wants = { true }) { talkCaptain(it) }
            for (merc in TtNpcs.mercenaries) {
                register(npcTypes, merc, TOURIST_TRAP, wants = { true }) { talkMercenary(it) }
            }
            register(npcTypes, TtNpcs.al_shabim, TOURIST_TRAP, wants = { true }) { talkShabim(it) }
            register(npcTypes, TtNpcs.nomad_guard, TOURIST_TRAP, wants = { true }) { talkNomadGuard(it) }
            register(npcTypes, TtNpcs.siad_vis, TOURIST_TRAP, wants = { true }) { talkSiad(it) }
            default(npcTypes, TtNpcs.nomad) { ttSection(dialogue, it, "Talking to Bedabin nomad") }
        }
        if (!isNpcOpBound(TtNpcs.captain, CAPTAIN_WATCH_OP)) {
            onOpNpc3(TtNpcs.captain) { ttSection(dialogue, null, "Watching the mercenary captain") }
        }
        OpInterceptors.register(AttackClaim())
        onEvent<NpcKilledEvent> { captainKilled(this) }

        // Captain Siad's quarters.
        bindLoc(TtLocs.chest, "Open") { openChest() }
        bindLoc(TtLocs.bookcase, "Search") { ttSection(dialogue, null, "Searching the bookcase by Captain Siad") }
        bindLoc(TtLocs.table, "Search") { searchTable(it) }

        // The Bedabin camp: the tent and the experimental anvil.
        bindLoc(TtLocs.tent_door, "Walk-through") { tentDoor(it) }
        bindLoc(TtLocs.anvil, "Use") { ttSection(dialogue, null, "Using the experimental anvil") }
        bindLocU(TtLocs.anvil, TtObjs.bronze_bar) { forgeDartTip() }
        bindHeldU(TtObjs.dart_tip, TtObjs.feather) { fletchDart() }
    }

    /* Binding helpers (a duplicate bind crashes boot). */

    private fun ScriptContext.bindLoc(loc: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(loc, op)) return
        onLocOpLabel(locTypes, loc, label, action)
    }

    private fun ScriptContext.bindLocU(loc: LocType, obj: ObjType, action: suspend ProtectedAccess.() -> Unit) {
        if (eventBus.contains(LocUEvents.Op::class.java, EventBus.composeLongKey(loc.id, obj.id))) return
        onOpLocU(loc, obj) { action() }
    }

    private fun ScriptContext.bindHeldU(first: ObjType, second: ObjType, action: suspend ProtectedAccess.() -> Unit) {
        val type = HeldUEvents.Type::class.java
        if (eventBus.contains(type, EventBus.composeLongKey(first.id, second.id))) return
        if (eventBus.contains(type, EventBus.composeLongKey(second.id, first.id))) return
        onOpHeldU(first, second) { action() }
    }

    private val ProtectedAccess.state: Int
        get() = player.ttState

    private fun ProtectedAccess.setState(value: Int) = player.setTtState(value)

    /* Irena */

    private suspend fun ProtectedAccess.talkIrena(npc: Npc) {
        when {
            state == 0 -> {
                val hooks = TtHooks(this, options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                ttSection(dialogue, npc, "Starting off", hooks)
            }
            state < ESCAPED -> ttSection(dialogue, npc, "Talking to Irena again")
            state == ESCAPED -> rewardSection(npc, "Talking to Irena while holding Ana in a barrel")
            state == KEY_GIVEN -> rewardSection(npc, "Talking to Irena after rescuing Ana")
            state == FIRST_SKILL -> rewardSection(npc, "Talking to Irena after choosing the first skill")
            else -> ttSection(dialogue, npc, "Talking to Irena after choosing both skills")
        }
    }

    private suspend fun ProtectedAccess.rewardSection(npc: Npc, section: String) {
        val access = this
        val hooks =
            TtHooks(
                this,
                boxes =
                    mapOf(
                        "Ana gives you a wrought iron key..." to {
                            if (state == ESCAPED) {
                                invDel(inv, TtObjs.ana_barrel, invTotal(inv, TtObjs.ana_barrel).coerceAtLeast(1), strict = false)
                                invAddOrDrop(objRepo, TtObjs.wrought_key)
                                setState(KEY_GIVEN)
                            }
                            false
                        }
                    ),
                actions = mapOf("Choose a reward skill" to { access.chooseSkill(it) }),
            )
        ttSection(dialogue, npc, section, hooks)
        if (state >= COMPLETE) finish()
    }

    /** Irena's "increased knowledge" in one skill (Wiki: 4,650 xp; the same skill may be picked twice). */
    private suspend fun ProtectedAccess.chooseSkill(dlg: Dialogue) {
        if (state != KEY_GIVEN && state != FIRST_SKILL) return
        val skill =
            dlg.choice4(
                "Fletching.",
                stats.fletching,
                "Agility.",
                stats.agility,
                "Smithing.",
                stats.smithing,
                "Thieving",
                stats.thieving,
                title = "Select an Option",
            )
        grantSkill(skill)
        setState(if (state == KEY_GIVEN) FIRST_SKILL else COMPLETE)
    }

    private fun ProtectedAccess.grantSkill(skill: StatType) {
        statAdvance(skill, REWARD_XP * xpMods.get(player, skill))
    }

    private fun ProtectedAccess.finish() {
        player.setTtVarBit(TtVarBits.irena_state, 1)
        player.setTtVarBit(TtVarBits.ana_shanty, 1)
        completeQuest(TOURIST_TRAP)
    }

    /* The mercenaries and their captain */

    private suspend fun ProtectedAccess.talkMercenary(npc: Npc) {
        val bet = player.ttBits(BET_SHIFT, BET_MASK)
        when {
            state >= GATE_KEY && bet > 0 -> {
                val hooks =
                    TtHooks(
                        this,
                        conditions =
                            mapOf(
                                "If the player bet 5 coins:" to { bet == 1 },
                                "If the player bet 10 coins:" to { bet == 2 },
                                "If the player bet 15 coins:" to { bet == 3 },
                                "If the player bet 20 coins:" to { bet == 4 },
                            ),
                    )
                ttSection(dialogue, npc, "Talking to the Mercenary to collect a bet", hooks)
                player.setTtBits(BET_SHIFT, BET_MASK, 0)
                invAddOrDrop(objRepo, TtObjs.coins, BET_PAYOUTS[bet - 1])
            }
            state >= GATE_KEY -> ttSection(dialogue, npc, "Talking to a guard again")
            else -> {
                var short = false
                var dumped = false
                var hadWater = false
                val options = HashMap<String, suspend () -> Unit>()
                for (label in BRIBES) {
                    options[label] = {
                        short = invTotal(inv, TtObjs.coins) < BRIBE
                        if (!short) invDel(inv, TtObjs.coins, BRIBE)
                    }
                }
                for ((index, amount) in BETS.withIndex()) {
                    options["I'll bet $amount gold that I win."] = {
                        short = invTotal(inv, TtObjs.coins) < amount
                        if (!short && player.ttBits(BET_SHIFT, BET_MASK) == 0) {
                            invDel(inv, TtObjs.coins, amount)
                            player.setTtBits(BET_SHIFT, BET_MASK, index + 1)
                        }
                    }
                }
                val hooks =
                    TtHooks(
                        this,
                        conditions =
                            mapOf(
                                "If the player does not have 5 coins:" to { short },
                                "If the player doesn't have enough gold:" to { short },
                                "If the player already placed a bet:" to { bet > 0 },
                                "If the player has any filled waterskins:" to { hadWater },
                            ),
                        options = options,
                        actions =
                            mapOf(
                                "The player is teleported somewhere north-west of the mining camp" to {
                                    hadWater = hasFilledWaterskin()
                                    dumped = true
                                    dumpInDesert(collision)
                                }
                            ),
                    )
                ttSection(dialogue, npc, "Talking to a Mercenary", hooks)
                if (dumped) return
            }
        }
    }

    private suspend fun ProtectedAccess.talkCaptain(npc: Npc) {
        if (state >= GATE_KEY) {
            ttSection(dialogue, npc, "Talking to a captain again")
            return
        }
        var evict = false
        var fight = false
        val hooks =
            TtHooks(
                this,
                options = mapOf("Consider it done." to { player.setTtBits(ZABA_SHIFT, 1, 1) }),
                actions =
                    mapOf(
                        "(Continues with Guards eviction dialogue.)" to { evict = true },
                        "(The Mercenary Captain attacks.)" to { fight = true },
                    ),
            )
        val told = player.ttBits(ZABA_TOLD_SHIFT, 1) == 1
        val section =
            if (told) "Returning to the mercenary captain after learning about Al Zaba Bhasim"
            else "Talking to the Mercenary Captain"
        ttSection(dialogue, npc, section, hooks)
        when {
            fight && state >= STARTED -> {
                if (state < CAPTAIN_ANGRY) setState(CAPTAIN_ANGRY)
                npc.opPlayer2(player, aiInteractions)
            }
            fight -> {
                // Authored (not Wiki): before Irena's request the captain leaves it to his men.
                evictions(npc)
            }
            evict -> evictions(npc)
        }
    }

    private suspend fun ProtectedAccess.evictions(npc: Npc) {
        val count = player.ttBits(EVICT_SHIFT, EVICT_MASK)
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If this is the first time being evicted by the guards:" to { count == 0 },
                        "If this is the second time being evicted by the guards:" to { count == 1 },
                        "If this is the third time being evicted by the guards:" to { count == 2 },
                        "If this is the fourth or greater time being evicted by the guards:" to { count >= 3 },
                    ),
            )
        ttSection(dialogue, npc, "Guards eviction dialogue", hooks)
        if (count >= 3) {
            dumpInDesert(collision)
        } else {
            player.setTtBits(EVICT_SHIFT, EVICT_MASK, count + 1)
        }
    }

    /** Attacking before the captain accepts the duel, any mercenary, or the winch guard. */
    private inner class AttackClaim : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean {
            if (op != InteractionOp.Op2) return false
            val id = npc.id
            return (id == TtNpcs.captain.id && player.ttState < CAPTAIN_ANGRY) ||
                TtNpcs.mercenaries.any { it.id == id } ||
                id == TtNpcs.winch_guard.id
        }

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            when (npc.id) {
                TtNpcs.captain.id -> {
                    var dump = false
                    val hooks =
                        TtHooks(
                            this,
                            boxes = mapOf("Sometime later you wake up in the desert." to { dump = true; false }),
                        )
                    ttSection(dialogue, npc, "Trying to attack the Mercenary Captain", hooks)
                    if (dump) dumpInDesert(collision)
                }
                TtNpcs.winch_guard.id -> {
                    var caught = false
                    val hooks =
                        TtHooks(this, actions = mapOf("(Continues with getting jailed dialogue.)" to { caught = true }))
                    ttSection(dialogue, npc, "Attacking the guard by the winch", hooks)
                    if (caught) player.jail(collision, underground = true) { random.randomBoolean() }
                }
                else -> {
                    var beaten = false
                    val hooks =
                        TtHooks(
                            this,
                            actions =
                                mapOf("(The player is hit 4 times before they are transported into the desert.)" to { beaten = true }),
                        )
                    ttSection(dialogue, npc, "Trying to attack a Mercenary", hooks)
                    if (beaten) {
                        repeat(MERC_HITS) { player.queueHit(delay = it, type = HitType.Melee, damage = random.of(1, 3)) }
                        delay(MERC_HITS)
                        dumpInDesert(collision)
                    }
                }
            }
        }
    }

    private fun captainKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != TtNpcs.captain.id) return
        val player = event.hero
        if (player.ttState != CAPTAIN_ANGRY) return
        player.setTtState(GATE_KEY)
        if (player.hasInInv(TtObjs.metal_key)) return
        val full = player.inv.freeSpace() == 0
        player.invAddOrDrop(objRepo, TtObjs.metal_key)
        player.mes(
            if (full) {
                "The mercenary captain drops a metal key on the floor, you try to add it to your inventory, but it won't fit in. You drop the key on the floor."
            } else {
                "The mercenary captain drops a metal key on the floor. You quickly grab the key and add it to your inventory."
            }
        )
    }

    /* Al Shabim and the Bedabin */

    private suspend fun ProtectedAccess.talkShabim(npc: Npc) {
        var extra: String? = null
        val options = HashMap<String, suspend () -> Unit>()
        options["Who are you?"] = { extra = "Who are you?" }
        options["What is this place?"] = { extra = "What is this place?" }
        when {
            state >= GUARD_FED -> {
                ttSection(shabimDialogue, npc, "After The Tourist Trap")
                return
            }
            state == PINEAPPLE && !ttOwns(TtObjs.tenti) -> {
                options["I am looking for a pineapple."] = { invAddOrDrop(objRepo, TtObjs.tenti) }
                ttSection(dialogue, npc, "Talking to Al Shabim after losing the pineapple", TtHooks(this, options = options))
            }
            state == PINEAPPLE ->
                ttSection(dialogue, npc, "Talking to Al Shabim with the pineapple in bank or inventory", TtHooks(this, options = options))
            state in PLANS..ANVIL_OK && carries(TtObjs.dart) -> handInDart(npc)
            state in PLANS..ANVIL_OK && carries(TtObjs.plans) -> showPlans(npc)
            state >= SHABIM_KEY -> {
                options["I've lost the key!"] = { if (!ttOwns(TtObjs.bedabin_key)) invAddOrDrop(objRepo, TtObjs.bedabin_key) }
                ttSection(dialogue, npc, "After losing the Bedabin key", TtHooks(this, options = options))
            }
            state >= PINEAPPLE_ASKED -> {
                val hooks =
                    TtHooks(
                        this,
                        options = options,
                        boxes =
                            mapOf(
                                "Al Shabim gives you a key." to {
                                    if (state == PINEAPPLE_ASKED) {
                                        invAddOrDrop(objRepo, TtObjs.bedabin_key)
                                        setState(SHABIM_KEY)
                                    }
                                    false
                                }
                            ),
                    )
                ttSection(dialogue, npc, "Talking to Al Shabim", hooks)
            }
            player.ttBits(ZABA_TOLD_SHIFT, 1) == 1 ->
                ttSection(dialogue, npc, "Talking to Al Shabim again after being told Al Zaba Bhasim doesn't exist", TtHooks(this, options = options))
            player.ttBits(ZABA_SHIFT, 1) == 1 -> {
                options["I am looking for Al Zaba Bhasim."] = { player.setTtBits(ZABA_TOLD_SHIFT, 1, 1) }
                ttSection(dialogue, npc, "Talking to Al Shabim after accepting to kill Al Zaba Bhasim", TtHooks(this, options = options))
            }
            else -> {
                ttSection(shabimDialogue, npc, "Before The Tourist Trap")
                return
            }
        }
        // The transcript's "quest=No" options point at Al Shabim's own transcript.
        extra?.let { runQuestBranch(npc, shabimDialogue, "Before The Tourist Trap", it, TtHooks(this), ttSpeakers) }
    }

    private suspend fun ProtectedAccess.showPlans(npc: Npc) {
        val bar = !carries(TtObjs.bronze_bar)
        val feathers = invTotal(inv, TtObjs.feather) < FEATHERS
        val hammer = !carries(TtObjs.hammer)
        fun missing(b: Boolean, f: Boolean, h: Boolean) = bar == b && feathers == f && hammer == h
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If the player is missing the bronze bar, feathers, and hammer:" to { missing(true, true, true) },
                        "If the player is missing the bronze bar and feathers:" to { missing(true, true, false) },
                        "If the player is missing the feathers and hammer:" to { missing(false, true, true) },
                        "If the player is missing the bronze bar and hammer:" to { missing(true, false, true) },
                        "If the player is missing the feathers:" to { missing(false, true, false) },
                        "If the player is missing the bronze bar:" to { missing(true, false, false) },
                        "If the player is missing the hammer:" to { missing(false, false, true) },
                    ),
                options = mapOf("Yes, I'm kind of curious." to { if (state == PLANS) setState(ANVIL_OK) }),
            )
        ttSection(dialogue, npc, "Talking to Al Shabim while holding the technical plans", hooks)
    }

    private suspend fun ProtectedAccess.handInDart(npc: Npc) {
        val hooks =
            TtHooks(
                this,
                boxes =
                    mapOf(
                        "You show Al Shabim the prototype dart." to {
                            invDel(inv, TtObjs.dart, invTotal(inv, TtObjs.dart))
                            false
                        },
                        "You hand over the technical plans for the weapon." to {
                            invDel(inv, TtObjs.plans, invTotal(inv, TtObjs.plans), strict = false)
                            false
                        },
                        "You receive six bronze throwing darts from Al Shabim." to {
                            invAddOrDrop(objRepo, TtObjs.bronze_dart, REWARD_DARTS)
                            false
                        },
                        "You receive a tasty looking pineapple from Al Shabim." to {
                            invDel(inv, TtObjs.bedabin_key, invTotal(inv, TtObjs.bedabin_key), strict = false)
                            invAddOrDrop(objRepo, TtObjs.tenti)
                            setState(PINEAPPLE)
                            false
                        },
                    ),
            )
        ttSection(dialogue, npc, "Talking to Al Shabim while holding the prototype dart", hooks)
    }

    private suspend fun ProtectedAccess.talkNomadGuard(npc: Npc) {
        if (state >= COMPLETE) {
            startDialogue(npc) {
                chatNpc(neutral, "Sorry, but you can't use the tent without permission. But thanks for all your help with the Bedabin people.")
            }
            return
        }
        ttSection(dialogue, npc, "Talking to Bedabin Nomad Guard without permission to enter tent")
    }

    private suspend fun ProtectedAccess.tentDoor(door: BoundLocInfo) {
        val inside = player.coords.z >= door.coords.z
        if (inside) {
            ttSection(dialogue, null, "Walk-through Tent door to leave the tent")
            passThroughWall(door)
            return
        }
        if (state in ANVIL_OK until PINEAPPLE && (carries(TtObjs.plans) || carries(TtObjs.dart_tip) || carries(TtObjs.dart))) {
            ttSection(dialogue, null, "Walk-through Tent door after getting permission")
            passThroughWall(door)
            return
        }
        ttSection(dialogue, null, "Walk-through Tent door without permission")
    }

    /* The prototype dart (the quest's own smithing and fletching steps) */

    private suspend fun ProtectedAccess.forgeDartTip() {
        if (state != ANVIL_OK || !carries(TtObjs.plans)) {
            ttSection(dialogue, null, "Using the experimental anvil")
            return
        }
        if (carries(TtObjs.dart_tip) || ttOwns(TtObjs.dart)) {
            ttSection(dialogue, null, "Trying to make another prototype dart while already having one")
            return
        }
        if (stat(stats.smithing) < SMITHING_LEVEL) {
            mesbox("You need a Smithing level of $SMITHING_LEVEL to follow these plans.") // Authored.
            return
        }
        if (!carries(TtObjs.hammer)) {
            mesbox("You need a hammer to work the metal with.") // Smithing's line.
            return
        }
        val success = random.randomDouble() < chance(stat(stats.smithing), SMITH_LOW, SMITH_HIGH)
        val wasted = !success && random.randomBoolean()
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If unsuccessful:" to { !success && !wasted },
                        "If very unsuccessful:" to { wasted },
                        "If successful:" to { success },
                    ),
                boxes =
                    mapOf(
                        "You begin experimenting in forging the weapon..." to {
                            anim(TtSeqs.smithing)
                            if (success || wasted) invDel(inv, TtObjs.bronze_bar)
                            if (success) invAdd(inv, TtObjs.dart_tip)
                            false
                        }
                    ),
            )
        ttSection(dialogue, null, "Using bronze bars on the experimental anvil", hooks)
    }

    private suspend fun ProtectedAccess.fletchDart() {
        if (ttOwns(TtObjs.dart)) {
            ttSection(dialogue, null, "Trying to make another prototype dart while already having one")
            return
        }
        if (stat(stats.fletching) < FLETCHING_LEVEL) {
            mesbox("You need a Fletching level of $FLETCHING_LEVEL to attach the feathers.") // Authored.
            return
        }
        if (invTotal(inv, TtObjs.feather) < FEATHERS) {
            mesbox("You need $FEATHERS feathers to complete the weapon.") // Authored.
            return
        }
        anim(TtSeqs.fletch_dart)
        if (random.randomDouble() >= chance(stat(stats.fletching), FLETCH_LOW, FLETCH_HIGH)) {
            startDialogue {
                mesbox("You try to attach feathers to the bronze dart tip.")
                // Authored (not Wiki): the failed attempt (Wiki: 24%-99% success by level).
                mesbox("The feathers come loose. You'll have to try again.")
            }
            return
        }
        invDel(inv, TtObjs.dart_tip)
        invDel(inv, TtObjs.feather, FEATHERS)
        invAdd(inv, TtObjs.dart)
        statAdvance(stats.fletching, DART_XP * xpMods.get(player, stats.fletching))
        ttSection(dialogue, null, "Using feathers on prototype dart tip")
    }

    /* Captain Siad */

    private suspend fun ProtectedAccess.talkSiad(npc: Npc) {
        if (state >= COMPLETE) {
            startDialogue(npc) {
                chatPlayer(neutral, "How is the camp holding up?")
                chatNpc(neutral, "The whole place is falling apart, to be honest. I'd jump at the chance to shut it all down for good.")
            }
            return
        }
        val distract: () -> Boolean = {
            val ok = random.of(SIAD_LUCK) == 0
            if (ok) distracted[player.uid.packed] = mapClock + DISTRACT_TICKS
            ok
        }
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If Captain Siad is successfully distracted:" to distract,
                        "If the captain is distracted:" to distract,
                    ),
                boxes =
                    mapOf(
                        "The Captain starts rambling on about his days as a salty sea dog. He looks quite distracted..." to {
                            distracted[player.uid.packed] = mapClock + DISTRACT_TICKS
                            false
                        }
                    ),
            )
        ttSection(dialogue, npc, "Talking to Captain Siad", hooks)
    }

    private fun ProtectedAccess.siadDistracted(): Boolean = (distracted[player.uid.packed] ?: 0) > mapClock

    private suspend fun ProtectedAccess.openChest() {
        val looking = !siadDistracted()
        val canTake = state in SHABIM_KEY..ANVIL_OK && !ttOwns(TtObjs.plans) && !ttOwns(TtObjs.dart)
        if (!looking && !canTake && carries(TtObjs.bedabin_key)) {
            mes("The chest is empty.") // Authored (not Wiki).
            return
        }
        val hooks =
            TtHooks(
                this,
                conditions =
                    mapOf(
                        "If Captain Siad is not distracted:" to { looking },
                        "If the player does not have the bedabin key:" to { !carries(TtObjs.bedabin_key) },
                        "If Captain Siad is distracted:" to { !looking },
                    ),
                boxes =
                    mapOf(
                        "While the Captain's distracted, you quickly unlock the chest with the Bedabins' copy of the key. You take out the plans." to {
                            invAddOrDrop(objRepo, TtObjs.plans)
                            if (state == SHABIM_KEY) setState(PLANS)
                            false
                        }
                    ),
            )
        ttSection(dialogue, null, "Opening chest by Captain Siad", hooks)
    }

    /** The ground-floor table gives the cell key; Siad's desks upstairs the camp's keys. */
    private suspend fun ProtectedAccess.searchTable(table: BoundLocInfo) {
        if (table.coords.level == 0) {
            if (ttOwns(TtObjs.cell_key)) {
                mes("...but you find nothing of interest.")
                return
            }
            invAddOrDrop(objRepo, TtObjs.cell_key)
            mesbox("You find a cell door key.")
            return
        }
        if (!siadDistracted()) {
            // Authored (adapted from the chest's line).
            mes("The captain spots you before you manage to search the desk...")
            return
        }
        mes("You search the captains desk while he's not looking...")
        val found = ArrayList<Pair<ObjType, String>>()
        if (!ttOwns(TtObjs.cell_key)) found += TtObjs.cell_key to "You find a cell door key."
        if (state >= GATE_KEY && !ttOwns(TtObjs.metal_key)) found += TtObjs.metal_key to "You find the key to the main gate."
        if (state >= COMPLETE && !ttOwns(TtObjs.wrought_key)) found += TtObjs.wrought_key to "You find the key to the main gate."
        if (found.isEmpty()) {
            mes("...but you find nothing of interest.")
            return
        }
        for ((obj, text) in found) {
            invAddOrDrop(objRepo, obj)
            mesbox(text)
        }
    }

    companion object {
        const val STARTED = 1
        const val CAPTAIN_ANGRY = 2
        const val GATE_KEY = 3
        const val SLAVE_CHAINS = 4
        const val SLAVE_UNLOCKED = 5
        const val ROBES = 6
        const val PINEAPPLE_ASKED = 7
        const val SHABIM_KEY = 8
        const val PLANS = 9
        const val ANVIL_OK = 10
        const val PINEAPPLE = 11
        const val GUARD_FED = 12
        const val ANA_BARREL = 13
        const val ANA_CART = 14
        const val ANA_CARRIED = 15
        const val ANA_LIFT = 16
        const val ANA_SURFACE = 17
        const val ANA_TOP = 18
        const val ANA_ON_CART = 19
        const val DRIVER_OK = 20
        const val ESCAPED = 21
        const val KEY_GIVEN = 22
        const val FIRST_SKILL = 25
        const val COMPLETE = 30

        const val SMITHING_LEVEL = 20
        const val FLETCHING_LEVEL = 10
        /** Wiki: 4,650 xp per pick (the dump has no stat xp column for this quest). */
        const val REWARD_XP = 4650.0
        private const val DART_XP = 10.0
        private const val FEATHERS = 10
        private const val REWARD_DARTS = 6

        /** Mod Ash: the dart tip succeeds 24%-96% and the feathers 24%-99%, by level (1-99). */
        private const val SMITH_LOW = 0.24
        private const val SMITH_HIGH = 0.96
        private const val FLETCH_LOW = 0.24
        private const val FLETCH_HIGH = 0.99

        private fun chance(level: Int, low: Double, high: Double): Double =
            low + (high - low) * (level.coerceIn(1, 99) - 1) / 98.0

        private const val CAPTAIN_WATCH_OP = 3
        private const val MERC_HITS = 4
        private const val BRIBE = 5
        private val BRIBES =
            listOf(
                "Perhaps five gold coins will make it my business?",
                "Perhaps these five gold coins will sweeten your mood?",
                "Perhaps five gold coins will help you remember?",
            )
        private val BETS = listOf(5, 10, 15, 20)
        /** The mercenary's sums ("You have won 1 / 2 / 4 / 10 gold pieces"). */
        private val BET_PAYOUTS = listOf(6, 12, 19, 30)
        private const val SIAD_LUCK = 4
        private const val DISTRACT_TICKS = 50

        const val ZABA_SHIFT = 0
        const val ZABA_TOLD_SHIFT = 1
        const val EVICT_SHIFT = 2
        const val EVICT_MASK = 3
        const val BET_SHIFT = 4
        const val BET_MASK = 7
        const val ANA_TALKED_SHIFT = 7

        val TOURIST_TRAP =
            QuestDef(
                name = "The Tourist Trap",
                varp = { TtVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "2 x 4,650 XP in skills of your choice",
                        "Dart tip smithing",
                        "Wrought iron key",
                        "Six bronze darts",
                    ),
                scrollObj = { TtObjs.wrought_key },
                listRow = "quest_touristtrap",
            )
    }
}
