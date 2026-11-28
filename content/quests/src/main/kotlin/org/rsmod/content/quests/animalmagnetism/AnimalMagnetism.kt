package org.rsmod.content.quests.animalmagnetism

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.ernest.ErnestTheChicken.Companion.ERNEST
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk.register
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.ghostsahoy.GhostsAhoy.Companion.GHOSTS_AHOY
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.content.quests.restlessghost.RestlessGhost.Companion.RESTLESS_GHOST
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Animal Magnetism (OSRS Wiki). `anma_main` varbit (cache db end state 240; the cache gives Malcolm
 * his amulet from 80 and makes the undead trees choppable from 150, so our steps follow its scale):
 * - 10 Ava wants two undead chickens; 20-70 the ghost farm's quarrel (Alice and her dead husband
 *   Malcolm, in turns: love, savings, bank accounts); 73 the old crone asked for a crone-made
 *   amulet, 76 got it, 80 Malcolm has it (he sells undead chickens).
 * - 90 / 100 one / both chickens to Ava (she wants a magnet); 110 the witch's selected iron (five
 *   iron bars), hammered into a bar magnet in the Rimmington mine; 150 magnet to Ava (50 Crafting
 *   xp; she wants undead twigs); 160 Turael's blessed axe (for a mithril axe and a holy symbol).
 * - 170 twigs delivered (garbled research notes), 180 translated, 190 handed in (a pattern), 240
 *   complete: the container (pattern, hard leather, polished buttons). Rewards: 1,000 Crafting,
 *   1,000 Fletching, 1,000 Slayer and 2,500 Woodcutting xp, Ava's attractor (accumulator at 50
 *   Ranged), 1 QP.
 *
 * Malcolm sells chickens for ecto-tokens (Ectofuntus, Prayer module); Ava's devices are her
 * generic shop; the research notes are translated on their orb interface. Not here: the
 * facing-north check in the mine (the server doesn't track facing). Turael's Talk-to is the Slayer
 * module's, so the blessed-axe swap starts from using a mithril axe or holy symbol on him.
 */
class AnimalMagnetism
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(AnimalMagnetism::class.java, "animal_magnetism")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Malcolm" to AmNpcs.malcolm, "Alice" to AmNpcs.alice, "Ava" to AmNpcs.ava)
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, AmNpcs.ava, "Talk-to") { talkAva(it) }
        // Ava's Trade op is her generic shop (generic-shops shops.tsv).
        onNpcOpLabel(npcTypes, AmNpcs.alice, "Talk-to") { talkAlice(it) }
        for (malcolm in AmNpcs.malcolms) {
            onNpcOpLabel(npcTypes, malcolm, "Talk-to") { talkMalcolm(it) }
        }
        // The crone is shared with Ghosts Ahoy (QuestTalk menu while both want her).
        register(
            npcTypes,
            AmNpcs.crone,
            ANIMAL_MAGNETISM,
            wants = { state in CRONE_ASKED..AMULET_MADE },
        ) {
            talkCrone(it)
        }
        onNpcOpLabel(npcTypes, AmNpcs.witch, "Talk-to") { talkWitch(it) }
        onOpNpcU(AmNpcs.turael, AmObjs.mithril_axe) { askTurael(it.npc) }
        onOpNpcU(AmNpcs.turael, AmObjs.holy_symbol) { askTurael(it.npc) }
        onOpHeldU(AmObjs.hammer, AmObjs.selected_iron) { hammerIron() }
        onNpcOpLabel(npcTypes, AmNpcs.tree_choppable, "Chop") { chopTree() }
        onHeldOpLabel(objTypes, AmObjs.garbled_notes, "Translate") { translate() }
        for (i in 0 until ORBS) {
            onIfModalButton(AmComponents.orbs_on[i]) { toggleOrb(i) }
            onIfModalButton(AmComponents.orbs_off[i]) { toggleOrb(i) }
        }
        onOpHeldU(AmObjs.pattern, AmObjs.hard_leather) { makeContainer() }
        onOpHeldU(AmObjs.pattern, AmObjs.buttons) { makeContainer() }
        onOpHeldU(AmObjs.buttons, AmObjs.hard_leather) {
            runQuestSection(null, dialogue, NO_PATTERN)
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(ANIMAL_MAGNETISM)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(ANIMAL_MAGNETISM, value)
    }

    private fun ProtectedAccess.chickens(): Int = invTotal(inv, AmObjs.chicken)

    private fun ProtectedAccess.meetsRequirements(): Boolean =
        player.isQuestComplete(ERNEST) &&
            player.isQuestComplete(RESTLESS_GHOST) &&
            player.isQuestComplete(PRIEST_IN_PERIL) &&
            statBase(stats.slayer) >= 18 &&
            statBase(stats.crafting) >= 19 &&
            statBase(stats.ranged) >= 30 &&
            statBase(stats.woodcutting) >= 35

    /* Ava */

    private suspend fun ProtectedAccess.talkAva(npc: Npc) {
        when {
            state == 0 && !meetsRequirements() ->
                runQuestSection(npc, dialogue, "Talking to Ava without meeting the requirements")
            state == 0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "Talking to Ava", hooks)
                if (start) setState(STARTED)
            }
            state < AMULET_GIVEN -> runQuestSection(npc, dialogue, "Talking to Ava again")
            state == AMULET_GIVEN || state == ONE_CHICKEN -> handChickens(npc)
            state == BOTH_CHICKENS -> runQuestSection(npc, dialogue, AVA_BEFORE_WITCH)
            state == IRON_GIVEN && carries(AmObjs.magnet) -> {
                runQuestSection(npc, dialogue, "Handing the magnet to Ava")
                invDel(inv, AmObjs.magnet)
                statAdvance(stats.crafting, MAGNET_XP * xpMods.get(player, stats.crafting))
                setState(MAGNET_GIVEN)
            }
            state == IRON_GIVEN -> 
                runQuestSection(npc, dialogue, "Talking to Ava before making the magnet")
            state == MAGNET_GIVEN -> runQuestSection(npc, dialogue, AVA_BEFORE_AXE)
            state == BLESSED_AXE && carries(AmObjs.twigs) -> {
                runQuestSection(npc, dialogue, "Delivering the twigs")
                invDel(inv, AmObjs.twigs)
                invAddOrDrop(objRepo, AmObjs.garbled_notes)
                setState(TWIGS_GIVEN)
            }
            state == BLESSED_AXE -> runQuestSection(npc, dialogue, AVA_AFTER_AXE)
            state == TWIGS_GIVEN && carries(AmObjs.translated_notes) -> {
                runQuestSection(npc, dialogue, "Handing in the notes")
                invDel(inv, AmObjs.translated_notes)
                invAddOrDrop(objRepo, AmObjs.pattern)
                setState(NOTES_GIVEN)
            }
            state == TWIGS_GIVEN && !carries(AmObjs.garbled_notes) -> {
                runQuestSection(npc, dialogue, "Talking to Ava after losing the research notes")
                invAddOrDrop(objRepo, AmObjs.garbled_notes)
            }
            state == TWIGS_GIVEN -> runQuestSection(npc, dialogue, "Talking to Ava with the notes")
            state == NOTES_GIVEN && carries(AmObjs.container) -> deliverContainer(npc)
            state == NOTES_GIVEN && carries(AmObjs.pattern) ->
                runQuestSection(npc, dialogue, "Talking to Ava again with the pattern")
            state == NOTES_GIVEN -> {
                runQuestSection(npc, dialogue, "Talking to Ava after losing the pattern")
                invAddOrDrop(objRepo, AmObjs.pattern)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Ava again (2)")
        }
    }

    private suspend fun ProtectedAccess.handChickens(npc: Npc) {
        val wielding = invTotal(worn, AmObjs.chicken) > 0
        val have = chickens()
        when {
            wielding -> {
                var give = false
                val hooks =
                    SimpleQuestHooks(
                        options = mapOf("You can take it; it smells anyway." to { give = true })
                    )
                runQuestSection(npc, dialogue, AVA_WIELDING, hooks)
                if (give) {
                    invDel(worn, AmObjs.chicken)
                    setState(if (state == AMULET_GIVEN) ONE_CHICKEN else BOTH_CHICKENS)
                }
            }
            have == 0 && state == AMULET_GIVEN ->
                runQuestSection(npc, dialogue, "Speaking to Ava without undead chickens")
            have == 0 -> runQuestSection(npc, dialogue, AVA_ANOTHER)
            state == AMULET_GIVEN && have == 1 -> {
                runQuestSection(npc, dialogue, "Speaking to Ava with one undead chicken")
                invDel(inv, AmObjs.chicken)
                setState(ONE_CHICKEN)
            }
            else -> {
                runQuestSection(npc, dialogue, "Handing over the second chicken to Ava")
                invDel(inv, AmObjs.chicken, if (state == AMULET_GIVEN) 2 else 1)
                setState(BOTH_CHICKENS)
            }
        }
    }

    private suspend fun ProtectedAccess.deliverContainer(npc: Npc) {
        val ranged = statBase(stats.ranged) >= ACCUMULATOR_RANGED
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player has level 50 Ranged:" to { ranged })
            )
        runQuestSection(npc, dialogue, "Delivering the container", hooks)
        invDel(inv, AmObjs.container)
        invAddOrDrop(objRepo, if (ranged) AmObjs.accumulator else AmObjs.attractor)
        statAdvance(stats.crafting, 1000.0 * xpMods.get(player, stats.crafting))
        statAdvance(stats.fletching, 1000.0 * xpMods.get(player, stats.fletching))
        statAdvance(stats.slayer, 1000.0 * xpMods.get(player, stats.slayer))
        statAdvance(stats.woodcutting, 2500.0 * xpMods.get(player, stats.woodcutting))
        completeQuest(ANIMAL_MAGNETISM)
    }

    /* The ghost farm */

    private suspend fun ProtectedAccess.talkAlice(npc: Npc) {
        val section =
            when (state) {
                STARTED -> "Talking to Alice"
                LOVE -> "Talking to Alice again before talking to her husband again"
                LOVE_ANSWER -> "Talking to Alice again"
                SAVINGS -> "Talking to Alice again before talking to her husband again (2)"
                SAVINGS_ANSWER -> "Talking to Alice (2)"
                BANKS -> "Talking to Alice before obtaining the amulet"
                BANKS_ANSWER -> "Talking to Alice again (2)"
                CRONE_ASKED -> "Talking to Alice before obtaining the amulet"
                AMULET_MADE -> "Speaking to Alice"
                else -> "Speaking to Alice again"
            }
        runQuestSection(npc, dialogue, section)
        when (state) {
            STARTED -> setState(LOVE)
            LOVE_ANSWER -> setState(SAVINGS)
            SAVINGS_ANSWER -> setState(BANKS)
            BANKS_ANSWER -> setState(CRONE_ASKED)
        }
    }

    private suspend fun ProtectedAccess.talkMalcolm(npc: Npc) {
        if (invTotal(worn, AmObjs.ghostspeak) == 0 && invTotal(worn, AmObjs.ghostspeak_e) == 0) {
            startDialogue(npc) { chatNpc(sad, "Woooo wooo woooo...") }
            return
        }
        when (state) {
            LOVE -> {
                runQuestSection(npc, dialogue, "Talking to Malcolm")
                setState(LOVE_ANSWER)
            }
            LOVE_ANSWER -> runQuestSection(npc, dialogue, MALCOLM_BEFORE_ALICE)
            SAVINGS -> {
                runQuestSection(npc, dialogue, "Talking to Malcolm (2)")
                setState(SAVINGS_ANSWER)
            }
            SAVINGS_ANSWER -> runQuestSection(npc, dialogue, MALCOLM_BEFORE_ALICE_AGAIN)
            BANKS -> {
                runQuestSection(npc, dialogue, "Talking to Malcolm again")
                setState(BANKS_ANSWER)
            }
            BANKS_ANSWER,
            CRONE_ASKED -> 
                runQuestSection(npc, dialogue, "Talking to Malcolm before obtaining the amulet")
            AMULET_MADE -> {
                var give = false
                val has = carries(AmObjs.crone_amulet)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the crone-made amulet:" to { has },
                                "If the player does not have the crone-made amulet:" to { !has },
                            ),
                        options =
                            mapOf(GIVE_AMULET to { give = true }),
                    )
                runQuestSection(npc, dialogue, "Speaking to Malcolm", hooks, speakers)
                if (give && has) {
                    invDel(inv, AmObjs.crone_amulet)
                    setState(AMULET_GIVEN)
                }
            }
            0,
            STARTED -> startDialogue(npc) { chatNpc(neutral, "Talk to my wife, not me.") }
            else -> sellChickens(npc)
        }
    }

    private suspend fun ProtectedAccess.sellChickens(npc: Npc) {
        var want = 0
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Could I buy 1 chicken now?" to { want = 1 },
                        "Could I buy 2 chickens?" to { want = 2 },
                    )
            )
        runQuestSection(npc, dialogue, "Speaking to Malcolm after catching the chicken", hooks)
        if (want == 0) return
        if (invTotal(inv, AmObjs.ecto_token) < want * TOKEN_PRICE) {
            mes("You need ${want * TOKEN_PRICE} ecto-tokens. The Ectofuntus earns them.")
            return
        }
        invDel(inv, AmObjs.ecto_token, want * TOKEN_PRICE)
        invAddOrDrop(objRepo, AmObjs.chicken, want)
    }

    private suspend fun ProtectedAccess.talkCrone(npc: Npc) {
        val ghostspeak =
            carries(AmObjs.ghostspeak) || invTotal(worn, AmObjs.ghostspeak) > 0
        when {
            state < CRONE_ASKED || state > AMULET_MADE ->
                runQuestSection(npc, dialogue, CRONE_AFTER)
            state == AMULET_MADE && carries(AmObjs.crone_amulet) ->
                runQuestSection(npc, dialogue, CRONE_BEFORE_ALICE)
            !ghostspeak -> 
                runQuestSection(npc, dialogue, CRONE_NO_GHOSTSPEAK)
            inv.isFull() -> 
                runQuestSection(npc, dialogue, "Speaking to the old crone without inventory space")
            else -> {
                val ahoyDone = player.isQuestComplete(GHOSTS_AHOY)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player has completed Ghosts Ahoy:" to { ahoyDone })
                    )
                val section =
                    if (state == AMULET_MADE) CRONE_LOST else "Speaking to the old crone"
                runQuestSection(npc, dialogue, section, hooks)
                invAdd(inv, AmObjs.crone_amulet)
                setState(AMULET_MADE)
            }
        }
    }

    /* The witch, the magnet and the twigs */

    private suspend fun ProtectedAccess.talkWitch(npc: Npc) {
        val bars = invTotal(inv, AmObjs.iron_bar)
        when {
            state < BOTH_CHICKENS -> startDialogue(npc) { chatNpc(neutral, "Shoo, I'm busy.") }
            state == BOTH_CHICKENS && bars == 0 ->
                runQuestSection(npc, dialogue, "Talking to the Witch again without iron bars")
            state == BOTH_CHICKENS && bars < IRON_BARS ->
                runQuestSection(npc, dialogue, "Talking to the Witch with less than five iron bars")
            state == BOTH_CHICKENS -> {
                runQuestSection(npc, dialogue, "Talking to the Witch again")
                invDel(inv, AmObjs.iron_bar, IRON_BARS)
                invAddOrDrop(objRepo, AmObjs.selected_iron)
                setState(IRON_GIVEN)
            }
            state == IRON_GIVEN && !carries(AmObjs.selected_iron) && !carries(AmObjs.magnet) -> {
                runQuestSection(npc, dialogue, WITCH_LOST_IRON)
                invAddOrDrop(objRepo, AmObjs.selected_iron)
            }
            state == IRON_GIVEN && carries(AmObjs.magnet) ->
                runQuestSection(npc, dialogue, "Talking to the Witch after creating the magnet")
            state == IRON_GIVEN -> 
                runQuestSection(npc, dialogue, "Talking to the Witch before making the magnet")
            else -> 
                runQuestSection(npc, dialogue, "Talking to the Witch after handing over the magnet")
        }
    }

    private suspend fun ProtectedAccess.hammerIron() {
        val c = player.coords
        if (c.level != 0 || c.x !in MINE_X || c.z !in MINE_Z) {
            runQuestSection(null, dialogue, HAMMER_WRONG_PLACE)
            return
        }
        runQuestSection(null, dialogue, "Using hammer on selected iron as intended")
        invDel(inv, AmObjs.selected_iron)
        invAdd(inv, AmObjs.magnet)
    }

    private suspend fun ProtectedAccess.askTurael(npc: Npc) {
        if (state < MAGNET_GIVEN) {
            mes("Turael has no use for that.")
            return
        }
        val axe = carries(AmObjs.mithril_axe)
        val symbol = carries(AmObjs.holy_symbol)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has both holy symbol and mithril axe:" to { axe && symbol },
                        "If the player only has a mithril axe:" to { axe && !symbol },
                        "If the player only has a holy symbol:" to { symbol && !axe },
                        "If the player has neither axe nor holy symbol:" to { !axe && !symbol },
                        "If the player has a blessed axe in their inventory:" to {
                            carries(AmObjs.blessed_axe)
                        },
                        "Otherwise:" to { !carries(AmObjs.blessed_axe) },
                        "If the player does not have a mithril axe:" to { !axe },
                    )
            )
        when {
            state == MAGNET_GIVEN && !askedTurael() -> {
                runQuestBranch(npc, dialogue, "Turael", "I'm here about a quest.", hooks)
                setAskedTurael()
                if (axe && symbol) exchange()
            }
            state == MAGNET_GIVEN -> {
                runQuestBranch(npc, dialogue, TURAEL_AGAIN, TREES_AGAIN, hooks)
                if (axe && symbol) exchange()
            }
            carries(AmObjs.blessed_axe) || invTotal(worn, AmObjs.blessed_axe) > 0 ->
                mes("You already have Turael's blessed axe.")
            else -> {
                runQuestBranch(npc, dialogue, TURAEL_REPLACE, AXES_AGAIN, hooks)
                if (axe && symbol) exchange()
            }
        }
    }

    private fun ProtectedAccess.exchange() {
        invDel(inv, AmObjs.mithril_axe)
        invDel(inv, AmObjs.holy_symbol)
        invAdd(inv, AmObjs.blessed_axe)
        if (state == MAGNET_GIVEN) setState(BLESSED_AXE)
    }

    private fun ProtectedAccess.askedTurael(): Boolean =
        player.vars[AmVarps.progress] and ASKED_TURAEL != 0

    private fun ProtectedAccess.setAskedTurael() {
        val flags = player.vars[AmVarps.progress] or ASKED_TURAEL
        VarPlayerIntMapSetter.set(player, AmVarps.progress, flags)
    }

    private suspend fun ProtectedAccess.chopTree() {
        val blessed = carries(AmObjs.blessed_axe) || invTotal(worn, AmObjs.blessed_axe) > 0
        when {
            blessed && state >= MAGNET_GIVEN -> {
                if (random.of(1, CHOP_ODDS) != 1) {
                    runQuestSection(null, dialogue, "Failing to chop an undead tree")
                    return
                }
                runQuestSection(null, dialogue, "Successfully chopping an undead tree")
                invAddOrDrop(objRepo, AmObjs.twigs)
            }
            carries(AmObjs.mithril_axe) -> runQuestSection(null, dialogue, CHOP_MITHRIL)
            else -> runQuestSection(null, dialogue, CHOP_NO_AXE)
        }
    }

    /* The notes and the container */

    /**
     * The notes interface (anma_rgb): nine orbs, all lit to start; clicking one toggles it. The
     * notes read clearly once only the 2nd, 5th and 9th are lit (OSRS Wiki quick guide); until then
     * the page shows one of six garbled symbols. Orb state is bits 1-9 of anma_progress (set =
     * unlit).
     */
    private suspend fun ProtectedAccess.translate() {
        runQuestSection(null, dialogue, "Reading the research notes")
        setOrbs(0)
        ifOpenMainModal(AmInterfaces.notes)
        refreshNotes()
    }

    private fun ProtectedAccess.orbs(): Int = (player.vars[AmVarps.progress] shr 1) and ORB_MASK

    private fun ProtectedAccess.setOrbs(unlit: Int) {
        val flags = (player.vars[AmVarps.progress] and ORB_MASK.shl(1).inv()) or (unlit shl 1)
        VarPlayerIntMapSetter.set(player, AmVarps.progress, flags)
    }

    private suspend fun ProtectedAccess.toggleOrb(index: Int) {
        if (!carries(AmObjs.garbled_notes)) {
            ifClose()
            return
        }
        setOrbs(orbs() xor (1 shl index))
        refreshNotes()
        if (orbs() != SOLVED_UNLIT) return
        delay(2)
        ifClose()
        invDel(inv, AmObjs.garbled_notes)
        invAdd(inv, AmObjs.translated_notes)
        runQuestSection(null, dialogue, "Successfully translating the notes")
    }

    private fun ProtectedAccess.refreshNotes() {
        val unlit = orbs()
        for (i in 0 until ORBS) {
            val lit = unlit and (1 shl i) == 0
            ifSetHide(AmComponents.orbs_on[i], !lit)
            ifSetHide(AmComponents.orbs_off[i], lit)
        }
        val solved = unlit == SOLVED_UNLIT
        for (symbol in AmComponents.complete) ifSetHide(symbol, !solved)
        val garbled = Integer.bitCount(unlit * GARBLE_MIX) % AmComponents.garbled.size
        for ((i, symbol) in AmComponents.garbled.withIndex()) {
            ifSetHide(symbol, solved || i != garbled)
        }
    }

    private suspend fun ProtectedAccess.makeContainer() {
        when {
            !carries(AmObjs.hard_leather) ->
                runQuestSection(null, dialogue, NO_LEATHER)
            !carries(AmObjs.buttons) ->
                runQuestSection(null, dialogue, NO_BUTTONS)
            else -> {
                invDel(inv, AmObjs.pattern)
                invDel(inv, AmObjs.hard_leather)
                invDel(inv, AmObjs.buttons)
                invAdd(inv, AmObjs.container)
                mes("You make a container for the undead chicken.")
            }
        }
    }

    companion object {
        const val STARTED = 10
        const val LOVE = 20
        const val LOVE_ANSWER = 30
        const val SAVINGS = 40
        const val SAVINGS_ANSWER = 50
        const val BANKS = 60
        const val BANKS_ANSWER = 70
        const val CRONE_ASKED = 73
        const val AMULET_MADE = 76
        const val AMULET_GIVEN = 80
        const val ONE_CHICKEN = 90
        const val BOTH_CHICKENS = 100
        const val IRON_GIVEN = 110
        const val MAGNET_GIVEN = 150
        const val BLESSED_AXE = 160
        const val TWIGS_GIVEN = 170
        const val NOTES_GIVEN = 190
        const val COMPLETE = 240

        private const val ASKED_TURAEL = 1
        private const val ORBS = 9
        private const val ORB_MASK = (1 shl ORBS) - 1

        /** Every orb unlit except the 2nd, 5th and 9th. */
        private const val SOLVED_UNLIT = ORB_MASK xor ((1 shl 1) or (1 shl 4) or (1 shl 8))
        private const val GARBLE_MIX = 0x2F5
        private const val TOKEN_PRICE = 10
        private const val IRON_BARS = 5
        private const val MAGNET_XP = 50.0
        private const val ACCUMULATOR_RANGED = 50
        private const val CHOP_ODDS = 3

        private val MINE_X = 2965..2992
        private val MINE_Z = 3228..3252

        private const val GIVE_AMULET = "Okay, you need it more than I do, I suppose."
        private const val NO_PATTERN =
            "Trying to combine buttons and leather without a pattern"
        private const val CRONE_AFTER =
            "Speaking to the old crone after delivering the amulet"
        private const val CRONE_NO_GHOSTSPEAK =
            "Speaking to the old crone without a ghostspeak amulet"
        private const val WITCH_LOST_IRON =
            "Talking to the Witch after losing the selected iron"
        private const val TREES_AGAIN =
            "Hello, I'm here about those trees again."
        private const val AXES_AGAIN =
            "I'm here about blessed axes again."
        private const val NO_LEATHER =
            "Trying to combine buttons with pattern without hard leather"
        private const val NO_BUTTONS =
            "Trying to combine hard leather with pattern without buttons"
        private const val AVA_BEFORE_WITCH = "Talking to Ava again before talking to the witch"
        private const val AVA_BEFORE_AXE = "Talking to Ava before obtaining a blessed axe"
        private const val AVA_AFTER_AXE = "Talking to Ava after obtaining a blessed axe"
        private const val AVA_WIELDING = "Speaking to Ava while wielding an undead chicken"
        private const val AVA_ANOTHER = "Speaking to Ava before giving her another chicken"
        private const val MALCOLM_BEFORE_ALICE = "Talking to Malcolm again before talking to Alice"
        private const val MALCOLM_BEFORE_ALICE_AGAIN =
            "Talking to Malcolm before talking to Alice again"
        private const val CRONE_BEFORE_ALICE =
            "Speaking to the old crone before delivering the amulet to Alice"
        private const val CRONE_LOST =
            "Speaking to the old crone after losing the ghostspeak amulet"
        private const val HAMMER_WRONG_PLACE =
            "Using hammer on selected iron without being in Rimmington mine"
        private const val TURAEL_AGAIN = "Talking to Turael or Aya again"
        private const val TURAEL_REPLACE = "Asking for a replacement blessed axe"
        private const val CHOP_MITHRIL = "Trying to chop an undead tree with mithril axe or better"
        private const val CHOP_NO_AXE =
            "Trying to chop an undead tree without mithril axe or better " +
                "(or without carrying an axe)"

        val ANIMAL_MAGNETISM =
            QuestDef(
                name = "Animal Magnetism",
                varbit = { AmVarBits.main },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "1,000 Crafting XP",
                        "1,000 Fletching XP",
                        "1,000 Slayer XP",
                        "2,500 Woodcutting XP",
                        "Ava's attractor or accumulator",
                    ),
                scrollObj = { AmObjs.attractor },
            )
    }
}

