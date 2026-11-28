package org.rsmod.content.quests.shilovillage

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.AGILITY_LEVEL
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.BERVIRIUS_FOUND
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.BONE_1
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.BONE_3
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.COMPLETE
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.FISSURE_LIT
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.NAZ_1
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.NAZ_2
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.NAZ_3
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.REWARD_XP
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.ROPE_TIED
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.SHILO_VILLAGE
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.STARTED
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.TOMB_ENTERED
import org.rsmod.content.quests.shilovillage.ShiloVillage.Companion.ZADIMUS_BURIED
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shilo Village's dungeons (see [ShiloVillage] for the stages): the temple of Ah Za Rhoon, the
 * Tomb of Bervirius on Cairn Isle and Rashiliyia's Tomb with Nazastarool.
 *
 * Positions are from the rev 243 map (tools/agility/maplocs.py plus its terrain flags, checked by
 * a walk search): Ah Za Rhoon's two halves (entrance, stone, table and waterfall to the north; the
 * ruins, loose rocks, sacks and gallows to the south) join only through the two cave-ins.
 *
 * Simplified: the mound stays a mound until dug and reverts after 5 minutes (searching it once the
 * rope is tied climbs straight down, as the Wiki notes); the loose rocks, waterfall path, Cairn Isle
 * rocks and tomb handholds fail at our odds (damage from the Wiki where it gives one); the crude
 * raft lands where the waterfall path does; Nazastarool is plain NPC melee with his cache stats
 * (= Wiki; the enricher has no attack anims for him, so he swings with the default anim) and each
 * form lasts 5 minutes; Rashiliyia's corpse goes straight to the inventory (or floor) instead of
 * appearing on the dolmen; both tomb rooms (the cache has two) work.
 */
