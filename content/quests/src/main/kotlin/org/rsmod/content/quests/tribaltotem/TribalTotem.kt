package org.rsmod.content.quests.tribaltotem

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
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
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tribal Totem (OSRS Wiki). Cache quest varp `totemquest`, end state 5:
 * - 1 Kangai Mau (Brimhaven) wants the Rantuki totem back from Lord Handelmort's mansion; 2 the
 *   Handelmorts' crate at the G.P.D.T. depot (Ardougne) gives its address label, stuck over the
 *   label of Wizard Cromperty's teleport crate; 3 Cromperty's teleport then sends you, crate and
 *   all, inside the mansion; 4 the combination door (KURT, cache varbits totemquest_combodoor_code1-4
 *   hold the dials), the trapped stairs (Investigate first, or fall) and the chest's totem; 5 Kangai
 *   Mau: 1 QP, 1,775 Thieving xp and 5 swordfish.
 *
 * Simplified: the combination lock is four menus of letters, and Horacio's garden is just talk.
 */
class TribalTotem
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TribalTotem::class.java, "tribal_totem") }

    /** Players who have spotted the stairs' trap this login, by uid. */
    private val disarmed = HashSet<Int>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, TtNpcs.kangai, TRIBAL_TOTEM, wants = { state < COMPLETE }) { talkKangai(it) }
            register(npcTypes, TtNpcs.horacio, TRIBAL_TOTEM, wants = { state in STARTED until COMPLETE }) {
                runQuestSection(it, dialogue, "Talking to Horacio")
            }
            register(npcTypes, TtNpcs.employee, TRIBAL_TOTEM, wants = { state in STARTED until COMPLETE }) {
                val section =
                    if (state >= LABELLED) "Talking to GPDT employee after using the address label on the crate"
                    else "Talking to a GPDT employee"
                runQuestSection(it, dialogue, section)
            }
            for (cromperty in TtNpcs.crompertys) {
                register(npcTypes, cromperty, TRIBAL_TOTEM, wants = { state in STARTED until COMPLETE }) {
                    talkCromperty(it)
                }
            }
        }
        onLocOpLabel(locTypes, TtLocs.handelmort_crate, "Investigate") { investigateHandelmortCrate() }
        onLocOpLabel(locTypes, TtLocs.teleport_crate, "Investigate") {
            val section = if (state >= LABELLED) "Investing the crate afterwards" else "Investigating Wizard Cromperty's crate"
            runQuestSection(null, dialogue, section)
        }
        onOpLocU(TtLocs.teleport_crate, TtObjs.label) { labelCrate() }
        onLocOpLabel(locTypes, TtLocs.front_door, "Open") {
            runQuestSection(null, dialogue, "Trying to open the door to the mansion")
        }
        onLocOpLabel(locTypes, TtLocs.combo_door, "Open") { comboDoor(it) }
        onLocOpLabel(locTypes, TtLocs.stairs, "Investigate") { investigateStairs() }
        onLocOpLabel(locTypes, TtLocs.stairs, "Climb-up") { climbStairs() }
        onLocOpLabel(locTypes, TtLocs.chest, "Open") { searchChest() }
        onLocOpLabel(locTypes, TtLocs.chest_open, "Search") { searchChest() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TRIBAL_TOTEM)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(TRIBAL_TOTEM, value)
    }

    private suspend fun ProtectedAccess.talkKangai(npc: Npc) {
        when {
            state == 0 -> {
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                runQuestSection(npc, dialogue, "Talking to Kangai Mau", hooks)
            }
            else -> {
                val has = carries(TtObjs.totem)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have the totem in their inventory:" to { !has },
                                "If the player has the totem in their inventory:" to { has },
                            ),
                        boxes = mapOf("You hand over the Tribal Totem." to { invDel(inv, TtObjs.totem); false }),
                    )
                runQuestSection(npc, dialogue, "Returning back to Kangai Mau", hooks)
                if (has) {
                    statAdvance(stats.thieving, 1775.0 * xpMods.get(player, stats.thieving))
                    invAddOrDrop(objRepo, TtObjs.swordfish, SWORDFISH)
                    completeQuest(TRIBAL_TOTEM)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.investigateHandelmortCrate() {
        if (state == STARTED && !carries(TtObjs.label)) {
            runQuestSection(null, dialogue, "Investigating The Handelmorts' crate")
            invAddOrDrop(objRepo, TtObjs.label)
            return
        }
        runQuestSection(null, dialogue, "Investigating the Handelmorts' crate again")
    }

    private suspend fun ProtectedAccess.labelCrate() {
        if (state != STARTED) {
            runQuestSection(null, dialogue, "Using a second address label on the crate")
            return
        }
        invDel(inv, TtObjs.label)
        runQuestSection(null, dialogue, "Using the Address label on Cromperty's crate")
        setState(LABELLED)
    }

    private suspend fun ProtectedAccess.talkCromperty(npc: Npc) {
        var teleport = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes, that sounds good. Teleport me!" to { teleport = true }))
        runQuestSection(npc, dialogue, "Talking to Wizard Cromperty", hooks)
        if (!teleport) return
        if (state == LABELLED) {
            setState(MANSION)
            telejump(MANSION_CRATE)
        } else {
            telejump(DEPOT_CRATE)
        }
    }

    /* Inside the mansion */

    private suspend fun ProtectedAccess.comboDoor(door: BoundLocInfo) {
        val inside = player.coords.x < door.coords.x
        if (inside || state > MANSION) {
            passThroughWall(door)
            return
        }
        val dials = ArrayList<String>()
        for ((index, letters) in DIALS.withIndex()) {
            var pick = ""
            startDialogue {
                pick = choice5(letters[0], letters[0], letters[1], letters[1], letters[2], letters[2],
                    letters[3], letters[3], letters[4], letters[4], title = "Dial ${index + 1}")
            }
            dials += pick
            VarPlayerIntMapSetter.set(player, TtVarBits.dials[index], letters.indexOf(pick))
        }
        val correct = dials.joinToString("") == COMBINATION
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the combination is wrong:" to { !correct },
                        "If the combination is correct:" to { correct },
                    )
            )
        runQuestSection(null, dialogue, "Entering the door combination", hooks)
        if (correct) passThroughWall(door)
    }

    private suspend fun ProtectedAccess.investigateStairs() {
        runQuestSection(null, dialogue, "Investigating the stairs")
        disarmed += player.uid.packed
    }

    private suspend fun ProtectedAccess.climbStairs() {
        if (player.uid.packed !in disarmed) {
            runQuestSection(null, dialogue, "Climbing the stairs without investigating")
            player.queueHit(delay = 0, type = HitType.Typeless, damage = TRAP_DAMAGE)
            telejump(STAIRS_FALL)
            return
        }
        runQuestSection(null, dialogue, "Climbing the stairs after investigatiing")
        telejump(STAIRS_TOP)
    }

    private suspend fun ProtectedAccess.searchChest() {
        if (state == MANSION && !carries(TtObjs.totem)) {
            runQuestSection(null, dialogue, "Searching the chest with the Totem")
            invAddOrDrop(objRepo, TtObjs.totem)
            setState(TOTEM)
            return
        }
        runQuestSection(null, dialogue, "Searching the chest after getting the totem")
    }

    companion object {
        const val STARTED = 1
        const val LABELLED = 2
        const val MANSION = 3
        const val TOTEM = 4
        const val COMPLETE = 5

        private const val SWORDFISH = 5
        private const val TRAP_DAMAGE = 2
        private const val COMBINATION = "KURT"

        /** Each dial's letters (the right one among four others). */
        private val DIALS =
            listOf(
                listOf("A", "K", "T", "S", "B"),
                listOf("E", "O", "U", "I", "A"),
                listOf("R", "M", "N", "L", "D"),
                listOf("S", "P", "T", "H", "C"),
            )

        /** Cromperty's teleport crates (depot 2650,3271 / mansion 2638,3320) and the stairs. */
        private val DEPOT_CRATE = CoordGrid(2650, 3270, 0)
        private val MANSION_CRATE = CoordGrid(2638, 3319, 0)
        private val STAIRS_TOP = CoordGrid(2632, 3322, 1)
        private val STAIRS_FALL = CoordGrid(2631, 3320, 0)

        val TRIBAL_TOTEM =
            QuestDef(
                name = "Tribal Totem",
                varp = { TtVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,775 Thieving XP", "5 swordfish"),
                scrollObj = { TtObjs.totem },
                listRow = "quest_tribaltotem",
            )
    }
}

// Every cache ref Tribal Totem uses (re-check after cache updates).

internal object TtNpcs : NpcReferences() {
    val kangai = find("kangai_mau")
    val horacio = find("horacio")
    val employee = find("rpdt_employee")
    val crompertys = listOf(find("cromperty_pre_diary"), find("cromperty_post_diary"))
}

internal object TtLocs : LocReferences() {
    val handelmort_crate = find("horncrate")
    val teleport_crate = find("teleportcrate")
    val front_door = find("tribaltotemdoor")
    val combo_door = find("combodoor")
    val stairs = find("totemtrapstairs")
    val chest = find("totemshutchest")
    val chest_open = find("totemopenchest")
}

internal object TtObjs : ObjReferences() {
    val label = find("tribal_totem_label")
    val totem = find("tribal_totem")
    val swordfish = find("swordfish")
}

internal object TtVarps : VarpReferences() {
    val quest = find("totemquest")
}

internal object TtVarBits : VarBitReferences() {
    val dials =
        listOf(
            find("totemquest_combodoor_code1"),
            find("totemquest_combodoor_code2"),
            find("totemquest_combodoor_code3"),
            find("totemquest_combodoor_code4"),
        )
}
