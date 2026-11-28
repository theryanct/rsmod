package org.rsmod.content.quests.holygrail

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
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
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.merlinscrystal.MerlinsCrystal.Companion.MERLINS_CRYSTAL
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Holy Grail (OSRS Wiki). Cache quest varp `grail`, end state 10 (needs Merlin's Crystal):
 * - 1 King Arthur sends you after the Grail; 2 Merlin (workshop) points to Entrana and Galahad;
 *   3 the High Priest of Entrana tells of the Fisher King; 4 Galahad (west of McGrubor's Wood)
 *   gives his holy table napkin; the magic whistles lie on the table in Draynor Manor's top floor.
 * - A magic whistle blown between the Brimhaven pillars takes you to the withered Fisher Realm
 *   (back again from inside). The Black Knight Titan (spawned for you) can only be beaten with
 *   Excalibur (5); the Grail bell, rung by the castle's broken bricks, lets you in; 6 the Fisher
 *   King asks for his son.
 * - 7 King Arthur's magic gold feather points to the sacks in Goblin Village; 8 Sir Percival takes
 *   a whistle home, and the realm is restored (whistles now go to its restored twin, 128 tiles
 *   west); the Holy grail (a ground item in the eastern tower) goes back to King Arthur: 10
 *   complete, 11,000 Prayer and 15,300 Defence xp, 2 QP.
 * - Server varp `hg_progress` (65791): bit 0 the Titan beaten.
 *
 * Simplified: the castle's inside is reached by a telejump, the Titan isn't instanced.
 */
class HolyGrail
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(HolyGrail::class.java, "holy_grail") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Grail Maiden" to HgNpcs.maiden,
            "Black Knight Titan" to HgNpcs.titan,
            "Sir Percival" to HgNpcs.percival,
            "King Percival" to HgNpcs.king_percival,
        )
    }

    /** Each player's Black Knight Titan, by player uid. */
    private val titans = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, HgNpcs.arthur, HOLY_GRAIL, wants = { wantsArthur() }) { talkArthur(it) }
            register(npcTypes, HgNpcs.merlin, HOLY_GRAIL, wants = { state == STARTED }) {
                runQuestSection(it, dialogue, "Talking to Merlin")
                setState(MERLIN)
            }
            register(npcTypes, HgNpcs.priest, HOLY_GRAIL, wants = { state == MERLIN }) {
                runQuestSection(it, dialogue, "Talking to the High Priest of Entrana")
                setState(PRIEST)
            }
            register(npcTypes, HgNpcs.galahad, HOLY_GRAIL, wants = { state >= STARTED && state < COMPLETE }) {
                talkGalahad(it)
            }
            for ((title, knight) in HgNpcs.knights) {
                register(npcTypes, knight, HOLY_GRAIL, wants = { state in STARTED until KING }) {
                    runQuestSection(it, dialogue, title)
                }
            }
            register(npcTypes, HgNpcs.fisherman, HOLY_GRAIL, wants = { state >= NAPKIN }) {
                runQuestSection(it, dialogue, "Talking to the Fisherman")
            }
            register(npcTypes, HgNpcs.fisher_king, HOLY_GRAIL, wants = { state in TITAN until COMPLETE }) {
                talkFisherKing(it)
            }
            register(npcTypes, HgNpcs.titan, HOLY_GRAIL, wants = { true }) { talkTitan(it) }
            register(npcTypes, HgNpcs.king_percival, HOLY_GRAIL, wants = { state >= PERCIVAL }) {
                runQuestSection(it, dialogue, "Talking to Sir Percival at the Fisher Realm", speakers = speakers)
            }
        }
        onHeldOpLabel(objTypes, HgObjs.whistle, "Blow") { blowWhistle() }
        onHeldOpLabel(objTypes, HgObjs.bell, "Ring") { ringBell() }
        onHeldOpLabel(objTypes, HgObjs.feather, "Blow-on") { blowFeather() }
        onLocOpLabel(locTypes, HgLocs.sacks, "Prod") {
            runQuestSection(null, dialogue, "Talking to Sir Percival", sackHooks(open = false))
        }
        onLocOpLabel(locTypes, HgLocs.sacks, "Open") { openSacks() }
        onModifyNpcHit(HgNpcs.titan) { excaliburOnly(hit) }
        onEvent<NpcKilledEvent> { titanDown(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(HOLY_GRAIL)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(HOLY_GRAIL, value)
    }

    /* King Arthur, Merlin and Galahad */

    private fun ProtectedAccess.wantsArthur(): Boolean =
        player.isQuestComplete(MERLINS_CRYSTAL) && state < COMPLETE

    private suspend fun ProtectedAccess.talkArthur(npc: Npc) {
        when {
            state == 0 -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player's combat level is less than 50:" to { false },
                                "If the player's combat level is more than 50:" to { true },
                            ),
                        options = mapOf("Yes." to { if (state == 0) setState(STARTED) }),
                    )
                runQuestSection(npc, dialogue, "Starting off", hooks)
            }
            state == KING || (state == FEATHER && !carries(HgObjs.feather)) -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have enough inventory space:" to {
                                    inv.freeSpace() < 1
                                },
                                "If the player has one free inventory space:" to { inv.freeSpace() >= 1 },
                            ),
                        boxes =
                            mapOf(
                                "King Arthur gives you a feather." to {
                                    invAddOrDrop(objRepo, HgObjs.feather)
                                    if (state == KING) setState(FEATHER)
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to King Arthur", hooks)
            }
            state == PERCIVAL && carries(HgObjs.grail) -> {
                runQuestSection(npc, dialogue, "Talking to King Arthur (2)")
                invDel(inv, HgObjs.grail)
                statAdvance(stats.prayer, 11000.0 * xpMods.get(player, stats.prayer))
                statAdvance(stats.defence, 15300.0 * xpMods.get(player, stats.defence))
                completeQuest(HOLY_GRAIL)
            }
            state == STARTED -> runQuestSection(npc, dialogue, "Subsequent dialogue with King Arthur")
            else -> runQuestSection(npc, dialogue, "Updating King Arthur")
        }
    }

    private suspend fun ProtectedAccess.talkGalahad(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf("If the player talked to the High Priest of Entrana first:" to { state >= PRIEST }),
                boxes =
                    mapOf(
                        "Galahad reluctantly passes you a small cloth." to {
                            if (!carries(HgObjs.napkin)) invAddOrDrop(objRepo, HgObjs.napkin)
                            if (state == PRIEST) setState(NAPKIN)
                            false
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to Sir Galahad", hooks)
    }

    /* The magic whistle and the Fisher Realm */

    private fun ProtectedAccess.inRealm(): Boolean {
        val c = player.coords
        return c.z in REALM_Z && (c.x in DEAD_X || c.x in RESTORED_X)
    }

    private suspend fun ProtectedAccess.blowWhistle() {
        when {
            inRealm() -> telejump(BRIMHAVEN)
            player.coords.chebyshevDistance(PILLARS) <= PILLAR_RANGE && state >= NAPKIN -> {
                if (state >= PERCIVAL) {
                    telejump(RESTORED_ARRIVAL)
                } else {
                    telejump(DEAD_ARRIVAL)
                    summonTitan()
                }
            }
            else ->
                runQuestSection(
                    null,
                    dialogue,
                    "Blowing on the Magic whistle",
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player blows the whistle anywhere besides between the four pillars at Brimhaven:" to
                                    { true }
                            )
                    ),
                )
        }
    }

    private fun ProtectedAccess.summonTitan() {
        if (hasTitanFlag()) return
        val current = titans[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        val titan = Npc(npcTypes[HgNpcs.titan], TITAN_SPOT)
        npcRepo.add(titan, TITAN_TICKS)
        titans[player.uid.packed] = titan
    }

    private fun ProtectedAccess.hasTitanFlag(): Boolean =
        (player.vars[HgVarps.progress] and (1 shl TITAN_BEATEN)) != 0

    private suspend fun ProtectedAccess.talkTitan(npc: Npc) {
        if (hasTitanFlag()) {
            runQuestSection(npc, dialogue, "Attempting to talk to the Black Knight Titan again after his defeat")
            return
        }
        var fight = false
        val hooks =
            SimpleQuestHooks(
                options = mapOf("Okay, have at ye oh evil knight!" to { fight = true }),
                defaultCondition = { false },
            )
        runQuestSection(npc, dialogue, "Encountering the Black Knight Titan", hooks, speakers)
        if (fight) npc.opPlayer2(player, aiInteractions)
    }

    /** Only Excalibur (wielded) can finish the Black Knight Titan. */
    private fun excaliburOnly(hit: HitBuilder) {
        if (hit.isFromPlayer && !hit.isRighthandObj(HgObjs.excalibur)) {
            hit.damage = 0
        }
    }

    private fun titanDown(event: NpcKilledEvent) {
        if (event.npc.type.id != HgNpcs.titan.id) return
        val player = event.hero
        titans.remove(player.uid.packed)
        val progress = player.vars[HgVarps.progress]
        VarPlayerIntMapSetter.set(player, HgVarps.progress, progress or (1 shl TITAN_BEATEN))
        if (player.questState(HOLY_GRAIL) == NAPKIN) player.setQuestState(HOLY_GRAIL, TITAN)
    }

    private suspend fun ProtectedAccess.ringBell() {
        val near = player.coords.chebyshevDistance(BRICKS) <= BELL_RANGE
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player rings the Grail bell but is not by the dismantled bricks:" to { !near },
                        "If the player rings the Grail bell and is at least four tiles by the dismantled bricks:" to
                            { near },
                    )
            )
        runQuestSection(null, dialogue, "Ringing the Grail bell", hooks, speakers)
        if (near) telejump(CASTLE_INSIDE)
    }

    private suspend fun ProtectedAccess.talkFisherKing(npc: Npc) {
        val hooks =
            SimpleQuestHooks(options = mapOf("I seek the Holy Grail." to { if (state == TITAN) setState(KING) }))
        runQuestSection(npc, dialogue, "Talking to The Fisher King", hooks)
    }

    /* The feather and Sir Percival */

    private fun ProtectedAccess.blowFeather() {
        val here = player.coords
        val dx = SACKS.x - here.x
        val dz = SACKS.z - here.z
        if (here.z > UNDERGROUND_Z) {
            mes("The feather doesn't seem to work down here.")
            return
        }
        if (here.chebyshevDistance(SACKS) <= FEATHER_CLOSE) {
            mes("The feather points right here!")
            return
        }
        val ns = if (dz > FEATHER_SLACK) "north" else if (dz < -FEATHER_SLACK) "south" else ""
        val ew = if (dx > FEATHER_SLACK) "east" else if (dx < -FEATHER_SLACK) "west" else ""
        mes("The feather points to the $ns$ew.")
    }

    private fun sackHooks(open: Boolean, hasWhistle: Boolean = false): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player prods at the sacks:" to { !open },
                    "If the player attempts to open the sack:" to { open },
                    "If the player does not have the whistle:" to { !hasWhistle },
                    "If the player has the magic whistle:" to { hasWhistle },
                )
        )

    private suspend fun ProtectedAccess.openSacks() {
        if (state != FEATHER || !carries(HgObjs.feather)) {
            // Authored line (not Wiki): the sacks before Arthur's feather.
            mes("You open the sacks but find only old rags.")
            return
        }
        val hasWhistle = carries(HgObjs.whistle)
        runQuestSection(null, dialogue, "Talking to Sir Percival", sackHooks(open = true, hasWhistle), speakers)
        if (hasWhistle && carries(HgObjs.whistle)) {
            invDel(inv, HgObjs.whistle)
            setState(PERCIVAL)
        }
    }

    companion object {
        const val STARTED = 1
        const val MERLIN = 2
        const val PRIEST = 3
        const val NAPKIN = 4
        const val TITAN = 5
        const val KING = 6
        const val FEATHER = 7
        const val PERCIVAL = 8
        const val COMPLETE = 10

        private const val TITAN_BEATEN = 0
        private const val TITAN_TICKS = 1000
        private const val PILLAR_RANGE = 4
        private const val BELL_RANGE = 4
        private const val FEATHER_CLOSE = 5
        private const val FEATHER_SLACK = 2
        private const val UNDERGROUND_Z = 6400

        private val PILLARS = CoordGrid(2740, 3232, 0)
        private val BRIMHAVEN = CoordGrid(2741, 3234, 0)
        private val REALM_Z = 4672..4735
        private val DEAD_X = 2752..2815
        private val RESTORED_X = 2624..2687

        /** By the fairy ring (2778,4730) in the withered realm and its restored twin. */
        private val DEAD_ARRIVAL = CoordGrid(2778, 4727, 0)
        private val RESTORED_ARRIVAL = CoordGrid(2650, 4727, 0)
        private val TITAN_SPOT = CoordGrid(2778, 4722, 0)
        /** The Grail bell lies in front of the castle's broken bricks (Wiki 2762,4694). */
        private val BRICKS = CoordGrid(2762, 4693, 0)
        private val CASTLE_INSIDE = CoordGrid(2764, 4688, 0)
        /** Sir Percival's sacks in Goblin Village's east house. */
        private val SACKS = CoordGrid(2962, 3506, 0)

        val HOLY_GRAIL =
            QuestDef(
                name = "Holy Grail",
                varp = { HgVarps.quest },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("11,000 Prayer XP", "15,300 Defence XP"),
                scrollObj = { HgObjs.grail },
                listRow = "quest_holygrail",
            )
    }
}

