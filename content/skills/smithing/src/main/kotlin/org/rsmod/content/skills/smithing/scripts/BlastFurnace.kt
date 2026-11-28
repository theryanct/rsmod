package org.rsmod.content.skills.smithing.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseSmithingLvl
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.player.ui.SkillMultiProduct
import org.rsmod.api.player.ui.SkillMultiVerb
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.varp.VarpType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Blast Furnace, Keldagrim (OSRS Wiki), played solo with the coffer: while the coffer holds coins
 * the dwarves run the belt, pump and stove (12 gp per tick drained while in the basement), so
 * Put-ore-on sends the inventory's ore and coal into the pot, which melts it straight into the bar
 * dispenser using half the usual coal. Bars come out hot: cool them with a bucket of water or take
 * them with ice gloves. Smithing below 60 must pay the foreman 2,500 gp per 10 minutes.
 *
 * Contents and coffer are saved in custom varps (the cache's blast_furnace_* varps are not saved)
 * and mirrored to the cache varbits, which drive the HUD and the dispenser/coffer multilocs.
 * Not yet: manual pedal/pump/stoke without coffer, breakdowns, coal bag, perfect gold, lead/nickel.
 */
class BlastFurnace
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    /** One store slot: an ore (or coal) in the pot or a bar in the dispenser. */
    private class Slot(val key: String, val obj: ObjType, val varp: VarpType, val mirror: VarBitType)

    private class Bar(
        val bar: String,
        val primary: String,
        val secondary: String?,
        val coal: Int,
        val level: Int,
        val xp: Double,
    )

    /** Wiki: half the furnace's coal; xp as a furnace (gold 56.2 with goldsmith gauntlets). Melt order. */
    private val bars =
        listOf(
            Bar("runite", "runite_ore", null, 4, 85, 50.0),
            Bar("adamantite", "adamantite_ore", null, 3, 70, 37.5),
            Bar("mithril", "mithril_ore", null, 2, 50, 30.0),
            Bar("steel", "iron_ore", null, 1, 30, 17.5),
            Bar("gold", "gold_ore", null, 0, 40, 22.5),
            Bar("silver", "silver_ore", null, 0, 20, 13.7),
            Bar("bronze", "copper_ore", "tin_ore", 0, 1, 6.2),
        )

    private val ores by lazy { BfData.ORES.map { slot(it, ore = true) } }
    private val barSlots by lazy { BfData.BARS.map { slot(it, ore = false) } }

    private fun slot(name: String, ore: Boolean): Slot {
        val obj = BfObjs.byName.getValue(if (ore) name else "${name}_bar")
        val key = if (ore) name else "${name}_bars"
        return Slot(key, obj, BfVarps.byName.getValue("bf_$key"), BfVarBits.byName.getValue("blast_furnace_$key"))
    }

    private fun ore(name: String): Slot = ores.first { it.key == name }

    private fun barSlot(name: String): Slot = barSlots.first { it.key == "${name}_bars" }

    override fun ScriptContext.startup() {
        bindLoc(BfLocs.keldagrim_stairs, "Climb-down") { enter() }
        bindLoc(BfLocs.stairs_up, "Climb-up") { exit() }
        bindLoc(BfLocs.belt, "Put-ore-on") { putOre() }
        bindLoc(BfLocs.pot, "Check") { checkPot() }
        bindLoc(BfLocs.sink, "Fill-bucket") { fillBucket() }
        for (dispenser in listOf(BfLocs.dispenser_full, BfLocs.dispenser_cooled)) {
            bindLoc(dispenser, "Take") { takeBars() }
        }
        for (dispenser in listOf(BfLocs.dispenser_empty, BfLocs.dispenser_full, BfLocs.dispenser_cooled)) {
            bindLoc(dispenser, "Check") { checkDispenser() }
        }
        onOpLocU(BfLocs.dispenser, BfObjs.bucket_water) { coolBars() }
        for (coffer in listOf(BfLocs.coffer_empty, BfLocs.coffer_full)) bindLoc(coffer, "Use") { useCoffer() }
        bindNpc(BfNpcs.foreman, "Talk-to") { talkForeman(it) }
        bindNpc(BfNpcs.foreman, "Pay") { payForeman(it) }
        onPlayerSoftTimer(BfTimers.tick) { tick(player) }
        onPlayerLogin {
            // Bars left in the dispenser have cooled by the next login.
            sync(player, cooled = true)
            if (inBasement(player)) player.softTimer(BfTimers.tick, TICK_INTERVAL)
        }
    }

    private fun locOp(type: LocType, label: String): Int =
        locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1

    private fun ScriptContext.bindLoc(type: LocType, label: String, action: suspend ProtectedAccess.() -> Unit) {
        val op = locOp(type, label)
        if (op <= 0 || isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action() }
            2 -> onOpLoc2(type) { action() }
            3 -> onOpLoc3(type) { action() }
        }
    }

    private fun ScriptContext.bindNpc(type: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op <= 0 || isNpcOpBound(type, op)) return
        when (op) {
            1 -> onOpNpc1(type) { action(it.npc) }
            3 -> onOpNpc3(type) { action(it.npc) }
        }
    }

    /* State */

    private fun Player.get(slot: Slot): Int = vars[slot.varp]

    private fun Player.put(slot: Slot, value: Int) {
        VarPlayerIntMapSetter.set(this, slot.varp, value)
        VarPlayerIntMapSetter.set(this, slot.mirror, value.coerceIn(0, 255))
    }

    private fun Player.coffer(): Int = vars[BfVarps.coffer]

    private fun Player.setCoffer(value: Int) {
        val gp = value.coerceAtLeast(0)
        VarPlayerIntMapSetter.set(this, BfVarps.coffer, gp)
        VarPlayerIntMapSetter.set(this, BfVarBits.coffer, gp)
        VarPlayerIntMapSetter.set(this, BfVarBits.coins_in_coffer, if (gp > 0) 1 else 0)
    }

    private fun Player.barsTotal(): Int = barSlots.sumOf { get(it) }

    private fun Player.setDispenser(state: Int) = VarPlayerIntMapSetter.set(this, BfVarBits.bars_hot, state)

    private fun sync(player: Player, cooled: Boolean = false) {
        for (slot in ores + barSlots) player.put(slot, player.get(slot))
        player.setCoffer(player.coffer())
        if (cooled) player.setDispenser(if (player.barsTotal() > 0) DISPENSER_COOLED else DISPENSER_EMPTY)
    }

    private fun inBasement(player: Player): Boolean {
        val c = player.coords
        return c.x in BASEMENT_X && c.z in BASEMENT_Z
    }

    /* Access */

    private fun ProtectedAccess.enter() {
        telejump(BASEMENT_ARRIVAL)
        ifOpenOverlay(BfInterfaces.hud)
        sync(player)
        player.softTimer(BfTimers.tick, TICK_INTERVAL)
    }

    private fun ProtectedAccess.exit() {
        ifCloseSub(BfInterfaces.hud)
        player.clearSoftTimer(BfTimers.tick)
        telejump(KELDAGRIM_ARRIVAL)
    }

    /* Coffer: 12 gp per tick while in the basement (Wiki); bars cool on their own after a while. */

    private val cooling = HashMap<Int, Int>()

    private fun tick(player: Player) {
        if (!inBasement(player)) {
            player.clearSoftTimer(BfTimers.tick)
            return
        }
        val gp = player.coffer()
        if (gp > 0) {
            val left = (gp - COFFER_PER_TICK * TICK_INTERVAL).coerceAtLeast(0)
            player.setCoffer(left)
            if (left == 0) player.mes("The coffer is empty. The dwarves have stopped working.")
        }
        val key = player.uid.packed
        val hot = cooling[key] ?: return
        if (hot - TICK_INTERVAL <= 0) {
            cooling.remove(key)
            if (player.vars[BfVarBits.bars_hot] == DISPENSER_HOT) player.setDispenser(DISPENSER_COOLED)
        } else {
            cooling[key] = hot - TICK_INTERVAL
        }
    }

    private suspend fun ProtectedAccess.useCoffer() {
        val gp = player.coffer()
        var choice = 0
        startDialogue {
            choice =
                choice3(
                    "Deposit coins.",
                    1,
                    "Withdraw coins.",
                    2,
                    "Cancel.",
                    0,
                )
        }
        when (choice) {
            1 -> {
                val carried = invTotal(inv, BfObjs.coins)
                if (carried <= 0) {
                    mes("You don't have any coins to deposit.")
                    return
                }
                val amount = minOf(countDialog("Deposit how much? (The coffer holds ${"%,d".format(gp)} gp.)"), carried)
                if (amount <= 0) return
                invDel(inv, BfObjs.coins, amount)
                player.setCoffer(gp + amount)
                mes("You deposit ${"%,d".format(amount)} coins into the coffer.")
            }
            2 -> {
                if (gp <= 0) {
                    mes("The coffer is empty.")
                    return
                }
                val amount = minOf(countDialog("Withdraw how much? (The coffer holds ${"%,d".format(gp)} gp.)"), gp)
                if (amount <= 0) return
                player.setCoffer(gp - amount)
                invAdd(inv, BfObjs.coins, amount)
                mes("You withdraw ${"%,d".format(amount)} coins from the coffer.")
            }
        }
    }

    /* Foreman fee (Smithing < 60): 2,500 gp per 10 minutes, kept server-side. */

    private val paidUntil = HashMap<Int, Long>()

    private fun ProtectedAccess.mayUseFurnace(): Boolean {
        if (player.baseSmithingLvl >= FREE_LEVEL) return true
        val until = paidUntil[player.uid.packed] ?: 0L
        return System.currentTimeMillis() < until
    }

    private suspend fun ProtectedAccess.talkForeman(npc: Npc) {
        // Authored (not Wiki): short foreman lines.
        startDialogue(npc) {
            if (player.baseSmithingLvl >= FREE_LEVEL) {
                chatNpc(neutral, "You know your way around a forge. Use the furnace as you like.")
            } else {
                chatNpc(neutral, "Below level $FREE_LEVEL Smithing you pay me ${"%,d".format(FEE)} coins for ten minutes on the furnace.")
            }
        }
    }

    private suspend fun ProtectedAccess.payForeman(npc: Npc) {
        if (player.baseSmithingLvl >= FREE_LEVEL) {
            startDialogue(npc) { chatNpc(neutral, "You don't need to pay me; you're skilled enough already.") }
            return
        }
        if (invTotal(inv, BfObjs.coins) < FEE) {
            startDialogue(npc) { chatNpc(neutral, "That's ${"%,d".format(FEE)} coins, and you don't have it.") }
            return
        }
        invDel(inv, BfObjs.coins, FEE)
        val now = System.currentTimeMillis()
        val until = maxOf(now, paidUntil[player.uid.packed] ?: 0L) + FEE_MILLIS
        paidUntil[player.uid.packed] = until
        mes("You pay the foreman ${"%,d".format(FEE)} coins. You may use the furnace for ten minutes.")
    }

    /* Belt, pot, dispenser */

    private fun ProtectedAccess.putOre() {
        if (!mayUseFurnace()) {
            mes("You need to pay the foreman before you can use the furnace.")
            return
        }
        if (player.coffer() <= 0) {
            mes("The conveyor belt isn't moving. Put some coins in the coffer to pay the dwarves.")
            return
        }
        var moved = 0
        for (slot in ores) {
            val carried = invTotal(inv, slot.obj)
            if (carried <= 0) continue
            val cap = if (slot.key == "coal" || slot.key == "tin_ore") STACK_CAP else ORE_CAP
            val room = cap - player.get(slot)
            val amount = minOf(carried, room)
            if (amount <= 0) continue
            invDel(inv, slot.obj, amount)
            player.put(slot, player.get(slot) + amount)
            moved += amount
        }
        if (moved == 0) {
            mes("You don't have any ore the furnace can use, or the pot is full.")
            return
        }
        anim(BfSeqs.put_ore)
        mes("You put the ore on the conveyor belt.")
        melt()
    }

    private fun ProtectedAccess.melt() {
        val gauntlets = player.worn[Wearpos.Hands.slot]?.id == objTypes[BfObjs.goldsmith_gauntlets].id
        var made = 0
        for (bar in bars) {
            val primary = ore(bar.primary)
            val secondary = bar.secondary?.let { ore(it) }
            val target = barSlot(bar.bar)
            val ironOnly = bar.bar == "steel" && player.smithingLvl < bar.level
            if (player.smithingLvl < (if (bar.bar == "steel") IRON_LEVEL else bar.level)) continue
            while (player.get(primary) > 0) {
                // Wiki: iron with no coal in the pot (or below level 30) comes out as iron bars.
                val asIron = bar.bar == "steel" && (ironOnly || player.get(ore("coal")) < bar.coal)
                if (!asIron && player.get(ore("coal")) < bar.coal) break
                if (secondary != null && player.get(secondary) <= 0) break
                val out = if (asIron) barSlot("iron") else target
                if (player.get(out) >= BAR_CAP) break
                player.put(primary, player.get(primary) - 1)
                secondary?.let { player.put(it, player.get(it) - 1) }
                if (!asIron && bar.coal > 0) player.put(ore("coal"), player.get(ore("coal")) - bar.coal)
                player.put(out, player.get(out) + 1)
                val xp =
                    when {
                        asIron -> IRON_XP
                        bar.bar == "gold" && gauntlets -> GOLD_GAUNTLETS_XP
                        else -> bar.xp
                    }
                statAdvance(stats.smithing, xp * xpMods.get(player, stats.smithing))
                made++
            }
        }
        if (made > 0) {
            player.setDispenser(DISPENSER_HOT)
            cooling[player.uid.packed] = COOL_TICKS
        }
    }

    private fun ProtectedAccess.checkPot() {
        val parts = ores.filter { player.get(it) > 0 }.map { "${player.get(it)} ${objTypes[it.obj].name.lowercase()}" }
        mes(if (parts.isEmpty()) "The melting pot is empty." else "The melting pot holds: ${parts.joinToString(", ")}.")
    }

    private fun ProtectedAccess.checkDispenser() {
        val parts = barSlots.filter { player.get(it) > 0 }.map { "${player.get(it)} ${objTypes[it.obj].name.lowercase()}" }
        mes(if (parts.isEmpty()) "The bar dispenser is empty." else "The bar dispenser holds: ${parts.joinToString(", ")}.")
    }

    private fun ProtectedAccess.fillBucket() {
        if (BfObjs.bucket_empty !in inv) {
            mes("You need an empty bucket.")
            return
        }
        invDel(inv, BfObjs.bucket_empty)
        invAdd(inv, BfObjs.bucket_water)
        mes("You fill the bucket from the sink.")
    }

    private fun ProtectedAccess.coolBars() {
        if (player.vars[BfVarBits.bars_hot] != DISPENSER_HOT) {
            mes("The bars don't need cooling.")
            return
        }
        invDel(inv, BfObjs.bucket_water)
        invAdd(inv, BfObjs.bucket_empty)
        player.setDispenser(DISPENSER_COOLED)
        cooling.remove(player.uid.packed)
        mes("You pour water over the bars, cooling them.")
    }

    private fun ProtectedAccess.wearsIceGloves(): Boolean {
        val hands = player.worn[Wearpos.Hands.slot] ?: return false
        return BfObjs.ice_gloves_all.any { objTypes[it].id == hands.id }
    }

    private suspend fun ProtectedAccess.takeBars() {
        val stocked = barSlots.filter { player.get(it) > 0 }
        if (stocked.isEmpty()) {
            mes("The bar dispenser is empty.")
            return
        }
        if (player.vars[BfVarBits.bars_hot] == DISPENSER_HOT && !wearsIceGloves()) {
            mes("The bars are still molten! You need to cool them down or wear ice gloves.")
            return
        }
        val products = stocked.map { SkillMultiProduct(it.obj, objTypes[it.obj].name, available = true) }
        val choice =
            skillMulti(
                verb = SkillMultiVerb.MAKE,
                title = "What would you like to take?",
                products = products,
                maxQuantity = stocked.maxOf { player.get(it) }.coerceIn(1, 28),
            )
        val slot = stocked[choice.index]
        val amount = minOf(choice.quantity, player.get(slot), inv.freeSpace())
        if (amount <= 0) {
            mes("You don't have enough inventory space.")
            return
        }
        player.put(slot, player.get(slot) - amount)
        invAdd(inv, slot.obj, amount)
        mes("You take $amount ${objTypes[slot.obj].name.lowercase()}${if (amount == 1) "" else "s"} from the dispenser.")
        if (player.barsTotal() == 0) player.setDispenser(DISPENSER_EMPTY)
    }

    private companion object {
        val BASEMENT_X = 1920..1983
        val BASEMENT_Z = 4928..4991
        // Not in the cache: stair landing tiles (check in-game).
        val BASEMENT_ARRIVAL = CoordGrid(1940, 4958, 0)
        val KELDAGRIM_ARRIVAL = CoordGrid(2930, 10198, 0)

        const val TICK_INTERVAL = 10
        const val COFFER_PER_TICK = 12
        const val FREE_LEVEL = 60
        const val FEE = 2500
        const val FEE_MILLIS = 10 * 60 * 1000L
        const val ORE_CAP = 28
        const val STACK_CAP = 254
        const val BAR_CAP = 28
        const val IRON_XP = 12.5
        const val IRON_LEVEL = 15
        const val GOLD_GAUNTLETS_XP = 56.2

        // Authored: hot bars cool by themselves after 60 ticks.
        const val COOL_TICKS = 60
        const val DISPENSER_EMPTY = 0
        const val DISPENSER_HOT = 2
        const val DISPENSER_COOLED = 3
    }
}

