package org.rsmod.content.quests.thefeud

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
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
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Feud (OSRS Wiki). Cache quest varbit `feud_var` (main_feud_var bits 0-5), end state 28 (every
 * Pollnivneach `_multi` npc switches to its `_postquest` child at 28; 1-27 are ours). 30 Thieving
 * to start; 1 QP, 15,000 Thieving xp, 500 coins. Sub-steps live in server varp `feud_progress`.
 * Stages:
 * - 1 Ali Morrisane (Al Kharid) sends you to find his nephew; 2-4 a beer each for Drunken Ali (use
 *   them on him); 4 ask a Menaphite Thug and a Bandit about the feud; 5 both blame a stolen camel;
 *   6 Ali the Camel Man sells two camel receipts for 500 coins; 7 both gangs refuse them.
 * - 8 Ali the Operator lets you join: pickpocket three villagers (8 any, 9 after paying the street
 *   urchin to distract one, 10 with the oak blackjack he gives you); 11 burglary: keys, desert
 *   disguise (fake beard + Kharidian headpiece) and gloves, hide behind the cactus (12 -> 13), the
 *   keys on the mayor's door (14), the landscape safe 1-1-2-3-5-8 upstairs (15 jewels), jewels to
 *   the Operator (16).
 * - 16 a thug names Traitorous Ali (17); the Operator orders his death (18); the barman points at
 *   his drink; Ali the Hag wants a snake (19: the snake charmer's money pot gives a charm and a
 *   basket; use the charm on a desert snake), then fresh camel dung (20: the kebab seller's red hot
 *   sauce on the camel trough, a bucket on the dung); her poison (21) goes in his drink (22).
 * - 23 the Menaphite Leader summons the Tough Guy (willow blackjack drop); 24 a villager sends you
 *   after the Bandit Leader (25), whose Bandit champion drops an adamant scimitar (26); 26 a villager
 *   sends you to the mayor (27), who tells where the nephew went; back to Ali Morrisane (28).
 *
 * Simplified: the knock-out is folded into the pickpocket (with the oak blackjack wielded, the third
 * villager's Pickpocket succeeds; no Lure/Knock-out ops, the spawned villagers are the plain
 * children); a failed pickpocket hits for 2 without the stun; the safe is six dial menus (the right
 * digit among four decoys; a wrong digit resets it); any glove counts; the camel always gives brown
 * dung; snake charming succeeds 3 times in 4. The villager Pickpocket is claimed (OpInterceptor)
 * only on stages 8-10, otherwise skilling's thieving.tsv handles it. Lines marked "Authored (not
 * Wiki)" fill gaps (the kebab seller and the snake charmer have no transcript).
 */
