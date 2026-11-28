package org.rsmod.content.quests.clocktower

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Clock Tower (OSRS Wiki). `cogquest` varp (cache db end state 8): 1 Brother Kojo's request, then
 * 2-5 as each of the four cogs goes on its spindle (red ground floor, blue first floor, white top
 * floor, black in the cellar), 8 complete (500 coins, 1,000 Firemaking xp, 1 QP). The cogs are the
 * world's ground spawns in the cellar; only one can be carried at a time, and the black cog sits
 * in fire (a bucket of water or worn ice gloves to take it). The white cog is behind the rat pen:
 * both levers open its gate, rat poison in the food trough kills the rats, and the far gate then
 * comes off its hinges.
 *
 * Server varp `clocktower_progress` (65780): bits 0-3 the cogs placed (red, blue, white, black),
 * bit 4 / 5 the levers, bit 6 the rats poisoned. A fitted spindle shows its cog for a while (the
 * spindles are shared locs).
 *
 * Not done: the blue cog's ladder/passage from the zoo is the world's own; only its wall is bound.
 */
class ClockTower
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ClockTower::class.java, "clock_tower") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, CtNpcs.kojo, "Talk-to") { talkKojo(it) }
        for ((index, cog) in CtObjs.cogs.withIndex()) {
            onOpObj3(cog) { takeCog(it.obj, index) }
            onOpLocU(CtLocs.broken_spindles[index], cog) { placeCog(it.loc, index) }
            for (other in CtObjs.cogs) {
                if (other != cog) {
                    onOpLocU(CtLocs.broken_spindles[index], other) {
                        mes("The cog doesn't fit on this spindle.")
                    }
                }
            }
        }
        onOpLocU(CtLocs.trough, CtObjs.rat_poison) { poisonTrough() }
        onLocOpLabel(locTypes, CtLocs.lever_a, "Pull") { pullLever(LEVER_A) }
        onLocOpLabel(locTypes, CtLocs.lever_b, "Pull") { pullLever(LEVER_B) }
        onLocOpLabel(locTypes, CtLocs.lever_a2, "Pull") { pullLever(LEVER_A) }
        onLocOpLabel(locTypes, CtLocs.lever_b2, "Pull") { pullLever(LEVER_B) }
        for (gate in listOf(CtLocs.rat_gate_a, CtLocs.rat_gate_b)) {
            onLocOpLabel(locTypes, gate, "Open") { ratGate(it) }
        }
        onLocOpLabel(locTypes, CtLocs.rat_gate_c, "Go-through") { farGate(it) }
        onLocOpLabel(locTypes, CtLocs.secret_wall, "Push") { passThroughWall(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(CLOCK_TOWER)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(CLOCK_TOWER, value)
    }

    private fun ProtectedAccess.flag(bit: Int): Boolean =
        player.vars[CtVarps.progress] and (1 shl bit) != 0

    private fun ProtectedAccess.setFlag(bit: Int, on: Boolean = true) {
        val v = player.vars[CtVarps.progress]
        val next = if (on) v or (1 shl bit) else v and (1 shl bit).inv()
        VarPlayerIntMapSetter.set(player, CtVarps.progress, next)
    }

    private val ProtectedAccess.placed: Int
        get() = (0 until COGS).count { flag(it) }

    /* Brother Kojo */

    private suspend fun ProtectedAccess.talkKojo(npc: Npc) {
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
        val section =
            when {
                state == 0 -> "Starting out"
                state == COMPLETE -> null
                placed == COGS -> "Finishing up"
                placed == 0 -> "Talking to Brother Kojo again"
                placed == 1 -> "After placing a cog"
                placed == 2 -> "After placing two cogs"
                else -> "After placing three cogs"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(happy, "Thank you again for fixing the clock!") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (section == "Finishing up") {
            invAddOrDrop(objRepo, CtObjs.coins, REWARD_COINS)
            statAdvance(stats.firemaking, REWARD_XP * xpMods.get(player, stats.firemaking))
            completeQuest(CLOCK_TOWER)
        }
    }

    /* The cogs */

    private suspend fun ProtectedAccess.takeCog(obj: Obj, index: Int) {
        if (CtObjs.cogs.any { carries(it) }) {
            mes("The cogs are heavy; you can only carry one at a time.")
            return
        }
        if (index == BLACK) {
            val cooled = carries(CtObjs.bucket_water) || invTotal(worn, CtObjs.ice_gloves) > 0
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            BLACK_HOT to { !cooled },
                            BLACK_COOL to { cooled },
                        )
                )
            runQuestSection(null, dialogue, "Black cog", hooks)
            if (!cooled) return
            if (invTotal(worn, CtObjs.ice_gloves) == 0) {
                invDel(inv, CtObjs.bucket_water)
                invAdd(inv, CtObjs.bucket)
            }
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        if (!objRepo.del(obj)) {
            mes("Too late - it's gone!")
            return
        }
        invAdd(inv, CtObjs.cogs[index])
    }

    private suspend fun ProtectedAccess.placeCog(spindle: BoundLocInfo, index: Int) {
        if (state == 0 || state == COMPLETE) {
            mes("You'd better ask Brother Kojo before fiddling with the clock.")
            return
        }
        if (flag(index)) {
            mes("There's already a cog on this spindle.")
            return
        }
        invDel(inv, CtObjs.cogs[index])
        locRepo.change(spindle, CtLocs.spindles[index], SPINDLE_TICKS)
        mes("The cog fits perfectly.")
        setFlag(index)
        setState(STARTED + placed)
    }

    /* The rat pen and the white cog */

    private suspend fun ProtectedAccess.pullLever(bit: Int) {
        val up = !flag(bit)
        setFlag(bit, up)
        mes(if (up) "You pull the lever up." else "You pull the lever down.")
        if (flag(LEVER_A) && flag(LEVER_B)) mes("You hear the gates to the rat pen unlock.")
    }

    private suspend fun ProtectedAccess.ratGate(gate: BoundLocInfo) {
        if (!(flag(LEVER_A) && flag(LEVER_B))) {
            mes("The gate is locked shut.")
            return
        }
        passThroughWall(gate)
    }

    private suspend fun ProtectedAccess.poisonTrough() {
        if (flag(RATS_POISONED)) {
            mes("The rats are already dead.")
            return
        }
        invDel(inv, CtObjs.rat_poison)
        mes("You pour the rat poison into the feeding trough.")
        mes("The rats swarm to the trough to eat the poisoned food, and keel over.")
        setFlag(RATS_POISONED)
        val zone = ZoneKey.from(player.coords)
        for (npc in npcRepo.findAll(zone, 1).toList()) {
            if (npc.isSlotAssigned && CtNpcs.rats.any { it.id == npc.type.id }) {
                npcRepo.del(npc, RAT_RESPAWN)
            }
        }
    }

    private suspend fun ProtectedAccess.farGate(gate: BoundLocInfo) {
        if (!flag(RATS_POISONED)) {
            mes("The gate is firmly shut. Perhaps something could shake it loose.")
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "Trying to open the door before the rats have been poisoned:" to { false },
                        "Using the poison on the food trough:" to { false },
                        "Opening the door after poisoning the rats:" to { true },
                    )
            )
        runQuestSection(null, dialogue, "White cog", hooks)
        passThroughWall(gate)
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 8

        private const val COGS = 4
        private const val BLACK = 3
        private const val LEVER_A = 4
        private const val LEVER_B = 5
        private const val RATS_POISONED = 6

        private const val REWARD_COINS = 500
        private const val REWARD_XP = 1000.0
        private const val SPINDLE_TICKS = 300
        private const val RAT_RESPAWN = 100

        private const val BLACK_HOT =
            "If the player does not have a bucket of water or ice gloves equipped:"
        private const val BLACK_COOL =
            "If the player has a bucket of water or ice gloves equipped:"

        val CLOCK_TOWER =
            QuestDef(
                name = "Clock Tower",
                varp = { CtVarps.cogquest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("500 Coins", "1,000 Firemaking XP"),
                scrollObj = { CtObjs.cogs[0] },
            )
    }
}

