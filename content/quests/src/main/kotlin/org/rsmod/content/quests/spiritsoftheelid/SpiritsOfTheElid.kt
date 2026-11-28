package org.rsmod.content.quests.spiritsoftheelid

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
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
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Spirits of the Elid (OSRS Wiki). The quest varbit is the cache's `elidquest` (0-60); its
 * multilocs fill Nardah's fountain and place the statuette at 60. The golem and channel varbits
 * (`elid_whitegolem`, `elid_thievingchannel`, ...) drive the cave's doors and channel multilocs.
 * - Awusah the Mayor (Nardah) starts it; Ghaslor the Elder gives the ballad.
 * - The north-west cupboard of the temple: torn robes; a needle and thread mend them. Telegrab the
 *   ancestral key from the temple table (normal Telekinetic Grab).
 * - A rope on the root over the waterfall cave (north of Nardah, on the Elid); the robes worn and
 *   the key open the doors. Kill each golem (white, grey, black) and clear its channel: Thieving
 *   (spikes), Mining (rocks), Ranged (the target). Through the lake doors to the spirits.
 * - The mayor threw the statuette down the crevice west of the general store (a rope); the genie
 *   wants a soul: cut the sole from the mayor's shoes (outside his house) and trade it.
 * - The statuette on the plinth in the shrine. 2 QP, 8,000 Prayer, 1,000 Thieving and 1,000 Magic
 *   xp.
 *
 * Simplified (Authored, not Wiki): the target is shot with Ranged 37 alone (no ammo check); the
 * crevice needs no light source; the cupboard's robes are given on Search (it doesn't open);
 * arrival tiles and the golem / genie / target spawns are best guesses (Ryan-test).
 */