internal object BfData {
    val ORES =
        listOf("coal", "tin_ore", "copper_ore", "iron_ore", "mithril_ore", "adamantite_ore", "runite_ore", "gold_ore", "silver_ore")
    val BARS = listOf("bronze", "iron", "steel", "mithril", "adamantite", "runite", "gold", "silver")
    val STORE_KEYS: List<String> = ORES + BARS.map { "${it}_bars" }
}

internal object BfLocs : LocReferences() {
    val keldagrim_stairs = find("dwarf_keldagrim_factory_stairs")
    val stairs_up = find("blast_furnace_stairs_up")
    val belt = find("blast_furnace_conveyer_belt_clickable")
    val pot = find("blast_furnace_chimney")
    val sink = find("blast_furnace_sink")
    val dispenser = find("blast_furnace_dispenser")
    val dispenser_empty = find("blast_furnace_ore_dispenser_empty")
    val dispenser_full = find("blast_furnace_ore_dispenser_full")
    val dispenser_cooled = find("blast_furnace_ore_dispenser_cooled")
    val coffer_empty = find("blast_furnace_automata_coffer_empty")
    val coffer_full = find("blast_furnace_automata_coffer_full")
}

internal object BfNpcs : NpcReferences() {
    val foreman = find("blast_furnace_instructor")
}

