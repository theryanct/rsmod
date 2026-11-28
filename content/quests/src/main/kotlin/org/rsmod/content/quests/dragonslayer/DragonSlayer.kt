package org.rsmod.content.quests.dragonslayer

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.cutscene.CutsceneRunner
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questPoints
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
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
 * Dragon Slayer I (OSRS Wiki). `dragonquest` varp (cache db end state 10; the cache multinpcs show
 * Elvarg for 1-8, Jenkins on the ship up to 7, Ned on the ship from 7 and on Crandor from 8):
 * 1 the Guildmaster sent you to Oziach, 2 Oziach's task (kill Elvarg), 3 the Guildmaster explained
 * the map / ship / shield, 7 Ned hired (map handed over), 8 shipwrecked on Crandor, 9 Elvarg slain,
 * 10 complete (Oziach or the Guildmaster: 18,650 Strength and Defence xp, 2 QP). 32 QP to start
 * when the QuestGates toggle is on.
 *
 * The map: Melzar's piece (maze key from the Guildmaster; red / orange / yellow / blue keys from
 * the zombie rat, ghost, skeleton and zombie key-carriers, magenta from Melzar, green from the
 * lesser demon; each colour door eats its key; the chest behind the green door), Thalzar's (silk,
 * wizard's mind bomb, unfired bowl and lobster pot on the Dwarven Mine magic door - cache varbits
 * `dragonslayer_used_*` - then the Oracle's chest), Lozar's (Wormbrain: 10,000 coins or kill him).
 * The Lady Lumbridge: 2,000 coins to Klarense, three planks + 30 steel nails each (hammer) on the
 * hull hole (`dragonslayer_ship_*_fixed`); Ned captains once he has the map. Duke Horacio gives
 * the anti-dragon shield. The voyage is a cutscene on an instance of the ship (dialogue, Elvarg,
 * the storm) ending on Crandor's beach; the volcano hole leads to Elvarg (dragonfire comes from the
 * combat module's dragonfire table). Server varp `ds1_progress`: ship bought, Wormbrain paid.
 *
 * Approximate: the maze monsters' spawn tiles, the gangplank / beach / lair landing tiles.
 * Not done: the sawmill operator's planks, the Karamja secret passage, Klarense on Crandor, the
 * other captains' refusals, Elvarg's head collection.
 */
