package org.rsmod.content.quests.templeoftheeye

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.runemysteries.RuneMysteries.Companion.RUNE_MYSTERIES
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Temple of the Eye (OSRS Wiki). Cache quest varbit `tote`, end state 130 (the Guardians of the
 * Rift gate, ui's GotR checks tote >= 130). Persten's Al Kharid multinpc shows 0-60, the tower
 * apprentices 70-90 and Persten's tower variant 85-90, so:
 * - 10 Wizard Persten (Al Kharid) lends the eye amulet; 20 the Mage of Zamorak (Varrock) sees it;
 *   30 the Tea Seller's strong cup of tea; 40 the Mage drinks it and teleports you (once) to the
 *   Abyss; 50 a bucket of water and the amulet on the Dark Mage raise six runic energies; touching
 *   all six empowers them; 60 the Dark Mage's abyssal incantation.
 * - 62 Persten copies it and teleports you (once) to the Wizards' Tower; 70 Sedridor takes the
 *   incantation; Traiborn sends you to his apprentices' three riddles (cache varbits
 *   tote_spoken_to_*); 80 the answer is 11; 85 Sedridor agrees to the incantation; 90 the portal
 *   opens in the basement; through it, the Temple of the Eye: the cutscenes and the Guardians of
 *   the Rift tutorial are played as dialogue (ui owns the GotR mechanics); 130 complete: 1 QP,
 *   5,000 Runecraft xp, a medium pouch.
 *
 * Simplified: the runic energies may be touched in any order, the Abyss teleport lands beside the
 * Dark Mage, the tutorial has no mechanics, and Enter the Abyss is read as Rune Mysteries.
 */