class TheFeud
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
    private val launcher: ProtectedAccessLauncher,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TheFeud::class.java, "the_feud") }

    private val villagerIds by lazy { FeudNpcs.villagers.mapTo(HashSet()) { it.id } }
    private val toughGuys = HashMap<Int, Npc>()
    private val champions = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, FeudNpcs.ali_m, THE_FEUD, wants = { state < COMPLETE }) { talkMorrisane(it) }
            default(npcTypes, FeudNpcs.ali_m) { greetMorrisane(it) }
            register(npcTypes, FeudNpcs.barman, THE_FEUD, wants = { wantsBarman() }) { talkBarman(it) }
            default(npcTypes, FeudNpcs.barman) { barmanDefault(it) }
        }

        // Multi bases have no op labels of their own: bind op1 (Talk-to) directly.
        onOpNpc1(FeudNpcs.drunken_ali) { talkDrunkenAli(it.npc) }
        onOpNpcU(FeudNpcs.drunken_ali, FeudObjs.beer) { beerOnDrunkenAli(it.npc) }
        onOpNpcU(FeudNpcs.drunken_ali) { talk(it.npc, "Using an item other than a beer on him") }
        onOpNpc1(FeudNpcs.camel_man) { talkCamelMan(it.npc) }
        onOpNpc1(FeudNpcs.operator) { talkOperator(it.npc) }
        onOpNpc1(FeudNpcs.mayor) { talkMayor(it.npc) }
        onOpNpc1(FeudNpcs.hag) { talkHag(it.npc) }
        onOpNpc1(FeudNpcs.snake_charmer) { talkSnakeCharmer(it.npc) }
        onOpNpc1(FeudNpcs.kebab_seller) { talkKebabSeller(it.npc) }
        onOpNpc1(FeudNpcs.menaphite_leader) { talkMenaphiteLeader(it.npc) }
        onOpNpc1(FeudNpcs.bandit_leader) { talkBanditLeader(it.npc) }

        for (thug in FeudNpcs.thugs) bindTalk(thug) { talkThug(it) }
        for (bandit in FeudNpcs.bandits) bindTalk(bandit) { talkBandit(it) }
        bindTalk(FeudNpcs.coward) { talkCoward(it) }
        bindTalk(FeudNpcs.urchin) { talkUrchin(it) }
        for (villager in FeudNpcs.villagers) bindTalk(villager) { talkVillager(it) }
        OpInterceptors.register(VillagerPickpocketClaim())

        // Burglary.
        onOpHeldU(FeudObjs.fake_beard, FeudObjs.headpiece) { makeDisguise() }
        bindLoc(FeudLocs.cactus, "Hide-behind") { hideBehindCactus() }
        for (door in FeudLocs.mayor_doors) {
            bindLoc(door, "Open") { mayorDoor(it) }
            onOpLocU(door, FeudObjs.keys) { keysOnDoor(it.loc) }
        }
        bindLoc(FeudLocs.stairs_base, "Climb-up") { telejump(MAYOR_UPSTAIRS) }
        bindLoc(FeudLocs.stairs_top, "Climb-down") { telejump(MAYOR_DOWNSTAIRS) }
        bindLoc(FeudLocs.desk, "Search") { searchDesk() }
        bindLoc(FeudLocs.bed, "Search") { searchBed() }
        bindLoc(FeudLocs.picture, "Search") { searchPicture() }
        onHeldOpLabel(objTypes, FeudObjs.nos_note, "Read") { readNumbersNote() }
        onHeldOpLabel(objTypes, FeudObjs.fib_note, "Read") { readFibonacciNote() }

        // Traitorous Ali.
        onOpLocU(FeudLocs.money_pot, FeudObjs.coins) { coinInPot() }
        for (snake in FeudNpcs.snakes) onOpNpcU(snake, FeudObjs.snake_charm) { charmSnake(it.npc) }
        for (trough in FeudLocs.troughs) onOpLocU(trough, FeudObjs.hot_sauce) { sauceOnTrough() }
        onOpLocU(FeudLocs.dung, FeudObjs.bucket) { collectDung(it.loc) }
        bindLoc(FeudLocs.dung, "Pick-up") {
            mes("You'd rather not pick that up with your bare hands.") // Authored (not Wiki).
        }
        onOpLocU(FeudLocs.beer_table, FeudObjs.poison) { poisonDrink() }
        onOpLocU(FeudLocs.beer_table, FeudObjs.hot_sauce) {
            startDialogue { chatPlayer(neutral, "You know what? This is childish. I'm not doing it.") }
        }
        onOpLocU(FeudLocs.beer_table) { itemOnDrink() }
        bindLoc(FeudLocs.beer_table, "Take-beer") {
            mes("That's Traitorous Ali's drink. You'd better leave it alone.") // Authored (not Wiki).
        }

        onEvent<NpcKilledEvent> { npcKilled(this) }
    }

    private fun ScriptContext.bindTalk(npc: NpcType, action: suspend ProtectedAccess.(Npc) -> Unit) {
        check(onNpcOpLabel(npcTypes, npc, "Talk-to", action)) { "The Feud: ${npc.internalName} has no Talk-to" }
    }

    private fun ScriptContext.bindLoc(
        loc: LocType,
        label: String,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        check(onLocOpLabel(locTypes, loc, label, action)) { "The Feud: ${loc.internalName} has no $label" }
    }

    /* Vars */

    private val ProtectedAccess.state: Int
        get() = player.questState(THE_FEUD)

    private fun ProtectedAccess.setStage(stage: Int) = player.setQuestState(THE_FEUD, stage)

    private fun Player.flag(bit: Int): Boolean = vars[FeudVarps.progress] and bit != 0

    private fun ProtectedAccess.flag(bit: Int): Boolean = player.flag(bit)

    private fun ProtectedAccess.setFlag(bit: Int, on: Boolean = true) {
        val now = player.vars[FeudVarps.progress]
        VarPlayerIntMapSetter.set(player, FeudVarps.progress, if (on) now or bit else now and bit.inv())
    }

    private fun Player.setBossVis(menaphite: Int? = null, bandit: Int? = null) {
        menaphite?.let { VarPlayerIntMapSetter.set(this, FeudVarBits.boss_vis, it) }
        bandit?.let { VarPlayerIntMapSetter.set(this, FeudVarBits.bandit_boss_vis, it) }
    }

    private fun ProtectedAccess.wears(obj: ObjType): Boolean = invTotal(worn, obj) > 0

    private fun ProtectedAccess.has(obj: ObjType): Boolean = carries(obj) || wears(obj)

    /* Dialogue */

    /**
     * Runs [section]. Action "initial" (the Wiki's "back to the first menu") restarts the section
     * from its first menu, skipping the greeting lines.
     */
    private suspend fun ProtectedAccess.talk(
        npc: Npc?,
        section: String,
        hooks: FeudHooks = FeudHooks(),
        speakers: Map<String, NpcType> = emptyMap(),
    ) {
        val nodes = dialogue.section(section)
        val conversation: suspend Dialogue.() -> Unit = {
            val runner = QuestDialogueRunner(hooks, speakers)
            var run = nodes
            while (true) {
                hooks.restart = false
                runner.run(this, run)
                if (!hooks.restart) break
                run = nodes.dropWhile { it is Node.Line || it is Node.Box }
                if (run.none { it is Node.Option }) break
            }
        }
        if (npc != null) startDialogue(npc, conversation = conversation) else startDialogue(conversation)
    }

    /* Ali Morrisane */

    private suspend fun ProtectedAccess.greetMorrisane(npc: Npc) {
        startDialogue(npc) {
            chatNpc(happy, "Good day and welcome back to Al Kharid.")
            chatNpc(happy, "Anyway, have a look at my wares.")
        }
    }

    private suspend fun ProtectedAccess.talkMorrisane(npc: Npc) {
        when {
            state == 0 -> offerQuest(npc)
            flag(PAID) -> finishQuest()
            flag(MAYOR_THANKED) -> {
                val hooks =
                    FeudHooks(
                        boxes =
                            mapOf(
                                "Ali Morrisane gives you 500 coins." to {
                                    if (!flag(PAID)) {
                                        invAddOrDrop(objRepo, FeudObjs.coins, REWARD_COINS)
                                        setFlag(PAID)
                                    }
                                    false
                                }
                            )
                    )
                talk(npc, "Talking to Ali Morrisane", hooks)
                if (flag(PAID)) finishQuest()
            }
            else -> talk(npc, "Talking to Ali Morrisane before any other progress")
        }
    }

    private suspend fun ProtectedAccess.offerQuest(npc: Npc) {
        if (statBase(stats.thieving) < THIEVING_LEVEL) {
            startDialogue(npc) {
                chatNpc(neutral, "Good day and welcome back to Al Kharid.")
                // Authored (not Wiki).
                chatNpc(neutral, "I could use a light-fingered helper, but you'll need a Thieving level of 30.")
            }
            return
        }
        val hooks =
            FeudHooks(
                conds = mapOf("If the player has a combat level less than 40:" to { player.combatLevel < RECOMMENDED_COMBAT }),
                options = mapOf("Yes." to { if (state == 0) setStage(STARTED) }),
            )
        talk(npc, "Getting started", hooks)
    }

    private fun ProtectedAccess.finishQuest() {
        statAdvance(stats.thieving, REWARD_XP * xpMods.get(player, stats.thieving))
        if (!has(FeudObjs.oak_blackjack)) invAddOrDrop(objRepo, FeudObjs.oak_blackjack)
        player.setBossVis(menaphite = 0, bandit = 0)
        VarPlayerIntMapSetter.set(player, FeudVarBits.mayor_multi, 1)
        completeQuest(THE_FEUD)
    }

    /* Drunken Ali */

    private suspend fun ProtectedAccess.talkDrunkenAli(npc: Npc) {
        val section =
            when (state) {
                0,
                STARTED -> "Initial dialogue"
                BEER1 -> "After the first beer"
                BEER2 -> "After the second beer"
                else -> "After the third beer"
            }
        talk(npc, section)
    }

    private suspend fun ProtectedAccess.beerOnDrunkenAli(npc: Npc) {
        val (section, next) =
            when (state) {
                STARTED -> "Using the first beer on him" to BEER1
                BEER1 -> "Using the second beer on him" to BEER2
                BEER2 -> "Using the third beer on him" to GANGS
                else -> {
                    talkDrunkenAli(npc)
                    return
                }
            }
        invDel(inv, FeudObjs.beer)
        setStage(next)
        talk(npc, section)
    }

    /* The gangs */

    private fun ProtectedAccess.gangsAsked(bit: Int) {
        setFlag(bit)
        if (state == GANGS && flag(THUG_ASKED) && flag(BANDIT_ASKED)) setStage(CAMELS)
    }

    private fun ProtectedAccess.receiptGiven(bit: Int) {
        setFlag(bit)
        if (state == RECEIPTS && flag(THUG_RECEIPT) && flag(BANDIT_RECEIPT)) setStage(JOIN)
    }

    private suspend fun ProtectedAccess.talkThug(npc: Npc) {
        when (state) {
            in 0 until GANGS,
            COMPLETE ->
                startDialogue(npc) { chatNpc(neutral, "What do you want? Move along.") } // Authored (not Wiki).
            GANGS,
            CAMELS -> {
                talk(npc, "Talking to a Menaphite Thug")
                gangsAsked(THUG_ASKED)
            }
            RECEIPTS ->
                if (flag(THUG_RECEIPT)) {
                    startDialogue(npc) { chatNpc(neutral, "Ten camels, or no peace.") } // Authored (not Wiki).
                } else {
                    var given = false
                    val hooks =
                        FeudHooks(
                            conds = mapOf("If the player does not have a receipt:" to { !carries(FeudObjs.receipt) }),
                            boxes =
                                mapOf(
                                    "You hand the doorman a sales receipt for the camel." to {
                                        invDel(inv, FeudObjs.receipt)
                                        given = true
                                        false
                                    }
                                ),
                        )
                    talk(npc, "Talking to a Menaphite Thug (2)", hooks)
                    if (given) receiptGiven(THUG_RECEIPT)
                }
            JOIN -> talk(npc, "Talking to a Menaphite Thug (3)")
            TRAITOR -> {
                talk(npc, "Talking to a Menaphite Thug (5)")
                if (state == TRAITOR) setStage(TRAITOR_FOUND)
            }
            MEET_LEADER ->
                if (flag(LEADER_TALKED)) {
                    talk(npc, "Talking to a Menaphite Thug (6)")
                    npc.opPlayer2(player, aiInteractions)
                } else {
                    talk(npc, "Talking to a Menaphite Thug (4)")
                }
            in TOUGH_DEAD until COMPLETE -> {
                talk(npc, "Talking to a Menaphite Thug (7)")
                npc.opPlayer2(player, aiInteractions)
            }
            else -> talk(npc, "Talking to a Menaphite Thug (4)")
        }
    }

    private suspend fun ProtectedAccess.talkBandit(npc: Npc) {
        when (state) {
            in 0 until GANGS,
            COMPLETE ->
                startDialogue(npc) { chatNpc(neutral, "What do you want? Clear off.") } // Authored (not Wiki).
            GANGS,
            CAMELS -> {
                talk(npc, "Asking the gangs")
                gangsAsked(BANDIT_ASKED)
            }
            RECEIPTS ->
                if (flag(BANDIT_RECEIPT)) {
                    startDialogue(npc) { chatNpc(neutral, "Ten full-price camels, or no peace.") } // Authored (not Wiki).
                } else {
                    var given = false
                    val hooks =
                        FeudHooks(
                            conds = mapOf("If the player does not have a receipt:" to { !carries(FeudObjs.receipt) }),
                            boxes =
                                mapOf(
                                    "You hand the guard a sales receipt for the camel." to {
                                        invDel(inv, FeudObjs.receipt)
                                        given = true
                                        false
                                    }
                                ),
                        )
                    talk(npc, "Talking to a Bandit", hooks)
                    if (given) receiptGiven(BANDIT_RECEIPT)
                }
            JOIN -> talk(npc, "Talking to a Bandit (2)")
            TOUGH_DEAD -> talk(npc, "Talking to a Bandit (4)")
            BANDITS -> talk(npc, "Talking to a Bandit (5)")
            CHAMPION_DEAD,
            MAYOR -> {
                talk(npc, "Talking to a Bandit (6)")
                npc.opPlayer2(player, aiInteractions)
            }
            else -> talk(npc, "Talking to a Bandit (3)")
        }
    }

    private suspend fun ProtectedAccess.talkCoward(npc: Npc) {
        when (state) {
            in JOINED until TOUGH_DEAD -> talk(npc, "Talking to the Cowardly Bandit")
            TOUGH_DEAD,
            BANDITS -> talk(npc, "Talking to the Cowardly Bandit (2)")
            CHAMPION_DEAD,
            MAYOR -> talk(npc, "Talking to the Cowardly Bandit (3)")
            else -> startDialogue(npc) { chatNpc(neutral, "Don't hurt me, please!") } // Authored (not Wiki).
        }
    }

    /* Ali the Camel Man */

    private suspend fun ProtectedAccess.talkCamelMan(npc: Npc) {
        when (state) {
            CAMELS -> buyCamels(npc)
            RECEIPTS -> {
                val given = (if (flag(THUG_RECEIPT)) 1 else 0) + (if (flag(BANDIT_RECEIPT)) 1 else 0)
                val missing = (RECEIPT_COUNT - given - invTotal(inv, FeudObjs.receipt)).coerceAtLeast(0)
                val hooks =
                    FeudHooks(
                        conds =
                            mapOf(
                                "If the player has both receipts:" to { missing == 0 },
                                "If the player doesn't have enough inventory space:" to { inv.freeSpace() < missing },
                            ),
                        actions =
                            mapOf(
                                "Ali the Camel Man gives the player any missing receipts." to {
                                    invAdd(inv, FeudObjs.receipt, missing)
                                }
                            ),
                    )
                talk(npc, "Talking to Ali the Camel Man again", hooks)
            }
            in JOIN until COMPLETE -> talk(npc, "Talking to Ali the Camel Man (3)")
            COMPLETE -> talk(npc, "Talking to Ali the Camel Man (4)")
            else -> talk(npc, "Talking to Ali the Camel Man")
        }
    }

    private suspend fun ProtectedAccess.buyCamels(npc: Npc) {
        var price = 0
        val sell: suspend () -> Boolean = {
            if (state == CAMELS && invTotal(inv, FeudObjs.coins) >= price) {
                invDel(inv, FeudObjs.coins, price)
                invAdd(inv, FeudObjs.receipt, RECEIPT_COUNT)
                setStage(RECEIPTS)
            }
            false
        }
        val hooks =
            FeudHooks(
                conds =
                    mapOf(
                        "Without two empty inventory spaces:" to { inv.freeSpace() < RECEIPT_COUNT },
                        "Without enough gold:" to { invTotal(inv, FeudObjs.coins) < price },
                    ),
                options =
                    mapOf(
                        "Would 500 gold coins for the pair of them do?" to { price = CAMEL_PRICE },
                        "Would 1000 gold coins for the pair of them do?" to { price = CAMEL_PRICE * 2 },
                    ),
                boxes =
                    mapOf(
                        "Ali the discount camel seller gives you a receipt for the camels in return for 500 gold coins." to sell,
                        "Ali the discount camel seller gives you a receipt for the camels in return for 1,000 gold coins." to sell,
                    ),
            )
        talk(npc, "Talking to Ali the Camel Man (2)", hooks)
    }

    /* Ali the Operator */

    private suspend fun ProtectedAccess.talkOperator(npc: Npc) {
        val keysBox: Map<String, suspend () -> Boolean> =
            mapOf(
                "Ali the Operator hands you a set of keys." to {
                    invAddOrDrop(objRepo, FeudObjs.keys)
                    if (state == PICK3) setStage(BURGLARY)
                    false
                }
            )
        when (state) {
            JOIN -> {
                val hooks =
                    FeudHooks(
                        options =
                            mapOf("Yes, of course, those bandits should be taught a lesson." to { if (state == JOIN) setStage(JOINED) })
                    )
                talk(npc, "Talking to Ali the Operator", hooks)
            }
            JOINED -> talk(npc, "Talking to Ali the Operator again")
            PICK1 -> talk(npc, "Talking to Ali the Operator (2)")
            PICK2 -> {
                val hooks =
                    FeudHooks(
                        conds =
                            mapOf(
                                "If the player has a full inventory:" to { inv.isFull() },
                                "If the player already has the blackjack:" to { has(FeudObjs.oak_blackjack) },
                            ),
                        boxes =
                            mapOf(
                                "Ali The Operator hands you a Blackjack made out of oak." to {
                                    invAdd(inv, FeudObjs.oak_blackjack)
                                    false
                                }
                            ),
                    )
                talk(npc, "Talking to Ali the Operator (3)", hooks)
            }
            PICK3 ->
                talk(
                    npc,
                    "Talking to Ali the Operator (4)",
                    FeudHooks(conds = mapOf("If the player has a full inventory:" to { inv.isFull() }), boxes = keysBox),
                )
            BURGLARY ->
                talk(
                    npc,
                    "Talking to Ali the Operator (5)",
                    FeudHooks(conds = mapOf("If the player has lost the keys:" to { !carries(FeudObjs.keys) }), boxes = keysBox),
                )
            STAKED ->
                talk(
                    npc,
                    "Talking to Ali the Operator (6)",
                    FeudHooks(conds = mapOf("If the player does not have the keys:" to { !carries(FeudObjs.keys) }), boxes = keysBox),
                )
            INSIDE -> talk(npc, "Speaking to Ali the Operator again")
            JEWELS -> {
                val hooks =
                    FeudHooks(
                        conds = mapOf("If the player does not have the jewels:" to { !carries(FeudObjs.jewels) }),
                        boxes =
                            mapOf(
                                "You hand Ali the Operator the jewels." to {
                                    invDel(inv, FeudObjs.jewels)
                                    setStage(TRAITOR)
                                    false
                                }
                            ),
                    )
                talk(npc, "Talking to Ali the Operator (7)", hooks)
            }
            TRAITOR -> talk(npc, "Talking to Ali the Operator (8)")
            TRAITOR_FOUND -> {
                talk(npc, "Talking to Ali the Operator again (2)")
                if (state == TRAITOR_FOUND) setStage(KILL_ORDER)
            }
            in KILL_ORDER until POISONED -> talk(npc, "Talking to Ali the Operator (9)")
            POISONED -> {
                talk(npc, "Talking to Ali the Operator (10)")
                if (state == POISONED) {
                    setStage(MEET_LEADER)
                    player.setBossVis(menaphite = 1)
                }
            }
            MEET_LEADER -> talk(npc, "Talking to Ali the Operator (11)")
            in TOUGH_DEAD..COMPLETE -> talk(npc, "Talking to Ali the Operator (12)")
            // Authored (not Wiki).
            else -> startDialogue(npc) { chatNpc(neutral, "I've no business with you. Not yet, anyway.") }
        }
    }

    /* Pickpocketing the villagers */

    /** The villager Pickpocket while the quest asks for one; generic thieving otherwise. */
    private inner class VillagerPickpocketClaim : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op3 &&
                (npc.id in villagerIds || npc.visType.id in villagerIds) &&
                player.questState(THE_FEUD) in JOINED..PICK2

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            pickVillager(npc)
        }
    }

    private suspend fun ProtectedAccess.pickVillager(npc: Npc) {
        when (state) {
            JOINED -> pickSucceeds(PICK1)
            PICK1 ->
                if (flag(DISTRACTED)) {
                    setFlag(DISTRACTED, on = false)
                    setFlag(URCHIN_OWED)
                    pickSucceeds(PICK2)
                } else {
                    pickFails(npc)
                }
            PICK2 ->
                if (wears(FeudObjs.oak_blackjack)) {
                    mes("You knock the villager out with your blackjack.") // Authored (not Wiki).
                    pickSucceeds(PICK3)
                } else {
                    pickFails(npc)
                }
        }
    }

    private suspend fun ProtectedAccess.pickSucceeds(next: Int) {
        mes("You attempt to pick the villager's pocket.") // Authored (not Wiki).
        anim(FeudSeqs.pickpocket)
        delay(PICK_TICKS)
        mes("You pick the villager's pocket.") // Authored (not Wiki).
        invAddOrDrop(objRepo, FeudObjs.coins, PICK_COINS)
        statAdvance(stats.thieving, PICK_XP * xpMods.get(player, stats.thieving))
        setStage(next)
    }

    private suspend fun ProtectedAccess.pickFails(npc: Npc) {
        val hooks =
            FeudHooks(
                actions =
                    mapOf(
                        "Villager hits the player for 2 damage and a stun." to {
                            player.queueHit(delay = 0, type = HitType.Typeless, damage = FAIL_DAMAGE)
                        }
                    )
            )
        talk(npc, "Trying to pickpocket a villager again", hooks)
    }

    private suspend fun ProtectedAccess.talkVillager(npc: Npc) {
        when (state) {
            TOUGH_DEAD,
            BANDITS -> {
                talk(npc, "Talking to a Villager (2)")
                if (state == TOUGH_DEAD) {
                    setStage(BANDITS)
                    player.setBossVis(bandit = 1)
                }
            }
            CHAMPION_DEAD,
            MAYOR -> {
                talk(npc, "Talking to a Villager (3)")
                if (state == CHAMPION_DEAD) setStage(MAYOR)
            }
            else -> {
                val option = if (random.of(2) == 0) "Dialogue 1" else "Dialogue 2"
                runQuestBranch(npc, dialogue, "Talking to a Villager", option)
            }
        }
    }

    /* Street urchin */

    private fun ProtectedAccess.villagerNearby(): Boolean =
        npcRepo.findAll(ZoneKey.from(player.coords), 1).any {
            it.id in villagerIds && it.coords.chebyshevDistance(player.coords) <= DISTRACT_RANGE
        }

    private suspend fun ProtectedAccess.talkUrchin(npc: Npc) {
        when {
            flag(URCHIN_OWED) -> {
                val hooks =
                    FeudHooks(
                        options =
                            mapOf(
                                "What money?" to {
                                    setFlag(URCHIN_OWED, on = false)
                                    setFlag(URCHIN_DENIED)
                                },
                                "Here you go, thanks." to {
                                    if (invTotal(inv, FeudObjs.coins) >= URCHIN_FEE) {
                                        invDel(inv, FeudObjs.coins, URCHIN_FEE)
                                        setFlag(URCHIN_OWED, on = false)
                                    }
                                },
                            )
                    )
                talk(npc, "First time after the distraction", hooks)
            }
            flag(URCHIN_DENIED) -> {
                val pays = invTotal(inv, FeudObjs.coins) >= URCHIN_FEE * 2
                val hooks =
                    FeudHooks(
                        conds =
                            mapOf(
                                "If the player has 20 coins" to { pays },
                                "If the player does not have 20 coins" to { !pays },
                            )
                    )
                talk(npc, "After denying them the money earlier", hooks)
                if (pays && invTotal(inv, FeudObjs.coins) >= URCHIN_FEE * 2) {
                    invDel(inv, FeudObjs.coins, URCHIN_FEE * 2)
                    setFlag(URCHIN_DENIED, on = false)
                }
            }
            state == PICK1 -> {
                val near = villagerNearby()
                val speakers = mapOf("Villager" to FeudNpcs.villagers.first())
                val hooks = FeudHooks(conds = mapOf("If a villager isn't in distance:" to { !near }))
                talk(npc, "Talking to a Street urchin (2)", hooks, speakers)
                if (near) setFlag(DISTRACTED)
            }
            state == PICK2 -> talk(npc, "After failing the distraction again")
            state in TOUGH_DEAD until COMPLETE -> talk(npc, "Talking to a Street urchin (4)")
            else -> talk(npc, "Talking to a Street urchin")
        }
    }

    /* The mayor's house */

    private fun ProtectedAccess.makeDisguise() {
        invDel(inv, FeudObjs.fake_beard)
        invReplace(inv, FeudObjs.headpiece, 1, FeudObjs.disguise)
        mes("You combine the fake beard and the headpiece into a desert disguise.") // Authored (not Wiki).
    }

    private fun ProtectedAccess.disguised(): Boolean =
        wears(FeudObjs.disguise) || wears(FeudObjs.karidian_disguise)

    private fun ProtectedAccess.gloved(): Boolean = player.worn[Wearpos.Hands.slot] != null

    private suspend fun ProtectedAccess.hideBehindCactus() {
        val onJob = state in BURGLARY..INSIDE
        val ready = onJob && gloved() && disguised()
        val hooks =
            FeudHooks(
                conds =
                    mapOf(
                        "Before being asked to rob the house or after robbing it:" to { !onJob },
                        "If the player is not wearing gloves:" to { !gloved() },
                        "If the player is not wearing a desert disguise:" to { !disguised() },
                    )
            )
        talk(null, "Hiding behind the Cactus", hooks)
        if (ready && state == BURGLARY) setStage(STAKED)
    }

    private suspend fun ProtectedAccess.mayorDoor(door: BoundLocInfo) {
        if (state >= INSIDE) {
            passThroughWall(door)
            return
        }
        mes("The door is locked.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.keysOnDoor(door: BoundLocInfo) {
        when {
            state >= INSIDE -> passThroughWall(door)
            state == STAKED -> {
                talk(null, "Using the keys on the Mayor's house door")
                setStage(INSIDE)
                passThroughWall(door)
            }
            // Authored (not Wiki).
            state == BURGLARY -> mes("I'd better make sure nobody's home before I go in.")
            else -> mes("Nothing interesting happens.")
        }
    }

    private suspend fun ProtectedAccess.searchNote(section: String, tag: String, note: ObjType) {
        if (has(note)) {
            mes("You find nothing else of interest.") // Authored (not Wiki).
            return
        }
        val hooks =
            FeudHooks(
                conds = mapOf("If the player has a full inventory:" to { inv.isFull() }),
                actions = mapOf(tag to { invAdd(inv, note) }),
            )
        talk(null, section, hooks)
    }

    private suspend fun ProtectedAccess.searchDesk() =
        searchNote("Searching the Mayor's Study desk", "Note (numbers) is added to the player's inventory.", FeudObjs.nos_note)

    private suspend fun ProtectedAccess.searchBed() =
        searchNote("Searching the Mayor's Bed", "Note (Fibonacci) is added to the player's inventory.", FeudObjs.fib_note)

    private suspend fun ProtectedAccess.readNumbersNote() {
        // Authored (not Wiki).
        startDialogue { mesbox("The note is covered in crossed-out sums. Whoever wrote it was no mathematician.") }
    }

    private suspend fun ProtectedAccess.readFibonacciNote() {
        // Authored (not Wiki).
        startDialogue { mesbox("1, 1, 2, 3, 5... Each number is the sum of the two before it.") }
    }

    private suspend fun ProtectedAccess.searchPicture() {
        val safeJob = state == INSIDE || (state == JEWELS && !carries(FeudObjs.jewels))
        if (!safeJob) {
            mes("It's a rather nondescript picture.") // Authored (not Wiki).
            return
        }
        var opened = false
        val hooks =
            FeudHooks(
                conds =
                    mapOf(
                        "Upon failing the combination:" to { !opened },
                        "Upon resetting the safe:" to { false },
                        "Upon unlocking the safe:" to { opened },
                        "If the player has a full inventory:" to { inv.isFull() },
                    ),
                safe = { crackSafe().also { opened = it } },
                boxes =
                    mapOf(
                        "You remove the jewels from the safe." to {
                            invAdd(inv, FeudObjs.jewels)
                            if (state == INSIDE) setStage(JEWELS)
                            false
                        }
                    ),
            )
        talk(null, "Searching the Mayor's Landscape", hooks)
    }

    /** Six turns of the dial (1-9): the right digit among four decoys; one wrong turn resets it. */
    private suspend fun Dialogue.crackSafe(): Boolean {
        for ((turn, digit) in SAFE_CODE.withIndex()) {
            val digits = listOf(0, 1, 3, 5, 7).map { (digit - 1 + it) % 9 + 1 }
            val shown = digits.drop(turn % 5) + digits.take(turn % 5)
            val labels = shown.map { "Turn the dial to $it" } // Authored (not Wiki).
            val picked =
                choice5(
                    labels[0],
                    shown[0],
                    labels[1],
                    shown[1],
                    labels[2],
                    shown[2],
                    labels[3],
                    shown[3],
                    labels[4],
                    shown[4],
                    title = "Safe dial: turn ${turn + 1} of ${SAFE_CODE.size}", // Authored (not Wiki).
                )
            if (picked != digit) return false
        }
        return true
    }

    /* Traitorous Ali */

    private fun ProtectedAccess.wantsBarman(): Boolean =
        state in KILL_ORDER until COMPLETE && !flag(MAYOR_THANKED)

    private suspend fun ProtectedAccess.talkBarman(npc: Npc) {
        val hooks = FeudHooks(options = mapOf("I'm looking for Traitorous Ali." to { setFlag(DRINK_FOUND) }))
        talk(npc, "Talking to Ali the Barman", hooks)
    }

    private suspend fun ProtectedAccess.barmanDefault(npc: Npc) {
        if (!(state == COMPLETE || flag(MAYOR_THANKED))) {
            talk(npc, "Talking to the Ali the Barman")
            return
        }
        if (flag(BARMAN_TOLD)) {
            talk(npc, "Talking to Ali the Barman again")
            return
        }
        val hooks =
            FeudHooks(
                options =
                    mapOf("Could you tell Traitorous Ali that someone has poisoned his drink?" to { setFlag(BARMAN_TOLD) }),
                boxes =
                    mapOf(
                        "Ali the barman gives you a beer." to {
                            invAddOrDrop(objRepo, FeudObjs.beer)
                            false
                        }
                    ),
            )
        talk(npc, "Talking to Ali the Barman (2)", hooks)
    }

    private suspend fun ProtectedAccess.talkHag(npc: Npc) {
        when (state) {
            KILL_ORDER -> {
                talk(npc, "Talking to Ali the Hag")
                if (state == KILL_ORDER) setStage(SNAKE)
            }
            SNAKE ->
                if (carries(FeudObjs.basket_full)) {
                    val hooks =
                        FeudHooks(
                            boxes =
                                mapOf(
                                    "You hand the basket with the charmed snake over to the old hag." to {
                                        invDel(inv, FeudObjs.basket_full)
                                        setStage(DUNG)
                                        false
                                    }
                                )
                        )
                    talk(npc, "Talking to Ali the Hag with a Snake basket full", hooks)
                } else {
                    talk(npc, "Talking to Ali the Hag again")
                }
            DUNG ->
                if (carries(FeudObjs.dung)) {
                    val hooks =
                        FeudHooks(
                            boxes =
                                mapOf(
                                    "You hand over your bucket of fresh camel dung and receive a vial of poison in return." to {
                                        invReplace(inv, FeudObjs.dung, 1, FeudObjs.poison)
                                        setStage(POISON)
                                        false
                                    }
                                )
                        )
                    talk(npc, "Talking to Ali the Hag with Ughthanki dung", hooks)
                } else {
                    talk(npc, "Talking to Ali the Hag again (2)")
                }
            POISON -> {
                val hooks =
                    FeudHooks(
                        conds =
                            mapOf("If the player has the Hag's poison in their inventory:" to { carries(FeudObjs.poison) }),
                        actions =
                            mapOf(
                                "(The player receives another Hag's poison.)" to {
                                    invAddOrDrop(objRepo, FeudObjs.poison)
                                }
                            ),
                    )
                talk(npc, "Talking to Ali the Hag (2)", hooks)
            }
            in POISONED..COMPLETE -> talk(npc, "Talking to Ali the Hag (3)")
            // Authored (not Wiki).
            else -> startDialogue(npc) { chatNpc(neutral, "Hexes and potions aren't free, Blockhead. Come back when you need one.") }
        }
    }

    private suspend fun ProtectedAccess.talkSnakeCharmer(npc: Npc) {
        startDialogue(npc) {
            // Authored (not Wiki).
            chatNpc(neutral, "Put a coin in my pot and I'll give you a snake charm and a basket. A fair trade!")
        }
    }

    private fun ProtectedAccess.coinInPot() {
        invDel(inv, FeudObjs.coins)
        mes("You put a coin in the snake charmer's money pot.") // Authored (not Wiki).
        if (!has(FeudObjs.snake_charm)) invAddOrDrop(objRepo, FeudObjs.snake_charm)
        if (!carries(FeudObjs.basket) && !carries(FeudObjs.basket_full)) invAddOrDrop(objRepo, FeudObjs.basket)
    }

    private suspend fun ProtectedAccess.charmSnake(snake: Npc) {
        if (!carries(FeudObjs.basket)) {
            mes("You need an empty snake basket to put the snake in.") // Authored (not Wiki).
            return
        }
        val charmed = random.of(1, CHARM_ODDS) != 1
        val hooks = FeudHooks(conds = mapOf("If unsuccessful:" to { !charmed }, "If successful:" to { charmed }))
        talk(null, "Charming a snake", hooks)
        if (!charmed) {
            snake.opPlayer2(player, aiInteractions)
            return
        }
        npcRepo.despawn(snake, SNAKE_RESPAWN_TICKS)
        invReplace(inv, FeudObjs.basket, 1, FeudObjs.basket_full)
    }

    private suspend fun ProtectedAccess.talkKebabSeller(npc: Npc) {
        if (state == DUNG && !carries(FeudObjs.hot_sauce) && !carries(FeudObjs.dung)) {
            startDialogue(npc) {
                chatPlayer(neutral, "Could I have some of your red hot sauce?") // Authored (not Wiki).
                chatNpc(happy, "My red hot sauce? Here you go - but go easy on it!") // Authored (not Wiki).
            }
            invAddOrDrop(objRepo, FeudObjs.hot_sauce)
            return
        }
        startDialogue(npc) { chatNpc(happy, "Kebabs! Get your hot kebabs here!") } // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.sauceOnTrough() {
        if (state < DUNG) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, FeudObjs.hot_sauce)
        mes("You pour the red hot sauce over the camels' food.") // Authored (not Wiki).
        delay(DUNG_DELAY)
        locRepo.add(DUNG_TILE, FeudLocs.dung, DUNG_TICKS, LocAngle.South, LocShape.CentrepieceStraight)
        mes("A camel eats the food... and a fresh pile of dung appears.") // Authored (not Wiki).
    }

    private fun ProtectedAccess.collectDung(dung: BoundLocInfo) {
        locRepo.del(dung, Int.MAX_VALUE)
        invReplace(inv, FeudObjs.bucket, 1, FeudObjs.dung)
        mes("You scoop the fresh camel dung into your bucket.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.poisonDrink() {
        when {
            state == POISON -> {
                invDel(inv, FeudObjs.poison)
                talk(null, "Using the Hag's poison on the table")
                setStage(POISONED)
            }
            state in POISONED until COMPLETE -> talk(null, "Using an item on the drink after poisoning it")
            else -> mes("Nothing interesting happens.")
        }
    }

    private suspend fun ProtectedAccess.itemOnDrink() {
        if (state in POISONED until COMPLETE) {
            talk(null, "Using an item on the drink after poisoning it")
            return
        }
        mes("Nothing interesting happens.")
    }

    /* The gang leaders */

    private fun ProtectedAccess.spawnFighter(
        fighters: HashMap<Int, Npc>,
        type: NpcType,
        at: CoordGrid,
    ) {
        val existing = fighters[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) {
            existing.opPlayer2(player, aiInteractions)
            return
        }
        val fighter = Npc(npcTypes[type], at)
        npcRepo.add(fighter, FIGHT_TICKS)
        fighters[player.uid.packed] = fighter
        fighter.opPlayer2(player, aiInteractions)
    }

    private val leaderSpeakers by lazy {
        mapOf(
            "Menaphite Leader" to FeudNpcs.menaphite_leader_vis,
            "Tough Guy" to FeudNpcs.tough_guy,
            "Bandit champion" to FeudNpcs.champion,
        )
    }

    private suspend fun ProtectedAccess.talkMenaphiteLeader(npc: Npc) {
        when {
            state == MEET_LEADER && !flag(LEADER_TALKED) -> {
                setFlag(LEADER_TALKED)
                val hooks =
                    FeudHooks(
                        conds =
                            mapOf(
                                "If the player has not completed Beneath Cursed Sands:" to { true },
                                "If the player has completed Beneath Cursed Sands:" to { false },
                            ),
                        actions =
                            mapOf(
                                "The Menaphite Leader summons the Tough Guy to attack the player." to {
                                    spawnFighter(toughGuys, FeudNpcs.tough_guy, npc.coords)
                                }
                            ),
                    )
                talk(npc, "Talking to the Menaphite Leader", hooks)
                spawnFighter(toughGuys, FeudNpcs.tough_guy, npc.coords)
            }
            state == MEET_LEADER -> {
                talk(npc, "Talking to the Menaphite Leader again", speakers = leaderSpeakers)
                spawnFighter(toughGuys, FeudNpcs.tough_guy, npc.coords)
            }
            state >= TOUGH_DEAD && !flag(LEADER_GONE) -> leaderFlees()
        }
    }

    private suspend fun ProtectedAccess.leaderFlees() {
        val hooks =
            FeudHooks(
                actions =
                    mapOf(
                        "Menaphite Leader teleports away." to {
                            setFlag(LEADER_GONE)
                            player.setBossVis(menaphite = 0)
                        }
                    )
            )
        talk(null, "After defeating the Tough Guy", hooks, leaderSpeakers)
    }

    private suspend fun ProtectedAccess.talkBanditLeader(npc: Npc) {
        when (state) {
            BANDITS ->
                if (!flag(CHAMP_CALLED)) {
                    setFlag(CHAMP_CALLED)
                    talk(npc, "Talking to the Bandit Leader", speakers = leaderSpeakers)
                    spawnFighter(champions, FeudNpcs.champion, npc.coords)
                } else {
                    spawnFighter(champions, FeudNpcs.champion, npc.coords)
                    talk(npc, "Talking to the Bandit Leader again", speakers = leaderSpeakers)
                }
            CHAMPION_DEAD ->
                if (!flag(BANDIT_DONE)) {
                    talk(npc, "Upon defeating the Bandit champion")
                    setFlag(BANDIT_DONE)
                } else {
                    talk(npc, "Talking to the Bandit Leader (2)")
                }
            in MAYOR..COMPLETE -> talk(npc, "Talking to the Bandit Leader (2)")
        }
    }

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        val at = event.npc.coords
        when (event.npc.type.id) {
            npcTypes[FeudNpcs.tough_guy].id -> {
                toughGuys.remove(player.uid.packed)
                objRepo.add(FeudObjs.willow_blackjack, at, DROP_TICKS, player)
                if (player.questState(THE_FEUD) != MEET_LEADER) return
                player.setQuestState(THE_FEUD, TOUGH_DEAD)
                launcher.launch(player) { leaderFlees() }
            }
            npcTypes[FeudNpcs.champion].id -> {
                champions.remove(player.uid.packed)
                objRepo.add(FeudObjs.adamant_scimitar, at, DROP_TICKS, player)
                if (player.questState(THE_FEUD) == BANDITS) player.setQuestState(THE_FEUD, CHAMPION_DEAD)
            }
        }
    }

    /* The mayor */

    private suspend fun ProtectedAccess.talkMayor(npc: Npc) {
        when (state) {
            in 0 until JOIN -> talk(npc, "Talking to Ali the Mayor")
            in JOIN until TRAITOR -> talk(npc, "Talking to Ali the Mayor (2)")
            in TRAITOR until TOUGH_DEAD -> talk(npc, "Talking to Ali the Mayor (3)")
            in TOUGH_DEAD until MAYOR -> talk(npc, "Talking to Ali the Mayor (4)")
            MAYOR ->
                if (flag(MAYOR_THANKED)) {
                    talk(npc, "Talking to Ali the Mayor (6)")
                } else {
                    talk(npc, "Talking to Ali the Mayor (5)")
                    setFlag(MAYOR_THANKED)
                }
            // Authored (not Wiki).
            else -> startDialogue(npc) { chatNpc(happy, "Thank you once again, adventurer. Pollnivneach is a better place.") }
        }
    }

    /** Hooks for this quest; conditions default to "Otherwise:" being the fallback branch. */
    private class FeudHooks(
        private val conds: Map<String, () -> Boolean> = emptyMap(),
        private val options: Map<String, suspend () -> Unit> = emptyMap(),
        private val boxes: Map<String, suspend () -> Boolean> = emptyMap(),
        private val actions: Map<String, suspend () -> Unit> = emptyMap(),
        private val safe: (suspend Dialogue.() -> Boolean)? = null,
    ) : QuestDialogueHooks {
        var restart = false

        override fun condition(text: String): Boolean = conds[text]?.invoke() ?: (text == OTHERWISE)

        override suspend fun Dialogue.onOption(label: String) {
            options[label]?.invoke()
        }

        override suspend fun Dialogue.onBox(text: String): Boolean = boxes[text]?.invoke() ?: false

        override suspend fun Dialogue.onAction(tag: String) {
            if (tag == INITIAL) {
                restart = true
                return
            }
            val crack = safe
            if (tag == SAFE_OPENS && crack != null) {
                crack(this)
                return
            }
            actions[tag]?.invoke()
        }
    }

    companion object {
        const val STARTED = 1
        private const val BEER1 = 2
        private const val BEER2 = 3
        private const val GANGS = 4
        private const val CAMELS = 5
        private const val RECEIPTS = 6
        private const val JOIN = 7
        private const val JOINED = 8
        private const val PICK1 = 9
        private const val PICK2 = 10
        private const val PICK3 = 11
        private const val BURGLARY = 12
        private const val STAKED = 13
        private const val INSIDE = 14
        private const val JEWELS = 15
        private const val TRAITOR = 16
        private const val TRAITOR_FOUND = 17
        private const val KILL_ORDER = 18
        private const val SNAKE = 19
        private const val DUNG = 20
        private const val POISON = 21
        private const val POISONED = 22
        private const val MEET_LEADER = 23
        private const val TOUGH_DEAD = 24
        private const val BANDITS = 25
        private const val CHAMPION_DEAD = 26
        private const val MAYOR = 27
        const val COMPLETE = 28

        /* feud_progress bits */
        private const val THUG_ASKED = 1
        private const val BANDIT_ASKED = 1 shl 1
        private const val THUG_RECEIPT = 1 shl 2
        private const val BANDIT_RECEIPT = 1 shl 3
        private const val DISTRACTED = 1 shl 4
        private const val URCHIN_OWED = 1 shl 5
        private const val URCHIN_DENIED = 1 shl 6
        private const val DRINK_FOUND = 1 shl 7
        private const val LEADER_TALKED = 1 shl 8
        private const val LEADER_GONE = 1 shl 9
        private const val CHAMP_CALLED = 1 shl 10
        private const val BANDIT_DONE = 1 shl 11
        private const val MAYOR_THANKED = 1 shl 12
        private const val PAID = 1 shl 13
        private const val BARMAN_TOLD = 1 shl 14

        private const val THIEVING_LEVEL = 30
        private const val RECOMMENDED_COMBAT = 40
        private const val REWARD_XP = 15_000.0
        private const val REWARD_COINS = 500
        private const val CAMEL_PRICE = 500
        private const val RECEIPT_COUNT = 2
        private const val URCHIN_FEE = 10
        private const val DISTRACT_RANGE = 10
        private const val PICK_TICKS = 2
        private const val PICK_COINS = 5
        private const val PICK_XP = 8.0
        private const val FAIL_DAMAGE = 2
        private const val CHARM_ODDS = 4
        private const val SNAKE_RESPAWN_TICKS = 50
        private const val DUNG_DELAY = 3
        private const val DUNG_TICKS = 200
        private const val FIGHT_TICKS = 500
        private const val DROP_TICKS = 200
        private const val OTHERWISE = "Otherwise:"
        private const val INITIAL = "initial"
        private const val SAFE_OPENS = "Safe interface opens."

        private val SAFE_CODE = listOf(1, 1, 2, 3, 5, 8)
        private val MAYOR_UPSTAIRS = CoordGrid(3374, 2978, 1)
        private val MAYOR_DOWNSTAIRS = CoordGrid(3374, 2978, 0)
        private val DUNG_TILE = CoordGrid(3341, 2962, 0)

        val THE_FEUD =
            QuestDef(
                name = "The Feud",
                varbit = { FeudVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("15,000 Thieving XP", "500 Coins", "Oak blackjack"),
                scrollObj = { FeudObjs.oak_blackjack },
                listRow = "quest_feud",
            )
    }
}