// Every cache ref Clock Tower uses (re-check after cache updates).

internal object CtNpcs : NpcReferences() {
    val kojo = find("brother_kojo")
    val rats = listOf(find("clocktower_rat"), find("clocktower_rat2"), find("clocktower_rat3"))
}

internal object CtLocs : LocReferences() {
    /** Red, blue, white, black (the cogs' order). */
    val broken_spindles: List<LocType> =
        listOf(
            find("brokeclockpole_red"),
            find("brokeclockpole_blue"),
            find("brokeclockpole_white"),
            find("brokeclockpole_black"),
        )
    val spindles: List<LocType> =
        listOf(
            find("clockpole_red"),
            find("clockpole_blue"),
            find("clockpole_white"),
            find("clockpole_black"),
        )
    val trough = find("ctfoodtrough")
    val lever_a = find("ctlevera")
    val lever_b = find("ctleverb")
    val lever_a2 = find("ctlevera2")
    val lever_b2 = find("ctleverb2")
    val rat_gate_a = find("ctratgatea")
    val rat_gate_b = find("ctratgateb")
    val rat_gate_c = find("ctratgatec")
    val secret_wall = find("secretdoor2")
}

internal object CtObjs : ObjReferences() {
    /** Red, blue, white, black (the spindles' order). */
    val cogs: List<ObjType> =
        listOf(find("redcog"), find("bluecog"), find("whitecog"), find("blackcog"))
    val rat_poison = find("rat_poison")
    val bucket_water = find("bucket_water")
    val bucket = find("bucket_empty")
    val ice_gloves = find("ice_gloves")
    val coins = find("coins")
}

internal object CtVarps : VarpReferences() {
    val cogquest = find("cogquest")

    /** Server varp (65780): cogs placed (bits 0-3), levers (4, 5), rats poisoned (6). */
    val progress = find("clocktower_progress")
}

internal object CtVarpBuilds : VarpBuilder() {
    init {
        build("clocktower_progress")
    }
}
