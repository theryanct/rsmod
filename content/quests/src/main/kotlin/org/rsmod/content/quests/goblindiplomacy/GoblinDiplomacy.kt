package org.rsmod.content.quests.goblindiplomacy

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Goblin Diplomacy (OSRS Wiki): Generals Wartface and Bentnoze argue over their armour colour; the
 * player shows them orange, then blue, then plain (brown) goblin mail. `goblinquest` varp (cache
 * db end state 6): 0, 1 started, 3 orange shown, 4 blue shown, 6 complete. Goblin mail comes from
 * the Goblin Village crates; orange dye = red + yellow dye; dye on goblin mail colours it.
 * Reward: gold bar, 200 Crafting xp, 5 QP.
 *
 * The dyes come from Aggie (see `aggie/Aggie.kt`).
 */
class GoblinDiplomacy
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(GoblinDiplomacy::class.java, "goblin_diplomacy")
    }

    override fun ScriptContext.startup() {
        for (general in GoblinDiplomacyNpcs.generals) {
            with(QuestTalk) { default(npcTypes, general) { talkGeneral(it) } }
            for (armour in GoblinDiplomacyObjs.armours) {
                onOpNpcU(general, armour) { showArmour(it.npc, it.objType.id) }
            }
        }
        for (crate in GoblinDiplomacyLocs.crates) {
            onLocOpLabel(locTypes, crate, "Search") { searchCrate() }
        }
        onOpHeldU(GoblinDiplomacyObjs.red_dye, GoblinDiplomacyObjs.yellow_dye) { mixOrange() }
        onOpHeldU(GoblinDiplomacyObjs.orange_dye, GoblinDiplomacyObjs.goblin_armour) {
            dye(GoblinDiplomacyObjs.orange_dye, GoblinDiplomacyObjs.armour_orange)
        }
        onOpHeldU(GoblinDiplomacyObjs.blue_dye, GoblinDiplomacyObjs.goblin_armour) {
            dye(GoblinDiplomacyObjs.blue_dye, GoblinDiplomacyObjs.armour_blue)
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(GOBLIN_DIPLOMACY)

    private suspend fun ProtectedAccess.talkGeneral(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes." to {
                            if (state == 0) player.setQuestState(GOBLIN_DIPLOMACY, STARTED)
                        },
                    ),
            )
        when (state) {
            0 -> runQuestSection(npc, dialogue, FIRST_TALK, hooks)
            in STARTED until COMPLETE ->
                runQuestSection(npc, dialogue, "Talking to the Generals again", hooks)
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Brown armour best armour! Thank you human.")
                }
        }
    }

    private suspend fun ProtectedAccess.showArmour(npc: Npc, armourId: Int) {
        val (section, wanted, next) =
            when (state) {
                STARTED ->
                    Triple(
                        "Orange-painted Goblin Armour",
                        GoblinDiplomacyObjs.armour_orange,
                        ORANGE,
                    )
                ORANGE ->
                    Triple("Blue-painted Goblin Armour", GoblinDiplomacyObjs.armour_blue, BLUE)
                BLUE ->
                    Triple(BROWN_SECTION, GoblinDiplomacyObjs.goblin_armour, COMPLETE)
                else -> return
            }
        val right = armourId == wanted.id
        val hooks =
            SimpleQuestHooks(
                defaultCondition = { text ->
                    when {
                        text.contains("uses orange dye") || text.contains("uses blue dye") -> false
                        text.contains("uses a regular or non-") -> !right
                        text.contains("uses a painted goblin armour") -> !right
                        text.contains("uses orange-painted") -> right
                        text.contains("uses blue-painted") -> right
                        text.contains("uses regular goblin armour") -> right
                        else -> false
                    }
                },
            )
        runQuestSection(npc, dialogue, section, hooks)
        if (!right) return
        invDel(inv, objTypeOf(armourId) ?: return)
        if (next == COMPLETE) {
            invAddOrDrop(objRepo, GoblinDiplomacyObjs.gold_bar)
            statAdvance(stats.crafting, 200.0 * xpMods.get(player, stats.crafting))
            completeQuest(GOBLIN_DIPLOMACY)
        } else {
            player.setQuestState(GOBLIN_DIPLOMACY, next)
        }
    }

    private fun objTypeOf(id: Int): ObjType? =
        GoblinDiplomacyObjs.armours.firstOrNull { it.id == id }

    private suspend fun ProtectedAccess.searchCrate() {
        if (state !in STARTED until COMPLETE || GoblinDiplomacyObjs.armours.any { carries(it) }) {
            runQuestSection(null, dialogue, "Searching the same crate again")
            return
        }
        invAddOrDrop(objRepo, GoblinDiplomacyObjs.goblin_armour)
        runQuestSection(null, dialogue, "Searching crates with goblin armour")
    }

    private fun ProtectedAccess.mixOrange() {
        invDel(inv, GoblinDiplomacyObjs.red_dye)
        invDel(inv, GoblinDiplomacyObjs.yellow_dye)
        invAdd(inv, GoblinDiplomacyObjs.orange_dye)
        mes("You mix the two dyes and make an orange dye.")
    }

    private fun ProtectedAccess.dye(dye: ObjType, result: ObjType) {
        invDel(inv, dye)
        invDel(inv, GoblinDiplomacyObjs.goblin_armour)
        invAdd(inv, result)
        mes("You dye the goblin armour.")
    }

    companion object {
        const val STARTED = 1
        const val ORANGE = 3
        const val BLUE = 4
        const val COMPLETE = 6
        private const val FIRST_TALK = "Talking to General Wartface or General Bentnoze"
        private const val BROWN_SECTION = "\"Brown\"-painted Goblin Armour"

        val GOBLIN_DIPLOMACY =
            QuestDef(
                name = "Goblin Diplomacy",
                varp = { GoblinDiplomacyVarps.goblinquest },
                complete = COMPLETE,
                questPoints = 5,
                rewards = listOf("Gold bar", "200 Crafting XP"),
                scrollObj = { GoblinDiplomacyObjs.goblin_armour },
            )
    }
}

// Every cache ref Goblin Diplomacy uses (re-check after the rev 243 cutover).

internal object GoblinDiplomacyNpcs : NpcReferences() {
    val generals = listOf(find("general_wartface_green"), find("general_bentnoze_red"))
}

internal object GoblinDiplomacyLocs : LocReferences() {
    val crates =
        listOf(
            find("goblin_outpost_large_crate_armour1"),
            find("goblin_outpost_large_crate_armour2"),
            find("goblin_outpost_large_crate_armour3"),
        )
}

internal object GoblinDiplomacyObjs : ObjReferences() {
    val goblin_armour = find("goblin_armour")
    val armour_orange = find("goblin_armour_orange")
    val armour_blue = find("goblin_armour_darkblue")
    val red_dye = find("reddye")
    val yellow_dye = find("yellowdye")
    val blue_dye = find("bluedye")
    val orange_dye = find("orangedye")
    val gold_bar = find("gold_bar")

    val armours = listOf(goblin_armour, armour_orange, armour_blue)
}

internal object GoblinDiplomacyVarps : VarpReferences() {
    val goblinquest = find("goblinquest")
}
