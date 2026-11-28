package org.rsmod.content.quests.witchspotion

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
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
 * Witch's Potion (OSRS Wiki): Hetty in Rimmington needs an onion, an eye of newt, burnt meat and
 * a rat's tail, then the player drinks from her cauldron. `hetty` varp (cache db end state 3):
 * 0, 1 started, 2 ingredients given, 3 complete. Rats killed while the quest is started drop a
 * rat's tail (if the player has none). The transcript's "only has ..." lines for partial sets are
 * matched against what the player carries. Reward: 325 Magic xp, 1 QP.
 */
class WitchsPotion
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(WitchsPotion::class.java, "witchs_potion")
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, WitchsPotionNpcs.hetty, "Talk-to") { talk(it) }
        onLocOpLabel(locTypes, WitchsPotionLocs.cauldron, "Drink-from") { drink() }
        onEvent<NpcKilledEvent> { dropRatTail(this) }
    }

    private val ingredientNames: Map<String, () -> ObjType> =
        mapOf(
            "rat's tail" to { WitchsPotionObjs.rats_tail },
            "burnt meat" to { WitchsPotionObjs.burnt_meat },
            "onion" to { WitchsPotionObjs.onion },
            "eye of newt" to { WitchsPotionObjs.eye_of_newt },
        )

    private fun ProtectedAccess.carried(): Set<String> =
        ingredientNames.filterValues { carries(it()) }.keys

    /** "If the player only has the X, Y, and Z:" -> carries exactly those ingredients. */
    private fun ProtectedAccess.onlyHas(text: String): Boolean {
        if (!text.startsWith(ONLY_HAS)) return false
        val listed =
            text.removePrefix(ONLY_HAS).removeSuffix(":").split(", and ", ", ", " and ")
                .map { it.removePrefix("the ").trim() }
                .toSet()
        return listed == carried()
    }

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        var gave = false
        val hooks =
            SimpleQuestHooks(
                defaultCondition = { onlyHas(it) },
                options =
                    mapOf(
                        "Yes." to {
                            if (player.questState(WITCHS_POTION) == 0) {
                                player.setQuestState(WITCHS_POTION, 1)
                            }
                        },
                    ),
            )
        when (player.questState(WITCHS_POTION)) {
            0 -> runQuestSection(npc, dialogue, "Talking to Hetty", hooks)
            1 -> {
                val have = carried()
                val section =
                    when (have.size) {
                        0 -> "Talking to Hetty with none of the items"
                        ingredientNames.size -> "Talking to Hetty with all the items"
                        else -> "Talking to Hetty with some of the items"
                    }
                if (have.size == ingredientNames.size) {
                    for (obj in ingredientNames.values) invDel(inv, obj())
                    player.setQuestState(WITCHS_POTION, 2)
                    gave = true
                }
                runQuestSection(npc, dialogue, section, hooks)
            }
            2 -> runQuestSection(npc, dialogue, BEFORE_DRINKING, hooks)
            else -> startDialogue(npc) { chatNpc(happy, "How's your magic coming along?") }
        }
        if (gave) {
            mes("Hetty's cauldron is ready; drink from it to finish the quest.")
        }
    }

    private suspend fun ProtectedAccess.drink() {
        if (player.questState(WITCHS_POTION) != 2) {
            runQuestSection(null, dialogue, "Attempting to drink from the cauldron")
            return
        }
        runQuestSection(null, dialogue, "Drinking from the cauldron")
        statAdvance(stats.magic, 325.0 * xpMods.get(player, stats.magic))
        completeQuest(WITCHS_POTION)
    }

    private fun dropRatTail(event: NpcKilledEvent) {
        val player = event.hero
        if (player.questState(WITCHS_POTION) != 1) return
        if (!event.npc.name.equals("Rat", ignoreCase = true)) return
        if (WitchsPotionObjs.rats_tail in player.inv) return
        objRepo.add(WitchsPotionObjs.rats_tail, event.npc.coords, RAT_TAIL_DURATION, player)
    }

    companion object {
        private const val ONLY_HAS = "If the player only has "
        private const val BEFORE_DRINKING =
            "Talking to Hetty again before drinking from the cauldron"
        private const val RAT_TAIL_DURATION = 200

        val WITCHS_POTION =
            QuestDef(
                name = "Witch's Potion",
                varp = { WitchsPotionVarps.hetty },
                complete = 3,
                questPoints = 1,
                rewards = listOf("325 Magic XP"),
                scrollObj = { WitchsPotionObjs.eye_of_newt },
            )
    }
}

// Every cache ref Witch's Potion uses (re-check after the rev 243 cutover).

internal object WitchsPotionNpcs : NpcReferences() {
    val hetty = find("hetty")
}

internal object WitchsPotionLocs : LocReferences() {
    val cauldron = find("hettycauldron")
}

internal object WitchsPotionObjs : ObjReferences() {
    val onion = find("onion")
    val eye_of_newt = find("eye_of_newt")
    val burnt_meat = find("burnt_meat")
    val rats_tail = find("rats_tail")
}

internal object WitchsPotionVarps : VarpReferences() {
    val hetty = find("hetty")
}