class SpiritsOfTheElid
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(SpiritsOfTheElid::class.java, "spirits_of_the_elid")
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, ElidNpcs.mayor, SPIRITS_OF_THE_ELID, wants = { !complete }) {
                talkMayor(it)
            }
            register(npcTypes, ElidNpcs.ghaslor, SPIRITS_OF_THE_ELID, wants = { !complete }) {
                talkGhaslor(it)
            }
            register(npcTypes, ElidNpcs.shiratti, SPIRITS_OF_THE_ELID, wants = { !complete }) {
                talkShiratti(it)
            }
            register(npcTypes, ElidNpcs.genie, SPIRITS_OF_THE_ELID, wants = { true }) { talkGenie(it) }
            for (spirit in ElidNpcs.spirits) {
                register(npcTypes, spirit, SPIRITS_OF_THE_ELID, wants = { true }) { talkSpirits(it) }
            }
        }
        onOpNpcU(ElidNpcs.mayor, ElidObjs.knife) {
            runQuestSection(it.npc, dialogue, "Using a knife on Awusah the Mayor")
        }

        onLocOpLabel(locTypes, ElidLocs.robe_cupboard, "Open") { searchCupboard() }
        onLocOpLabel(locTypes, ElidLocs.robe_cupboard_open, "Search") { searchCupboard() }
        onOpHeldU(ElidObjs.needle, ElidObjs.torn_top) { mend(ElidObjs.torn_top, ElidObjs.robe_top) }
        onOpHeldU(ElidObjs.needle, ElidObjs.torn_bottoms) {
            mend(ElidObjs.torn_bottoms, ElidObjs.robe_bottoms)
        }

        onOpLocU(ElidLocs.root, ElidObjs.rope) { enterCave() }
        onLocOpLabel(locTypes, ElidLocs.cave_exit, "Leave") { telejump(CAVE_OUTSIDE) }
        for (door in ElidLocs.robe_doors) {
            onLocOpLabel(locTypes, door, "Open") { openRobeDoor(it) }
            onOpLocU(door, ElidObjs.key) { openRobeDoor(it.loc) }
        }
        for ((door, golem) in ElidLocs.golem_doors.zip(ElidVarBits.golems)) {
            onLocOpLabel(locTypes, door, "Open") { openGolemDoor(it, golem) }
        }
        onEvent<NpcKilledEvent> { golemKilled(this) }
        onLocOpLabel(locTypes, ElidLocs.spike_trap, "Clear") { clearSpikes() }
        onLocOpLabel(locTypes, ElidLocs.blocked_rocks, "Clear") { clearRocks() }
        onNpcOpLabel(npcTypes, ElidNpcs.target, "Shoot") { shootTarget() }
        for (door in ElidLocs.lake_doors) onLocOpLabel(locTypes, door, "Open") { openLakeDoor(it) }

        onOpLocU(ElidLocs.crevice, ElidObjs.rope) { enterCrevice() }
        onLocOpLabel(locTypes, ElidLocs.crevice, "Climb-down") { enterCrevice() }
        onLocOpLabel(locTypes, ElidLocs.genie_rope, "Climb") { telejump(CREVICE_OUTSIDE) }
        onLocOpLabel(locTypes, ElidLocs.genie_door, "Open") { passThroughWall(it) }
        onOpHeldU(ElidObjs.knife, ElidObjs.shoes) { cutShoes() }
        onOpLocU(ElidLocs.plinth, ElidObjs.statuette) { placeStatuette() }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(SPIRITS_OF_THE_ELID)

    private val ProtectedAccess.complete: Boolean
        get() = stage >= COMPLETE

    private fun ProtectedAccess.advance(to: Int) {
        if (stage < to) player.setQuestState(SPIRITS_OF_THE_ELID, to)
    }

    private fun ProtectedAccess.flag(varbit: VarBitType): Int = player.vars[varbit]

    private fun ProtectedAccess.setFlag(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /* Nardah */

    private suspend fun ProtectedAccess.talkMayor(npc: Npc) {
        when {
            stage == 0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "Starting off", hooks)
                if (start) advance(STARTED)
            }
            carries(ElidObjs.statuette) ->
                runQuestSection(npc, dialogue, "Talking to the mayor with the statuette")
            stage < SPIRITS -> runQuestSection(npc, dialogue, "Talking to Awusah after starting the quest")
            stage == SPIRITS -> {
                val prayer = statBase(stats.prayer) >= PRAYER_LINE
                val hooks =
                    SimpleQuestHooks(conditions = mapOf("If the player has at least 40 Prayer:" to { prayer }))
                runQuestSection(npc, dialogue, "Talking to Awusah the Mayor", hooks)
                advance(CREVICE)
            }
            stage == CREVICE -> runQuestSection(npc, dialogue, "Talking to Awusah the Mayor again")
            else -> runQuestSection(npc, dialogue, "Talking to the mayor again")
        }
    }

    private suspend fun ProtectedAccess.talkGhaslor(npc: Npc) {
        if (stage >= SPIRITS) {
            runQuestSection(npc, dialogue, "Talking to Ghaslor the Elder (2)")
            return
        }
        var ballad = false
        val hooks =
            SimpleQuestHooks(options = mapOf("River spirits, what are they?" to { ballad = true }))
        runQuestSection(npc, dialogue, "Talking to Ghaslor the Elder", hooks)
        if (ballad && !carries(ElidObjs.ballad)) invAddOrDrop(objRepo, ElidObjs.ballad)
    }

    private suspend fun ProtectedAccess.talkShiratti(npc: Npc) {
        when {
            carries(ElidObjs.statuette) ->
                runQuestSection(npc, dialogue, "Talking to Shiratti with the statuette")
            stage >= CREVICE -> runQuestSection(npc, dialogue, "Talking to Shiratti the Custodian (2)")
            else -> {
                runQuestSection(npc, dialogue, "Talking to Shiratti the Custodian")
                if (stage >= STARTED) setFlag(ElidVarBits.shiratti, 1)
            }
        }
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        if (stage < STARTED || owns(ElidObjs.torn_top, ElidObjs.robe_top)) {
            mes("You search the cupboard but find nothing of interest.") // Authored (not Wiki).
            return
        }
        runQuestSection(null, dialogue, "Searching the north-western cupboard")
        invAddOrDrop(objRepo, ElidObjs.torn_top)
        invAddOrDrop(objRepo, ElidObjs.torn_bottoms)
    }

    private fun ProtectedAccess.owns(vararg objs: ObjType): Boolean =
        objs.any { carries(it) || invTotal(worn, it) > 0 }

    private fun ProtectedAccess.mend(torn: ObjType, robe: ObjType) {
        if (!carries(ElidObjs.thread)) {
            mes("You need some thread to repair this.") // Authored (not Wiki).
            return
        }
        invDel(inv, ElidObjs.thread)
        invReplace(inv, torn, 1, robe)
        mes("You repair the torn robe with the needle and thread.") // Authored (not Wiki).
    }

    /* The waterfall cave */

    private fun ProtectedAccess.enterCave() {
        // Authored line (not Wiki).
        mes("You tie the rope to the root and climb down into the cave.")
        telejump(CAVE_INSIDE)
    }

    private suspend fun ProtectedAccess.openRobeDoor(door: BoundLocInfo) {
        if (player.coords.x <= ROBE_DOOR_X) {
            passThroughWall(door)
            return
        }
        if (!carries(ElidObjs.key)) {
            mes("The doors are locked.") // Authored (not Wiki).
            return
        }
        if (invTotal(worn, ElidObjs.robe_top) == 0 || invTotal(worn, ElidObjs.robe_bottoms) == 0) {
            runQuestSection(null, dialogue, "Attempting to open the door without the robes of Elidinis equipped")
            return
        }
        runQuestSection(null, dialogue, "Opening the door with the robes equipped")
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.openGolemDoor(door: BoundLocInfo, golem: VarBitType) {
        if (flag(golem) == 0) {
            val golemSpeaker = mapOf("Golem" to npcTypes[ElidNpcs.golems[0]])
            runQuestSection(
                null,
                dialogue,
                "Attempting to open a door before defeating the golem",
                SimpleQuestHooks(),
                golemSpeaker,
            )
            return
        }
        passThroughWall(door)
    }

    private fun golemKilled(event: NpcKilledEvent) {
        val index = ElidNpcs.golems.indexOfFirst { it.id == event.npc.type.id }
        if (index < 0) return
        val player = event.hero
        VarPlayerIntMapSetter.set(player, ElidVarBits.golems[index], 1)
        player.mes("The Golem disappears, but will be back to defend his door.")
    }

    private suspend fun ProtectedAccess.clearSpikes() {
        if (!meets(stats.thieving, "Thieving")) return
        setFlag(ElidVarBits.thieving_channel, 1)
        runQuestSection(null, dialogue, "Southern room")
    }

    private suspend fun ProtectedAccess.clearRocks() {
        if (!meets(stats.mining, "Mining")) return
        if (ElidObjs.pickaxes.none { carries(it) || invTotal(worn, it) > 0 }) {
            mes("You need a pickaxe to clear these rocks.") // Authored (not Wiki).
            return
        }
        setFlag(ElidVarBits.mining_channel, 1)
        runQuestSection(null, dialogue, "Eastern room")
    }

    private suspend fun ProtectedAccess.shootTarget() {
        if (!meets(stats.ranged, "Ranged")) return
        setFlag(ElidVarBits.ranging_channel, 1)
        runQuestSection(null, dialogue, "Northern room")
    }

    private fun ProtectedAccess.meets(stat: StatType, name: String): Boolean {
        if (stat(stat) >= SKILL_LEVEL) return true
        mes("You need a $name level of $SKILL_LEVEL to do this.") // Authored (not Wiki).
        return false
    }

    private suspend fun ProtectedAccess.openLakeDoor(door: BoundLocInfo) {
        val open = ElidVarBits.channels.all { flag(it) == 1 }
        if (!open && player.coords.z < LAKE_DOOR_Z) {
            // Authored line (not Wiki): the machinery isn't running yet.
            mes("The doors won't budge. Perhaps the machinery behind them needs water.")
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkSpirits(npc: Npc) {
        if (stage >= SPIRITS) {
            runQuestSection(npc, dialogue, SPIRITS_AGAIN, SimpleQuestHooks(), speakers)
            return
        }
        var told = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf("Is there anything they can do to get their fountain working again?" to { told = true })
            )
        runQuestSection(npc, dialogue, "Talking to the Spirits of the Elid", hooks, speakers)
        if (told && stage >= STARTED) advance(SPIRITS)
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Nirrie" to npcTypes[ElidNpcs.spirits[0]],
            "Tirrie" to npcTypes[ElidNpcs.spirits[1]],
            "Hallak" to npcTypes[ElidNpcs.spirits[2]],
        )
    }

    /* The crevice and the genie */

    private fun ProtectedAccess.enterCrevice() {
        if (!carries(ElidObjs.rope) && stage < CREVICE) {
            mes("You need a rope to climb down the crevice.") // Authored (not Wiki).
            return
        }
        mes("You climb down into the crevice.") // Authored (not Wiki).
        telejump(CREVICE_INSIDE)
    }

    private suspend fun ProtectedAccess.talkGenie(npc: Npc) {
        val sole = carries(ElidObjs.sole)
        when {
            stage < CREVICE ->
                startDialogue(npc) {
                    // Authored (not Wiki): before the quest sends you here.
                    chatNpc(neutral, "Leave me be, mortal.")
                }
            stage == CREVICE -> {
                var deal = false
                val hooks = SimpleQuestHooks(options = mapOf("Ok I agree to the deal." to { deal = true }))
                runQuestSection(npc, dialogue, "Talking to the Genie", hooks)
                if (deal) advance(DEAL)
            }
            stage == DEAL && sole -> {
                runQuestSection(npc, dialogue, "Returning to the genie with the mayor's \"sole\"")
                invReplace(inv, ElidObjs.sole, 1, ElidObjs.statuette)
                advance(STATUETTE)
            }
            stage == DEAL ->
                runQuestSection(npc, dialogue, "Talking to the Genie again after accepting his deal")
            !carries(ElidObjs.statuette) && !complete -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has a sole:" to { sole },
                                "If the player does not have a sole:" to { !sole },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to the genie after losing the statuette", hooks)
                if (sole) invReplace(inv, ElidObjs.sole, 1, ElidObjs.statuette)
            }
            else ->
                startDialogue(npc) {
                    chatNpc(neutral, "We have done our business, mortal.") // Authored (not Wiki).
                }
        }
    }

    private suspend fun ProtectedAccess.cutShoes() {
        if (stage < DEAL) {
            mes("You'd better not ruin the mayor's shoes.") // Authored (not Wiki).
            return
        }
        if (player.inMayorsHouse()) {
            runQuestSection(null, dialogue, "Attempting to cut the mayor's shoes in his presence", speakers = mapOf("Awusah the Mayor" to ElidNpcs.mayor))
            return
        }
        runQuestSection(null, dialogue, "Cutting the mayor's shoes")
        invReplace(inv, ElidObjs.shoes, 1, ElidObjs.sole)
    }

    private fun Player.inMayorsHouse(): Boolean =
        coords.x in MAYOR_HOUSE_X && coords.z in MAYOR_HOUSE_Z

    private suspend fun ProtectedAccess.placeStatuette() {
        if (stage < STATUETTE) return
        invDel(inv, ElidObjs.statuette)
        runQuestSection(null, dialogue, "Using the statuette on the plinth")
        statAdvance(stats.prayer, PRAYER_XP * xpMods.get(player, stats.prayer))
        statAdvance(stats.thieving, OTHER_XP * xpMods.get(player, stats.thieving))
        statAdvance(stats.magic, OTHER_XP * xpMods.get(player, stats.magic))
        completeQuest(SPIRITS_OF_THE_ELID)
    }

    companion object {
        const val STARTED = 10
        const val SPIRITS = 40
        const val CREVICE = 45
        const val DEAL = 50
        const val STATUETTE = 55
        const val COMPLETE = 60

        private const val SPIRITS_AGAIN =
            "Talking to the Spirits of the Elid again after learning about the statuette"
        private const val SKILL_LEVEL = 37
        private const val PRAYER_LINE = 40
        private const val PRAYER_XP = 8000.0
        private const val OTHER_XP = 1000.0

        /** The robe doors are at x 3353-3354; west of them is the entrance side. */
        private const val ROBE_DOOR_X = 3352
        /** The lake doors are at z 9558; north of them are the spirits. */
        private const val LAKE_DOOR_Z = 9559

        /** Authored (Ryan-test): the mayor's house, east Nardah. */
        private val MAYOR_HOUSE_X = 3437..3450
        private val MAYOR_HOUSE_Z = 2905..2922

        /** Authored arrival tiles (Ryan-test). */
        private val CAVE_INSIDE = CoordGrid(3348, 9535, 0)
        private val CAVE_OUTSIDE = CoordGrid(3372, 3133, 0)
        private val CREVICE_INSIDE = CoordGrid(3373, 9307, 0)
        private val CREVICE_OUTSIDE = CoordGrid(3375, 2905, 0)

        val SPIRITS_OF_THE_ELID =
            QuestDef(
                name = "Spirits of the Elid",
                varbit = { ElidVarBits.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards =
                    listOf(
                        "8,000 Prayer XP",
                        "1,000 Thieving XP",
                        "1,000 Magic XP",
                        "Nardah's fountain and shrine",
                    ),
                scrollObj = { ElidObjs.statuette },
                listRow = "quest_spiritsoftheelid",
            )
    }
}