class DragonSlayer
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val cutscenes: CutsceneRunner,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DragonSlayer::class.java, "dragon_slayer") }

    private val voyageSpeakers: Map<String, NpcType> by lazy {
        mapOf(
            "Captain Ned" to Ds1Npcs.ned_cutscene,
            "Cabin Boy Jenkins" to Ds1Npcs.jenkins_cutscene,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, Ds1Npcs.guildmaster, "Talk-to") { talkGuildmaster(it) }
        onNpcOpLabel(npcTypes, Ds1Npcs.oziach, "Talk-to") { talkOziach(it) }
        onNpcOpLabel(npcTypes, Ds1Npcs.oracle, "Talk-to") {
            runQuestSection(it, dialogue, "Talking to the Oracle")
        }
        onNpcOpLabel(npcTypes, Ds1Npcs.wormbrain, "Talk-to") { talkWormbrain(it) }
        onNpcOpLabel(npcTypes, Ds1Npcs.klarense, "Talk-to") { talkKlarense(it) }
        with(QuestTalk) {
            register(npcTypes, Ds1Npcs.ned, wants = { wantsNed() }) { talkNed(it) }
            register(npcTypes, Ds1Npcs.duke, wants = { needsShield() }) {
                runQuestSection(it, dialogue, "Talking to Duke Horacio", shieldHooks())
            }
        }
        onNpcOpLabel(npcTypes, Ds1Npcs.ned_ship, "Talk-to") { talkNedOnShip(it) }
        onNpcOpLabel(npcTypes, Ds1Npcs.jenkins, "Talk-to") { talkJenkins(it) }
        onEvent<NpcKilledEvent> { npcKilled(this) }

        // Melzar's Maze
        onLocOpLabel(locTypes, Ds1Locs.maze_door, "Open") { mazeDoor(it) }
        for ((door, key) in Ds1Locs.colour_doors) {
            onLocOpLabel(locTypes, door, "Open") { colourDoor(it, key) }
        }
        onLocOpLabel(locTypes, Ds1Locs.wardrobe, "Open") { searchWardrobe() }
        onLocOpLabel(locTypes, Ds1Locs.bookshelves, "Search") {
            runQuestSection(null, dialogue, "Searching bookshelves")
        }
        onLocOpLabel(locTypes, Ds1Locs.melzar_chest, "Open") {
            mapChest(Ds1Objs.map_melzar, "Searching the chest", Ds1VarBits.told_melzar)
        }

        // Thalzar's door and the Oracle's chest
        for (item in Ds1Objs.oracle_items) {
            onOpLocU(Ds1Locs.magic_door, item) { useOnMagicDoor(item) }
        }
        onLocOpLabel(locTypes, Ds1Locs.magic_door, "Open") { magicDoor(it) }
        onLocOpLabel(locTypes, Ds1Locs.oracle_chest, "Open") {
            mapChest(Ds1Objs.map_thalzar, "Searching the chest (2)", Ds1VarBits.told_oracle)
        }

        // The map
        for ((piece, section) in Ds1Objs.studies) {
            onHeldOpLabel(objTypes, piece, "Study") { runQuestSection(null, dialogue, section) }
        }
        for (a in Ds1Objs.pieces) {
            for (b in Ds1Objs.pieces) {
                if (a != b) onOpHeldU(a, b) { joinMap() }
            }
        }

        // The Lady Lumbridge
        onLocOpLabel(locTypes, Ds1Locs.gangplank_on, "Cross") { board() }
        onLocOpLabel(locTypes, Ds1Locs.gangplank_off, "Cross") { telejump(PORT_SARIM_DOCK) }
        onLocOpLabel(locTypes, Ds1Locs.deck_ladder, "Climb-down") { telejump(HULL) }
        onLocOpLabel(locTypes, Ds1Locs.hull_ladder, "Climb-up") { telejump(DECK) }
        onLocOpLabel(locTypes, Ds1Locs.ship_hole, "Repair") { repairHole() }

        // Crandor
        onLocOpLabel(locTypes, Ds1Locs.volcano_hole, "Enter") { telejump(LAIR_ROPE) }
        onLocOpLabel(locTypes, Ds1Locs.lair_rope, "Climb") { telejump(VOLCANO_TOP) }
        for (wall in Ds1Locs.lair_walls) {
            onLocOpLabel(locTypes, wall, "Climb-over") { passThroughWall(it) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(DRAGON_SLAYER)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(DRAGON_SLAYER, value)
    }

    private fun ProtectedAccess.bit(varbit: VarBitType): Boolean = player.vars[varbit] == 1

    private fun ProtectedAccess.setBit(varbit: VarBitType) {
        VarPlayerIntMapSetter.set(player, varbit, 1)
    }

    private fun ProtectedAccess.flag(bit: Int): Boolean =
        player.vars[Ds1Varps.progress] and bit != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        val value = player.vars[Ds1Varps.progress] or bit
        VarPlayerIntMapSetter.set(player, Ds1Varps.progress, value)
    }

    private fun ProtectedAccess.hasFullMap(): Boolean =
        carries(Ds1Objs.map) || Ds1Objs.pieces.all { carries(it) }

    private fun ProtectedAccess.shipFixed(): Boolean = bit(Ds1VarBits.ship_fixed)

    /* The Guildmaster and Oziach */

    private suspend fun ProtectedAccess.talkGuildmaster(npc: Npc) {
        var mazeKey = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have 45 combat or 33 Magic:" to {
                            player.appearance.combatLevel < RECOMMENDED_COMBAT
                        },
                        "If the player has completed Pandemonium:" to { false },
                    ),
                options =
                    mapOf(
                        "Yes." to { if (state == 0) setState(TO_OZIACH) },
                        "Where is Melzar's map piece?" to { setBit(Ds1VarBits.told_melzar) },
                        "Where is Thalzar's map piece?" to { setBit(Ds1VarBits.told_oracle) },
                        "Where is Lozar's map piece?" to { setBit(Ds1VarBits.told_wormbrain) },
                        "What kind of ship do I need?" to { setBit(Ds1VarBits.told_ship) },
                        "How can I protect myself from the dragon's breath?" to {
                            setBit(Ds1VarBits.told_shield)
                        },
                    ),
                boxes =
                    mapOf(
                        "The Guildmaster hands you a key." to {
                            mazeKey = true
                            false
                        },
                    ),
            )
        when {
            state == 0 -> {
                if (player.isFeatureOn(Feature.QuestGates) && player.questPoints < REQUIRED_QP) {
                    startDialogue(npc) {
                        chatNpc(neutral, "Only champions with $REQUIRED_QP quest points come here.")
                    }
                    return
                }
                runQuestSection(npc, dialogue, "Talking to the Guildmaster", hooks)
            }
            state == TO_OZIACH -> runQuestSection(npc, dialogue, GUILDMASTER_BEFORE_OZIACH, hooks)
            state == TASK -> {
                runQuestSection(npc, dialogue, GUILDMASTER_AFTER_OZIACH, hooks)
                setState(EXPLAINED)
            }
            state in EXPLAINED until ELVARG_DEAD ->
                runQuestSection(npc, dialogue, "Talking to the Guildmaster again", hooks)
            state == ELVARG_DEAD -> {
                runQuestSection(npc, dialogue, "Talking to the Guildmaster after slaying Elvarg")
                finish()
            }
            else -> startDialogue(npc) { chatNpc(happy, "Greetings, champion.") }
        }
        if (mazeKey && !carries(Ds1Objs.maze_key)) invAddOrDrop(objRepo, Ds1Objs.maze_key)
    }

    private suspend fun ProtectedAccess.talkOziach(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If Murder Mystery has been completed:" to { false },
                        "If the player has Elvarg's head in their inventory:" to {
                            carries(Ds1Objs.elvarg_head)
                        },
                    ),
                options =
                    mapOf(
                        "A dragon, that sounds like fun." to {
                            if (state == TO_OZIACH) setState(TASK)
                        },
                    ),
            )
        when {
            state == TO_OZIACH -> runQuestSection(npc, dialogue, "Talking to Oziach", hooks)
            state in TASK until ELVARG_DEAD ->
                runQuestSection(npc, dialogue, "Talking to Oziach before slaying the dragon", hooks)
            state == ELVARG_DEAD -> {
                runQuestSection(npc, dialogue, "Talking to Oziach after slaying Elvarg", hooks)
                finish()
            }
            else -> startDialogue(npc) { chatNpc(neutral, "Aye, 'tis a fair day my friend.") }
        }
    }

    private suspend fun ProtectedAccess.finish() {
        if (state != ELVARG_DEAD) return
        statAdvance(stats.strength, REWARD_XP * xpMods.get(player, stats.strength))
        statAdvance(stats.defence, REWARD_XP * xpMods.get(player, stats.defence))
        completeQuest(DRAGON_SLAYER)
    }

    /* Melzar's Maze */

    private suspend fun ProtectedAccess.mazeDoor(door: BoundLocInfo) {
        if (!carries(Ds1Objs.maze_key) && player.coords.x > door.coords.x) {
            mes("This door is locked.")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.colourDoor(door: BoundLocInfo, key: ObjType) {
        if (!carries(key)) {
            mes("This door is locked.")
            return
        }
        invDel(inv, key)
        mes("The key disappears as you open the door.")
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.searchWardrobe() {
        val level = player.coords.level
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "On the ground floor:" to { level == 0 },
                        "On the first floor:" to { level == 1 },
                        "On the second floor:" to { level == 2 },
                    ),
            )
        runQuestSection(null, dialogue, "Searching wardrobes", hooks)
    }

    /** The maze chest (green door) and Thalzar's chest: each holds one map piece. */
    private suspend fun ProtectedAccess.mapChest(
        piece: ObjType,
        section: String,
        told: VarBitType,
    ) {
        if (state < EXPLAINED || !bit(told) || carries(piece) || hasFullMapAlready()) {
            mes("The chest is empty.")
            return
        }
        runQuestSection(null, dialogue, section)
        invAddOrDrop(objRepo, piece)
    }

    private fun ProtectedAccess.hasFullMapAlready(): Boolean =
        carries(Ds1Objs.map) || state >= NED_HIRED

    /* Thalzar's magic door */

    private fun ProtectedAccess.useOnMagicDoor(item: ObjType) {
        val varbit = Ds1VarBits.oracle_items.getValue(item.id)
        if (bit(varbit)) {
            mes("You've already put that in the door.")
            return
        }
        invDel(inv, item)
        setBit(varbit)
        mes("You put the item into the door.")
        if (Ds1VarBits.oracle_items.values.all { bit(it) }) {
            mes("You hear a click as the door unlocks.")
        }
    }

    private suspend fun ProtectedAccess.magicDoor(door: BoundLocInfo) {
        if (Ds1VarBits.oracle_items.values.all { bit(it) }) {
            passThroughWall(door)
        } else {
            mes("The door is locked. It has four slots for objects.")
        }
    }

    /* Wormbrain */

    private suspend fun ProtectedAccess.talkWormbrain(npc: Npc) {
        var bought = false
        val coins = Ds1Objs.coins
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have enough coins:" to {
                            !carries(coins, WORMBRAIN_PRICE)
                        },
                        "If the player has enough coins:" to { carries(coins, WORMBRAIN_PRICE) },
                    ),
                boxes =
                    mapOf(
                        "You buy the map piece from Wormbrain." to {
                            bought = carries(coins, WORMBRAIN_PRICE)
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Wormbrain", hooks)
        if (bought && !carries(Ds1Objs.map_lozar)) {
            invDel(inv, coins, WORMBRAIN_PRICE)
            invAddOrDrop(objRepo, Ds1Objs.map_lozar)
        }
    }

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        val id = event.npc.type.id
        val at = event.npc.coords
        val state = player.questState(DRAGON_SLAYER)
        if (state < EXPLAINED) return
        when {
            id == Ds1Npcs.wormbrain.id && player.vars[Ds1VarBits.told_wormbrain] == 1 ->
                objRepo.add(Ds1Objs.map_lozar, at, DROP_TICKS, player)
            id == Ds1Npcs.elvarg.id && state == WRECKED -> {
                player.setQuestState(DRAGON_SLAYER, ELVARG_DEAD)
                objRepo.add(Ds1Objs.elvarg_head, at, DROP_TICKS, player)
                player.mes("You have slain Elvarg! Tell Oziach or the Guildmaster.")
            }
            else -> Ds1Npcs.key_carriers[id]?.let { objRepo.add(it, at, DROP_TICKS, player) }
        }
    }

    /* The map */

    private suspend fun ProtectedAccess.joinMap() {
        if (!Ds1Objs.pieces.all { carries(it) }) {
            runQuestSection(null, dialogue, "Using a map piece on another while only having 2")
            mes("You still need one more piece of map.")
            return
        }
        for (piece in Ds1Objs.pieces) invDel(inv, piece)
        invAdd(inv, Ds1Objs.map)
        runQuestSection(null, dialogue, "Joining the maps together")
    }

    /* The Lady Lumbridge */

    private suspend fun ProtectedAccess.talkKlarense(npc: Npc) {
        var bought = false
        val coins = Ds1Objs.coins
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have enough coins:" to {
                            !carries(coins, SHIP_PRICE)
                        },
                        "If the player has enough coins:" to { carries(coins, SHIP_PRICE) },
                        "If the player has not fixed the ship:" to { !shipFixed() },
                        "If the player has fixed the ship:" to { shipFixed() },
                    ),
                options = mapOf("Yep, sounds good." to { bought = carries(coins, SHIP_PRICE) }),
            )
        val section =
            when {
                state >= WRECKED -> "Talking to Klarense after reaching Crandor"
                flag(SHIP_BOUGHT) -> "Talking to Klarense after purchasing the Lady Lumbridge"
                else -> "Talking to Klarense"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (bought && !flag(SHIP_BOUGHT) && state >= EXPLAINED) {
            invDel(inv, coins, SHIP_PRICE)
            setFlag(SHIP_BOUGHT)
        }
    }

    private suspend fun ProtectedAccess.board() {
        if (!flag(SHIP_BOUGHT)) {
            runQuestSection(null, dialogue, "Attempting to board the ship before purchasing it")
            return
        }
        telejump(DECK)
    }

    private suspend fun ProtectedAccess.talkJenkins(npc: Npc) {
        var ashore = false
        val hooks =
            SimpleQuestHooks(options = mapOf("I'm ready to go back to shore." to { ashore = true }))
        val section =
            if (state >= NED_HIRED) JENKINS_NED_ABOARD else "Talking to Cabin Boy Jenkins"
        runQuestSection(npc, dialogue, section, hooks)
        if (ashore) telejump(PORT_SARIM_DOCK)
    }

    private suspend fun ProtectedAccess.repairHole() {
        val planks = carries(Ds1Objs.plank)
        val nails = carries(Ds1Objs.nails, NAILS_PER_PLANK)
        val hammer = carries(Ds1Objs.hammer)
        if (shipFixed()) return
        if (!planks || !nails || !hammer) {
            val text =
                when {
                    !planks -> "You'll need to use wooden planks on this hole to patch it up."
                    !nails -> "You need 30 steel nails to attach the plank with."
                    else -> "You need a hammer to force the nails in with."
                }
            startDialogue { mesbox(text) }
            return
        }
        invDel(inv, Ds1Objs.plank)
        invDel(inv, Ds1Objs.nails, NAILS_PER_PLANK)
        val text =
            when {
                !bit(Ds1VarBits.ship_third) -> {
                    setBit(Ds1VarBits.ship_third)
                    "You nail a plank over the hole, but you still need more planks to close the " +
                        "hole completely."
                }
                !bit(Ds1VarBits.ship_two_thirds) -> {
                    setBit(Ds1VarBits.ship_two_thirds)
                    "You nail a plank over the hole, but you still need one more plank to close " +
                        "the hole completely."
                }
                else -> {
                    setBit(Ds1VarBits.ship_fixed)
                    "You nail a final plank over the hole. You have successfully patched the " +
                        "hole in the ship."
                }
            }
        startDialogue { mesbox(text) }
    }

    /* Ned */

    private fun ProtectedAccess.wantsNed(): Boolean = state in EXPLAINED until NED_HIRED

    private suspend fun ProtectedAccess.talkNed(npc: Npc) {
        var handed = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't bought the ship yet:" to { !flag(SHIP_BOUGHT) },
                        "If the player hasn't repaired the ship yet:" to {
                            flag(SHIP_BOUGHT) && !shipFixed()
                        },
                        "If the player has the completed map:" to { carries(Ds1Objs.map) },
                        "If the player has all three map parts:" to {
                            !carries(Ds1Objs.map) && Ds1Objs.pieces.all { carries(it) }
                        },
                        "If the player does not have the completed map:" to { !hasFullMap() },
                        "If the player has the completed map or all 3 pieces:" to { hasFullMap() },
                        "If the player does not have the completed map or all 3 pieces:" to {
                            !hasFullMap()
                        },
                    ),
                options =
                    mapOf(
                        "You're a sailor? Could you take me to Crandor?" to {
                            setBit(Ds1VarBits.asked_ned)
                        },
                    ),
                boxes =
                    mapOf(
                        "You hand the map to Ned." to {
                            handed = true
                            false
                        },
                        "You hand the map parts to Ned." to {
                            handed = true
                            false
                        },
                    ),
            )
        val section = if (bit(Ds1VarBits.asked_ned)) NED_AGAIN else "Ned"
        runQuestSection(npc, dialogue, section, hooks)
        if (handed && hasFullMap() && flag(SHIP_BOUGHT) && shipFixed()) {
            if (carries(Ds1Objs.map)) invDel(inv, Ds1Objs.map)
            for (piece in Ds1Objs.pieces) if (carries(piece)) invDel(inv, piece)
            setState(NED_HIRED)
        }
    }

    private suspend fun ProtectedAccess.talkNedOnShip(npc: Npc) {
        var sail = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes, let's go!" to { sail = true }))
        when {
            state == NED_HIRED -> runQuestSection(npc, dialogue, NED_ON_SHIP, hooks)
            state >= WRECKED -> runQuestSection(npc, dialogue, NED_CRANDOR)
            else -> runQuestSection(npc, dialogue, NED_HAS_MAP)
        }
        if (sail && state == NED_HIRED) voyage()
    }

    /** The voyage: the ship instance, the storm and Elvarg, then Crandor's beach. */
    private suspend fun ProtectedAccess.voyage() {
        val finished =
            cutscenes.play(this, SHIP_TEMPLATE, returnTo = CRANDOR_BEACH) {
                val ned = spawn(Ds1Npcs.ned_cutscene, DECK_HELM)
                val jenkins = spawn(Ds1Npcs.jenkins_cutscene, DECK_BOW)
                teleport(DECK)
                camMoveTo(DECK_CAMERA, height = 900)
                camLookAt(DECK, height = 200)
                fadeIn()
                ned.faceTile(DECK)
                jenkins.faceTile(DECK)
                delay(2)
                conversation {
                    runQuestSection(null, dialogue, "Sailing to Crandor", speakers = voyageSpeakers)
                }
                val elvarg = spawn(Ds1Npcs.elvarg_cutscene, DECK_SKY)
                elvarg.walkTo(DECK_BOW)
                camShake(axis = 1, random = 2, amplitude = 12, rate = 40)
                delay(3)
                despawn(elvarg)
                fadeOut()
                delay(3)
            }
        if (!finished) return
        setState(WRECKED)
        mes("You are knocked unconscious and later awake on an ash-strewn beach.")
    }

    /* Duke Horacio */

    private fun ProtectedAccess.needsShield(): Boolean =
        state in EXPLAINED until ELVARG_DEAD && !carries(Ds1Objs.shield) &&
            invTotal(worn, Ds1Objs.shield) == 0

    private fun ProtectedAccess.shieldHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            boxes =
                mapOf(
                    "The Duke hands you a heavy orange shield." to {
                        invAddOrDrop(objRepo, Ds1Objs.shield)
                        false
                    },
                ),
        )

    companion object {
        const val TO_OZIACH = 1
        const val TASK = 2
        const val EXPLAINED = 3
        const val NED_HIRED = 7
        const val WRECKED = 8
        const val ELVARG_DEAD = 9
        const val COMPLETE = 10

        /* ds1_progress */
        private const val SHIP_BOUGHT = 1

        private const val REQUIRED_QP = 32
        private const val RECOMMENDED_COMBAT = 45
        private const val REWARD_XP = 18650.0
        private const val WORMBRAIN_PRICE = 10000
        private const val SHIP_PRICE = 2000
        private const val NAILS_PER_PLANK = 30
        private const val DROP_TICKS = 200

        private const val GUILDMASTER_BEFORE_OZIACH =
            "Talking to the Guildmaster again before talking to Oziach"
        private const val GUILDMASTER_AFTER_OZIACH =
            "Talking to the Guildmaster after talking to Oziach"
        private const val JENKINS_NED_ABOARD = "Talking to Cabin Boy Jenkins on the ship"
        private const val NED_ON_SHIP = "Talking to Ned on the Lady Lumbridge"
        private const val NED_CRANDOR = "Talking to Ned after reaching Crandor"
        private const val NED_HAS_MAP = "Talking to Ned after giving him the Crandor map"
        private const val NED_AGAIN =
            "Talking to Ned again if the player wasn't ready the first time"

        private val PORT_SARIM_DOCK = CoordGrid(3047, 3204, 0)
        private val DECK = CoordGrid(3047, 3207, 1)
        private val DECK_HELM = CoordGrid(3045, 3207, 1)
        private val DECK_BOW = CoordGrid(3050, 3207, 1)
        private val DECK_SKY = CoordGrid(3056, 3215, 1)
        private val DECK_CAMERA = CoordGrid(3047, 3199, 1)
        private val HULL = CoordGrid(3049, 9641, 1)
        private val CRANDOR_BEACH = CoordGrid(2849, 3239, 0)
        private val VOLCANO_TOP = CoordGrid(2834, 3256, 0)
        private val LAIR_ROPE = CoordGrid(2833, 9658, 0)

        /** The Lady Lumbridge at Port Sarim (zones 380-383 x 399-402, all levels). */
        private val SHIP_TEMPLATE =
            RegionTemplate.create {
                copyAllLevels(SHIP_ZONE_X, SHIP_ZONE_Z) {
                    zoneWidth = 4
                    zoneLength = 4
                }
            }
        private const val SHIP_ZONE_X = 380
        private const val SHIP_ZONE_Z = 399

        val DRAGON_SLAYER =
            QuestDef(
                name = "Dragon Slayer I",
                listRow = "quest_dragonslayer1",
                varp = { Ds1Varps.dragonquest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf("18,650 Strength XP", "18,650 Defence XP", "Rune platebody access"),
                scrollObj = { Ds1Objs.elvarg_head },
            )
    }
}

