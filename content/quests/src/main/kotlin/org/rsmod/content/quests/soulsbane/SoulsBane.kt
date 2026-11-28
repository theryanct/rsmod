package org.rsmod.content.quests.soulsbane

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * A Soul's Bane (OSRS Wiki). Quest varbit `soulbane_prog` (bits 0-4 of `soulbane`), end state 13.
 * The rift's rooms are the cache's own (map squares 46-48_81) and their multilocs read the quest's
 * varbits: `soulbane_riftrope_pres` (the rope at the rift's edge), `soulbane_anger_flamepres` (the
 * rage room's firewall), `soulbane_fear_exitlit`, `soulbane_confu_door1-5pres` / `door6open`,
 * `soulbane_hope_bridgepres`; `soulbane_tolna_pres` shows Tolna on the surface afterwards.
 * Stages are ours:
 * - 1 Launa (the rift east of Varrock) asks you to find her son and husband; a rope on the rift's
 *   edge (2). 3 Rage: the rack's anger weapons each hurt only one kind of angry creature; enough
 *   kills fill the rage (4). Fear: dark holes hide fear reapers; five killed (6). Confusion: only one
 *   of the beasts is real; five real kills open the last door (8). Hopelessness: five hopeless
 *   creatures (10). Tolna's body in three forms (11); human Tolna again (12) returns to the
 *   surface; 13 Tolna's thanks: 1 QP, 500 Defence and 500 Hitpoints XP, 500 coins.
 *
 * Simplified: no instance (players share the rooms); you go straight into the next unfinished room;
 * the cutscenes are their dialogue; the confusion doors are just the way on once the room is done.
 */
class SoulsBane
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(SoulsBane::class.java, "souls_bane") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Launa" to SbNpcs.launa,
            "Tolna" to SbNpcs.tolna,
            "Brana" to SbNpcs.brana,
            "Tolna's Voice" to SbNpcs.tolna,
        )
    }

    override fun ScriptContext.startup() {
        onOpNpc1(SbNpcs.launa_multi) { talkLauna(it.npc) }
        onOpNpc1(SbNpcs.tolna_multi) { talkTolnaSurface(it.npc) }
        onNpcOpLabel(npcTypes, SbNpcs.brana, "Talk-to") { talkBrana(it) }
        onNpcOpLabel(npcTypes, SbNpcs.tolna, "Talk-to") { talkTolnaRift(it) }

        onOpLocU(SbLocs.edge, SbObjs.rope) { tieRope() }
        onLocOpLabel(locTypes, SbLocs.edge_rope, "Enter") { enterRift() }

        for (rack in SbLocs.racks) onLocOpLabel(locTypes, rack, "Take-from") { takeWeapon() }
        for ((creature, weapon) in SbNpcs.angry) onModifyNpcHit(creature) { onlyWith(hit, weapon()) }
        for (fake in SbNpcs.fakes) onModifyNpcHit(fake) { if (hit.isFromPlayer) hit.damage = 0 }
        onLocOpLabel(locTypes, SbLocs.rage_exit, "Enter") { leave(RAGE_DONE, FEAR_START, "Room of Fear") }
        for (hole in SbLocs.holes) onLocOpLabel(locTypes, hole, "Look-inside") { lookInHole(it.coords) }
        onLocOpLabel(locTypes, SbLocs.fear_exit, "Enter") { leave(FEAR_DONE, CONFUSION_START, "Room of Confusion") }
        for (door in SbLocs.doors) onLocOpLabel(locTypes, door, "Enter") { confusingDoor() }
        onLocOpLabel(locTypes, SbLocs.hope_exit, "Use") { enterFinal() }

        onEvent<NpcKilledEvent> { killed(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SOULS_BANE)

    private fun Player.setVarbit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(this, varbit, value)
    }

    /* Launa and the rift's edge */

    private suspend fun ProtectedAccess.talkLauna(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                runQuestSection(npc, dialogue, "Talking with Launa to start", SimpleQuestHooks(options = mapOf("Yes" to { start = true })), speakers)
                if (start) player.setQuestState(SOULS_BANE, STARTED)
            }
            RAGE_DONE -> runQuestSection(npc, dialogue, "Talking to Launa after watching the cutscene of Tolna leaving", speakers = speakers)
            FEAR_DONE -> runQuestSection(npc, dialogue, "Talking to Launa after completing the room of fear", speakers = speakers)
            CONFUSION_DONE -> runQuestSection(npc, dialogue, "Talking to Launa after completing the confusion room", speakers = speakers)
            HOPE_DONE -> runQuestSection(npc, dialogue, "Launa after the Hopelessness room", speakers = speakers)
            BODY_DONE -> runQuestSection(npc, dialogue, "Talking to Launa after defeating Tolna's body", speakers = speakers)
            else -> runQuestSection(npc, dialogue, "Talking with Launa before going down", speakers = speakers)
        }
    }

    private fun ProtectedAccess.tieRope() {
        if (state < STARTED) return
        if (player.vars[SbVarBits.rope] == 0) {
            invDel(inv, SbObjs.rope)
            player.setVarbit(SbVarBits.rope, 1)
            mes("You tie the rope to the edge of the rift.") // Authored (not Wiki).
        }
        if (state == STARTED) player.setQuestState(SOULS_BANE, ROPE)
    }

    /** Climbing down drops you in the next room you haven't finished. */
    private suspend fun ProtectedAccess.enterRift() {
        when {
            state < ROPE -> mes("You should speak to Launa first.") // Authored (not Wiki).
            state < RAGE_DONE -> {
                if (state == ROPE) player.setQuestState(SOULS_BANE, RAGE)
                telejump(RAGE_START)
                runQuestSection(null, dialogue, "Room of Rage", speakers = speakers)
            }
            state < FEAR_DONE -> {
                telejump(FEAR_START)
                runQuestSection(null, dialogue, "Room of Fear", speakers = speakers)
            }
            state < CONFUSION_DONE -> {
                telejump(CONFUSION_START)
                runQuestSection(null, dialogue, "Room of Confusion", speakers = speakers)
            }
            state < HOPE_DONE -> {
                telejump(HOPE_START)
                runQuestSection(null, dialogue, "Room of Hopelessness", speakers = speakers)
            }
            state < FREED -> enterFinal()
            else -> mes("You have no reason to go back down there right now.") // Authored (not Wiki).
        }
    }

    private suspend fun ProtectedAccess.leave(done: Int, next: CoordGrid, intro: String) {
        if (state < done) {
            mes("You can't leave this room until you've dealt with it.") // Authored (not Wiki).
            return
        }
        telejump(next)
        runQuestSection(null, dialogue, intro, speakers = speakers)
    }

    /* Rage */

    private suspend fun ProtectedAccess.takeWeapon() {
        val section = dialogue.section("Weapon rack")
        var picked = -1
        val labels = listOf("The Sword", "The Spear", "The Mace", "The Battleaxe")
        val hooks = SimpleQuestHooks(options = labels.withIndex().associate { (i, label) -> label to { picked = i } })
        startDialogue { QuestDialogueRunner(hooks).run(this, section) }
        val weapon = SbObjs.weapons.getOrNull(picked) ?: return
        if (carries(weapon) || invTotal(worn, weapon) > 0) return
        invAddOrDrop(objRepo, weapon)
    }

    private fun onlyWith(hit: HitBuilder, weapon: ObjType) {
        if (hit.isFromPlayer && !hit.isRighthandObj(weapon)) hit.damage = 0
    }

    /* Fear */

    private suspend fun ProtectedAccess.lookInHole(at: CoordGrid) {
        if (state != RAGE_DONE) return
        val nodes = pick(dialogue.section("Look-inside dark holes"))
        startDialogue { QuestDialogueRunner(SimpleQuestHooks()).run(this, nodes) }
        if (random.of(1, REAPER_ODDS) != 1) return
        val reaper = Npc(npcTypes[SbNpcs.reaper], at)
        npcRepo.add(reaper, ROOM_NPC_TICKS)
        reaper.opPlayer2(player, aiInteractions)
    }

    /** One of a section's "Dialogue N" variants at random. */
    private fun pick(section: List<Node>): List<Node> {
        val variants = section.filter { it is Node.Option && it.label.startsWith("Dialogue ") }
        if (variants.isEmpty()) return section
        return variants[random.of(0, variants.lastIndex)].children
    }

    /* Confusion and hopelessness */

    private suspend fun ProtectedAccess.confusingDoor() {
        if (state < CONFUSION_DONE) {
            mes("The door leads nowhere.") // Authored (not Wiki).
            return
        }
        telejump(HOPE_START)
        runQuestSection(null, dialogue, "Room of Hopelessness", speakers = speakers)
    }

    private suspend fun ProtectedAccess.enterFinal() {
        if (state < HOPE_DONE) {
            mes("You can't leave this room until you've dealt with it.") // Authored (not Wiki).
            return
        }
        telejump(FINAL_START)
        if (state == HOPE_DONE) {
            runQuestSection(null, dialogue, "Finding Tolna", speakers = speakers)
            spawnTolnaForm(SbNpcs.forms.first())
        } else if (state == BODY_DONE) {
            spawnHumanTolna()
        }
    }

    private fun ProtectedAccess.spawnTolnaForm(type: NpcType) {
        val form = Npc(npcTypes[type], FINAL_TOLNA)
        npcRepo.add(form, ROOM_NPC_TICKS)
        form.opPlayer2(player, aiInteractions)
    }

    private fun spawnHumanTolna() {
        npcRepo.add(Npc(npcTypes[SbNpcs.tolna], FINAL_TOLNA), ROOM_NPC_TICKS)
    }

    /* Kills */

    private fun killed(event: NpcKilledEvent) {
        val player = event.hero
        val state = player.questState(SOULS_BANE)
        val id = event.npc.type.id
        when {
            state == RAGE && SbNpcs.angry.any { it.first.id == id } -> {
                val kills = player.vars[SbVarps.rage_kills] + 1
                VarPlayerIntMapSetter.set(player, SbVarps.rage_kills, kills)
                if (kills >= RAGE_KILLS) {
                    player.setQuestState(SOULS_BANE, RAGE_DONE)
                    player.setVarbit(SbVarBits.flame, 1)
                    talk(player, "After filling the rage level")
                } else {
                    player.mes("Your rage level rises.") // Authored (not Wiki).
                }
            }
            state == RAGE_DONE && id == SbNpcs.reaper.id -> {
                val kills = player.vars[SbVarBits.fear_kills] + 1
                player.setVarbit(SbVarBits.fear_kills, kills)
                if (kills >= ROOM_KILLS) {
                    player.setQuestState(SOULS_BANE, FEAR_DONE)
                    player.setVarbit(SbVarBits.fear_exit, 1)
                }
                talk(player, FEAR_LINES.getOrElse(kills - 1) { FEAR_LINES.last() })
            }
            state == FEAR_DONE && id == SbNpcs.creeper.id -> {
                val kills = SbVarBits.doors.count { player.vars[it] != 0 } + 1
                SbVarBits.doors.getOrNull(kills - 1)?.let { player.setVarbit(it, 1) }
                if (kills >= ROOM_KILLS) {
                    player.setQuestState(SOULS_BANE, CONFUSION_DONE)
                    player.setVarbit(SbVarBits.door6, 1)
                    talk(player, "After revealing the final door")
                } else {
                    talk(player, "Killing the last confusion beast each time")
                }
            }
            state == CONFUSION_DONE && SbNpcs.hopeless.any { it.id == id } -> {
                val kills = player.vars[SbVarBits.hope_kills] + 1
                player.setVarbit(SbVarBits.hope_kills, kills)
                if (kills >= ROOM_KILLS) {
                    player.setQuestState(SOULS_BANE, HOPE_DONE)
                    player.setVarbit(SbVarBits.bridge, 1)
                }
                talk(player, HOPE_LINES.getOrElse(kills - 1) { HOPE_LINES.last() })
            }
            state == HOPE_DONE && SbNpcs.forms.any { it.id == id } -> {
                val next = SbNpcs.forms.getOrNull(SbNpcs.forms.indexOfFirst { it.id == id } + 1)
                if (next != null) {
                    protectedAccess.launch(player) { spawnTolnaForm(next) }
                } else {
                    player.setQuestState(SOULS_BANE, BODY_DONE)
                    spawnHumanTolna()
                    player.mes("Tolna's body is defeated. Talk to him.") // Authored (not Wiki).
                }
            }
        }
    }

    private fun talk(player: Player, section: String) {
        protectedAccess.launch(player) { runQuestSection(null, dialogue, section, speakers = speakers) }
    }

    /* Brana and Tolna */

    private suspend fun ProtectedAccess.talkBrana(npc: Npc) {
        val section = if (state >= BODY_DONE) "Talking to Brana after defeating Tolna's body" else "Talking to Brana"
        runQuestSection(npc, dialogue, section, speakers = speakers)
    }

    private suspend fun ProtectedAccess.talkTolnaRift(npc: Npc) {
        if (state != BODY_DONE) return
        runQuestSection(npc, dialogue, "Talking to human Tolna", speakers = speakers)
        npcRepo.del(npc, Int.MAX_VALUE)
        player.setQuestState(SOULS_BANE, FREED)
        player.setVarbit(SbVarBits.tolna_surface, 1)
        telejump(SURFACE)
    }

    private suspend fun ProtectedAccess.talkTolnaSurface(npc: Npc) {
        runQuestSection(npc, dialogue, "Talking to Tolna on the surface", speakers = speakers)
        if (state != FREED) return
        invAddOrDrop(objRepo, SbObjs.coins, REWARD_COINS)
        statAdvance(stats.defence, XP * xpMods.get(player, stats.defence))
        statAdvance(stats.hitpoints, XP * xpMods.get(player, stats.hitpoints))
        completeQuest(SOULS_BANE)
    }

    companion object {
        const val STARTED = 1
        const val ROPE = 2
        const val RAGE = 3
        const val RAGE_DONE = 4
        const val FEAR_DONE = 6
        const val CONFUSION_DONE = 8
        const val HOPE_DONE = 10
        const val BODY_DONE = 11
        const val FREED = 12
        const val COMPLETE = 13

        private const val RAGE_KILLS = 6
        private const val ROOM_KILLS = 5
        private const val REAPER_ODDS = 2
        private const val ROOM_NPC_TICKS = 300
        private const val REWARD_COINS = 500
        private const val XP = 500.0

        private val FEAR_LINES =
            listOf(
                "After killing the first reaper",
                "After killing the second reaper",
                "After killing the third reaper",
                "After killing the forth reaper",
                "After killing the last reaper",
            )
        private val HOPE_LINES =
            listOf(
                "After killing the first hopeless creature",
                "After killing the second hopeless creature",
                "After killing the third hopeless creature",
                "After killing the fourth hopeless creature",
                "After killing the last hopeless creature",
            )

        private val RAGE_START = CoordGrid(3020, 5235, 0)
        private val FEAR_START = CoordGrid(3049, 5228, 0)
        private val CONFUSION_START = CoordGrid(3048, 5205, 0)
        private val HOPE_START = CoordGrid(3015, 5200, 0)
        private val FINAL_START = CoordGrid(2970, 5212, 1)
        private val FINAL_TOLNA = CoordGrid(2976, 5214, 1)
        private val SURFACE = CoordGrid(3307, 3455, 0)

        val SOULS_BANE =
            QuestDef(
                name = "A Soul's Bane",
                varbit = { SbVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("500 Defence XP", "500 Hitpoints XP", "500 coins", "Tolna's rift for training"),
                scrollObj = { SbObjs.coins },
                listRow = "quest_soulsbane",
            )
    }
}

