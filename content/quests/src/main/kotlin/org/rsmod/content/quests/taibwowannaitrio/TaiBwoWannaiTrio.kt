package org.rsmod.content.quests.taibwowannaitrio

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.interact.OpInterceptors
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.cookingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.junglepotion.JunglePotion.Companion.JUNGLE_POTION
import org.rsmod.content.skills.cooking.scripts.CookingOverrides
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.stat.StatType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tai Bwo Wannai Trio (OSRS Wiki). The quest varp is the cache's `tbwt_main` (0-6): the brothers'
 * multinpcs show them at the shore / island / jungle while it is 0-5 and in their village houses at
 * 6, so it only moves 1 (started), 2 (Lubufu's apprentice) and +1 per brother brought round (5 =
 * all three, see Timfraku); everything finer lives in bits of the cache's spare `tbwt_tiadeche`
 * varp (no varbits or client reads, saved like any perm varp).
 * - Timfraku (upstairs, north-west hut of Tai Bwo Wannai) asks you to bring his sons home; needs
 *   Jungle Potion.
 * - Lubufu (Brimhaven shore) wants 20 raw karambwanji, then makes you his apprentice: a karambwan
 *   vessel and karambwan fishing (the Fishing module's method, 65 Fishing; gated here until then).
 * - Tiadeche (north-east shore) catches a karambwan with a vessel loaded with karambwanji and
 *   gives it to you; later he wants Tinsay's crafting manual.
 * - Cooking raw karambwan before Tinsay's lesson gives poison karambwan (CookingOverrides); a pestle
 *   grinds it to karambwan paste, which poisons a spear (kp).
 * - Tamayu (mine south-east of the village) hunts the Shaikahan; with a 4-dose agility potion and
 *   an iron-or-better kp spear he slays it. He skins monkey corpses once you've helped him.
 * - Tinsay (Cairn Isle) wants banana (sliced) in Karamjan rum, a seaweed-in-monkey-skin sandwich and
 *   burnt jogre bones marinated in karambwanji; a vessel used on him gives the crafting manual.
 * - Timfraku pays 2,000 coins; the brothers' houses then give 5,000 Fishing xp (Tiadeche), 5,000
 *   Cooking xp and proper karambwan cooking (Tinsay), 2,500 Attack and Strength xp and a rune
 *   spear(kp) (Tamayu). 2 QP.
 *
 * Simplified (Authored, not Wiki):
 * - The Shaikahan hunts are narrated (no cutscene); the spear must be iron or better (plain, p or
 *   kp; only kp works) and the agility doses add up to four.
 * - Joke items: banana-stuffed rum is kept (it is one item-on-item), the stuffed monkey and cooked
 *   karambwanji paste are not made; monkey melee immunity and Karamjan rum breaking off-island are
 *   skipped.
 * - Jogre bones burn with a tinderbox in the inventory (Firemaking 30) or on a furnace, instead of
 *   lighting them on the ground; marinating needs Cooking 30 (the quest's Cooking requirement).
 * - Cairn Isle: the rock slide (Agility 15) just moves you across, no fall damage; the bridge has no
 *   loc to bind and is walked over normally. Tile picks are best guesses (Ryan-test).
 * - After Tinsay's lesson raw karambwan always cooks into cooked karambwan (no choice of poison).
 * - Tiadeche / Tamayu's shop npcs (generic-shops) stand in the houses before the quest is done;
 *   before completion their Talk-to just says they're busy. The tribal statue stays unbound.
 */
class TaiBwoWannaiTrio
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val rng: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(TaiBwoWannaiTrio::class.java, "tai_bwo_wannai_trio")
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, TbwtNpcs.timfraku, TAI_BWO_WANNAI_TRIO, wants = { true }) { talkTimfraku(it) }
            register(npcTypes, TbwtNpcs.lubufu, TAI_BWO_WANNAI_TRIO, wants = { true }) { talkLubufu(it) }
            register(npcTypes, TbwtNpcs.tiadeche, TAI_BWO_WANNAI_TRIO, wants = { true }) { talkTiadeche(it) }
            register(npcTypes, TbwtNpcs.tinsay, TAI_BWO_WANNAI_TRIO, wants = { true }) { talkTinsay(it) }
            register(npcTypes, TbwtNpcs.tamayu, TAI_BWO_WANNAI_TRIO, wants = { true }) { talkTamayu(it) }
            register(npcTypes, TbwtNpcs.tiadeche_final, TAI_BWO_WANNAI_TRIO, wants = { true }) { homeTiadeche(it) }
            register(npcTypes, TbwtNpcs.tinsay_final, TAI_BWO_WANNAI_TRIO, wants = { true }) { homeTinsay(it) }
            register(npcTypes, TbwtNpcs.tamayu_final, TAI_BWO_WANNAI_TRIO, wants = { true }) { homeTamayu(it) }
        }
        bindNpcItems()
        bindCrafting()
        bindHeat()
        onLocOpLabel(locTypes, TbwtLocs.rock_slide, "Climb") { climbRocks(it) }
        onLocOpLabel(locTypes, TbwtLocs.bamboo_door, "Open") { openHut(it) }
        OpInterceptors.register(KarambwanSpots())
        CookingOverrides.register { player, raw ->
            if (raw.id == TbwtObjs.raw_karambwan.id && !player.flag(REWARD_COOKING)) {
                CookingOverrides.Product(TbwtObjs.poison_karambwan, POISON_KARAMBWAN_XP, 1)
            } else {
                null
            }
        }
    }

    private fun ScriptContext.bindNpcItems() {
        onOpNpcU(TbwtNpcs.lubufu, TbwtObjs.raw_karambwanji) { talkLubufu(it.npc) }
        for (obj in listOf(TbwtObjs.raw_karambwan, TbwtObjs.cooked_karambwan, TbwtObjs.poison_karambwan)) {
            onOpNpcU(TbwtNpcs.tiadeche, obj) { tiadecheItem(it.npc, ITEM_KARAMBWAN) }
        }
        onOpNpcU(TbwtNpcs.tiadeche, TbwtObjs.vessel) { tiadecheItem(it.npc, ITEM_VESSEL) }
        onOpNpcU(TbwtNpcs.tiadeche, TbwtObjs.vessel_loaded) { tiadecheItem(it.npc, ITEM_LOADED) }
        onOpNpcU(TbwtNpcs.tiadeche, TbwtObjs.raw_karambwanji) { tiadecheItem(it.npc, ITEM_KARAMBWANJI) }
        onOpNpcU(TbwtNpcs.tiadeche, TbwtObjs.crafting_manual) { talkTiadeche(it.npc) }
        onOpNpcU(TbwtNpcs.tamayu, TbwtObjs.monkey_corpse) { tamayuCorpse(it.npc) }
        onOpNpcU(TbwtNpcs.tamayu, TbwtObjs.monkey_skin) {
            tamayuItems(it.npc, mapOf("If the player uses the monkey skin on Tamayu:" to { true }))
        }
        for ((spear, kind) in TbwtObjs.tamayu_spears) {
            onOpNpcU(TbwtNpcs.tamayu, spear) { tamayuSpear(it.npc, spear, kind) }
        }
        for ((potion, doses) in TbwtObjs.agility_potions) {
            onOpNpcU(TbwtNpcs.tamayu, potion) { tamayuPotion(it.npc, potion, doses) }
        }
        for (obj in TbwtObjs.tinsay_items) {
            onOpNpcU(TbwtNpcs.tinsay, obj) { talkTinsay(it.npc) }
        }
        onOpNpcU(TbwtNpcs.tinsay, TbwtObjs.vessel) { tinsayVessel(it.npc, TbwtObjs.vessel) }
        onOpNpcU(TbwtNpcs.tinsay, TbwtObjs.vessel_loaded) { tinsayVessel(it.npc, TbwtObjs.vessel_loaded) }
    }

    /* State */

    private val ProtectedAccess.stage: Int
        get() = player.questState(TAI_BWO_WANNAI_TRIO)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(TAI_BWO_WANNAI_TRIO, to)
    }

    /** Lubufu's apprentice: 2, plus one per brother who has agreed to go home (5 = all three). */
    private fun ProtectedAccess.recompute() {
        if (stage < TAUGHT || player.isQuestComplete(TAI_BWO_WANNAI_TRIO)) return
        val home = listOf(TIADECHE_DONE, TAMAYU_DONE, TINSAY_DONE).count { player.flag(it) }
        advance(TAUGHT + home)
    }

    private fun ProtectedAccess.has(bit: Int): Boolean = player.flag(bit)

    private fun ProtectedAccess.mark(bit: Int) = player.setFlag(bit)

    private suspend fun ProtectedAccess.section(
        npc: Npc?,
        title: String,
        hooks: QuestDialogueHooks = SimpleQuestHooks(),
    ) {
        runQuestSection(npc, dialogue, title, hooks)
    }

    private fun ProtectedAccess.give(obj: ObjType, count: Int = 1) {
        invAddOrDrop(objRepo, obj, count)
    }

    private fun ProtectedAccess.xp(stat: StatType, amount: Double) {
        statAdvance(stat, amount * xpMods.get(player, stat))
    }

    /* Timfraku */

    private fun ProtectedAccess.identityHooks(): Map<String, () -> Boolean> {
        val id = player.field(IDENTITY, IDENTITY_BITS)
        return mapOf(
            "If the player says they are a roving adventurer:" to { id == 1 },
            "If the player says they are a travelling explorer:" to { id == 2 },
            "If the player says they are a wandering wayfarer:" to { id == 3 },
            "If the player says they are a \"Nobody\":" to { id == 4 },
        )
    }

    private suspend fun ProtectedAccess.talkTimfraku(npc: Npc) {
        when {
            player.isQuestComplete(TAI_BWO_WANNAI_TRIO) ->
                section(npc, "Talking to Timfraku after starting the quest", SimpleQuestHooks(conditions = identityHooks()))
            has(PAID) -> completeQuest(TAI_BWO_WANNAI_TRIO)
            stage >= ALL_HOME -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions = identityHooks(),
                        boxes =
                            mapOf(
                                "Timfraku hands you a big stack of gold coins." to {
                                    if (!has(PAID)) {
                                        give(TbwtObjs.coins, REWARD_COINS)
                                        mark(PAID)
                                    }
                                    false
                                }
                            ),
                    )
                section(npc, "Talking to Timfraku after getting all three sons home", hooks)
                if (has(PAID)) completeQuest(TAI_BWO_WANNAI_TRIO)
            }
            stage >= STARTED ->
                section(npc, "Talking to Timfraku after starting the quest", SimpleQuestHooks(conditions = identityHooks()))
            !player.isQuestComplete(JUNGLE_POTION) ->
                // Authored line (not Wiki): the quest's requirement.
                mes("You need to have completed Jungle Potion to help Timfraku.")
            else -> {
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(
                                "I am a roving adventurer." to { setIdentity(1) },
                                "I am a travelling explorer." to { setIdentity(2) },
                                "I am a wandering wayfarer." to { setIdentity(3) },
                                "Who me? Oh I'm just a nobody." to { setIdentity(4) },
                                "Yes." to { advance(STARTED) },
                                "No." to { mark(REFUSED_START) },
                            )
                    )
                val title =
                    if (has(REFUSED_START)) {
                        "If the player refused to start the quest and talks to Timfraku again"
                    } else {
                        "Talking to Timfraku"
                    }
                section(npc, title, hooks)
            }
        }
    }

    private fun ProtectedAccess.setIdentity(id: Int) = player.setField(IDENTITY, IDENTITY_BITS, id)

    /* Lubufu */

    private suspend fun ProtectedAccess.talkLubufu(npc: Npc) {
        val count = player.field(KARAMBWANJI, KARAMBWANJI_BITS)
        when {
            stage >= TAUGHT || player.isQuestComplete(TAI_BWO_WANNAI_TRIO) -> lostVessel(npc)
            count >= KARAMBWANJI_NEEDED -> {
                var yes = false
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(
                                "Yes!" to {
                                    yes = true
                                    becomeApprentice()
                                },
                                "No." to { mark(LUBUFU_REFUSED) },
                            )
                    )
                if (has(LUBUFU_REFUSED)) {
                    section(npc, "Talking to Lubufu after refusing to be his apprentice", hooks)
                    if (yes) {
                        runQuestBranch(npc, dialogue, "Talking to Lubufu after delivering raw Karambwanji", "Yes!")
                    }
                } else {
                    section(npc, "Talking to Lubufu after delivering raw Karambwanji", hooks)
                }
            }
            has(BAIT_ASKED) -> deliverKarambwanji(npc, count)
            has(LUBUFU_MET) -> {
                val hooks =
                    SimpleQuestHooks(
                        options =
                            mapOf(
                                "You sound like you could do with the help." to {
                                    if (stage >= STARTED) mark(BAIT_ASKED)
                                }
                            )
                    )
                section(npc, "Talking to Lubufu again", hooks)
            }
            else -> {
                section(npc, "Talking to Lubufu")
                mark(LUBUFU_MET)
            }
        }
    }

    private fun ProtectedAccess.becomeApprentice() {
        if (stage >= TAUGHT) return
        give(TbwtObjs.vessel)
        advance(TAUGHT)
        recompute()
    }

    private suspend fun ProtectedAccess.deliverKarambwanji(npc: Npc, delivered: Int) {
        val held = invTotal(inv, TbwtObjs.raw_karambwanji)
        if (held == 0) {
            startDialogue(npc) {
                chatNpc(neutral, "Have you retrieved the Karambwanji yet?")
                chatPlayer(neutral, "No, I'm still fishing for them.")
                chatNpc(neutral, "Well hurry! My reserves are beginning to run low.")
            }
            return
        }
        val needed = KARAMBWANJI_NEEDED - delivered
        val given = minOf(held, needed)
        val remaining = needed - given
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has all 20 raw Karambwanji:" to { delivered == 0 && given == needed },
                        "If the player has some raw Karambwanji:" to { remaining > 0 },
                        "If the player has the rest of the Karambwanji:" to { delivered > 0 && remaining == 0 },
                    ),
                boxes =
                    mapOf(
                        "You hand Lubufu 20 raw Karambwanji." to {
                            handKarambwanji(given)
                            false
                        },
                        "You hand Lubufu [#] raw Karambwanji." to {
                            handKarambwanji(given)
                            false
                        },
                    ),
                rewrites = { text ->
                    when {
                        text == "You hand Lubufu [#] raw Karambwanji." -> text.replace("[#]", given.toString())
                        text.contains("[#]") && remaining == 0 -> text.replace("[#]", needed.toString())
                        else -> text.replace("[#]", remaining.toString())
                    }
                },
            )
        section(npc, "Delivering raw Karambwanji to Lubufu", hooks)
    }

    private fun ProtectedAccess.handKarambwanji(count: Int) {
        if (count <= 0 || invDel(inv, TbwtObjs.raw_karambwanji, count).failure) return
        val total = player.field(KARAMBWANJI, KARAMBWANJI_BITS) + count
        player.setField(KARAMBWANJI, KARAMBWANJI_BITS, minOf(total, KARAMBWANJI_NEEDED))
    }

    private fun ProtectedAccess.hasVessel(): Boolean = carries(TbwtObjs.vessel) || carries(TbwtObjs.vessel_loaded)

    private suspend fun ProtectedAccess.lostVessel(npc: Npc) {
        var lost = false
        var handled = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "... a shark ate it!" to { lost = true },
                        "... a Karambwan stole it!" to { lost = true },
                        "... it floated away!" to { lost = true },
                    ),
                boxes =
                    mapOf(
                        "Lubufu hands you a Karambwan vessel." to {
                            handled = true
                            if (hasVessel()) {
                                true
                            } else {
                                give(TbwtObjs.vessel)
                                false
                            }
                        }
                    ),
            )
        section(npc, "Subsequent dialogue with Lubufu", hooks)
        // The shark / Karambwan excuses end on "continue" in the transcript; he hands one over too.
        if (lost && !handled && !hasVessel()) {
            give(TbwtObjs.vessel)
            mes("Lubufu hands you a Karambwan vessel.")
        } else if (lost && hasVessel() && !handled) {
            mes("You already have a Karambwan vessel.") // Authored (not Wiki).
        }
    }

    /* Tiadeche */

    private fun ProtectedAccess.karambwanChain(): Boolean =
        carries(TbwtObjs.raw_karambwan) ||
            carries(TbwtObjs.poison_karambwan) ||
            carries(TbwtObjs.poison_paste) ||
            TbwtObjs.kp_spears.any { carries(it) }

    private suspend fun ProtectedAccess.talkTiadeche(npc: Npc) {
        when {
            !has(TIADECHE_CATCH) -> section(npc, "Talking to Tiadeche")
            has(TIADECHE_DONE) ->
                startDialogue(npc) {
                    chatNpc(
                        neutral,
                        "I will meet you in the village once you have helped my brothers and spoken to my father.",
                    )
                }
            else -> {
                // Softlock guard: he has caught another for a player who lost or burnt theirs.
                val another = !has(TAMAYU_DONE) && player.field(SPEAR, SPEAR_BITS) != SPEAR_KP && !karambwanChain()
                if (another) give(TbwtObjs.raw_karambwan)
                val manual = carries(TbwtObjs.crafting_manual)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has not received (another?) Karambwan" to { another },
                                "If the player has done so:" to { has(MANUAL_GIVEN) },
                                "If the player does not have the crafting manual:" to { !manual },
                                "If the player has the crafting manual:" to { manual },
                                "If the player has not done so:" to { !has(MANUAL_GIVEN) },
                            ),
                        boxes =
                            mapOf(
                                "You hand over the crafting manual to Tiadeche." to {
                                    if (invDel(inv, TbwtObjs.crafting_manual, 1).success) {
                                        mark(TIADECHE_DONE)
                                        recompute()
                                    }
                                    false
                                }
                            ),
                    )
                section(npc, "Subsequent dialogue with Tiadeche", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.tiadecheItem(npc: Npc, item: Int) {
        if (has(TIADECHE_CATCH) || stage < STARTED) {
            talkTiadeche(npc)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player uses a raw, cooked, or poisoned Karambwan on Tiadeche:" to { item == ITEM_KARAMBWAN },
                        "If the player gives Tiadeche an empty Karambwan vessel:" to { item == ITEM_VESSEL },
                        "If the player gives Tiadeche a raw Karambwanji:" to { item == ITEM_KARAMBWANJI },
                        "If the player gives Tiadeche a Karambwan vessel with a raw Karambwanji inside:" to {
                            item == ITEM_LOADED
                        },
                    ),
                boxes =
                    mapOf(
                        "You hand over the Karambwan vessel to Tiadeche." to {
                            if (item == ITEM_LOADED && invDel(inv, TbwtObjs.vessel_loaded, 1).success) {
                                mark(TIADECHE_CATCH)
                            }
                            false
                        },
                        "You hand over a raw Karambwanji to Tiadeche." to {
                            invDel(inv, TbwtObjs.raw_karambwanji, 1)
                            false
                        },
                        "Tiadeche hands you his first catch!" to {
                            give(TbwtObjs.raw_karambwan)
                            false
                        },
                    ),
            )
        section(npc, "Talking to Tiadeche again", hooks)
    }

    /* Tamayu */

    private suspend fun ProtectedAccess.talkTamayu(npc: Npc) {
        when {
            has(TAMAYU_DONE) -> section(npc, "Talking to Tamayu after his triumph against the Shaikahan")
            !has(TAMAYU_MET) -> {
                section(npc, "Talking to Tamayu")
                mark(TAMAYU_MET)
            }
            !has(TAMAYU_HUNTED) -> {
                var follow = false
                val tellMore =
                    rng.pick(
                        "A source of indescribable evil. A beast more foul than your dreams could imagine. " +
                            "It hunts the jungle for anything foolish enough to cross its path.",
                        "It stands a full man's length in height. Its fore claws are sharp enough to rip " +
                            "through metal. In its eyes glow the embers of death.",
                    )
                section(npc, "Talking to Tamayu again", FirstHuntHooks(tellMore) { follow = true })
                if (follow) mark(TAMAYU_HUNTED)
            }
            else -> {
                var hunt = false
                val hooks =
                    SimpleQuestHooks(options = mapOf("Take me on your next hunt for the Shaikahan." to { hunt = true }))
                section(npc, "Subsequent dialogue with Tamayu", hooks)
                if (hunt) hunt(npc)
            }
        }
    }

    /** "Tell me more" picks one of its two Wiki lines; the first hunt is narrated in a box. */
    private class FirstHuntHooks(private val tellMore: String, private val onFollow: () -> Unit) :
        QuestDialogueHooks {
        override suspend fun Dialogue.onOption(label: String) {
            if (label == "Yes") onFollow()
        }

        override suspend fun Dialogue.onAction(tag: String) {
            if (tag == HUNT_ACTION) mesbox(tag)
        }

        override fun rewrite(text: String): String = if (text.startsWith(TELL_MORE_PREFIX)) tellMore else text
    }

    private suspend fun ProtectedAccess.hunt(npc: Npc) {
        val kind = player.field(SPEAR, SPEAR_BITS)
        val agile = player.field(DOSES, DOSES_BITS) >= FULL_DOSES
        // Authored line (not Wiki): the hunt cutscene is narrated.
        mesbox("You follow Tamayu into the jungle as he hunts the Shaikahan...")
        val outcome =
            when {
                agile && kind == SPEAR_KP ->
                    "If the player brings a poisoned Karambwan spear to Tamayu and joins the hunt:"
                agile && kind == SPEAR_POISON -> "If the player brings a poisoned spear to Tamayu and joins the hunt:"
                agile && kind == SPEAR_PLAIN ->
                    "If the player has brought a better spear and a four-dose Agility potion and wants to join the hunt again:"
                else -> null
            }
        if (outcome != null) {
            tamayuItems(npc, mapOf(outcome to { true }))
        } else {
            // Transcript lines for the hunts without both the potion and the spear.
            val lines =
                when {
                    kind != SPEAR_NONE ->
                        listOf(
                            "Shaikhan is at present far too agile for me!",
                            "I can do enough damage ...",
                            "... but it seems to be healing too quickly!",
                        )
                    agile ->
                        listOf(
                            "I am beginning to match Shaikahan's agility!",
                            "I cannot do enough damage ...",
                            "... and it seems to be healing too quickly!",
                        )
                    else ->
                        listOf(
                            "Shaikhan is at present far too agile for me!",
                            "I cannot do enough damage with this spear...",
                            "... and it seems to be healing too quickly!",
                        )
                }
            startDialogue(npc) { for (line in lines) chatNpc(neutral, line) }
        }
        if (agile && kind == SPEAR_KP && !has(TAMAYU_DONE)) {
            mark(TAMAYU_DONE)
            recompute()
            // Authored line (not Wiki).
            mesbox("Tamayu slays the Shaikahan!")
        }
    }

    private suspend fun ProtectedAccess.tamayuItems(
        npc: Npc,
        conditions: Map<String, () -> Boolean>,
        boxes: Map<String, suspend () -> Boolean> = emptyMap(),
    ) {
        section(npc, "Using several items on Tamayu", SimpleQuestHooks(conditions = conditions, boxes = boxes))
    }

    private fun ProtectedAccess.helpedTamayu(): Boolean =
        player.field(SPEAR, SPEAR_BITS) != SPEAR_NONE || player.field(DOSES, DOSES_BITS) > 0

    private suspend fun ProtectedAccess.tamayuCorpse(npc: Npc) {
        if (!has(TAMAYU_MET)) {
            talkTamayu(npc)
            return
        }
        val helped = helpedTamayu() || has(TAMAYU_DONE)
        var skinned = false
        tamayuItems(
            npc,
            mapOf(
                "If the player uses the stuffed monkey or monkey corpse on Tamayu before helping him with his hunt:" to
                    { !helped },
                "If the player uses the monkey corpse on Tamayu after helping him (e.g. having given him a spear and an agility potion), even before he kills the Shaikahan:" to
                    { helped },
            ),
            mapOf(
                "You hand over the monkey corpse to Tamayu." to {
                    skinned = invDel(inv, TbwtObjs.monkey_corpse, 1).success
                    false
                },
                "Tamayu hands you some monkey skin and bones." to {
                    if (skinned) {
                        give(TbwtObjs.monkey_skin)
                        give(TbwtObjs.monkey_bones)
                    }
                    false
                },
            ),
        )
    }

    private suspend fun ProtectedAccess.tamayuSpear(npc: Npc, spear: ObjType, kind: Int) {
        if (!has(TAMAYU_MET) || has(TAMAYU_DONE)) {
            talkTamayu(npc)
            return
        }
        if (player.field(SPEAR, SPEAR_BITS) >= kind) {
            mes("Tamayu already has a spear at least as good as that one.") // Authored (not Wiki).
            return
        }
        tamayuItems(
            npc,
            mapOf("If the player uses an iron or better spear on Tamayu:" to { true }),
            mapOf(
                "You hand the spear to Tamayu." to {
                    if (invDel(inv, spear, 1).success) player.setField(SPEAR, SPEAR_BITS, kind)
                    false
                }
            ),
        )
    }

    private suspend fun ProtectedAccess.tamayuPotion(npc: Npc, potion: ObjType, doses: Int) {
        if (!has(TAMAYU_MET) || has(TAMAYU_DONE)) {
            talkTamayu(npc)
            return
        }
        val before = player.field(DOSES, DOSES_BITS)
        if (before >= FULL_DOSES) {
            mes("Tamayu has had enough agility potion.") // Authored (not Wiki).
            return
        }
        val total = minOf(before + doses, FULL_DOSES)
        tamayuItems(
            npc,
            mapOf(
                "If the player hands a partially full agility potion on Tamayu after handing the better spear:" to
                    { true },
                "If the Agility potion has one dose:" to { total == 1 },
                "If the Agility potion has two doses:" to { total == 2 },
                "If the Agility potion has three doses:" to { total == 3 },
                "If the Agility potion has four doses:" to { total >= FULL_DOSES },
            ),
            mapOf(
                "You hand over the agility potion to Tamayu." to {
                    if (invDel(inv, potion, 1).success) player.setField(DOSES, DOSES_BITS, total)
                    false
                }
            ),
        )
    }

    /* Tinsay */

    private suspend fun ProtectedAccess.talkTinsay(npc: Npc) {
        when {
            !has(TINSAY_MET) -> {
                section(npc, "Talking to Tinsay")
                mark(TINSAY_MET)
            }
            has(TINSAY_DONE) -> section(npc, "Subsequent dialogue with Tinsay")
            has(TINSAY_SANDWICH) -> tinsayBones(npc)
            has(TINSAY_RUM) -> {
                val sandwich = carries(TbwtObjs.sandwich)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the seaweed sandwich:" to { sandwich },
                                "If the player does not have the monkey skin sandwich:" to { !sandwich },
                            ),
                        boxes =
                            mapOf(
                                "You hand Tinsay the seaweed in monkey skin sandwich." to {
                                    if (invDel(inv, TbwtObjs.sandwich, 1).success) mark(TINSAY_SANDWICH)
                                    false
                                }
                            ),
                    )
                section(npc, "Talking to Tinsay again with his second request", hooks)
            }
            else -> {
                val sliced = carries(TbwtObjs.sliced_banana_rum)
                val whole = !sliced && carries(TbwtObjs.banana_rum)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the Karamjan rum with a whole banana inside:" to { whole },
                                "If the player has the Karamjan rum with slices of banana inside it:" to { sliced },
                                "If the player doesn't have the rum:" to { !sliced && !whole },
                            ),
                        boxes =
                            mapOf(
                                "You hand Tinsay the sliced bananas in Karamjan rum." to {
                                    if (invDel(inv, TbwtObjs.sliced_banana_rum, 1).success) mark(TINSAY_RUM)
                                    false
                                }
                            ),
                        rewrites = { insanity(it) },
                    )
                section(npc, "Talking to Tinsay again", hooks)
            }
        }
    }

    /** "Just how insane are you?" picks one of the Wiki's four scales and a number (Authored). */
    private fun ProtectedAccess.insanity(text: String): String {
        if (!text.startsWith("On a scale of 1 to")) return text
        val word = rng.pick("barmy", "kooky", "mad", "loopy")
        return "On a scale of 1 to $word , I'm about ${rng.of(1, 10)}."
    }

    private suspend fun ProtectedAccess.tinsayBones(npc: Npc) {
        if (carries(TbwtObjs.marinated_burnt_bones)) {
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf("If the player gives him cooked and marinated Jogre bones as requested:" to { true }),
                    boxes =
                        mapOf(
                            "You hand Tinsay the burnt Jogre bones marinated in Karambwanji." to {
                                if (invDel(inv, TbwtObjs.marinated_burnt_bones, 1).success) {
                                    mark(TINSAY_DONE)
                                    recompute()
                                }
                                false
                            }
                        ),
                )
            section(npc, "Giving Tinsay the perfect Jogre bones", hooks)
            return
        }
        // Exactly one wrong-bones branch, the "best" one carried.
        val held =
            when {
                carries(TbwtObjs.burnt_bones_raw_paste) -> TbwtObjs.burnt_bones_raw_paste
                carries(TbwtObjs.marinated_bones) -> TbwtObjs.marinated_bones
                carries(TbwtObjs.bones_raw_paste) -> TbwtObjs.bones_raw_paste
                carries(TbwtObjs.burnt_jogre_bones) -> TbwtObjs.burnt_jogre_bones
                carries(TbwtObjs.jogre_bones) -> TbwtObjs.jogre_bones
                else -> null
            }
        val take: suspend () -> Boolean = {
            if (held != null) invDel(inv, held, 1)
            false
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player gives him regular Jogre bones:" to { held == TbwtObjs.jogre_bones },
                        "If the player gives him uncooked Jogre bones smothered with raw Karambwanji:" to
                            { held == TbwtObjs.bones_raw_paste },
                        "If the player gives him burnt Jogre bones:" to { held == TbwtObjs.burnt_jogre_bones },
                        "If the player gives him burnt Jogre bones with raw Karambwanji paste:" to
                            { held == TbwtObjs.burnt_bones_raw_paste },
                        "If the player gives him cooked and marinated Jogre bones but was not burnt first:" to
                            { held == TbwtObjs.marinated_bones },
                    ),
                boxes =
                    mapOf(
                        "You hand Tinsay the Jogre Bones in raw Karambwanji paste." to take,
                        "You hand Tinsay the burnt Jogre Bones in raw Karambwanji paste." to take,
                        "You hand Tinsay the Jogre Bones marinated in Karambwanji." to take,
                    ),
            )
        section(npc, "Talking to Tinsay again with his third request", hooks)
    }

    private suspend fun ProtectedAccess.tinsayVessel(npc: Npc, vessel: ObjType) {
        if (!has(TINSAY_MET)) {
            talkTinsay(npc)
            return
        }
        val request = "Your brother Tiadeche has sent me. He asks you to study this vessel and produce crafting instructions."
        if (!has(TINSAY_SANDWICH)) {
            startDialogue(npc) {
                chatPlayer(neutral, request)
                chatNpc(neutral, "You first help me, then I'll help you.")
            }
            return
        }
        if (has(TIADECHE_DONE) || carries(TbwtObjs.crafting_manual)) {
            mes("Tinsay has already sketched you a crafting manual.") // Authored (not Wiki).
            return
        }
        if (invDel(inv, vessel, 1).failure) return
        give(TbwtObjs.crafting_manual)
        mark(MANUAL_GIVEN)
        startDialogue(npc) {
            chatPlayer(neutral, request)
            chatNpc(neutral, "Very well - hand it over.")
            mesbox("You hand over the Karambwan vessel to Tinsay.")
            mesbox("Tinsay studies it for a moment then quickly sketches some crafting instructions.")
            mesbox("Tinsay hands you a Karambwan vessel crafting manual.")
            chatNpc(neutral, "I hope that's what he wanted.")
        }
    }

    /* The brothers at home (after the quest) */

    private fun ProtectedAccess.busy(npc: Npc): Boolean {
        if (player.isQuestComplete(TAI_BWO_WANNAI_TRIO)) return false
        mes("${npc.name} is too busy to talk right now.") // Authored (not Wiki).
        return true
    }

    private suspend fun ProtectedAccess.homeTiadeche(npc: Npc) {
        if (busy(npc)) return
        if (!has(REWARD_FISHING)) {
            mark(REWARD_FISHING)
            xp(stats.fishing, BROTHER_XP)
            section(npc, "Talking to Tiadeche (2)")
            return
        }
        var buy = false
        section(npc, "Subsequent dialogue with Tiadeche (2)", SimpleQuestHooks(options = mapOf("Yes." to { buy = true })))
        if (buy) opNpc3(npc)
    }

    private suspend fun ProtectedAccess.homeTinsay(npc: Npc) {
        if (busy(npc)) return
        if (!has(REWARD_COOKING)) {
            mark(REWARD_COOKING)
            xp(stats.cooking, BROTHER_XP)
            section(npc, "Talking to Tinsay (2)")
            return
        }
        section(npc, "Subsequent dialogue with Tinsay (2)")
    }

    private suspend fun ProtectedAccess.homeTamayu(npc: Npc) {
        if (busy(npc)) return
        if (!has(REWARD_TAMAYU)) {
            mark(REWARD_TAMAYU)
            xp(stats.attack, TAMAYU_XP)
            xp(stats.strength, TAMAYU_XP)
            give(TbwtObjs.rune_spear_kp)
            section(npc, "Talking to Tamayu (2)")
            return
        }
        var buy = false
        section(npc, "Subsequent dialogue with Tamayu (2)", SimpleQuestHooks(options = mapOf("Yes." to { buy = true })))
        if (buy) opNpc3(npc)
    }

    /* Item-on-item: vessel, banana rum, sandwich, pastes, spears, jogre bones */

    private fun ScriptContext.bindCrafting() {
        onOpHeldU(TbwtObjs.vessel, TbwtObjs.raw_karambwanji) {
            invDel(inv, TbwtObjs.raw_karambwanji, 1)
            invReplace(inv, TbwtObjs.vessel, 1, TbwtObjs.vessel_loaded)
            mes("You load the vessel with karambwanji.") // Authored (not Wiki).
        }
        onOpHeldU(TbwtObjs.knife, TbwtObjs.banana) {
            invReplace(inv, TbwtObjs.banana, 1, TbwtObjs.sliced_banana)
            mes("You slice the banana.") // Authored (not Wiki).
        }
        onOpHeldU(TbwtObjs.sliced_banana, TbwtObjs.karamja_rum) {
            invDel(inv, TbwtObjs.sliced_banana, 1)
            invReplace(inv, TbwtObjs.karamja_rum, 1, TbwtObjs.sliced_banana_rum)
            mes("You add the sliced banana to the Karamjan rum.") // Authored (not Wiki).
        }
        onOpHeldU(TbwtObjs.banana, TbwtObjs.karamja_rum) {
            invDel(inv, TbwtObjs.banana, 1)
            invReplace(inv, TbwtObjs.karamja_rum, 1, TbwtObjs.banana_rum)
            mes("You stuff the banana into the neck of the bottle. You begin to wonder why.")
        }
        onOpHeldU(TbwtObjs.seaweed, TbwtObjs.monkey_skin) {
            invDel(inv, TbwtObjs.seaweed, 1)
            invReplace(inv, TbwtObjs.monkey_skin, 1, TbwtObjs.sandwich)
            mes("You wrap the seaweed in the monkey skin to make a seaweed sandwich.") // Authored (not Wiki).
        }
        onOpHeldU(TbwtObjs.knife, TbwtObjs.monkey_corpse) { mes("You don't know how to skin a monkey ... yet.") }
        onOpHeldU(TbwtObjs.pestle, TbwtObjs.raw_karambwanji) {
            invDel(inv, TbwtObjs.raw_karambwanji, 1)
            give(TbwtObjs.raw_karambwanji_paste)
            mes("You grind the raw Karambwanji to form a sticky paste.")
        }
        onOpHeldU(TbwtObjs.pestle, TbwtObjs.poison_karambwan) {
            invReplace(inv, TbwtObjs.poison_karambwan, 1, TbwtObjs.poison_paste)
            mes("You grind the poison karambwan into a paste.") // Authored (not Wiki).
        }
        for ((spear, kp) in TbwtObjs.spear_to_kp) {
            onOpHeldU(TbwtObjs.poison_paste, spear) {
                invDel(inv, TbwtObjs.poison_paste, 1)
                invReplace(inv, spear, 1, kp)
                mes("You coat the spear with the karambwan paste.") // Authored (not Wiki).
            }
            for (karambwan in listOf(TbwtObjs.raw_karambwan, TbwtObjs.poison_karambwan)) {
                onOpHeldU(karambwan, spear) {
                    pasteMistake("If the player uses a raw or poorly cooked Karambwan on a spear:")
                }
            }
        }
        onOpHeldU(TbwtObjs.raw_karambwanji_paste, TbwtObjs.jogre_bones) {
            smother(TbwtObjs.jogre_bones, TbwtObjs.bones_raw_paste)
        }
        onOpHeldU(TbwtObjs.raw_karambwanji_paste, TbwtObjs.burnt_jogre_bones) {
            smother(TbwtObjs.burnt_jogre_bones, TbwtObjs.burnt_bones_raw_paste)
        }
        for (bones in listOf(TbwtObjs.jogre_bones, TbwtObjs.burnt_jogre_bones)) {
            onOpHeldU(TbwtObjs.poison_paste, bones) {
                pasteMistake("If the player uses poisoned or regular Karambwan paste on the bones:")
            }
        }
        onOpHeldU(TbwtObjs.tinderbox, TbwtObjs.jogre_bones) {
            if (stat(stats.firemaking) < FIREMAKING_BONES) {
                mesbox(
                    "You need a firemaking level of 30 to burn Jogre bones. You could find some other way of burning them."
                )
                return@onOpHeldU
            }
            invReplace(inv, TbwtObjs.jogre_bones, 1, TbwtObjs.burnt_jogre_bones)
            mes("The jogre bones blacken in the flames.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.pasteMistake(condition: String) {
        section(null, "Making the burnt/marinated Jogre bones", SimpleQuestHooks(conditions = mapOf(condition to { true })))
    }

    private fun ProtectedAccess.smother(bones: ObjType, into: ObjType) {
        if (invDel(inv, TbwtObjs.raw_karambwanji_paste, 1).failure) return
        invReplace(inv, bones, 1, into)
        mes("You smother the raw Karambwanji paste over the Jogre bones.")
    }

    /** Fires / ranges (as the Cooking module finds them) and furnaces, for the jogre bones. */
    private fun ScriptContext.bindHeat() {
        for (type in locTypes.values) {
            if (type.isFurnace()) {
                onOpLocU(type, TbwtObjs.jogre_bones) {
                    invReplace(inv, TbwtObjs.jogre_bones, 1, TbwtObjs.burnt_jogre_bones)
                    mes("You burn the jogre bones in the furnace.") // Authored (not Wiki).
                }
                continue
            }
            if (!type.isHeatSource()) continue
            onOpLocU(type, TbwtObjs.jogre_bones) { mes("You heat the bones, but they don't look any different.") }
            onOpLocU(type, TbwtObjs.burnt_bones_raw_paste) {
                marinate(TbwtObjs.burnt_bones_raw_paste, TbwtObjs.marinated_burnt_bones)
            }
            onOpLocU(type, TbwtObjs.bones_raw_paste) { marinate(TbwtObjs.bones_raw_paste, TbwtObjs.marinated_bones) }
            for (done in listOf(TbwtObjs.marinated_burnt_bones, TbwtObjs.marinated_bones)) {
                onOpLocU(type, done) { mes("The jogre bones are already marinated! They don't need any more cooking.") }
            }
            onOpLocU(type, TbwtObjs.raw_karambwanji_paste) { mes("You can't cook that.") }
        }
    }

    private fun ProtectedAccess.marinate(from: ObjType, into: ObjType) {
        if (player.cookingLvl < COOKING_MARINATE) {
            mes("You need a Cooking level of $COOKING_MARINATE to marinate these.") // Authored (not Wiki).
            return
        }
        invReplace(inv, from, 1, into)
        mes("You marinate the burnt bones perfectly.")
    }

    private fun UnpackedLocType.isHeatSource(): Boolean =
        name.lowercase() in HEAT_NAMES || op.any { it?.trim().equals("Cook", ignoreCase = true) }

    private fun UnpackedLocType.isFurnace(): Boolean {
        if (!name.contains("furnace", ignoreCase = true)) return false
        val exact = name.equals("Furnace", ignoreCase = true)
        return op.any { it.equals("Smelt", ignoreCase = true) || (exact && it.equals("Use", ignoreCase = true)) }
    }

    /* Locs */

    private fun ProtectedAccess.climbRocks(loc: BoundLocInfo) {
        if (stat(stats.agility) < AGILITY_ROCKS) {
            mes("You need an Agility level of $AGILITY_ROCKS to climb these rocks.") // Authored (not Wiki).
            return
        }
        // The slide spans x 2792-2794 (Cairn Isle to the west); land on the far side (Ryan-test).
        val east = player.coords.x >= ROCKS_EAST_FACE
        val dest = CoordGrid(if (east) ROCKS_WEST_LANDING else ROCKS_EAST_LANDING, loc.coords.z, 0)
        mes("You climb across the rocks.") // Authored (not Wiki).
        telejump(dest)
    }

    private suspend fun ProtectedAccess.openHut(door: BoundLocInfo) {
        if (!player.isQuestComplete(TAI_BWO_WANNAI_TRIO)) {
            section(null, "Attempting to enter any of the brother's huts in Tai Bwo Wannai Village")
            return
        }
        passThroughWall(door)
    }

    /** Karambwan fishing needs Lubufu's lesson (the Fishing module's method does the rest). */
    private inner class KarambwanSpots : OpInterceptors.NpcOp {
        private val spots by lazy { TbwtNpcs.karambwan_spots.map { it.id }.toSet() }

        override fun claims(player: Player, npc: Npc, op: InteractionOp): Boolean =
            op == InteractionOp.Op1 &&
                npc.id in spots &&
                player.questState(TAI_BWO_WANNAI_TRIO) < TAUGHT &&
                !player.isQuestComplete(TAI_BWO_WANNAI_TRIO)

        override suspend fun ProtectedAccess.run(npc: Npc, op: InteractionOp) {
            mes("You don't know how to catch karambwan. Perhaps a local fisherman could teach you.") // Authored.
        }
    }

    companion object {
        const val STARTED = 1
        const val TAUGHT = 2
        const val ALL_HOME = 5
        const val COMPLETE = 6

        /* Bits of tbwt_tiadeche (TbwtVarps.progress). */
        private const val KARAMBWANJI = 0
        private const val KARAMBWANJI_BITS = 5
        private const val BAIT_ASKED = 5
        private const val IDENTITY = 6
        private const val IDENTITY_BITS = 3
        private const val TIADECHE_CATCH = 9
        private const val TIADECHE_DONE = 10
        private const val SPEAR = 11
        private const val SPEAR_BITS = 2
        private const val DOSES = 13
        private const val DOSES_BITS = 3
        private const val TAMAYU_MET = 16
        private const val TAMAYU_HUNTED = 17
        private const val TAMAYU_DONE = 18
        private const val TINSAY_MET = 19
        private const val TINSAY_RUM = 20
        private const val TINSAY_SANDWICH = 21
        private const val TINSAY_DONE = 22
        private const val MANUAL_GIVEN = 23
        private const val LUBUFU_MET = 24
        private const val LUBUFU_REFUSED = 25
        private const val REWARD_FISHING = 26
        private const val REWARD_COOKING = 27
        private const val REWARD_TAMAYU = 28
        private const val REFUSED_START = 29
        private const val PAID = 30

        private const val SPEAR_NONE = 0
        private const val SPEAR_PLAIN = 1
        private const val SPEAR_POISON = 2
        private const val SPEAR_KP = 3

        private const val ITEM_KARAMBWAN = 0
        private const val ITEM_VESSEL = 1
        private const val ITEM_LOADED = 2
        private const val ITEM_KARAMBWANJI = 3

        private const val KARAMBWANJI_NEEDED = 20
        private const val FULL_DOSES = 4
        private const val REWARD_COINS = 2000
        private const val BROTHER_XP = 5000.0
        private const val TAMAYU_XP = 2500.0
        private const val POISON_KARAMBWAN_XP = 80.0
        private const val FIREMAKING_BONES = 30
        private const val COOKING_MARINATE = 30
        private const val AGILITY_ROCKS = 15

        /** Authored tiles (Ryan-test): the rock slide's faces at x 2792 / 2794. */
        private const val ROCKS_EAST_FACE = 2794
        private const val ROCKS_WEST_LANDING = 2791
        private const val ROCKS_EAST_LANDING = 2795

        private const val HUNT_ACTION = "Tamayu attempts to defeat the Shaikahan with his bronze spear..."
        private val TELL_MORE_PREFIX = "A source of indescribable evil."

        private val HEAT_NAMES = setOf("range", "cooking range", "stove", "clay oven", "fire")

        private fun Player.progress(): Int = vars[TbwtVarps.progress]

        private fun Player.flag(bit: Int): Boolean = (progress() shr bit) and 1 == 1

        private fun Player.setFlag(bit: Int) =
            VarPlayerIntMapSetter.set(this, TbwtVarps.progress, progress() or (1 shl bit))

        private fun Player.field(shift: Int, bits: Int): Int = (progress() shr shift) and ((1 shl bits) - 1)

        private fun Player.setField(shift: Int, bits: Int, value: Int) {
            val mask = ((1 shl bits) - 1) shl shift
            val next = (progress() and mask.inv()) or ((value shl shift) and mask)
            VarPlayerIntMapSetter.set(this, TbwtVarps.progress, next)
        }

        val TAI_BWO_WANNAI_TRIO =
            QuestDef(
                name = "Tai Bwo Wannai Trio",
                varp = { TbwtVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "2,000 Coins",
                        "5,000 Fishing XP (Tiadeche)",
                        "5,000 Cooking XP (Tinsay)",
                        "2,500 Attack & Strength XP (Tamayu)",
                        "Rune spear(kp) (Tamayu)",
                        "Karambwan cooking (Tinsay)",
                    ),
                scrollObj = { TbwtObjs.cooked_karambwan },
            )
    }
}

// Every cache ref Tai Bwo Wannai Trio uses (re-check after cache updates).

internal object TbwtNpcs : NpcReferences() {
    val timfraku = find("tbwt_timfraku")
    val lubufu = find("tbwt_lubufu")
    /** Children of tbwt_tiadeche_multinpc_shore / _tinsay_multinpc_island / _tamayu_multinpc_jungle (0-5). */
    val tiadeche = find("tbwt_tiadeche")
    val tinsay = find("tbwt_tinsay")
    val tamayu = find("tbwt_tamayu")
    /** In the houses: tbwt_tinsay_multinpc_house (6); the other two are generic-shops spawns. */
    val tiadeche_final = find("tbwt_tiadeche_final")
    val tinsay_final = find("tbwt_tinsay_final")
    val tamayu_final = find("tbwt_tamayu_final")
    val karambwan_spots = listOf(find("0_45_48_karambwan"), find("general_karambwan"), find("lubufu_karambwan"))
}

internal object TbwtLocs : LocReferences() {
    /** Cairn Isle rock slide (2792 / 2794, 2978-2980). */
    val rock_slide = find("zqclimbingrocks")
    val bamboo_door = find("tbwt_bamboo_door")
}

internal object TbwtObjs : ObjReferences() {
    val coins = find("coins")
    val knife = find("knife")
    val pestle = find("pestle_and_mortar")
    val tinderbox = find("tinderbox")
    val banana = find("banana")
    val karamja_rum = find("karamja_rum")
    val seaweed = find("seaweed")
    val vessel = find("tbwt_karambwan_vessel")
    val vessel_loaded = find("tbwt_karambwan_vessel_loaded_with_karambwanji")
    val raw_karambwanji = find("tbwt_raw_karambwanji")
    val raw_karambwanji_paste = find("tbwt_raw_karambwanji_paste")
    val raw_karambwan = find("tbwt_raw_karambwan")
    val cooked_karambwan = find("tbwt_cooked_karambwan")
    /** "Poison karambwan" (the internal name says poorly cooked). */
    val poison_karambwan = find("tbwt_poorly_cooked_karambwan")
    /** The karambwan paste that poisons spears. */
    val poison_paste = find("tbwt_poisonous_karambwan_paste")
    val crafting_manual = find("tbwt_crafting_manual")
    val sliced_banana = find("tbwt_sliced_banana")
    val sliced_banana_rum = find("tbwt_sliced_banana_in_karamja_rum")
    val banana_rum = find("tbwt_banana_in_karamja_rum")
    val monkey_corpse = find("tbwt_monkey_corpse")
    val monkey_skin = find("tbwt_monkey_skin")
    val monkey_bones = find("mm_normal_monkey_bones")
    val sandwich = find("tbwt_seaweed_in_monkey_skin_sandwich")
    val jogre_bones = find("tbwt_jogre_bones")
    val burnt_jogre_bones = find("tbwt_burnt_jogre_bones")
    val bones_raw_paste = find("tbwt_jogre_bones_in_raw_karambwanji_paste")
    val burnt_bones_raw_paste = find("tbwt_burnt_jogre_bones_in_raw_karambwanji_paste")
    /** Burnt then marinated: what Tinsay wants. */
    val marinated_burnt_bones = find("tbwt_burnt_jogre_bones_marinated_in_karambwanji")
    /** Marinated without burning (the wrong "Marinated j' bones"). */
    val marinated_bones = find("tbwt_jogre_bones_marinated_in_karambwanji")
    val bronze_spear = find("bronze_spear")
    val iron_spear = find("iron_spear")
    val steel_spear = find("steel_spear")
    val black_spear = find("black_spear")
    val mithril_spear = find("mithril_spear")
    val adamant_spear = find("adamant_spear")
    val rune_spear = find("rune_spear")
    val dragon_spear = find("dragon_spear")
    val bronze_spear_kp = find("tbwt_bronze_spear_kp")
    val iron_spear_kp = find("tbwt_iron_spear_kp")
    val steel_spear_kp = find("tbwt_steel_spear_kp")
    val black_spear_kp = find("tbwt_black_spear_kp")
    val mithril_spear_kp = find("tbwt_mithril_spear_kp")
    val adamant_spear_kp = find("tbwt_adamant_spear_kp")
    val rune_spear_kp = find("tbwt_rune_spear_kp")
    val dragon_spear_kp = find("tbwt_dragon_spear_kp")
    val iron_spear_p = find("iron_spear_p")
    val steel_spear_p = find("steel_spear_p")
    val mithril_spear_p = find("mithril_spear_p")
    val adamant_spear_p = find("adamant_spear_p")
    val rune_spear_p = find("rune_spear_p")
    val dragon_spear_p = find("dragon_spear_p")
    val iron_spear_p1 = find("iron_spear_p+")
    val steel_spear_p1 = find("steel_spear_p+")
    val mithril_spear_p1 = find("mithril_spear_p+")
    val adamant_spear_p1 = find("adamant_spear_p+")
    val rune_spear_p1 = find("rune_spear_p+")
    val dragon_spear_p1 = find("dragon_spear_p+")
    val iron_spear_p2 = find("iron_spear_p++")
    val steel_spear_p2 = find("steel_spear_p++")
    val mithril_spear_p2 = find("mithril_spear_p++")
    val adamant_spear_p2 = find("adamant_spear_p++")
    val rune_spear_p2 = find("rune_spear_p++")
    val dragon_spear_p2 = find("dragon_spear_p++")
    val agility4 = find("4dose1agility")
    val agility3 = find("3dose1agility")
    val agility2 = find("2dose1agility")
    val agility1 = find("1dose1agility")

    val spear_to_kp
        get() =
            listOf(
                bronze_spear to bronze_spear_kp,
                iron_spear to iron_spear_kp,
                steel_spear to steel_spear_kp,
                black_spear to black_spear_kp,
                mithril_spear to mithril_spear_kp,
                adamant_spear to adamant_spear_kp,
                rune_spear to rune_spear_kp,
                dragon_spear to dragon_spear_kp,
            )

    /** Iron-or-better kp spears (the ones that work on the Shaikahan). */
    val kp_spears
        get() = listOf(iron_spear_kp, steel_spear_kp, mithril_spear_kp, adamant_spear_kp, rune_spear_kp, dragon_spear_kp)

    /** Iron-or-better spears Tamayu takes, with their kind (1 plain, 2 weapon poison, 3 kp). */
    val tamayu_spears: List<Pair<ObjType, Int>>
        get() =
            listOf(iron_spear, steel_spear, mithril_spear, adamant_spear, rune_spear, dragon_spear).map { it to 1 } +
                listOf(
                        iron_spear_p,
                        steel_spear_p,
                        mithril_spear_p,
                        adamant_spear_p,
                        rune_spear_p,
                        dragon_spear_p,
                        iron_spear_p1,
                        steel_spear_p1,
                        mithril_spear_p1,
                        adamant_spear_p1,
                        rune_spear_p1,
                        dragon_spear_p1,
                        iron_spear_p2,
                        steel_spear_p2,
                        mithril_spear_p2,
                        adamant_spear_p2,
                        rune_spear_p2,
                        dragon_spear_p2,
                    )
                    .map { it to 2 } +
                kp_spears.map { it to 3 }

    val agility_potions
        get() = listOf(agility4 to 4, agility3 to 3, agility2 to 2, agility1 to 1)

    /** Items used on Tinsay that just start his conversation. */
    val tinsay_items
        get() =
            listOf(
                sliced_banana_rum,
                banana_rum,
                sandwich,
                jogre_bones,
                burnt_jogre_bones,
                bones_raw_paste,
                burnt_bones_raw_paste,
                marinated_burnt_bones,
                marinated_bones,
            )
}

internal object TbwtVarps : VarpReferences() {
    val quest = find("tbwt_main")
    /** Spare cache varp (no varbits, not read by the client): this quest's progress bits. */
    val progress = find("tbwt_tiadeche")
}
