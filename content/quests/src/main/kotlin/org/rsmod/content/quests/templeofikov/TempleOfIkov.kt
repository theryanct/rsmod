package org.rsmod.content.quests.templeofikov

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onApNpc1
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpObj3
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.grantQuest
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Temple of Ikov (OSRS Wiki). Progress lives in the cache quest varp `ikov` (26, db end state 80);
 * which Lucien shows is the cache varbit `ikov_lucien_vis` (0 the pub one, 1 the house one, 2 the
 * house one attackable, 3 neither). The stages are ours:
 * - Lucien (Flying Horse Inn) gives the Pendant of Lucien (1).
 * - Temple of Ikov ([IkovDungeon]): the lever from across the bridge fitted and pulled opens the
 *   south gate (2); ice arrows from the ice chamber chests; the trap lever (42 Thieving) opens the
 *   way to the Fire Warrior of Lesarkus, who takes damage only from ice arrows (3); Winelda takes 20
 *   limpwurt roots and teleports you over the lava (4).
 * - Lucien's side: take the Staff of Armadyl (a guardian attacks first) and hand it to Lucien at his
 *   house (80). Guardians' side: be cleansed and agree to help (5, Armadyl pendant), then kill
 *   Lucien at his house wearing the pendant (80). Either way: 1 QP, 10,500 Ranged and 8,000
 *   Fletching XP (cache db 105000 / 80000 tenths; Wiki 10,500 / 8,000).
 *
 * Simplified: the start warning checks the skills only (not the combat level); attacking Lucien
 * without the Armadyl pendant does no damage and shows the "mind trick" (the fight isn't stopped);
 * Lucien's defeat completes the quest even though the npc respawns hidden; one guardian killed lets
 * you take the staff (Wiki: defeat them); a lost Armadyl pendant is re-given by the guardians on
 * request (authored); Winelda's "met" flag and the trap-lever state last until logout.
 */
class TempleOfIkov
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val playerList: PlayerList,
    private val aiInteractions: AiPlayerInteractions,
    private val launcher: ProtectedAccessLauncher,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TempleOfIkov::class.java, "temple_of_ikov") }

    /** Players who have heard Winelda's first speech (until logout). */
    private val metWinelda = HashSet<Int>()

    /** Players who killed a guardian and may take the staff (until logout). */
    private val mayTakeStaff = HashSet<Int>()

    override fun ScriptContext.startup() {
        bindTalk(IkNpcs.lucien_pub) { talkPubLucien(it) }
        bindTalk(IkNpcs.lucien_pub_vis) { talkPubLucien(it) }
        bindTalk(IkNpcs.lucien_house) { talkHouseLucien(it) }
        bindTalk(IkNpcs.lucien_house_talk) { talkHouseLucien(it) }
        bindTalk(IkNpcs.lucien_house_fight) { talkHouseLucien(it) }
        onModifyNpcHit(IkNpcs.lucien_house) { lucienHit(npc, hit) }
        onModifyNpcHit(IkNpcs.lucien_house_fight) { lucienHit(npc, hit) }

        for (guardian in IkNpcs.guardians) bindTalk(guardian) { talkGuardian(it) }
        onOpObj3(IkObjs.staff) { takeStaff(it.obj) }

        bindTalk(IkNpcs.winelda) { talkWinelda(it) }
        // Back across the lava: she is out of reach from the far bank.
        onApNpc1(IkNpcs.winelda) { if (isWithinApRange(it.npc, WINELDA_REACH)) talkWinelda(it.npc) }

        onEvent<NpcKilledEvent> { npcKilled(this) }
    }

    private fun ScriptContext.bindTalk(npc: NpcType, action: suspend ProtectedAccess.(Npc) -> Unit) {
        val op = npcTypes[npc].op.indexOfFirst { it.equals("Talk-to", ignoreCase = true) } + 1
        if (op == 0 || isNpcOpBound(npc, op)) return
        onNpcOpLabel(npcTypes, npc, "Talk-to", action)
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TEMPLE_OF_IKOV)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(TEMPLE_OF_IKOV, value)
    }

    private suspend fun ProtectedAccess.section(npc: Npc?, title: String, hooks: QuestDialogueHooks = IkHooks(this)) =
        runQuestSection(npc, dialogue, title, hooks, ikSpeakers)

    /* Lucien in the Flying Horse Inn */

    private suspend fun ProtectedAccess.talkPubLucien(npc: Npc) {
        when {
            state >= COMPLETE -> startDialogue(npc) {
                // Authored (not Wiki): the pub Lucien is normally gone by now.
                chatNpc(neutral, "I have no more need of you.")
            }
            state >= STARTED -> {
                val lost = !ikOwns(IkObjs.pendant_lucien)
                val hooks =
                    IkHooks(
                        this,
                        conditions =
                            mapOf(
                                "If the player lost the pendant:" to { lost },
                                "If the player still has the pendant:" to { !lost },
                            ),
                        boxes =
                            mapOf(
                                "Lucien has given you another pendant!" to {
                                    player.invAddOrDrop(objRepo, IkObjs.pendant_lucien)
                                    false
                                }
                            ),
                    )
                section(npc, "Talking to Lucien again", hooks)
            }
            else -> startQuest(npc)
        }
    }

    private suspend fun ProtectedAccess.startQuest(npc: Npc) {
        var accepted = false
        var viaReward = false
        val full = inv.isFull()
        val missing = stat(stats.thieving) < THIEVING_LEVEL || stat(stats.ranged) < RANGED_LEVEL
        val hooks =
            IkHooks(
                this,
                conditions =
                    mapOf(
                        "If the player's inventory is full:" to { full },
                        "Without the required skills or recommended combat level to complete it:" to { missing },
                    ),
                options =
                    mapOf(
                        "Yes." to { accepted = true },
                        "Whats the reward?!" to { viaReward = true },
                    ),
            )
        section(npc, "Talking to Lucien", hooks)
        if (!accepted || state != 0) return
        // The reward branch's "Yes." only points back ("above"): run the accepting lines.
        if (viaReward) runQuestBranch(npc, dialogue, "Talking to Lucien", "Yes.", IkHooks(this), ikSpeakers)
        player.invAddOrDrop(objRepo, IkObjs.pendant_lucien)
        setState(STARTED)
    }

    /* Lucien at his house */

    private suspend fun ProtectedAccess.talkHouseLucien(npc: Npc) {
        if (state >= COMPLETE || state < STARTED) {
            startDialogue(npc) {
                // Authored (not Wiki).
                chatNpc(neutral, "Leave me be. I have business to attend to.")
            }
            return
        }
        var handIn = false
        val staff = carries(IkObjs.staff)
        val hooks =
            IkHooks(
                this,
                conditions =
                    mapOf(
                        "If the player does not have the staff:" to { !staff || state == HELPING },
                        "If the player has the staff:" to { staff && state != HELPING },
                    ),
                options = mapOf("Yes! Here it is." to { handIn = true }),
                boxes =
                    mapOf(
                        "You give Lucien the Staff of Armadyl." to {
                            if (carries(IkObjs.staff)) invDel(inv, IkObjs.staff) else handIn = false
                            false
                        }
                    ),
            )
        section(npc, "Talking to Lucien near Varrock", hooks)
        if (handIn) finish()
    }

    /** Zero damage unless the attacker wears the Armadyl pendant, with the "mind trick". */
    private fun lucienHit(npc: Npc, hit: HitBuilder) {
        if (!hit.isFromPlayer) return
        val uid = hit.sourceUid ?: return
        val player = PlayerUid(uid).resolve(playerList) ?: return
        if (IkObjs.pendant_armadyl in player.worn) return
        hit.damage = 0
        launcher.launch(player) {
            runQuestSection(npc, dialogue, "Attacking Lucien without wearing the Armadyl pendant", IkHooks(this), ikSpeakers)
        }
    }

    /* The guardians */

    private suspend fun ProtectedAccess.talkGuardian(npc: Npc) {
        when {
            carries(IkObjs.staff) -> section(npc, "Talking to a Guardian of Armadyl while carrying the staff of Armadyl")
            state == HELPING -> {
                val lost = !ikOwns(IkObjs.pendant_armadyl)
                val hooks =
                    IkHooks(
                        this,
                        conditions =
                            mapOf(
                                "If the player lost the Armadyl pendant:" to { lost },
                                "If the player still has the Armadyl pendant:" to { !lost },
                            ),
                    )
                section(npc, "Talking to the Guardian of Armadyl after agreeing to kill Lucien", hooks)
                if (lost) {
                    player.invAddOrDrop(objRepo, IkObjs.pendant_armadyl)
                    mes("The guardian has given you a pendant.") // Authored (not Wiki): the replacement.
                }
            }
            IkObjs.pendant_lucien in worn -> {
                section(npc, "Talking to a Guardian of Armadyl while wearing the Pendant of Lucien")
                npc.opPlayer2(player, aiInteractions)
            }
            else -> {
                var attack = false
                var helping = false
                val hooks =
                    IkHooks(
                        this,
                        options =
                            mapOf(
                                "Ok! I'll help!" to { helping = true },
                                // The transcript's {{qact}} attack (the converter drops it).
                                "Then you must die!" to { attack = true },
                            ),
                        actions = mapOf("The guardian attacks the player." to { attack = true }),
                        boxes =
                            mapOf(
                                "The guardian has given you a pendant." to {
                                    player.invAddOrDrop(objRepo, IkObjs.pendant_armadyl)
                                    false
                                }
                            ),
                    )
                section(npc, "Talking to a Guardian of Armadyl while not wearing the Pendant of Lucien", hooks)
                if (attack) npc.opPlayer2(player, aiInteractions)
                if (helping && state in STARTED until COMPLETE) {
                    setState(HELPING)
                    VarPlayerIntMapSetter.set(player, IkVarBits.lucien_vis, LUCIEN_ATTACKABLE)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.takeStaff(obj: Obj) {
        if (obj.coords != STAFF_TABLE) {
            // A dropped staff elsewhere: a plain take.
            if (inv.hasFreeSpace() && objRepo.del(obj)) invAdd(inv, IkObjs.staff)
            return
        }
        when {
            state == HELPING -> section(null, "Taking the Staff of Armadyl after agreeing to kill Lucien")
            carries(IkObjs.staff) -> mes("I already have the staff.") // Wiki {{mes}}
            player.uid.packed !in mayTakeStaff -> {
                val guardian = nearestGuardian(player.coords)
                if (guardian != null) {
                    guardian.say("That is not thine to take!")
                    guardian.opPlayer2(player, aiInteractions)
                } else {
                    mes("A guardian bars your way.") // Authored (not Wiki): no guardian nearby.
                }
            }
            !inv.hasFreeSpace() -> mes("You don't have enough inventory space.")
            else -> {
                objRepo.del(obj)
                invAdd(inv, IkObjs.staff)
                if (state in STARTED until COMPLETE) {
                    VarPlayerIntMapSetter.set(player, IkVarBits.lucien_vis, LUCIEN_TALK)
                }
            }
        }
    }

    /** The nearest living guardian of the temple (the one that defends the staff). */
    private fun nearestGuardian(coords: CoordGrid): Npc? {
        val ids = IkNpcs.guardians.mapTo(HashSet()) { it.id }
        return npcRepo
            .findAll(ZoneKey.from(coords), GUARDIAN_ZONES)
            .filter { it.type.id in ids && it.hitpoints > 0 }
            .minByOrNull { it.coords.chebyshevDistance(coords) }
    }

    /* Winelda */

    private suspend fun ProtectedAccess.talkWinelda(npc: Npc) {
        if (state >= WINELDA_PAID) {
            section(npc, "Talking to Winelda after giving the limpwurts")
            crossLava()
            return
        }
        val roots = invTotal(inv, IkObjs.limpwurt)
        val key = player.uid.packed
        if (key !in metWinelda && roots < ROOTS) {
            metWinelda += key
            section(npc, "Talking to Winelda")
            return
        }
        metWinelda += key
        val hooks =
            IkHooks(
                this,
                conditions =
                    mapOf(
                        "If the player has no limpwurt roots:" to { roots == 0 },
                        "If the player has 1-19 limpwurt roots:" to { roots in 1 until ROOTS },
                        "If the player has 20 limpwurt roots:" to { roots >= ROOTS },
                    ),
            )
        section(npc, "Talking to Winelda again", hooks)
        if (roots < ROOTS || !carries(IkObjs.limpwurt, ROOTS)) return
        invDel(inv, IkObjs.limpwurt, ROOTS)
        if (state in STARTED until WINELDA_PAID) setState(WINELDA_PAID)
        crossLava()
    }

    private suspend fun ProtectedAccess.crossLava() {
        val east = player.coords.x >= LAVA_X
        telejump(if (east) WINELDA_WEST else WINELDA_EAST)
        delay(1)
    }

    /* Kills */

    private fun npcKilled(event: NpcKilledEvent) {
        val npc = event.npc
        val player = event.hero
        val ids = setOf(npc.type.id, npc.visType.id)
        when {
            IkNpcs.guardians.any { it.id in ids } -> guardianKilled(player)
            IkNpcs.lucien_house.id in ids || IkNpcs.lucien_house_fight.id in ids -> lucienKilled(player)
        }
    }

    private fun guardianKilled(player: Player) {
        mayTakeStaff += player.uid.packed
        // Wiki: after the quest the pendant is got again by killing the guardians.
        val state = player.questState(TEMPLE_OF_IKOV)
        val owns = IkObjs.pendant_armadyl in player.inv || IkObjs.pendant_armadyl in player.worn
        if (state >= COMPLETE && !owns) {
            player.invAddOrDrop(objRepo, IkObjs.pendant_armadyl)
            player.mes("The guardian drops a pendant.") // Authored (not Wiki).
        }
    }

    private fun lucienKilled(player: Player) {
        if (player.questState(TEMPLE_OF_IKOV) != HELPING) return
        val launched =
            launcher.launch(player) {
                section(null, "Defeating Lucien")
                finish()
            }
        if (!launched) {
            // Busy (e.g. mid-dialogue): grant without the scroll.
            player.mes("Lucien: You have defeated me for now! I shall reappear in the North!")
            player.statAdvance(stats.ranged, RANGED_XP * xpMods.get(player, stats.ranged))
            player.statAdvance(stats.fletching, FLETCHING_XP * xpMods.get(player, stats.fletching))
            VarPlayerIntMapSetter.set(player, IkVarBits.lucien_vis, LUCIEN_GONE)
            if (player.grantQuest(TEMPLE_OF_IKOV)) {
                player.mes("Congratulations, you've completed a quest: Temple of Ikov")
            }
        }
    }

    private fun ProtectedAccess.finish() {
        if (state >= COMPLETE) return
        statAdvance(stats.ranged, RANGED_XP * xpMods.get(player, stats.ranged))
        statAdvance(stats.fletching, FLETCHING_XP * xpMods.get(player, stats.fletching))
        VarPlayerIntMapSetter.set(player, IkVarBits.lucien_vis, LUCIEN_GONE)
        completeQuest(TEMPLE_OF_IKOV)
    }

    companion object {
        const val STARTED = 1
        const val GATE_OPEN = 2
        const val WARRIOR_DEAD = 3
        const val WINELDA_PAID = 4
        const val HELPING = 5
        const val COMPLETE = 80

        const val THIEVING_LEVEL = 42
        const val RANGED_LEVEL = 40
        /** Cache db stat_xp_awarded ranged 105000, fletching 80000 (tenths); Wiki 10,500 / 8,000. */
        const val RANGED_XP = 10500.0
        const val FLETCHING_XP = 8000.0

        private const val ROOTS = 20
        private const val WINELDA_REACH = 10
        private const val GUARDIAN_ZONES = 2
        private const val LUCIEN_TALK = 1
        private const val LUCIEN_ATTACKABLE = 2
        private const val LUCIEN_GONE = 3

        /* Positions from the rev 243 map (terrain + loc decode). */
        /** The lava stream west of Winelda runs x 2656-2662. */
        private const val LAVA_X = 2659
        private val WINELDA_WEST = CoordGrid(2654, 9876, 0)
        private val WINELDA_EAST = CoordGrid(2664, 9877, 0)
        /** The staff's spawn on the temple table (`egypt_table_2` 2638,9906). */
        private val STAFF_TABLE = CoordGrid(2638, 9906, 0)

        val TEMPLE_OF_IKOV =
            QuestDef(
                name = "Temple of Ikov",
                varp = { IkVarps.ikov },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("10,500 Ranged XP", "8,000 Fletching XP"),
                scrollObj = { IkObjs.staff },
            )
    }
}

/* Shared by the Temple of Ikov scripts. */

/**
 * Hooks for the Temple of Ikov transcript: like SimpleQuestHooks, but the transcript's {{mes}}
 * lines ([IK_MES]) go to the chatbox as game messages instead of a message box.
 */
internal class IkHooks(
    private val access: ProtectedAccess,
    private val conditions: Map<String, () -> Boolean> = emptyMap(),
    private val options: Map<String, suspend () -> Unit> = emptyMap(),
    private val boxes: Map<String, suspend () -> Boolean> = emptyMap(),
    private val actions: Map<String, suspend () -> Unit> = emptyMap(),
) : QuestDialogueHooks {
    override fun condition(text: String): Boolean = conditions[text]?.invoke() ?: false

    override suspend fun Dialogue.onOption(label: String) {
        options[label]?.invoke()
    }

    override suspend fun Dialogue.onBox(text: String): Boolean {
        val handler = boxes[text]
        if (handler != null && handler()) return true
        if (text in IK_MES) {
            access.mes(text) // Wiki {{mes}}
            return true
        }
        return false
    }

    override suspend fun Dialogue.onAction(tag: String) {
        actions[tag]?.invoke()
    }
}

internal val ikSpeakers: Map<String, NpcType> by lazy {
    mapOf(
        "Lucien" to IkNpcs.lucien_house_fight,
        "Fire Warrior of Lesarkus" to IkNpcs.fire_warrior,
        "Guardian of Armadyl" to IkNpcs.guardian_male,
        "Winelda" to IkNpcs.winelda,
    )
}

internal fun ProtectedAccess.ikOwns(obj: ObjType): Boolean =
    carries(obj) || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

/** The transcript's {{mes}} lines (game messages, not message boxes). */
internal val IK_MES: Set<String> =
    setOf(
        "As you reach to open the door a great terror overcomes you!",
        "The bridge gives way under your weight!",
        "Good thing that lava was shallow!",
        "You search the chest, but find nothing.",
        "I already have the staff.",
        "You decide not to steal the staff as you have agreed to help the Guardians.",
    )
