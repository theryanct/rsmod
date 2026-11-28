package org.rsmod.content.quests.spiritofadventure

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Spirit of Adventure (OSRS Wiki miniquest, Auburn Valley / the Proudspire). An unlisted
 * miniquest: no quest-list dbrow, quest points, rewards or scroll, so no
 * [org.rsmod.content.quests.framework.QuestDef] or BuiltQuests entry; the three Talk-tos are
 * QuestTalk defaults.
 *
 * State is the cache varbit `spiritofadventure` (on `soc_main`), which the client reads through
 * the multinpcs (Bernina on the coast 0-3, on the mountain at 4; Bernard on the path 2-5, at the
 * summit at 6). Our stages:
 * - 1 Bernina asked for her necklace; 2 Jasper sold it to a climber (Bernard appears);
 *   `spiritofadventure_mountain_hint` Bernina pointed at the Proudspire,
 *   `spiritofadventure_fire_hint` Bernard asked for a fire.
 * - 3 a fire (the Firemaking module's `fire` loc) is burning next to Bernard and he named his
 *   mother; 4 Bernina went up the mountain; 5 she has faded away; 6 Bernard is at the summit
 *   (the end state: there is nothing left to do but talk to him there).
 *
 * Simplified: Bernard heads for the summit as soon as the reconciliation ends (OSRS moves him
 * some time later: "If he is not there, leave the mountain and come back"); only the ghostspeak
 * amulet (plain or enchanted) counts as ghostspeak ability.
 */
class SpiritOfAdventure
@Inject
constructor(private val npcTypes: NpcTypeList, private val locRepo: LocRepository) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(SpiritOfAdventure::class.java, "spirit_of_adventure")
    }

    private val ghostSpeaker: Map<String, NpcType> by lazy { mapOf("Ghost" to SoaNpcs.bernina) }

    override fun ScriptContext.startup() {
        // Each multinpc's Talk-to is on its shared `_vis` child.
        with(QuestTalk) {
            default(npcTypes, SoaNpcs.bernina) { talkBernina(it) }
            default(npcTypes, SoaNpcs.jasper) { talkJasper(it) }
            default(npcTypes, SoaNpcs.bernard) { talkBernard(it) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.vars[SoaVarBits.quest]

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.ghostspeak(): Boolean =
        invTotal(worn, SoaObjs.ghostspeak) > 0 || invTotal(worn, SoaObjs.ghostspeak_e) > 0

    /* Bernina */

    private suspend fun ProtectedAccess.talkBernina(npc: Npc) {
        if (!ghostspeak()) {
            runQuestSection(npc, dialogue, "Talking to Bernina without ghostspeak ability")
            return
        }
        when (state) {
            0 -> {
                runQuestSection(npc, dialogue, "Talking to Bernina with ghostspeak ability")
                setBit(SoaVarBits.quest, STARTED)
            }
            STARTED -> runQuestSection(npc, dialogue, "Talking to Bernina again")
            JASPER_TOLD ->
                if (player.vars[SoaVarBits.mountain_hint] == 0) {
                    runQuestSection(npc, dialogue, "Talking to Bernina")
                    setBit(SoaVarBits.mountain_hint, 1)
                } else {
                    runQuestSection(npc, dialogue, "Talking to Bernina again (2)")
                }
            FIRE_LIT -> {
                runQuestSection(npc, dialogue, "Talking to Bernina (2)")
                setBit(SoaVarBits.quest, ON_MOUNTAIN)
            }
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): Bernina beside Bernard on the mountain.
                    chatNpc(neutral, "He's right here, ${player.displayName}. Please, tell him who I am.")
                }
        }
    }

    /* Jasper */

    private suspend fun ProtectedAccess.talkJasper(npc: Npc) {
        when (state) {
            0 ->
                startDialogue(npc) {
                    // Authored (not Wiki): before Bernina has named him.
                    chatPlayer(neutral, "Hello there.")
                    chatNpc(neutral, "Who are you? I've got salvage to sort, if you don't mind.")
                }
            STARTED -> {
                runQuestSection(npc, dialogue, "Talking to Jasper")
                setBit(SoaVarBits.quest, JASPER_TOLD)
            }
            else -> runQuestSection(npc, dialogue, "Talking to Jasper again")
        }
    }

    /* Bernard */

    private suspend fun ProtectedAccess.talkBernard(npc: Npc) {
        when (state) {
            JASPER_TOLD ->
                when {
                    player.vars[SoaVarBits.fire_hint] == 0 -> {
                        runQuestSection(npc, dialogue, "Talking to Bernard")
                        setBit(SoaVarBits.fire_hint, 1)
                    }
                    fireBeside(npc) -> {
                        runQuestSection(npc, dialogue, "Talking to Bernard after lighting a fire")
                        setBit(SoaVarBits.quest, FIRE_LIT)
                    }
                    else -> runQuestSection(npc, dialogue, "Talking to Bernard again")
                }
            FIRE_LIT -> runQuestSection(npc, dialogue, "Talking to Bernard again after lighting a fire")
            ON_MOUNTAIN -> reconcile(npc)
            FADED -> {
                runQuestSection(npc, dialogue, "Talking to Bernard again (2)")
                setBit(SoaVarBits.quest, AT_SUMMIT)
            }
            AT_SUMMIT -> runQuestSection(npc, dialogue, "At the summit of the Proudspire")
            else ->
                startDialogue(npc) {
                    // Authored (not Wiki): Bernard is only seen once the quest has begun.
                    chatNpc(neutral, "Hey, iknami. Lovely day for a climb, isn't it?")
                }
        }
    }

    private suspend fun ProtectedAccess.reconcile(npc: Npc) {
        if (!ghostspeak()) {
            startDialogue(npc) {
                // Authored (not Wiki): without ghostspeak, Bernina's words can't be passed on.
                chatNpc(
                    neutral,
                    "Ah, hello again, ${player.displayName}. I don't want to cause alarm, but there's a ghost here with us!",
                )
                mesbox("The ghost is trying to say something, but you can't understand her.")
            }
            return
        }
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "Bernina fades away." to {
                            setBit(SoaVarBits.quest, FADED)
                            false
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Talking to Bernard (2)", hooks, ghostSpeaker)
        if (state == FADED) setBit(SoaVarBits.quest, AT_SUMMIT)
    }

    /** A Firemaking fire on or next to Bernard's tile. */
    private fun fireBeside(npc: Npc): Boolean {
        val at = npc.coords
        for (dx in -FIRE_RANGE..FIRE_RANGE) {
            for (dz in -FIRE_RANGE..FIRE_RANGE) {
                if (locRepo.findExact(at.translate(dx, dz), SoaLocs.fire) != null) return true
            }
        }
        return false
    }

    private companion object {
        const val STARTED = 1
        const val JASPER_TOLD = 2
        const val FIRE_LIT = 3
        const val ON_MOUNTAIN = 4
        const val FADED = 5
        const val AT_SUMMIT = 6
        const val FIRE_RANGE = 2
    }
}

// Every cache ref Spirit of Adventure uses (re-check after cache updates).

internal object SoaNpcs : NpcReferences() {
    /** The Talk-to children of the Bernina / Bernard multinpcs (coast, path, mountain, summit). */
    val bernina = find("spiritofadventure_bernina_vis")
    val bernard = find("spiritofadventure_bernard_vis")
    val jasper = find("spiritofadventure_jasper")
}

internal object SoaLocs : LocReferences() {
    val fire = find("fire")
}

internal object SoaObjs : ObjReferences() {
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_e = find("amulet_of_ghostspeak_enchanted")
}

internal object SoaVarBits : VarBitReferences() {
    val quest = find("spiritofadventure")
    val mountain_hint = find("spiritofadventure_mountain_hint")
    val fire_hint = find("spiritofadventure_fire_hint")
}