// Every cache ref Spirits of the Elid uses (re-check after cache updates).

internal object ElidNpcs : NpcReferences() {
    val mayor = find("elid_mayor")
    val ghaslor = find("elid_ghaslor")
    val shiratti = find("elid_shiratti")
    val genie = find("elid_genie")
    /** Nirrie, the sitting spirit and Hallak, past the lake doors. */
    val spirits =
        listOf(find("elid_waterspirit"), find("elid_waterspirit_sitting"), find("elid_waterspirit_male"))
    /** White (south), grey (east), black (north): the [ElidVarBits.golems] order. */
    val golems = listOf(find("elid_golem_white"), find("elid_golem_grey"), find("elid_golem_black"))
    /** Child 0 of elid_ranging_target_multinpc (elid_rangingchannel). */
    val target = find("elid_ranging_target")
}

internal object ElidLocs : LocReferences() {
    /** The temple's north-west cupboard (3420,2930). */
    val robe_cupboard = find("elid_cupboard_closed_withrobes")
    val robe_cupboard_open = find("elid_cupboard_open_withrobes")
    val root = find("desert_water_cave_root")
    val cave_exit = find("elid_underground_exit")
    val robe_doors = listOf(find("elid_underground_robe_door"), find("elid_underground_robe_door_mirror"))
    /** White (south), grey (east), black (north). */
    val golem_doors =
        listOf(find("elid_whitegolem_door"), find("elid_greygolem_door"), find("elid_blackgolem_door"))
    /** Child 0 of elid_waterchannel_thieving_multiloc. */
    val spike_trap = find("elid_water_channel_spiketrap")
    /** Child 0 of elid_waterchannel_mining_multiloc. */
    val blocked_rocks = find("elid_water_channel_blocked_rocks")
    val lake_doors = listOf(find("elid_underground_lake_door"), find("elid_underground_lake_door_mirror"))
    val crevice = find("elid_crevice_clickzone")
    val genie_rope = find("elid_climbing_rope")
    val genie_door = find("elid_genie_door")
    /** Child of elid_statuette_multiloc before the quest's end. */
    val plinth = find("elid_statuette_base")
}