// Every cache ref A Soul's Bane uses (re-check after cache updates).

internal object SbNpcs : NpcReferences() {
    /** Multinpc on `soulbane_launa_pres`: Launa at 0. */
    val launa_multi = find("soulbane_launa_multi")
    val launa = find("soulbane_launa")
    /** Multinpc on `soulbane_tolna_pres`: Tolna at the surface from 1. */
    val tolna_multi = find("soulbane_tolna_multi")
    val tolna = find("soulbane_tolna")
    val brana = find("soulbane_brana")
    private val bear = find("soulbane_anger_bear")
    private val unicorn = find("soulbane_anger_unicorn")
    private val rat = find("soulbane_anger_rat")
    private val goblin = find("soulbane_anger_goblin")
    /** Each angry creature and the anger weapon that hurts it (Wiki). */
    val angry: List<Pair<NpcType, () -> ObjType>> =
        listOf(
            unicorn to { SbObjs.sword },
            bear to { SbObjs.spear },
            rat to { SbObjs.mace },
            goblin to { SbObjs.axe },
        )
    val reaper = find("soulbane_fear_reaper")
    val creeper = find("soulbane_confu_creeper")
    val fakes =
        listOf(
            find("soulbane_confu_creeper_fake1"),
            find("soulbane_confu_creeper_fake2"),
            find("soulbane_confu_creeper_fake3"),
            find("soulbane_confu_creeper_fake4"),
        )
    val hopeless = listOf(find("soulbane_hope_monst1"), find("soulbane_hope_monst2"), find("soulbane_hope_monst3"))
    val forms = listOf(find("soulbane_final_tolna1"), find("soulbane_final_tolna2"), find("soulbane_final_tolna3"))
}

