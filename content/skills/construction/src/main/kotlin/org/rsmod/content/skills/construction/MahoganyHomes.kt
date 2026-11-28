package org.rsmod.content.skills.construction

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpHeldLabel
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.skills.construction.MahoganyHomesData.House
import org.rsmod.content.skills.construction.MahoganyHomesData.Spot
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mahogany Homes (OSRS Wiki "Mahogany Homes", "Mahogany Homes/Mechanics", "Amy"): Amy in Falador
 * (and, after the first contract, Marlo, Ellie and Angelo) hands out Beginner (1, planks), Novice
 * (20, oak), Adept (50, teak) or Expert (70, mahogany) contracts for one of 12 client houses in
 * Falador, Varrock, East Ardougne and Hosidius. Each contract is one of the Wiki's fixed layouts
 * for that client: broken furniture is repaired (one tier plank, or a steel bar for a range, sink
 * or bath) and old furniture is removed and rebuilt with tier planks (hammer and saw needed).
 * Talking to the client afterwards pays carpenter points (2/3/4/5) and the completion xp.
 *
 * The hotspots are the cache's con_contract_* multilocs, driven by varbits 10554-10561 of varp
 * construction_contract_building_display (0 normal, 1 broken, 2 repaired, 3 remove, 4 empty,
 * 5-8 built regular/oak/teak/mahogany). All houses share those varbits, so the state is saved in
 * custom varps and mirrored, and ops on a house other than the contracted one are refused.
 *
 * Amy's Rewards is a dialogue menu paid in points: carpenter's outfit (+2.5% Construction xp in
 * these contracts with the full set), plank sack (Fill/Empty/Check; builds draw from it), Amy's
 * saw, supply crate (one Wiki roll of noted supplies). Not yet: Hosidius blueprints, Astral
 * Contact, cup of tea, the build menu interface, outfit bonus outside Mahogany Homes.
 */