internal object ElidObjs : ObjReferences() {
    val torn_top = find("elid_robetop_torn")
    val torn_bottoms = find("elid_robebottoms_torn")
    val robe_top = find("elid_robetop")
    val robe_bottoms = find("elid_robebottoms")
    val key = find("elid_key")
    val ballad = find("elid_ballad")
    val shoes = find("elid_shoes")
    val sole = find("elid_sole")
    val statuette = find("elid_statuette")
    val needle = find("needle")
    val thread = find("thread")
    val knife = find("knife")
    val rope = find("rope")
    val pickaxes: List<ObjType> =
        listOf(
            "bronze_pickaxe", "iron_pickaxe", "steel_pickaxe", "black_pickaxe", "mithril_pickaxe",
            "adamant_pickaxe", "rune_pickaxe", "dragon_pickaxe", "infernal_pickaxe", "crystal_pickaxe",
        ).map { find(it) }
}

internal object ElidVarBits : VarBitReferences() {
    val quest = find("elidquest")
    val shiratti = find("elid_shiratti")
    /** White, grey, black golem beaten. */
    val golems = listOf(find("elid_whitegolem"), find("elid_greygolem"), find("elid_blackgolem"))
    val thieving_channel = find("elid_thievingchannel")
    val mining_channel = find("elid_miningchannel")
    val ranging_channel = find("elid_rangingchannel")
    val channels = listOf(thieving_channel, mining_channel, ranging_channel)
}
