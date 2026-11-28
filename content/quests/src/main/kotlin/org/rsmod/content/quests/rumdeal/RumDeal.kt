package org.rsmod.content.quests.rumdeal

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.events.interact.NpcUEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
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
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.content.quests.seaslug.SeaSlug.Companion.SEA_SLUG
import org.rsmod.content.quests.zogre.ZogreFleshEaters.Companion.ZOGRE
import org.rsmod.content.travel.ships.nearestWalkable
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Rum Deal (OSRS Wiki). Progress is the cache quest varp `deal_quest` (cache db end state 19); the
 * side state uses the cache's own `deal_var` varbits: `deal_farming` (the blindweed patch
 * multiloc), `deal_barrel` / `deal_slug` / `deal_thulhu` (the pressure barrel and its lever
 * multiloc), `deal_multi_hopper` (the brewing control: 0 idle, 1 possessed, 2 brewing) and
 * `deal_multi_pirate_a..f` (intimidated swabs). Server varp `rumdeal_progress` (65976) keeps the
 * flags the cache has no bit for; soft timer `rumdeal_blindweed` (65560) grows the blindweed.
 * Stages are ours (the client only checks 0 / started / 19):
 * - 1 Pirate Pete (Port Phasmatys dock; Zogre Flesh Eaters, Priest in Peril, Slayer 42) knocks you
 *   out and rows you over; 2 Captain Braindeath gives a blindweed seed (grow it on the island).
 * - 3 blindweed in the hopper (attic); 4 sent for stagnant water (the volcano lake past 50% Luke);
 *   5 water in the hopper; 6 sent for five sea creatures (fishbowl and net at the island spots,
 *   Fishing 50, into the pressure barrel); 7 lever pulled (the controls are possessed).
 * - 8 the wrench: Davey blesses it (47 prayer points), the holy wrench on the controls brings out
 *   the evil spirit (level 150); 9 banished; 10 sent for a fever spider body (basement, Slayer 42);
 *   11 body in the hopper; 12 sent to Captain Donnie with a bucket of 'rum' from the output tap;
 *   13 Donnie drank it; 19 told Braindeath: 2 QP, 7,000 Fishing / Prayer / Farming XP (cache db
 *   stat_xp_awarded 70000 tenths each; Wiki 7,000), the holy wrench, the disease cured.
 * Island side content (travel, patch, fishing, ladders, the crew): [BraindeathIsland].
 *
 * Simplified: the knock-out cutscene is a fade (its lines play as a conversation); the evil spirit's
 * and the fever spider's drops/messages are game messages; refusing Pete's quest for the cash and
 * for fear share one "second thoughts" follow-up; Davey's blessing has no animation or camera; the
 * holy wrench's prayer potion bonus isn't built (no potion drinking on this server).
 */