// Every cache ref Animal Magnetism uses (re-check after cache updates).

internal object AmNpcs : NpcReferences() {
    /* Child of the anma_assistant_multi. */
    val ava = find("anma_assistant")

    /* Alice is also the farm's Farming shop (Trade). */
    val alice = find("farming_shopkeeper_4")
    val malcolm = find("anma_ghost_farmer")

    /** Children of the anma_ghost_farmer_multi (amulet from state 80). */
    val malcolms = listOf(malcolm, find("anma_ghost_farmer_amulet"))
    val crone = find("ahoy_crone")
    val witch = find("anma_witch")
    val turael = find("slayer_master_1_tureal")

    /* The nasty_tree multinpc's choppable child (from state 150). */
    val tree_choppable = find("nasty_tree_choppable")
}

internal object AmObjs : ObjReferences() {
    val chicken = find("anma_chicken_sack_full")
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_e = find("amulet_of_ghostspeak_enchanted")
    val crone_amulet = find("amulet_of_humanspeak")
    val ecto_token = find("ectotoken")
    val iron_bar = find("iron_bar")
    val selected_iron = find("anma_iron_bar")
    val magnet = find("anma_magnet")
    val hammer = find("hammer")
    val mithril_axe = find("mithril_axe")
    val holy_symbol = find("blessedstar")
    val blessed_axe = find("anma_axe")
    val twigs = find("anma_wood")
    val garbled_notes = find("anma_garb_notes")
    val translated_notes = find("anma_trans_notes")
    val pattern = find("anma_pattern")
    val hard_leather = find("hard_leather")
    val buttons = find("anma_p_buttons")
    val container = find("anma_container")
    val attractor = find("anma_30_reward")
    val accumulator = find("anma_50_reward")
}

internal object AmInterfaces : InterfaceReferences() {
    val notes = find("anma_rgb")
}

internal object AmComponents : ComponentReferences() {
    val orbs_on = (1..9).map { find("anma_rgb:anma_buton_${it}_on") }
    val orbs_off = (1..9).map { find("anma_rgb:anma_buton_${it}_off") }
    val complete = listOf("a", "b", "c").map { find("anma_rgb:anma_complete_$it") }
    val garbled = (1..6).map { find("anma_rgb:anma_random_$it") }
}

internal object AmVarBits : VarBitReferences() {
    val main = find("anma_main")
}

internal object AmVarps : VarpReferences() {
    /** Server varp (65783): bit 0 asked Turael about the trees; bits 1-9 unlit note orbs. */
    val progress = find("anma_progress")
}

internal object AmVarpBuilds : VarpBuilder() {
    init {
        build("anma_progress")
    }
}