internal object SbLocs : LocReferences() {
    /** Children of `soulbane_falloff2_rope_multi` (`soulbane_riftrope_pres`). */
    val edge = find("soulbane_falloff2")
    val edge_rope = find("soulbane_falloff2_rope")
    val racks =
        listOf(
            find("soulbane_rack_all"),
            find("soulbane_rack_no_sword"),
            find("soulbane_rack_no_spear"),
            find("soulbane_rack_no_mace"),
            find("soulbane_rack_no_axe"),
        )
    val rage_exit = find("soul_bane_awall_void_exit")
    val holes =
        listOf(
            find("soul_bane_fwall_void"),
            find("soul_bane_fwall_void2"),
            find("soul_bane_fwall_void3"),
            find("soul_bane_fwall_void4"),
            find("soul_bane_fwall_void5"),
            find("soul_bane_fwall_void6"),
        )
    val fear_exit = find("soul_bane_fwall_exit")
    val doors =
        listOf(
            find("soul_bane_con_door1_closed"),
            find("soul_bane_con_door2_closed"),
            find("soul_bane_con_door3_closed"),
            find("soul_bane_con_door4_closed"),
            find("soul_bane_con_door5_closed"),
            find("soul_bane_con_door6_closed"),
            find("soul_bane_con_door6_open1"),
        )
    val hope_exit = find("soul_bane_hwall_void_exit")
}