class RumDeal
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val collision: CollisionFlagMap,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(RumDeal::class.java, "rum_deal").also { d ->
            fillAbove(d, "Talking to Pirate Pete", listOf("Talking to Pete again"))
            fillAbove(d, CAPTAIN_AGAIN, CAPTAIN_MENUS)
        }
    }
    private val davey by lazy { QuestDialogue.load(RumDeal::class.java, "rum_deal_davey") }
    private val donnie by lazy { QuestDialogue.load(RumDeal::class.java, "rum_deal_donnie") }
    private val captainAfter by lazy { QuestDialogue.load(RumDeal::class.java, "rum_deal_braindeath") }

    /** Per-player evil spirit (single player, keyed for safety). */
    private val spirits = HashMap<Int, Npc>()

    private val cutsceneSpeakers: Map<String, NpcType> by lazy {
        mapOf("Captain Braindeath" to npcTypes[RdNpcs.braindeath], "Pirate Pete" to npcTypes[RdNpcs.pete])
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, RdNpcs.pete, RUM_DEAL, wants = { true }) { talkPete(it, island = false) }
            register(npcTypes, RdNpcs.island_pete, RUM_DEAL, wants = { true }) { talkPete(it, island = true) }
            register(npcTypes, RdNpcs.braindeath, RUM_DEAL, wants = { state in STARTED until COMPLETE }) {
                talkCaptain(it)
            }
            default(npcTypes, RdNpcs.braindeath) { runQuestSection(it, captainAfter, AFTER_QUEST) }
            register(npcTypes, RdNpcs.davey, RUM_DEAL, wants = { state in STARTED until COMPLETE }) { talkDavey(it) }
            default(npcTypes, RdNpcs.davey) { daveyAfter(it) }
            register(npcTypes, RdNpcs.donnie, RUM_DEAL, wants = { state < COMPLETE }) { talkDonnie(it) }
            default(npcTypes, RdNpcs.donnie) { donnieAfter(it) }
        }

        // Items on Captain Braindeath and Captain Donnie.
        npcU(RdNpcs.braindeath, RdObjs.seed) { runQuestSection(it, dialogue, "Using the blindweed seed on Captain Braindeath") }
        npcU(RdNpcs.braindeath, RdObjs.slugling) { runQuestSection(it, dialogue, "Using Sluglings on Captain Braindeath") }
        npcU(RdNpcs.braindeath, RdObjs.karamthulhu) { runQuestSection(it, dialogue, "Using Karamthultu on Captain Braindeath") }
        npcU(RdNpcs.braindeath, RdObjs.holy_wrench) { runQuestSection(it, dialogue, "Using the holy wrench on Captain Braindeath") }
        npcU(RdNpcs.braindeath, RdObjs.spider_body) { runQuestSection(it, dialogue, "Using the spider corpse on Captain Braindeath") }
        npcU(RdNpcs.donnie, RdObjs.swill) { talkDonnie(it) }

        // The brewery.
        locU(RdLocs.hopper, RdObjs.blindweed) { blindweedInHopper() }
        locU(RdLocs.hopper, RdObjs.stagnant_bucket) { waterInHopper() }
        locU(RdLocs.hopper, RdObjs.spider_body) { spiderInHopper() }
        locU(RdLocs.pressure, RdObjs.slugling) { creatureInBarrel(RdObjs.slugling) }
        locU(RdLocs.pressure, RdObjs.karamthulhu) { creatureInBarrel(RdObjs.karamthulhu) }
        locOp(RdLocs.pressure, "Count") { countBarrel() }
        for (lever in RdLocs.levers) locOp(lever, "Pull") { pullLever() }
        for (control in RdLocs.controls) {
            locU(control, RdObjs.wrench) { runQuestSection(null, dialogue, "Using a non-holy wrench on Brewing control") }
            locU(control, RdObjs.holy_wrench) { smiteControls() }
        }
        locOp(RdLocs.tap, "Turn") { turnTap() }
        onEvent<NpcKilledEvent> { npcKilled(this) }
    }

    /* Guarded binds (another module may already own an op or a use pair). */

    private fun ScriptContext.locOp(loc: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(loc, op)) return
        onLocOpLabel(locTypes, loc, label, action)
    }

    private fun ScriptContext.locU(loc: LocType, obj: ObjType, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        if (eventBus.contains(LocUEvents.Op::class.java, EventBus.composeLongKey(loc.id, obj.id))) return
        onOpLocU(loc, obj) { action(it.loc) }
    }

    private fun ScriptContext.npcU(npc: NpcType, obj: ObjType, action: suspend ProtectedAccess.(Npc) -> Unit) {
        if (eventBus.contains(NpcUEvents.Op::class.java, EventBus.composeLongKey(npc.id, obj.id))) return
        onOpNpcU(npc, obj) { action(it.npc) }
    }

    /* State */

    private val ProtectedAccess.state: Int
        get() = player.questState(RUM_DEAL)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(RUM_DEAL, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.hasFlag(flag: Int): Boolean = (player.vars[RdVarps.progress] and flag) != 0

    private fun ProtectedAccess.setFlag(flag: Int) {
        VarPlayerIntMapSetter.set(player, RdVarps.progress, player.vars[RdVarps.progress] or flag)
    }

    private fun ProtectedAccess.give(obj: ObjType) {
        player.invAddOrDrop(objRepo, obj)
    }

    /* Pirate Pete */

    private fun ProtectedAccess.canStart(): Boolean =
        player.isQuestComplete(ZOGRE) &&
            player.isQuestComplete(PRIEST_IN_PERIL) &&
            statBase(stats.slayer) >= SLAYER_REQ

    private fun ProtectedAccess.lowSkills(): Boolean =
        stat(stats.crafting) < CRAFTING_REQ ||
            stat(stats.fishing) < FISHING_REQ ||
            stat(stats.farming) < FARMING_REQ ||
            statBase(stats.prayer) < PRAYER_REQ

    private suspend fun ProtectedAccess.talkPete(npc: Npc, island: Boolean) {
        if (state > 0) {
            peteLift(npc, island)
            return
        }
        if (island) return
        if (!canStart()) {
            // Transcript:Pirate Pete, "Lacking the requirements for Rum Deal".
            startDialogue(npc) {
                chatPlayer(neutral, "Hello there!")
                chatNpc(neutral, "Mornin'.")
                chatPlayer(neutral, "Got any quests?")
                chatNpc(neutral, "I may have a quest, but you don't look like you'd be able to handle the kind of monsters I have problems with.")
            }
            return
        }
        var start = false
        val refuse: suspend () -> Unit = { setFlag(PETE_REFUSED) }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has one or more levels less than the requirement:" to { lowSkills() },
                        "If talking to Pirate Pete with having second thoughts after accepting the quest:" to { true },
                    ),
                options =
                    mapOf(
                        "No." to refuse,
                        "Not a chance, this sounds too dangerous." to refuse,
                        "Great, I'll take the cash in used coins please." to refuse,
                        "No reason." to refuse,
                    ),
                actions = mapOf("(Cutscene 1 begins.)" to { start = true }),
            )
        val section = if (hasFlag(PETE_REFUSED)) "Talking to Pete again" else "Talking to Pirate Pete"
        runQuestSection(npc, dialogue, section, hooks)
        if (start) knockedOut()
    }

    /** "Pick up your diversion": the bottle, the row over, Pete and the captain, then the meeting. */
    private suspend fun ProtectedAccess.knockedOut() {
        setState(STARTED)
        fadeOverlay(startColour = 0, startTransparency = 255, endColour = 0, endTransparency = 0, clientDuration = 50)
        delay(FADE_TICKS)
        telejump(collision.nearestWalkable(CAPTAIN_ROOM))
        startDialogue { QuestDialogueRunner(SimpleQuestHooks(), cutsceneSpeakers).run(this, dialogue.section("Cutscene 1")) }
        fadeOverlay(startColour = 0, startTransparency = 0, endColour = 0, endTransparency = 255, clientDuration = 50)
        closeFadeOverlay()
        meetCaptain(null)
    }

    private suspend fun ProtectedAccess.meetCaptain(npc: Npc?) {
        runQuestSection(npc, dialogue, "Meeting Captain Braindeath", speakers = cutsceneSpeakers)
        if (!carries(RdObjs.seed)) give(RdObjs.seed)
        setState(MET)
    }

    /** Transcript:Pirate Pete "Standard dialogue": the bottle teleport both ways. */
    private suspend fun ProtectedAccess.peteLift(npc: Npc, island: Boolean) {
        var go = false
        startDialogue(npc) {
            chatPlayer(neutral, "Do I know you?")
            chatNpc(neutral, "Yes, you owe me some money.")
            if (!island) {
                chatNpc(neutral, "Want a lift to Braindeath Island?")
                chatPlayer(neutral, "Well, possibly, but could I go to Mos Le'Harmless instead?")
                chatNpc(neutral, "Nope. It's Braindeath island or nothing. Interested?")
            } else {
                chatNpc(neutral, "Want a lift to Port Phasmatys?")
            }
            when (
                choice4(
                    "Okay!", 0,
                    "Not now", 1,
                    "Why do I get a headache every time I see you?", 2,
                    "Are you any relation to Party Pete?", 3,
                )
            ) {
                0 -> {
                    chatPlayer(neutral, "Okay!")
                    for ((speaker, text) in LIFT_LINES[random.of(0, LIFT_LINES.size - 1)]) {
                        if (speaker) chatNpc(neutral, text) else chatPlayer(neutral, text)
                    }
                    go = true
                }
                1 -> {
                    chatPlayer(neutral, "Not now.")
                    chatPlayer(neutral, "I'm getting an awful headache talking to you. Any idea why?")
                    chatNpc(neutral, "No idea whatsoever.")
                }
                2 -> {
                    chatPlayer(neutral, "Why do I get a headache every time I see you?")
                    chatNpc(neutral, "Well, it's possibly the weight of all of your expensive items giving you a sore back.")
                    chatNpc(neutral, "As a doctor I can tell you that sometimes a bad back can manifest as a headache.")
                    chatPlayer(neutral, "You're a doctor?")
                    chatNpc(neutral, "I'm on a break.")
                    chatNpc(neutral, "Regardless, I can tell you that if you hand me your most expensive items, then the pain will disappear.")
                    chatNpc(neutral, "CoughonceyouturnaroundagainCough!")
                    chatPlayer(neutral, "What was that last bit?")
                    chatNpc(neutral, "Nothing...")
                }
                else -> {
                    chatPlayer(neutral, "Are you any relation to Party Pete?")
                    chatNpc(neutral, "Yes I am, he's my cousin.")
                    chatPlayer(neutral, "Well, you don't sound too happy about it. What happened?")
                    chatNpc(neutral, "Well, I arranged with all my friends to have a party at his place.")
                    chatNpc(neutral, "But then I humiliated myself by trying to dance with the knights.")
                    chatNpc(neutral, "All of them collapsed on me in a horrific, jangling pile.")
                    chatNpc(neutral, "I tried to salvage the night by having all the balloons come down...")
                    chatPlayer(neutral, "So what happened?")
                    chatNpc(neutral, "I didn't know that someone had swapped the balloons with cannonballs!")
                    chatNpc(neutral, "The casualties were horrific...")
                    chatNpc(neutral, "That was the worst fifth birthday party in the history of the world.")
                    chatPlayer(neutral, "I'm sure it wasn't that bad.")
                    chatNpc(neutral, "Not according to the Official History of Gielinor!")
                    chatNpc(neutral, "Every edition... the pictures bring it all back...")
                    chatPlayer(neutral, "Ouch...")
                }
            }
        }
        if (!go) return
        say("OW!")
        fadeOverlay(startColour = 0, startTransparency = 255, endColour = 0, endTransparency = 0, clientDuration = 50)
        delay(FADE_TICKS)
        telejump(collision.nearestWalkable(if (island) PORT_ARRIVAL else ISLAND_ARRIVAL))
        delay(1)
        fadeOverlay(startColour = 0, startTransparency = 0, endColour = 0, endTransparency = 255, clientDuration = 50)
        closeFadeOverlay()
        val other = npcTypes[if (island) RdNpcs.pete else RdNpcs.island_pete]
        startDialogue {
            chatPlayer(neutral, "Ooooh... my head...")
            chatNpcSpecific("Pirate Pete", other, neutral, "Are you ok? You, errr...")
            for (text in BRUISE_LINES[random.of(0, BRUISE_LINES.size - 1)]) {
                chatNpcSpecific("Pirate Pete", other, neutral, text)
            }
            chatPlayer(neutral, "Wow... I'm lucky I wasn't seriously hurt!")
        }
    }

    /* Captain Braindeath */

    private suspend fun ProtectedAccess.talkCaptain(npc: Npc) {
        val full = inv.isFull()
        when (state) {
            STARTED -> meetCaptain(npc)
            MET -> blindweedTalk(npc, full)
            WEED_IN -> {
                val noBucket = !carries(RdObjs.bucket)
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player doesn't have an empty bucket:" to { noBucket }),
                        actions = mapOf(BUCKET_ACTION to { if (!full) invAdd(inv, RdObjs.bucket) }),
                    )
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath after using blindweed on the hopper", hooks)
                setState(WATER)
            }
            WATER ->
                if (hasFlag(WATER_FETCHED)) {
                    val lost = !carries(RdObjs.stagnant_bucket)
                    val hooks =
                        SimpleQuestHooks(
                            conditions =
                                mapOf(
                                    "If the player has lost the bucket of water:" to { lost },
                                    "If the player doesn't have room in their inventory" to { full },
                                )
                        )
                    runQuestSection(npc, dialogue, "Talking to Captain Braindeath before pouring in the stagnant in the hopper", hooks)
                    if (lost && !full) invAdd(inv, RdObjs.stagnant_bucket)
                } else {
                    val noBucket = !carries(RdObjs.bucket)
                    val hooks = SimpleQuestHooks(conditions = mapOf("If the player doesn't have an empty bucket:" to { noBucket }))
                    runQuestSection(npc, dialogue, "Talking to Captain Braindeath again before getting stagnant water", hooks)
                    // Authored (not Wiki): he hands over the bucket he reminds you of.
                    if (noBucket && !full) invAdd(inv, RdObjs.bucket)
                }
            WATER_IN -> {
                var hand = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has completed Sea Slug quest:" to { player.isQuestComplete(SEA_SLUG) },
                                "If the player doesn't have free inventory space:" to { full },
                                "If the player has inventory space:" to { hand = true; true },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath after using the water on the hopper", hooks)
                if (hand && !carries(RdObjs.fishbowl_net)) invAdd(inv, RdObjs.fishbowl_net)
                setState(SLUGS)
            }
            SLUGS -> {
                val noNet = !carries(RdObjs.fishbowl_net)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have a fishbowl and net:" to { noNet },
                                "If the player doesn't have inventory space:" to { full },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath again before catching the sluglings", hooks)
                if (noNet && !full) invAdd(inv, RdObjs.fishbowl_net)
            }
            LEVER -> {
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath after filling the pressure barrel")
                give(RdObjs.wrench)
                setState(WRENCH)
            }
            WRENCH ->
                if (carries(RdObjs.holy_wrench)) {
                    runQuestSection(npc, dialogue, "Talking to Captain Braindeath with the holy wrench")
                } else {
                    var hand = false
                    val lost = !carries(RdObjs.wrench)
                    val hooks =
                        SimpleQuestHooks(
                            conditions =
                                mapOf(
                                    "If the player has lost the wrench:" to { lost },
                                    "If the player doesn't have inventory space:" to { full },
                                    "If the player has inventory space:" to { hand = true; true },
                                )
                        )
                    runQuestSection(npc, dialogue, "$CAPTAIN_AGAIN (3)", hooks)
                    if (hand && lost) invAdd(inv, RdObjs.wrench)
                }
            BANISHED -> {
                val gloves = RdObjs.slayer_gloves in player.worn
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("Not wearing Slayer Gloves:" to { !gloves }, "Wearing Slayer Gloves:" to { gloves })
                    )
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath after killing the evil spirit", hooks)
                setState(SPIDER)
            }
            SPIDER -> runQuestSection(npc, dialogue, "Talking to Captain Braindeath again before getting the spider corpse")
            SPIDER_IN -> {
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath after using the spider carcass on the hopper")
                // Wiki walkthrough: Braindeath gives you a bucket if you don't already have one.
                if (!carries(RdObjs.bucket) && !carries(RdObjs.swill)) give(RdObjs.bucket)
                setState(DONNIE)
            }
            DONNIE -> {
                val noRum = !carries(RdObjs.swill)
                val hooks = SimpleQuestHooks(conditions = mapOf("If the player hasn't gotten 'rum' yet:" to { noRum }))
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath before talking to Captain Donnie", hooks)
                if (noRum && !carries(RdObjs.bucket)) give(RdObjs.bucket) // Authored (not Wiki).
            }
            DRUNK -> {
                runQuestSection(npc, dialogue, "Talking to Captain Braindeath after talking to Captain Donnie")
                finish()
            }
        }
    }

    private suspend fun ProtectedAccess.blindweedTalk(npc: Npc, full: Boolean) {
        when {
            !hasFlag(PICKED) -> {
                val lostSeed = !carries(RdObjs.seed) && bit(RdVarBits.farming) < PATCH_PLANTED
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has lost the Blindweed seed:" to { lostSeed },
                                "If the player has no inventory space:" to { full },
                                "If the player has inventory space:" to { true },
                            ),
                        actions = mapOf("(The player is handed another seed.)" to { invAdd(inv, RdObjs.seed) }),
                    )
                runQuestSection(npc, dialogue, CAPTAIN_AGAIN, hooks)
            }
            !hasFlag(WEED_REPORTED) -> {
                if (carries(RdObjs.blindweed)) {
                    runQuestSection(npc, dialogue, "Talking to Captain Braindeath after getting blindweed")
                } else {
                    runQuestSection(npc, dialogue, "If the player has lost the blindweed")
                    give(RdObjs.blindweed)
                }
                setFlag(WEED_REPORTED)
            }
            else -> {
                var hand = false
                val lost = !carries(RdObjs.blindweed)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player loses the blindweed:" to { lost },
                                "If the player does not have inventory space:" to { full },
                                "If the player has inventory space:" to { hand = true; true },
                            )
                    )
                runQuestSection(npc, dialogue, "$CAPTAIN_AGAIN (2)", hooks)
                if (hand && lost) invAdd(inv, RdObjs.blindweed)
            }
        }
    }

    private fun ProtectedAccess.finish() {
        statAdvance(stats.fishing, REWARD_XP * xpMods.get(player, stats.fishing))
        statAdvance(stats.prayer, REWARD_XP * xpMods.get(player, stats.prayer))
        statAdvance(stats.farming, REWARD_XP * xpMods.get(player, stats.farming))
        // Sluglings and karamthulhu don't outlive the quest (Wiki); the holy wrench is the reward.
        for (obj in listOf(RdObjs.slugling, RdObjs.karamthulhu)) {
            val count = invTotal(inv, obj)
            if (count > 0) invDel(inv, obj, count)
        }
        if (!carries(RdObjs.holy_wrench)) give(RdObjs.holy_wrench)
        // "Disease is cured upon completing the quest."
        if (player.vars[RdVarps.disease] > 0) {
            VarPlayerIntMapSetter.set(player, RdVarps.disease, 0)
            player.clearSoftTimer(RdTimers.disease)
        }
        completeQuest(RUM_DEAL)
    }

    /* Davey and Captain Donnie */

    private suspend fun ProtectedAccess.talkDavey(npc: Npc) {
        when {
            state < WRENCH -> runQuestSection(npc, dialogue, "Talking to Davey")
            state > WRENCH || carries(RdObjs.holy_wrench) ->
                runQuestSection(npc, dialogue, "Talking to Davey after getting the holy wrench")
            else -> {
                val wrench = carries(RdObjs.wrench)
                val devout = stat(stats.prayer) >= PRAYER_REQ
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "Without the wrench:" to { !wrench },
                                "If the player does not have 47 Prayer points:" to { !devout },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Davey (2)", hooks)
                if (wrench && devout) invReplace(inv, RdObjs.wrench, 1, RdObjs.holy_wrench)
            }
        }
    }

    private suspend fun ProtectedAccess.daveyAfter(npc: Npc) {
        val has = carries(RdObjs.holy_wrench) || invTotal(worn, RdObjs.holy_wrench) > 0 || invTotal(bank, RdObjs.holy_wrench) > 0
        if (has) {
            runQuestSection(npc, davey, "After Rum Deal")
            return
        }
        val full = inv.isFull()
        val hooks = SimpleQuestHooks(conditions = mapOf("If the player has no inventory space:" to { full }))
        runQuestSection(npc, davey, "Without a holy wrench in the inventory or bank", hooks)
        if (!full) invAdd(inv, RdObjs.holy_wrench)
    }

    private suspend fun ProtectedAccess.talkDonnie(npc: Npc) {
        val rum = carries(RdObjs.swill)
        when {
            state == DONNIE && rum -> {
                val drink: suspend () -> Boolean = {
                    invReplace(inv, RdObjs.swill, 1, RdObjs.bucket)
                    false
                }
                val hooks = SimpleQuestHooks(boxes = mapOf("The Captain drinks the 'rum' as quickly as possible." to drink))
                runQuestSection(npc, dialogue, "Talking to Captain Donnie (2)", hooks)
                setState(DRUNK)
            }
            state > DONNIE && rum -> donnieDrinks(npc)
            state > DONNIE -> runQuestSection(npc, donnie, AFTER_QUEST)
            else -> runQuestSection(npc, dialogue, "Talking to Captain Donnie")
        }
    }

    private suspend fun ProtectedAccess.donnieDrinks(npc: Npc) {
        val drink: suspend () -> Boolean = {
            invReplace(inv, RdObjs.swill, 1, RdObjs.bucket)
            false
        }
        val hooks = SimpleQuestHooks(boxes = mapOf("Donnie drinks the 'rum' like it is water." to drink))
        runQuestSection(npc, dialogue, "Talking to Captain Donnie again while having 'rum' with you", hooks)
    }

    private suspend fun ProtectedAccess.donnieAfter(npc: Npc) {
        if (carries(RdObjs.swill)) donnieDrinks(npc) else runQuestSection(npc, donnie, AFTER_QUEST)
    }

    /* The brewery machinery */

    private suspend fun ProtectedAccess.blindweedInHopper() {
        if (state != MET || !hasFlag(PICKED)) {
            mes("Nothing interesting happens.")
            return
        }
        runQuestSection(null, dialogue, "Using blindweed in the hopper")
        invDel(inv, RdObjs.blindweed)
        setState(WEED_IN)
    }

    private suspend fun ProtectedAccess.waterInHopper() {
        if (state != WATER) {
            mes("Nothing interesting happens.")
            return
        }
        runQuestSection(null, dialogue, "Using bucket of water in the hopper")
        invReplace(inv, RdObjs.stagnant_bucket, 1, RdObjs.bucket)
        setState(WATER_IN)
    }

    private suspend fun ProtectedAccess.spiderInHopper() {
        if (state != SPIDER) {
            mes("Nothing interesting happens.")
            return
        }
        runQuestSection(null, dialogue, "Using the spider corpse on the hopper")
        invDel(inv, RdObjs.spider_body)
        setBit(RdVarBits.control, CONTROL_BREWING)
        setState(SPIDER_IN)
    }

    private suspend fun ProtectedAccess.creatureInBarrel(obj: ObjType) {
        if (state != SLUGS) {
            mes("Nothing interesting happens.")
            return
        }
        val count = bit(RdVarBits.barrel)
        if (count >= BARREL_FULL) {
            runQuestSection(null, dialogue, "Attempting to stuff a sixth Slugling into the barrel")
            return
        }
        val slug = obj == RdObjs.slugling
        runQuestSection(null, dialogue, if (slug) "Using Sluglings on the barrel" else "Using Karamthultu on the barrel")
        invDel(inv, obj)
        setBit(RdVarBits.barrel, count + 1)
        if (slug) setBit(RdVarBits.slug, bit(RdVarBits.slug) + 1) else setBit(RdVarBits.thulhu, bit(RdVarBits.thulhu) + 1)
    }

    private fun ProtectedAccess.countBarrel() {
        // Authored (not Wiki): the barrel's Count op.
        val count = bit(RdVarBits.barrel)
        mes(if (count == 1) "There is 1 sea creature in the barrel." else "There are $count sea creatures in the barrel.")
    }

    private suspend fun ProtectedAccess.pullLever() {
        if (state > SLUGS) {
            mes("Nothing interesting happens.") // Authored (not Wiki).
            return
        }
        if (state < SLUGS || bit(RdVarBits.barrel) < BARREL_FULL) {
            runQuestSection(null, dialogue, "Trying to pull the lever with less than five sea creatures")
            return
        }
        runQuestSection(null, dialogue, "Pulling the lever with 5 sea creatures in the barrel")
        setBit(RdVarBits.control, CONTROL_POSSESSED)
        setState(LEVER)
    }

    private suspend fun ProtectedAccess.smiteControls() {
        if (state != WRENCH) {
            mes("Nothing interesting happens.")
            return
        }
        val existing = spirits[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) {
            runQuestSection(null, dialogue, "Using the holy wrench on the controls when the spirit is already out")
            return
        }
        runQuestSection(null, dialogue, "Using the holy wrench on the brewing control")
        val spirit = Npc(npcTypes[RdNpcs.evil_spirit], collision.nearestWalkable(player.coords.translate(1, 0), radius = 2))
        npcRepo.add(spirit, SPIRIT_TICKS)
        spirits[player.uid.packed] = spirit
        spirit.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.turnTap() {
        when {
            state >= DRUNK -> runQuestSection(null, dialogue, "Trying to get more 'rum' from the Output tap")
            state < SPIDER_IN -> mes("Nothing interesting happens.")
            else -> {
                val bucket = carries(RdObjs.bucket)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have an empty bucket:" to { !bucket },
                                "If the player has an empty bucket:" to { bucket },
                            )
                    )
                runQuestSection(null, dialogue, "Turning the output tap (requires a bucket)", hooks)
                if (bucket) invReplace(inv, RdObjs.bucket, 1, RdObjs.swill)
            }
        }
    }

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        when (event.npc.type.id) {
            RdNpcs.evil_spirit.id -> {
                spirits.remove(player.uid.packed)
                if (player.questState(RUM_DEAL) == WRENCH) {
                    player.setQuestState(RUM_DEAL, BANISHED)
                    VarPlayerIntMapSetter.set(player, RdVarBits.control, CONTROL_IDLE)
                    player.mes("You have banished the Evil Spirit!")
                }
            }
            RdNpcs.fever_spider.id -> {
                // The body only drops once Braindeath has asked for it (Wiki).
                if (player.questState(RUM_DEAL) != SPIDER) return
                if (RdObjs.spider_body in player.inv) return
                objRepo.add(Obj.fromOwner(player, event.npc.coords, RdObjs.spider_body, 1), DROP_TICKS)
            }
        }
    }

    companion object {
        const val STARTED = 1
        const val MET = 2
        const val WEED_IN = 3
        const val WATER = 4
        const val WATER_IN = 5
        const val SLUGS = 6
        const val LEVER = 7
        const val WRENCH = 8
        const val BANISHED = 9
        const val SPIDER = 10
        const val SPIDER_IN = 11
        const val DONNIE = 12
        const val DRUNK = 13
        const val COMPLETE = 19

        /* rumdeal_progress bits. */
        const val PETE_REFUSED = 1
        const val PICKED = 2
        const val WEED_REPORTED = 4
        const val WATER_FETCHED = 8

        /* deal_farming (the patch multiloc): 0-2 weeds, 3 raked, 4 growing, 5 grown. */
        const val PATCH_RAKED = 3
        const val PATCH_PLANTED = 4
        const val PATCH_GROWN = 5

        private const val CONTROL_IDLE = 0
        private const val CONTROL_POSSESSED = 1
        private const val CONTROL_BREWING = 2
        private const val BARREL_FULL = 5

        private const val SLAYER_REQ = 42
        private const val CRAFTING_REQ = 42
        private const val FISHING_REQ = 50
        private const val FARMING_REQ = 40
        private const val PRAYER_REQ = 47
        private const val REWARD_XP = 7000.0

        private const val FADE_TICKS = 3
        private const val SPIRIT_TICKS = 500
        private const val DROP_TICKS = 200

        private val CAPTAIN_ROOM = CoordGrid(2144, 5107, 1)
        private val PORT_ARRIVAL = CoordGrid(3679, 3537, 0)
        private val ISLAND_ARRIVAL = CoordGrid(2158, 5113, 0)

        private const val AFTER_QUEST = "Standard dialogue after Rum Deal"
        private const val CAPTAIN_AGAIN = "Talking to Captain Braindeath again"
        private const val BUCKET_ACTION =
            "(The player is given an empty bucket. If the inventory is full, this line still occurs, but no bucket will appear.)"

        /** Later Braindeath menus whose small-talk options only say "above" on the Wiki. */
        private val CAPTAIN_MENUS =
            listOf(
                "$CAPTAIN_AGAIN (2)",
                "$CAPTAIN_AGAIN (3)",
                "Before putting blindweed in the hopper",
                "Talking to Captain Braindeath again before getting stagnant water",
                "Talking to Captain Braindeath before pouring in the stagnant in the hopper",
                "Talking to Captain Braindeath again before catching the sluglings",
                "Talking to Captain Braindeath with the holy wrench",
                "Talking to Captain Braindeath again before getting the spider corpse",
            )

        /** Transcript:Pirate Pete, the five random lines before the bottle (true = Pete speaks). */
        private val LIFT_LINES: List<List<Pair<Boolean, String>>> =
            listOf(
                listOf(
                    true to "All right. I'll need to use my Magical Teleporting Bottle.",
                    false to "Magical Teleporting Bottle?",
                    true to "Yes. Just turn around three times, then clap your hands and say the place you want to go.",
                    false to "This I have to see.",
                    false to "Oh well, here goes.",
                    false to "One...",
                ),
                listOf(true to "Would you like a hand with your stuff? You seemed to have dropped something.", false to "Where?"),
                listOf(true to "All right. You know that it's going to cost extra to take your friend as well, right?", false to "What friend?"),
                listOf(
                    true to "Certainly. By the way, you have a spider in your hair.",
                    false to "Oh no! Get it out, get it out!",
                    true to "Sure, turn around and I'll get it for you.",
                    false to "Thanks!",
                ),
                listOf(true to "Well I'll be more than happy to...", true to "Egad! Did you see that?", false to "What? Where?"),
            )

        /** Transcript:Pirate Pete, the four random excuses on arrival. */
        private val BRUISE_LINES: List<List<String>> =
            listOf(
                listOf("...missed your mouth while drinking from a bottle. Hence the bottle-shaped bruises."),
                listOf("...slipped and slid into a brick wall."),
                listOf("...managed to fall onto my boot while I was practicing kicking."),
                listOf("...hit your head on my oars while I was rowing over.", "Twice."),
            )

        private val EMPTY_TAGS = setOf("above", "other", "previous")

        /**
         * Options in [targets] whose Wiki answer is a bare "above" get the answer of the
         * same-labelled option in [source] (the Wiki shows it once and refers back to it).
         */
        internal fun fillAbove(dialogue: QuestDialogue, source: String, targets: List<String>) {
            val answers = HashMap<String, List<Node>>()
            fun collect(nodes: List<Node>) {
                for (node in nodes) {
                    if (node is Node.Option && node.label !in answers && !isBare(node)) answers[node.label] = node.children
                    collect(node.children)
                }
            }
            collect(dialogue.section(source))
            fun fill(nodes: List<Node>) {
                for (node in nodes) {
                    if (node is Node.Option && isBare(node)) {
                        val answer = answers[node.label] ?: continue
                        node.children.clear()
                        node.children.addAll(answer)
                    } else {
                        fill(node.children)
                    }
                }
            }
            for (target in targets) fill(dialogue.section(target))
        }

        private fun isBare(option: Node.Option): Boolean =
            option.children.isNotEmpty() && option.children.all { it is Node.Action && it.tag.lowercase() in EMPTY_TAGS }

        val RUM_DEAL =
            QuestDef(
                name = "Rum Deal",
                varp = { RdVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "7,000 Fishing XP",
                        "7,000 Prayer XP",
                        "7,000 Farming XP",
                        "The Holy wrench",
                        "Access to Braindeath Island",
                    ),
                scrollObj = { RdObjs.holy_wrench },
            )
    }
}

