package org.rsmod.content.skills.farming

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.features.invFullForGathering
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.farmingLvl
import org.rsmod.api.player.stat.woodcuttingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.farming.FarmingData.Bin
import org.rsmod.content.skills.farming.FarmingData.Crop
import org.rsmod.content.skills.farming.FarmingData.Kind
import org.rsmod.content.skills.farming.FarmingData.Patch
import org.rsmod.content.skills.woodcutting.scripts.Woodcutting
import org.rsmod.content.skills.woodcutting.scripts.Woodcutting.Companion.axeWoodcuttingAnim
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Farming: allotments, herbs, flowers and compost bins at Falador, Catherby, Ardougne and Port
 * Phasmatys; oak/willow tree patches (Taverley, Falador, Varrock, Lumbridge, Gnome Stronghold);
 * low-tier hops (Yanille, Seers', Lumbridge, McGrubor's Wood).
 *
 * Each patch's state is saved in two varps (`farming_<patch>_state` / `_time`); growth is real
 * time (Wiki minutes per growth cycle, counted from planting, also while logged out). The client
 * draws patches from the shared `farming_transmit_*` varbits, which are rewritten for whichever
 * farming area the player is in (on login and every few seconds).
 *
 * - Rake weeds (4 xp and a weeds item per stage), compost/supercompost (+1/+2 harvest lives),
 *   plant with a seed dibber, water allotments/flowers (visual only: disease is off), harvest
 *   with Wiki save-a-life chances (flowers give a fixed amount), clear with a spade.
 * - Trees: plant a sapling with a spade, Check-health when grown (Wiki xp), then chop for logs
 *   (axe + Woodcutting level) until it becomes a stump, which regrows; a spade clears it.
 * - Compost bins: fill with 15 compostables, close, wait for it to rot, open, take buckets.
 * - Tool leprechauns note produce, and store tools, a watering can, buckets and compost.
 *
 * Not yet: disease/death, weed regrowth, supercompost, sapling growing, higher tiers.
 */
