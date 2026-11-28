package org.rsmod.content.quests.heroesquest

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.dragonslayer.DragonSlayer.Companion.DRAGON_SLAYER
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questPoints
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.lostcity.LostCity.Companion.LOST_CITY
import org.rsmod.content.quests.merlinscrystal.MerlinsCrystal.Companion.MERLINS_CRYSTAL
import org.rsmod.content.quests.shieldofarrav.ArravVarps
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.BLACKARM_JOINED
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.PHOENIX_JOINED
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.SHIELD_OF_ARRAV
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Heroes' Quest (OSRS Wiki). Cache quest varp `heroquest`, end state 15 (55 QP, Shield of Arrav,
 * Lost City, Merlin's Crystal and Dragon Slayer I to start; 53 Cooking and Fishing, 25 Herblore and
 * 50 Mining to finish). No cache multis key on the varp, so the stages are ours: 1 started at
 * Achietties outside the Heroes' Guild, 15 done. Progress is server varp `hq_progress` (bits).
 * - Thieves' armband, by Shield of Arrav gang:
 *   - Phoenix: Straven, Alfonse at the Shrimp and Parrot ("gherkin"), Charlie the cook, the
 *     kitchen's secret wall to Olbors' garden, Scarface Pete's side door (miscellaneous key) and
 *     the arrowslit room, where Grip can be shot. With Grip dead, Charlie hands over a candlestick
 *     and Straven gives the armband.
 *   - Black Arm: Katrine, Grubor ("four leaved clover"), Trobert's ID papers, Garv (full black
 *     armour + papers), Grip (papers, then the miscellaneous key), Grip's keyring, the treasure
 *     room door and the candlestick chest, then Katrine.
 * - Fire feather: kill the Entrana firebird; the feather can only be picked up wearing ice gloves
 *   (the Ice Queen drops them, beyond the rock slide on White Wolf Mountain, Mining 50).
 * - Lava eel: Gerrant's blamish snail slime + harralander potion (unf) = blamish oil, rubbed into
 *   a fishing rod; lava eels are fished by the fishing skill and cooked on any fire or range.
 * - All three to Achietties: 1 QP, the Wiki's xp, and the guild doors open.
 *
 * Simplified (solo play; the real quest needs a partner from the other gang): a Phoenix player
 * gets the miscellaneous key from Charlie after trying the side door, and Charlie's "inside man"
 * supplies the candlestick once the player has shot Grip; a Black Arm player may fight Grip
 * themselves after opening his drinks cabinet (Grip comes over alone). Grip can't be attacked
 * otherwise. Lines marked "Authored (not Wiki)" fill those gaps.
 */
