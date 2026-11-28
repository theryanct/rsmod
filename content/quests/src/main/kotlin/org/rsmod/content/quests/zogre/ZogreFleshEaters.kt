package org.rsmod.content.quests.zogre

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
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.bigchompy.BigChompy.Companion.BIG_CHOMPY
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.ShopGates
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.junglepotion.JunglePotion.Companion.JUNGLE_POTION
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Zogre Flesh Eaters (OSRS Wiki). Cache quest varbit `zogre` (bits 0-4 of varp `zombie_ogre`;
 * cache db end state 14, which the 5-bit width fits) plus the cache's own `thzfe_*` varbits for
 * the clues; server varp `zogre_progress` (65975) holds the few flags the cache has no bit for.
 * Stages are ours (the client only checks 0 / started / 14):
 * - 1 Grish (Jiggig, Smithing 4, Herblore 8, Ranged 30, Big Chompy + Jungle Potion) hands over two
 *   super restores and three cooked chompies; 2 the Ogre guard crushes the barricade
 *   (`thzfe_blocking_barricade`, which the barricade multilocs show).
 * - Jiggig Dungeon (level 2 under the east stairs): the lectern's torn page, the skeleton's zombie
 *   (Brentle Vahn) and its ruined backpack (tankard, knife, rotten food), and the special coffin:
 *   search, knife, lift (Strength roll), search (`thzfe_prismsearch` 1-4) for the black prism.
 * - Zavistic Rarve (Wizards' Guild or its bell) reads the prism + page: 4, go to Sithik Ints (the
 *   guest house bed loc, north of the guild). Asking to look around lets you search his drawers
 *   (papyrus, charcoal, portraiture book), cupboard (necromancy book) and wardrobe (H.A.M book).
 *   Papyrus on Sithik draws a portrait (Crafting roll); the Dragon Inn bartender recognises the
 *   tankard and signs the good portrait. Zavistic sees all four (`thzfe_shown*`): 6, strange potion.
 * - 7 potion in his tea (the drawers it stands on); talking to him again finds an ogre
 *   (`thzfe_sithik_transformed` 1, the bed multiloc); his three answers: 9, Relicym's balm and
 *   brutal arrows unlock (`thzfe_makecuredisease` / `thzfe_makebrutalarrow`), he turns back (2).
 * - Grish: 10, the ogre gate key (and the comp ogre bow demonstration, `thzfe_makecompozogrebow`);
 *   the key opens the stone doors to the lower level; the stand spawns Slash Bash (level 111, 25%
 *   damage except brutal arrows 100% / Crumble Undead 50%); 11 killed: ogre artefact + 3 ourg
 *   bones + 2 zogre bones on the floor. 14 artefact to Grish: 1 QP, 2,000 Fletching / Ranged /
 *   Herblore XP (cache db stat_xp_awarded 20000 tenths each; Wiki 2,000).
 * - Uglug Nar buys one balm (100/300/650/1,000 coins by dose, `thzfe_sold_balm`), which opens his
 *   generic shop (brutal arrows, comp ogre bows, balms); Zavistic buys the prism for 2,000 once
 *   Sithik has talked (`thzfe_prismsold`). Disease: [ZogreDisease].
 *
 * Simplified: Sithik turns into an ogre the next time you talk to him (no leaving the house); the
 * tea takes the potion through the drawers it stands on (no item-on-ground-obj op); Sithik's
 * unlocks come per answer rather than after all three; Slash Bash's drops and the zombie's
 * backpack land on the floor at once; the comp ogre bow's wield gate isn't enforced; the
 * flee/despawn line isn't shown; the dungeon's plain ogre coffins aren't built.
 */