class TempleOfTheEye
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(TempleOfTheEye::class.java, "temple_of_the_eye") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Wizard Persten" to TeNpcs.persten_tower,
            "Archmage Sedridor" to TeNpcs.sedridors.first(),
            "Wizard Traiborn" to TeNpcs.traiborn,
            "Apprentice Cordelia" to TeNpcs.cordelia,
            "Apprentice Felix" to TeNpcs.felix,
            "Apprentice Tamara" to TeNpcs.tamara,
            "Cordelia" to TeNpcs.cordelia,
            "Felix" to TeNpcs.felix,
            "Tamara" to TeNpcs.tamara,
            "The Great Guardian" to TeNpcs.great_guardian,
            "Dark Mage" to TeNpcs.dark_mage,
            "Mage of Zamorak" to TeNpcs.mages.first(),
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, TeNpcs.persten_alkharid, TEMPLE_OF_THE_EYE, wants = { state <= INCANTATION }) {
                talkPersten(it)
            }
            for (mage in TeNpcs.mages) {
                register(npcTypes, mage, TEMPLE_OF_THE_EYE, wants = { state in AMULET until INCANTATION }) {
                    talkMage(it)
                }
            }
            register(npcTypes, TeNpcs.tea_seller, TEMPLE_OF_THE_EYE, wants = { state == SHOWN }) {
                talkTeaSeller(it)
            }
            register(npcTypes, TeNpcs.dark_mage, TEMPLE_OF_THE_EYE, wants = { state in ABYSS..INCANTATION }) {
                talkDarkMage(it)
            }
            for (sedridor in TeNpcs.sedridors) {
                register(npcTypes, sedridor, TEMPLE_OF_THE_EYE, wants = { state in TOWER until COMPLETE }) {
                    talkSedridor(it)
                }
            }
            register(npcTypes, TeNpcs.persten_tower, TEMPLE_OF_THE_EYE, wants = { state in READY until COMPLETE }) {
                gate(it)
            }
            register(npcTypes, TeNpcs.traiborn, TEMPLE_OF_THE_EYE, wants = { state in SEDRIDOR until COMPLETE }) {
                talkTraiborn(it)
            }
            for ((name, apprentice) in TeNpcs.apprentices) {
                register(npcTypes, apprentice, TEMPLE_OF_THE_EYE, wants = { state in SEDRIDOR until COMPLETE }) {
                    talkApprentice(it, name)
                }
            }
        }
        for (mage in TeNpcs.mages) {
            onOpNpcU(mage, TeObjs.amulet) { talkMage(it.npc) }
            onOpNpcU(mage, TeObjs.tea) { talkMage(it.npc) }
        }
        onOpNpcU(TeNpcs.dark_mage, TeObjs.amulet) { talkDarkMage(it.npc) }
        onOpNpcU(TeNpcs.dark_mage, TeObjs.bucket_water) { talkDarkMage(it.npc) }
        for (sedridor in TeNpcs.sedridors) onOpNpcU(sedridor, TeObjs.incantation) { talkSedridor(it.npc) }
        for ((index, energy) in TeLocs.energies.withIndex()) {
            onLocOpLabel(locTypes, energy, "Touch") { touchEnergy(index) }
        }
        onLocOpLabel(locTypes, TeLocs.portal, "Enter") { enterPortal() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(TEMPLE_OF_THE_EYE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(TEMPLE_OF_THE_EYE, value)
    }

    private fun ProtectedAccess.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun ProtectedAccess.give(obj: org.rsmod.game.type.obj.ObjType) {
        if (!carries(obj)) invAddOrDrop(objRepo, obj)
    }

    private fun ProtectedAccess.invHooks(
        extra: Map<String, () -> Boolean> = emptyMap(),
        options: Map<String, suspend () -> Unit> = emptyMap(),
    ) =
        SimpleQuestHooks(
            options = options,
            conditions =
                mapOf(
                    "If the player has inventory space:" to { inv.freeSpace() > 0 },
                    "If the player does not have inventory space:" to { inv.freeSpace() == 0 },
                    "If the player has no inventory space:" to { inv.freeSpace() == 0 },
                    "If the player lost the amulet:" to { !carries(TeObjs.amulet) },
                    "If the player still has the amulet:" to { carries(TeObjs.amulet) },
                    "If the player has the amulet:" to { carries(TeObjs.amulet) },
                    "If the player does not have the amulet:" to { !carries(TeObjs.amulet) },
                    "If the player doesn't have the amulet:" to { !carries(TeObjs.amulet) },
                    "If the player does not have the eye amulet:" to { !carries(TeObjs.amulet) },
                    "If the player lost the incantation:" to { !carries(TeObjs.incantation) },
                    "If the player has the incantation:" to { carries(TeObjs.incantation) },
                    "If the player already has the incantation:" to { carries(TeObjs.incantation) },
                    "If the player doesn't have the incantation:" to { !carries(TeObjs.incantation) },
                    "If the player does not have 10 Runecraft:" to { statBase(stats.runecraft) < 10 },
                    "If the player has not completed Enter the Abyss:" to {
                        !player.isQuestComplete(RUNE_MYSTERIES)
                    },
                    "If the player has completed One Small Favour:" to { false },
                    "If the player has not completed One Small Favour:" to { true },
                    "If the player did not ask \"Who are you?\":" to { true },
                ) + extra,
            boxes =
                mapOf(
                    "Persten hands you the amulet." to { give(TeObjs.amulet); false },
                    "Persten hands you an amulet." to { give(TeObjs.amulet); false },
                    "The Tea Seller hands you a strong cup of tea." to { give(TeObjs.tea); false },
                    "The Dark Mage hands you an incantation." to { give(TeObjs.incantation); false },
                    "Persten hands you a copy of the incantation." to { give(TeObjs.incantation); false },
                ),
        )

    /* Wizard Persten (Al Kharid) and the Mage of Zamorak */

    private suspend fun ProtectedAccess.talkPersten(npc: Npc) {
        when {
            state == 0 -> {
                var start = false
                val hooks = invHooks(options = mapOf("Yes." to { start = true }))
                runQuestSection(npc, dialogue, "Talking to Wizard Persten", hooks)
                val eligible = statBase(stats.runecraft) >= 10 && player.isQuestComplete(RUNE_MYSTERIES)
                if (start && eligible) {
                    setState(AMULET)
                    give(TeObjs.amulet)
                }
            }
            state in AMULET until INCANTATION ->
                runQuestSection(npc, dialogue, "Talking to Wizard Persten again", invHooks())
            state == INCANTATION -> {
                var teleport = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { teleport = true }))
                runQuestSection(npc, dialogue, "Talking to Wizard Persten (2)", hooks)
                setState(TOWER)
                if (teleport) telejump(TOWER_BASEMENT)
            }
        }
    }

    private suspend fun ProtectedAccess.talkMage(npc: Npc) {
        when (state) {
            AMULET -> {
                runQuestSection(npc, dialogue, "Talking to the Mage of Zamorak", invHooks())
                setState(SHOWN)
            }
            SHOWN, TEA ->
                if (carries(TeObjs.tea)) {
                    var teleport = false
                    invDel(inv, TeObjs.tea)
                    val hooks = SimpleQuestHooks(options = mapOf("Yes." to { teleport = true }))
                    runQuestSection(npc, dialogue, "Talking to the Mage of Zamorak after getting the tea", hooks)
                    setState(ABYSS)
                    if (teleport) telejump(ABYSS_ARRIVAL)
                } else {
                    runQuestSection(npc, dialogue, "Talking to the Mage of Zamorak again", invHooks())
                }
            ABYSS -> {
                var teleport = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { teleport = true }))
                runQuestSection(npc, dialogue, "Talking to the Mage of Zamorak again after he drinks the tea", hooks)
                if (teleport) telejump(ABYSS_ARRIVAL)
            }
            else -> runQuestSection(npc, dialogue, "Talking to the Mage of Zamorak after getting the incantation")
        }
    }

    private suspend fun ProtectedAccess.talkTeaSeller(npc: Npc) {
        val section = if (carries(TeObjs.tea)) "Talking to the Tea Seller again" else "Talking to the Tea Seller"
        runQuestSection(
            npc,
            dialogue,
            section,
            invHooks(
                mapOf(
                    "If the player lost the strong cup of tea:" to { !carries(TeObjs.tea) },
                    "If the player already has the strong cup of tea:" to { carries(TeObjs.tea) },
                )
            ),
        )
        give(TeObjs.tea)
    }

    /* The Abyss */

    private suspend fun ProtectedAccess.talkDarkMage(npc: Npc) {
        val ready = carries(TeObjs.amulet) && carries(TeObjs.bucket_water)
        when {
            state == ABYSS && ready -> {
                runQuestSection(npc, dialogue, "Talking to the Dark Mage", invHooks())
                invDel(inv, TeObjs.bucket_water)
                invAdd(inv, TeObjs.bucket)
                for (energy in TeVarBits.energies) setBit(energy, ENERGY_VISIBLE)
                setState(ENERGIES)
            }
            state == ABYSS -> runQuestSection(npc, dialogue, "Talking to the Dark Mage", invHooks())
            state == ENERGIES && TeVarBits.energies.all { player.vars[it] == ENERGY_EMPOWERED } -> {
                runQuestSection(npc, dialogue, "Obtaining the incantation", invHooks())
                give(TeObjs.incantation)
                setState(INCANTATION)
            }
            state == ENERGIES -> runQuestSection(npc, dialogue, "Talking to the Dark Mage after the energy spawns")
            else -> {
                runQuestSection(npc, dialogue, "Reclaiming the incantation", invHooks())
                give(TeObjs.incantation)
            }
        }
    }

    private fun ProtectedAccess.touchEnergy(index: Int) {
        if (state != ENERGIES) return
        val varbit = TeVarBits.energies[index]
        if (player.vars[varbit] == ENERGY_EMPOWERED) return
        setBit(varbit, ENERGY_EMPOWERED)
        mes("The energy glows white.")
    }

    /* The Wizards' Tower */

    private suspend fun ProtectedAccess.talkSedridor(npc: Npc) {
        when {
            state == TOWER && carries(TeObjs.incantation) -> {
                val hooks = SimpleQuestHooks(
                    boxes = mapOf("You give the incantation to Sedridor." to { invDel(inv, TeObjs.incantation); false })
                )
                runQuestSection(npc, dialogue, "Talking to Archmage Sedridor", hooks)
                setState(SEDRIDOR)
            }
            state == TOWER -> runQuestSection(npc, dialogue, "Talking to Archmage Sedridor", invHooks())
            state in SEDRIDOR until RIDDLE -> runQuestSection(npc, dialogue, "Talking to Sedridor again")
            state in RIDDLE until COMPLETE -> gate(npc)
        }
    }

    private suspend fun ProtectedAccess.talkTraiborn(npc: Npc) {
        val seen = TeVarBits.spoken.all { player.vars[it] == 1 }
        when {
            state == SEDRIDOR && !hasMet() -> {
                runQuestSection(npc, dialogue, "Talking to Wizard Traiborn")
                setBit(TeVarBits.pre_story, 1)
            }
            state == SEDRIDOR && seen -> {
                val answer = countDialog("Enter amount:")
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the input is 11:" to { answer == RIDDLE_ANSWER },
                                "If the input is any other number:" to { answer != RIDDLE_ANSWER },
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to Traiborn after seeing all three riddles", hooks)
                if (answer == RIDDLE_ANSWER) setState(RIDDLE)
            }
            state == SEDRIDOR -> runQuestSection(npc, dialogue, "Talking to Traiborn again")
            else -> runQuestSection(npc, dialogue, "Talking to Traiborn")
        }
    }

    private fun ProtectedAccess.hasMet(): Boolean = player.vars[TeVarBits.pre_story] == 1

    private suspend fun ProtectedAccess.talkApprentice(npc: Npc, name: String) {
        val index = TeNpcs.apprentices.indexOfFirst { it.first == name }
        val spoken = TeVarBits.spoken[index]
        when {
            state == SEDRIDOR && !hasMet() -> runQuestSection(npc, dialogue, name)
            state == SEDRIDOR && player.vars[spoken] == 0 -> {
                runQuestSection(npc, dialogue, "$name (2)")
                setBit(spoken, 1)
            }
            state == SEDRIDOR -> runQuestSection(npc, dialogue, "Talking to $name again")
            else -> runQuestSection(npc, dialogue, "Talking to Apprentice $name")
        }
    }

    /** Sedridor or Persten in the basement: the incantation opens the portal (85, 90). */
    private suspend fun ProtectedAccess.gate(npc: Npc) {
        if (state == RIDDLE) setState(READY)
        if (state != READY) {
            runQuestSection(npc, dialogue, "Talking to Archmage Sedridor after entering the portal", speakers = speakers)
            return
        }
        var go = false
        val hooks = SimpleQuestHooks(options = mapOf("Let's do it." to { go = true }))
        runQuestSection(npc, dialogue, "Talking to Sedridor or Persten", hooks, speakers)
        if (!go) return
        runQuestSection(null, dialogue, "Incantation cutscene", speakers = speakers)
        setState(PORTAL)
    }

    /* The Temple of the Eye (the cutscenes and the GotR tutorial, as dialogue) */

    private suspend fun ProtectedAccess.enterPortal() {
        if (state < PORTAL) {
            runQuestSection(null, dialogue, "Entering the portal before finishing the cutscene", speakers = speakers)
            return
        }
        if (state >= COMPLETE) {
            telejump(TEMPLE)
            return
        }
        telejump(TEMPLE)
        for (section in TEMPLE_SEQUENCE) {
            runQuestSection(null, dialogue, section, speakers = speakers)
        }
        telejump(TOWER_BASEMENT)
        runQuestSection(null, dialogue, "Sedridor cutscene", speakers = speakers)
        give(TeObjs.medium_pouch)
        statAdvance(stats.runecraft, 5000.0 * xpMods.get(player, stats.runecraft))
        completeQuest(TEMPLE_OF_THE_EYE)
    }

    companion object {
        const val AMULET = 10
        const val SHOWN = 20
        const val TEA = 30
        const val ABYSS = 40
        const val ENERGIES = 50
        const val INCANTATION = 60
        const val TOWER = 62
        const val SEDRIDOR = 70
        const val RIDDLE = 80
        const val READY = 85
        const val PORTAL = 90
        const val COMPLETE = 130

        private const val ENERGY_VISIBLE = 1
        private const val ENERGY_EMPOWERED = 2
        private const val RIDDLE_ANSWER = 11

        private val ABYSS_ARRIVAL = CoordGrid(3039, 4836, 0)
        private val TOWER_BASEMENT = CoordGrid(3104, 9572, 0)
        private val TEMPLE = CoordGrid(3615, 9480, 0)

        /** The temple's cutscenes and the tutorial's main beats, in the Wiki's order. */
        private val TEMPLE_SEQUENCE =
            listOf(
                "Temple of the Eye cutscene",
                "Talking to Persten upon arrival",
                "Talking to Apprentice Cordelia (2)",
                "Talking to Apprentice Felix (2)",
                "Talking to Apprentice Tamara (2)",
                "Talking to Wizard Persten after speaking with the apprentices",
                "Abyssal rift cutscene",
                "Great Guardian cutscene",
                "Talking to Felix",
                "Talking to Tamara",
                "After placing the weak cell in the inactive cell tile",
                "Talking to Felix after placing the weak cell",
                "After assembling the first essence pile",
                "After assembling the second essence pile",
                "Talking to Tamara after assembling both essence piles",
                "After mining 5 fragments",
                "Talking to Felix after getting guardian fragments",
                "Talking to Tamara after making guardian essence",
                "Talking to Tamara after returning from the Mind Altar",
                "Powering up the Great Guardian",
                "Talking to Tamara after returning from the Water Altar",
                "Powering the guardian the second time",
                "Talking to Tamara after powering the Great Guardian",
                "Constructing the medium elemental guardian",
                "Talking to Tamara after constructing the medium elemental guardian",
                "Placing the medium cell",
                "Closing the rift",
            )

        val TEMPLE_OF_THE_EYE =
            QuestDef(
                name = "Temple of the Eye",
                varbit = { TeVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("5,000 Runecraft XP", "Access to Guardians of the Rift", "A medium pouch"),
                scrollObj = { TeObjs.amulet },
                listRow = "quest_templeoftheeye",
            )
    }
}