class MahoganyHomes
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private class Tier(
        val name: String,
        val level: Int,
        val plank: ObjType,
        val buildXp: Double,
        val repairXp: Double,
        val completionXp: Double,
        val points: Int,
    )

    /** Wiki "Mahogany Homes" experience and rewards tables. */
    private val tiers by lazy {
        listOf(
            Tier("Beginner", 1, MhObjs.plank, 22.5, 127.5, 500.0, 2),
            Tier("Novice", 20, MhObjs.plank_oak, 48.0, 160.0, 1250.0, 3),
            Tier("Adept", 50, MhObjs.plank_teak, 72.0, 190.0, 2250.0, 4),
            Tier("Expert", 70, MhObjs.plank_mahogany, 112.0, 240.0, 2750.0, 5),
        )
    }

    private class Ware(val name: String, val obj: ObjType, val points: Int)

    /** Wiki "Mahogany Homes Reward Shop" (Hosidius blueprints not yet). */
    private val wares by lazy {
        listOf(
            Ware("Carpenter's helmet", MhObjs.carpenter_helmet, 400),
            Ware("Carpenter's shirt", MhObjs.carpenter_shirt, 800),
            Ware("Carpenter's trousers", MhObjs.carpenter_trousers, 600),
            Ware("Carpenter's boots", MhObjs.carpenter_boots, 200),
            Ware("Plank sack", MhObjs.plank_sack, 350),
            Ware("Amy's saw", MhObjs.amys_saw, 500),
            Ware("Supply crate", MhObjs.supply_crate, 25),
        )
    }

    private class Loot(val obj: ObjType, val min: Int, val max: Int, val weight: Int)

    /** Wiki "Supply crate (Mahogany Homes)": one roll out of 144, all noted. */
    private val crate by lazy {
        listOf(
            Loot(MhObjs.cert_plank_oak, 28, 28, 10),
            Loot(MhObjs.cert_plank_oak, 29, 29, 15),
            Loot(MhObjs.cert_plank_oak, 30, 30, 5),
            Loot(MhObjs.cert_plank_teak, 15, 15, 20),
            Loot(MhObjs.cert_plank_teak, 16, 16, 4),
            Loot(MhObjs.cert_plank_mahogany, 6, 6, 15),
            Loot(MhObjs.cert_plank_mahogany, 7, 7, 3),
            Loot(MhObjs.cert_soft_clay, 45, 45, 5),
            Loot(MhObjs.cert_soft_clay, 46, 46, 10),
            Loot(MhObjs.cert_soft_clay, 47, 47, 10),
            Loot(MhObjs.cert_soft_clay, 48, 48, 5),
            Loot(MhObjs.cert_steel_bar, 23, 23, 5),
            Loot(MhObjs.cert_steel_bar, 24, 24, 20),
            Loot(MhObjs.cert_steel_bar, 25, 25, 5),
            Loot(MhObjs.cert_bolt_of_cloth, 9, 9, 5),
            Loot(MhObjs.cert_bolt_of_cloth, 10, 10, 1),
            Loot(MhObjs.cert_limestone_brick, 9, 9, 5),
            Loot(MhObjs.cert_limestone_brick, 10, 10, 1),
        )
    }

    private val houses = MahoganyHomesData.houses

    private val sackPlanks by lazy {
        listOf(MhObjs.plank, MhObjs.plank_oak, MhObjs.plank_teak, MhObjs.plank_mahogany)
    }

    override fun ScriptContext.startup() {
        val spots = houses.flatMap { it.spots }
        for (name in spots.map { it.broken }.distinct()) bindLoc(locRef(name), "Repair") { repair(it) }
        val removable = spots.flatMap { listOfNotNull(it.editable) + it.built }.distinct()
        for (name in removable) bindLoc(locRef(name), "Remove") { remove(it) }
        for (name in spots.mapNotNull { it.empty }.distinct()) bindLoc(locRef(name), "Build") { build(it) }

        for (amy in listOf(MhNpcs.amy_1op, MhNpcs.amy_3op)) bindNpc(amy, "Talk-to") { talkAmy(it) }
        bindNpc(MhNpcs.amy_3op, "Contract") { contract(it, last = false) }
        bindNpc(MhNpcs.amy_3op, "Last-tier contract") { contract(it, last = true) }
        bindNpc(MhNpcs.amy_3op, "Rewards") { rewards(it) }
        for ((oneOp, twoOp) in MhNpcs.contractors) {
            // QuestTalk: quests (Daddy's Home on Marlo) can claim the conversation; this is the default.
            for (type in listOf(oneOp, twoOp)) with(QuestTalk) { default(npcTypes, type) { talkContractor(it) } }
            bindNpc(twoOp, "Contract") { contract(it, last = false) }
            bindNpc(twoOp, "Last-tier contract") { contract(it, last = true) }
        }
        for ((index, house) in houses.withIndex()) {
            bindNpc(MhNpcs.byName.getValue(house.client), "Talk-to") { talkClient(it, index) }
        }

        onOpHeldLabel(objTypes, MhObjs.plank_sack, "Fill") { fillSack() }
        onOpHeldLabel(objTypes, MhObjs.plank_sack, "Empty") { emptySack() }
        onOpHeldLabel(objTypes, MhObjs.plank_sack, "Check") { checkSack() }
        val opens = onOpHeldLabel(objTypes, MhObjs.supply_crate, "Open") { openCrate() }
        if (!opens) onOpHeldLabel(objTypes, MhObjs.supply_crate, "Search") { openCrate() }
        onPlayerLogin { sync(player) }
    }

    /* Binding: skip ops another script already owns. */

    private fun locRef(name: String): LocType = MhLocs.byName.getValue(name)

    private fun ScriptContext.bindLoc(type: LocType, label: String, action: suspend ProtectedAccess.(BoundLocInfo) -> Unit) {
        val op = locTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op <= 0 || isLocOpBound(type, op)) return
        when (op) {
            1 -> onOpLoc1(type) { action(it.loc) }
            2 -> onOpLoc2(type) { action(it.loc) }
            3 -> onOpLoc3(type) { action(it.loc) }
            4 -> onOpLoc4(type) { action(it.loc) }
            5 -> onOpLoc5(type) { action(it.loc) }
        }
    }

    private fun ScriptContext.bindNpc(type: NpcType, label: String, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[type].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1
        if (op <= 0 || isNpcOpBound(type, op)) return
        when (op) {
            1 -> onOpNpc1(type) { action(it.npc) }
            2 -> onOpNpc2(type) { action(it.npc) }
            3 -> onOpNpc3(type) { action(it.npc) }
            4 -> onOpNpc4(type) { action(it.npc) }
            5 -> onOpNpc5(type) { action(it.npc) }
        }
    }

    /* State: mh_contract = house+1 (bits 0-3), tier (4-5), last tier+1 (8-10), signed up (12). */

    private fun Player.contractBits(): Int = vars[MhVarps.contract]

    private fun Player.houseIndex(): Int = (contractBits() and 0xF) - 1

    private fun Player.tierIndex(): Int = (contractBits() ushr 4) and 0x3

    private fun Player.lastTier(): Int = ((contractBits() ushr 8) and 0x7) - 1

    private fun Player.signedUp(): Boolean = ((contractBits() ushr 12) and 1) == 1

    private fun Player.setContract(house: Int, tier: Int, last: Int, signed: Boolean) {
        val value = (house + 1) or (tier shl 4) or ((last + 1) shl 8) or ((if (signed) 1 else 0) shl 12)
        VarPlayerIntMapSetter.set(this, MhVarps.contract, value)
        VarPlayerIntMapSetter.set(this, MhVarBits.discussed, if (signed) 1 else 0)
    }

    private fun Player.slotState(slot: Int): Int = (vars[MhVarps.display] ushr (4 * (slot - 1))) and 0xF

    private fun Player.setSlotState(slot: Int, state: Int) {
        val shift = 4 * (slot - 1)
        val value = (vars[MhVarps.display] and (0xF shl shift).inv()) or ((state and 0xF) shl shift)
        setDisplay(value)
    }

    private fun Player.setDisplay(value: Int) {
        VarPlayerIntMapSetter.set(this, MhVarps.display, value)
        VarPlayerIntMapSetter.set(this, MhVarps.building_display, value)
    }

    private fun Player.points(): Int = vars[MhVarps.points]

    private fun Player.setPoints(value: Int) = VarPlayerIntMapSetter.set(this, MhVarps.points, value.coerceAtLeast(0))

    private fun sync(player: Player) {
        player.setDisplay(player.vars[MhVarps.display])
        VarPlayerIntMapSetter.set(player, MhVarBits.discussed, if (player.signedUp()) 1 else 0)
    }

    private fun Player.activeHouse(): House? = houses.getOrNull(houseIndex())

    private fun Player.contractDone(): Boolean {
        val house = activeHouse() ?: return false
        return house.spots.none { slotState(it.slot) in PENDING }
    }

    /* Contractors */

    private suspend fun ProtectedAccess.talkAmy(npc: Npc) {
        if (!player.signedUp()) {
            var join = false
            // Authored (not Wiki transcript): a short sign-up in Amy's voice.
            startDialogue(npc) {
                chatNpc(happy, "Welcome to Mahogany Homes! I'm Amy. We fix up homes all over Falador, Varrock, East Ardougne and Hosidius.")
                chatNpc(neutral, "Bring a hammer, a saw, some planks and a steel bar or two, and I'll pay you in carpenter points as well as experience.")
                join = choice2("I'd like to work for you.", true, "Not right now.", false)
            }
            if (!join) return
            player.setContract(player.houseIndex(), player.tierIndex(), player.lastTier(), signed = true)
            contract(npc, last = false)
            return
        }
        var pick = 0
        startDialogue(npc) {
            chatNpc(happy, "Hello again! What can I do for you?")
            pick =
                choice4(
                    "Could I have a contract?", 1,
                    "Let's see the rewards.", 2,
                    "How many points do I have?", 3,
                    "Nothing, thanks.", 0,
                )
        }
        when (pick) {
            1 -> contract(npc, last = false)
            2 -> rewards(npc)
            3 -> startDialogue(npc) { chatNpc(neutral, "You have ${"%,d".format(player.points())} carpenter points.") }
        }
    }

    private suspend fun ProtectedAccess.talkContractor(npc: Npc) {
        if (!player.signedUp()) {
            startDialogue(npc) {
                chatNpc(neutral, "Looking for work? Speak to Amy at our Falador office first; she'll sign you up.")
            }
            return
        }
        var wants = false
        startDialogue(npc) {
            chatNpc(happy, "Hello! Need another contract?")
            wants = choice2("Yes please.", true, "No thanks.", false)
        }
        if (wants) contract(npc, last = false)
    }

    private suspend fun ProtectedAccess.contract(npc: Npc, last: Boolean) {
        val current = player.activeHouse()
        if (current != null) {
            val tier = tiers[player.tierIndex()]
            val line =
                if (player.contractDone()) {
                    "You've finished the work; go and see ${current.owner} in ${current.town} to get paid."
                } else {
                    "You already have a ${tier.name.lowercase()} contract: ${current.owner} in ${current.town} needs some work done."
                }
            startDialogue(npc) { chatNpc(neutral, line) }
            return
        }
        val tierIndex =
            if (last && player.lastTier() >= 0) player.lastTier() else chooseTier(npc) ?: return
        val tier = tiers[tierIndex]
        if (player.constructionLvl < tier.level) {
            startDialogue(npc) {
                chatNpc(neutral, "You'll need a Construction level of ${tier.level} for ${tier.name.lowercase()} contracts.")
            }
            return
        }
        val houseIndex = random.of(houses.size)
        val house = houses[houseIndex]
        val layouts = house.layouts[tierIndex]
        val mask = layouts[random.of(layouts.size)]
        var display = 0
        for (spot in house.spots) {
            if (((mask ushr (spot.slot - 1)) and 1) == 0) continue
            val state = if (spot.repair) STATE_BROKEN else STATE_REMOVE
            display = display or (state shl (4 * (spot.slot - 1)))
        }
        player.setDisplay(display)
        player.setContract(houseIndex, tierIndex, tierIndex, signed = true)
        startDialogue(npc) {
            chatNpc(neutral, "${house.owner} in ${house.town} needs some work done. Bring ${objTypes[tier.plank].name.lowercase()}s and a steel bar, just in case.")
        }
        mes("New contract: ${house.owner}, ${house.town} (${tier.name}).")
    }

    private suspend fun ProtectedAccess.chooseTier(npc: Npc): Int? {
        var picked = -1
        startDialogue(npc) {
            picked =
                choice5(
                    "Beginner (level 1)", 0,
                    "Novice (level 20)", 1,
                    "Adept (level 50)", 2,
                    "Expert (level 70)", 3,
                    "Never mind.", -1,
                    title = "What tier of contract?",
                )
        }
        return picked.takeIf { it >= 0 }
    }

    /* Clients */

    private suspend fun ProtectedAccess.talkClient(npc: Npc, index: Int) {
        val house = houses[index]
        if (player.houseIndex() != index) {
            // Authored (not Wiki transcript).
            startDialogue(npc) { chatNpc(neutral, "Hello there. Lovely day, isn't it?") }
            return
        }
        if (!player.contractDone()) {
            val left = house.spots.count { player.slotState(it.slot) in PENDING }
            startDialogue(npc) {
                chatNpc(neutral, "Oh, you're from Mahogany Homes! There's still ${if (left == 1) "one job" else "$left jobs"} left to do around the house.")
            }
            return
        }
        val tier = tiers[player.tierIndex()]
        player.setDisplay(0)
        player.setContract(-1, 0, player.lastTier(), signed = true)
        val points = player.points() + tier.points
        player.setPoints(points)
        statAdvance(stats.construction, tier.completionXp * xpMultiplier())
        startDialogue(npc) { chatNpc(happy, "That's wonderful, thank you! I'll let Amy know you did a great job.") }
        mes("You have completed your contract. You have been awarded ${tier.points} carpenter points (total ${"%,d".format(points)}).")
    }

    /* Hotspots */

    private fun ProtectedAccess.spotFor(loc: BoundLocInfo): Spot? {
        val house = player.activeHouse()
        val spot = house?.spots?.firstOrNull { loc.isType(locRef(it.base)) }
        if (spot == null) mes("This isn't part of your contract.")
        return spot
    }

    private suspend fun ProtectedAccess.repair(loc: BoundLocInfo) {
        val spot = spotFor(loc) ?: return
        if (player.slotState(spot.slot) != STATE_BROKEN) return
        if (!hasTools()) return
        val tier = tiers[player.tierIndex()]
        val material = if (spot.steel) MhObjs.steel_bar else tier.plank
        if (spot.steel) {
            if (invTotal(inv, material) < 1) {
                mes("You need a steel bar to repair the ${spot.label}.")
                return
            }
            invDel(inv, material, 1)
        } else if (!takePlanks(tier.plank, 1)) {
            mes("You need a ${objTypes[tier.plank].name.lowercase()} to repair the ${spot.label}.")
            return
        }
        anim(ConstructionSeqs.build)
        delay(2)
        player.setSlotState(spot.slot, STATE_REPAIRED)
        val xp = if (spot.steel) STEEL_XP else tier.repairXp
        statAdvance(stats.construction, xp * xpMultiplier())
        mes("You repair the ${spot.label}.")
        doneCheck()
    }

    private suspend fun ProtectedAccess.remove(loc: BoundLocInfo) {
        val spot = spotFor(loc) ?: return
        when (player.slotState(spot.slot)) {
            STATE_REMOVE -> {
                anim(ConstructionSeqs.build)
                delay(2)
                player.setSlotState(spot.slot, STATE_EMPTY)
                mes("You remove the old ${spot.label}.")
            }
            in STATE_BUILT..STATE_BUILT + 3 -> mes("You've already built a new ${spot.label} here.")
        }
    }

    private suspend fun ProtectedAccess.build(loc: BoundLocInfo) {
        val spot = spotFor(loc) ?: return
        if (player.slotState(spot.slot) != STATE_EMPTY) return
        val tierIndex = player.tierIndex()
        val tier = tiers[tierIndex]
        if (player.constructionLvl < tier.level) {
            mes("You need a Construction level of ${tier.level} to build that.")
            return
        }
        if (!hasTools()) return
        val name = objTypes[tier.plank].name.lowercase()
        if (plankCount(tier.plank) < spot.planks) {
            mes("You need ${spot.planks} ${name}s to build the ${spot.label}.")
            return
        }
        takePlanks(tier.plank, spot.planks)
        anim(ConstructionSeqs.build)
        delay(2)
        player.setSlotState(spot.slot, STATE_BUILT + tierIndex)
        statAdvance(stats.construction, spot.planks * tier.buildXp * xpMultiplier())
        mes("You build a new ${spot.label}.")
        doneCheck()
    }

    private fun ProtectedAccess.doneCheck() {
        val house = player.activeHouse() ?: return
        if (player.contractDone()) {
            mes("You have finished the contract. Speak to ${house.owner} to get paid.")
        }
    }

    private fun ProtectedAccess.hasTools(): Boolean {
        val saw = player.hasTool(MhObjs.saw) || player.hasTool(MhObjs.amys_saw)
        if (!saw || !player.hasTool(MhObjs.hammer)) {
            mes("You need a hammer and a saw to do that.")
            return false
        }
        return true
    }

    /** Carpenter's outfit (Wiki): 0.4/0.8/0.6/0.2% per piece, 2.5% with the full set. */
    private fun ProtectedAccess.xpMultiplier(): Double {
        val pieces =
            listOf(
                MhObjs.carpenter_helmet to 0.004,
                MhObjs.carpenter_shirt to 0.008,
                MhObjs.carpenter_trousers to 0.006,
                MhObjs.carpenter_boots to 0.002,
            )
        val worn = pieces.filter { (obj, _) -> player.worn.any { it.isType(obj) } }
        val bonus = worn.sumOf { it.second } + if (worn.size == pieces.size) 0.005 else 0.0
        return xpMods.get(player, stats.construction) * (1.0 + bonus)
    }

    /* Plank sack: up to 28 planks of any kind, saved per plank type; builds draw from it. */

    private fun Player.sacked(index: Int): Int = vars[MhVarps.sack[index]]

    private fun Player.setSacked(index: Int, value: Int) = VarPlayerIntMapSetter.set(this, MhVarps.sack[index], value)

    private fun Player.sackTotal(): Int = MhVarps.sack.indices.sumOf { sacked(it) }

    private fun ProtectedAccess.hasSack(): Boolean = invTotal(inv, MhObjs.plank_sack) > 0

    private fun ProtectedAccess.plankCount(plank: ObjType): Int {
        val index = sackPlanks.indexOf(plank)
        val sacked = if (index >= 0 && hasSack()) player.sacked(index) else 0
        return invTotal(inv, plank) + sacked
    }

    /** Inventory planks first, then the plank sack. */
    private fun ProtectedAccess.takePlanks(plank: ObjType, count: Int): Boolean {
        if (plankCount(plank) < count) return false
        val carried = minOf(count, invTotal(inv, plank))
        if (carried > 0) invDel(inv, plank, carried)
        val rest = count - carried
        if (rest > 0) {
            val index = sackPlanks.indexOf(plank)
            player.setSacked(index, player.sacked(index) - rest)
        }
        return true
    }

    private fun ProtectedAccess.fillSack() {
        var space = SACK_CAPACITY - player.sackTotal()
        if (space <= 0) {
            mes("Your plank sack is full.")
            return
        }
        var added = 0
        for ((index, plank) in sackPlanks.withIndex()) {
            val count = minOf(space, invTotal(inv, plank))
            if (count <= 0) continue
            invDel(inv, plank, count)
            player.setSacked(index, player.sacked(index) + count)
            space -= count
            added += count
        }
        mes(if (added == 0) "You don't have any planks to put in the sack." else "You fill the plank sack.")
    }

    private fun ProtectedAccess.emptySack() {
        if (player.sackTotal() == 0) {
            mes("Your plank sack is empty.")
            return
        }
        for ((index, plank) in sackPlanks.withIndex()) {
            val count = minOf(player.sacked(index), inv.freeSpace())
            if (count <= 0) continue
            invAdd(inv, plank, count)
            player.setSacked(index, player.sacked(index) - count)
        }
        mes(if (player.sackTotal() > 0) "You don't have enough inventory space to empty the whole sack." else "You empty the plank sack.")
    }

    private fun ProtectedAccess.checkSack() {
        val parts = sackPlanks.indices.filter { player.sacked(it) > 0 }
        if (parts.isEmpty()) {
            mes("Your plank sack is empty.")
            return
        }
        val text = parts.joinToString(", ") { "${player.sacked(it)} x ${objTypes[sackPlanks[it]].name}" }
        mes("Your plank sack contains: $text.")
    }

    /* Rewards */

    private suspend fun ProtectedAccess.rewards(npc: Npc) {
        var picked: Ware? = null
        val points = player.points()
        startDialogue(npc) {
            val first =
                choice5(
                    label(wares[0]), 0,
                    label(wares[1]), 1,
                    label(wares[2]), 2,
                    label(wares[3]), 3,
                    "More...", -1,
                    title = "Carpenter points: ${"%,d".format(points)}",
                )
            picked =
                if (first >= 0) {
                    wares[first]
                } else {
                    val second =
                        choice4(
                            label(wares[4]), 4,
                            label(wares[5]), 5,
                            label(wares[6]), 6,
                            "Nothing, thanks.", -1,
                            title = "Carpenter points: ${"%,d".format(points)}",
                        )
                    wares.getOrNull(second)
                }
        }
        val ware = picked ?: return
        val count =
            if (ware.obj.isSameAs(MhObjs.supply_crate)) {
                val max = player.points() / ware.points
                if (max <= 1) 1 else minOf(countDialog("How many crates? (max $max)"), max)
            } else {
                1
            }
        if (count <= 0) return
        val cost = ware.points * count
        if (player.points() < cost) {
            mes("You need ${"%,d".format(cost)} carpenter points for that; you have ${"%,d".format(player.points())}.")
            return
        }
        if (inv.freeSpace() < count) {
            mes("You don't have enough inventory space.")
            return
        }
        player.setPoints(player.points() - cost)
        invAdd(inv, ware.obj, count)
        val what = if (count == 1) ware.name.lowercase() else "$count ${ware.name.lowercase()}s"
        mes("You spend ${"%,d".format(cost)} carpenter points on $what.")
    }

    private fun ObjType.isSameAs(other: ObjType): Boolean = objTypes[this].id == objTypes[other].id

    private fun label(ware: Ware): String = "${ware.name} - ${"%,d".format(ware.points)} points"

    private fun ProtectedAccess.openCrate() {
        val roll = random.of(crate.sumOf { it.weight })
        var acc = 0
        val loot = crate.first { acc += it.weight; roll < acc }
        val count = random.of(loot.min, loot.max)
        invDel(inv, MhObjs.supply_crate, 1)
        invAdd(inv, loot.obj, count)
        mes("You open the supply crate and find $count x ${objTypes[loot.obj].name.lowercase()}.")
    }

    private companion object {
        const val STATE_BROKEN = 1
        const val STATE_REPAIRED = 2
        const val STATE_REMOVE = 3
        const val STATE_EMPTY = 4
        const val STATE_BUILT = 5
        val PENDING = setOf(STATE_BROKEN, STATE_REMOVE, STATE_EMPTY)
        const val STEEL_XP = 120.0
        const val SACK_CAPACITY = 28
    }
}