internal object SbObjs : ObjReferences() {
    val rope = find("rope")
    val coins = find("coins")
    val sword = find("soulbane_anger_swordq")
    val spear = find("soulbane_anger_spearq")
    val mace = find("soulbane_anger_maceq")
    val axe = find("soulbane_anger_axeq")
    val weapons = listOf(sword, spear, mace, axe)
}

internal object SbVarBits : VarBitReferences() {
    val quest = find("soulbane_prog")
    val rope = find("soulbane_riftrope_pres")
    val flame = find("soulbane_anger_flamepres")
    val fear_kills = find("soulbane_fear_killedtally")
    val fear_exit = find("soulbane_fear_exitlit")
    val doors =
        listOf(
            find("soulbane_confu_door1pres"),
            find("soulbane_confu_door2pres"),
            find("soulbane_confu_door3pres"),
            find("soulbane_confu_door4pres"),
            find("soulbane_confu_door5pres"),
        )
    val door6 = find("soulbane_confu_door6open")
    val hope_kills = find("soulbane_hope_killedtally")
    val bridge = find("soulbane_hope_bridgepres")
    val tolna_surface = find("soulbane_tolna_pres")
}

internal object SbVarps : VarpReferences() {
    /** Server varp (65801): angry creatures killed with the right weapon. */
    val rage_kills = find("sb_rage_kills")
}

internal object SbVarpBuilds : VarpBuilder() {
    init {
        build("sb_rage_kills")
    }
}
