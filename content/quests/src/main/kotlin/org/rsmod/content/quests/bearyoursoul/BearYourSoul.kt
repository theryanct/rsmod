package org.rsmod.content.quests.bearyoursoul

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Bear Your Soul (OSRS Wiki miniquest). Progress lives in the cache quest varbit
 * `arceuus_soulbearer_story` (varp `millcheck_multi` bits 30-31; the client's quest_progress_get
 * reads it for dbrow `miniquest_bearyoursoul`, end state 3). Stages are ours:
 * - 1 read Soul Journey (any Search of an Arceuus Library bookcase may turn it up);
 * - 2 Aretha told you about the crypt (her "Yes please." answer); a spade in the crypt (the
 *   blood-stained centre of the Arceuus church, ground floor) digs up the damaged soul bearer;
 * - 3 the Key Master (Cerberus' Lair, via the hellhound cave in Taverley Dungeon) repairs it: the
 *   soul bearer. Lose it and you can dig another damaged one up; he repairs it again.
 *
 * Side content: the Arceuus stairs (library, church and the rest of Arceuus) climb between floors
 * (ArceuusStairs), the church's double doors open, and the hellhound cave entrances lead to and
 * from Cerberus' Lair.
 *
 * Simplified: the library's shifting book locations aren't modelled (each Search has a 1 in
 * [BOOK_CHANCE] chance of the book while you don't carry it, and Biblia's hints don't exist); the
 * Key Master's slayer-task branch always takes the no-task line; the soul bearer's own charge/send
 * functions aren't built.
 */
class BearYourSoul
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(BearYourSoul::class.java, "bear_your_soul") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, BysNpcs.aretha, BEAR_YOUR_SOUL, wants = { true }) { talkAretha(it) }
            register(npcTypes, BysNpcs.key_master, BEAR_YOUR_SOUL, wants = { carries(BysObjs.damaged) }) {
                repair(it)
            }
            default(npcTypes, BysNpcs.key_master) { keyMasterStandard(it) }
        }
        onHeldOpLabel(objTypes, BysObjs.book, "Read") { readBook() }
        QuestDigSpots.register { digCrypt() }

        var shelves = 0
        for (type in locTypes.values) {
            if (!type.internalName.orEmpty().startsWith(LIBRARY_BOOKCASE)) continue
            if (onLocOpLabel(locTypes, type, "Search") { searchBookcase() }) shelves++
        }
        check(shelves > 0) { "Bear Your Soul: no Arceuus Library bookcases found" }

        for (door in BysLocs.church_doors) onLocOpLabel(locTypes, door, "Open") { passThroughWall(it) }
        for (cave in BysLocs.taverley_caves) onLocOpLabel(locTypes, cave, "Crawl") { enterLair() }
        for (cave in BysLocs.lair_caves) onLocOpLabel(locTypes, cave, "Crawl") { leaveLair() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BEAR_YOUR_SOUL)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(BEAR_YOUR_SOUL, value)
    }

    /* The library and the book */

    private suspend fun ProtectedAccess.searchBookcase() {
        arriveDelay()
        spam("You search the bookcase...") // Authored (not Wiki).
        delay(2)
        if (carries(BysObjs.book) || random.of(BOOK_CHANCE) != 0) {
            mes("You don't find anything useful.") // Authored (not Wiki).
            return
        }
        if (inv.isFull()) {
            mes("You find a book called 'The Journey of Souls', but you have no room for it.") // Authored.
            return
        }
        invAdd(inv, BysObjs.book)
        mes("You find a book called 'The Journey of Souls'.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.readBook() {
        // The book's own text (Wiki "Transcript:Soul Journey"), its final chapter.
        startDialogue { for (page in BOOK_PAGES) mesbox(page) }
        if (state == 0) {
            runQuestSection(null, dialogue, "Reading The Journey of Souls")
            setState(READ)
        }
    }

    /* Aretha */

    private suspend fun ProtectedAccess.talkAretha(npc: Npc) {
        when {
            state == 0 -> runQuestSection(npc, dialogue, "Talking to Aretha before reading the book")
            state >= COMPLETE -> runQuestSection(npc, dialogue, "Talking to Aretha after the miniquest")
            state >= TOLD && carries(BysObjs.damaged) ->
                runQuestSection(npc, dialogue, "Talking to Aretha after recovering the damaged soul bearer")
            else -> {
                var told = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes please." to { told = true }))
                runQuestSection(npc, dialogue, "Talking to Aretha", hooks)
                if (told && state < TOLD) setState(TOLD)
            }
        }
    }

    /* The crypt */

    private suspend fun ProtectedAccess.digCrypt(): Boolean {
        val here = player.coords
        if (here.level != 0 || here.x !in CRYPT_X || here.z !in CRYPT_Z) return false
        if (state < TOLD) return false // The Elders' spells hide it.
        if (carries(BysObjs.damaged) || carries(BysObjs.bearer)) {
            mes("You dig but find nothing.") // Authored: you already have it.
            return true
        }
        runQuestSection(null, dialogue, "Digging for the artefact")
        invAddOrDrop(objRepo, BysObjs.damaged)
        return true
    }

    /* The Key Master */

    private suspend fun ProtectedAccess.repair(npc: Npc) {
        if (state < TOLD) {
            keyMasterStandard(npc)
            return
        }
        val hooks =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "The Key Master repairs your soul bearer." to {
                            if (carries(BysObjs.damaged)) {
                                invReplace(inv, BysObjs.damaged, 1, BysObjs.bearer)
                            }
                            false
                        }
                    )
            )
        runQuestSection(npc, dialogue, "Talking to the Key Master", hooks)
        if (state < COMPLETE && carries(BysObjs.bearer)) completeQuest(BEAR_YOUR_SOUL)
    }

    private suspend fun ProtectedAccess.keyMasterStandard(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has travelled to Great Kourend:" to { state > 0 },
                        "If the player has not travelled to Great Kourend:" to { state == 0 },
                        "If the player does not have hellhounds or Cerberus herself as a slayer task:" to { true },
                    )
            )
        runQuestSection(npc, dialogue, "Key Master standard dialogue", hooks)
    }

    /* Cerberus' Lair */

    private suspend fun ProtectedAccess.enterLair() {
        mes("You crawl through the cave entrance.") // Authored (not Wiki).
        delay(1)
        telejump(LAIR_ARRIVAL)
    }

    private suspend fun ProtectedAccess.leaveLair() {
        mes("You crawl back out of the lair.") // Authored (not Wiki).
        delay(1)
        telejump(TAVERLEY_ARRIVAL)
    }

    companion object {
        const val READ = 1
        const val TOLD = 2
        const val COMPLETE = 3

        private const val BOOK_CHANCE = 12
        private const val LIBRARY_BOOKCASE = "archeuus_library_bookcase"

        /** The blood-stained centre of the Arceuus church, below its raised floor. */
        private val CRYPT_X = 1690..1709
        private val CRYPT_Z = 3787..3800

        /** Just north of the lair-side cave entrances (1309-1311,1236), south of the Key Master. */
        private val LAIR_ARRIVAL = CoordGrid(1310, 1239, 0)
        /** West of the hellhound cave entrances (2874,9846-9848) in Taverley Dungeon. */
        private val TAVERLEY_ARRIVAL = CoordGrid(2872, 9847, 0)

        private val BOOK_PAGES =
            listOf(
                "The Journey of Souls, by Aretha.<br><br>The Soul Bearer<br>I have written of the " +
                    "time when a human adventurer breached the Guardian's cave. While in there, the " +
                    "human attempted to steal an artefact from the cave.",
                "The origin of the artefact is unclear, but it is believed to draw a soul from one " +
                    "place to another, enabling the soul to travel with the River across the " +
                    "dimensions. Thus it was named the Soul Bearer.",
                "In the death of the adventurer the artefact had become damaged. Even the most " +
                    "learned Elders could not repair it, and eventually it was buried with all due " +
                    "ceremony and reverence, protected by spells to ensure no future explorer would " +
                    "find it by chance.",
                "If the artefact could ever be repaired, it would regain its powers to draw a soul " +
                    "from one place to another. Indeed, I believe it could do this to an ensouled " +
                    "head, exerting its power on the soul contained within the head.",
            )

        val BEAR_YOUR_SOUL =
            QuestDef(
                name = "Bear Your Soul",
                varbit = { BysVarBits.story },
                complete = COMPLETE,
                questPoints = 0,
                rewards = listOf("The soul bearer"),
                scrollObj = { BysObjs.bearer },
                listRow = "miniquest_bearyoursoul",
            )
    }
}