// Every cache ref Dragon Slayer I uses (re-check after the rev 243 cutover).

internal object Ds1Npcs : NpcReferences() {
    val guildmaster = find("guildmaster")
    val oziach = find("oziach")
    val oracle = find("oracle")
    val wormbrain = find("wormbrain")
    val klarense = find("klarense")
    val ned = find("ned")
    val duke = find("duke_of_lumbridge")

    /* Children of the dragonslayer_ned_on_ship / cabin_boy_jenkins / elvarg multinpcs. */
    val ned_ship = find("ned_1op")
    val jenkins = find("dragonslayer_jenkins_there")
    val elvarg = find("elvarg_alive")
    val ned_cutscene = find("dragonslayer_ned_cutscene")
    val jenkins_cutscene = find("dragonslayer_jenkins_cutscene")
    val elvarg_cutscene = find("dragonslayer_elvarg_cutscene")

    /** Maze key-carriers -> the key they drop. */
    val key_carriers: Map<Int, ObjType> =
        mapOf(
            find("dragonslayer_giantrat_1_key").id to Ds1Objs.red_key,
            find("dragonslayer_ghost_1_key").id to Ds1Objs.orange_key,
            find("dragonslayer_skeleton_1_key").id to Ds1Objs.yellow_key,
            find("dragonslayer_zombie_1_key").id to Ds1Objs.blue_key,
            find("melzar_the_mad").id to Ds1Objs.magenta_key,
            find("dragonslayer_demon").id to Ds1Objs.green_key,
        )
}

