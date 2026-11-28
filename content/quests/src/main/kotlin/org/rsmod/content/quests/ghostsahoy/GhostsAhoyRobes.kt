package org.rsmod.content.quests.ghostsahoy

import jakarta.inject.Inject
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Ghosts Ahoy, the Robes of Necrovarus and the ending: the ghost innkeeper's bedsheet (dipped in a
 * bucket of slime), Gravingas's petition, ten ghost villagers' signatures (they won't answer the
 * same mortal twice running; some want 1-3 ecto-tokens; some refuse), Necrovarus's bone key, his
 * tomb (the temple's upstairs door) and coffin; the crone's materials and enchantment; the
 * Ectophial (Empty teleports to the Ectofuntus and refills it there).
 */
class GhostsAhoyRobes
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
) : PluginScript() {
    /** The villager each player asked last (identity), so they can't ask the same one twice. */
    private val lastAsked = HashMap<Long, Int>()

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, GaNpcs.innkeeper, "Talk-to") { talkInnkeeper(it) }
        onNpcOpLabel(npcTypes, GaNpcs.gravingas, "Talk-to") { talkGravingas(it) }
        onNpcOpLabel(npcTypes, GaNpcs.villager, "Talk-to") { talkVillager(it) }

        onOpHeldU(GaObjs.bedsheet, GaObjs.slime) {
            invDel(inv, GaObjs.bedsheet)
            invDel(inv, GaObjs.slime)
            invAdd(inv, GaObjs.bedsheet_slimy)
            invAdd(inv, GaObjs.bucket)
            mes("You dip the bedsheet into the bucket of slime.")
        }
        onOpHeldU(GaObjs.bedsheet, GaObjs.water) { mes("That won't make it look any more ghostly.") }
        onOpHeldU(GaObjs.bedsheet, GaObjs.milk) { mes("That won't make it look any more ghostly.") }

        onLocOpLabel(locTypes, GaLocs.door, "Open") { door(it) }
        onOpLocU(GaLocs.door, GaObjs.bone_key) { unlockTomb(it.loc) }
        onLocOpLabel(locTypes, GaLocs.coffin, "Open") {
            locRepo.change(it, GaLocs.coffin_open, COFFIN_TICKS)
        }
        onLocOpLabel(locTypes, GaLocs.coffin_open, "Close") {
            locRepo.change(it, GaLocs.coffin, COFFIN_TICKS)
        }
        onLocOpLabel(locTypes, GaLocs.coffin_open, "Search") { searchCoffin() }

        onOpHeld1(GaObjs.ectophial) { emptyEctophial() }
        onOpLocU(GaLocs.ectofuntus, GaObjs.ectophial_empty) { refill() }
    }

    /* The bedsheet */

    private suspend fun ProtectedAccess.talkInnkeeper(npc: Npc) {
        val hasSheet = carries(GaObjs.bedsheet) || carries(GaObjs.bedsheet_slimy)
        var job = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have a bedsheet in their inventory:" to { !hasSheet },
                        "If the player already has a bedsheet in their inventory" to { hasSheet },
                    ),
                options = mapOf("Yes, I'd be delighted." to { job = true }),
            )
        runQuestSection(npc, dialogue, "Talking to the Ghost innkeeper", hooks, GaNpcs.speakers)
        if (job && !hasSheet) {
            if (inv.isFull()) {
                mes("You don't have room for the bedsheet.")
                return
            }
            invAdd(inv, GaObjs.bedsheet)
            setBit(GaVarBits.requested_sheet, 1)
        }
    }

    /* The petition */

    private suspend fun ProtectedAccess.talkGravingas(npc: Npc) {
        if (gaState() >= GhostsAhoy.COMPLETE) {
            runQuestSection(npc, dialogue, "Gravingas", SimpleQuestHooks(), GaNpcs.speakers)
            return
        }
        val wearing = wearingGhostspeak()
        val signatures = bit(GaVarBits.signatures)
        val hasForm = carries(GaObjs.petition)
        val keyStage = bit(GaVarBits.templedoor) != 0 || carries(GaObjs.bone_key)
        if (!hasForm && signatures == 0 && !keyStage) {
            var join = false
            val full = inv.isFull()
            val hooks =
                SimpleQuestHooks(
                    conditions =
                        mapOf(
                            "If the player talks to Gravingas without the amulet of ghostspeak:" to { !wearing },
                            "If the player talks to Gravingas with the amulet of ghostspeak:" to { wearing },
                            "If the player has no inventory space:" to { full },
                            "If the player has one free inventory space:" to { !full },
                        ),
                    options =
                        mapOf(
                            "After hearing Velorina's story I will be happy to help out." to { join = true },
                        ),
                )
            runQuestSection(npc, dialogue, "Talking to Gravingas", hooks, GaNpcs.speakers)
            if (join && wearing && !full && gaState() >= GhostsAhoy.STARTED) {
                invAdd(inv, GaObjs.petition)
            }
            return
        }
        if (!wearing) {
            gaRun(npc, "Talking to Gravingas")
            return
        }
        val lost = !hasForm && bit(GaVarBits.templedoor) == 0 && !carries(GaObjs.bone_key)
        val full = inv.isFull()
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player loses the petition form:" to { lost },
                        "If the player does not have inventory space:" to { full },
                        "If the player has one free inventory space:" to { !full },
                        "If the player has no signatures:" to { signatures == 0 },
                        "If the player has one signature:" to { signatures in 1..3 },
                        "If the player has 4-6 signatures:" to { signatures in 4..6 },
                        "If the player has 7-8 signatures:" to { signatures in 7..8 },
                        "If the player has 9 signatures:" to { signatures == 9 },
                        "If the player has all 10 signatures:" to { signatures >= SIGNATURES },
                    ),
                rewrites = { it.replace("[#]", "${SIGNATURES - signatures}").replace("supposeno full stop", "suppose.") },
            )
        runQuestSection(npc, dialogue, "Talking to Gravingas again", hooks, GaNpcs.speakers)
        if (lost && !full) {
            invAdd(inv, GaObjs.petition)
            setBit(GaVarBits.signatures, 0)
        }
    }

    private suspend fun ProtectedAccess.talkVillager(npc: Npc) {
        if (!wearingGhostspeak()) {
            startDialogue(npc) {
                chatNpc(neutral, "Woooo wooo wooooo woooo")
                mesbox("You cannot understand the ghost.")
            }
            return
        }
        if (!carries(GaObjs.petition) || gaState() >= GhostsAhoy.ENCHANTED) {
            startDialogue(npc) { chatNpc(neutral, "Leave us in peace, mortal.") }
            return
        }
        val slimy = invTotal(worn, GaObjs.bedsheet_slimy) > 0
        val plain = invTotal(worn, GaObjs.bedsheet) > 0
        val uuid = player.uuid ?: return
        val signatures = bit(GaVarBits.signatures)
        startDialogue(npc) {
            if (signatures >= SIGNATURES) {
                chatPlayer(happy, "Thank you for your kind support.")
                return@startDialogue
            }
            chatPlayer(neutral, "Would you sign this petition form, please?")
            when {
                plain -> {
                    chatNpc(angry, PLAIN_SHEET)
                    return@startDialogue
                }
                !slimy -> {
                    chatNpc(
                        neutral,
                        "I'm sorry, but it's hard to believe that a mortal could be interested in helping us.",
                    )
                    return@startDialogue
                }
                lastAsked[uuid] == System.identityHashCode(npc) -> {
                    chatNpc(
                        angry,
                        "You only just asked me the same thing! Leave me alone - I've had my say!",
                    )
                    return@startDialogue
                }
            }
            lastAsked[uuid] = System.identityHashCode(npc)
            val roll = access.random.of(0, 9)
            when {
                roll < 5 -> {
                    chatNpc(happy, SUPPORT[roll % SUPPORT.size])
                    sign()
                }
                roll < 7 -> {
                    chatNpc(neutral, BRIBE[roll % BRIBE.size])
                    chatPlayer(neutral, "How much?")
                    val cost = access.random.of(1, 3)
                    chatNpc(
                        neutral,
                        "Oh, it'll cost you $cost ecto-token${if (cost > 1) "s" else ""}.",
                    )
                    if (access.invTotal(access.inv, GaObjs.ecto_token) < cost) {
                        chatPlayer(sad, "I don't have that many on me.")
                        chatNpc(neutral, "No tokens, no signature.")
                        return@startDialogue
                    }
                    val pay =
                        choice2(
                            "Okay, if you insist.",
                            true,
                            "There's no way I'm giving in to corruption.",
                            false,
                        )
                    if (!pay) {
                        chatPlayer(angry, "There's no way I'm giving in to corruption.")
                        chatNpc(neutral, "Suit yourself.")
                        return@startDialogue
                    }
                    chatPlayer(neutral, "Okay, if you insist.")
                    access.invDel(access.inv, GaObjs.ecto_token, cost)
                    sign()
                }
                else -> chatNpc(angry, OPPOSE[roll % OPPOSE.size])
            }
        }
    }

    private suspend fun Dialogue.sign() {
        val count = access.bit(GaVarBits.signatures) + 1
        access.setBit(GaVarBits.signatures, count)
        mesbox(
            "The ghost signs your petition. You have obtained $count signature${if (count > 1) "s" else ""} so far.",
        )
        if (count >= SIGNATURES) mesbox(
            "You have succeeded in obtaining 10 signatures on the petition form!",
        )
    }

    /* The tomb */

    private suspend fun ProtectedAccess.door(loc: BoundLocInfo) {
        if (loc.coords != TOMB_DOOR) {
            passThroughWall(loc)
            return
        }
        if (bit(GaVarBits.templedoor) == 0) {
            gaRun(null, "Entering Necrovarus' Tomb")
            return
        }
        passThroughWall(loc)
    }

    private suspend fun ProtectedAccess.unlockTomb(loc: BoundLocInfo) {
        if (loc.coords != TOMB_DOOR) {
            mes("The key doesn't fit this door.")
            return
        }
        setBit(GaVarBits.templedoor, 1)
        mes("You unlock the door with the bone key.")
        passThroughWall(loc)
    }

    private fun ProtectedAccess.searchCoffin() {
        val have = carries(GaObjs.robes) || bit(GaVarBits.given_robes) != 0
        if (have || gaState() < GhostsAhoy.REMEMBERED) {
            mes("You search the coffin but find nothing of interest.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have room to take the robes.")
            return
        }
        invAdd(inv, GaObjs.robes)
        mes("You take the Robes of Necrovarus from the remains of his mortal body.")
    }

    /* The Ectophial */

    private suspend fun ProtectedAccess.emptyEctophial() {
        invDel(inv, GaObjs.ectophial)
        invAdd(inv, GaObjs.ectophial_empty)
        mes("You empty the ectoplasm onto the ground around your feet...")
        delay(3)
        telejump(ECTOFUNTUS)
        mes("...and the world changes around you.")
        delay(1)
        refill()
    }

    private suspend fun ProtectedAccess.refill() {
        if (invTotal(inv, GaObjs.ectophial_empty) == 0) return
        delay(1)
        invDel(inv, GaObjs.ectophial_empty)
        invAdd(inv, GaObjs.ectophial)
        mes("You refill the ectophial from the Ectofuntus.")
    }

    companion object {
        const val SIGNATURES = 10
        private const val COFFIN_TICKS = 100
        private val TOMB_DOOR = CoordGrid(3656, 3514, 1)
        private val ECTOFUNTUS = CoordGrid(3660, 3522, 0)

        private const val PLAIN_SHEET =
            "Why are you wearing that bedsheet? If you're trying to pretend to be one of us, " +
                "you're not fooling anybody - you're not even green!!"
        private val SUPPORT =
            listOf(
                "Most certainly, I will.",
                "I'll do anything that annoys Necrovarus.",
                "Yes! It's about time somebody did something about Necrovarus.",
                "Yes, of course.",
            )
        private val BRIBE =
            listOf(
                "It'll cost you...",
                "I will if you make it worth my while...",
                "You scratch my back and I'll scratch yours...",
            )
        private val OPPOSE =
            listOf(
                "I will have you know that I am a fervent supporter of Necrovarus.",
                "How dare you accost me in the street?",
                "Get lost.",
                "I don't have time for this nonsense.",
            )

        /** Necrovarus while you collect signatures; true if this handled the conversation. */
        suspend fun petitionTalk(access: ProtectedAccess, npc: Npc): Boolean =
            with(access) {
                val form = carries(GaObjs.petition)
                val key = carries(GaObjs.bone_key) || bit(GaVarBits.templedoor) != 0
                if (!form && !key) return false
                val full = bit(GaVarBits.signatures) >= SIGNATURES
                if (form) {
                    val hooks =
                        ghostHooks(
                            conditions =
                                mapOf(
                                    PARTIAL_FORM to { !full },
                                    "If the player has a full petition form in their inventory:" to { full },
                                )
                        )
                    runQuestSection(
                        npc,
                        dialogue,
                        "Talking to Necrovarus whilst collecting signatures",
                        hooks,
                        GaNpcs.speakers,
                    )
                    if (full && wearingGhostspeak()) {
                        invDel(inv, GaObjs.petition)
                        invAdd(inv, GaObjs.bone_key)
                    }
                    return@with true
                }
                val hasKey = carries(GaObjs.bone_key)
                val hooks =
                    ghostHooks(
                        conditions =
                            mapOf(
                                "If the player doesn't have the bone key:" to { !hasKey },
                                "If the player has the bone key:" to { hasKey },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Subsequent dialogue with Necrovarus",
                    hooks,
                    GaNpcs.speakers,
                )
                true
            }

        private const val PARTIAL_FORM =
            "If the player also has the petition form (not signed or partially signed) in their inventory:"

        fun hasMaterials(access: ProtectedAccess): Boolean =
            with(access) {
                (carries(GaObjs.book) && bit(GaVarBits.given_book) == 0) ||
                    (carries(GaObjs.manual) && bit(GaVarBits.given_manual) == 0) ||
                    (carries(GaObjs.robes) && bit(GaVarBits.given_robes) == 0) ||
                    gaState() == GhostsAhoy.MATERIALS_GIVEN
            }

        suspend fun handInMaterials(access: ProtectedAccess, npc: Npc): Unit =
            with(access) {
                val book = carries(GaObjs.book) && bit(GaVarBits.given_book) == 0
                val manual = carries(GaObjs.manual) && bit(GaVarBits.given_manual) == 0
                val robes = carries(GaObjs.robes) && bit(GaVarBits.given_robes) == 0
                val allAfter =
                    (book || bit(GaVarBits.given_book) != 0) &&
                        (manual || bit(GaVarBits.given_manual) != 0) &&
                        (robes || bit(GaVarBits.given_robes) != 0)
                val amulet = carries(GaObjs.ghostspeak) || invTotal(worn, GaObjs.ghostspeak) > 0
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the Book of Haricanto:" to { book },
                                "If the player has the translation manual:" to { manual },
                                "If the player has the robes of Necrovarus:" to { robes },
                                "If the player gave all three materials to the Old crone:" to { allAfter },
                                "If the player does not have the amulet of ghostspeak:" to { !amulet },
                                "If the player has the amulet of ghostspeak:" to { amulet },
                            )
                    )
                runQuestSection(
                    npc,
                    dialogue,
                    "Talking to the Old crone with the materials",
                    hooks,
                    GaNpcs.speakers,
                )
                if (book) {
                    invDel(inv, GaObjs.book)
                    setBit(GaVarBits.given_book, 1)
                }
                if (manual) {
                    invDel(inv, GaObjs.manual)
                    setBit(GaVarBits.given_manual, 1)
                }
                if (robes) {
                    invDel(inv, GaObjs.robes)
                    setBit(GaVarBits.given_robes, 1)
                }
                if (!allAfter) return@with
                if (!amulet) {
                    setGaState(GhostsAhoy.MATERIALS_GIVEN)
                    return@with
                }
                val where = if (invTotal(worn, GaObjs.ghostspeak) > 0) worn else inv
                invReplace(where, GaObjs.ghostspeak, 1, GaObjs.ghostspeak_enchanted)
                setGaState(GhostsAhoy.ENCHANTED)
            }
    }
}