class HeroesQuest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(HeroesQuest::class.java, "heroes_quest") }

    private val speakers by lazy {
        mapOf(
            "Achietties" to HqNpcs.achietties,
            "Alfonse the waiter" to HqNpcs.alfonse,
            "Garv" to HqNpcs.garv,
            "Grip" to HqNpcs.grip,
            "Grubor" to HqNpcs.grubor,
            "Pirate Guard" to HqNpcs.pirate_guard,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, HqNpcs.achietties, "Talk-to") { talkAchietties(it) }
        for (door in HqLocs.guild_doors) onLocOpLabel(locTypes, door, "Open") { guildDoor(it) }

        with(QuestTalk) {
            register(npcTypes, HqNpcs.straven, HEROES_QUEST, wants = { wantsStraven() }) { talkStraven(it) }
            register(npcTypes, HqNpcs.katrine, HEROES_QUEST, wants = { wantsKatrine() }) { talkKatrine(it) }
            default(npcTypes, HqNpcs.alfonse) { talkAlfonse(it) }
            default(npcTypes, HqNpcs.charlie) { talkCharlie(it) }
            default(npcTypes, HqNpcs.gerrant) { talkGerrant(it) }
        }

        // Phoenix route.
        onLocOpLabel(locTypes, HqLocs.kitchen_door, "Open") { kitchenDoor(it) }
        onLocOpLabel(locTypes, HqLocs.kitchen_panel, "Push") { kitchenPanel(it) }
        onLocOpLabel(locTypes, HqLocs.side_door, "Open") { sideDoor(it) }
        onOpLocU(HqLocs.side_door, HqObjs.misc_key) { sideDoor(it.loc) }

        // Black Arm route.
        onNpcOpLabel(npcTypes, HqNpcs.grubor, "Talk-to") { talkGrubor(it) }
        onLocOpLabel(locTypes, HqLocs.grubor_door, "Open") { gruborDoor(it) }
        onNpcOpLabel(npcTypes, HqNpcs.trobert, "Talk-to") { talkTrobert(it) }
        onNpcOpLabel(npcTypes, HqNpcs.garv, "Talk-to") { garv(it, null) }
        onLocOpLabel(locTypes, HqLocs.garv_door, "Open") { garv(null, it) }
        onNpcOpLabel(npcTypes, HqNpcs.grip, "Talk-to") { talkGrip(it) }
        onLocOpLabel(locTypes, HqLocs.grip_cabinet, "Open") { gripCabinet() }
        onLocOpLabel(locTypes, HqLocs.treasure_door, "Open") { treasureDoor(it) }
        onOpLocU(HqLocs.treasure_door, HqObjs.grip_keys) { treasureDoor(it.loc) }
        onLocOpLabel(locTypes, HqLocs.candle_chest, "Open") { candleChest() }
        OpInterceptors.register(GripAttackClaim())

        // Feather and gloves.
        onOpObj3(HqObjs.hot_feather) { takeFeather(it.obj) }
        onLocOpLabel(locTypes, HqLocs.rockslide, "Investigate") {
            runQuestSection(null, dialogue, "Investigating the rockslide on White Wolf Mountain")
        }
        onLocOpLabel(locTypes, HqLocs.rockslide, "Mine") { mineRockslide(it) }

        // Lava eel.
        onOpHeldU(HqObjs.blamish_slime, HqObjs.harralander_unf) { mixOil() }
        onOpHeldU(HqObjs.blamish_oil, HqObjs.fishing_rod) { oilRod() }
        onHeldOpLabel(objTypes, HqObjs.blamish_oil, "Drink") {
            runQuestSection(null, dialogue, "Attempting to drink blamish oil")
        }
        bindLavaEelCooking()

        onEvent<NpcKilledEvent> { npcKilled(this) }
    }

    /* Vars */

    private val ProtectedAccess.state: Int
        get() = player.questState(HEROES_QUEST)

    private val ProtectedAccess.started: Boolean
        get() = state == STARTED

    private fun Player.flag(bit: Int): Boolean = vars[HqVarps.progress] and bit != 0

    private fun ProtectedAccess.flag(bit: Int): Boolean = player.flag(bit)

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, HqVarps.progress, player.vars[HqVarps.progress] or bit)
    }

    private fun Player.isPhoenix(): Boolean = vars[ArravVarps.phoenixgang] >= PHOENIX_JOINED

    private fun Player.isBlackArm(): Boolean = vars[ArravVarps.blackarmgang] >= BLACKARM_JOINED

    private fun ProtectedAccess.wears(obj: ObjType): Boolean = invTotal(worn, obj) > 0

    private fun ProtectedAccess.hasArmband(): Boolean = carries(HqObjs.armband) || wears(HqObjs.armband)

    /* Achietties and the guild */

    private fun ProtectedAccess.canStart(): Boolean =
        player.questPoints >= START_QP &&
            listOf(SHIELD_OF_ARRAV, LOST_CITY, MERLINS_CRYSTAL, DRAGON_SLAYER).all { player.isQuestComplete(it) }

    private fun ProtectedAccess.skillsMet(): Boolean =
        stat(stats.cooking) >= COOKING_LEVEL &&
            stat(stats.fishing) >= FISHING_LEVEL &&
            stat(stats.herblore) >= HERBLORE_LEVEL &&
            stat(stats.mining) >= MINING_LEVEL

    private suspend fun ProtectedAccess.talkAchietties(npc: Npc?) {
        when {
            state == 0 -> offerQuest(npc)
            state >= COMPLETE ->
                if (npc != null) startDialogue(npc) { chatNpc(neutral, "Greetings. Welcome to the Heroes' Guild.") }
            carries(HqObjs.hot_feather) && hasArmband() && carries(HqObjs.lava_eel) -> handIn(npc)
            carries(HqObjs.hot_feather) && hasArmband() && carries(HqObjs.raw_lava_eel) ->
                runQuestSection(npc, dialogue, "Trying to hand in an uncooked lava eel", speakers = speakers)
            else ->
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to Achietties or trying to enter the guild again without the three items",
                    speakers = speakers,
                )
        }
    }

    private suspend fun ProtectedAccess.offerQuest(npc: Npc?) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player's skill levels are lower than the quest requirements." to { !skillsMet() },
                        "If the player doesn't have the quest requirements to start the quest:" to { !canStart() },
                    ),
                options = mapOf("Yes." to { if (canStart()) player.setQuestState(HEROES_QUEST, STARTED) }),
            )
        runQuestSection(npc, dialogue, "Talking to Achietties or trying to enter the guild", hooks, speakers)
    }

    private suspend fun ProtectedAccess.handIn(npc: Npc?) {
        runQuestSection(npc, dialogue, "Speaking to Achietties", speakers = speakers)
        if (!carries(HqObjs.hot_feather) || !carries(HqObjs.lava_eel)) return
        val armbandWorn = !carries(HqObjs.armband)
        if (armbandWorn && !wears(HqObjs.armband)) return
        invDel(inv, HqObjs.hot_feather)
        invDel(inv, HqObjs.lava_eel)
        if (armbandWorn) invDel(worn, HqObjs.armband) else invDel(inv, HqObjs.armband)
        for ((stat, xp) in REWARD_XP) statAdvance(stat, xp * xpMods.get(player, stat))
        completeQuest(HEROES_QUEST)
    }

    private suspend fun ProtectedAccess.guildDoor(door: BoundLocInfo) {
        if (state >= COMPLETE) {
            passThroughWall(door)
            return
        }
        talkAchietties(null)
    }

    /* Phoenix Gang */

    private fun ProtectedAccess.wantsStraven(): Boolean =
        player.isPhoenix() && (started || (state >= COMPLETE && !hasArmband()))

    private suspend fun ProtectedAccess.talkStraven(npc: Npc) {
        when {
            state >= COMPLETE -> {
                runQuestSection(npc, dialogue, "Talking to Straven after the quest without having a Master Thief armband")
                invAddOrDrop(objRepo, HqObjs.armband)
            }
            carries(HqObjs.candlestick) && !flag(GRIP_KILLED) ->
                runQuestSection(npc, dialogue, "Handing in a candlestick you didn't steal")
            carries(HqObjs.candlestick) -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "You hand Straven the candlestick." to {
                                    invDel(inv, HqObjs.candlestick)
                                    false
                                },
                                "Straven hands you a Master Thief armband." to {
                                    invAddOrDrop(objRepo, HqObjs.armband)
                                    setFlag(ARMBAND)
                                    false
                                },
                            )
                    )
                runQuestSection(npc, dialogue, "Handing in Pete's Candlestick", hooks)
            }
            flag(PHX_ASKED) -> runQuestSection(npc, dialogue, "Talking to Straven again")
            else -> {
                runQuestSection(npc, dialogue, "Talking to Straven")
                setFlag(PHX_ASKED)
            }
        }
    }

    private fun ProtectedAccess.phoenixStep(): Boolean = started && player.isPhoenix() && !flag(ARMBAND)

    private suspend fun ProtectedAccess.talkAlfonse(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Do you sell Gherkins?" to { if (phoenixStep() && flag(PHX_ASKED)) setFlag(PHX_ALFONSE) })
            )
        runQuestSection(npc, dialogue, "Talking to Alfonse", hooks)
    }

    private suspend fun ProtectedAccess.kitchenDoor(door: BoundLocInfo) {
        if (flag(PHX_ALFONSE) || state >= COMPLETE) {
            passThroughWall(door)
            return
        }
        runQuestSection(null, dialogue, "Trying to go into the kitchen early", speakers = speakers)
    }

    private suspend fun ProtectedAccess.talkCharlie(npc: Npc) {
        if (phoenixStep() && flag(GRIP_KILLED) && !carries(HqObjs.candlestick)) {
            startDialogue(npc) {
                chatNpc(happy, "Grip's dead! Our man inside grabbed Pete's candlesticks in the confusion.") // Authored (not Wiki).
                chatNpc(happy, "Here, this one's yours, compadre. Take it to Straven.") // Authored (not Wiki).
            }
            invAddOrDrop(objRepo, HqObjs.candlestick)
            return
        }
        if (phoenixStep() && flag(SIDE_TRIED) && !carries(HqObjs.misc_key)) {
            startDialogue(npc) {
                chatNpc(neutral, "That side door's locked, eh? One of our lads lifted this key off a pirate guard.") // Authored (not Wiki).
                chatNpc(neutral, "Nobody knows what it opens. See if it fits.") // Authored (not Wiki).
            }
            invAddOrDrop(objRepo, HqObjs.misc_key)
            setFlag(MISC_KEY)
            return
        }
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "I want to steal Scarface Pete's candlesticks." to {
                            if (phoenixStep() && flag(PHX_ALFONSE)) setFlag(PHX_CHARLIE)
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Charlie", hooks)
    }

    private suspend fun ProtectedAccess.kitchenPanel(panel: BoundLocInfo) {
        if (flag(PHX_CHARLIE) || state >= COMPLETE) {
            passThroughWall(panel)
            return
        }
        mes("You push the wall, but nothing happens.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.sideDoor(door: BoundLocInfo) {
        if (carries(HqObjs.misc_key)) {
            passThroughWall(door)
            if (phoenixStep()) setFlag(SIDE_IN)
            return
        }
        runQuestSection(null, dialogue, "Attempting to enter Scarface Pete's mansion without using the key")
        if (phoenixStep()) setFlag(SIDE_TRIED)
    }

    /* Black Arm Gang */

    private fun ProtectedAccess.wantsKatrine(): Boolean =
        player.isBlackArm() && (started || (state >= COMPLETE && !hasArmband()))

    private fun ProtectedAccess.blackArmStep(): Boolean = started && player.isBlackArm() && !flag(ARMBAND)

    private suspend fun ProtectedAccess.talkKatrine(npc: Npc) {
        when {
            state >= COMPLETE -> {
                startDialogue(npc) {
                    chatPlayer(sad, "I'm afraid I've lost my Master Thief armband.") // Authored (not Wiki).
                    chatNpc(neutral, "Lucky for you I have a spare. Don't lose it again!") // Authored (not Wiki).
                }
                invAddOrDrop(objRepo, HqObjs.armband)
            }
            carries(HqObjs.candlestick) && !flag(CANDLE) ->
                runQuestSection(npc, dialogue, "Attempting to hand in Pete's Candlestick if you've obtained it too early")
            carries(HqObjs.candlestick) -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "Katrine gives you a master thief armband." to {
                                    invDel(inv, HqObjs.candlestick)
                                    invAddOrDrop(objRepo, HqObjs.armband)
                                    setFlag(ARMBAND)
                                    false
                                }
                            )
                    )
                runQuestSection(npc, dialogue, "Handing in Pete's Candlestick (2)", hooks)
            }
            else -> {
                val hooks =
                    SimpleQuestHooks(
                        options = mapOf("Is there any way I can get the rank of master thief?" to { setFlag(BA_ASKED) })
                    )
                runQuestSection(npc, dialogue, "Katrine's request", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.talkGrubor(npc: Npc?) {
        when {
            flag(BA_GRUBOR) -> runQuestSection(npc, dialogue, "Talking to Grubor again", speakers = speakers)
            blackArmStep() && flag(BA_ASKED) -> {
                val hooks = SimpleQuestHooks(options = mapOf("Four leaved clover." to { setFlag(BA_GRUBOR) }))
                runQuestSection(npc, dialogue, "Talking to Grubor", hooks, speakers)
            }
            else -> runQuestSection(npc, dialogue, "Trying to enter Grubor's house", speakers = speakers)
        }
    }

    private suspend fun ProtectedAccess.gruborDoor(door: BoundLocInfo) {
        if (flag(BA_GRUBOR) || state >= COMPLETE) {
            passThroughWall(door)
            return
        }
        talkGrubor(null)
        if (flag(BA_GRUBOR)) passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkTrobert(npc: Npc) {
        if (!flag(BA_PAPERS)) {
            val hooks =
                SimpleQuestHooks(
                    options =
                        mapOf(
                            "I volunteer to undertake that mission!" to {
                                if (blackArmStep()) {
                                    invAddOrDrop(objRepo, HqObjs.id_papers)
                                    setFlag(BA_PAPERS)
                                }
                            }
                        )
                )
            runQuestSection(npc, dialogue, "Trobert's plan", hooks)
            return
        }
        val has = carries(HqObjs.id_papers) || flag(BA_GRIP)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the ID papers:" to { has },
                        "If the player has lost the ID papers:" to { !has && !inv.isFull() },
                        "If the player has lost the ID papers and has a full inventory:" to { !has && inv.isFull() },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Trobert again", hooks)
        if (!has) invAddOrDrop(objRepo, HqObjs.id_papers)
    }

    private fun ProtectedAccess.wearsBlack(): Boolean =
        wears(HqObjs.black_full_helm) &&
            wears(HqObjs.black_platebody) &&
            (wears(HqObjs.black_platelegs) || wears(HqObjs.black_plateskirt))

    private suspend fun ProtectedAccess.garv(npc: Npc?, door: BoundLocInfo?) {
        if (door != null && (flag(BA_GARV) || state >= COMPLETE)) {
            passThroughWall(door)
            return
        }
        if (!blackArmStep() || !flag(BA_PAPERS) || flag(BA_GARV)) {
            val section =
                if (door != null) "Attempting to enter Scarface Pete's mansion from the front" else "Talking to Garv"
            runQuestSection(npc, dialogue, section, speakers = speakers)
            return
        }
        val black = wearsBlack()
        val papers = carries(HqObjs.id_papers)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Trying to enter the mansion rather than talking to Garv" to { door != null },
                        "Talking to Garv" to { door == null },
                        "If the player is not wearing black armour:" to { !black },
                        "If the player is wearing black armour:" to { black },
                        "If the player has the ID papers" to { papers },
                        "If the player do not have the ID papers" to { !papers },
                    )
            )
        runQuestSection(npc, dialogue, "Infiltration", hooks, speakers)
        if (black && papers) {
            setFlag(BA_GARV)
            if (door != null) passThroughWall(door)
        }
    }

    private suspend fun ProtectedAccess.talkGrip(npc: Npc) {
        if (!blackArmStep() || !flag(BA_GARV)) {
            startDialogue(npc) { chatNpc(neutral, "Shouldn't you be somewhere else? Move along.") } // Authored (not Wiki).
            return
        }
        val papers = carries(HqObjs.id_papers) || flag(BA_GRIP)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the ID papers" to { papers },
                        "If the player lost the ID papers" to { !papers },
                        "If the player does not have a miscellaneous key yet:" to { !carries(HqObjs.misc_key) },
                        "If the player has a miscellaneous key already:" to { carries(HqObjs.misc_key) },
                    ),
                boxes =
                    mapOf(
                        "You hand the ID papers over to Grip." to {
                            if (carries(HqObjs.id_papers)) invDel(inv, HqObjs.id_papers)
                            setFlag(BA_GRIP)
                            false
                        },
                        "Grip hands you a key." to {
                            invAddOrDrop(objRepo, HqObjs.misc_key)
                            setFlag(MISC_KEY)
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Reporting in", hooks)
    }

    private suspend fun ProtectedAccess.gripCabinet() {
        if (blackArmStep() && flag(MISC_KEY) && !flag(CABINET)) {
            val hooks =
                SimpleQuestHooks(
                    conditions = mapOf("If there's no one to stop you:" to { false }),
                    options = mapOf("He won't notice me having a quick look." to { setFlag(CABINET) }),
                )
            runQuestSection(null, dialogue, "When searching through his drinks cabinet", hooks, speakers)
            if (flag(CABINET)) {
                mes("Grip storms over to his cabinet, well away from the other guards.") // Authored (not Wiki).
                mes("Nobody else is watching him now...") // Authored (not Wiki).
            }
            return
        }
        if (carries(HqObjs.whisky)) {
            mes("There's nothing else of interest in the cupboard.") // Authored (not Wiki).
            return
        }
        mes("You find a bottle of whiskey in the cupboard.")
        invAddOrDrop(objRepo, HqObjs.whisky)
    }

    private suspend fun ProtectedAccess.treasureDoor(door: BoundLocInfo) {
        if (!carries(HqObjs.grip_keys)) {
            mes("This door is locked.") // Authored (not Wiki).
            return
        }
        if (!flag(MISC_KEY)) {
            mes("None of the keys on Grip's keyring seem to fit this door.") // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.candleChest() {
        if (!started || flag(ARMBAND) || carries(HqObjs.candlestick)) {
            mes("The chest is empty.") // Authored (not Wiki).
            return
        }
        // Solo play: one candlestick, not one for you and one for your partner.
        val hooks =
            SimpleQuestHooks(
                rewrites = { if (it.startsWith("You find two candlesticks")) SOLO_CHEST else it }
            )
        runQuestSection(null, dialogue, "Searching the chest with the candlesticks", hooks)
        invAddOrDrop(objRepo, HqObjs.candlestick)
        setFlag(CANDLE)
    }

    /** Grip's Attack: refused unless this player's route has a safe moment to fight him. */
    private inner class GripAttackClaim : OpInterceptors.NpcOp {
        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op2 && npc.type.id == npcTypes[HqNpcs.grip].id && !player.mayAttackGrip()

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            runQuestSection(null, dialogue, "When trying to attack Grip")
        }
    }

    private fun Player.mayAttackGrip(): Boolean {
        val state = questState(HEROES_QUEST)
        if (state >= COMPLETE) return true
        if (state != STARTED || flag(ARMBAND)) return false
        return (isPhoenix() && flag(SIDE_IN)) || (isBlackArm() && flag(MISC_KEY) && flag(CABINET))
    }

    /* Fire feather and ice gloves */

    private suspend fun ProtectedAccess.takeFeather(obj: Obj) {
        if (!wears(HqObjs.ice_gloves)) {
            runQuestSection(null, dialogue, "Attempting to pick up a fire feather without ice gloves")
            return
        }
        if (inv.isFull() || !objRepo.del(obj)) return
        invAdd(inv, HqObjs.hot_feather)
    }

    private suspend fun ProtectedAccess.mineRockslide(rocks: BoundLocInfo) {
        if (rocks.coords != WHITE_WOLF_ROCKSLIDE) {
            mes("These rocks contain nothing interesting.") // Authored (not Wiki).
            return
        }
        if (stat(stats.mining) < MINING_LEVEL) {
            mes("You need a Mining level of $MINING_LEVEL to clear the rock slide.") // Authored (not Wiki).
            return
        }
        if (HqObjs.pickaxes.none { carries(it) || wears(it) }) {
            mes("You need a pickaxe to clear the rock slide.") // Authored (not Wiki).
            return
        }
        mes("You clear a path through the rock slide.") // Authored (not Wiki).
        delay(2)
        val at = rocks.coords
        val dx = player.coords.x - at.x
        val dz = player.coords.z - at.z
        val dest =
            if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
                at.translate(if (dx > 0) -1 else 1, 0)
            } else {
                at.translate(0, if (dz > 0) -1 else 1)
            }
        telejump(dest)
    }

    /* Lava eel */

    private fun ProtectedAccess.mixOil() {
        if (stat(stats.herblore) < HERBLORE_LEVEL) {
            mes("You need a Herblore level of $HERBLORE_LEVEL to make blamish oil.") // Authored (not Wiki).
            return
        }
        invDel(inv, HqObjs.blamish_slime)
        invReplace(inv, HqObjs.harralander_unf, 1, HqObjs.blamish_oil)
        statAdvance(stats.herblore, OIL_XP * xpMods.get(player, stats.herblore))
        mes("You mix the slime into your potion.")
    }

    private fun ProtectedAccess.oilRod() {
        invDel(inv, HqObjs.blamish_oil)
        invReplace(inv, HqObjs.fishing_rod, 1, HqObjs.oily_rod)
        mes("You rub the oil into the fishing rod.")
    }

    private suspend fun ProtectedAccess.talkGerrant(npc: Npc) {
        val asked =
            carries(HqObjs.blamish_slime) || carries(HqObjs.blamish_oil) || carries(HqObjs.oily_rod)
        if (asked) {
            runQuestSection(npc, dialogue, "Talking to Gerrant again with blamish snail slime, blamish oil, or an oily fishing rod")
            return
        }
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "Gerrant searches around a bit." to {
                            invAddOrDrop(objRepo, HqObjs.blamish_slime)
                            false
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Gerrant", hooks)
    }

    /** Raw lava eels cook on any fire or range (Wiki: level 53, 30 xp, never burns). */
    private fun ScriptContext.bindLavaEelCooking() {
        for (type in locTypes.values) {
            val cookOp = type.op.any { it?.trim().equals("Cook", ignoreCase = true) }
            if (!cookOp && type.name !in HEAT_NAMES) continue
            onOpLocU(type, HqObjs.raw_lava_eel) { cookEels() }
        }
    }

    private suspend fun ProtectedAccess.cookEels() {
        if (stat(stats.cooking) < COOKING_LEVEL) {
            mes("You need a Cooking level of $COOKING_LEVEL to cook a lava eel.") // Authored (not Wiki).
            return
        }
        while (carries(HqObjs.raw_lava_eel)) {
            anim(HqSeqs.cook)
            delay(COOK_TICKS)
            invReplace(inv, HqObjs.raw_lava_eel, 1, HqObjs.lava_eel)
            statAdvance(stats.cooking, COOK_XP * xpMods.get(player, stats.cooking))
            mes("You successfully cook a lava eel.")
        }
    }

    /* Kills */

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        val at = event.npc.coords
        when (event.npc.type.id) {
            npcTypes[HqNpcs.ice_queen].id -> objRepo.add(HqObjs.ice_gloves, at, DROP_TICKS, player)
            npcTypes[HqNpcs.fire_bird].id ->
                if (player.questState(HEROES_QUEST) == STARTED && player.inv.count(objTypes[HqObjs.hot_feather]) == 0) {
                    objRepo.add(HqObjs.hot_feather, at, DROP_TICKS, player)
                }
            npcTypes[HqNpcs.grip].id ->
                if (player.questState(HEROES_QUEST) == STARTED) {
                    VarPlayerIntMapSetter.set(player, HqVarps.progress, player.vars[HqVarps.progress] or GRIP_KILLED)
                    objRepo.add(HqObjs.grip_keys, at, DROP_TICKS, player)
                }
        }
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 15

        /* hq_progress bits */
        private const val PHX_ASKED = 1
        private const val PHX_ALFONSE = 1 shl 1
        private const val PHX_CHARLIE = 1 shl 2
        private const val SIDE_TRIED = 1 shl 3
        private const val SIDE_IN = 1 shl 4
        private const val BA_ASKED = 1 shl 5
        private const val BA_GRUBOR = 1 shl 6
        private const val BA_PAPERS = 1 shl 7
        private const val BA_GARV = 1 shl 8
        private const val BA_GRIP = 1 shl 9
        private const val MISC_KEY = 1 shl 10
        private const val CABINET = 1 shl 11
        private const val GRIP_KILLED = 1 shl 12
        private const val CANDLE = 1 shl 13
        private const val ARMBAND = 1 shl 14

        private const val START_QP = 55
        private const val COOKING_LEVEL = 53
        private const val FISHING_LEVEL = 53
        private const val HERBLORE_LEVEL = 25
        private const val MINING_LEVEL = 50
        private const val OIL_XP = 80.0
        private const val COOK_XP = 30.0
        private const val COOK_TICKS = 4
        private const val DROP_TICKS = 200

        private val WHITE_WOLF_ROCKSLIDE = CoordGrid(2838, 3517, 0)
        private val HEAT_NAMES = setOf("Fire", "Range", "Cooking range")
        private const val SOLO_CHEST = "You find a candlestick in the chest." // Authored (not Wiki).

        private val REWARD_XP =
            listOf(
                stats.attack to 3_075.0,
                stats.defence to 3_075.0,
                stats.strength to 3_075.0,
                stats.hitpoints to 3_075.0,
                stats.ranged to 2_075.0,
                stats.fishing to 2_725.0,
                stats.cooking to 2_825.0,
                stats.woodcutting to 1_575.0,
                stats.firemaking to 1_575.0,
                stats.smithing to 2_275.0,
                stats.mining to 2_575.0,
                stats.herblore to 1_325.0,
            )

        val HEROES_QUEST =
            QuestDef(
                name = "Heroes' Quest",
                varp = { HqVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "3,075 Attack, Defence, Strength and Hitpoints XP",
                        "2,075 Ranged, 2,725 Fishing, 2,825 Cooking XP",
                        "1,575 Woodcutting and Firemaking XP",
                        "2,275 Smithing, 2,575 Mining, 1,325 Herblore XP",
                        "Access to the Heroes' Guild",
                    ),
                scrollObj = { HqObjs.hot_feather },
                listRow = "quest_heroes",
            )
    }
}

internal object HqNpcs : NpcReferences() {
    val achietties = find("achietties")
    val straven = find("straven")
    val katrine = find("katrine")
    val alfonse = find("alfonse_the_waiter")
    val charlie = find("charlie_the_cook")
    val grubor = find("grubor")
    val trobert = find("trobert")
    val garv = find("garv")
    val grip = find("grip")
    val pirate_guard = find("pirate_guard")
    val gerrant = find("gerrant")
    val fire_bird = find("fire_bird")
    val ice_queen = find("ice_queen")
}

internal object HqLocs : LocReferences() {
    val guild_doors = listOf(find("herodoor_l"), find("herodoor_r"))
    val kitchen_door = find("herokitchendoor")
    val kitchen_panel = find("herokitchenpanel")
    val side_door = find("pete_sidedoor")
    val grubor_door = find("grubordoor")
    val garv_door = find("garvdoor")
    val grip_cabinet = find("gripcbshut")
    val treasure_door = find("pete_treasuredoor")
    val candle_chest = find("shutcandlechest")
    val rockslide = find("herorockslide")
}

internal object HqObjs : ObjReferences() {
    val id_papers = find("id_papers")
    val misc_key = find("misc_key")
    val grip_keys = find("grip_keys")
    val candlestick = find("petecandlestick")
    val armband = find("master_thief_armband")
    val whisky = find("whisky")
    val hot_feather = find("hot_feather")
    val ice_gloves = find("ice_gloves")
    val blamish_slime = find("blamish_snail_slime")
    val blamish_oil = find("blamish_oil")
    val harralander_unf = find("harralandervial")
    val fishing_rod = find("fishing_rod")
    val oily_rod = find("oily_fishing_rod")
    val raw_lava_eel = find("raw_lava_eel")
    val lava_eel = find("lava_eel")
    val black_full_helm = find("black_full_helm")
    val black_platebody = find("black_platebody")
    val black_platelegs = find("black_platelegs")
    val black_plateskirt = find("black_plateskirt")
    val pickaxes: List<ObjType> =
        listOf(
            find("bronze_pickaxe"),
            find("iron_pickaxe"),
            find("steel_pickaxe"),
            find("black_pickaxe"),
            find("mithril_pickaxe"),
            find("adamant_pickaxe"),
            find("rune_pickaxe"),
            find("dragon_pickaxe"),
            find("infernal_pickaxe"),
            find("3a_pickaxe"),
            find("crystal_pickaxe"),
        )
}

internal object HqVarps : VarpReferences() {
    val quest = find("heroquest")

    /** Server varp (65803): the `hq_progress` bits in [HeroesQuest.Companion]. */
    val progress = find("hq_progress")
}

internal object HqVarpBuilds : VarpBuilder() {
    init {
        build("hq_progress")
    }
}

internal object HqSeqs : org.rsmod.api.type.refs.seq.SeqReferences() {
    val cook = find("human_cooking")
}