internal object Ds1Locs : LocReferences() {
    val maze_door = find("melzardoor")
    val colour_doors =
        listOf(
            find("reddoor") to Ds1Objs.red_key,
            find("orangedoor") to Ds1Objs.orange_key,
            find("yellowdoor") to Ds1Objs.yellow_key,
            find("bluedoor") to Ds1Objs.blue_key,
            find("magentadoor") to Ds1Objs.magenta_key,
            find("greendoor") to Ds1Objs.green_key,
        )
    val wardrobe = find("dragonslayer_spookywardrobe")
    val bookshelves = find("dragon_slayer_qip_spookybookshelves")
    val melzar_chest = find("funchestshut")
    val magic_door = find("dragon_slayer_qip_magic_door")
    val oracle_chest = find("oraclechestshut")
    val gangplank_on = find("dragonshipgangplank_on")
    val gangplank_off = find("dragonshipgangplank_off")
    val deck_ladder = find("dragonshipladdertop")
    val hull_ladder = find("dragonshipladder")
    val ship_hole = find("dragonslayer_shiphole")
    val volcano_hole = find("dragon_slayer_qip_ruin_entrance")
    val lair_rope = find("dragon_slayer_qip_climbing_rope")
    val lair_walls = listOf(find("dragon_slayer_qip_stalagtite_jump"))
}