// Every cache ref Rum Deal uses (re-check after cache updates).

internal object RdNpcs : NpcReferences() {
    val pete = find("deal_pete")
    val island_pete = find("deal_island_pete")
    val braindeath = find("deal_captian_braindeath")
    val luke = find("deal_50luke")
    val davey = find("deal_davey")
    val donnie = find("deal_captian_donnie")
    val evil_spirit = find("deal_evil_spirit")
    val fever_spider = find("deal_fever_spiders1")
    val squid_spot = find("deal_squid")
    val protesters =
        listOf(
            find("deal_zombie_protester_1"),
            find("deal_zombie_protester_2"),
            find("deal_zombie_protester_3"),
            find("deal_zombie_protester_4"),
            find("deal_zombie_protester_5"),
            find("deal_zombie_protester_6"),
        )
    /** Zombie swabs a..f ("Zombie Swab 1..6" on the Wiki; `deal_multi_pirate_a..f`). */
    val swabs =
        listOf(
            find("deal_pirate_a"),
            find("deal_pirate_b"),
            find("deal_pirate_c"),
            find("deal_pirate_d"),
            find("deal_pirate_e"),
            find("deal_pirate_f"),
        )
    val brewers =
        listOf(
            find("deal_brewer_1"),
            find("deal_brewer_2"),
            find("deal_brewer_3"),
            find("deal_brewer_4"),
            find("deal_brewer_5"),
            find("deal_brewer_6"),
            find("deal_brewer_7"),
            find("deal_brewer_8"),
        )
}