class ZogreFleshEaters
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(ZogreFleshEaters::class.java, "zogre_flesh_eaters").also { d ->
            for (title in PRUNED) prune(d.section(title) as MutableList<Node>)
        }
    }

    /** Per-player Slash Bash / zombie (single player, keyed for safety). */
    private val bashes = HashMap<Int, Npc>()
    private val zombies = HashMap<Int, Npc>()

    private val grishSpeakers: Map<String, NpcType> by lazy {
        mapOf("Uglug Nar" to npcTypes[ZfeNpcs.uglug])
    }

    private val zavisticSpeakers: Map<String, NpcType> by lazy {
        mapOf("Zavistic Rarve" to npcTypes[ZfeNpcs.zavistic])
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, ZfeNpcs.grish, ZOGRE, wants = { true }) { talkGrish(it) }
            register(npcTypes, ZfeNpcs.guard, ZOGRE, wants = { true }) { talkGuard(it) }
            register(npcTypes, ZfeNpcs.zavistic, ZOGRE, wants = { state in STARTED until COMPLETE }) {
                talkZavistic(it)
            }
            register(npcTypes, ZfeNpcs.uglug, ZOGRE, wants = { bit(ZfeVarBits.sold_balm) == 0 }) {
                runQuestSection(it, dialogue, UGLUG_BEFORE)
            }
            default(npcTypes, ZfeNpcs.uglug) {
                // Authored (not Wiki): after the balm is sold.
                startDialogue(it) { chatNpc(happy, "Yous wants da stuffsies? Me's got glug-glugs for da sickies!") }
            }
        }
        ZogreBell.ring = { if (state in STARTED until COMPLETE) { talkZavistic(null); true } else false }
        ShopGates.register(ZfeNpcs.uglug) { npc ->
            if (bit(ZfeVarBits.sold_balm) != 0) {
                true
            } else {
                runQuestSection(npc, dialogue, UGLUG_BEFORE)
                false
            }
        }

        // Jiggig.
        for (barricade in listOf(ZfeLocs.barricade_l, ZfeLocs.barricade_r)) {
            locOp(barricade, "Climb-over") { climbBarricade() }
        }
        locOp(ZfeLocs.stairs_down, "Climb-down") { climbDown() }
        locOp(ZfeLocs.stairs_up, "Climb-up") { climbUp() }
        locOp(ZfeLocs.lectern, "Search") { searchLectern() }
        locOp(ZfeLocs.skeleton, "Search") { searchSkeleton() }
        npcOp(ZfeNpcs.zombie, "Talk-to") { runQuestSection(it, dialogue, "Talking to the zombie") }
        for (coffin in ZfeLocs.coffins) {
            locOp(coffin, "Search") { searchCoffin() }
            locU(coffin, ZfeObjs.knife) { knifeCoffin() }
        }
        for (door in ZfeLocs.doors) locOp(door, "Open") { openDoor(it) }
        locOp(ZfeLocs.stand, "Search") { searchStand() }
        onModifyNpcHit(ZfeNpcs.slash_bash) { bashDamage(hit) }
        onEvent<NpcKilledEvent> { npcKilled(this) }

        // Items.
        onHeldOpLabel(objTypes, ZfeObjs.backpack, "Open") { openBackpack() }
        onHeldOpLabel(objTypes, ZfeObjs.page, "Read") { runQuestSection(null, dialogue, "Read Torn page") }
        onHeldOpLabel(objTypes, ZfeObjs.tankard, "Look-at") { runQuestSection(null, dialogue, "Look-at Dragon inn tankard") }
        onHeldOpLabel(objTypes, ZfeObjs.prism, "Look-at") { runQuestSection(null, dialogue, "Look-at Black prism") }
        onHeldOpLabel(objTypes, ZfeObjs.ham_book, "Read") { runQuestSection(null, dialogue, "Read Book of 'H.A.M'") }
        onHeldOpLabel(objTypes, ZfeObjs.necro_book, "Read") { runQuestSection(null, dialogue, "Read Necromancy book") }
        onHeldOpLabel(objTypes, ZfeObjs.portrait_book, "Read") {
            runQuestSection(null, dialogue, "Read the Book of portraiture")
        }

        // Clues on Grish, the bartender, Irwin and Zavistic.
        npcU(ZfeNpcs.grish, ZfeObjs.tankard) { runQuestSection(it, dialogue, "Using the Dragon inn tankard on Grish") }
        npcU(ZfeNpcs.grish, ZfeObjs.page) { runQuestSection(it, dialogue, "Using the Torn page on Grish") }
        npcU(ZfeNpcs.grish, ZfeObjs.prism) { runQuestSection(it, dialogue, "Using the Black prism on Grish") }
        npcU(ZfeNpcs.bartender, ZfeObjs.tankard) { tankardOnBartender(it) }
        npcU(ZfeNpcs.bartender, ZfeObjs.page) {
            runQuestSection(it, dialogue, "Using the torn page on the Dragon Inn Bartender")
        }
        npcU(ZfeNpcs.bartender, ZfeObjs.prism) {
            runQuestSection(it, dialogue, "Using the black prism on the Dragon Inn Bartender")
        }
        npcU(ZfeNpcs.bartender, ZfeObjs.portrait_bad) {
            runQuestSection(it, dialogue, "Using the bad portrait on the Dragon Inn Bartender")
        }
        npcU(ZfeNpcs.bartender, ZfeObjs.portrait_good) { goodPortraitOnBartender(it) }
        npcU(ZfeNpcs.irwin, ZfeObjs.page) { runQuestSection(it, dialogue, "Using the torn page on Irwin Feaselbaum") }
        npcU(ZfeNpcs.irwin, ZfeObjs.necro_book) {
            runQuestSection(it, dialogue, "Using the necromancy book on Irwin Feaselbaum")
        }
        npcU(ZfeNpcs.zavistic, ZfeObjs.prism) { prismOnZavistic(it) }
        npcU(ZfeNpcs.zavistic, ZfeObjs.page) { runQuestSection(it, dialogue, "Use the torn page on Zavistic Rarve") }
        npcU(ZfeNpcs.zavistic, ZfeObjs.portrait_bad) {
            runQuestSection(it, dialogue, "Using the bad portrait on Zavistic Rarve")
        }
        npcU(ZfeNpcs.zavistic, ZfeObjs.portrait_good) {
            runQuestSection(it, dialogue, "Using the good portrait on Zavistic Rarve")
        }
        for (balm in ZfeObjs.balms) npcU(ZfeNpcs.uglug, balm) { balmOnUglug(it, balm) }

        // Sithik Ints (the bed multiloc's two children) and his room.
        for (bed in ZfeLocs.beds) {
            locOp(bed, "Talk-to") { talkSithik() }
            locU(bed, ZfeObjs.prism) { sithikUse("Use Black prism on Sithik Ints") }
            locU(bed, ZfeObjs.page) { sithikUse("Use Torn page on Sithik Ints") }
            locU(bed, ZfeObjs.tankard) { sithikUse("Use Dragon inn tankard on Sithik Ints") }
            locU(bed, ZfeObjs.ham_book) { sithikUse("Using the Book of 'H.A.M' on Sithik Ints") }
            locU(bed, ZfeObjs.necro_book) { sithikUse("Use Necromancy book on Sithik Ints") }
            locU(bed, ZfeObjs.portrait_book) { sithikUse("Use Book of portraiture on Sithik Ints") }
            locU(bed, ZfeObjs.portrait_bad) { sithikUse("Using the bad portrait on Sithik Ints") }
            locU(bed, ZfeObjs.portrait_good) { sithikUse("Using the good portrait on Sithik Ints") }
            locU(bed, ZfeObjs.portrait_signed) { sithikUse("Using the Signed portrait on Sithik Ints") }
            locU(bed, ZfeObjs.potion) { sithikUse("Using strange potion on Sithik Ints") }
            locU(bed, ZfeObjs.papyrus) { drawPortrait() }
        }
        locOp(ZfeLocs.drawers, "Search") { searchFurniture(ZfeLocs.drawers) }
        locOp(ZfeLocs.cupboard, "Search") { searchFurniture(ZfeLocs.cupboard) }
        locOp(ZfeLocs.wardrobe, "Search") { searchFurniture(ZfeLocs.wardrobe) }
        locU(ZfeLocs.drawers, ZfeObjs.potion) { potionInTea() }
        onOpObj3(ZfeObjs.tea) { sithikUse("Taking Cup of tea by Sithik Ints") }
    }

    /* Guarded binds (another module may already own an op or a use pair). */

    private fun ScriptContext.locOp(loc: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[loc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isLocOpBound(loc, op)) return
        onLocOpLabel(locTypes, loc, label, action)
    }

    private fun ScriptContext.npcOp(npc: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[npc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op == 0 || isNpcOpBound(npc, op)) return
        onNpcOpLabel(npcTypes, npc, label, action)
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
        get() = player.questState(ZOGRE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(ZOGRE, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.hasFlag(flag: Int): Boolean = (player.vars[ZfeVarps.progress] and flag) != 0

    private fun ProtectedAccess.setFlag(flag: Int) {
        VarPlayerIntMapSetter.set(player, ZfeVarps.progress, player.vars[ZfeVarps.progress] or flag)
    }

    private suspend fun ProtectedAccess.runNodes(npc: Npc?, nodes: List<Node>, hooks: QuestDialogueHooks, speakers: Map<String, NpcType>) {
        if (npc != null) {
            startDialogue(npc) { QuestDialogueRunner(hooks, speakers).run(this, nodes) }
        } else {
            startDialogue { QuestDialogueRunner(hooks, speakers).run(this, nodes) }
        }
    }

    /* Grish, the Ogre guard and Uglug */

    private fun ProtectedAccess.meetsRequirements(): Boolean =
        player.isQuestComplete(BIG_CHOMPY) &&
            player.isQuestComplete(JUNGLE_POTION) &&
            stat(stats.smithing) >= SMITHING_REQ &&
            stat(stats.herblore) >= HERBLORE_REQ &&
            statBase(stats.ranged) >= RANGED_REQ

    private suspend fun ProtectedAccess.talkGrish(npc: Npc) {
        when {
            state == 0 -> {
                var start = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player doesn't have the requirements to start the quest:" to { !meetsRequirements() }),
                        boxes = mapOf("Goes to ground with full inventory" to { start = true; true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Grish", hooks, grishSpeakers)
                if (start) {
                    repeat(2) { player.invAddOrDrop(objRepo, ZfeObjs.super_restore) }
                    repeat(3) { player.invAddOrDrop(objRepo, ZfeObjs.cooked_chompy) }
                    setState(STARTED)
                }
            }
            state < ANSWERED -> runQuestSection(npc, dialogue, "Talking to Grish after starting the quest", speakers = grishSpeakers)
            state == ANSWERED -> {
                var key = false
                val hooks =
                    SimpleQuestHooks(
                        options = mapOf(COMP_BOW_OPTION to { setBit(ZfeVarBits.compbow, 1) }),
                        boxes = mapOf("Drops to the ground if inventory is full." to { key = true; true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Grish (2)", hooks, grishSpeakers)
                if (key) {
                    player.invAddOrDrop(objRepo, ZfeObjs.gate_key)
                    setState(KEY)
                }
            }
            state == KEY && !carries(ZfeObjs.gate_key) && !carries(ZfeObjs.artefact) -> {
                runQuestSection(npc, dialogue, "Talking to Grish after losing the Ogre gate key", speakers = grishSpeakers)
                player.invAddOrDrop(objRepo, ZfeObjs.gate_key)
            }
            state == KEY && !carries(ZfeObjs.artefact) -> {
                val hooks = SimpleQuestHooks(options = mapOf(EASIER_OPTION to { setBit(ZfeVarBits.compbow, 1) }))
                runQuestSection(npc, dialogue, "Talking to Grish after getting the Ogre gate key", hooks, grishSpeakers)
            }
            state < COMPLETE -> {
                var handIn = false
                val hooks = SimpleQuestHooks(options = mapOf("Yeah, I have them here!" to { handIn = carries(ZfeObjs.artefact) }))
                runQuestSection(npc, dialogue, "Talking to Grish after killing Slash Bash", hooks, grishSpeakers)
                if (handIn) finish()
            }
            else -> runQuestBranch(npc, dialogue, "Talking to Grish after killing Slash Bash", "How's everything going now?")
        }
    }

    private fun ProtectedAccess.finish() {
        if (!carries(ZfeObjs.artefact)) return
        invDel(inv, ZfeObjs.artefact)
        while (carries(ZfeObjs.gate_key)) invDel(inv, ZfeObjs.gate_key)
        setBit(ZfeVarBits.compbow, 1)
        statAdvance(stats.fletching, REWARD_XP * xpMods.get(player, stats.fletching))
        statAdvance(stats.ranged, REWARD_XP * xpMods.get(player, stats.ranged))
        statAdvance(stats.herblore, REWARD_XP * xpMods.get(player, stats.herblore))
        completeQuest(ZOGRE)
    }

    private suspend fun ProtectedAccess.talkGuard(npc: Npc) {
        if (state == STARTED && bit(ZfeVarBits.barricade) == 0) {
            val hooks =
                SimpleQuestHooks(
                    actions =
                        mapOf(
                            "The Ogre guard crushes the barricade." to {
                                setBit(ZfeVarBits.barricade, 1)
                                setState(INSIDE)
                            }
                        )
                )
            runQuestSection(npc, dialogue, "Talking to the Ogre guard", hooks)
            return
        }
        // The section's first line (Wiki) for every other visit.
        startDialogue(npc) {
            chatNpc(neutral, "Yous needs ta stay away from dis place...yous get da sickies and mebe yous goes to dead if yous da unlucky fing.")
        }
    }

    private suspend fun ProtectedAccess.balmOnUglug(npc: Npc, balm: ObjType) {
        if (bit(ZfeVarBits.cure) == 0) {
            runQuestSection(npc, dialogue, UGLUG_BEFORE)
            return
        }
        if (bit(ZfeVarBits.sold_balm) != 0) {
            // Authored (not Wiki): he only buys the first one.
            startDialogue(npc) { chatNpc(happy, "Me's already got da sickies glug-glug from yous creature. Yous trade wiv Uglug now!") }
            return
        }
        val price = BALM_PRICES.getValue(ZfeObjs.balms.indexOf(balm) + 1)
        runQuestSection(npc, dialogue, "Use Relicym's balm on Uglug Nar", SimpleQuestHooks(rewrites = { it.replace("650", "$price") }))
        invDel(inv, balm)
        invAdd(inv, ZfeObjs.coins, price)
        setBit(ZfeVarBits.sold_balm, 1)
    }

    /* Jiggig and its dungeon */

    private suspend fun ProtectedAccess.climbBarricade() {
        val z = player.coords.z.coerceIn(BARRICADE_Z_MIN, BARRICADE_Z_MAX)
        val dest = if (player.coords.x <= BARRICADE_X) CoordGrid(BARRICADE_X + 1, z, 0) else CoordGrid(BARRICADE_X - 1, z, 0)
        delay(1)
        telejump(dest)
    }

    private suspend fun ProtectedAccess.climbDown() {
        mes("You climb down the steps.")
        delay(1)
        telejump(if (player.coords.level == 2) BASH_FLOOR else DUNGEON_ARRIVAL)
    }

    private suspend fun ProtectedAccess.climbUp() {
        mes("You climb up the steps.")
        delay(1)
        telejump(if (player.coords.level == 0 && player.coords.z > SURFACE_Z_MAX) BASH_UP else SURFACE_ARRIVAL)
    }

    private suspend fun ProtectedAccess.searchLectern() {
        if (state < INSIDE || state >= ANSWERED || carries(ZfeObjs.page)) {
            mes("You search the broken down lectern.")
            mes("You find nothing of interest.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Search Broken Lecturn")
        invAdd(inv, ZfeObjs.page)
    }

    private suspend fun ProtectedAccess.searchSkeleton() {
        val uid = player.uid.packed
        val alive = zombies[uid]?.takeIf { it.isSlotAssigned }
        if (alive != null) return
        val haveClue = carries(ZfeObjs.backpack) || carries(ZfeObjs.tankard)
        val needed = bit(ZfeVarBits.brentle) == 0 || (!haveClue && state < CONVINCED)
        if (state < INSIDE || !needed) {
            mes("You search the skeleton but find nothing.") // Authored (not Wiki).
            return
        }
        var attack = false
        runQuestSection(null, dialogue, "Search skeleton", SimpleQuestHooks(actions = mapOf("The Zombie attacks." to { attack = true })))
        if (!attack) return
        val zombie = Npc(npcTypes[ZfeNpcs.zombie], ZOMBIE_SPAWN)
        npcRepo.add(zombie, FIGHT_TICKS)
        zombies[uid] = zombie
        zombie.opPlayer2(player, aiInteractions)
    }

    private suspend fun ProtectedAccess.openBackpack() {
        if (inv.freeSpace() + 1 < BACKPACK_SPACE) {
            mes("You don't have enough room in your inventory for the contents of this bag.")
            return
        }
        startDialogue {
            mesbox("Just before you open the backpack, you notice a small leather patch with the moniker: 'B.Vahn', on it.")
            mesbox("You find an interesting looking tankard.")
            mesbox("You find a knife and some rotten food, the backpack is ripped to shreds.")
        }
        invReplace(inv, ZfeObjs.backpack, 1, ZfeObjs.tankard)
        invAdd(inv, ZfeObjs.knife)
        invAdd(inv, ZfeObjs.rotten_food)
    }

    private suspend fun ProtectedAccess.searchCoffin() {
        when (bit(ZfeVarBits.prismsearch)) {
            0 -> {
                if (state < INSIDE) {
                    mes("You find nothing of interest.") // Authored (not Wiki).
                    return
                }
                runQuestSection(null, dialogue, "Search Ogre Coffin")
                setBit(ZfeVarBits.prismsearch, 1)
            }
            1 -> runQuestSection(null, dialogue, "Search Ogre Coffin")
            2 -> liftLid()
            3 -> {
                if (inv.isFull()) {
                    mes("You don't have enough inventory space.") // Authored (not Wiki).
                    return
                }
                runQuestSection(null, dialogue, "Search the Ogre Coffin after it is opened")
                invAdd(inv, ZfeObjs.prism)
                setBit(ZfeVarBits.prismsearch, 4)
            }
            else ->
                if (!carries(ZfeObjs.prism) && bit(ZfeVarBits.prismsold) == 0 && !inv.isFull()) {
                    // Authored (not Wiki): a lost prism is still lying in the coffin.
                    runQuestSection(null, dialogue, "Search the Ogre Coffin after it is opened")
                    invAdd(inv, ZfeObjs.prism)
                } else {
                    mes("You search the coffin but find nothing.") // Authored (not Wiki).
                }
        }
    }

    private suspend fun ProtectedAccess.liftLid() {
        val full = inv.isFull()
        val chance = (LID_BASE + stat(stats.strength) * 2).coerceAtMost(LID_MAX)
        val lifted = random.of(0, 99) < chance
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have enough inventory space:" to { full },
                        "If the player fails:" to { !lifted },
                        "If the player succeeds:" to { lifted },
                    )
            )
        runQuestSection(null, dialogue, "Search Ogre Coffin after unlocking it", hooks)
        if (full) return
        if (lifted) {
            setBit(ZfeVarBits.prismsearch, 3)
        } else {
            statSub(stats.strength, 1, 0)
        }
    }

    private suspend fun ProtectedAccess.knifeCoffin() {
        when (bit(ZfeVarBits.prismsearch)) {
            0 -> searchCoffin()
            1 -> {
                runQuestSection(null, dialogue, "Use knife on Ogre Coffin")
                setBit(ZfeVarBits.prismsearch, 2)
            }
            else -> mes("The coffin is already unlocked.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.openDoor(door: BoundLocInfo) {
        val leaving = player.coords.z < door.coords.z
        val key = carries(ZfeObjs.gate_key)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the ogre gate key:" to { !leaving && key },
                        "If the player does not have the ogre gate key:" to { !leaving && !key },
                        "If the player is leaving:" to { leaving },
                    )
            )
        runQuestSection(null, dialogue, "Open Ogre stone door", hooks)
        if (leaving || key) passThroughWall(door)
    }

    private suspend fun ProtectedAccess.searchStand() {
        when {
            state == KEY -> {
                val existing = bashes[player.uid.packed]
                if (existing != null && existing.isSlotAssigned) return
                var spawn = false
                runQuestSection(null, dialogue, "Search Stand", SimpleQuestHooks(actions = mapOf("Slash Bash spawns and attacks." to { spawn = true })))
                if (!spawn) return
                val bash = Npc(npcTypes[ZfeNpcs.slash_bash], BASH_SPAWN)
                npcRepo.add(bash, BASH_TICKS)
                bashes[player.uid.packed] = bash
                bash.opPlayer2(player, aiInteractions)
            }
            state in KILLED until COMPLETE -> {
                val has = carries(ZfeObjs.artefact)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player still has the artefact:" to { has },
                                "If the player lost the Ogre artefact:" to { !has },
                            )
                    )
                runQuestSection(null, dialogue, "Search the stand again", hooks)
                if (!has) player.invAddOrDrop(objRepo, ZfeObjs.artefact)
            }
            else -> {
                mes("You search the plinth...")
                mes("You find nothing in particular.")
            }
        }
    }

    private fun bashDamage(hit: HitBuilder) {
        if (!hit.isFromPlayer) return
        hit.damage =
            when {
                ZfeObjs.brutals.any { hit.isSecondaryObj(it) } -> hit.damage
                hit.isSecondaryObj(ZfeObjs.crumble_undead) -> hit.damage / 2
                else -> hit.damage / 4
            }
    }

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        when (event.npc.type.id) {
            ZfeNpcs.zombie.id -> {
                zombies.remove(player.uid.packed)
                VarPlayerIntMapSetter.set(player, ZfeVarBits.brentle, 1)
                drop(player, event.npc.coords, ZfeObjs.backpack)
            }
            ZfeNpcs.slash_bash.id -> {
                bashes.remove(player.uid.packed)
                if (player.questState(ZOGRE) == KEY) player.setQuestState(ZOGRE, KILLED)
                val at = event.npc.coords
                drop(player, at, ZfeObjs.artefact)
                repeat(3) { drop(player, at, ZfeObjs.ourg_bones) }
                repeat(2) { drop(player, at, ZfeObjs.zogre_bones) }
            }
        }
    }

    private fun drop(player: Player, at: CoordGrid, obj: ObjType) {
        objRepo.add(Obj.fromOwner(player, at, obj, 1), DROP_TICKS)
    }

    /* Yanille: the bartender and Zavistic */

    private suspend fun ProtectedAccess.tankardOnBartender(npc: Npc) {
        if (bit(ZfeVarBits.mugshown) == 0) {
            runQuestSection(npc, dialogue, "Using the Dragon inn tankard on the Dragon Inn Bartender")
            setBit(ZfeVarBits.mugshown, 1)
        } else {
            runQuestSection(npc, dialogue, "Using the Dragon inn tankard on the Dragon Inn Bartender again")
        }
    }

    private suspend fun ProtectedAccess.goodPortraitOnBartender(npc: Npc) {
        runQuestSection(npc, dialogue, "Using the good portrait on the Dragon Inn Bartender")
        invReplace(inv, ZfeObjs.portrait_good, 1, ZfeObjs.portrait_signed)
        setBit(ZfeVarBits.portraitshown, 1)
    }

    private suspend fun ProtectedAccess.prismOnZavistic(npc: Npc) {
        when {
            state >= ANSWERED && bit(ZfeVarBits.prismsold) == 0 -> {
                startDialogue(npc) {
                    // Authored (not Wiki): the reward he promised for the prism.
                    chatPlayer(neutral, "You asked me to bring you this black prism once the problems at Jiggig were resolved.")
                    chatNpc(happy, "Ah yes, splendid! It'll be quite safe with us. Here, take this for your trouble.")
                }
                invDel(inv, ZfeObjs.prism)
                invAdd(inv, ZfeObjs.coins, PRISM_PRICE)
                setBit(ZfeVarBits.prismsold, 1)
                mes("You hand over the black prism and get $PRISM_PRICE coins.") // Authored (not Wiki).
            }
            hasFlag(PRISM_SHOWN) -> runQuestSection(npc, dialogue, "Use the black prism on Zavistic Rarve after he has already it", speakers = zavisticSpeakers)
            else -> talkZavistic(npc)
        }
    }

    private suspend fun ProtectedAccess.talkZavistic(npc: Npc?) {
        when {
            state < SENT -> firstClues(npc)
            state < CONVINCED -> evidence(npc)
            state < OGRE -> {
                val lost = !carries(ZfeObjs.potion) && state == CONVINCED
                var give = false
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If the player has lost the potion:" to { lost }),
                        boxes = mapOf("Drops to the ground with full inventory." to { give = true; true }),
                    )
                runQuestSection(npc, dialogue, "Talking to Zavistic Rarve after getting the potion", hooks, zavisticSpeakers)
                if (give) player.invAddOrDrop(objRepo, ZfeObjs.potion)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Zavistic Rarve after giving Sithik the potion", speakers = zavisticSpeakers)
        }
    }

    private suspend fun ProtectedAccess.firstClues(npc: Npc?) {
        val prism = carries(ZfeObjs.prism)
        val page = carries(ZfeObjs.page)
        val tankard = carries(ZfeObjs.tankard)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the Black prism but not the Torn page:" to { prism && !page },
                        "If the player has the Torn page but not the Black prism:" to { page && !prism },
                        "If the player has the Black prism and Torn page:" to { prism && page },
                        "If the player has the Dragon inn tankard:" to { tankard },
                    )
            )
        val bell = dialogue.section(BELL)
        val nodes =
            if (npc == null) {
                bell
            } else {
                // Inside the guild: his greeting (the Wiki's non-Hand-in-the-Sand variant) instead of the bell's.
                val inside =
                    dialogue.section("Talking to Zavistic Rarve inside the guild")
                        .filterIsInstance<Node.Cond>()
                        .first { it.text.startsWith("If the player has not started") }
                        .children
                        .filter { it is Node.Line }
                inside + bell.dropWhile { it is Node.Line }
            }
        runNodes(npc, nodes, hooks, zavisticSpeakers)
        if (prism) setFlag(PRISM_SHOWN)
        if (prism && page) setState(SENT)
    }

    private suspend fun ProtectedAccess.evidence(npc: Npc?) {
        val items =
            listOf(
                ZfeObjs.necro_book to ZfeVarBits.necro_shown,
                ZfeObjs.ham_book to ZfeVarBits.ham_shown,
                ZfeObjs.tankard to ZfeVarBits.tankard_shown,
                ZfeObjs.portrait_signed to ZfeVarBits.signed_shown,
            )
        val shownBefore = items.map { (_, varbit) -> bit(varbit) != 0 }
        val has = items.map { (obj, _) -> carries(obj) }
        fun allShown() = items.all { (_, varbit) -> bit(varbit) != 0 }
        var convinced = false
        var oneItem = false
        val handOver: suspend () -> Boolean = {
            convinced = true
            false
        }
        fun show(index: Int): suspend () -> Boolean = {
            setBit(items[index].second, 1)
            false
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the necromancy book:" to { has[0] },
                        "If the player has shown Zavistic the necromancy book before:" to { shownBefore[0] },
                        "If the player has the Book of 'H.A.M':" to { has[1] },
                        "If the player has shown Zavistic the Book of 'H.A.M' before:" to { shownBefore[1] },
                        "If the player has the dragon inn tankard:" to { has[2] },
                        "If the player has shown Zavistic the Dragon Inn tankard before:" to { shownBefore[2] },
                        "If the player has the signed portrait:" to { has[3] },
                        "If the player has shown Zavistic the signed portrait before:" to { shownBefore[3] },
                        "If the player has not yet shown all 4 pieces of evidence:" to { !allShown() },
                        "If the player has shown all 4 pieces of evidence:" to { allShown() },
                    ),
                options = mapOf("I have an item that I'd like you to look at." to { oneItem = true }),
                boxes =
                    mapOf(
                        "You show the Necromancy book to Zavistic." to show(0),
                        "You show the HAM book to Zavistic." to show(1),
                        "You show the dragon Inn Tankard to Zavistic." to show(2),
                        "You show the signed portrait of Sithik to Zavistic." to show(3),
                        "Zavistic hands you a strange looking potion bottle and takes all the evidence you've accumulated so far." to
                            handOver,
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Zavistic Rarve after gathering evidence", hooks, zavisticSpeakers)
        if (oneItem) {
            runQuestBranch(npc, dialogue, "Talking to Zavistic Rarve after gathering evidence", "I have some items that I'd like you to look at.", hooks, zavisticSpeakers)
        }
        if (convinced) {
            for ((obj, _) in items) if (carries(obj)) invDel(inv, obj)
            player.invAddOrDrop(objRepo, ZfeObjs.potion)
            setState(CONVINCED)
        }
    }

    /* Sithik Ints */

    private fun ProtectedAccess.sithikSpeakers(): Map<String, NpcType> {
        val ogre = bit(ZfeVarBits.transformed) == 1
        return mapOf("Sithik Ints" to npcTypes[if (ogre) ZfeNpcs.sithik_ogre else ZfeNpcs.sithik_man])
    }

    private suspend fun ProtectedAccess.sithikUse(section: String) {
        if (bit(ZfeVarBits.transformed) == 1) {
            talkSithik()
            return
        }
        runQuestSection(null, dialogue, section, speakers = sithikSpeakers())
    }

    private suspend fun ProtectedAccess.talkSithik() {
        when {
            state < SENT -> runQuestSection(null, dialogue, "Talking to Sithik Ints before talking to Zavistic Rarve", speakers = sithikSpeakers())
            state < CONVINCED -> {
                val asked = hasFlag(LOOK_ASKED)
                val hooks =
                    SimpleQuestHooks(
                        conditions = mapOf("If asking for the first time:" to { !asked }, "If asking him again:" to { asked }),
                        options = mapOf("Do you mind if I look around?" to { setFlag(LOOK_ASKED) }),
                    )
                runQuestSection(null, dialogue, "Talking to Sithik Ints", hooks, sithikSpeakers())
            }
            state == POURED -> {
                setBit(ZfeVarBits.transformed, 1)
                setState(OGRE)
                // Authored (not Wiki): the potion has done its work while you were away.
                mes("Something seems to have happened to Sithik...")
                questionOgre(first = true)
            }
            state == OGRE -> questionOgre(first = false)
            else -> runQuestSection(null, dialogue, "Talking to Sithik Ints after convincing Zavistic Rarve", speakers = sithikSpeakers())
        }
    }

    private suspend fun ProtectedAccess.questionOgre(first: Boolean) {
        val form = dialogue.section("Talking to Sithik Ints in ogre form")
        val nodes =
            if (first) {
                form
            } else {
                dialogue.section("Talking to Ogre Sithik again").filterIsInstance<Node.Line>() +
                    form.dropWhile { it !is Node.Select }
            }
        var firstTime = true
        fun ask(flag: Int): suspend () -> Unit = {
            firstTime = !hasFlag(flag)
            setFlag(flag)
            when (flag) {
                Q_ARROWS -> setBit(ZfeVarBits.brutal, 1)
                Q_DISEASE -> setBit(ZfeVarBits.cure, 1)
            }
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Asking for the first time:" to { firstTime },
                        "Asking him again:" to { !firstTime },
                        "Asking him again as an ogre:" to { !firstTime },
                        "Asking him again as a human:" to { false },
                    ),
                options =
                    mapOf(
                        "How do I remove the effects of the spell from the area?" to ask(Q_SPELL),
                        "How do I get rid of the undead ogres?" to ask(Q_ARROWS),
                        "How do I get rid of the disease?" to ask(Q_DISEASE),
                    ),
            )
        runNodes(null, nodes, hooks, sithikSpeakers())
        if (hasFlag(Q_SPELL) && hasFlag(Q_ARROWS) && hasFlag(Q_DISEASE)) {
            setBit(ZfeVarBits.transformed, 2)
            setState(ANSWERED)
            mes("Sithik slowly turns back into his human form.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.drawPortrait() {
        if (bit(ZfeVarBits.transformed) == 1 || state < SENT) {
            sithikUse("Talking to Sithik Ints before talking to Zavistic Rarve")
            return
        }
        val charcoal = carries(ZfeObjs.charcoal)
        val good = random.of(0, 99) < (PORTRAIT_BASE + stat(stats.crafting)).coerceAtMost(PORTRAIT_MAX)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have charcoal" to { !charcoal },
                        "If the player has charcoal" to { charcoal },
                        "If the player fails at painting:" to { !good },
                        "If the player succeeds at painting:" to { good },
                    )
            )
        runQuestSection(null, dialogue, "Use papyrus on Sithik Ints", hooks, sithikSpeakers())
        if (!charcoal) return
        invReplace(inv, ZfeObjs.papyrus, 1, if (good) ZfeObjs.portrait_good else ZfeObjs.portrait_bad)
    }

    private suspend fun ProtectedAccess.searchFurniture(loc: LocType) {
        when {
            state >= CONVINCED -> runQuestSection(null, dialogue, "Searching the Drawers, wardrobe, or cupboard after convincing Zavistic Rarve")
            state < SENT || !hasFlag(LOOK_ASKED) ->
                runQuestSection(null, dialogue, "Search Drawers, Cupboard, or Wardrobe", speakers = sithikSpeakers())
            loc == ZfeLocs.drawers -> searchDrawers()
            else -> {
                val (obj, section) =
                    if (loc == ZfeLocs.wardrobe) ZfeObjs.ham_book to "Search Wardrobe" else ZfeObjs.necro_book to "Search Cupboard"
                if (carries(obj)) {
                    mes("You search but find nothing of significance.")
                    return
                }
                val full = inv.isFull()
                runQuestSection(null, dialogue, section, SimpleQuestHooks(conditions = mapOf("If the player has no inventory space:" to { full })))
                if (!full) invAdd(inv, obj)
            }
        }
    }

    private suspend fun ProtectedAccess.searchDrawers() {
        val missPapyrus = !carries(ZfeObjs.papyrus)
        val missCharcoal = !carries(ZfeObjs.charcoal)
        val missBook = !carries(ZfeObjs.portrait_book)
        val needed = listOf(missPapyrus, missCharcoal, missBook).count { it }
        if (needed == 0) {
            mes("You search but find nothing of significance.")
            return
        }
        val full = inv.freeSpace() < needed
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have 3 inventory spaces:" to { full },
                        "If the player has papyrus but not charcoal:" to { missPapyrus && !missCharcoal },
                        "If the player has charcoal but not papyrus:" to { missCharcoal && !missPapyrus },
                        "If the player has neither:" to { missPapyrus && missCharcoal },
                    )
            )
        runQuestSection(null, dialogue, "Search Drawers", hooks)
        if (full) return
        if (missPapyrus) invAdd(inv, ZfeObjs.papyrus)
        if (missCharcoal) invAdd(inv, ZfeObjs.charcoal)
        if (missBook) invAdd(inv, ZfeObjs.portrait_book)
    }

    private suspend fun ProtectedAccess.potionInTea() {
        if (state != CONVINCED) {
            mes("Nothing interesting happens.")
            return
        }
        runQuestSection(null, dialogue, "Using strange potion on cup of tea")
        invReplace(inv, ZfeObjs.potion, 1, ZfeObjs.sample_bottle)
        setState(POURED)
    }

    companion object {
        const val STARTED = 1
        const val INSIDE = 2
        const val SENT = 4
        const val CONVINCED = 6
        const val POURED = 7
        const val OGRE = 8
        const val ANSWERED = 9
        const val KEY = 10
        const val KILLED = 11
        const val COMPLETE = 14

        /* zogre_progress bits. */
        private const val LOOK_ASKED = 1
        private const val Q_SPELL = 2
        private const val Q_ARROWS = 4
        private const val Q_DISEASE = 8
        private const val PRISM_SHOWN = 16

        private const val SMITHING_REQ = 4
        private const val HERBLORE_REQ = 8
        private const val RANGED_REQ = 30
        private const val REWARD_XP = 2000.0
        private const val PRISM_PRICE = 2000
        private val BALM_PRICES = mapOf(1 to 100, 2 to 300, 3 to 650, 4 to 1000)

        private const val BACKPACK_SPACE = 3
        private const val FIGHT_TICKS = 300
        private const val BASH_TICKS = 500 // Wiki: he despawns after five minutes.
        private const val DROP_TICKS = 200
        private const val LID_BASE = 30 // Authored lid roll: 30% + 2% per Strength level, max 90%.
        private const val LID_MAX = 90
        private const val PORTRAIT_BASE = 40 // Authored sketch roll: 40% + 1% per Crafting level.
        private const val PORTRAIT_MAX = 95

        private const val BARRICADE_X = 2456
        private const val BARRICADE_Z_MIN = 3048
        private const val BARRICADE_Z_MAX = 3049
        private const val SURFACE_Z_MAX = 4000
        private val DUNGEON_ARRIVAL = CoordGrid(2477, 9437, 2)
        private val SURFACE_ARRIVAL = CoordGrid(2484, 3043, 0)
        private val BASH_FLOOR = CoordGrid(2446, 9418, 0)
        private val BASH_UP = CoordGrid(2441, 9418, 2)
        private val BASH_SPAWN = CoordGrid(2479, 9442, 0)
        private val ZOMBIE_SPAWN = CoordGrid(2442, 9457, 2)

        private const val UGLUG_BEFORE = "Talking to Uglug Nar (before using Relicym's balm on him)"
        private const val BELL = "Ringing the Bell outside the Wizards' Guild"
        private const val COMP_BOW_OPTION = "I've got some information on how to kill the zogres from a distance."
        private const val EASIER_OPTION = "There must be an easier way to kill these zogres!"

        /** Zavistic's sections whose guild small-talk options have no Wiki text: dropped. */
        private val PRUNED =
            listOf(
                BELL,
                "Talking to Zavistic Rarve after gathering evidence",
                "Talking to Zavistic Rarve after getting the potion",
                "Talking to Zavistic Rarve after giving Sithik the potion",
            )

        private val EMPTY_TAGS = setOf("above", "other", "previous")

        /** Drops options with no Wiki answer (empty or a bare "above"), and menus left empty. */
        private fun prune(nodes: MutableList<Node>) {
            nodes.removeAll { node ->
                node is Node.Option && node.children.all { it is Node.Action && it.tag.lowercase() in EMPTY_TAGS }
            }
            for (node in nodes) prune(node.children)
            val iterator = nodes.listIterator()
            while (iterator.hasNext()) {
                val node = iterator.next()
                if (node is Node.Select) {
                    val next = nodes.getOrNull(iterator.nextIndex())
                    if (next !is Node.Option) iterator.remove()
                }
            }
        }

        val ZOGRE =
            QuestDef(
                name = "Zogre Flesh Eaters",
                varbit = { ZfeVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards =
                    listOf(
                        "2,000 Fletching XP",
                        "2,000 Ranged XP",
                        "2,000 Herblore XP",
                        "Relicym's balm and brutal arrows",
                        "Comp ogre bows",
                    ),
                scrollObj = { ZfeObjs.artefact },
            )
    }
}