internal object Ds1Objs : ObjReferences() {
    val maze_key = find("melzarkey")
    val red_key = find("redkey")
    val orange_key = find("orangekey")
    val yellow_key = find("yellowkey")
    val blue_key = find("bluekey")
    val magenta_key = find("magentakey")
    val green_key = find("greenkey")
    val map_melzar = find("mappart1")
    val map_thalzar = find("mappart2")
    val map_lozar = find("mappart3")
    val map = find("dragonmap")
    val pieces = listOf(map_melzar, map_thalzar, map_lozar)
    val studies =
        listOf(
            map_melzar to "Studying Melzar's map part",
            map_thalzar to "Studying Thalzar's map part",
            map_lozar to "Studying Lozar's map part",
        )
    val silk = find("silk")
    val mind_bomb = find("wizards_mind_bomb")
    val bowl = find("bowl_unfired")
    val lobster_pot = find("lobster_pot")
    val oracle_items = listOf(silk, mind_bomb, bowl, lobster_pot)
    val plank = find("woodplank")
    val nails = find("nails")
    val hammer = find("hammer")
    val shield = find("antidragonbreathshield")
    val elvarg_head = find("dragon_slayer_qip_elvargs_head")
    val coins = find("coins")
}

internal object Ds1Varps : VarpReferences() {
    val dragonquest = find("dragonquest")