internal object RdLocs : LocReferences() {
    val hopper = find("deal_hopper")
    val pressure = find("deal_pressure")
    /** Children of `deal_multi_lever` (`deal_barrel` 0-4 down, 5 up). */
    val levers = listOf(find("deal_lever_down"), find("deal_lever_up"))
    /** Children of `deal_multicontrol` (`deal_multi_hopper` 0/1/2). */
    val controls = listOf(find("deal_brew_control"), find("deal_brew_control_spin"), find("deal_brew_control_work"))
    val tap = find("deal_brewvat_tap")
    /** Children of `deal_blindweed` (`deal_farming` 0-5). */
    val patch_weeds = listOf(find("deal_blindweed_weeds3"), find("deal_blindweed_weeds2"), find("deal_blindweed_weeds1"))
    val patch_empty = find("deal_blindweed_empty")
    val patch_seed = find("deal_blindweed_seed")
    val patch_grown = find("deal_blindweed_fullygrown")
    val trashed_patch = find("deal_overgrown_patch")
    val stagnant = find("deal_stagnant")
    val cupboard = find("deal_broomcupboard")
    val cupboard_open = find("deal_broomcupboard_open")
    val ladder_up = find("deal_ladder_up")
    val ladder_down = find("deal_laddertop")
    val stairs_up = find("deal_stairs_bottom")
    val stairs_down = find("deal_stairs_top")
    val gate = find("deal_gate_closed")
}

