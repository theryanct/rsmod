package org.rsmod.content.quests.sheepherder

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
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
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Sheep Herder (OSRS Wiki). Cache quest varp `sheepherderquest`, end state 3:
 * - 1 Councillor Halgrive (Ardougne) hands over poisoned sheep feed; Doctor Orbon sells the
 *   protective suit (100 coins) and Farmer Brumty's cattleprod drives each of the four plague
 *   sheep into the enclosure north of Ardougne. Each sheep's state is its cache varbit
 *   `sheepherder_sheep_a..d`: 1 penned (the enclosure multinpc then shows it), 2 poisoned (its
 *   bones taken), 3 burned in the incinerator. 3 complete with all four burned: 4 QP, 3,100 coins.
 *
 * Simplified: one prod sends a sheep straight over the gate (OSRS herds it step by step).
 */
class SheepHerder
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(SheepHerder::class.java, "sheep_herder") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, ShNpcs.halgrive, SHEEP_HERDER, wants = { state < COMPLETE }) { talkHalgrive(it) }
            register(npcTypes, ShNpcs.orbon, SHEEP_HERDER, wants = { state == STARTED }) { talkOrbon(it) }
            register(npcTypes, ShNpcs.brumty, SHEEP_HERDER, wants = { state == STARTED }) {
                val section = if (carries(ShObjs.prod)) "Talking to Farmer Brumty" else "Talking to Farmer Brunty"
                runQuestSection(it, dialogue, section)
            }
        }
        for ((index, sheep) in ShNpcs.sheep.withIndex()) {
            onNpcOpLabel(npcTypes, sheep, "Prod") { prod(it, index) }
            onOpNpcU(sheep, ShObjs.feed) { feed(it.npc, index) }
            onOpNpcU(sheep, ShObjs.poison) { runQuestSection(null, dialogue, "Using poison on the sheep") }
            onOpLocU(ShLocs.incinerator, ShObjs.bones[index]) { burn(index) }
        }
        for (gate in ShLocs.gates) onLocOpLabel(locTypes, gate, "Open") { gate(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SHEEP_HERDER)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(SHEEP_HERDER, value)
    }

    private fun ProtectedAccess.suited(): Boolean =
        invTotal(worn, ShObjs.jacket) > 0 && invTotal(worn, ShObjs.trousers) > 0

    private fun ProtectedAccess.sheepState(index: Int): Int = player.vars[ShVarBits.sheep[index]]

    private fun ProtectedAccess.setSheep(index: Int, value: Int) {
        VarPlayerIntMapSetter.set(player, ShVarBits.sheep[index], value)
    }

    /* Halgrive, Orbon and the gear */

    private suspend fun ProtectedAccess.talkHalgrive(npc: Npc) {
        when {
            state == 0 -> {
                val hooks =
                    SimpleQuestHooks(
                        options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                        boxes = mapOf("The councillor gives you some poisoned sheep feed." to { give(ShObjs.feed); false }),
                    )
                runQuestSection(npc, dialogue, "Talking to Councillor Halgrive", hooks)
            }
            ShVarBits.sheep.indices.all { sheepState(it) == BURNED } -> {
                runQuestSection(npc, dialogue, "Talking to Councillor Halgrive after burning all bones")
                invAddOrDrop(objRepo, objs.coins, REWARD_COINS)
                completeQuest(SHEEP_HERDER)
            }
            carries(ShObjs.jacket) || suited() ->
                runQuestSection(npc, dialogue, "Talking to Councillor Halgrive after getting the gear")
            else -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player lost the sheep feed:" to { !carries(ShObjs.feed) },
                                "If the player still has the sheep feed:" to { carries(ShObjs.feed) },
                            ),
                        boxes = mapOf("The councillor gives you some poisoned sheep feed." to { give(ShObjs.feed); false }),
                    )
                runQuestSection(npc, dialogue, "Talking to Councillor Halgrive again", hooks)
            }
        }
    }

    private fun ProtectedAccess.give(obj: ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    private suspend fun ProtectedAccess.talkOrbon(npc: Npc) {
        val coins = invTotal(inv, objs.coins) >= SUIT_PRICE
        val space = inv.freeSpace() >= 2
        val hasGear = carries(ShObjs.jacket) || suited()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have 100 coins:" to { !coins },
                        "If the player does not have 100 coins:" to { !coins },
                        "If the player does not have the 100 coins:" to { !coins },
                        "If the player has 100 coins and 2 free inventory spaces:" to { coins && space },
                        "If the player has 100 coins but not 2 free inventory spaces:" to { coins && !space },
                        "If the player has 100 coins:" to { coins },
                        "If the player has the gear in their inventory:" to { hasGear },
                        "If the player does not have the gear in their inventory:" to { !hasGear },
                    ),
                boxes =
                    mapOf(
                        "You give Doctor Orbon 100 coins. He hands over a protective suit." to {
                            invDel(inv, objs.coins, SUIT_PRICE)
                            invAdd(inv, ShObjs.jacket)
                            invAdd(inv, ShObjs.trousers)
                            false
                        }
                    ),
            )
        val section = if (hasGear) "Talking to Doctor Orbon again" else "Talking to Doctor Orbon"
        runQuestSection(npc, dialogue, section, hooks)
    }

    /* The sheep */

    private fun inPen(coords: CoordGrid): Boolean = coords.x < PEN_EDGE_X && coords.z in PEN_Z

    private suspend fun ProtectedAccess.prod(npc: Npc, index: Int) {
        when {
            state != STARTED -> mes("The sheep wanders off.")
            !suited() -> runQuestSection(null, dialogue, "Prod sheep when not wearing suit")
            !carries(ShObjs.prod) && invTotal(worn, ShObjs.prod) == 0 ->
                mes("You need a cattleprod to herd the sheep. There should be one in Farmer Brumty's barn.")
            inPen(npc.coords) || sheepState(index) == PENNED ->
                runQuestSection(null, dialogue, "Prodding sheep already inside the enclosure")
            sheepState(index) == BONES || carries(ShObjs.bones[index]) ->
                runQuestSection(null, dialogue, "Prodding sheep while carrying that sheep's bones")
            sheepState(index) == BURNED -> runQuestSection(null, dialogue, "Prodding the same colour sheep again")
            else -> {
                runQuestSection(null, dialogue, "Prodding sheep into the enclosure")
                setSheep(index, PENNED)
                npcRepo.hide(npc, HIDE_TICKS)
            }
        }
    }

    private suspend fun ProtectedAccess.feed(npc: Npc, index: Int) {
        if (!inPen(npc.coords)) {
            runQuestSection(null, dialogue, "Use feed on sheep outside enclosure")
            return
        }
        if (!suited()) {
            runQuestSection(null, dialogue, "Using sheep feed on sheep inside enclosure without the protection clothing")
            return
        }
        if (sheepState(index) != PENNED) return
        runQuestSection(null, dialogue, "Using sheep feed on sheep inside enclosure")
        setSheep(index, BONES)
        invAddOrDrop(objRepo, ShObjs.bones[index])
    }

    private suspend fun ProtectedAccess.burn(index: Int) {
        if (sheepState(index) != BONES) {
            runQuestSection(null, dialogue, "using sheep bones on the incinerator while that sheep is in the pen")
            return
        }
        invDel(inv, ShObjs.bones[index])
        runQuestSection(null, dialogue, "Using sheep bones on the incinerator")
        setSheep(index, BURNED)
    }

    private suspend fun ProtectedAccess.gate(gate: BoundLocInfo) {
        if (!suited()) {
            runQuestSection(null, dialogue, "Opening the gate without protect clothing")
            return
        }
        passThroughWall(gate)
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 3

        private const val PENNED = 1
        private const val BONES = 2
        private const val BURNED = 3
        private const val SUIT_PRICE = 100
        private const val REWARD_COINS = 3100
        private const val HIDE_TICKS = 300
        /** The enclosure west of its gate (2594,3361-3362): the sheep pens at 2596-2598,3359-3362. */
        private const val PEN_EDGE_X = 2600
        private val PEN_Z = 3355..3366

        val SHEEP_HERDER =
            QuestDef(
                name = "Sheep Herder",
                varp = { ShVarps.quest },
                complete = COMPLETE,
                questPoints = 4,
                rewards = listOf("3,100 coins"),
                scrollObj = { ShObjs.prod },
                listRow = "quest_sheepherder",
            )
    }
}

