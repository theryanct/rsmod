package org.rsmod.content.quests.magearena

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The way to the Mage Arena (OSRS Wiki), for Mage Arena I:
 * - the Edgeville and Ardougne levers to the Deserted Keep, and the Keep's lever back;
 * - the slashable webs (a knife or a wielded weapon; they grow back);
 * - the ruins' lever down to the Mage Arena bank and back up, and the arena's levers.
 *
 * Simplified (Authored, not Wiki): the Keep's lever asks where to go (OSRS sends you back the way
 * you came); a web slash works half the time with any wielded weapon; the arrival tiles are best
 * guesses (Ryan-test).
 */
class WildernessAccess
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val locRepo: LocRepository,
    private val random: GameRandom,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MageArena::class.java, "mage_arena_1") }

    override fun ScriptContext.startup() {
        onLocOpLabel(locTypes, WaLocs.edgeville_lever, "Pull") { pullLever(KEEP) }
        onLocOpLabel(locTypes, WaLocs.ardougne_lever, "Pull") { pullLever(KEEP) }
        onLocOpLabel(locTypes, WaLocs.keep_lever, "Pull") { pullKeepLever() }
        for (lever in WaLocs.keep_diary_levers) {
            onLocOpLabel(locTypes, lever, "Edgeville") { pullLever(EDGEVILLE) }
            onLocOpLabel(locTypes, lever, "Ardougne") { pullLever(ARDOUGNE) }
        }
        for ((web, slashed) in WaLocs.webs) onLocOpLabel(locTypes, web, "Slash") { slashWeb(it, slashed) }
        onLocOpLabel(locTypes, WaLocs.ruins_lever, "Pull") { pullLever(BANK) }
        onLocOpLabel(locTypes, WaLocs.bank_lever, "Pull") { pullLever(RUINS) }
        onLocOpLabel(locTypes, WaLocs.arena_lever_in, "Pull") { pullArenaLever() }
        onLocOpLabel(locTypes, WaLocs.arena_lever_out, "Pull") { pullLever(ARENA_OUTSIDE) }
    }

    private suspend fun ProtectedAccess.pullLever(dest: CoordGrid) {
        // Authored lines (not Wiki).
        mes("You pull the lever...")
        delay(LEVER_DELAY)
        telejump(dest)
        mes("...and are teleported away.")
    }

    private suspend fun ProtectedAccess.pullKeepLever() {
        // Authored (not Wiki): a choice of destinations.
        when (menu("Where would you like to go?", "Edgeville", "Ardougne")) {
            0 -> pullLever(EDGEVILLE)
            1 -> pullLever(ARDOUGNE)
        }
    }

    private suspend fun ProtectedAccess.pullArenaLever() {
        if (player.questState(MageArena.MAGE_ARENA_I) < MageArena.DEFEATED) {
            val speakers = mapOf("Kolodion" to npcTypes[MaNpcs.kolodion])
            runQuestSection(null, dialogue, LEVER_EARLY, SimpleQuestHooks(), speakers)
            return
        }
        pullLever(ARENA_INSIDE)
    }

    private fun ProtectedAccess.slashWeb(web: BoundLocInfo, slashed: LocType) {
        val weapon = worn.any { it != null } || carries(WaObjs.knife)
        if (!weapon) {
            mes("Only a sharp blade can cut through this sticky web.")
            return
        }
        if (!random.randomBoolean(SLASH_CHANCE)) {
            mes("You fail to cut through it.")
            return
        }
        locRepo.change(web, slashed, WEB_TICKS)
        mes("You slash the web apart.")
    }

    private companion object {
        const val LEVER_DELAY = 2
        const val SLASH_CHANCE = 2
        const val WEB_TICKS = 100
        const val LEVER_EARLY = "Pulling the Mage Arena lever before starting Mage Arena I"

        val KEEP = CoordGrid(3154, 3924, 0)
        val EDGEVILLE = CoordGrid(3090, 3476, 0)
        val ARDOUGNE = CoordGrid(2562, 3311, 0)
        val BANK = CoordGrid(2538, 4714, 0)
        val RUINS = CoordGrid(3091, 3956, 0)
        val ARENA_INSIDE = CoordGrid(3105, 3945, 0)
        val ARENA_OUTSIDE = CoordGrid(3104, 3957, 0)
    }
}

internal object WaLocs : LocReferences() {
    val edgeville_lever = find("edgeville_wildy_lever")
    val ardougne_lever = find("wildinlever")
    /** The Deserted Keep lever's children (wildoutlever; the diary ones offer both towns). */
    val keep_lever = find("wildoutlever_default")
    val keep_diary_levers = listOf(find("wildoutlever_diary"), find("wildoutlever_diary_swapped"))
    /** Each slashable web with its slashed form. */
    val webs =
        listOf(
            find("bigweb_slashable") to find("bigweb_slashed"),
            find("bigweb_slashable_shape1") to find("bigweb_slashed_shape1"),
        )
    /** In the ruins north of the arena (3090,3956): down to the Mage Arena bank. */
    val ruins_lever = find("magearena_lever_to_cellar")
    val bank_lever = find("magearena_lever_from_cellar")
    val arena_lever_in = find("magearena_lever_in")
    val arena_lever_out = find("magearena_lever_out")
}

internal object WaObjs : ObjReferences() {
    val knife = find("knife")
}