    /** Server varp (65777): ship bought. */
    val progress = find("ds1_progress")
}

internal object Ds1VarBits : VarBitReferences() {
    val told_melzar = find("dragonslayer_instructions_melzar")
    val told_oracle = find("dragonslayer_instructions_oracle")
    val told_wormbrain = find("dragonslayer_instructions_wormbrain")
    val told_ship = find("dragonslayer_instructions_ship")
    val told_shield = find("dragonslayer_instructions_shield")
    val asked_ned = find("dragonslayer_asked_ned")
    val ship_third = find("dragonslayer_ship_onethird_fixed")
    val ship_two_thirds = find("dragonslayer_ship_twothird_fixed")
    val ship_fixed = find("dragonslayer_ship_fullyfixed")
    val oracle_items: Map<Int, VarBitType> =
        mapOf(
            Ds1Objs.silk.id to find("dragonslayer_used_silk"),
            Ds1Objs.mind_bomb.id to find("dragonslayer_used_wizards_mind_bomb"),
            Ds1Objs.bowl.id to find("dragonslayer_used_bowl_unfired"),
            Ds1Objs.lobster_pot.id to find("dragonslayer_used_lobster_pot"),
        )
}

internal object Ds1VarpBuilds : VarpBuilder() {
    init {
        build("ds1_progress")
    }
}