// Every cache ref Sheep Herder uses (re-check after cache updates).

internal object ShNpcs : NpcReferences() {
    val halgrive = find("councillor_halgrive_vis")
    val orbon = find("doctor_orbon")
    val brumty = find("farmer_brumty")

    /** Red, green, blue, yellow (sheepherder_sheep_a..d order). */
    val sheep: List<NpcType> =
        listOf(
            find("herder_plaguesheep_1"),
            find("herder_plaguesheep_2"),
            find("herder_plaguesheep_3"),
            find("herder_plaguesheep_4"),
        )
}

internal object ShLocs : LocReferences() {
    val incinerator = find("plaguesheep_furnace")
    val gates = listOf(find("plaguesheep_gatel"), find("plaguesheep_gater"))
}

internal object ShObjs : ObjReferences() {
    val feed = find("poisoned_feed")
    val poison = find("poison")
    val prod = find("cattleprod")
    val jacket = find("plague_jacket")
    val trousers = find("plague_trousers")
    val bones = listOf(find("sheepbonesa"), find("sheepbonesb"), find("sheepbonesc"), find("sheepbonesd"))
}

internal object ShVarps : VarpReferences() {
    val quest = find("sheepherderquest")
}

internal object ShVarBits : VarBitReferences() {
    val sheep: List<VarBitType> =
        listOf(
            find("sheepherder_sheep_a"),
            find("sheepherder_sheep_b"),
            find("sheepherder_sheep_c"),
            find("sheepherder_sheep_d"),
        )
}