class ShiloDungeons
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locRepo: LocRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ShiloVillage::class.java, "shilo_village") }

    /** The smashed table's wood (0 whole, 1 hacked away), reset on each climb down. */
    private val tableWood = HashMap<Int, Int>()

    /** The Nazastarool form fighting each player (single player, but keyed for safety). */
    private val fighters = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        // Ah Za Rhoon: the entrance.
        onLocOpLabel(locTypes, SvLocs.mound, "Look-at") { svSection(dialogue, null, "Look-at Mound of earth") }
        onLocOpLabel(locTypes, SvLocs.mound, "Search") { searchMound() }
        onOpLocU(SvLocs.mound) { onMound(it.loc, it.objType) }
        onLocOpLabel(locTypes, SvLocs.fissure, "Look-at") { svSection(dialogue, null, "Look-at Fissure") }
        onLocOpLabel(locTypes, SvLocs.fissure, "Search") { searchFissure() }
        onOpLocU(SvLocs.fissure) { onFissure(it.loc, it.objType) }
        onLocOpLabel(locTypes, SvLocs.fissure_rope, "Look-at") {
            svSection(dialogue, null, "Look at Fissure after rope is attached")
        }
        onLocOpLabel(locTypes, SvLocs.fissure_rope, "Search") { searchFissure() }
        onOpLocU(SvLocs.fissure_rope) { onFissure(it.loc, it.objType) }

        // Ah Za Rhoon: inside.
        onLocOpLabel(locTypes, SvLocs.stone, "Look-at") { lookStone() }
        onLocOpLabel(locTypes, SvLocs.stone, "Investigate") { investigateStone() }
        onOpLocU(SvLocs.stone) { onStone(it.objType) }
        onLocOpLabel(locTypes, SvLocs.cave_in, "Search") { searchCaveIn() }
        onLocOpLabel(locTypes, SvLocs.loose_rocks, "Search") { searchLooseRocks() }
        onLocOpLabel(locTypes, SvLocs.sacks, "Search") { searchSacks() }
        onLocOpLabel(locTypes, SvLocs.gallows, "Look-at") { lookGallows() }
        onLocOpLabel(locTypes, SvLocs.gallows, "Search") { searchGallows() }
        onLocOpLabel(locTypes, SvLocs.table, "Look-at") { svSection(dialogue, null, "Look-at Smashed table") }
        onLocOpLabel(locTypes, SvLocs.table, "Craft") { craftTable() }
        onLocOpLabel(locTypes, SvLocs.waterfall_rocks, "Look-at") { svSection(dialogue, null, "Look-at Waterfall rocks") }
        onLocOpLabel(locTypes, SvLocs.waterfall_rocks, "Search") { searchWaterfall() }

        // Cairn Isle and the Tomb of Bervirius.
        onLocOpLabel(locTypes, SvLocs.cairn_isle_rocks, "Climb") { climbCairnRocks() }
        onLocOpLabel(locTypes, SvLocs.cairn, "Investigate") { svSection(dialogue, null, "Investigate Well stacked rocks") }
        onLocOpLabel(locTypes, SvLocs.cairn, "Search") { searchCairn() }
        onLocOpLabel(locTypes, SvLocs.bervirius_dolmen, "Inspect") { inspectBerviriusDolmen() }
        onLocOpLabel(locTypes, SvLocs.bervirius_dolmen, "Search") { searchBerviriusDolmen() }
        bindLocU(SvLocs.bervirius_dolmen, SvObjs.remains) { placeRemains() }
        onLocOpLabel(locTypes, SvLocs.handholds, "Climb") { climbHandholds() }

        // Rashiliyia's Tomb: the hidden doors.
        for (palm in SvLocs.palms) onLocOpLabel(locTypes, palm, "Search") { searchPalms() }
        for (door in SvLocs.carved_doors) {
            onLocOpLabel(locTypes, door, "Open") { svSection(dialogue, null, "Open Carved doors") }
            onLocOpLabel(locTypes, door, "Search") { svSection(dialogue, null, "Search Carved doors") }
            onOpLocU(door) { onCarvedDoors(it.objType) }
        }
        for (door in SvLocs.open_doors) onLocOpLabel(locTypes, door, "Enter") { enterOpenDoors() }

        // Rashiliyia's Tomb: inside.
        for (gate in SvLocs.tomb_gates) {
            onLocOpLabel(locTypes, gate, "Open") { openTombGate(it) }
            onLocOpLabel(locTypes, gate, "Search") { svSection(dialogue, null, "Search Ancient metal gate") }
        }
        onLocOpLabel(locTypes, SvLocs.tomb_rocks, "Climb") { climbTombRocks() }
        onOpObj3(SvObjs.fake_coins) { touchCoins() }
        for (door in SvLocs.tomb_doors) {
            onLocOpLabel(locTypes, door, "Open") { openTombDoors(it) }
            onLocOpLabel(locTypes, door, "Search") { searchTombDoors() }
            bindLocU(door, SvObjs.bones) { boneOnTombDoors() }
        }
        onLocOpLabel(locTypes, SvLocs.tomb_dolmen, "Look-at") { searchTombDolmen() }
        onLocOpLabel(locTypes, SvLocs.tomb_dolmen, "Search") { searchTombDolmen() }
        for (exit in SvLocs.tomb_exits) {
            onLocOpLabel(locTypes, exit, "Open") { openTombExit() }
            onLocOpLabel(locTypes, exit, "Search") { svSection(dialogue, null, "Search Tomb exit") }
            bindLocU(exit, SvObjs.bone_key) { keyOnTombExit() }
        }
        onEvent<NpcKilledEvent> { nazastaroolKilled(this) }
    }

    /** Binds [obj] used on [loc] unless another script has that pair (duplicates crash boot). */
    private fun ScriptContext.bindLocU(loc: LocType, obj: ObjType, action: suspend ProtectedAccess.() -> Unit) {
        if (eventBus.contains(LocUEvents.Op::class.java, EventBus.composeLongKey(loc.id, obj.id))) return
        onOpLocU(loc, obj) { action() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SHILO_VILLAGE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(SHILO_VILLAGE, value)
    }

    private fun ProtectedAccess.wearsBeads(): Boolean = invTotal(worn, SvObjs.dead_beads) > 0

    private fun ProtectedAccess.hurt(min: Int, max: Int) {
        player.queueHit(delay = 0, type = HitType.Typeless, damage = random.of(min, max))
    }

    /** Authored odds (not Wiki): 1 in [odds] to slip, a little less likely with Agility. */
    private fun ProtectedAccess.slips(odds: Int): Boolean =
        random.of(1, odds * 100) <= (100 - stat(stats.agility)).coerceAtLeast(20)

    /* Ah Za Rhoon: the entrance */

    private suspend fun ProtectedAccess.searchMound() {
        if (state >= ROPE_TIED) {
            searchFissure()
            return
        }
        svSection(dialogue, null, "Search Mound of earth")
    }

    private suspend fun ProtectedAccess.onMound(loc: BoundLocInfo, obj: ObjType) {
        if (obj.id != SvObjs.spade.id) {
            mes("Nothing interesting happens.")
            return
        }
        if (state < STARTED) {
            mes("You dig for a while but find nothing of interest.") // Authored (not Wiki).
            return
        }
        svSection(dialogue, null, "Use spade on Mound of earth")
        val into = if (state >= ROPE_TIED) SvLocs.fissure_rope else SvLocs.fissure
        locRepo.change(loc, into, MOUND_TICKS)
    }

    private suspend fun ProtectedAccess.onFissure(loc: BoundLocInfo, obj: ObjType) {
        val light = SvObjs.lights.firstOrNull { it.id == obj.id }
        when {
            obj.id == SvObjs.spade.id -> svSection(dialogue, null, "Use spade on Fissure")
            light != null -> dropLight(light)
            obj.id == SvObjs.rope.id -> tieRope(loc)
            else -> mes("Nothing interesting happens.")
        }
    }

    private suspend fun ProtectedAccess.dropLight(light: ObjType) {
        if (state >= FISSURE_LIT) {
            mes("You have already seen what lies below the fissure.") // Authored (not Wiki).
            return
        }
        if (state < STARTED) {
            mes("Nothing interesting happens.")
            return
        }
        // The transcript has the lit candle and black candle; torches and lanterns use its words.
        val black = light.id == SvObjs.lit_black_candle.id
        val section = if (black) "Use lit black candle on Fissure" else "Use lit candle on Fissure"
        val name = objName(light)
        var drop = false
        val hooks =
            SvHooks(
                this,
                options = mapOf("Yes" to { drop = true }),
                rewrites = { if (black) it else it.replace("lit candle", name) },
            )
        svSection(dialogue, null, section, hooks)
        if (drop && carries(light)) {
            invDel(inv, light)
            setState(FISSURE_LIT)
        }
    }

    private fun objName(obj: ObjType): String =
        when (obj.id) {
            SvObjs.torch_lit.id -> "lit torch"
            SvObjs.candle_lantern_lit.id,
            SvObjs.candle_lantern_black_lit.id -> "lit candle lantern"
            else -> "lit candle"
        }

    private suspend fun ProtectedAccess.tieRope(loc: BoundLocInfo) {
        when {
            state >= ROPE_TIED -> mes("The rope is already tied securely.") // Authored (not Wiki).
            state < FISSURE_LIT ->
                // Authored (not Wiki): the anchor point shows only once a light has been dropped.
                mes("It's too dark to see anywhere to tie a rope. Perhaps some light would help.")
            else -> {
                svSection(dialogue, null, "Use rope on Fissure")
                invDel(inv, SvObjs.rope)
                setState(ROPE_TIED)
                locRepo.change(loc, SvLocs.fissure_rope, MOUND_TICKS)
            }
        }
    }

    private suspend fun ProtectedAccess.searchFissure() {
        if (state < ROPE_TIED) {
            svSection(dialogue, null, "Search Fissure")
            return
        }
        var climb = false
        val hooks = SvHooks(this, options = mapOf("Yes, I'll give it a go!" to { climb = true }))
        svSection(dialogue, null, "Search Fissure after rope is attached", hooks)
        if (!climb) return
        if (stat(stats.agility) < AGILITY_LEVEL) {
            mes("You need an Agility level of $AGILITY_LEVEL to climb down into the fissure.") // Authored.
            return
        }
        tableWood.remove(player.uid.packed)
        telejump(AH_ZA_RHOON_LANDING)
    }

    /* Ah Za Rhoon: inside */

    private val ProtectedAccess.afterQuest: Boolean
        get() = state >= COMPLETE

    private suspend fun ProtectedAccess.lookStone() {
        if (afterQuest) {
            svSection(dialogue, null, "Look-at or Investigate Strange looking stone")
        } else {
            svSection(dialogue, null, "Look-at Strange looking stone")
        }
    }

    private suspend fun ProtectedAccess.investigateStone() {
        if (afterQuest) {
            svSection(dialogue, null, "Look-at or Investigate Strange looking stone")
        } else {
            svSection(dialogue, null, "Investigate Strange looking stone")
        }
    }

    private suspend fun ProtectedAccess.onStone(obj: ObjType) {
        when {
            afterQuest -> svSection(dialogue, null, "Using items on Strange looking stone")
            obj.id != SvObjs.chisel.id -> mes("Nothing interesting happens.")
            svOwns(SvObjs.plaque) -> mes("You have already cut the plaque from this stone.") // Authored.
            else -> {
                svSection(dialogue, null, "Use chisel on Strange looking stone")
                invAddOrDrop(objRepo, SvObjs.plaque)
            }
        }
    }

    private suspend fun ProtectedAccess.searchCaveIn() {
        var through = false
        val hooks = SvHooks(this, options = mapOf("Yes, I'll wriggle through." to { through = true }))
        svSection(dialogue, null, "Search Cave in", hooks)
        if (!through) return
        // The north cave-in (2887,9373) and the ruins' one (2886,9283).
        telejump(if (player.coords.z > CAVE_IN_SPLIT_Z) RUINS_CAVE_IN else NORTH_CAVE_IN)
    }

    private suspend fun ProtectedAccess.searchLooseRocks() {
        if (afterQuest) {
            svSection(dialogue, null, "Search Loose rocks (2)")
            return
        }
        var move = false
        val hooks =
            SvHooks(
                this,
                options = mapOf("Yes, I'll carefully move the rocks to see what's behind them." to { move = true }),
            )
        if (svOwns(SvObjs.tattered_scroll)) {
            mes("You find nothing else behind the rocks.") // Authored (not Wiki).
            return
        }
        if (slips(LOOSE_ROCK_ODDS)) {
            // Authored (not Wiki): the ceiling gives way.
            runSimpleBoxes(listOf("You start to slowly move the rocks to one side.", "Some rocks fall from the ceiling and hit you!"))
            hurt(4, 7)
            return
        }
        svSection(dialogue, null, "Search Loose rocks", hooks)
        if (move) invAddOrDrop(objRepo, SvObjs.tattered_scroll)
    }

    private suspend fun ProtectedAccess.runSimpleBoxes(lines: List<String>) {
        startDialogue { for (line in lines) mesbox(line) }
    }

    private suspend fun ProtectedAccess.searchSacks() {
        when {
            afterQuest -> svSection(dialogue, null, "Search Old sacks (2)")
            svOwns(SvObjs.crumpled_scroll) -> mes("You find nothing else in the sacks.") // Authored.
            else -> {
                svSection(dialogue, null, "Search Old sacks")
                invAddOrDrop(objRepo, SvObjs.crumpled_scroll)
            }
        }
    }

    private suspend fun ProtectedAccess.lookGallows() {
        if (afterQuest) {
            svSection(dialogue, null, "Look-at or Search the Ancient gallows")
        } else {
            svSection(dialogue, null, "Look-at Ancient gallows")
        }
    }

    /** The corpse is there until it has been buried (or again if the bone shard and key are lost). */
    private fun ProtectedAccess.corpseHere(): Boolean =
        when {
            state < STARTED || state >= COMPLETE -> false
            svOwns(SvObjs.zadimus_corpse) -> false
            state < ZADIMUS_BURIED -> true
            else -> state < TOMB_ENTERED && !svOwns(SvObjs.bone_shard) && !svOwns(SvObjs.bone_key)
        }

    private suspend fun ProtectedAccess.searchGallows() {
        if (afterQuest) {
            svSection(dialogue, null, "Look-at or Search the Ancient gallows")
            return
        }
        if (!corpseHere()) {
            mes("You search the gallows but find nothing more.") // Authored (not Wiki).
            return
        }
        var take = false
        val hooks = SvHooks(this, options = mapOf("Yes, I may find something else on the corpse." to { take = true }))
        svSection(dialogue, null, "Search Ancient gallows", hooks)
        if (take) invAddOrDrop(objRepo, SvObjs.zadimus_corpse)
    }

    private suspend fun ProtectedAccess.craftTable() {
        val key = player.uid.packed
        var raft = false
        val hooks =
            SvHooks(
                this,
                options =
                    mapOf(
                        "A ladder" to { tableWood[key] = 1 },
                        "A pole vault" to { tableWood[key] = 1 },
                        "A crude raft" to { raft = true },
                    ),
            )
        if ((tableWood[key] ?: 0) == 0) {
            svSection(dialogue, null, "Craft Smashed table", hooks)
        } else {
            svSection(dialogue, null, "Craft Smashed table after using up the wood", hooks)
            // Its raft option only points back above: the raft still floats.
            if (raft) svBranch(dialogue, "Craft Smashed table", "A crude raft")
        }
        if (raft) {
            tableWood.remove(key)
            telejump(WATERFALL_EXIT)
        }
    }

    private suspend fun ProtectedAccess.searchWaterfall() {
        var follow = false
        val fell = slips(WATERFALL_ODDS)
        val hooks =
            SvHooks(
                this,
                options = mapOf("Yes, I'll follow the path." to { follow = true }),
                conditions = mapOf("If the player succeeds:" to { !fell }, "If the player fails:" to { fell }),
                actions = mapOf("The player is dealt some damage." to { hurt(10, 20) }),
            )
        svSection(dialogue, null, "Search Waterfall rocks", hooks)
        // Both ways end outside (Wiki: a fall deals ~20 and you land by the stepping stones).
        if (follow) telejump(WATERFALL_EXIT)
    }

    /* Cairn Isle and the Tomb of Bervirius */

    private suspend fun ProtectedAccess.climbCairnRocks() {
        // Authored (not Wiki odds): Wiki "You may fall and take damage, keep trying".
        if (slips(CAIRN_ROCK_ODDS)) {
            mes("You slip and fall from the rocks!") // Authored.
            hurt(1, 4)
            return
        }
        telejump(if (player.coords.x >= CAIRN_ROCKS_X) CAIRN_ROCKS_WEST else CAIRN_ROCKS_EAST)
        mes("You climb across the rocks.") // Authored.
    }

    private suspend fun ProtectedAccess.searchCairn() {
        var crawl = false
        val hooks = SvHooks(this, options = mapOf("Yes please, I can think of nothing nicer!" to { crawl = true }))
        if (stat(stats.agility) < AGILITY_LEVEL) {
            mes("You need an Agility level of $AGILITY_LEVEL to squeeze into the crawl-way.") // Authored.
            return
        }
        svSection(dialogue, null, "Search Well stacked rocks", hooks)
        if (crawl) telejump(BERVIRIUS_LANDING)
    }

    private suspend fun ProtectedAccess.inspectBerviriusDolmen() {
        if (afterQuest) {
            svSection(dialogue, null, "Inspect or Search Tomb dolmen")
        } else {
            svSection(dialogue, null, "Inspect Tomb dolmen")
        }
    }

    private suspend fun ProtectedAccess.searchBerviriusDolmen() {
        if (afterQuest) {
            svSection(dialogue, null, "Inspect or Search Tomb dolmen")
            return
        }
        if (state < ZADIMUS_BURIED) {
            svSection(dialogue, null, "Inspect Tomb dolmen")
            return
        }
        val pommel = !svOwns(SvObjs.pommel) && !svOwns(SvObjs.bone_beads) && !svOwns(SvObjs.dead_beads)
        val crystal = !svOwns(SvObjs.crystal)
        val notes = !svOwns(SvObjs.notes)
        if (!pommel && !crystal && !notes) {
            mes("You find nothing else on the dolmen.") // Authored (not Wiki).
            return
        }
        val hooks =
            SvHooks(
                this,
                boxes =
                    mapOf(
                        POMMEL_BOX to { !pommel },
                        CRYSTAL_BOX to { !crystal },
                        NOTES_BOX to { !notes },
                    ),
            )
        svSection(dialogue, null, "Search Tomb dolmen", hooks)
        if (pommel) invAddOrDrop(objRepo, SvObjs.pommel)
        if (crystal) invAddOrDrop(objRepo, SvObjs.crystal)
        if (notes) invAddOrDrop(objRepo, SvObjs.notes)
        if (state < BERVIRIUS_FOUND) setState(BERVIRIUS_FOUND)
    }

    private suspend fun ProtectedAccess.climbHandholds() {
        val fell = slips(HANDHOLD_ODDS)
        val hooks =
            SvHooks(
                this,
                conditions =
                    mapOf(
                        "If the player fails to climb the rock:" to { fell },
                        "If the player successfully climbs the rock:" to { !fell },
                    ),
                actions = mapOf("The player is dealt 1-10 damage" to { hurt(1, 10) }),
            )
        svSection(dialogue, null, "Climb Climbing rocks", hooks)
        if (!fell) telejump(CAIRN_OUTSIDE)
    }

    private suspend fun ProtectedAccess.placeRemains() {
        if (state != NAZ_3 || !carries(SvObjs.remains)) {
            mes("Nothing interesting happens.")
            return
        }
        svSection(dialogue, null, "Using Rashiliyias' remains on the dolmen")
        invDel(inv, SvObjs.remains)
        statAdvance(stats.crafting, REWARD_XP * xpMods.get(player, stats.crafting))
        completeQuest(SHILO_VILLAGE)
    }

    /* Rashiliyia's Tomb: the hidden doors */

    private fun ProtectedAccess.setDoors(value: Int) {
        VarPlayerIntMapSetter.set(player, SvVarBits.door_multi, value)
    }

    private suspend fun ProtectedAccess.searchPalms() {
        if (bit(SvVarBits.door_multi) >= 1) {
            mes("You search the palm trees but find nothing more.") // Authored (not Wiki).
            return
        }
        svSection(dialogue, null, "Search Palm tree")
        setDoors(1)
    }

    private suspend fun ProtectedAccess.onCarvedDoors(obj: ObjType) {
        when (obj.id) {
            SvObjs.bone_shard.id -> svSection(dialogue, null, "Using the bone shard on the carved doors")
            SvObjs.bone_key.id -> {
                svSection(dialogue, null, "Use Bone key on Carved doors")
                if (state == BERVIRIUS_FOUND) setState(TOMB_ENTERED)
                telejump(TOMB_LANDING)
            }
            else -> mes("Nothing interesting happens.")
        }
    }

    private fun ProtectedAccess.enterOpenDoors() {
        telejump(TOMB_LANDING)
    }

    /* Rashiliyia's Tomb: inside */

    private suspend fun ProtectedAccess.rashiliyiaAttacks() {
        val hooks =
            SvHooks(
                this,
                conditions =
                    mapOf(
                        "If the player is not wearing the Beads of the Dead:" to { true },
                        "If the player is wearing the Beads of the Dead:" to { false },
                    ),
                actions = mapOf("Rahiliyia spawns 1-3 undead ones" to { spawnUndead(player, random.of(1, 3)) }),
            )
        svSection(dialogue, null, "Open Ancient metal gate", hooks)
    }

    private suspend fun ProtectedAccess.openTombGate(gate: BoundLocInfo) {
        if (!wearsBeads()) {
            rashiliyiaAttacks()
            return
        }
        mes("The Beads of the Dead start to glow...") // Wiki {{mes}}
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.climbTombRocks() {
        if (player.coords.z >= TOMB_ROCKS_TOP.z) {
            mes("You carefully try to pick your way down the rocks.") // Wiki {{mes}}
            telejump(TOMB_ROCKS_BOTTOM)
            mes("You manage to carefully clamber down.") // Wiki {{mes}}
        } else {
            telejump(TOMB_ROCKS_TOP)
            mes("You climb back up the rocks.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.touchCoins() {
        svSection(dialogue, null, "Attempting to pick up the coins")
        spawnUndead(player, COIN_UNDEAD)
    }

    private val ProtectedAccess.bonesIn: Int
        get() = (state - TOMB_ENTERED).coerceIn(0, 3)

    private suspend fun ProtectedAccess.openTombDoors(door: BoundLocInfo) {
        if (state >= BONE_3) {
            passThroughWall(door)
            return
        }
        mes("This door is completely sealed, it is very ornately carved.") // Wiki {{mes}}
    }

    private suspend fun ProtectedAccess.searchTombDoors() {
        val section =
            if (state >= BONE_3) {
                "Three bones put in"
            } else {
                listOf("No bones put in", "One bone put in", "Two bones put in")[bonesIn]
            }
        svSection(dialogue, null, section)
    }

    private suspend fun ProtectedAccess.boneOnTombDoors() {
        when {
            state >= BONE_3 -> svSection(dialogue, null, "Using bones on the Tomb door")
            state < TOMB_ENTERED -> mes("Nothing interesting happens.")
            else -> {
                val placed = bonesIn
                val hooks =
                    SvHooks(
                        this,
                        conditions =
                            mapOf(
                                "If this is the first bone:" to { placed == 0 },
                                "If this is the second bone:" to { placed == 1 },
                                "If this is the third bone:" to { placed == 2 },
                            ),
                    )
                invDel(inv, SvObjs.bones)
                setState(BONE_1 + placed)
                svSection(dialogue, null, "Using bones on Tomb doors", hooks)
            }
        }
    }

    private suspend fun ProtectedAccess.searchTombDolmen() {
        when {
            state >= COMPLETE -> svSection(dialogue, null, "Look-at or Search Tomb dolmen (2)")
            state < BONE_3 -> mes("Nothing interesting happens.")
            state == NAZ_3 -> {
                if (svOwns(SvObjs.remains)) {
                    mes("There is nothing else on the dolmen.") // Authored (not Wiki).
                    return
                }
                svSection(dialogue, null, "Take Rashiliyia corpse")
                invAddOrDrop(objRepo, SvObjs.remains)
            }
            else -> summonNazastarool()
        }
    }

    /** The current form (BONE_3 = the zombie, NAZ_1 = the skeleton, NAZ_2 = the ghost). */
    private suspend fun ProtectedAccess.summonNazastarool() {
        val existing = fighters[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) {
            mes("Nazastarool is already here!") // Authored (not Wiki).
            return
        }
        val form = state - BONE_3
        if (form == 0) {
            startDialogue {
                mesbox(
                    "You touch the dolmen, and the ground starts to shake. You hear an unearthly voice booming and you " +
                        "step away from the dolmen in anticipation."
                )
            }
        }
        spawnForm(player, form)
    }

    private fun spawnForm(player: Player, form: Int) {
        val type = SvNpcs.nazastarool[form]
        val room = if (player.coords.z >= SECOND_ROOM_Z) NAZ_SPAWN_2 else NAZ_SPAWN_1
        val naz = Npc(npcTypes[type], room)
        npcRepo.add(naz, NAZ_TICKS)
        fighters[player.uid.packed] = naz
        naz.opPlayer2(player, aiInteractions)
        when (form) {
            0 -> naz.say("I am Nazastarool! Prepare to die!")
            1 -> naz.say("Quake in fear, for I am reborn!")
            else -> naz.say("Nazastarool returns with vengeance!")
        }
    }

    private fun nazastaroolKilled(event: NpcKilledEvent) {
        val form = SvNpcs.nazastarool.indexOfFirst { it.id == event.npc.type.id }
        if (form < 0) return
        val player = event.hero
        fighters.remove(player.uid.packed)
        if (player.questState(SHILO_VILLAGE) != BONE_3 + form) return
        player.setQuestState(SHILO_VILLAGE, NAZ_1 + form)
        when (form) {
            0 -> {
                player.mes(
                    "You defeat Nazastarool and the corpse falls to the ground. The bones start to move again and " +
                        "soon they reform into a grisly giant skeleton."
                )
                spawnForm(player, 1)
            }
            1 -> {
                player.mes(
                    "You defeat Nazastarool and the skeleton falls to the ground. An ethereal form starts taking shape " +
                        "above the bones and you soon face the vengeful ghost of Nazastarool."
                )
                spawnForm(player, 2)
            }
            else -> {
                player.mes("Nazastarool: May you perish in the fires of Zamorak's furnace! Rashiliyia's") // Wiki {{mes}}
                player.mes("Nazastarool: curse is upon you!") // Wiki {{mes}}
                player.mes(
                    "You take the mummified remains of a human female. You feel certain that these are Rashiliyia's " +
                        "remains. You carefully place the remains in your inventory."
                )
                player.invAddOrDrop(objRepo, SvObjs.remains)
            }
        }
    }

    private suspend fun ProtectedAccess.openTombExit() {
        val beads = wearsBeads()
        val hooks =
            SvHooks(
                this,
                conditions =
                    mapOf(
                        "If the player is not wearing the Beads of the Dead:" to { !beads },
                        "If the player is wearing the Beads of the Dead:" to { beads },
                    ),
                actions =
                    mapOf(
                        "Same as above from \"Rashiliyia appears!\" but also continues below" to {
                            spawnUndead(player, UNDEAD_AT_EXIT)
                        }
                    ),
            )
        svSection(dialogue, null, "Open Tomb exit", hooks)
    }

    private suspend fun ProtectedAccess.keyOnTombExit() {
        svSection(dialogue, null, "Use bone key on tomb exit")
        telejump(TOMB_OUTSIDE)
    }

    private fun spawnUndead(player: Player, count: Int) =
        spawnUndeadAt(player, count, npcRepo, npcTypes, aiInteractions)

    private companion object {
        const val MOUND_TICKS = 500
        const val NAZ_TICKS = 500
        const val COIN_UNDEAD = 3
        const val UNDEAD_AT_EXIT = 3
        const val LOOSE_ROCK_ODDS = 4
        const val WATERFALL_ODDS = 3
        const val HANDHOLD_ODDS = 3
        const val CAIRN_ROCK_ODDS = 4

        const val POMMEL_BOX =
            "You find a rusty sword with an ivory pommel. You take the Ivory pommel and place it in your inventory."
        const val CRYSTAL_BOX = "You find a Crystal Sphere. You take the crystal and place it carefully in your inventory."
        const val NOTES_BOX =
            "You find some writing on the dolmen, you grab some nearby scraps of delicate paper together and copy " +
                "the text as best you can and collect them together as a scroll."

        /* Positions from the rev 243 map (see the class doc). */
        /** Below the fissure, at the north end of Ah Za Rhoon. */
        val AH_ZA_RHOON_LANDING = CoordGrid(2888, 9385, 0)
        /** Beside the north cave-in (2887,9373) and the ruins' cave-in (2886,9283). */
        val NORTH_CAVE_IN = CoordGrid(2889, 9373, 0)
        val RUINS_CAVE_IN = CoordGrid(2888, 9284, 0)
        const val CAVE_IN_SPLIT_Z = 9330
        /** Outside the waterfall, north of the stepping stones east of Shilo Village. */
        val WATERFALL_EXIT = CoordGrid(2924, 2955, 0)
        /** Either side of the Cairn Isle rocks (2792-2794, 2978-2980). */
        val CAIRN_ROCKS_EAST = CoordGrid(2795, 2979, 0)
        val CAIRN_ROCKS_WEST = CoordGrid(2790, 2979, 0)
        const val CAIRN_ROCKS_X = 2794
        /** Beside the well stacked rocks (2762,2990) and inside the Tomb of Bervirius. */
        val CAIRN_OUTSIDE = CoordGrid(2763, 2989, 0)
        val BERVIRIUS_LANDING = CoordGrid(2761, 9377, 0)
        /** Inside the hillside doors (by the tomb exit 2928,9527) and outside them. */
        val TOMB_LANDING = CoordGrid(2929, 9525, 0)
        val TOMB_OUTSIDE = CoordGrid(2916, 3089, 0)
        /** Above and below the rocks south of the ancient gate (2928,9516). */
        val TOMB_ROCKS_TOP = CoordGrid(2929, 9516, 0)
        val TOMB_ROCKS_BOTTOM = CoordGrid(2928, 9509, 0)
        /** Inside the two tomb rooms (doors 2892,9480 and 2892,9497; dolmens 9487 / 9504). */
        val NAZ_SPAWN_1 = CoordGrid(2890, 9484, 0)
        val NAZ_SPAWN_2 = CoordGrid(2890, 9501, 0)
        const val SECOND_ROOM_Z = 9497
    }
}
