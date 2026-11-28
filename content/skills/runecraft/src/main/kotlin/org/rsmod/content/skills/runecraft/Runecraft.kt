package org.rsmod.content.skills.runecraft

import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag
import org.rsmod.api.player.front
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.hat
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.player.stat.runecraftingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.advanced.onWearposChange
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
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
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.mining.scripts.Mining
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeAnim
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeTicks
import org.rsmod.content.skills.runecraft.RunecraftData.Altar
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Runecraft (OSRS Wiki data): the six F2P altars (PR1) plus cosmic, chaos, nature, law, death and
 * wrath through their ruins, the astral walk-up altar (pure essence only, PR2), and combination
 * runes (mist/dust/mud/smoke/steam/lava: an element talisman on another element's altar). Blood and
 * soul (Morytania / Arceuus dark essence) and the Abyss are not included.
 * - Mysterious ruins: use the talisman on them, or "Enter" while wearing the matching tiara (the
 *   ruins are a multiloc on `rc_no_tally_required_<rune>`, which is kept in sync with the hat).
 * - Altars: "Craft-rune" binds all carried rune/pure essence, with extra runes per essence at every
 *   multiple of the rune's multiple level. Using a tiara (or talisman) on the altar with both
 *   carried binds a talisman tiara.
 * - Exit portals return to the ruins; the talisman's "Locate" points at its ruins.
 * - Rune essence mine: Aubury teleports there (Talk-to option, or "Teleport" on his 3-op variant);
 *   the essence rocks are mined until the inventory is full (pure essence at 30 Mining), and the
 *   mine's portals return to his shop. The Rune Mysteries requirement is ignored.
 */
class Runecraft
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    override fun ScriptContext.startup() {
        for (altar in RunecraftData.altars) {
            val talisman = altar.talisman?.let(::obj)
            val ruins = altar.ruins?.let(::loc)
            if (ruins != null && talisman != null) {
                onOpLocU(ruins, talisman) { enterRuins(altar, viaTalisman = true) }
            }
            altar.ruinsEnter?.let { onLocOp(loc(it), "Enter") { enterRuins(altar, viaTalisman = false) } }
            onLocOp(loc(altar.altar), "Craft-rune") { craftRunes(altar) }
            if (talisman != null && altar.tiara != null) {
                onOpLocU(loc(altar.altar), RunecraftObjs.tiara) { bindTiara(altar) }
                onOpLocU(loc(altar.altar), talisman) { bindTiara(altar) }
            }
            altar.portal?.let { onLocOp(loc(it), "Use") { leaveAltar(altar) } }
            if (talisman != null) {
                onHeldOp(talisman, "Locate") { locate(altar) }
            }
        }
        // Combination runes: an element's talisman used on another element's altar.
        val altarsByKey = RunecraftData.altars.associateBy { it.key }
        for (combo in RunecraftData.combos) {
            val altar = altarsByKey.getValue(combo.altar)
            val talisman = obj(altarsByKey.getValue(combo.talisman).talisman!!)
            onOpLocU(loc(altar.altar), talisman) { craftCombo(combo, talisman) }
        }
        onLocOp(RunecraftLocs.essence_rock, "Mine") { mineEssence(it) }
        for (portal in RunecraftLocs.mine_portals) {
            val op = locTypes[portal].op.indexOfFirst { it != null && it != "Examine" } + 1
            if (op > 0) onLocOpIndex(portal, op) { telejump(RunecraftData.auburyShop) }
        }
        onNpcOp(RunecraftNpcs.aubury, "Talk-to") { talkToAubury(it) }
        onNpcOp(RunecraftNpcs.aubury_teleport, "Talk-to") { talkToAubury(it) }
        onNpcOp(RunecraftNpcs.aubury_teleport, "Teleport") { teleportToMine(it) }
        onWearposChange {
            if (wearpos == Wearpos.Hat) {
                syncRuinsVarbits(player)
            }
        }
    }

    private fun loc(name: String): LocType = RunecraftLocs.byName.getValue(name)

    private fun obj(name: String): ObjType = RunecraftObjs.byName.getValue(name)

    /* Ruins and portals */

    /** With the QuestGates toggle on, Runecraft needs Rune Mysteries (cache end state 6). */
    private fun ProtectedAccess.passesQuestGate(): Boolean {
        if (!player.isFeatureOn(Feature.QuestGates)) return true
        if (player.vars[RunecraftVarps.runemysteries] >= RUNE_MYSTERIES_COMPLETE) return true
        mes("You need to complete Rune Mysteries to do that.")
        return false
    }

    private suspend fun ProtectedAccess.enterRuins(altar: Altar, viaTalisman: Boolean) {
        if (!passesQuestGate()) return
        if (viaTalisman) {
            mes("You hold the ${altar.key} talisman towards the mysterious ruins.")
        }
        mes("You feel a powerful force take hold of you...")
        delay(1)
        telejump(arrival(altar, altar.inside))
    }

    private fun ProtectedAccess.leaveAltar(altar: Altar) {
        telejump(arrival(altar, altar.outside))
    }

    /** PR2 altars use Wiki map pins (the altar / ruins tile): step onto the nearest free tile. */
    private fun arrival(altar: Altar, pin: CoordGrid): CoordGrid {
        if (!altar.pinned) {
            return pin
        }
        for (r in 1..ARRIVAL_RADIUS) {
            for (dz in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dz)) != r) continue
                    val tile = pin.translate(dx, dz)
                    if ((collision[tile.x, tile.z, tile.level] and BLOCKED) == 0) {
                        return tile
                    }
                }
            }
        }
        return pin
    }

    private fun ProtectedAccess.locate(altar: Altar) {
        val dx = altar.outside.x - player.coords.x
        val dz = altar.outside.z - player.coords.z
        val ns = if (abs(dz) * 2 >= abs(dx)) (if (dz > 0) "north" else "south") else ""
        val ew = if (abs(dx) * 2 >= abs(dz)) (if (dx > 0) "east" else "west") else ""
        val direction = listOf(ns, ew).filter { it.isNotEmpty() }.joinToString("-")
        mes("The talisman pulls toward the $direction.")
    }

    /** Wearing a talisman tiara shows the "Enter" variant of its ruins (OSRS behaviour). */
    private fun syncRuinsVarbits(player: Player) {
        val hat = player.hat?.id
        for (altar in RunecraftData.altars) {
            val varbit = RunecraftVarBits.byName[altar.varbit] ?: continue
            val tiara = altar.tiara ?: continue
            val value = if (hat == obj(tiara).id) 1 else 0
            if (player.vars[varbit] != value) {
                VarPlayerIntMapSetter.set(player, varbit, value)
            }
        }
    }

    /* Altars */

    private fun ProtectedAccess.craftRunes(altar: Altar) {
        if (player.runecraftingLvl < altar.level) {
            mes("You need a Runecraft level of ${altar.level} to infuse these runes.")
            return
        }
        // Members runes take pure essence only; rune essence stays in the inventory.
        val rune = if (altar.pureOnly) 0 else invTotal(inv, RunecraftObjs.rune_essence)
        val pure = invTotal(inv, RunecraftObjs.pure_essence)
        val essence = rune + pure
        if (essence == 0) {
            if (altar.pureOnly && invTotal(inv, RunecraftObjs.rune_essence) > 0) {
                mes("This altar needs pure essence to bind ${altar.key} runes.")
            } else {
                mes("You do not have any rune essence to bind.")
            }
            return
        }
        if (rune > 0) invDel(inv, RunecraftObjs.rune_essence, rune)
        if (pure > 0) invDel(inv, RunecraftObjs.pure_essence, pure)
        val perEssence = 1 + player.runecraftingLvl / altar.multipleEvery
        anim(RunecraftSeqs.bind)
        spotanim(RunecraftSpotanims.bind, height = 92)
        invAdd(inv, obj(altar.rune), essence * perEssence)
        statAdvance(stats.runecrafting, essence * altar.xp * xpMods.get(player, stats.runecrafting))
        mes("You bind the temple's power into ${altar.key} runes.")
    }

    /**
     * Combination runes: pure essence + one of the talisman's element runes each; the talisman is
     * used up. Without a binding necklace worn, each bind has a 50% chance to fail.
     */
    private fun ProtectedAccess.craftCombo(combo: RunecraftData.Combo, talisman: ObjType) {
        if (player.runecraftingLvl < combo.level) {
            mes("You need a Runecraft level of ${combo.level} to combine these runes.")
            return
        }
        val runeObj = obj(combo.rune)
        val count = minOf(invTotal(inv, RunecraftObjs.pure_essence), invTotal(inv, runeObj))
        if (count == 0) {
            mes("You need pure essence and ${objTypes[runeObj].name.lowercase()}s to combine runes here.")
            return
        }
        val necklace = player.front?.id == objTypes[RunecraftObjs.binding_necklace].id
        val made = if (necklace) count else (0 until count).count { random.of(0, 1) == 1 }
        invDel(inv, RunecraftObjs.pure_essence, count, runeObj, count)
        invDel(inv, talisman, 1)
        anim(RunecraftSeqs.bind)
        spotanim(RunecraftSpotanims.bind, height = 92)
        if (made > 0) {
            invAdd(inv, obj(combo.product), made)
            statAdvance(stats.runecrafting, made * combo.xp * xpMods.get(player, stats.runecrafting))
        }
        val name = objTypes[obj(combo.product)].name.lowercase()
        mes(if (made == count) "You bind the temple's power into ${name}s." else "You partially succeed to bind the temple's power into ${name}s.")
    }

    private fun ProtectedAccess.bindTiara(altar: Altar) {
        val talisman = obj(altar.talisman!!)
        if (invTotal(inv, RunecraftObjs.tiara) == 0 || invTotal(inv, talisman) == 0) {
            mes("You need a tiara and ${altar.key} talisman to do that.")
            return
        }
        invDel(inv, RunecraftObjs.tiara, 1, talisman, 1)
        invAdd(inv, obj(altar.tiara!!))
        anim(RunecraftSeqs.bind)
        spotanim(RunecraftSpotanims.bind, height = 92)
        statAdvance(
            stats.runecrafting,
            altar.tiaraXp * xpMods.get(player, stats.runecrafting),
        )
        mes("You bind the power of the talisman into your tiara.")
    }

    /* Rune essence mine */

    private suspend fun ProtectedAccess.talkToAubury(npc: Npc) {
        var choice = 0
        startDialogue(npc) {
            chatNpc(happy, "Do you want to buy some runes?")
            choice =
                choice2(
                    "Can you teleport me to the Rune Essence?",
                    1,
                    "No thanks.",
                    2,
                )
        }
        if (choice == 1) {
            teleportToMine(npc)
        }
    }

    private suspend fun ProtectedAccess.teleportToMine(npc: Npc) {
        if (!passesQuestGate()) return
        npc.say("Senventior Disthine Molenko!")
        delay(2)
        telejump(RunecraftData.essenceMine)
    }

    private fun ProtectedAccess.mineEssence(rock: BoundLocInfo) {
        val pickaxe = Mining.findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine this rock.")
            mes("You do not have a pickaxe which you have the Mining level to use.")
            return
        }
        if (inv.isFull()) {
            mes("Your inventory is too full to hold any more essence.")
            return
        }
        val pickaxeType = objTypes[pickaxe]
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(pickaxeType.pickaxeAnim)
        }
        if (actionDelay < mapClock) {
            actionDelay = mapClock + pickaxeType.pickaxeTicks
            spam("You swing your pick at the rock.")
        } else if (actionDelay == mapClock) {
            // OSRS: essence never depletes; one essence per pickaxe interval until full.
            val essence =
                if (player.miningLvl >= RunecraftData.PURE_ESSENCE_MINING) {
                    RunecraftObjs.pure_essence
                } else {
                    RunecraftObjs.rune_essence
                }
            // Never noted: runecrafting consumes essence unnoted.
            invAdd(inv, essence)
            statAdvance(
                stats.mining,
                RunecraftData.ESSENCE_XP * xpMods.get(player, stats.mining),
            )
            actionDelay = mapClock + pickaxeType.pickaxeTicks
        }
        when (mineOp) {
            1 -> opLoc1(rock)
            2 -> opLoc2(rock)
            3 -> opLoc3(rock)
            4 -> opLoc4(rock)
        }
    }

    private val mineOp by lazy {
        locTypes[RunecraftLocs.essence_rock].op.indexOfFirst { it == "Mine" } + 1
    }

    /* Op registration by label (op indices come from the cache types). */

    private fun ScriptContext.onLocOp(
        type: LocType,
        label: String,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        // Fall back to the first non-Examine op so a renamed label never breaks startup.
        val ops = locTypes[type].op
        val labelled = ops.indexOfFirst { it.equals(label, ignoreCase = true) }
        val op = if (labelled >= 0) labelled else ops.indexOfFirst { it != null && it != "Examine" }
        onLocOpIndex(type, op + 1, action)
    }

    private fun ScriptContext.onLocOpIndex(
        type: LocType,
        op: Int,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ) {
        when (op) {
            1 -> onOpLoc1(type) { this.action(it.loc) }
            2 -> onOpLoc2(type) { this.action(it.loc) }
            3 -> onOpLoc3(type) { this.action(it.loc) }
            4 -> onOpLoc4(type) { this.action(it.loc) }
            5 -> onOpLoc5(type) { this.action(it.loc) }
        }
    }

    private fun ScriptContext.onNpcOp(
        type: NpcType,
        label: String,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        when (npcTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> onOpNpc1(type) { this.action(it.npc) }
            2 -> onOpNpc2(type) { this.action(it.npc) }
            3 -> onOpNpc3(type) { this.action(it.npc) }
            4 -> onOpNpc4(type) { this.action(it.npc) }
            5 -> onOpNpc5(type) { this.action(it.npc) }
        }
    }

    private fun ScriptContext.onHeldOp(
        type: ObjType,
        label: String,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        when (objTypes[type].iop.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> onOpHeld1(type) { this.action() }
            2 -> onOpHeld2(type) { this.action() }
            3 -> onOpHeld3(type) { this.action() }
            4 -> onOpHeld4(type) { this.action() }
            5 -> onOpHeld5(type) { this.action() }
        }
    }
}

private const val RUNE_MYSTERIES_COMPLETE = 6
private const val ARRIVAL_RADIUS = 6
private const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC
