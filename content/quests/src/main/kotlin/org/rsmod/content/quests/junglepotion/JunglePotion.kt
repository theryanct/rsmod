package org.rsmod.content.quests.junglepotion

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
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
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Jungle Potion (OSRS Wiki). `junglepotion` varp (cache db end state 12); ours: Trufitus asks for
 * one herb at a time, so states 1, 3, 5, 7, 9 each want the next herb (snake weed, ardrigal, sito
 * foil, volencia moss, rogue's purse) and handing it over moves two on; 12 complete (775 Herblore
 * xp, 1 QP).
 *
 * Each herb grows on its own searchable loc around Tai Bwo Wannai (the rogue's purse inside the
 * Pothole Dungeon behind the rocks north-east of the village) and must be cleaned (Herblore 3);
 * the quest cleans its own five grimy herbs unless Herblore already does.
 */
class JunglePotion
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(JunglePotion::class.java, "jungle_potion") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, JpNpcs.trufitus, "Talk-to") { talkTrufitus(it) }
        for ((index, herb) in HERBS.withIndex()) {
            onLocOpLabel(locTypes, herb.full(), "Search") { searchHerb(it, index) }
            onHeldOpLabel(objTypes, herb.grimy(), "Clean") { clean(herb) }
        }
        onLocOpLabel(locTypes, JpLocs.pothole_rocks, "Search") { searchRocks() }
        onLocOpLabel(locTypes, JpLocs.cave_exit, "Climb") { telejump(POTHOLE_OUTSIDE) }
    }

    private class Herb(
        val name: String,
        val full: () -> LocType,
        val empty: () -> LocType,
        val grimy: () -> ObjType,
        val clean: () -> ObjType,
        val search: String,
    )

    private val ProtectedAccess.state: Int
        get() = player.questState(JUNGLE_POTION)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(JUNGLE_POTION, value)
    }

    /** The herb Trufitus currently wants (0..4), or -1. */
    private val ProtectedAccess.wanted: Int
        get() = if (state in STARTED..LAST_HERB_STATE && state % 2 == 1) state / 2 else -1

    private suspend fun ProtectedAccess.talkTrufitus(npc: Npc) {
        if (state == 0) {
            val hooks =
                SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
            runQuestSection(npc, dialogue, "Starting off", hooks)
            return
        }
        val index = wanted
        if (index < 0) {
            startDialogue(npc) {
                chatNpc(happy, "Thank you again, Bwana. The gods have shown us the way home.")
            }
            return
        }
        val herb = HERBS[index]
        val hasClean = carries(herb.clean())
        val hasGrimy = carries(herb.grimy())
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have any ${herb.name}:" to {
                            !hasClean && !hasGrimy
                        },
                        "If the player has no ${herb.name}:" to { !hasClean && !hasGrimy },
                        "If the player has grimy ${herb.name}:" to { !hasClean && hasGrimy },
                        "If the player has ${herb.name}:" to { hasClean },
                        "If the player has snake weed, but obtained from a monster drop:" to {
                            false
                        },
                    ),
                boxes =
                    mapOf(
                        GIVE_BOXES[index] to {
                            invDel(inv, herb.clean())
                            false
                        }
                    ),
            )
        val section =
            if (index == 0) "Talking to Trufitus" else "Talking to Trufitus (${index + 1})"
        runQuestSection(npc, dialogue, section, hooks)
        if (!hasClean) return
        if (index == HERBS.lastIndex) {
            statAdvance(stats.herblore, REWARD_XP * xpMods.get(player, stats.herblore))
            completeQuest(JUNGLE_POTION)
        } else {
            setState(state + 2)
        }
    }

    private suspend fun ProtectedAccess.searchHerb(loc: BoundLocInfo, index: Int) {
        val herb = HERBS[index]
        if (wanted != index || carries(herb.grimy()) || carries(herb.clean())) {
            mes("You search but find nothing of interest.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        runQuestSection(null, dialogue, herb.search)
        invAdd(inv, herb.grimy())
        locRepo.change(loc, herb.empty(), REGROW_TICKS)
    }

    private suspend fun ProtectedAccess.clean(herb: Herb) {
        if (statBase(stats.herblore) < CLEAN_LEVEL) {
            mes("You need a Herblore level of $CLEAN_LEVEL to clean this herb.")
            return
        }
        invDel(inv, herb.grimy())
        invAdd(inv, herb.clean())
        statAdvance(stats.herblore, CLEAN_XP * xpMods.get(player, stats.herblore))
        mes("You clean the dirt from the ${herb.name}.")
    }

    private suspend fun ProtectedAccess.searchRocks() {
        var enter = false
        val hooks =
            SimpleQuestHooks(options = mapOf("Yes, I'll enter the cave." to { enter = true }))
        runQuestSection(null, dialogue, "Search Rocks", hooks)
        if (enter) telejump(POTHOLE_INSIDE)
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 12
        private const val LAST_HERB_STATE = 9

        private const val REWARD_XP = 775.0
        private const val CLEAN_LEVEL = 3
        private const val CLEAN_XP = 2.5
        private const val REGROW_TICKS = 100

        private val POTHOLE_OUTSIDE = CoordGrid(2824, 3119, 0)
        private val POTHOLE_INSIDE = CoordGrid(2830, 9520, 0)

        private val GIVE_BOXES =
            listOf(
                "You give the Snake Weed to Trufitus.",
                "You give the Ardrigal to Trufitus.",
                "You give the Sito Foil to Trufitus.",
                "You give the Volencia Moss to Trufitus.",
                "You give the Rogue's Pursee to Trufitus.",
            )

        private val HERBS =
            listOf(
                Herb(
                    "snake weed",
                    { JpLocs.snake_vine },
                    { JpLocs.snake_vine_empty },
                    { JpObjs.grimy_snake_weed },
                    { JpObjs.snake_weed },
                    "Searching the Marshy jungle vine",
                ),
                Herb(
                    "ardrigal",
                    { JpLocs.palm },
                    { JpLocs.palm_empty },
                    { JpObjs.grimy_ardrigal },
                    { JpObjs.ardrigal },
                    "Search palm tree",
                ),
                Herb(
                    "sito foil",
                    { JpLocs.scorched },
                    { JpLocs.scorched_empty },
                    { JpObjs.grimy_sito_foil },
                    { JpObjs.sito_foil },
                    "Searching the Scorched earth",
                ),
                Herb(
                    "volencia moss",
                    { JpLocs.moss_rock },
                    { JpLocs.moss_rock_empty },
                    { JpObjs.grimy_volencia_moss },
                    { JpObjs.volencia_moss },
                    "Searching the rock",
                ),
                Herb(
                    "rogue's purse",
                    { JpLocs.fungus_wall },
                    { JpLocs.fungus_wall_empty },
                    { JpObjs.grimy_rogues_purse },
                    { JpObjs.rogues_purse },
                    "Search Fungus covered Cavern wall",
                ),
            )

        val JUNGLE_POTION =
            QuestDef(
                name = "Jungle Potion",
                varp = { JpVarps.junglepotion },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("775 Herblore XP"),
                scrollObj = { JpObjs.snake_weed },
            )
    }
}

// Every cache ref Jungle Potion uses (re-check after cache updates).

internal object JpNpcs : NpcReferences() {
    val trufitus = find("trufitus")
}

internal object JpLocs : LocReferences() {
    val snake_vine = find("snake_vine_full")
    val snake_vine_empty = find("snake_vine_empty")
    val palm = find("ardrigal_palm_full")
    val palm_empty = find("ardrigal_palm_empty")
    val scorched = find("sito_soil_full")
    val scorched_empty = find("sito_soil_empty")
    val moss_rock = find("volencia_moss_rock_full")
    val moss_rock_empty = find("volencia_moss_rock_empty")
    val fungus_wall = find("rogues_purse_cave_full")
    val fungus_wall_empty = find("rogues_purse_cave_empty")
    val pothole_rocks = find("pothole_cave_entrance")
    val cave_exit = find("jp_caverocksout")
}

internal object JpObjs : ObjReferences() {
    val grimy_snake_weed = find("unidentified_snake_weed")
    val snake_weed = find("snake_weed")
    val grimy_ardrigal = find("unidentified_ardrigal")
    val ardrigal = find("ardrigal")
    val grimy_sito_foil = find("unidentified_sito_foil")
    val sito_foil = find("sito_foil")
    val grimy_volencia_moss = find("unidentified_volencia_moss")
    val volencia_moss = find("volencia_moss")
    val grimy_rogues_purse = find("unidentified_rogues_purse")
    val rogues_purse = find("rogues_purse")
}

internal object JpVarps : VarpReferences() {
    val junglepotion = find("junglepotion")
}