// Every cache ref Temple of the Eye uses (re-check after cache updates).

internal object TeNpcs : NpcReferences() {
    val persten_alkharid = find("tote_persten_alkharid_child")
    val persten_tower = find("tote_persten_tower_child")
    val mages = listOf(find("rcu_zammy_mage1a"), find("rcu_zammy_mage1b"))
    val tea_seller = find("tea_seller")
    val dark_mage = find("rcu_zammy_mage2")
    val sedridors = listOf(find("head_wizard_1op"), find("head_wizard_2op"))
    val traiborn = find("traiborn")
    val cordelia = find("tote_cordelia_wizard_tower_child")
    val felix = find("tote_felix_wizard_tower_child")
    val tamara = find("tote_tamara_wizard_tower_child")
    val great_guardian = find("tote_great_guardian")

    /** The Wiki's names for the apprentices' sections, in tote_spoken_to_* order. */
    val apprentices: List<Pair<String, NpcType>> =
        listOf("Tamara" to tamara, "Felix" to felix, "Cordelia" to cordelia)
}

internal object TeLocs : LocReferences() {
    val portal = find("tote_portal_to_gotr_child")

    /** earth, cosmic, death, nature, law, fire (the energies' Touch children). */
    val energies =
        listOf(
            find("tote_abyssal_energy_earth_vis"),
            find("tote_abyssal_energy_cosmic_vis"),
            find("tote_abyssal_energy_death_vis"),
            find("tote_abyssal_energy_nature_vis"),
            find("tote_abyssal_energy_law_vis"),
            find("tote_abyssal_energy_fire_vis"),
        )
}

internal object TeObjs : ObjReferences() {
    val amulet = find("tote_amulet")
    val tea = find("tote_cup_of_tea_strong")
    val incantation = find("tote_incantation")
    val bucket_water = find("bucket_water")
    val bucket = find("bucket_empty")
    val medium_pouch = find("rcu_pouch_medium")
}

internal object TeVarBits : VarBitReferences() {
    val quest = find("tote")
    val pre_story = find("tote_pre_story")

    /** Tamara, Felix, Cordelia. */
    val spoken =
        listOf(find("tote_spoken_to_tamara"), find("tote_spoken_to_felix"), find("tote_spoken_to_cordelia"))

    /** earth, cosmic, death, nature, law, fire (0 hidden, 1 visible, 2 empowered). */
    val energies =
        listOf(
            find("tote_abyssal_energy_earth"),
            find("tote_abyssal_energy_cosmic"),
            find("tote_abyssal_energy_death"),
            find("tote_abyssal_energy_nature"),
            find("tote_abyssal_energy_law"),
            find("tote_abyssal_energy_fire"),
        )
}
