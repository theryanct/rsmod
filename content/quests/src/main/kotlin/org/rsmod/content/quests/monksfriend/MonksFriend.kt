package org.rsmod.content.quests.monksfriend

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Monk's Friend (OSRS Wiki). `drunkmonkquest` varp (cache db end state 80; no cache multis use
 * it, so the steps in between are ours): 10 Brother Omad's stolen blanket (already a ground spawn
 * in the thieves' hideout under the stone circle west of the monastery), 20 blanket returned,
 * 30 sent to find Brother Cedric, 40 found him (wants water), 50 water given (cart trouble),
 * 60 agreed to help (wants logs or a plank), 70 cart fixed, 80 complete (the party: 8 law runes,
 * 2,000 Woodcutting xp, 1 QP). The dancing cutscene plays as its transcript lines.
 */
class MonksFriend
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MonksFriend::class.java, "monks_friend") }

    private val speakers: Map<String, NpcType> by lazy { mapOf("Monk" to MonksNpcs.monk) }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, MonksNpcs.omad, "Talk-to") { talkOmad(it) }
        onNpcOpLabel(npcTypes, MonksNpcs.cedric, "Talk-to") { talkCedric(it) }
        onOpNpcU(MonksNpcs.cedric, MonksObjs.jug_water) { talkCedric(it.npc) }
        onOpNpcU(MonksNpcs.omad, MonksObjs.blanket) { talkOmad(it.npc) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(MONKS_FRIEND)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(MONKS_FRIEND, value)
    }

    private suspend fun ProtectedAccess.talkOmad(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Yes." to { if (state == 0) setState(BLANKET) }),
                boxes =
                    mapOf(
                        "You hand the monk the childs blanket." to {
                            if (carries(MonksObjs.blanket)) {
                                invDel(inv, MonksObjs.blanket)
                                setState(RETURNED)
                            }
                            false
                        },
                    ),
                rewrites = { it.removeSuffix(" Here is where he actually takes the blanket.") },
            )
        when {
            state == 0 -> runQuestSection(npc, dialogue, "Talking to Brother Omad", hooks)
            state == BLANKET && carries(MonksObjs.blanket) ->
                runQuestSection(npc, dialogue, OMAD_BLANKET, hooks)
            state == BLANKET -> runQuestSection(npc, dialogue, OMAD_NO_BLANKET, hooks)
            state == RETURNED -> {
                runQuestSection(npc, dialogue, "Talking to Brother Omad again", hooks)
                setState(FIND_CEDRIC)
            }
            state == FIND_CEDRIC ->
                runQuestSection(npc, dialogue, OMAD_SEARCHING)
            state == FOUND_CEDRIC ->
                runQuestSection(npc, dialogue, OMAD_FOUND)
            state in WATERED until CART_FIXED -> runQuestSection(npc, dialogue, OMAD_AFTER_WATER)
            state == CART_FIXED -> party(npc)
            else -> runQuestSection(npc, dialogue, "Talking to Brother Omad (2)")
        }
    }

    private suspend fun ProtectedAccess.party(npc: Npc) {
        runQuestSection(npc, dialogue, OMAD_FINISH)
        runQuestSection(npc, dialogue, "Dancing cutscene", speakers = speakers)
        invAddOrDrop(objRepo, MonksObjs.law_rune, LAW_RUNES)
        statAdvance(stats.woodcutting, WOODCUTTING_XP * xpMods.get(player, stats.woodcutting))
        completeQuest(MONKS_FRIEND)
    }

    private suspend fun ProtectedAccess.talkCedric(npc: Npc) {
        var helping = false
        val wood = listOf(MonksObjs.logs, MonksObjs.plank).firstOrNull { carries(it) }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has logs:" to { wood == MonksObjs.logs },
                        "If the player has a plank:" to { wood == MonksObjs.plank },
                    ),
                options = mapOf("Yes, I'd be happy to!" to { helping = true }),
                boxes =
                    mapOf(
                        "You hand the monk a jug of water." to {
                            invDel(inv, MonksObjs.jug_water)
                            invAdd(inv, MonksObjs.jug)
                            false
                        },
                    ),
            )
        val section =
            when {
                state < FIND_CEDRIC -> "Talking to Brother Cedric before agreeing to find him"
                state == FIND_CEDRIC -> "Talking to Brother Cedric"
                state == FOUND_CEDRIC && carries(MonksObjs.jug_water) -> CEDRIC_WATER
                state == FOUND_CEDRIC -> "Talking to Brother Cedric before getting water"
                state == WATERED -> CEDRIC_NOT_AGREED
                state == AGREED && wood != null -> CEDRIC_WOOD
                state == AGREED -> "Talking to Brother Cedric before getting some wood"
                state == CART_FIXED -> "Talking to Brother Cedric again before returning"
                else -> "Brother Cedric"
            }
        runQuestSection(npc, dialogue, section, hooks)
        when {
            state == FIND_CEDRIC -> setState(FOUND_CEDRIC)
            section == CEDRIC_WATER -> setState(if (helping) AGREED else WATERED)
            state == WATERED && helping -> setState(AGREED)
            state == AGREED && wood != null -> {
                invDel(inv, wood)
                setState(CART_FIXED)
            }
        }
    }

    companion object {
        const val BLANKET = 10
        const val RETURNED = 20
        const val FIND_CEDRIC = 30
        const val FOUND_CEDRIC = 40
        const val WATERED = 50
        const val AGREED = 60
        const val CART_FIXED = 70
        const val COMPLETE = 80

        private const val LAW_RUNES = 8
        private const val WOODCUTTING_XP = 2000.0

        private const val OMAD_BLANKET = "Talking to Brother Omad after retrieving the blanket"
        private const val OMAD_NO_BLANKET = "Talking to brother Omad before retrieving the blanket"
        private const val OMAD_AFTER_WATER =
            "Talking to Brother Omad after giving Brother Cedric water"
        private const val OMAD_SEARCHING = "Talking to Brother Omad before finding Brother Cedric"
        private const val OMAD_FOUND = "Talking to Brother Omad after finding Brother Cedric"
        private const val CEDRIC_WATER = "Talking to Brother Cedric with water"
        private const val CEDRIC_WOOD = "Talking to Brother Cedric while holding some wood"
        private const val OMAD_FINISH =
            "Talking to Brother Omad after finding and helping Brother Cedric"
        private const val CEDRIC_NOT_AGREED =
            "Talking to Brother Cedric after giving him water before accepting to help with the " +
                "cart"

        val MONKS_FRIEND =
            QuestDef(
                name = "Monk's Friend",
                varp = { MonksVarps.drunkmonkquest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("8 Law runes", "2,000 Woodcutting XP"),
                scrollObj = { MonksObjs.blanket },
            )
    }
}

// Every cache ref Monk's Friend uses (re-check after the rev 243 cutover).

internal object MonksNpcs : NpcReferences() {
    val omad = find("brother_omad")
    val cedric = find("brother_cedric")
    val monk = find("monk_ardougne")
}

internal object MonksObjs : ObjReferences() {
    val blanket = find("childs_blanket")
    val jug_water = find("jug_water")
    val jug = find("jug_empty")
    val logs = find("logs")
    val plank = find("woodplank")
    val law_rune = find("lawrune")
}

internal object MonksVarps : VarpReferences() {
    val drunkmonkquest = find("drunkmonkquest")
}