internal object FeudNpcs : NpcReferences() {
    val ali_m = find("feud_ali_m")
    val barman = find("feud_ali_the_barman")
    val drunken_ali = find("feud_drunken_ali_multi")
    val camel_man = find("feud_ali_the_discount_camel_seller_multi")
    val operator = find("feud_egyptian_minder_multi")
    val mayor = find("feud_mayor")
    val hag = find("feud_hag_multi")
    val snake_charmer = find("feud_snakecharmer_multi")
    val kebab_seller = find("feud_kebabman_multi")
    val menaphite_leader = find("feud_menap_boss")
    val menaphite_leader_vis = find("feud_menap_boss_vis")
    val bandit_leader = find("feud_bandit_boss")
    val tough_guy = find("feud_menap_toughguy")
    val champion = find("feud_bandit_toughguy")
    val urchin = find("feud_street_urchin")
    val coward = find("feud_arabian_guard_coward")
    val thugs = listOf(find("feud_egyptian_doorman_1"), find("feud_egyptian_doorman_2"))
    val bandits =
        listOf(
            find("feud_arabian_guard1_1"),
            find("feud_arabian_guard1_2"),
            find("feud_arabian_guard2_1"),
            find("feud_arabian_guard2_2"),
        )
    val villagers =
        listOf(
            find("feud_villager_1_1"),
            find("feud_villager_1_2"),
            find("feud_villager_1_3"),
            find("feud_villager_2_1"),
            find("feud_villager_2_2"),
            find("feud_villager_2_3"),
            find("feud_villager_3_1"),
            find("feud_villager_3_2"),
            find("feud_villager_3_3"),
        )
    val snakes = listOf(find("feud_desert_snake"), find("feud_desert_snake_outside"))
}