internal object RdObjs : ObjReferences() {
    val seed = find("deal_blindweed_seed")
    val blindweed = find("deal_blindweed")
    val stagnant_bucket = find("deal_stagnant_bucket")
    val wrench = find("deal_wrench")
    val holy_wrench = find("deal_wrench_blessed")
    val slugling = find("deal_slugling")
    val karamthulhu = find("deal_karamthulhu")
    val spider_body = find("deal_spider_body")
    val swill = find("deal_bucket_swill")
    val slayer_gloves = find("deal_slayer_gloves")
    val fishbowl_net = find("fishbowl_net")
    val fishbowl = find("fishbowl_empty")
    val tiny_net = find("tiny_net")
    val bucket = find("bucket_empty")
    val rake = find("rake")
    val dibber = find("dibber")
    val weeds = find("weeds")
    val tinderbox = find("tinderbox")
    val charos = find("ring_of_charos_unlocked")
    val watering_can = find("watering_can_0")
    val watering_cans =
        listOf(
            find("watering_can_0"),
            find("watering_can_1"),
            find("watering_can_2"),
            find("watering_can_3"),
            find("watering_can_4"),
            find("watering_can_5"),
            find("watering_can_6"),
            find("watering_can_7"),
            find("watering_can_8"),
        )
}

