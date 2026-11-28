package org.rsmod.content.quests.restlessghost

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.front
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
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
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Restless Ghost (OSRS Wiki). `prieststart` varp (cache db end state 5): 0, 1 started (Father
 * Aereck), 2 has the Amulet of Ghostspeak (Father Urhney), 3 spoke to the ghost wearing it, 4 has
 * the skull (Wizards' Tower basement altar), 5 complete (skull put in the opened coffin).
 * The coffin opens/closes in place; the altar's skull shows through its cache multiloc varbit.
 * Taking the skull spawns the skeleton that attacks. Reward: 1125 Prayer xp, 1 QP.
 */
class RestlessGhost
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val npcRepo: NpcRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(RestlessGhost::class.java, "restless_ghost")
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) { default(npcTypes, RestlessGhostNpcs.aereck) { talkAereck(it) } }
        onNpcOpLabel(npcTypes, RestlessGhostNpcs.urhney, "Talk-to") { talkUrhney(it) }
        onNpcOpLabel(npcTypes, RestlessGhostNpcs.ghost, "Talk-to") { talkGhost(it) }
        onLocOpLabel(locTypes, RestlessGhostLocs.coffin_shut, "Open") { openCoffin(it) }
        for (open in RestlessGhostLocs.coffins_open) {
            onLocOpLabel(locTypes, open, "Search") { searchCoffin() }
            onLocOpLabel(locTypes, open, "Close") { closeCoffin(it) }
            onOpLocU(open, RestlessGhostObjs.skull) { skullInCoffin() }
        }
        onOpLocU(RestlessGhostLocs.coffin_shut, RestlessGhostObjs.skull) {
            runQuestSection(null, dialogue, "Using the skull on the closed coffin")
        }
        for (altar in RestlessGhostLocs.altars) {
            onLocOpLabel(locTypes, altar, "Search") { searchAltar() }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(RESTLESS_GHOST)

    private fun ProtectedAccess.wearsAmulet(): Boolean =
        player.front?.id == RestlessGhostObjs.amulet.id

    private suspend fun ProtectedAccess.talkAereck(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes" to {
                            if (state == 0) player.setQuestState(RESTLESS_GHOST, STARTED)
                        },
                    ),
            )
        val section =
            when (state) {
                0 -> "Starting out"
                STARTED -> "Talking to Father Aereck again"
                HAS_SKULL -> "Talking to Father Aereck with the skull"
                in HAS_AMULET..SPOKE_TO_GHOST -> "Talking to Father Aereck"
                else -> null
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(happy, "Thank you for laying that poor spirit to rest.") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
    }

    private suspend fun ProtectedAccess.talkUrhney(npc: Npc) {
        val amulet = RestlessGhostObjs.amulet
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If they player currently has it in their inventory:" to
                            { carries(amulet) || wearsAmulet() },
                        "If the player does not have inventory space:" to { inv.isFull() },
                        "If the player has inventory space:" to { !inv.isFull() },
                    ),
                boxes =
                    mapOf(
                        "Father Urhney hands you an amulet." to {
                            if (!carries(amulet) && !wearsAmulet()) {
                                invAddOrDrop(objRepo, amulet)
                            }
                            if (state == STARTED) player.setQuestState(RESTLESS_GHOST, HAS_AMULET)
                            false
                        },
                    ),
            )
        val section =
            when {
                state == STARTED -> "Talking to Father Urhney"
                state in HAS_AMULET until COMPLETE -> "If the ghostspeak amulet is ever lost"
                else -> "Talking to Father Urhney"
            }
        runQuestSection(npc, dialogue, section, hooks)
    }

    private suspend fun ProtectedAccess.talkGhost(npc: Npc) {
        val wearing = wearsAmulet()
        val section =
            when {
                state < HAS_AMULET || !wearing -> "Without wearing the Amulet of Ghostspeak"
                state == HAS_AMULET -> "While wearing the Amulet of Ghostspeak"
                state == SPOKE_TO_GHOST ->
                    "Talking to the Restless ghost again with the amulet of ghostspeak"
                state == HAS_SKULL -> "Talking to the Restless ghost (2)"
                else -> "While wearing the Amulet of Ghostspeak"
            }
        runQuestSection(npc, dialogue, section)
        if (state == HAS_AMULET && wearing) {
            player.setQuestState(RESTLESS_GHOST, SPOKE_TO_GHOST)
        }
    }

    private fun ProtectedAccess.openCoffin(coffin: BoundLocInfo) {
        locRepo.change(coffin, RestlessGhostLocs.coffin_open, COFFIN_OPEN_TICKS)
        mes("You open the coffin.")
    }

    private fun ProtectedAccess.closeCoffin(coffin: BoundLocInfo) {
        locRepo.change(coffin, RestlessGhostLocs.coffin_shut, COFFIN_OPEN_TICKS)
        mes("You close the coffin.")
    }

    private suspend fun ProtectedAccess.searchCoffin() {
        if (state >= COMPLETE) {
            runQuestSection(null, dialogue, "Searching the coffin after the quest")
        } else {
            runQuestSection(null, dialogue, "Searching the coffin")
        }
    }

    private suspend fun ProtectedAccess.skullInCoffin() {
        if (state != HAS_SKULL || !carries(RestlessGhostObjs.skull)) return
        invDel(inv, RestlessGhostObjs.skull)
        runQuestSection(null, dialogue, "Putting the skull in the coffin", speakers = mapOf("Restless ghost" to RestlessGhostNpcs.ghost))
        statAdvance(stats.prayer, 1125.0 * xpMods.get(player, stats.prayer))
        completeQuest(RESTLESS_GHOST)
    }

    private suspend fun ProtectedAccess.searchAltar() {
        when {
            state >= COMPLETE ->
                runQuestSection(null, dialogue, "Searching the altar after the quest")
            carries(RestlessGhostObjs.skull) ->
                runQuestSection(null, dialogue, "Searching the altar (2)")
            state == SPOKE_TO_GHOST || state == HAS_SKULL -> {
                invAddOrDrop(objRepo, RestlessGhostObjs.skull)
                VarPlayerIntMapSetter.set(player, RestlessGhostVarBits.altar, 1)
                player.setQuestState(RESTLESS_GHOST, HAS_SKULL)
                mes("You find a skull on the altar and take it.")
                spawnSkeleton()
            }
            else -> mes("You search the altar but find nothing of interest.")
        }
    }

    /** OSRS: a skeleton (level 13) appears and attacks when the skull is taken. */
    private fun ProtectedAccess.spawnSkeleton() {
        val skeleton = Npc(npcTypes[RestlessGhostNpcs.skeleton], player.coords)
        npcRepo.add(skeleton, SKELETON_TICKS)
        skeleton.opPlayer2(player, aiInteractions)
        mes("A skeleton appears!")
    }

    companion object {
        private const val SKELETON_TICKS = 500
        const val STARTED = 1
        const val HAS_AMULET = 2
        const val SPOKE_TO_GHOST = 3
        const val HAS_SKULL = 4
        const val COMPLETE = 5
        private const val COFFIN_OPEN_TICKS = 300

        val RESTLESS_GHOST =
            QuestDef(
                name = "The Restless Ghost",
                listRow = "quest_restlessghost",
                varp = { RestlessGhostVarps.prieststart },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("1,125 Prayer XP"),
                scrollObj = { RestlessGhostObjs.skull },
            )
    }
}

// Every cache ref The Restless Ghost uses (re-check after the rev 243 cutover).

internal object RestlessGhostNpcs : NpcReferences() {
    val aereck = find("father_aereck")
    val urhney = find("father_urhney")
    val ghost = find("ghostx")
    val skeleton = find("skull_skeleton")
}

internal object RestlessGhostLocs : LocReferences() {
    val coffin_shut = find("shutghostcoffin")
    val coffin_open = find("openghostcoffin")
    val coffins_open =
        listOf(
            find("openghostcoffin"),
            find("openghostcoffin_no_head"),
            find("openghostcoffin_with_head"),
        )
    val altars =
        listOf(
            find("restless_ghost_altar_skull"),
            find("restless_ghost_altar_no_skull"),
        )
}

internal object RestlessGhostObjs : ObjReferences() {
    val amulet = find("amulet_of_ghostspeak")
    val skull = find("ghostskull")
}

internal object RestlessGhostVarps : VarpReferences() {
    val prieststart = find("prieststart")
}

internal object RestlessGhostVarBits : VarBitReferences() {
    val altar = find("restless_ghost_altar_var")
}