internal object MhLocs : LocReferences() {
    val byName: Map<String, LocType> =
        MahoganyHomesData.houses
            .flatMap { house ->
                house.spots.flatMap { listOf(it.base, it.broken) + listOfNotNull(it.editable, it.empty) + it.built }
            }
            .distinct()
            .associateWith { find(it) }
}

internal object MhNpcs : NpcReferences() {
    val amy_1op = find("con_contractor_1op")
    val amy_3op = find("con_contractor_3op")
    val contractors =
        listOf(
            find("con_contractor_varrock_1op") to find("con_contractor_varrock_2op"),
            find("con_contractor_ardougne_1op") to find("con_contractor_ardougne_2op"),
            find("con_contractor_hosidius_1op") to find("con_contractor_hosidius_2op"),
        )
    val byName: Map<String, NpcType> = MahoganyHomesData.houses.map { it.client }.associateWith { find(it) }
}

internal object MhObjs : ObjReferences() {
    val hammer = find("hammer")
    val saw = find("poh_saw")
    val amys_saw = find("wearable_saw")
    val steel_bar = find("steel_bar")
    val plank = find("woodplank")
    val plank_oak = find("plank_oak")
    val plank_teak = find("plank_teak")
    val plank_mahogany = find("plank_mahogany")
    val carpenter_helmet = find("construction_hat")
    val carpenter_shirt = find("construction_shirt")
    val carpenter_trousers = find("construction_trousers")
    val carpenter_boots = find("construction_boots")
    val plank_sack = find("plank_sack")
    val supply_crate = find("construction_supply_crate")
    val cert_plank_oak = find("cert_plank_oak")
    val cert_plank_teak = find("cert_plank_teak")
    val cert_plank_mahogany = find("cert_plank_mahogany")
    val cert_soft_clay = find("cert_softclay")
    val cert_steel_bar = find("cert_steel_bar")
    val cert_bolt_of_cloth = find("cert_cloth")
    val cert_limestone_brick = find("cert_limestonebrick")
}

internal object MhVarBits : VarBitReferences() {
    val discussed = find("con_contract_discussed")
}

/** Skilling symbol range (65559-65565). Saved per character. */
object MhVarpBuilds : VarpBuilder() {
    init {
        build("mh_points")
        build("mh_contract")
        build("mh_display")
        for (wood in MhData.SACK_WOODS) build("mh_sack_$wood")
    }
}

internal object MhData {
    val SACK_WOODS = listOf("plank", "oak", "teak", "mahogany")
}

internal object MhVarps : VarpReferences() {
    val points = find("mh_points")
    val contract = find("mh_contract")
    val display = find("mh_display")
    val building_display = find("construction_contract_building_display")
    val sack = MhData.SACK_WOODS.map { find("mh_sack_$it") }
}