/** The Wizards' Guild bell is Hand in the Sand's loc; it asks Zogre first when that quest is idle. */
object ZogreBell {
    var ring: (suspend ProtectedAccess.() -> Boolean)? = null
}

// Every cache ref Zogre Flesh Eaters uses (re-check after cache updates).

internal object ZfeNpcs : NpcReferences() {
    val grish = find("zogre_ogre_shaman")
    val uglug = find("zogre_uglug_nar")
    val guard = find("zogre_ogre_guard")
    val zavistic = find("zogre_human_zavistic_rarve")
    val zombie = find("zogre_human_brentle_vahn")
    val slash_bash = find("zogre_slash_bash")
    val sithik_man = find("zogre_sithik_man")
    val sithik_ogre = find("zogre_sithik_ogre")
    val irwin = find("zogre_necromancers_assistant")
    val bartender = find("dragon_bartender")
}

internal object ZfeLocs : LocReferences() {
    val barricade_l = find("ogre_barricade_collapsedl")
    val barricade_r = find("ogre_barricade_collapsedr")
    val stairs_down = find("ogre_stairs_down")
    val stairs_up = find("ogre_stairs")
    val lectern = find("zogre_lecturn")
    val skeleton = find("zogre_brentle_skeleton")
    /** Children of `zogre_coffin_special_entity` (`thzfe_prismsearch` 0-2 / 3-5). */
    val coffins = listOf(find("zogre_coffin_special"), find("zogre_coffin_special_searched"))
    val doors = listOf(find("ogre_cavedoorr"), find("ogre_cavedoorl"))
    val stand = find("zogre_stand")
    val drawers = find("sithiks_drawers")
    val cupboard = find("sithiks_cupboard")
    val wardrobe = find("sithiks_wardrobe")
    /** Children of `zogre_sithik_bed_entity` (`thzfe_sithik_transformed` 0/2 man, 1 ogre). */
    val beds = listOf(find("ogre_bedman_loc"), find("ogre_bedogre_loc"))
}

