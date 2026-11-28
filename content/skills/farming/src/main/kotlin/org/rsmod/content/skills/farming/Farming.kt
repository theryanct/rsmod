package org.rsmod.content.skills.farming

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.farmingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.farming.FarmingData.Crop
import org.rsmod.content.skills.farming.FarmingData.Kind
import org.rsmod.content.skills.farming.FarmingData.Patch
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Farming PR1: allotments, herbs and flowers at Falador, Catherby, Ardougne and Port Phasmatys.
 *
 * Each patch's state is saved in two varps (`farming_<patch>_state` / `_time`); growth is real
 * time (Wiki minutes per growth cycle, counted from planting, also while logged out). The client
 * draws patches from the shared `farming_transmit_*` varbits, which are rewritten for whichever
 * farming area the player is in (on login and every few seconds).
 *
 * - Rake weeds (4 xp and a weeds item per stage), compost/supercompost (+1/+2 harvest lives),
 *   plant with a seed dibber, water allotments/flowers (visual only: disease is off), harvest
 *   with Wiki save-a-life chances (flowers give a fixed amount), clear with a spade.
 * - Tool leprechauns note any PR1 produce used on them.
 *
 * Not yet: disease/death, weed regrowth, trees, hops, the leprechaun's tool store.
 */
class Farming
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()

    private val patchesByLoc by lazy {
        FarmingData.patches.associateBy { FarmingLocs.byName.getValue(it.loc).id }
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
        for (produce in FarmingData.crops.map { it.produce }.distinct()) {
            val obj = FarmingObjs.byName.getValue(produce)
            onOpNpcU(FarmingNpcs.leprechaun, obj) { noteProduce(it.objType.id) }
        }
        onPlayerLogin { refresh(player) }
        onPlayerSoftTimer(FarmingTimers.refresh) { refresh(player) }
        logger.info {
            "Farming: ${FarmingData.patches.size} patches, ${children.size} patch stage locs " +
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
            if (label !in PATCH_OPS) continue
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
                1 -> onOpLoc1(type) { patchOp(it.loc, label, op) }
                2 -> onOpLoc2(type) { patchOp(it.loc, label, op) }
                3 -> onOpLoc3(type) { patchOp(it.loc, label, op) }
                4 -> onOpLoc4(type) { patchOp(it.loc, label, op) }
                5 -> onOpLoc5(type) { patchOp(it.loc, label, op) }
            }
            bound++
        }
        return bound
    }

    private fun ProtectedAccess.patchOp(loc: BoundLocInfo, label: String, op: Int) {
        val patch = patchesByLoc[loc.id] ?: return
        when (label) {
            "rake" -> rake(loc, patch, op)
            "harvest",
            "pick" -> harvest(loc, patch, op)
            "clear" -> clear(patch)
            "inspect" -> inspect(patch)
        }
    }

    /* Patch state: [state varp] bits 0-1 weeds (3 = clear), 2-7 crop (index + 1), 8-9 compost,
     * 10 watered, 11-14 harvest lives; [time varp] minute the crop was planted. */

    private class State(
        var weeds: Int,
        var crop: Crop?,
        var compost: Int,
        var watered: Boolean,
        var lives: Int,
    ) {
        fun pack(): Int =
            (weeds and 3) or
                ((crop?.let { FarmingData.crops.indexOf(it) + 1 } ?: 0) shl 2) or
                ((compost and 3) shl 8) or
                ((if (watered) 1 else 0) shl 10) or
                ((lives and 15) shl 11)
    }

    private fun unpack(value: Int): State =
        State(
            weeds = value and 3,
            crop = FarmingData.crops.getOrNull(((value shr 2) and 63) - 1),
            compost = (value shr 8) and 3,
            watered = (value shr 10) and 1 == 1,
            lives = (value shr 11) and 15,
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
        stage(patch, crop) >= crop.cycles

    private fun Player.displayValue(patch: Patch): Int {
        val state = state(patch)
        val crop = state.crop ?: return state.weeds
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
        val area =
            FarmingData.areas.firstOrNull {
                coords.level == it.centre.level &&
                    abs(coords.x - it.centre.x) <= FarmingData.AREA_RADIUS &&
                    abs(coords.z - it.centre.z) <= FarmingData.AREA_RADIUS
            } ?: return
        for (patch in area.patches) {
            val varbit = FarmingVarBits.byName.getValue(patch.transmit)
            VarPlayerIntMapSetter.set(player, varbit, player.displayValue(patch))
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
        if (invTotal(inv, FarmingObjs.dibber) == 0) {
            mes("You need a seed dibber to plant seeds.")
            return
        }
        val seed = FarmingObjs.byName.getValue(crop.seed)
        if (invTotal(inv, seed) < crop.seedsPer) {
            mes("You need ${crop.seedsPer} seeds to plant a ${patch.kind.noun}.")
            return
        }
        invDel(inv, seed, crop.seedsPer)
        anim(FarmingSeqs.dibbing)
        state.crop = crop
        state.watered = false
        state.lives = if (crop.fixedYield > 0) 1 else 3 + state.compost
        player.save(patch, state, planted = nowMinutes())
        statAdvance(stats.farming, crop.plantXp * xpMods.get(player, stats.farming))
        val seeds =
            if (crop.seedsPer > 1) "${crop.seedsPer} ${crop.key} seeds" else "a ${crop.key} seed"
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
        if (!player.isGrown(patch, crop)) {
            mes("The ${crop.key} isn't ready to harvest yet.")
            return
        }
        if (patch.kind == Kind.Allotment && invTotal(inv, FarmingObjs.spade) == 0) {
            mes("You need a spade to harvest your crops.")
            return
        }
        val produce = FarmingObjs.byName.getValue(crop.produce)
        if (inv.isFull()) {
            mes("You don't have enough room in your inventory.")
            return
        }
        if (crop.fixedYield > 0) {
            anim(FarmingSeqs.pick)
            invAdd(inv, produce, crop.fixedYield)
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
            invAdd(inv, produce)
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
        mes("The ${crop.key} in this ${patch.kind.noun} $status (treated with $compost).")
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
        val PATCH_OPS = setOf("rake", "harvest", "pick", "clear", "inspect")
        const val REFRESH_TICKS = 8
        const val RAKE_TICKS = 4
        const val HARVEST_TICKS = 3
    }
}
