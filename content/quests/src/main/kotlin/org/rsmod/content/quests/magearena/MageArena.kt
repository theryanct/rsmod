package org.rsmod.content.quests.magearena

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
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
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mage Arena I (OSRS Wiki miniquest). The quest varp is the cache's `magearena` (0-8).
 * - Kolodion (Mage Arena bank, under the ruins north of the arena; 60 Magic) duels you in the arena:
 *   five forms (human, ogre, spider, ghost, demon); each death brings the next.
 * - The sparkling pool in the bank leads to the god chamber; pray at a statue for its cape; the
 *   chamber guardian swaps the cape's god for a staff; Kolodion finishes it.
 *
 * Stages: 1-5 the form being fought, 6 Kolodion beaten, 7 staff given, 8 done.
 *
 * Simplified (Authored, not Wiki): Kolodion's forms take any damage (OSRS: Magic only); leaving
 * the arena mid-duel restarts the current form when you talk to him again; the spawn and arrival
 * tiles are best guesses (Ryan-test).
 */
class MageArena
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MageArena::class.java, "mage_arena_1") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf("Kolodion" to npcTypes[MaNpcs.kolodion])
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, MaNpcs.kolodion, MAGE_ARENA_I, wants = { true }) { talkKolodion(it) }
            register(npcTypes, MaNpcs.guardian, MAGE_ARENA_I, wants = { wantsGuardian() }) {
                talkGuardian(it)
            }
        }
        onLocOpLabel(locTypes, MaLocs.bank_pool, "Step-into") { stepIntoPool(toChamber = true) }
        onLocOpLabel(locTypes, MaLocs.chamber_pool, "Step-into") { stepIntoPool(toChamber = false) }
        for ((statue, god) in MaLocs.statues.zip(God.entries)) {
            onLocOpLabel(locTypes, statue, "Pray-at") { pray(god) }
        }
        onEvent<NpcKilledEvent> { formKilled(this) }
    }

    private val ProtectedAccess.stage: Int
        get() = player.questState(MAGE_ARENA_I)

    private fun ProtectedAccess.setStage(value: Int) = player.setQuestState(MAGE_ARENA_I, value)

    /* Kolodion */

    private suspend fun ProtectedAccess.talkKolodion(npc: Npc) {
        when {
            stage == 0 && statBase(stats.magic) < MAGIC_REQUIRED ->
                runQuestSection(npc, dialogue, "Without level 60 Magic")
            stage == 0 -> {
                var fight = false
                val hooks = SimpleQuestHooks(options = mapOf("Okay, let's fight." to { fight = true }))
                runQuestSection(npc, dialogue, "Introduction", hooks)
                if (fight) startDuel(FIRST_FORM)
            }
            stage < DEFEATED -> startDuel(stage)
            stage == DEFEATED || stage == STAFF && !hasStaff() -> {
                startDialogue(npc) {
                    chatPlayer(neutral, "Hello, Kolodion.")
                    chatNpc(neutral, "Hello, young mage. You're a tough one.")
                    chatPlayer(neutral, "What now?")
                    chatNpc(
                        neutral,
                        "Step into the magic pool. It will take you to a chamber. There, you must " +
                            "decide which god you will represent in the arena.",
                    )
                }
            }
            else -> {
                val god = God.entries.firstOrNull { carries(it.staff) || invTotal(worn, it.staff) > 0 }
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            God.entries.associate { g ->
                                "If player has cast ${g.spell} (unknown) times:" to { god == g }
                            } + mapOf("If player hasn't mastered all the god spells yet:" to { true })
                    )
                runQuestSection(npc, dialogue, "Talking to Kolodion after choosing a god", hooks)
                if (stage == STAFF) completeQuest(MAGE_ARENA_I)
            }
        }
    }

    private fun ProtectedAccess.hasStaff(): Boolean =
        God.entries.any { carries(it.staff) || invTotal(worn, it.staff) > 0 }

    private suspend fun ProtectedAccess.startDuel(form: Int) {
        setStage(form)
        telejump(ARENA_CENTRE)
        delay(1)
        spawnForm(player, form)
    }

    private fun spawnForm(player: Player, form: Int) {
        val type = MaNpcs.forms[form - FIRST_FORM]
        val npc = Npc(npcTypes[type], FORM_SPAWN)
        npcRepo.add(npc, FORM_TICKS)
        npc.say(FORM_LINES[form - FIRST_FORM])
        npc.opPlayer2(player, aiInteractions)
    }

    private fun formKilled(event: NpcKilledEvent) {
        val index = MaNpcs.forms.indexOfFirst { it.id == event.npc.type.id }
        if (index < 0) return
        val player = event.hero
        val form = index + FIRST_FORM
        if (player.questState(MAGE_ARENA_I) != form) return
        if (form < LAST_FORM) {
            player.setQuestState(MAGE_ARENA_I, form + 1)
            spawnForm(player, form + 1)
            return
        }
        player.setQuestState(MAGE_ARENA_I, DEFEATED)
        player.mes("Well done, young adventurer; you truly are a worthy battle mage.")
        // Authored (not Wiki): the way to the god chamber from here.
        player.mes("Leave the arena by its lever, and step into the magic pool in the bank below the ruins.")
    }

    /* The god chamber */

    private suspend fun ProtectedAccess.stepIntoPool(toChamber: Boolean) {
        if (toChamber && stage < DEFEATED) {
            mes("You must defeat Kolodion before the pool will take you anywhere.") // Authored.
            return
        }
        runQuestSection(null, dialogue, "Entering/exiting the chamber")
        telejump(if (toChamber) CHAMBER_ARRIVE else BANK_ARRIVE)
    }

    private suspend fun ProtectedAccess.pray(god: God) {
        if (stage < DEFEATED) {
            mes("You kneel and chant, but there is no response.") // Authored (not Wiki).
            return
        }
        val full = inv.isFull()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has 1 inventory space open:" to { !full },
                        "If the player has no inventory spaces open:" to { full },
                    )
            )
        runQuestSection(null, dialogue, god.label, hooks)
        if (!full) invAdd(inv, god.cape)
    }

    private fun ProtectedAccess.wantsGuardian(): Boolean = stage in DEFEATED until COMPLETE

    private suspend fun ProtectedAccess.talkGuardian(npc: Npc) {
        val cape = God.entries.firstOrNull { carries(it.cape) || invTotal(worn, it.cape) > 0 }
        if (stage == STAFF || cape == null) {
            runQuestSection(npc, dialogue, "Talking to the chamber guardian")
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have the cape with them:" to { cape == null },
                        "If the player has the cape with them:" to { cape != null },
                    ) + God.entries.associate { g -> "If player chose ${g.label} cape:" to { cape == g } }
            )
        runQuestSection(npc, dialogue, "Talking to the chamber guardian after choosing a god", hooks)
        if (cape != null) {
            invAddOrDrop(objRepo, cape.staff)
            setStage(STAFF)
        }
    }

    private enum class God(val label: String, val spell: String) {
        SARADOMIN("Saradomin", "Saradomin Strike"),
        GUTHIX("Guthix", "Claws of Guthix"),
        ZAMORAK("Zamorak", "Flames of Zamorak");

        val cape: ObjType
            get() =
                when (this) {
                    SARADOMIN -> MaObjs.saradomin_cape
                    GUTHIX -> MaObjs.guthix_cape
                    ZAMORAK -> MaObjs.zamorak_cape
                }

        val staff: ObjType
            get() =
                when (this) {
                    SARADOMIN -> MaObjs.saradomin_staff
                    GUTHIX -> MaObjs.guthix_staff
                    ZAMORAK -> MaObjs.zamorak_staff
                }
    }

    companion object {
        const val FIRST_FORM = 1
        const val LAST_FORM = 5
        const val DEFEATED = 6
        const val STAFF = 7
        const val COMPLETE = 8

        private const val MAGIC_REQUIRED = 60
        private const val FORM_TICKS = 500

        /** The Wiki's duel lines, one per form. */
        private val FORM_LINES =
            listOf(
                "You must prove yourself... now!",
                "This is only the beginning; you can't beat me!",
                "Foolish mortal; I am unstoppable.",
                "Now you feel it... The dark energy.",
                "Aaaaaaaarrgghhhh! The power!",
            )

        /** The arena (walls 3089-3117, 3921-3946). */
        private val ARENA_CENTRE = CoordGrid(3105, 3934, 0)
        private val FORM_SPAWN = CoordGrid(3105, 3938, 0)
        /** Authored arrival tiles (Ryan-test). */
        private val BANK_ARRIVE = CoordGrid(2538, 4716, 0)
        private val CHAMBER_ARRIVE = CoordGrid(2508, 4689, 0)

        val MAGE_ARENA_I =
            QuestDef(
                name = "Mage Arena I",
                varp = { MaVarps.magearena },
                complete = COMPLETE,
                questPoints = 0,
                rewards = listOf("A god staff", "A god cape", "The god spells"),
                scrollObj = { MaObjs.saradomin_staff },
                listRow = "miniquest_magearena1",
            )
    }
}

// Every cache ref Mage Arena I uses (re-check after cache updates).

internal object MaNpcs : NpcReferences() {
    val kolodion = find("arenamage1")
    val guardian = find("magearena_guardian")
    /** Kolodion's five duel forms, in order. */
    val forms =
        listOf(find("kolhuman"), find("kologre"), find("kolspider"), find("kolethereal"), find("koldemon"))
}

internal object MaLocs : LocReferences() {
    val bank_pool = find("magearena_waterportal1")
    val chamber_pool = find("magearena_waterportal2")
    /** Saradomin, Guthix, Zamorak (the [MageArena.God] order). */
    val statues =
        listOf(
            find("magearena_statue_saradomin"),
            find("magearena_statue_guthix"),
            find("magearena_statue_zamorak"),
        )
}

internal object MaObjs : ObjReferences() {
    val saradomin_cape = find("saradomin_cape")
    val guthix_cape = find("guthix_cape")
    val zamorak_cape = find("zamorak_cape")
    val saradomin_staff = find("saradomin_staff")
    val guthix_staff = find("guthix_staff")
    val zamorak_staff = find("zamorak_staff")
}

internal object MaVarps : VarpReferences() {
    val magearena = find("magearena")
}