// Every cache ref Bear Your Soul uses (re-check after cache updates).
internal object BysNpcs : NpcReferences() {
    val aretha = find("arceuus_soulguardian")
    val key_master = find("keeper_of_keys")
}

internal object BysObjs : ObjReferences() {
    val book = find("arceuus_library_soulbearerbook")
    val damaged = find("arceuus_soulbearer_damaged")
    val bearer = find("arceuus_soulbearer")
}

internal object BysLocs : LocReferences() {
    val church_doors =
        listOf(
            find("archeuus_door_double_left_turquoise"),
            find("archeuus_door_double_left_green"),
            find("archeuus_door_double_left_red"),
            find("archeuus_door_double_left_blue"),
            find("archeuus_door_double_right_turquoise"),
            find("archeuus_door_double_right_green"),
            find("archeuus_door_double_right_red"),
            find("archeuus_door_double_right_blue"),
        )
    val taverley_caves =
        listOf(
            find("hellhound_cave_entrance_a_01"),
            find("hellhound_cave_entrance_b_02"),
            find("hellhound_cave_entrance_c_03"),
        )
    val lair_caves =
        listOf(
            find("hellhound_cave_entrance_01"),
            find("hellhound_cave_entrance_02"),
            find("hellhound_cave_entrance_03"),
        )
}

internal object BysVarBits : VarBitReferences() {
    val story = find("arceuus_soulbearer_story")
}
