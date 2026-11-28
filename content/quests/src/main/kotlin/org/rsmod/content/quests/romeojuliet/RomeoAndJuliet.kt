package org.rsmod.content.quests.romeojuliet

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.cutscene.CutsceneRunner
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
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Romeo & Juliet (OSRS Wiki). `rjquest` varp (cache db end state 100): 0, 10 Romeo asked you to
 * find Juliet, 20 Juliet's message, 30 message delivered (see Father Lawrence), 40 Lawrence's
 * plan (Cadava potion from the Apothecary: cadava berries), 60 Juliet drank it (she's hidden via
 * `romjul_juliet_visible`), 100 complete after the crypt cutscene (5 QP).
 *
 * The crypt scene is the first user of the cutscene framework: an instance of the R&J crypt
 * (map square 36_72), Romeo walks to Juliet's tomb, Phillipa steps in. If the scene is aborted
 * (e.g. a click during the dialogue) the quest stays at 60 and talking to Romeo replays it.
 * Also here: picking the wild cadava bushes south-east of Varrock (2 -> 1 -> 0 berries).
 * Not done: drinking the potion at the Apothecary's, the post-quest antique lamp.
 */
class RomeoAndJuliet
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val cutscenes: CutsceneRunner,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(RomeoAndJuliet::class.java, "romeo_and_juliet")
    }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Romeo" to RjNpcs.romeo,
            "Juliet" to RjNpcs.juliet,
            "Phillipa" to RjNpcs.phillipa,
            "Draul Leptoc" to RjNpcs.draul,
        )
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, RjNpcs.romeo, "Talk-to") { talkRomeo(it) }
        onOpNpcU(RjNpcs.romeo, RjObjs.message) { talkRomeo(it.npc) }
        onNpcOpLabel(npcTypes, RjNpcs.juliet, "Talk-to") { talkJuliet(it) }
        onOpNpcU(RjNpcs.juliet, RjObjs.potion) { talkJuliet(it.npc) }
        onNpcOpLabel(npcTypes, RjNpcs.phillipa, "Talk-to") { talkPhillipa(it) }
        onNpcOpLabel(npcTypes, RjNpcs.draul, "Talk-to") { talkDraul(it) }
        onNpcOpLabel(npcTypes, RjNpcs.lawrence, "Talk-to") { talkLawrence(it) }
        onNpcOpLabel(npcTypes, RjNpcs.apothecary, "Talk-to") { talkApothecary(it) }
        onHeldOpLabel(objTypes, RjObjs.potion, "Drink") {
            runQuestSection(null, dialogue, "Attempting to drink the potion elsewhere")
        }
        for ((index, bush) in RjLocs.bushes.withIndex()) {
            val next = RjLocs.bushes.getOrNull(index + 1)
            onLocOpLabel(locTypes, bush, "Pick-from") { pickBerries(it, next) }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(ROMEO_AND_JULIET)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(ROMEO_AND_JULIET, value)
    }

    /* Romeo */

    private suspend fun ProtectedAccess.talkRomeo(npc: Npc) {
        val message = RjObjs.message
        val potion = RjObjs.potion
        var delivered = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player is nearby Romeo:" to { false },
                        "If the player does not have Juliet's message in their inventory:" to {
                            !carries(message)
                        },
                        "If the player has Juliet's message in their inventory:" to {
                            carries(message)
                        },
                        "If player is not carrying a cadava potion:" to { !carries(potion) },
                        "If player is carrying a cadava potion:" to { carries(potion) },
                    ),
                options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                boxes =
                    mapOf(
                        "You hand over Juliet's message to Romeo." to {
                            delivered = carries(message)
                            false
                        },
                    ),
            )
        when (state) {
            0 -> runQuestSection(npc, dialogue, "Talking to Romeo", hooks)
            STARTED -> runQuestSection(npc, dialogue, "Talking to Romeo again", hooks)
            MESSAGE -> runQuestSection(npc, dialogue, "Talking to Romeo (2)", hooks)
            DELIVERED -> runQuestSection(npc, dialogue, "Talking to Romeo again (2)", hooks)
            LAWRENCE ->
                if (carries(potion)) {
                    runQuestSection(npc, dialogue, "Talking to Romeo (3)", hooks)
                } else {
                    runQuestSection(npc, dialogue, ROMEO_AFTER_LAWRENCE, hooks)
                }
            POTION_GIVEN -> cryptScene()
            else -> runQuestSection(npc, dialogue, "Talking to Romeo (5)", hooks)
        }
        if (delivered) {
            invDel(inv, message)
            setState(DELIVERED)
        }
    }

    /** The crypt: Romeo goes to "dead" Juliet, Phillipa steps in. */
    private suspend fun ProtectedAccess.cryptScene() {
        val back = player.coords
        val finished =
            cutscenes.play(this, CRYPT_TEMPLATE, returnTo = back) {
                val romeo = spawn(RjNpcs.romeo, CRYPT_ROMEO)
                val juliet = spawn(RjNpcs.juliet, CRYPT_JULIET)
                val phillipa = spawn(RjNpcs.phillipa, CRYPT_PHILLIPA_START)
                teleport(CRYPT_PLAYER)
                camMoveTo(CRYPT_CAMERA, height = 600)
                camLookAt(CRYPT_JULIET, height = 150)
                fadeIn()
                delay(2)
                romeo.walkTo(CRYPT_TOMB_SIDE)
                juliet.faceTile(CRYPT_PLAYER)
                delay(4)
                phillipa.walkTo(CRYPT_PHILLIPA_END)
                conversation {
                    runQuestSection(romeo, dialogue, "Talking to Romeo (4)", speakers = speakers)
                }
                fadeOut()
                delay(2)
            }
        if (!finished) return
        completeQuest(ROMEO_AND_JULIET)
    }

    /* Juliet's house */

    private suspend fun ProtectedAccess.talkJuliet(npc: Npc) {
        val message = RjObjs.message
        var gaveMessage = false
        var drank = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player still has the message:" to { carries(message) },
                        "If the player lost the message:" to { !carries(message) },
                        "If the player doesn't have a Cadava potion in their inventory:" to {
                            !carries(RjObjs.potion)
                        },
                        "If the player has a Cadava potion in their inventory:" to {
                            carries(RjObjs.potion)
                        },
                    ),
                boxes =
                    mapOf(
                        "Juliet gives you a message." to {
                            gaveMessage = true
                            false
                        },
                        "Juliet gives you another message." to {
                            gaveMessage = true
                            false
                        },
                        "You pass the suspicious potion to Juliet." to {
                            drank = carries(RjObjs.potion)
                            false
                        },
                    ),
            )
        val section =
            when {
                state == STARTED -> "Talking to Juliet"
                state == MESSAGE -> "Talking to Juliet again"
                state < LAWRENCE -> "Talking to Juliet (2)"
                state == LAWRENCE && carries(RjObjs.potion) -> "Talking to Juliet (3)"
                state == LAWRENCE -> JULIET_AFTER_LAWRENCE
                else -> null
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "Romeo... where are you?") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks, speakers)
        if (gaveMessage && !carries(message)) {
            invAddOrDrop(objRepo, message)
            if (state == STARTED) setState(MESSAGE)
        }
        if (drank) {
            invDel(inv, RjObjs.potion)
            VarPlayerIntMapSetter.set(player, RjVarBits.juliet_visible, JULIET_HIDDEN)
            setState(POTION_GIVEN)
        }
    }

    private suspend fun ProtectedAccess.talkPhillipa(npc: Npc) {
        val section =
            when {
                state >= POTION_GIVEN -> "Talking to Phillipa (2)"
                state >= MESSAGE -> "Talking to Phillipa after agreeing to deliver the message"
                else -> "Talking to Phillipa"
            }
        runQuestSection(npc, dialogue, section)
    }

    private suspend fun ProtectedAccess.talkDraul(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the message on them:" to { carries(RjObjs.message) },
                        "If the player currently has the cadava potion:" to {
                            carries(RjObjs.potion)
                        },
                    ),
            )
        val section =
            when {
                state >= POTION_GIVEN -> "Talking to Draul Leptoc (4)"
                state == LAWRENCE -> "Talking to Draul Leptoc (3)"
                state == DELIVERED -> "Talking to Draul Leptoc (2)"
                state == MESSAGE -> DRAUL_MESSAGE
                else -> "Talking to Draul Leptoc"
            }
        runQuestSection(npc, dialogue, section, hooks)
    }

    /* Father Lawrence and the Apothecary */

    private suspend fun ProtectedAccess.talkLawrence(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has not made a cadava potion:" to {
                            !carries(RjObjs.potion)
                        },
                        "If player is not carrying a cadava potion:" to { !carries(RjObjs.potion) },
                        "If player is carrying a cadava potion:" to { carries(RjObjs.potion) },
                    ),
            )
        when {
            state == DELIVERED -> {
                runQuestSection(npc, dialogue, "Talking to Father Lawrence", hooks)
                setState(LAWRENCE)
            }
            state == LAWRENCE && carries(RjObjs.potion) ->
                runQuestSection(npc, dialogue, "Talking to Father Lawrence (2)", hooks)
            state == LAWRENCE -> runQuestSection(npc, dialogue, LAWRENCE_AGAIN, hooks)
            else ->
                startDialogue(npc) { chatNpc(neutral, "Saradomin be with you, my child.") }
        }
    }

    private suspend fun ProtectedAccess.talkApothecary(npc: Npc) {
        val berries = RjObjs.berries
        var made = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has cadava berries in their inventory:" to {
                            carries(berries)
                        },
                        "If the player does not have cadava berries in their inventory:" to {
                            !carries(berries)
                        },
                        "Without any cadava berries:" to {
                            !carries(berries) && !carries(RjObjs.potion)
                        },
                        "With some cadava berries:" to { carries(berries) },
                        "With the cadava potion:" to {
                            carries(RjObjs.potion) && !carries(berries)
                        },
                        "After giving Juliet the cadava potion:" to { state >= POTION_GIVEN },
                    ),
                boxes =
                    mapOf(
                        "The Apothecary gives you a Cadava potion." to {
                            made = carries(berries)
                            false
                        },
                    ),
            )
        val section =
            if (state < LAWRENCE) "Talking to the Apothecary" else "Talking to the Apothecary again"
        runQuestSection(npc, dialogue, section, hooks)
        if (made && carries(berries)) {
            invDel(inv, berries)
            invAddOrDrop(objRepo, RjObjs.potion)
        }
    }

    private fun ProtectedAccess.pickBerries(bush: BoundLocInfo, next: LocType?) {
        if (next == null) {
            mes("There are no berries on this bush at the moment.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        invAdd(inv, RjObjs.berries)
        locRepo.change(bush, next, BUSH_RESPAWN_TICKS)
        mes("You pick some cadava berries.")
    }

    companion object {
        const val STARTED = 10
        const val MESSAGE = 20
        const val DELIVERED = 30
        const val LAWRENCE = 40
        const val POTION_GIVEN = 60
        const val COMPLETE = 100

        private const val JULIET_HIDDEN = 1
        private const val BUSH_RESPAWN_TICKS = 100

        private const val ROMEO_AFTER_LAWRENCE = "Talking to Romeo after Father Lawrence"
        private const val JULIET_AFTER_LAWRENCE = "Talking to Juliet after Father Lawrence"
        private const val DRAUL_MESSAGE =
            "Talking to Draul Leptoc after agreeing to deliver the message"
        private const val LAWRENCE_AGAIN = "Talking to Father Lawrence again"

        /** The R&J crypt (map square 36_72): zones 290-291 x, 579-581 z, level 0. */
        private val CRYPT_TEMPLATE =
            RegionTemplate.create {
                copy(CRYPT_ZONE_X, CRYPT_ZONE_Z, 0) {
                    zoneWidth = 3
                    zoneLength = 3
                }
            }
        private const val CRYPT_ZONE_X = 290
        private const val CRYPT_ZONE_Z = 579
        private val CRYPT_PLAYER = CoordGrid(2333, 4644, 0)
        private val CRYPT_ROMEO = CoordGrid(2332, 4644, 0)
        private val CRYPT_JULIET = CoordGrid(2327, 4645, 0)
        private val CRYPT_TOMB_SIDE = CoordGrid(2328, 4644, 0)
        private val CRYPT_PHILLIPA_START = CoordGrid(2333, 4642, 0)
        private val CRYPT_PHILLIPA_END = CoordGrid(2329, 4643, 0)
        private val CRYPT_CAMERA = CoordGrid(2331, 4639, 0)

        val ROMEO_AND_JULIET =
            QuestDef(
                name = "Romeo & Juliet",
                listRow = "quest_romeoandjuliet",
                varp = { RjVarps.rjquest },
                complete = COMPLETE,
                questPoints = 5,
                rewards = emptyList(),
                scrollObj = { RjObjs.potion },
            )
    }
}

// Every cache ref Romeo & Juliet uses (re-check after the rev 243 cutover).

internal object RjNpcs : NpcReferences() {
    val romeo = find("romeo")

    /** Child of `juliet_multi_visible` (varbit romjul_juliet_visible: 1 hidden). */
    val juliet = find("juliet")
    val phillipa = find("phillipa")
    val draul = find("draul_leptoc")
    val lawrence = find("father_lawrence")
    val apothecary = find("apothecary")
}

internal object RjLocs : LocReferences() {
    /** Wild bush stages, full to empty. */
    val bushes =
        listOf(
            find("fai_varrock_cadavabush_2"),
            find("fai_varrock_cadavabush_1"),
            find("fai_varrock_cadavabush_0"),
        )
}

internal object RjObjs : ObjReferences() {
    val message = find("julietmessage")
    val berries = find("cadavaberries")
    val potion = find("cadava")
}

internal object RjVarps : VarpReferences() {
    val rjquest = find("rjquest")
}

internal object RjVarBits : VarBitReferences() {
    val juliet_visible = find("romjul_juliet_visible")
}