// Every cache ref Holy Grail uses (re-check after cache updates).

internal object HgNpcs : NpcReferences() {
    val arthur = find("king_arthur")
    val merlin = find("merlin2")
    val priest = find("high_priest_of_entrana")
    val galahad = find("brother_galahad")
    val fisherman = find("grail_fisherman")
    val fisher_king = find("fisher_king")
    val maiden = find("grail_maiden")
    val titan = find("black_knight_titan")
    val percival = find("sir_percival")
    val king_percival = find("king_percival")

    /** The Wiki's section titles (its spelling) and the knight. */
    val knights: List<Pair<String, NpcType>> =
        listOf(
            "Talking to Sir Palomedes" to find("sir_palomedes"),
            "Talking to Sir Lucan" to find("sir_lucan"),
            "Talking to Sir Gawain" to find("sir_gawain"),
            "Talking to Sir Kay" to find("sir_kay"),
            "Talking to Sir Tristam" to find("sir_tristram"),
            "Talking to Sir Pellas" to find("sir_pelleas"),
            "Talking to Sir Bedivere" to find("sir_bedivere"),
            "Talking to Sir Lancelot" to find("sir_lancelot"),
        )
}

internal object HgLocs : LocReferences() {
    val sacks = find("percy_sacks")
}

internal object HgObjs : ObjReferences() {
    val napkin = find("holy_table_napkin")
    val whistle = find("magic_whistle")
    val bell = find("grail_bell")
    val feather = find("magic_golden_feather")
    val grail = find("holy_grail")
    val excalibur = find("excalibur")
}

internal object HgVarps : VarpReferences() {
    val quest = find("grail")

    /** Server varp (65791): bit 0 the Black Knight Titan beaten. */
    val progress = find("hg_progress")
}

internal object HgVarpBuilds : VarpBuilder() {
    init {
        build("hg_progress")
    }
}