internal object RdVarBits : VarBitReferences() {
    val farming = find("deal_farming")
    val barrel = find("deal_barrel")
    val slug = find("deal_slug")
    val thulhu = find("deal_thulhu")
    val control = find("deal_multi_hopper")
    val swabs =
        listOf(
            find("deal_multi_pirate_a"),
            find("deal_multi_pirate_b"),
            find("deal_multi_pirate_c"),
            find("deal_multi_pirate_d"),
            find("deal_multi_pirate_e"),
            find("deal_multi_pirate_f"),
        )
}

internal object RdVarps : VarpReferences() {
    val quest = find("deal_quest")
    /** Server varp (65976): bit 0 refused Pete, 1 blindweed picked, 2 reported it, 3 water fetched. */
    val progress = find("rumdeal_progress")
    /** Cache varp: disease periods left (Zogre Flesh Eaters' ZogreDisease ticks it). */
    val disease = find("disease")
}

internal object RdVarpBuilds : VarpBuilder() {
    init {
        build("rumdeal_progress")
    }
}

internal object RdTimers : TimerReferences() {
    val blindweed = find("rumdeal_blindweed")
    val disease = find("zogre_disease")
}

internal object RdSeqs : SeqReferences() {
    val rake = find("farming_raking")
    val dibbing = find("farming_seed_dibbing")
    val pick = find("picking_low")
    val smallnet = find("human_smallnet")
    val pickup = find("human_pickupfloor")
}