internal object FeudLocs : LocReferences() {
    val cactus = find("feud_cactus_row")
    val mayor_doors = listOf(find("feud_closed_door_left"), find("feud_closed_door_right"))
    val stairs_base = find("feud_insidestairs_base")
    val stairs_top = find("feud_insidestairs_top")
    val desk = find("feud_mayors_desk")
    val bed = find("feud_mayors_bed")
    val picture = find("feud_mayors_picture")
    val beer_table = find("feud_poison_beer_table")
    val troughs = listOf(find("feud_foodtrough"), find("feud_foodtrough2"))
    val dung = find("feud_ugthanki_pooh")
    val money_pot = find("feud_money_bowl")
}

internal object FeudObjs : ObjReferences() {
    val coins = find("coins")
    val beer = find("beer")
    val bucket = find("bucket_empty")
    val receipt = find("feud_camel_receipt")
    val fake_beard = find("feud_karidian_fakebeard")
    val headpiece = find("feud_karidian_turban")
    val disguise = find("feud_desert_disguise")
    val karidian_disguise = find("feud_karidian_fakebeard_and_hat")
    val keys = find("feud_mayors_house_keys")
    val jewels = find("feud_mayors_jewels")
    val nos_note = find("feud_nos_note")
    val fib_note = find("feud_fib_hint")
    val oak_blackjack = find("blackjack_oak")
    val willow_blackjack = find("blackjack_willow")
    val adamant_scimitar = find("adamant_scimitar")
    val snake_charm = find("snake_flute")
    val basket = find("basket_for_snake")
    val basket_full = find("basket_with_snake")
    val hot_sauce = find("superhot_kebab_sauce")
    val dung = find("feud_camel_pooh_bucket")
    val poison = find("poison_from_hag")
}

internal object FeudVarBits : VarBitReferences() {
    val quest = find("feud_var")
    val boss_vis = find("feud_boss_vis")
    val bandit_boss_vis = find("feud_bandit_boss_vis")
    val mayor_multi = find("feud_mayor_multivar")
}

internal object FeudVarps : VarpReferences() {
    /** Server varp (65804): the `feud_progress` bits in [TheFeud.Companion]. */
    val progress = find("feud_progress")
}

internal object FeudVarpBuilds : VarpBuilder() {
    init {
        build("feud_progress")
    }
}

internal object FeudSeqs : SeqReferences() {
    val pickpocket = find("human_pickpocket")
}
