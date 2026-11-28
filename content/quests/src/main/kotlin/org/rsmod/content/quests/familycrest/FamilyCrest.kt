package org.rsmod.content.quests.familycrest

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.skills.mining.scripts.MiningOreOverrides
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Family Crest (OSRS Wiki). The quest varp is the cache's `crestquest` (end state 11, 1 QP); the
 * brothers are multinpcs on it (Avan is "Man" below 6; all three gain a Gauntlets op at 11).
 * - Dimintheis (south-east Varrock) starts it. Caleb (Catherby) trades his piece for cooked
 *   shrimps, salmon, tuna, bass and swordfish; ask him about the rest of the crest.
 * - The Al Kharid gem trader points to Avan (the 'Man' at the Al Kharid mine), who wants a
 *   'perfect' ruby ring and necklace; Boot (Dwarven Mine) says the gold is east of Ardougne.
 * - Witchaven Dungeon: levers G (north wall), H (south room) and I (north room) unlock the doors
 *   their names encode (`famcrest_doorg2h1` opens with G up and H down); the hellhound room's
 *   gate (I up, H down) stays unlocked once passed. Its gold rocks give 'perfect' gold ore (via
 *   [MiningOreOverrides]); smelt it at a furnace (40 Smithing, 22.5 xp) and use the bar on a
 *   furnace with a ruby and a mould (40 Crafting; ring 70 xp, necklace 75 xp).
 * - Johnathon (Jolly Boar Inn, upstairs) needs any antipoison; Chronozon (Edgeville Dungeon, by
 *   the Earth Obelisk) must be hit by Wind, Water, Earth and Fire Blast (each a non-zero hit,
 *   "Chronozon weakens...") or he returns to full health instead of dying; he drops Johnathon's
 *   piece. Use the pieces together for the crest.
 * - Rewards: steel gauntlets, 2,000 Mining, Smithing and Crafting xp; each brother imbues the
 *   gauntlets (cooking, goldsmithing, chaos), free from steel, 25,000 coins to change after.
 *
 * Stages: 1 started, 2 fish asked, 3 Caleb's piece, 4 told of Avan, 5 gem trader, 6 Avan
 * revealed, 7 Boot, 8 Avan's piece, 9 Johnathon found, 10 cured, 11 done. Server varp
 * `crest_progress` (65440, ui custom range): bits 0-2 levers G/H/I up, bit 3 the hellhound gate unlocked.
 *
 * Simplified (Authored, not Wiki): only the gem trader of the Al Kharid traders knows of Avan;
 * the gem trader's Talk-to only greets you unless you seek Avan (Trade opens his shop); levers
 * are tracked per player and don't visibly flip; Chronozon's weakness doesn't wear off over time;
 * perfect ore and bars must be used on a furnace (the Smelt / skillmulti menus don't list them)
 * and the bar step makes one item per use (no make-x); the gold-room rocks give perfect ore only
 * while the quest is active; if you skip asking Caleb about the rest of the crest he tells you
 * on your next talk; gauntlets are imbued from the inventory only; Dimintheis replaces lost
 * gauntlets with steel ones; the dungeon arrival tiles are best guesses (Ryan-test).
 */
class FamilyCrest
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val playerList: PlayerList,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(FamilyCrest::class.java, "family_crest") }

    /** The blasts (bits 0-3: wind, water, earth, fire) that have hit each Chronozon. */
    private val weakened = HashMap<Npc, Int>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, CrestNpcs.dimintheis, FAMILY_CREST, wants = { true }) {
                talkDimintheis(it)
            }
            register(npcTypes, CrestNpcs.caleb, FAMILY_CREST, wants = { true }) { talkCaleb(it) }
            register(npcTypes, CrestNpcs.avan_man, FAMILY_CREST, wants = { true }) { talkMan(it) }
            register(npcTypes, CrestNpcs.avan, FAMILY_CREST, wants = { true }) { talkAvan(it) }
            register(npcTypes, CrestNpcs.johnathon, FAMILY_CREST, wants = { true }) {
                talkJohnathon(it)
            }
            register(npcTypes, CrestNpcs.gem_trader, FAMILY_CREST, wants = { seeksAvan() }) {
                talkGemTrader(it)
            }
            default(npcTypes, CrestNpcs.gem_trader) { npc ->
                startDialogue(npc) {
                    // The Wiki greeting; his shop is on Trade.
                    chatNpc(neutral, "Good day to you traveller. Would you be interested in buying some gems?")
                }
            }
            register(npcTypes, CrestNpcs.boot, FAMILY_CREST, wants = { true }) { talkBoot(it) }
            for (brother in Brother.entries) {
                register(npcTypes, brother.npc, FAMILY_CREST, wants = { true }) {
                    imbue(it, brother, talk = true)
                }
            }
        }
        for (brother in Brother.entries) {
            onNpcOpLabel(npcTypes, brother.npc, "Gauntlets") { imbue(it, brother, talk = false) }
        }
        for (cure in CrestObjs.antipoisons) {
            onOpNpcU(CrestNpcs.johnathon, cure) { cureJohnathon(it.npc, cure) }
        }

        val pieces = CrestObjs.pieces
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) onOpHeldU(pieces[i], pieces[j]) { assembleCrest() }
        }

        onLocOpLabel(locTypes, CrestLocs.ruin_entrance, "Climb-down") { telejump(DUNGEON_ARRIVE) }
        onLocOpLabel(locTypes, CrestLocs.ruin_exit, "Climb-up") { telejump(RUIN_ARRIVE) }
        for ((index, lever) in CrestLocs.levers.withIndex()) {
            onLocOpLabel(locTypes, lever, "Pull") { pullLever(index) }
        }
        onLocOpLabel(locTypes, CrestLocs.door_h2, "Open") { openDoor(it, up(H)) }
        onLocOpLabel(locTypes, CrestLocs.door_g2h1, "Open") { openDoor(it, up(G) && !up(H)) }
        onLocOpLabel(locTypes, CrestLocs.door_h2g1, "Open") { openDoor(it, up(H) && !up(G)) }
        onLocOpLabel(locTypes, CrestLocs.door_i2h1, "Open") { openGate(it) }
        MiningOreOverrides.register { player, rock -> perfectOre(player, rock) }

        for (type in locTypes.values) {
            if (!type.isSmeltingFurnace()) continue
            onOpLocU(type, CrestObjs.perfect_ore) { smeltPerfectOre() }
            onOpLocU(type, CrestObjs.perfect_bar) { craftPerfectJewellery() }
        }

        onModifyNpcHit(CrestNpcs.chronozon) {
            val spell = CrestObjs.blasts.indexOfFirst { hit.isSecondaryObj(it) }
            val mask = weakened[npc] ?: 0
            if (spell >= 0 && hit.damage > 0 && mask and (1 shl spell) == 0) {
                weakened[npc] = mask or (1 shl spell)
                val uid = hit.sourceUid
                if (hit.isFromPlayer && uid != null) {
                    PlayerUid(uid).resolve(playerList)?.mes("Chronozon weakens...")
                }
            }
            if ((weakened[npc] ?: 0) != ALL_BLASTS && hit.damage >= npc.hitpoints) {
                // Wiki: without all four blasts he returns to full health when nearly dead.
                hit.damage = 0
                npc.hitpoints = npc.baseHitpointsLvl
            }
        }
        onEvent<NpcKilledEvent> { chronozonKilled(this) }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(FAMILY_CREST)

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(FAMILY_CREST, to)
    }

    private fun ProtectedAccess.seeksAvan(): Boolean = stage == TOLD_OF_AVAN || stage == GEM_TRADER

    /** Carried, worn or banked. */
    private fun ProtectedAccess.owns(obj: ObjType): Boolean =
        carries(obj) || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

    private fun ProtectedAccess.ownsPiece(piece: ObjType): Boolean =
        owns(piece) || owns(CrestObjs.family_crest)

    private fun ProtectedAccess.hasWholeCrest(): Boolean =
        carries(CrestObjs.family_crest) || CrestObjs.pieces.all { carries(it) }

    /* Dimintheis */

    private suspend fun ProtectedAccess.talkDimintheis(npc: Npc) {
        when {
            stage == 0 -> startQuest(npc)
            stage >= COMPLETE -> dimintheisAfter(npc)
            stage >= CURED && hasWholeCrest() -> finishQuest(npc)
            stage >= CALEB_PIECE ->
                runQuestSection(npc, dialogue, "Talking to Dimintheis again after obtaining a crest piece")
            else -> runQuestSection(npc, dialogue, "Talking to Dimintheis again")
        }
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        var start = false
        var bold = false
        val lacks =
            REQUIREMENTS.any { (stat, level) -> statBase(stat) < level } &&
                player.combatLevel < RECOMMENDED_COMBAT
        val where by lazy { dialogue.branch(DIMINTHEIS, WHERE) }
        val hooks =
            CrestHooks(
                conditions = mapOf(LACKS_REQUIREMENTS to { lacks }),
                options =
                    mapOf(
                        "Yes." to { start = true },
                        "Hi, I am a bold adventurer." to { bold = true },
                    ),
                // The Wiki transcript points back up the tree here; replay those branches.
                jump = { tag ->
                    when (tag) {
                        WHERE -> if (bold) where else null
                        CONTINUES_WHERE -> where
                        CONTINUES_RULE -> dialogue.branch(DIMINTHEIS, "Why would he do that?").drop(1)
                        else -> null
                    }
                },
            )
        runQuestSection(npc, dialogue, DIMINTHEIS, hooks)
        if (start) advance(STARTED)
    }

    private suspend fun ProtectedAccess.finishQuest(npc: Npc) {
        val assembled = carries(CrestObjs.family_crest)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the crest isn't assembled:" to { !assembled },
                        "If the crest is assembled:" to { assembled },
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Dimintheis", hooks)
        if (!hasWholeCrest()) return
        if (carries(CrestObjs.family_crest)) {
            invDel(inv, CrestObjs.family_crest)
        } else {
            for (piece in CrestObjs.pieces) invDel(inv, piece)
        }
        invAddOrDrop(objRepo, CrestObjs.steel_gauntlets)
        for (stat in listOf(stats.mining, stats.smithing, stats.crafting)) {
            statAdvance(stat, REWARD_XP * xpMods.get(player, stat))
        }
        completeQuest(FAMILY_CREST)
    }

    private suspend fun ProtectedAccess.dimintheisAfter(npc: Npc) {
        if (CrestObjs.gauntlets.any { owns(it) }) {
            startDialogue(npc) {
                // Authored (not Wiki).
                chatNpc(neutral, "Thank you again, adventurer. My family owes you a great debt.")
            }
            return
        }
        startDialogue(npc) {
            // Authored (not Wiki): the gauntlets "return to the head of the family" when lost.
            chatNpc(neutral, "Ah, adventurer. These gauntlets found their way back to me again.")
        }
        invAddOrDrop(objRepo, CrestObjs.steel_gauntlets)
        mes("Dimintheis hands you a pair of steel gauntlets.") // Authored (not Wiki).
    }

    /* Caleb */

    private suspend fun ProtectedAccess.talkCaleb(npc: Npc) {
        when {
            stage == 0 ->
                startDialogue(npc) {
                    chatNpc(neutral, "Who are you? What are you after?")
                    chatPlayer(neutral, "Nothing, I will be on my way.") // Wiki option, no reply.
                }
            stage == STARTED -> {
                var agreed = false
                val hooks = SimpleQuestHooks(options = mapOf("Ok, I will get those." to { agreed = true }))
                runQuestSection(npc, dialogue, "Caleb", hooks)
                if (agreed) advance(FISH)
            }
            stage == FISH -> calebFish(npc)
            carries(CrestObjs.family_crest) ->
                runQuestSection(npc, dialogue, "Talking to Caleb after assembling the family crest")
            else -> calebAgain(npc)
        }
    }

    private suspend fun ProtectedAccess.calebFish(npc: Npc) {
        val all = CrestObjs.fish.all { carries(it) }
        var exchanged = false
        var asked = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have all the fish in their inventory:" to { !all },
                        "If the player has all the fish in their inventory:" to { all },
                    ),
                options = mapOf("Uh... what happened to the rest of it?" to { asked = true }),
                boxes =
                    mapOf(
                        "You exchange the fish for Caleb's piece of the crest." to {
                            exchanged = true
                            false
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Caleb again", hooks)
        if (!exchanged || !CrestObjs.fish.all { carries(it) }) return
        for (fish in CrestObjs.fish) invDel(inv, fish)
        invAddOrDrop(objRepo, CrestObjs.caleb_crest)
        advance(if (asked) TOLD_OF_AVAN else CALEB_PIECE)
    }

    private suspend fun ProtectedAccess.calebAgain(npc: Npc) {
        val has = ownsPiece(CrestObjs.caleb_crest)
        var lost = false
        var asked = false
        val hooks =
            CrestHooks(
                conditions =
                    mapOf(
                        "Before finding Avan:" to { stage < AVAN },
                        "If the player has the first piece" to { has },
                        "If the player has lost the crest piece" to { !has },
                    ),
                options =
                    mapOf(
                        "I have lost the piece you gave me." to { lost = true },
                        "Uh.. what happened to the rest of the crest?" to { asked = true },
                    ),
                jump = { tag -> if (tag == ASK_REST_AGAIN) dialogue.branch(CALEB_AGAIN, ASK_REST) else null },
            )
        runQuestSection(npc, dialogue, "Talking to Caleb again after obtaining the first crest piece", hooks)
        if (lost && !ownsPiece(CrestObjs.caleb_crest)) invAddOrDrop(objRepo, CrestObjs.caleb_crest)
        if (stage == CALEB_PIECE && !asked) {
            // Authored (not Wiki): the Wiki requires asking; the branch from the fish hand-in.
            runQuestBranch(npc, dialogue, CALEB_AGAIN, ASK_REST)
            asked = true
        }
        if (asked) advance(TOLD_OF_AVAN)
    }

    /* Avan, the gem trader and Boot */

    private suspend fun ProtectedAccess.talkGemTrader(npc: Npc) {
        var asked = false
        val hooks = SimpleQuestHooks(options = mapOf(SEARCH_AVAN to { asked = true }))
        runQuestSection(npc, dialogue, "Talking to the Gem trader", hooks)
        if (asked && stage == TOLD_OF_AVAN) advance(GEM_TRADER)
    }

    private suspend fun ProtectedAccess.talkMan(npc: Npc) {
        if (stage != GEM_TRADER) {
            runQuestSection(npc, dialogue, "Talking to Avan (Man) before talking to the Gem trader")
            return
        }
        var found = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("I'm looking for a man named Avan Fitzharmon" to { found = true })
            )
        runQuestSection(npc, dialogue, "Talking to Avan", hooks)
        if (found) advance(AVAN)
    }

    private suspend fun ProtectedAccess.talkAvan(npc: Npc) {
        when {
            stage == AVAN -> runQuestSection(npc, dialogue, "Talking to Avan again")
            stage == BOOT && carries(CrestObjs.ring) && carries(CrestObjs.necklace) -> {
                runQuestSection(npc, dialogue, "If the player has the jewellery")
                if (!carries(CrestObjs.ring) || !carries(CrestObjs.necklace)) return
                invDel(inv, CrestObjs.ring)
                invDel(inv, CrestObjs.necklace)
                invAddOrDrop(objRepo, CrestObjs.avan_crest)
                advance(AVAN_PIECE)
            }
            stage == BOOT -> runQuestSection(npc, dialogue, "If the player has not made the jewellery")
            carries(CrestObjs.family_crest) ->
                runQuestSection(npc, dialogue, "Talking to Avan after assembling the family crest")
            stage == AVAN_PIECE && ownsPiece(CrestObjs.avan_crest) ->
                runQuestSection(npc, dialogue, "Talking to Avan after getting his crest piece")
            else -> avanAgain(npc)
        }
    }

    private suspend fun ProtectedAccess.avanAgain(npc: Npc) {
        val has = ownsPiece(CrestObjs.avan_crest)
        var lost = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has all the crest pieces:" to { has },
                        "If the player has lost Avan's crest piece:" to { !has },
                    ),
                options = mapOf("I have lost the fragment you gave me..." to { lost = true }),
            )
        runQuestSection(npc, dialogue, "Talking to Avan after obtaining all crest pieces", hooks)
        if (lost && !ownsPiece(CrestObjs.avan_crest)) invAddOrDrop(objRepo, CrestObjs.avan_crest)
    }

    private suspend fun ProtectedAccess.talkBoot(npc: Npc) {
        var asked = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Hello. I'm in search of very high quality gold." to { asked = true })
            )
        runQuestSection(npc, dialogue, "Boot", hooks)
        if (asked && stage == AVAN) advance(BOOT)
    }

    /* Witchaven Dungeon */

    private fun ProtectedAccess.progress(): Int = player.vars[CrestVarps.progress]

    private fun ProtectedAccess.up(lever: Int): Boolean = progress() and (1 shl lever) != 0

    private suspend fun ProtectedAccess.pullLever(lever: Int) {
        val wasUp = up(lever)
        anim(CrestSeqs.pull_lever)
        delay(1)
        VarPlayerIntMapSetter.set(player, CrestVarps.progress, progress() xor (1 shl lever))
        // Authored line (not Wiki).
        mes(if (wasUp) "You pull the lever down." else "You pull the lever up.")
    }

    private suspend fun ProtectedAccess.openDoor(door: BoundLocInfo, open: Boolean) {
        if (!open) {
            mes("The door is locked.") // Authored (not Wiki).
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.openGate(door: BoundLocInfo) {
        val unlocked = progress() and (1 shl GATE_BIT) != 0
        if (!unlocked && !(up(I) && !up(H))) {
            mes("The door is locked.") // Authored (not Wiki).
            return
        }
        if (!unlocked) VarPlayerIntMapSetter.set(player, CrestVarps.progress, progress() or (1 shl GATE_BIT))
        passThroughWall(door)
    }

    /** The hellhound room's gold rocks give 'perfect' gold ore during the quest. */
    private fun perfectOre(player: Player, rock: CoordGrid): ObjType? {
        if (rock.x !in GOLD_ROOM_X || rock.z !in GOLD_ROOM_Z || rock.level != 0) return null
        val state = player.questState(FAMILY_CREST)
        return if (state in STARTED until COMPLETE) CrestObjs.perfect_ore else null
    }

    /* Furnace */

    /** Smelting's rule: a loc named "Furnace" with a Smelt op (or Use, for a plain "Furnace"). */
    private fun UnpackedLocType.isSmeltingFurnace(): Boolean {
        if (!name.contains("furnace", ignoreCase = true)) return false
        val exact = name.equals("Furnace", ignoreCase = true)
        return op.any { it.equals("Smelt", ignoreCase = true) || (exact && it.equals("Use", true)) }
    }

    private suspend fun ProtectedAccess.smeltPerfectOre() {
        if (stat(stats.smithing) < SKILL_LEVEL) {
            mes("You need a Smithing level of $SKILL_LEVEL to smelt this.") // Authored (not Wiki).
            return
        }
        anim(CrestSeqs.furnace)
        delay(SMELT_TICKS)
        if (!carries(CrestObjs.perfect_ore)) return
        invReplace(inv, CrestObjs.perfect_ore, 1, CrestObjs.perfect_bar)
        statAdvance(stats.smithing, BAR_XP * xpMods.get(player, stats.smithing))
        mes("You retrieve a bar of 'perfect' gold.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.craftPerfectJewellery() {
        if (stat(stats.crafting) < SKILL_LEVEL) {
            mes("You need a Crafting level of $SKILL_LEVEL to make this.") // Authored (not Wiki).
            return
        }
        if (!carries(CrestObjs.ruby)) {
            mes("You need a ruby to make 'perfect' jewellery.") // Authored (not Wiki).
            return
        }
        val ring = carries(CrestObjs.ring_mould)
        val necklace = carries(CrestObjs.necklace_mould)
        val makeRing =
            when {
                ring && necklace -> {
                    var pick = true
                    startDialogue {
                        pick = choice2("'Perfect' ring", true, "'Perfect' necklace", false, title = "What would you like to make?")
                    }
                    pick
                }
                ring -> true
                necklace -> false
                else -> {
                    mes("You need a ring or necklace mould to do that.") // Authored (not Wiki).
                    return
                }
            }
        anim(CrestSeqs.furnace)
        delay(CRAFT_TICKS)
        if (!carries(CrestObjs.perfect_bar) || !carries(CrestObjs.ruby)) return
        invDel(inv, CrestObjs.perfect_bar)
        invDel(inv, CrestObjs.ruby)
        val product = if (makeRing) CrestObjs.ring else CrestObjs.necklace
        invAdd(inv, product)
        val xp = if (makeRing) RING_XP else NECKLACE_XP
        statAdvance(stats.crafting, xp * xpMods.get(player, stats.crafting))
        // Authored line (not Wiki).
        mes(if (makeRing) "You make a 'perfect' ruby ring." else "You make a 'perfect' ruby necklace.")
    }

    /* Johnathon and Chronozon */

    private suspend fun ProtectedAccess.talkJohnathon(npc: Npc) {
        when {
            stage == AVAN_PIECE -> {
                runQuestSection(npc, dialogue, "Johnathon")
                advance(JOHNATHON)
            }
            stage < CURED -> runQuestSection(npc, dialogue, "Talking to Johnathon again")
            ownsPiece(CrestObjs.johnathon_crest) ->
                runQuestSection(npc, dialogue, "Returning after defeating Chronozon")
            else -> {
                val hooks =
                    CrestHooks(
                        jump = { tag ->
                            when (tag) {
                                "So, is this Chronozon hard to defeat?" ->
                                    dialogue.branch(CURE, "So is this Chronozon hard to defeat?")
                                "Where can I find Chronozon?" ->
                                    dialogue.branch(CURE, "Where can I find Chronozon?")
                                else -> null
                            }
                        }
                    )
                runQuestSection(npc, dialogue, "Talking to Johnathon again (2)", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.cureJohnathon(npc: Npc, cure: ObjType) {
        if (stage != AVAN_PIECE && stage != JOHNATHON) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, cure)
        advance(CURED)
        runQuestSection(npc, dialogue, CURE)
    }

    private fun chronozonKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != CrestNpcs.chronozon.id) return
        weakened.remove(event.npc)
        val player = event.hero
        if (player.questState(FAMILY_CREST) != CURED) return
        val held =
            listOf(CrestObjs.johnathon_crest, CrestObjs.family_crest).any {
                it in player.inv || it in player.worn
            }
        if (!held) objRepo.add(CrestObjs.johnathon_crest, event.npc.coords, DROP_TICKS, player)
    }

    /* The crest */

    private suspend fun ProtectedAccess.assembleCrest() {
        if (!CrestObjs.pieces.all { carries(it) }) {
            runQuestSection(null, dialogue, "Trying to assemble the crest with only two pieces")
            return
        }
        for (piece in CrestObjs.pieces) invDel(inv, piece)
        invAdd(inv, CrestObjs.family_crest)
        runQuestSection(null, dialogue, "Assembling the crest")
    }

    /* The gauntlets */

    private enum class Brother(
        val section: String,
        val accept: String,
        val gauntlets: () -> ObjType,
        val npc: NpcType,
    ) {
        CALEB(
            "Talking to Caleb after the quest",
            "Yes, please do that for me.",
            { CrestObjs.cooking },
            CrestNpcs.caleb_after,
        ),
        AVAN(
            "Talking to Avan after the quest",
            "That sounds good, improve them for me.",
            { CrestObjs.goldsmithing },
            CrestNpcs.avan_after,
        ),
        JOHNATHON(
            "Talking to Johnathon after the quest",
            "That sounds good to me.",
            { CrestObjs.chaos },
            CrestNpcs.johnathon_after,
        ),
    }

    private suspend fun ProtectedAccess.imbue(npc: Npc, brother: Brother, talk: Boolean) {
        val target = brother.gauntlets()
        val held = CrestObjs.gauntlets.firstOrNull { carries(it) }
        if (held == null) {
            val wearing = CrestObjs.gauntlets.any { invTotal(worn, it) > 0 }
            startDialogue(npc) {
                // Authored lines (not Wiki).
                if (wearing) {
                    chatNpc(neutral, "Take your gauntlets off and I can work on them for you.")
                } else {
                    chatNpc(neutral, "If you have lost the gauntlets, my father will have them again.")
                }
            }
            return
        }
        when {
            held == target ->
                startDialogue(npc) {
                    chatNpc(neutral, "Those gauntlets already carry my skill.") // Authored (not Wiki).
                }
            held == CrestObjs.steel_gauntlets -> {
                var yes = !talk
                if (talk) {
                    val hooks = SimpleQuestHooks(options = mapOf(brother.accept to { yes = true }))
                    runQuestSection(npc, dialogue, brother.section, hooks)
                }
                if (yes && carries(held)) invReplace(inv, held, 1, target)
            }
            else -> swapGauntlets(npc, held, target)
        }
    }

    private suspend fun ProtectedAccess.swapGauntlets(npc: Npc, held: ObjType, target: ObjType) {
        var yes = false
        startDialogue(npc) {
            // Authored lines (not Wiki); the 25,000 coin fee is the Wiki's.
            chatNpc(neutral, "I can change those gauntlets to carry my skill instead, for 25,000 coins.")
            yes = choice2("Yes, change them.", true, "No thanks.", false)
        }
        if (!yes) return
        if (!carries(CrestObjs.coins, SWAP_COST)) {
            mes("You don't have enough coins.") // Authored (not Wiki).
            return
        }
        invDel(inv, CrestObjs.coins, SWAP_COST)
        invReplace(inv, held, 1, target)
    }

    companion object {
        const val STARTED = 1
        const val FISH = 2
        const val CALEB_PIECE = 3
        const val TOLD_OF_AVAN = 4
        const val GEM_TRADER = 5
        const val AVAN = 6
        const val BOOT = 7
        const val AVAN_PIECE = 8
        const val JOHNATHON = 9
        const val CURED = 10
        const val COMPLETE = 11

        private const val DIMINTHEIS = "Dimintheis"
        private const val WHERE = "So where is this crest?"
        private const val CONTINUES_WHERE = "Continues from \"So where is this crest?\""
        private const val CONTINUES_RULE = "Continues above from \"Well, there is a long standing rule\""
        private const val LACKS_REQUIREMENTS =
            "If the player lacks one or more requirements of the quest and has a combat level lower than 55:"
        private const val CALEB_AGAIN = "Talking to Caleb again"
        private const val ASK_REST = "Uh... what happened to the rest of it?"
        private const val ASK_REST_AGAIN = "Uh.. what happened to the rest of the crest?"
        private const val SEARCH_AVAN = "I'm in search of a man named Avan Fitzharmon."
        private const val CURE = "Using an antipoison on Johnathon"

        private const val RECOMMENDED_COMBAT = 55
        private const val SKILL_LEVEL = 40
        private const val REWARD_XP = 2000.0
        private const val BAR_XP = 22.5
        private const val RING_XP = 70.0
        private const val NECKLACE_XP = 75.0
        private const val SMELT_TICKS = 4
        private const val CRAFT_TICKS = 3
        private const val SWAP_COST = 25_000
        private const val DROP_TICKS = 200

        /** Levers (bits of `crest_progress`) and the gate flag. */
        private const val G = 0
        private const val H = 1
        private const val I = 2
        private const val GATE_BIT = 3
        private const val ALL_BLASTS = 0b1111

        /** The hellhound room, east of the gate at x 2727. */
        private val GOLD_ROOM_X = 2728..2760
        private val GOLD_ROOM_Z = 9660..9710

        /** Authored arrival tiles (Ryan-test). */
        private val DUNGEON_ARRIVE = CoordGrid(2698, 9682, 0)
        private val RUIN_ARRIVE = CoordGrid(2698, 3283, 0)

        private val REQUIREMENTS by lazy {
            listOf(stats.mining to 40, stats.smithing to 40, stats.magic to 59, stats.crafting to 40)
        }

        val FAMILY_CREST =
            QuestDef(
                name = "Family Crest",
                varp = { CrestVarps.crestquest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf("Steel gauntlets", "2,000 Mining XP", "2,000 Smithing XP", "2,000 Crafting XP"),
                scrollObj = { CrestObjs.family_crest },
            )
    }
}

/**
 * Quest hooks that can replay another transcript branch where the Wiki transcript points back up
 * the tree ("above", "Continues from ..."): [jump] maps an option label or action tag to nodes.
 * Boxes always show; flags set in [options] are applied after the section.
 */
private class CrestHooks(
    private val conditions: Map<String, () -> Boolean> = emptyMap(),
    private val options: Map<String, () -> Unit> = emptyMap(),
    private val jump: (String) -> List<QuestDialogue.Node>? = { null },
) : QuestDialogueHooks {
    override fun condition(text: String): Boolean = conditions[text]?.invoke() ?: false

    override suspend fun Dialogue.onOption(label: String) {
        options[label]?.invoke()
        jump(label)?.let { QuestDialogueRunner(this@CrestHooks).run(this, it) }
    }

    override suspend fun Dialogue.onAction(tag: String) {
        jump(tag)?.let { QuestDialogueRunner(this@CrestHooks).run(this, it) }
    }
}

// Every cache ref Family Crest uses (re-check after cache updates).

internal object CrestNpcs : NpcReferences() {
    val dimintheis = find("dimintheis")
    /** Children of the `caleb_fitzharmon` multinpc (crestquest 0-10, 11-12). */
    val caleb = find("caleb_fitzharmon_1op")
    val caleb_after = find("caleb_fitzharmon_2ops")
    /** Children of the `avan` multinpc (crestquest 0-5, 6-10, 11-12). */
    val avan_man = find("avan_fitzharmon_man")
    val avan = find("avan_fitzharmon_avan_1op")
    val avan_after = find("avan_fitzharmon_avan_2ops")
    /** Children of the `johnathon_fitzharmon` multinpc (crestquest 0-10, 11-12). */
    val johnathon = find("johnathon_fitzharmon_1op")
    val johnathon_after = find("johnathon_fitzharmon_2ops")
    /** Al Kharid; generic-shops binds his Trade op. */
    val gem_trader = find("gem_trader")
    val boot = find("boot_the_dwarf")
    val chronozon = find("chronozon")
}

internal object CrestLocs : LocReferences() {
    val ruin_entrance = find("slug2_ruin_entrance")
    val ruin_exit = find("slug2_dongeon_ruin_exit")
    /** G (north wall), H (south room), I (north room). */
    val levers = listOf(find("leverg"), find("leverh"), find("leveri"))
    /** South room, west door. */
    val door_h2 = find("famcrest_doorh2")
    /** South room, east door. */
    val door_g2h1 = find("famcrest_doorg2h1")
    /** North room door. */
    val door_h2g1 = find("famcrest_doorh2g1")
    /** The hellhound room gate. */
    val door_i2h1 = find("famcrest_doori2h1")
}

internal object CrestObjs : ObjReferences() {
    val caleb_crest = find("caleb_crest")
    val avan_crest = find("avan_crest")
    val johnathon_crest = find("johnathon_crest")
    val family_crest = find("family_crest")
    val pieces = listOf(caleb_crest, avan_crest, johnathon_crest)
    /** Cooked shrimps, salmon, tuna, bass, swordfish. */
    val fish = listOf(find("shrimp"), find("salmon"), find("tuna"), find("bass"), find("swordfish"))
    val perfect_ore = find("perfect_gold_ore")
    val perfect_bar = find("perfect_gold_bar")
    val ring = find("perfect_ruby_ring")
    val necklace = find("perfect_ruby_necklace")
    val ruby = find("ruby")
    val ring_mould = find("ring_mould")
    val necklace_mould = find("necklace_mould")
    val steel_gauntlets = find("steel_gauntlets")
    val cooking = find("gauntlets_of_cooking")
    val goldsmithing = find("gauntlets_of_goldsmithing")
    val chaos = find("gauntlets_of_chaos")
    val gauntlets = listOf(steel_gauntlets, cooking, goldsmithing, chaos)
    val coins = find("coins")
    /** Wind, Water, Earth and Fire Blast (the spell objs on a magic hit). */
    val blasts =
        listOf(find("41_wind_blast"), find("47_water_blast"), find("53_earth_blast"), find("59_fire_blast"))
    /** Wiki: any poison cure. */
    val antipoisons: List<ObjType> =
        listOf(
            "4doseantipoison", "3doseantipoison", "2doseantipoison", "1doseantipoison",
            "4dose2antipoison", "3dose2antipoison", "2dose2antipoison", "1dose2antipoison",
            "antidote+4", "antidote+3", "antidote+2", "antidote+1",
            "antidote++4", "antidote++3", "antidote++2", "antidote++1",
        ).map { find(it) }
}

internal object CrestSeqs : SeqReferences() {
    val pull_lever = find("human_pull_lever")
    val furnace = find("human_furnace")
}

internal object CrestVarps : VarpReferences() {
    val crestquest = find("crestquest")

    /** Server varp (65812): see [FamilyCrest]. */
    val progress = find("crest_progress")
}

internal object CrestVarpBuilds : VarpBuilder() {
    init {
        build("crest_progress")
    }
}