class Farming
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    private val patchesByLoc by lazy {
        FarmingData.patches.associateBy { FarmingLocs.byName.getValue(it.loc).id }
    }

    private val binsByLoc by lazy {
        FarmingData.bins.associateBy { FarmingLocs.byName.getValue(it.loc).id }
    }

    override fun ScriptContext.startup() {
        var childOps = 0
        val children = HashSet<Int>()
        for (patch in FarmingData.patches) {
            val base = FarmingLocs.byName.getValue(patch.loc)
            bindItems(patch, base)
            for (child in locTypes[base].multiLoc) {
                val id = child.toInt() and 0xFFFF
                if (id == 0xFFFF || !children.add(id)) continue
                childOps += bindChildOps(locTypes[id] ?: continue)
            }
        }
        for (bin in FarmingData.bins) {
            val base = FarmingLocs.byName.getValue(bin.loc)
            for (name in FarmingData.compostables) {
                onOpLocU(base, FarmingObjs.byName.getValue(name)) { fillBin(bin, it.objType.id, superItem = false) }
            }
            for (name in FarmingData.supercompostables) {
                onOpLocU(base, FarmingObjs.byName.getValue(name)) { fillBin(bin, it.objType.id, superItem = true) }
            }
            onOpLocU(base, FarmingObjs.bucket) { takeCompost(it.loc, bin, 0) }
            for (child in locTypes[base].multiLoc) {
                val id = child.toInt() and 0xFFFF
                if (id == 0xFFFF || !children.add(id)) continue
                childOps += bindChildOps(locTypes[id] ?: continue)
            }
        }
        val leprechaunOps = npcTypes[FarmingNpcs.leprechaun].op.map { it?.trim()?.lowercase() }
        for (label in listOf("talk-to", "exchange")) {
            when (leprechaunOps.indexOf(label) + 1) {
                1 -> if (!isNpcOpBound(FarmingNpcs.leprechaun, 1)) onOpNpc1(FarmingNpcs.leprechaun) { toolStore(it.npc) }
                2 -> if (!isNpcOpBound(FarmingNpcs.leprechaun, 2)) onOpNpc2(FarmingNpcs.leprechaun) { toolStore(it.npc) }
                3 -> if (!isNpcOpBound(FarmingNpcs.leprechaun, 3)) onOpNpc3(FarmingNpcs.leprechaun) { toolStore(it.npc) }
                4 -> if (!isNpcOpBound(FarmingNpcs.leprechaun, 4)) onOpNpc4(FarmingNpcs.leprechaun) { toolStore(it.npc) }
                5 -> if (!isNpcOpBound(FarmingNpcs.leprechaun, 5)) onOpNpc5(FarmingNpcs.leprechaun) { toolStore(it.npc) }
            }
        }
        val notable = FarmingData.crops.filter { it.kind !in FarmingData.woodKinds }.map { it.produce }
        for (produce in notable.distinct()) {
            val obj = FarmingObjs.byName.getValue(produce)
            onOpNpcU(FarmingNpcs.leprechaun, obj) { noteProduce(it.objType.id) }
        }
        // Gardeners: "Pay" (and "Pay (north)/(south)") protect a planted patch for the Wiki fee.
        for (gardener in FarmingNpcs.gardeners) {
            for ((index, text) in npcTypes[gardener].op.withIndex()) {
                if (text?.startsWith("Pay", ignoreCase = true) != true) continue
                val op = index + 1
                val which = if (text.contains("south", ignoreCase = true)) 1 else 0
                if (isNpcOpBound(gardener, op)) continue
                when (op) {
                    1 -> onOpNpc1(gardener) { pay(it.npc, which) }
                    2 -> onOpNpc2(gardener) { pay(it.npc, which) }
                    3 -> onOpNpc3(gardener) { pay(it.npc, which) }
                    4 -> onOpNpc4(gardener) { pay(it.npc, which) }
                    5 -> onOpNpc5(gardener) { pay(it.npc, which) }
                }
            }
        }
        onPlayerLogin { refresh(player) }
        onPlayerSoftTimer(FarmingTimers.refresh) { refresh(player) }
        logger.info {
            "Farming: ${FarmingData.patches.size} patches, ${FarmingData.bins.size} bins, " +
                "${children.size} stage locs " +
                "($childOps ops), ${FarmingData.crops.size} crops."
        }
    }

    /* Registration */

    private fun ScriptContext.bindItems(patch: Patch, base: LocType) {
        onOpLocU(base, FarmingObjs.rake) { rake(it.loc, patch, opIndex(it.type, "rake")) }
        onOpLocU(base, FarmingObjs.spade) { clear(patch) }
        onOpLocU(base, FarmingObjs.compost) { compost(patch, supercompost = false) }
        onOpLocU(base, FarmingObjs.supercompost) { compost(patch, supercompost = true) }
        if (patch.kind.wateredOffset > 0) {
            for (can in FarmingObjs.watering_cans.drop(1)) {
                onOpLocU(base, can) { water(patch, it.objType.id) }
            }
        }
        for (crop in FarmingData.crops.filter { it.kind == patch.kind }) {
            onOpLocU(base, FarmingObjs.byName.getValue(crop.seed)) { plant(patch, crop) }
        }
    }

    /** Child (stage) locs are shared by every patch of a kind; the event's base loc picks it. */
    private fun ScriptContext.bindChildOps(type: UnpackedLocType): Int {
        var bound = 0
        for ((index, text) in type.op.withIndex()) {
            val label = text?.trim()?.lowercase() ?: continue
            if (label !in PATCH_OPS && !label.startsWith("pick")) continue
            val op = index + 1
            val event =
                when (op) {
                    1 -> LocEvents.Op1::class.java
                    2 -> LocEvents.Op2::class.java
                    3 -> LocEvents.Op3::class.java
                    4 -> LocEvents.Op4::class.java
                    else -> LocEvents.Op5::class.java
                }
            if (eventBus.contains(event, type.id)) continue
            when (op) {
                1 -> if (!isLocOpBound(type, 1)) onOpLoc1(type) { patchOp(it.loc, label, op) }
                2 -> if (!isLocOpBound(type, 2)) onOpLoc2(type) { patchOp(it.loc, label, op) }
                3 -> if (!isLocOpBound(type, 3)) onOpLoc3(type) { patchOp(it.loc, label, op) }
                4 -> if (!isLocOpBound(type, 4)) onOpLoc4(type) { patchOp(it.loc, label, op) }
                5 -> if (!isLocOpBound(type, 5)) onOpLoc5(type) { patchOp(it.loc, label, op) }
            }
            bound++
        }
        return bound
    }

    private fun ProtectedAccess.patchOp(loc: BoundLocInfo, label: String, op: Int) {
        val bin = binsByLoc[loc.id]
        if (bin != null) {
            when (label) {
                "close" -> closeBin(bin)
                "open" -> openBin(bin)
                "take" -> takeCompost(loc, bin, op)
                "dump" -> dumpBin(bin)
            }
            return
        }
        val patch = patchesByLoc[loc.id] ?: return
        when (label) {
            "rake" -> rake(loc, patch, op)
            "harvest",
            "pick" -> harvest(loc, patch, op)
            "check-health" -> checkHealth(patch)
            "chop down",
            "chop-down" -> chop(loc, patch, op)
            "clear" -> clear(patch)
            "inspect" -> inspect(patch)
            else -> if (label.startsWith("pick")) harvest(loc, patch, op)
        }
    }

    /* Patch state: [state varp] bits 0-1 weeds (3 = clear), 2-7 crop (index + 1), 8-9 compost,
     * 10 watered (trees: health checked), 11-14 harvest lives, 15 tree stump; [time varp] minute
     * the crop was planted (trees, once checked: minute the tree was cut down). */

    private class State(
        var weeds: Int,
        var crop: Crop?,
        var compost: Int,
        var watered: Boolean,
        var lives: Int,
        var stump: Boolean = false,
        var protected: Boolean = false,
    ) {
        fun pack(): Int =
            (weeds and 3) or
                ((crop?.let { FarmingData.crops.indexOf(it) + 1 } ?: 0) shl 2) or
                ((compost and 3) shl 8) or
                ((if (watered) 1 else 0) shl 10) or
                ((lives and 15) shl 11) or
                ((if (stump) 1 else 0) shl 15) or
                ((if (protected) 1 else 0) shl 16)
    }

    private fun unpack(value: Int): State =
        State(
            weeds = value and 3,
            crop = FarmingData.crops.getOrNull(((value shr 2) and 63) - 1),
            compost = (value shr 8) and 3,
            watered = (value shr 10) and 1 == 1,
            lives = (value shr 11) and 15,
            stump = (value shr 15) and 1 == 1,
            protected = (value shr 16) and 1 == 1,
        )

    private fun Player.state(patch: Patch): State =
        unpack(vars[FarmingVarps.byName.getValue(patch.stateVarp)])

    private fun Player.planted(patch: Patch): Int =
        vars[FarmingVarps.byName.getValue(patch.timeVarp)]

    private fun Player.save(patch: Patch, state: State, planted: Int? = null) {
        val stateVarp = FarmingVarps.byName.getValue(patch.stateVarp)
        VarPlayerIntMapSetter.set(this, stateVarp, state.pack())
        if (planted != null) {
            VarPlayerIntMapSetter.set(this, FarmingVarps.byName.getValue(patch.timeVarp), planted)
        }
        refresh(this)
    }

    private fun nowMinutes(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    /** Growth cycles completed (capped at the crop's cycles = fully grown). */
    private fun Player.stage(patch: Patch, crop: Crop): Int =
        (max(0, nowMinutes() - planted(patch)) / crop.minutesPerCycle).coerceAtMost(crop.cycles)

    private fun Player.isGrown(patch: Patch, crop: Crop): Boolean =
        (crop.kind in TREE_LIKE && state(patch).watered) || stage(patch, crop) >= crop.cycles

    /** Fruit/berries now on a health-checked fruit kind: stored count + regrowth since [planted]. */
    private fun Player.fruits(patch: Patch, state: State, crop: Crop): Int {
        val regrown = max(0, nowMinutes() - planted(patch)) / max(1, crop.regrowMinutes)
        return (state.lives + regrown).coerceAtMost(crop.maxFruit)
    }

    /** A cut-down tree stays a stump for [FarmingData.STUMP_MINUTES], then regrows. */
    private fun Player.isStump(patch: Patch, state: State): Boolean =
        state.stump && nowMinutes() - planted(patch) < FarmingData.STUMP_MINUTES

    private fun Player.displayValue(patch: Patch): Int {
        val state = state(patch)
        val crop = state.crop ?: return state.weeds
        if (crop.kind in FarmingData.woodKinds && state.watered) {
            return if (isStump(patch, state)) crop.stumpValue else crop.checkedValue
        }
        if (crop.kind in FarmingData.fruitKinds) {
            if (!state.watered) {
                return if (stage(patch, crop) >= crop.cycles) crop.checkedValue
                else crop.seedValue + stage(patch, crop)
            }
            if (state.stump) return crop.stumpValue
            val fruit = fruits(patch, state, crop)
            return if (fruit == 0) crop.grownValue else crop.fruitValue + fruit - 1
        }
        val stage = stage(patch, crop)
        if (stage >= crop.cycles) return crop.grownValue
        val watered = if (state.watered) patch.kind.wateredOffset else 0
        return crop.seedValue + stage + watered
    }

    /* Client visuals */

    /**
     * Rewrites the area's transmit varbits every time (4 varbits every few seconds) so the client
     * is always in sync, including right after login.
     */
    private fun refresh(player: Player) {
        player.softTimer(FarmingTimers.refresh, REFRESH_TICKS)
        val coords = player.coords
        // Every area in range; the nearest is written last so it wins a shared transmit letter.
        val inRange =
            FarmingData.allAreas
                .filter {
                    coords.level == it.centre.level &&
                        abs(coords.x - it.centre.x) <= FarmingData.AREA_RADIUS &&
                        abs(coords.z - it.centre.z) <= FarmingData.AREA_RADIUS
                }
                .sortedByDescending { abs(coords.x - it.centre.x) + abs(coords.z - it.centre.z) }
        for (area in inRange) {
            for (patch in area.patches) {
                val varbit = FarmingVarBits.byName.getValue(patch.transmit)
                VarPlayerIntMapSetter.set(player, varbit, player.displayValue(patch))
            }
            val bin = area.bin ?: continue
            val binVarbit = FarmingVarBits.byName.getValue(bin.transmit)
            VarPlayerIntMapSetter.set(player, binVarbit, player.binValue(bin))
        }
    }

    /* Actions */

    private fun ProtectedAccess.rake(loc: BoundLocInfo, patch: Patch, op: Int = 0) {
        val state = player.state(patch)
        if (state.crop != null || state.weeds >= 3) {
            mes("This patch doesn't need weeding right now.")
            return
        }
        if (invTotal(inv, FarmingObjs.rake) == 0) {
            mes("You need a rake to weed a farming patch.")
            return
        }
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(FarmingSeqs.rake)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + RAKE_TICKS
        } else if (actionDelay == mapClock) {
            state.weeds++
            invAddOrDropWeeds()
            statAdvance(stats.farming, FarmingData.RAKE_XP * xpMods.get(player, stats.farming))
            player.save(patch, state)
            if (state.weeds >= 3) {
                resetAnim()
                mes("You've cleared the ${patch.kind.noun} of weeds.")
                return
            }
            actionDelay = mapClock + RAKE_TICKS
        }
        reop(loc, op)
    }

    private fun ProtectedAccess.invAddOrDropWeeds() {
        if (!inv.isFull()) invAdd(inv, FarmingObjs.weeds)
    }

    private fun ProtectedAccess.compost(patch: Patch, supercompost: Boolean) {
        val state = player.state(patch)
        if (state.compost > 0) {
            mes("This ${patch.kind.noun} has already been treated with compost.")
            return
        }
        val crop = state.crop
        if (crop != null && player.isGrown(patch, crop)) {
            mes("This ${patch.kind.noun} doesn't need compost now.")
            return
        }
        val bucket = if (supercompost) FarmingObjs.supercompost else FarmingObjs.compost
        invDel(inv, bucket)
        invAdd(inv, FarmingObjs.bucket)
        anim(FarmingSeqs.compost)
        state.compost = if (supercompost) 2 else 1
        if (crop != null) state.lives += state.compost
        player.save(patch, state)
        val what = if (supercompost) "supercompost" else "compost"
        mes("You treat the ${patch.kind.noun} with $what.")
        statAdvance(
            stats.farming,
            (if (supercompost) 26.0 else 18.0) * xpMods.get(player, stats.farming),
        )
    }

    private fun ProtectedAccess.plant(patch: Patch, crop: Crop) {
        val state = player.state(patch)
        if (state.crop != null) {
            mes("You can only plant ${crop.key} seeds in an empty ${patch.kind.noun}.")
            return
        }
        if (state.weeds < 3) {
            mes("This ${patch.kind.noun} needs weeding first.")
            return
        }
        if (player.farmingLvl < crop.level) {
            mes("You must be a Level ${crop.level} Farmer to plant those.")
            return
        }
        val sapling = crop.kind in FarmingData.saplingKinds
        val tool = if (sapling) FarmingObjs.spade else FarmingObjs.dibber
        if (invTotal(inv, tool) == 0) {
            val name = if (sapling) "spade" else "seed dibber"
            mes("You need a $name to plant that.")
            return
        }
        val seed = FarmingObjs.byName.getValue(crop.seed)
        if (invTotal(inv, seed) < crop.seedsPer) {
            mes("You need ${crop.seedsPer} seeds to plant a ${patch.kind.noun}.")
            return
        }
        invDel(inv, seed, crop.seedsPer)
        if (sapling) {
            invAdd(inv, FarmingObjs.plantpot)
            anim(FarmingSeqs.dig)
        } else {
            anim(FarmingSeqs.dibbing)
        }
        state.crop = crop
        state.watered = false
        state.lives = if (crop.fixedYield > 0) 1 else crop.baseLives + state.compost
        state.protected = false
        player.save(patch, state, planted = nowMinutes())
        statAdvance(stats.farming, crop.plantXp * xpMods.get(player, stats.farming))
        val seeds =
            when {
                sapling -> "the ${crop.key} sapling"
                crop.seedsPer > 1 -> "${crop.seedsPer} ${crop.key} seeds"
                else -> "a ${crop.key} seed"
            }
        mes("You plant $seeds in the ${patch.kind.noun}.")
    }

    private fun ProtectedAccess.water(patch: Patch, canId: Int) {
        val state = player.state(patch)
        val crop = state.crop
        if (crop == null || player.isGrown(patch, crop)) {
            mes("This ${patch.kind.noun} doesn't need watering.")
            return
        }
        if (state.watered) {
            mes("This ${patch.kind.noun} has already been watered.")
            return
        }
        val cans = FarmingObjs.watering_cans
        val charges = cans.indexOfFirst { it.id == canId }
        if (charges <= 0) return
        invDel(inv, cans[charges])
        invAdd(inv, cans[charges - 1])
        anim(FarmingSeqs.watering)
        state.watered = true
        player.save(patch, state)
        mes("You water the ${patch.kind.noun}.")
    }

    private fun ProtectedAccess.harvest(loc: BoundLocInfo, patch: Patch, op: Int) {
        val state = player.state(patch)
        val crop = state.crop ?: return
        if (crop.kind in FarmingData.woodKinds) return
        if (crop.kind in FarmingData.fruitKinds) {
            pickFruit(loc, patch, state, crop, op)
            return
        }
        if (!player.isGrown(patch, crop)) {
            mes("The ${crop.key} isn't ready to harvest yet.")
            return
        }
        if (patch.kind == Kind.Allotment && invTotal(inv, FarmingObjs.spade) == 0) {
            mes("You need a spade to harvest your crops.")
            return
        }
        val produce = FarmingObjs.byName.getValue(crop.produce)
        if (invFullForGathering(objTypes, listOf(produce))) {
            mes("You don't have enough room in your inventory.")
            return
        }
        if (crop.fixedYield > 0) {
            anim(FarmingSeqs.pick)
            invAddGathered(produce, crop.fixedYield)
            statAdvance(stats.farming, crop.harvestXp * xpMods.get(player, stats.farming))
            mes("You harvest the ${patch.kind.noun}.")
            empty(patch)
            return
        }
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(if (patch.kind == Kind.Allotment) FarmingSeqs.dig else FarmingSeqs.pick)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + HARVEST_TICKS
            spam("You begin to harvest the ${patch.kind.noun}.")
        } else if (actionDelay == mapClock) {
            invAddGathered(produce)
            statAdvance(stats.farming, crop.harvestXp * xpMods.get(player, stats.farming))
            if (random.of(10_000) >= (crop.saveChance(player.farmingLvl) * 100).toInt()) {
                state.lives--
            }
            if (state.lives <= 0) {
                resetAnim()
                mes("The ${patch.kind.noun} is now empty.")
                empty(patch)
                return
            }
            player.save(patch, state)
            actionDelay = mapClock + HARVEST_TICKS
        }
        reop(loc, op)
    }

    /** One fruit/berry per [HARVEST_TICKS]; the stored count is re-based on every pick. */
    private fun ProtectedAccess.pickFruit(loc: BoundLocInfo, patch: Patch, state: State, crop: Crop, op: Int) {
        if (!state.watered) {
            mes("You should check the health of the ${patch.kind.noun.removeSuffix(" patch")} first.")
            return
        }
        val fruit = player.fruits(patch, state, crop)
        if (fruit == 0) {
            mes("There's nothing left to pick.")
            return
        }
        val produce = FarmingObjs.byName.getValue(crop.produce)
        if (invFullForGathering(objTypes, listOf(produce))) {
            mes("You don't have enough room in your inventory.")
            return
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + HARVEST_TICKS
            anim(FarmingSeqs.pick)
        } else if (actionDelay == mapClock) {
            invAddGathered(produce)
            statAdvance(stats.farming, crop.harvestXp * xpMods.get(player, stats.farming))
            state.lives = fruit - 1
            player.save(patch, state, planted = nowMinutes())
            if (fruit - 1 == 0) {
                resetAnim()
                return
            }
            actionDelay = mapClock + HARVEST_TICKS
            anim(FarmingSeqs.pick)
        }
        reop(loc, op)
    }

    /**
     * Wiki farmer payments: the gardener nearest the patch takes the crop's fee and looks after
     * it. Disease is off on this server, so protection only changes the inspect text.
     */
    private suspend fun ProtectedAccess.pay(npc: Npc, which: Int) {
        val area =
            FarmingData.allAreas.minByOrNull {
                abs(it.centre.x - npc.coords.x) + abs(it.centre.z - npc.coords.z)
            } ?: return
        val planted = area.patches.filter { player.state(it).crop != null }
        val patch = planted.getOrNull(which) ?: planted.firstOrNull()
        if (patch == null) {
            startDialogue(npc) { chatNpc(neutral, "You don't have anything planted that I can look after.") }
            return
        }
        val state = player.state(patch)
        val crop = state.crop ?: return
        if (state.protected) {
            startDialogue(npc) { chatNpc(happy, "I'm already looking after that patch for you.") }
            return
        }
        val fee = crop.payment?.let { FarmingObjs.byName.getValue(it) }
        if (fee == null || crop.paymentCount <= 0) {
            startDialogue(npc) { chatNpc(neutral, "That doesn't need looking after.") }
            return
        }
        if (invTotal(inv, fee) < crop.paymentCount) {
            val name = objTypes[fee].name.lowercase()
            startDialogue(npc) {
                chatNpc(neutral, "I'll look after that for ${crop.paymentCount} $name.")
            }
            return
        }
        invDel(inv, fee, crop.paymentCount)
        state.protected = true
        player.save(patch, state)
        startDialogue(npc) {
            chatNpc(happy, "That'll do nicely. Leave it with me - I'll make sure it grows for you.")
        }
    }

    private fun ProtectedAccess.empty(patch: Patch) {
        val cleared = State(weeds = 3, crop = null, compost = 0, watered = false, lives = 0)
        player.save(patch, cleared, planted = 0)
    }

    private fun ProtectedAccess.clear(patch: Patch) {
        val state = player.state(patch)
        if (state.crop == null) {
            mes("There's nothing to dig up in this ${patch.kind.noun}.")
            return
        }
        if (invTotal(inv, FarmingObjs.spade) == 0) {
            mes("You need a spade to clear this ${patch.kind.noun}.")
            return
        }
        anim(FarmingSeqs.dig)
        empty(patch)
        mes("You dig up the ${patch.kind.noun}.")
    }

    private fun ProtectedAccess.inspect(patch: Patch) {
        val state = player.state(patch)
        val crop = state.crop
        val compost =
            when (state.compost) {
                1 -> "compost"
                2 -> "supercompost"
                else -> "no compost"
            }
        if (crop == null) {
            val weeds = if (state.weeds >= 3) "is empty" else "needs weeding"
            mes("This ${patch.kind.noun} $weeds and has been treated with $compost.")
            return
        }
        val status =
            if (player.isGrown(patch, crop)) "is fully grown"
            else {
                val total = crop.cycles * crop.minutesPerCycle
                val left = total - (nowMinutes() - player.planted(patch))
                "is growing (about ${max(1, left)} minutes left)"
            }
        val guarded = if (state.protected) " A gardener is looking after it." else ""
        mes("The ${crop.key} in this ${patch.kind.noun} $status (treated with $compost).$guarded")
    }

    /* Trees */

    private fun ProtectedAccess.checkHealth(patch: Patch) {
        val state = player.state(patch)
        val crop = state.crop ?: return
        if (crop.kind !in TREE_LIKE || state.watered || !player.isGrown(patch, crop)) return
        anim(FarmingSeqs.check)
        state.watered = true
        if (crop.kind in FarmingData.fruitKinds) {
            // Grown with a full load; [planted] becomes the regrowth reference.
            state.lives = crop.maxFruit
            player.save(patch, state, planted = nowMinutes())
        } else {
            player.save(patch, state)
        }
        statAdvance(stats.farming, crop.checkXp * xpMods.get(player, stats.farming))
        mes("You examine the tree for signs of disease and find that it is in perfect health.")
    }

    private fun ProtectedAccess.chop(loc: BoundLocInfo, patch: Patch, op: Int) {
        val state = player.state(patch)
        val crop = state.crop ?: return
        if (crop.kind == Kind.FruitTree && state.watered && !state.stump) {
            if (Woodcutting.findAxe(player, objTypes) == null) {
                mes("You need an axe to chop down this tree.")
                return
            }
            anim(FarmingSeqs.dig)
            state.stump = true
            player.save(patch, state)
            mes("You chop down the tree; dig up the stump with a spade.")
            return
        }
        if (crop.kind !in FarmingData.woodKinds || !state.watered || crop.logs.isEmpty()) return
        if (player.isStump(patch, state)) {
            mes("This tree has already been cut down.")
            return
        }
        val axe = Woodcutting.findAxe(player, objTypes)
        if (axe == null) {
            mes("You need an axe to chop down this tree.")
            mes("You do not have an axe which you have the Woodcutting level to use.")
            return
        }
        if (player.woodcuttingLvl < crop.woodLevel) {
            mes("You need a Woodcutting level of ${crop.woodLevel} to chop down this tree.")
            return
        }
        if (invFullForGathering(objTypes, listOf(FarmingObjs.byName.getValue(crop.logs)))) {
            mes("Your inventory is too full to hold any more logs.")
            return
        }
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(objTypes[axe].axeWoodcuttingAnim)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + CHOP_TICKS
            spam("You swing your axe at the tree.")
        } else if (actionDelay == mapClock) {
            // Approximation of the Woodcutting curve: better odds the further above the tree.
            val chance = (40 + (player.woodcuttingLvl - crop.woodLevel) * 2).coerceIn(20, 90)
            if (random.of(100) < chance) {
                invAddGathered(FarmingObjs.byName.getValue(crop.logs))
                statAdvance(stats.woodcutting, crop.woodXp * xpMods.get(player, stats.woodcutting))
                spam("You get some ${crop.key} logs.")
                if (random.of(STUMP_CHANCE) == 0) {
                    resetAnim()
                    state.stump = true
                    player.save(patch, state, planted = nowMinutes())
                    mes("You chop down the tree.")
                    return
                }
            }
            actionDelay = mapClock + CHOP_TICKS
        }
        reop(loc, op)
    }

    /* Compost bins: [state varp] bits 0-3 count, 4-5 phase (0 filling, 1 closed, 2 open), 6 super;
     * [time varp] minute the bin was closed. */

    private fun Player.binState(bin: Bin): Int = vars[FarmingVarps.byName.getValue(bin.stateVarp)]

    private fun Player.saveBin(bin: Bin, count: Int, phase: Int, closed: Int? = null, superBin: Boolean = isSuperBin(bin)) {
        val stateVarp = FarmingVarps.byName.getValue(bin.stateVarp)
        val flag = if (superBin && count > 0) BIN_SUPER_FLAG else 0
        VarPlayerIntMapSetter.set(this, stateVarp, count or (phase shl 4) or flag)
        if (closed != null) {
            VarPlayerIntMapSetter.set(this, FarmingVarps.byName.getValue(bin.timeVarp), closed)
        }
        refresh(this)
    }

    private fun Player.isSuperBin(bin: Bin): Boolean = (binState(bin) and BIN_SUPER_FLAG) != 0

    private fun Player.binRotted(bin: Bin): Boolean {
        val minutes = if (isSuperBin(bin)) FarmingData.SUPER_ROT_MINUTES else FarmingData.ROT_MINUTES
        return nowMinutes() - vars[FarmingVarps.byName.getValue(bin.timeVarp)] >= minutes
    }

    private fun Player.binValue(bin: Bin): Int {
        val state = binState(bin)
        val count = state and 15
        // Cache multiloc (farming_compost_bin_*): supercompost fills 33+, opens 48+, rots 95, ready 112+.
        val sup = (state and BIN_SUPER_FLAG) != 0
        return when ((state shr 4) and 3) {
            0 -> if (count == 0) 0 else if (sup) BIN_SUPER_FILL + count - 1 else count
            1 ->
                when {
                    !binRotted(bin) -> if (sup) BIN_SUPER_ROTTING else BIN_ROTTING
                    sup -> BIN_SUPER_READY + count - 1
                    else -> BIN_READY + count - 1
                }
            else -> if (count == 0) 0 else if (sup) BIN_SUPER_OPEN + count - 1 else BIN_OPEN + count - 1
        }
    }

    private fun ProtectedAccess.fillBin(bin: Bin, objId: Int, superItem: Boolean) {
        val state = player.binState(bin)
        val count = state and 15
        if (((state shr 4) and 3) != 0 || count >= BIN_SIZE) {
            mes("The compost bin is too full to put anything else in it.")
            return
        }
        val type = objTypes[objId] ?: return
        val added = minOf(BIN_SIZE - count, invTotal(inv, type))
        if (added <= 0) return
        invDel(inv, type, added)
        anim(FarmingSeqs.bin)
        // Any regular compostable turns the whole bin into regular compost (Wiki).
        val superBin = superItem && (count == 0 || player.isSuperBin(bin))
        player.saveBin(bin, count + added, 0, superBin = superBin)
        mes("You fill the compost bin with ${type.name.lowercase()}.")
    }

    private fun ProtectedAccess.closeBin(bin: Bin) {
        val state = player.binState(bin)
        if (((state shr 4) and 3) != 0 || (state and 15) < BIN_SIZE) return
        anim(FarmingSeqs.bin)
        player.saveBin(bin, BIN_SIZE, 1, closed = nowMinutes())
        mes("You close the compost bin, and the contents start to rot.")
    }

    private fun ProtectedAccess.openBin(bin: Bin) {
        val state = player.binState(bin)
        if (((state shr 4) and 3) != 1) return
        if (!player.binRotted(bin)) {
            mes("The compost bin is still rotting; give it a while longer.")
            return
        }
        anim(FarmingSeqs.bin)
        player.saveBin(bin, state and 15, 2)
        mes("You open the compost bin.")
    }

    private fun ProtectedAccess.takeCompost(loc: BoundLocInfo, bin: Bin, op: Int) {
        val state = player.binState(bin)
        val count = state and 15
        if (((state shr 4) and 3) != 2 || count == 0) {
            mes("There is no compost ready to take.")
            return
        }
        if (invTotal(inv, FarmingObjs.bucket) == 0) {
            mes("You need an empty bucket to take compost.")
            return
        }
        if (actionDelay > mapClock) {
            reop(loc, op)
            return
        }
        anim(FarmingSeqs.bin)
        invDel(inv, FarmingObjs.bucket)
        val sup = player.isSuperBin(bin)
        invAdd(inv, if (sup) FarmingObjs.supercompost else FarmingObjs.compost)
        val takeXp = if (sup) SUPERCOMPOST_TAKE_XP else COMPOST_TAKE_XP
        statAdvance(stats.farming, takeXp * xpMods.get(player, stats.farming))
        player.saveBin(bin, count - 1, if (count - 1 == 0) 0 else 2)
        actionDelay = mapClock + 2
        if (count - 1 > 0 && invTotal(inv, FarmingObjs.bucket) > 0) reop(loc, op)
    }

    private fun ProtectedAccess.dumpBin(bin: Bin) {
        player.saveBin(bin, 0, 0)
        mes("You empty the compost bin.")
    }

    /* Tool leprechaun store: [store varp] bits 0-4 rake/dibber/spade/secateurs/trowel,
     * 5-8 watering can (charges + 1, 0 = none), 9-13 buckets, 14-21 compost, 22-29 supercompost. */

    private val storeTools by lazy {
        listOf(
            FarmingObjs.rake,
            FarmingObjs.dibber,
            FarmingObjs.spade,
            FarmingObjs.secateurs,
            FarmingObjs.trowel,
        )
    }

    /** Counted store slots: obj, bit shift, bit width. */
    private val storeCounts: List<Triple<() -> ObjType, Int, Int>> =
        listOf(
            Triple({ FarmingObjs.bucket }, 9, 5),
            Triple({ FarmingObjs.compost }, 14, 8),
            Triple({ FarmingObjs.supercompost }, 22, 8),
        )

    private fun Player.store(): Int = vars[FarmingVarps.byName.getValue(FarmingData.STORE_VARP)]

    private fun Player.saveStore(value: Int) {
        VarPlayerIntMapSetter.set(this, FarmingVarps.byName.getValue(FarmingData.STORE_VARP), value)
    }

    private fun field(value: Int, shift: Int, bits: Int): Int =
        (value shr shift) and ((1 shl bits) - 1)

    private fun withField(value: Int, shift: Int, bits: Int, field: Int): Int {
        val mask = ((1 shl bits) - 1) shl shift
        return (value and mask.inv()) or ((field shl shift) and mask)
    }

    private suspend fun ProtectedAccess.toolStore(npc: Npc) {
        var choice = 0
        startDialogue(npc) {
            chatNpc(happy, "Ah, 'tis a grand day to be farming! Want me to look after your tools?")
            choice =
                choice3(
                    "Yes, store my farming tools and compost.",
                    1,
                    "I'd like some tools back, please.",
                    2,
                    "No thanks.",
                    3,
                )
        }
        when (choice) {
            1 -> storeAll()
            2 -> withdrawMenu(npc)
        }
    }

    private fun ProtectedAccess.storeAll() {
        var value = player.store()
        var stored = 0
        for ((index, tool) in storeTools.withIndex()) {
            if (field(value, index, 1) == 0 && invTotal(inv, tool) > 0) {
                invDel(inv, tool)
                value = withField(value, index, 1, 1)
                stored++
            }
        }
        if (field(value, 5, 4) == 0) {
            val cans = FarmingObjs.watering_cans
            val best = cans.indices.reversed().firstOrNull { invTotal(inv, cans[it]) > 0 }
            if (best != null) {
                invDel(inv, cans[best])
                value = withField(value, 5, 4, best + 1)
                stored++
            }
        }
        for ((obj, shift, bits) in storeCounts) {
            val updated = storeCount(value, obj(), shift, bits)
            if (updated != value) stored++
            value = updated
        }
        player.saveStore(value)
        if (stored > 0) {
            mes("The leprechaun stores your items.")
        } else {
            mes("You have nothing the leprechaun can store.")
        }
    }

    private fun ProtectedAccess.storeCount(value: Int, obj: ObjType, shift: Int, bits: Int): Int {
        val have = field(value, shift, bits)
        val room = (1 shl bits) - 1 - have
        val moved = minOf(room, invTotal(inv, obj))
        if (moved <= 0) return value
        invDel(inv, obj, moved)
        return withField(value, shift, bits, have + moved)
    }

    private suspend fun ProtectedAccess.withdrawMenu(npc: Npc) {
        var pick = 0
        startDialogue(npc) {
            pick =
                choice5("Rake", 1, "Seed dibber", 2, "Spade", 3, "Secateurs", 4, "More...", 5)
            if (pick == 5) {
                pick =
                    choice5(
                        "Gardening trowel",
                        5,
                        "Watering can",
                        6,
                        "All my buckets",
                        7,
                        "All my compost",
                        8,
                        "All my supercompost",
                        9,
                    )
            }
        }
        var value = player.store()
        when (pick) {
            in 1..5 -> {
                val index = pick - 1
                if (field(value, index, 1) == 0) {
                    mes("The leprechaun isn't storing one of those for you.")
                    return
                }
                if (inv.isFull()) {
                    mes("You don't have enough inventory space.")
                    return
                }
                value = withField(value, index, 1, 0)
                invAdd(inv, storeTools[index])
            }
            6 -> {
                val can = field(value, 5, 4)
                if (can == 0 || inv.isFull()) {
                    mes("You can't take a watering can back right now.")
                    return
                }
                value = withField(value, 5, 4, 0)
                invAdd(inv, FarmingObjs.watering_cans[can - 1])
            }
            7 -> value = withdrawCount(value, FarmingObjs.bucket, 9, 5)
            8 -> value = withdrawCount(value, FarmingObjs.compost, 14, 8)
            9 -> value = withdrawCount(value, FarmingObjs.supercompost, 22, 8)
            else -> return
        }
        player.saveStore(value)
    }

    private fun ProtectedAccess.withdrawCount(
        value: Int,
        obj: ObjType,
        shift: Int,
        bits: Int,
    ): Int {
        val have = field(value, shift, bits)
        val moved = minOf(have, inv.freeSpace())
        if (moved <= 0) {
            mes("You can't take any of those back right now.")
            return value
        }
        invAdd(inv, obj, moved)
        return withField(value, shift, bits, have - moved)
    }

    private fun ProtectedAccess.noteProduce(objId: Int) {
        val type = objTypes[objId] ?: return
        if (type.certlink <= 0 || type.certtemplate > 0) {
            mes("The leprechaun can't note that.")
            return
        }
        val count = invTotal(inv, type)
        invDel(inv, type, count)
        invAdd(inv, type, count, cert = true)
        mes("The leprechaun exchanges your items for banknotes.")
    }

    private fun opIndex(type: UnpackedLocType, label: String): Int =
        type.op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1

    private fun ProtectedAccess.reop(loc: BoundLocInfo, op: Int) {
        when (op) {
            1 -> opLoc1(loc)
            2 -> opLoc2(loc)
            3 -> opLoc3(loc)
            4 -> opLoc4(loc)
        }
    }

    private companion object {
        val PATCH_OPS =
            setOf(
                "rake",
                "harvest",
                "pick",
                "clear",
                "inspect",
                "check-health",
                "chop down",
                "chop-down",
                "close",
                "open",
                "take",
                "dump",
            )
        /** Kinds with a health check once grown. */
        val TREE_LIKE = setOf(Kind.Tree, Kind.FruitTree, Kind.Bush, Kind.Cactus, Kind.Hardwood, Kind.SpiritTree)
        const val CHOP_TICKS = 4
        const val STUMP_CHANCE = 8
        const val BIN_SIZE = 15
        const val BIN_OPEN = 16
        const val BIN_ROTTING = 31
        const val BIN_READY = 80
        const val COMPOST_TAKE_XP = 4.5
        const val SUPERCOMPOST_TAKE_XP = 8.5
        const val BIN_SUPER_FLAG = 64
        const val BIN_SUPER_FILL = 33
        const val BIN_SUPER_OPEN = 48
        const val BIN_SUPER_ROTTING = 95
        const val BIN_SUPER_READY = 112
        const val REFRESH_TICKS = 8
        const val RAKE_TICKS = 4
        const val HARVEST_TICKS = 3
    }
}