internal object BfObjs : ObjReferences() {
    val coins = find("coins")
    val bucket_empty = find("bucket_empty")
    val bucket_water = find("bucket_water")
    val goldsmith_gauntlets = find("gauntlets_of_goldsmithing")
    val ice_gloves_all = listOf(find("ice_gloves"), find("smithing_uniform_gloves_ice"))
    val byName: Map<String, ObjType> =
        (BfData.ORES + BfData.BARS.map { "${it}_bar" }).associateWith { find(it) }
}

internal object BfSeqs : org.rsmod.api.type.refs.seq.SeqReferences() {
    val put_ore = find("human_pickuptable")
}

internal object BfInterfaces : InterfaceReferences() {
    val hud = find("blast_furnace_hud")
}

internal object BfVarBits : VarBitReferences() {
    val bars_hot = find("blast_furnace_bars_hot")
    val coffer = find("blast_furnace_coffer")
    val coins_in_coffer = find("blast_furnace_coinsincoffer")
    val byName: Map<String, VarBitType> =
        BfData.STORE_KEYS.map { "blast_furnace_$it" }.associateWith { find(it) }
}

/** Skilling symbol range (65541-65558). Saved per character. */
object BfVarpBuilds : VarpBuilder() {
    init {
        build("bf_coffer")
        for (key in BfData.STORE_KEYS) build("bf_$key")
    }
}

internal object BfVarps : VarpReferences() {
    val coffer = find("bf_coffer")
    val byName: Map<String, VarpType> = BfData.STORE_KEYS.map { "bf_$it" }.associateWith { find(it) }
}

internal object BfTimers : TimerReferences() {
    /** Custom timer (65505, skilling range). */
    val tick = find("blast_furnace_tick")
}

/** Foreman and the coffer dwarves (Wiki pins; nobody else spawns them). */
object BfNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<BlastFurnace>("blast_furnace_npcs.toml")
    }
}