internal object ZfeObjs : ObjReferences() {
    val prism = find("zogre_black_prism")
    val page = find("zogre_necromantic_page")
    val backpack = find("zogre_brentle_vahn_backpack")
    val tankard = find("zogre_dragon_tankard")
    val portrait_good = find("zogre_sithik_portrait_good")
    val portrait_bad = find("zogre_sithik_portrait_bad")
    val portrait_signed = find("zogre_sithik_portrait_signed")
    val portrait_book = find("zogre_portrait_book")
    val artefact = find("zogre_artifacts")
    val ham_book = find("zogre_hambook")
    val necro_book = find("zogre_necrobook")
    val potion = find("zogre_ogre_trans_potion")
    val tea = find("zogre_cup_of_tea_sithix")
    val gate_key = find("zogre_tomb_artefact_key")
    /** "Sample bottle" (what the strange potion leaves). */
    val sample_bottle = find("empty_dye_bottle")
    val knife = find("knife")
    val rotten_food = find("rotten_food")
    val papyrus = find("papyrus")
    val charcoal = find("charcoal")
    val coins = find("coins")
    val super_restore = find("4dose2restore")
    val cooked_chompy = find("cooked_chompy")
    val ourg_bones = find("zogre_ancestral_bones_ourg")
    val zogre_bones = find("zogre_bones")
    val crumble_undead = find("39_crumble_undead")
    /** Relicym's balm (1) .. (4). */
    val balms = listOf(find("relicyms_balm1"), find("relicyms_balm2"), find("relicyms_balm3"), find("relicyms_balm4"))
    val brutals =
        listOf(
            find("zogre_brutal_bronze"),
            find("zogre_brutal_iron"),
            find("zogre_brutal_steel"),
            find("zogre_brutal_black"),
            find("zogre_brutal_mithril"),
            find("zogre_brutal_adamant"),
            find("zogre_brutal_rune"),
        )
}

internal object ZfeVarBits : VarBitReferences() {
    val quest = find("zogre")
    val prismsearch = find("thzfe_prismsearch")
    val mugshown = find("thzfe_innkeepermugshown")
    val portraitshown = find("thzfe_innkeeperportraitshown")
    val necro_shown = find("thzfe_shownnecrobook")
    val ham_shown = find("thzfe_shownhambook")
    val tankard_shown = find("thzfe_showntankard")
    val signed_shown = find("thzfe_shownsignedportrait")
    val transformed = find("thzfe_sithik_transformed")
    val barricade = find("thzfe_blocking_barricade")
    val cure = find("thzfe_makecuredisease")
    val brutal = find("thzfe_makebrutalarrow")
    val compbow = find("thzfe_makecompozogrebow")
    val sold_balm = find("thzfe_sold_balm")
    val brentle = find("thzfe_brentle_skele")
    val prismsold = find("thzfe_prismsold")
}

internal object ZfeVarps : VarpReferences() {
    /** Server varp (65975): bit 0 asked Sithik to look around, 1-3 his three answers, 4 prism shown. */
    val progress = find("zogre_progress")
}

internal object ZfeVarpBuilds : VarpBuilder() {
    init {
        build("zogre_progress")
    }
}
