package org.rsmod.content.quests.piratestreasure

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDigSpots
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Pirate's Treasure (OSRS Wiki). `hunt` varp (cache db end state 4): 0, 1 Frank wants Karamjan
 * rum, 2 he swapped it for Hector's chest key, 3 the chest gave the pirate message, 4 complete.
 * Reward: Hector's casket (450 coins, gold ring, emerald), 800 Cooking xp, 2 QP.
 *
 * Smuggling: ask Luthas for work, hide the rum in his banana crate (Karamja), fill it with 10
 * bananas, get paid; the crate goes to Wydin's back room in Port Sarim. Wydin hires anyone with a
 * white apron, and the back room needs it worn. Crate / job / dig progress is in the server varp
 * `pirate_progress`.
 *
 * Also here until a proper travel module exists: the Port Sarim <-> Karamja boats (seamen,
 * customs officer, 30 coins; rum can't pass customs) and banana tree picking. Spade "Dig" is bound
 * via the shared [QuestDigSpots]. Not done: rum lost
 * on teleport, Ring of Charos(a) options, the boat cutscene.
 */
class PiratesTreasure
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy {
        QuestDialogue.load(PiratesTreasure::class.java, "pirates_treasure")
    }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, PirateNpcs.frank, "Talk-to") { talkFrank(it) }
        onOpNpcU(PirateNpcs.frank, PirateObjs.rum) { talkFrank(it.npc) }
        onOpNpcU(PirateNpcs.frank, PirateObjs.casket) { casketOnFrank(it.npc) }
        onNpcOpLabel(npcTypes, PirateNpcs.luthas, "Talk-to") { talkLuthas(it) }
        onNpcOpLabel(npcTypes, PirateNpcs.wydin, "Talk-to") { talkWydin(it) }
        onLocOpLabel(locTypes, PirateLocs.wydin_door, "Open") { wydinDoor(it) }
        onLocOpLabel(locTypes, PirateLocs.grocery_crate, "Search") { searchGroceryCrate() }

        onLocOpLabel(locTypes, PirateLocs.banana_crate, "Search") { searchBananaCrate() }
        onLocOpLabel(locTypes, PirateLocs.banana_crate, "Fill") { fillCrate(all = true) }
        onOpLocU(PirateLocs.banana_crate, PirateObjs.banana) { fillCrate(all = false) }
        onOpLocU(PirateLocs.banana_crate, PirateObjs.rum) { stashRum() }
        for ((index, tree) in PirateLocs.banana_trees.withIndex()) {
            val next = PirateLocs.banana_trees.getOrNull(index + 1) ?: PirateLocs.banana_tree_empty
            onLocOpLabel(locTypes, tree, "Pick") { pickBanana(it, next) }
        }
        onLocOpLabel(locTypes, PirateLocs.banana_tree_empty, "Search") {
            mes("There are no bananas left on the tree.")
        }

        onLocOpLabel(locTypes, PirateLocs.chest, "Open") { mes("The chest is locked.") }
        onOpLocU(PirateLocs.chest, PirateObjs.chest_key) { unlockChest(it.loc) }
        onLocOpLabel(locTypes, PirateLocs.chest_open, "Search") {
            mes("The chest is empty.")
        }
        onHeldOpLabel(objTypes, PirateObjs.message, "Read") { readMessage() }
        QuestDigSpots.register { dig() }
        onHeldOpLabel(objTypes, PirateObjs.casket, "Open") { openCasket() }

        for (seaman in PirateNpcs.seamen) {
            onNpcOpLabel(npcTypes, seaman, "Talk-to") { talkSeaman(it) }
            onNpcOpLabel(npcTypes, seaman, "Pay-fare") { sail(TO_KARAMJA, "Karamja") }
        }
        onNpcOpLabel(npcTypes, PirateNpcs.customs, "Talk-to") { talkCustoms(it) }
        onNpcOpLabel(npcTypes, PirateNpcs.customs, "Pay-Fare") { customsFare() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(PIRATES_TREASURE)

    /* `pirate_progress`: bits 0-3 bananas in the crate, then the flags below. */
    private fun ProtectedAccess.progress(): Int = player.vars[PirateVarps.progress]

    private fun ProtectedAccess.setProgress(value: Int) {
        VarPlayerIntMapSetter.set(player, PirateVarps.progress, value)
    }

    private fun ProtectedAccess.flag(bit: Int): Boolean = progress() and bit != 0

    private fun ProtectedAccess.setFlag(bit: Int, on: Boolean = true) {
        setProgress(if (on) progress() or bit else progress() and bit.inv())
    }

    private val ProtectedAccess.bananas: Int
        get() = progress() and BANANA_MASK

    private fun ProtectedAccess.setBananas(count: Int) {
        setProgress((progress() and BANANA_MASK.inv()) or count)
    }

    /* Redbeard Frank */

    private suspend fun ProtectedAccess.talkFrank(npc: Npc) {
        val rum = PirateObjs.rum
        var tookRum = false
        var gaveKey = false
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player does not have the rum:" to { !carries(rum) }),
                options =
                    mapOf("Yes." to { if (state == 0) player.setQuestState(PIRATES_TREASURE, 1) }),
                boxes =
                    mapOf(
                        "Frank happily takes the rum... and hands you a key." to {
                            if (carries(rum)) {
                                invDel(inv, rum)
                                tookRum = true
                            }
                            false
                        },
                        "Frank hands you a chest key." to {
                            gaveKey = true
                            false
                        },
                    ),
            )
        val section =
            when {
                state == 0 -> "Talking to Redbeard Frank"
                state == 1 && carries(rum) -> "Talking to Redbeard Frank (2)"
                state == 1 -> "Talking to Redbeard Frank again"
                state == 2 && !carries(PirateObjs.chest_key) -> "Reclaiming the key"
                else -> null
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(happy, "Arr, Matey! Found Hector's treasure yet?") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (tookRum || gaveKey) {
            invAddOrDrop(objRepo, PirateObjs.chest_key)
        }
        if (tookRum) {
            player.setQuestState(PIRATES_TREASURE, 2)
        }
    }

    private suspend fun ProtectedAccess.casketOnFrank(npc: Npc) {
        runQuestSection(npc, dialogue, "Using the chest on Redbeard Frank after the quest")
    }

    /* Karamja: Luthas, the banana crate, banana trees */

    private suspend fun ProtectedAccess.talkLuthas(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Could you offer me employment on your plantation?" to {
                            setFlag(EMPLOYED)
                        },
                    ),
            )
        if (bananas < CRATE_SIZE) {
            runQuestSection(npc, dialogue, "Talking to Luthas", hooks)
            return
        }
        // The full crate is loaded on the ship; any hidden rum now goes to Wydin's back room.
        if (flag(RUM_IN_CRATE)) {
            setFlag(RUM_IN_CRATE, on = false)
            setFlag(RUM_AT_WYDIN)
        }
        setBananas(0)
        invAddOrDrop(objRepo, PirateObjs.coins, CRATE_PAY)
        runQuestSection(npc, dialogue, "Talking to Luthas after filling up the crate", hooks)
    }

    private suspend fun ProtectedAccess.searchBananaCrate() {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If there are fewer than 10 bananas inside the crate:" to {
                            bananas < CRATE_SIZE
                        },
                        "If the crate has 10 bananas:" to { bananas >= CRATE_SIZE },
                        "If the player has hidden a rum bottle in the crate:" to {
                            flag(RUM_IN_CRATE)
                        },
                    ),
            )
        if (bananas < CRATE_SIZE) {
            mes("The crate has $bananas banana${if (bananas == 1) "" else "s"} in it.")
        }
        runQuestSection(null, dialogue, "Searching the crate", hooks)
    }

    private suspend fun ProtectedAccess.fillCrate(all: Boolean) {
        if (!flag(EMPLOYED)) {
            mes("Why would I want to do that?")
            return
        }
        if (bananas >= CRATE_SIZE) {
            mes("The crate is already full.")
            return
        }
        val carried = invTotal(inv, PirateObjs.banana)
        if (carried == 0) {
            mes("You don't have any bananas.")
            return
        }
        val add = if (all) minOf(carried, CRATE_SIZE - bananas) else 1
        invDel(inv, PirateObjs.banana, add)
        setBananas(bananas + add)
        mes(
            if (add == 1) {
                "You pack a banana into the crate."
            } else {
                "You pack your bananas into the crate."
            },
        )
        if (bananas >= CRATE_SIZE) {
            mes("The crate is full of bananas.")
        }
    }

    private fun ProtectedAccess.stashRum() {
        if (!flag(EMPLOYED)) {
            mes("Why would I want to do that?")
            return
        }
        if (flag(RUM_IN_CRATE)) {
            mes("There's already some rum hidden in the crate.")
            return
        }
        invDel(inv, PirateObjs.rum)
        setFlag(RUM_IN_CRATE)
        mes("You stash the rum in the crate.")
    }

    private suspend fun ProtectedAccess.pickBanana(tree: BoundLocInfo, next: LocType) {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        anim(PirateSeqs.pick)
        delay(1)
        invAdd(inv, PirateObjs.banana)
        locRepo.change(tree, next, BANANA_RESPAWN_TICKS)
        mes(
            if (next == PirateLocs.banana_tree_empty) {
                "You pick the last banana from the tree."
            } else {
                "You pick a banana."
            },
        )
    }

    /* Port Sarim: Wydin's food store */

    private fun ProtectedAccess.wearingApron(): Boolean =
        invTotal(worn, PirateObjs.white_apron) > 0

    private fun ProtectedAccess.hasApron(): Boolean =
        carries(PirateObjs.white_apron) || wearingApron()

    private suspend fun ProtectedAccess.talkWydin(npc: Npc) {
        val hooks = wydinHooks()
        if (flag(WYDIN_JOB)) {
            runQuestSection(npc, dialogue, "Talking to Wydin after getting the job", hooks)
        } else {
            runQuestSection(npc, dialogue, "Talking to Wydin", hooks)
        }
    }

    private fun ProtectedAccess.wydinHooks(): SimpleQuestHooks =
        SimpleQuestHooks(
            conditions =
                mapOf(
                    "If the player has a white apron:" to { hasApron() },
                    "If the player does not have a white apron:" to { !hasApron() },
                    "If the white apron is not equipped:" to { !wearingApron() },
                ),
            options =
                mapOf(
                    "Can I get a job here?" to { if (hasApron()) setFlag(WYDIN_JOB) },
                    "Well, can I get a job here?" to { if (hasApron()) setFlag(WYDIN_JOB) },
                ),
        )

    private suspend fun ProtectedAccess.wydinDoor(door: BoundLocInfo) {
        val wydin = PirateNpcs.wydin
        if (!flag(WYDIN_JOB)) {
            startDialogue {
                chatNpcSpecific(
                    npcTypes[wydin].name,
                    wydin,
                    neutral,
                    "Hey, you can't go in there. Only employees of the grocery store can go in.",
                )
            }
            return
        }
        if (!wearingApron()) {
            startDialogue {
                chatNpcSpecific(
                    npcTypes[wydin].name,
                    wydin,
                    neutral,
                    "Can you put your white apron on before going in there, please?",
                )
            }
            return
        }
        passThroughWall(door)
    }

    private fun ProtectedAccess.searchGroceryCrate() {
        if (state == 1 && flag(RUM_AT_WYDIN) && !carries(PirateObjs.rum)) {
            if (inv.isFull()) {
                mes("You don't have enough inventory space.")
                return
            }
            setFlag(RUM_AT_WYDIN, on = false)
            invAdd(inv, PirateObjs.rum)
            mes("There are a lot of bananas in the crate.")
            mes("You find your bottle of rum in amongst them.")
            return
        }
        mes("There are a lot of bananas in the crate.")
    }

    /* Varrock: Hector's chest; Falador Park: the treasure */

    private fun ProtectedAccess.unlockChest(chest: BoundLocInfo) {
        if (state != 2) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, PirateObjs.chest_key)
        locRepo.change(chest, PirateLocs.chest_open, CHEST_TICKS)
        invAddOrDrop(objRepo, PirateObjs.message)
        player.setQuestState(PIRATES_TREASURE, 3)
        mes("You unlock the chest. All that's in the chest is a message...")
        mes("You take the message from the chest.")
    }

    private suspend fun ProtectedAccess.readMessage() {
        startDialogue {
            mesbox(
                "Visit the city of the White Knights. In the park, Saradomin points to the X " +
                    "which marks the spot.",
            )
        }
    }

    /** Falador Park treasure spot (via [QuestDigSpots]); `false` anywhere else. */
    private suspend fun ProtectedAccess.dig(): Boolean {
        val here = player.coords
        if (state != 3 || here.level != TREASURE.level || here.chebyshevDistance(TREASURE) > 1) {
            return false
        }
        if (!flag(GARDENER_ANGRY)) {
            setFlag(GARDENER_ANGRY)
            val gardener = Npc(npcTypes[PirateNpcs.gardener], player.coords.translate(1, 0))
            npcRepo.add(gardener, GARDENER_TICKS)
            startDialogue(gardener) {
                chatNpc(angry, "First moles, now this! Take this, vandal!")
            }
            gardener.opPlayer2(player, aiInteractions)
            return true
        }
        invAddOrDrop(objRepo, PirateObjs.casket)
        mes("You dig a hole in the ground... and find a casket!")
        statAdvance(stats.cooking, COOKING_XP * xpMods.get(player, stats.cooking))
        setFlag(GARDENER_ANGRY, on = false)
        completeQuest(PIRATES_TREASURE)
        return true
    }

    private suspend fun ProtectedAccess.openCasket() {
        invDel(inv, PirateObjs.casket)
        invAddOrDrop(objRepo, PirateObjs.coins, CASKET_COINS)
        invAddOrDrop(objRepo, PirateObjs.gold_ring)
        invAddOrDrop(objRepo, PirateObjs.emerald)
        runQuestSection(null, dialogue, "Opening the casket")
    }

    /* Boats: Port Sarim seamen -> Karamja; Karamja customs officer -> Port Sarim */

    private suspend fun ProtectedAccess.talkSeaman(npc: Npc) {
        var go = false
        startDialogue(npc) {
            chatNpc(neutral, "Do you want to go on a trip to Karamja?")
            chatNpc(neutral, "The trip will cost you $FARE coins.")
            go = choice2("Yes please.", true, "No, thank you.", false)
            chatPlayer(neutral, if (go) "Yes please." else "No, thank you.")
        }
        if (go) {
            sail(TO_KARAMJA, "Karamja")
        }
    }

    private suspend fun ProtectedAccess.talkCustoms(npc: Npc) {
        var pay = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has Karamjan rum:" to { carries(PirateObjs.rum) },
                        "If the player does not have Karamjan rum:" to {
                            !carries(PirateObjs.rum)
                        },
                        "If the player doesn't have 30 coins:" to {
                            !carries(PirateObjs.coins, FARE)
                        },
                    ),
                options = mapOf("Okay." to { pay = true }),
            )
        runQuestSection(npc, dialogue, "Talking to the Customs officer", hooks)
        if (pay && !carries(PirateObjs.rum)) {
            sail(TO_PORT_SARIM, "Port Sarim")
        }
    }

    private suspend fun ProtectedAccess.customsFare() {
        if (carries(PirateObjs.rum)) {
            mes("The customs officer finds your Karamjan rum and won't let you board.")
            return
        }
        sail(TO_PORT_SARIM, "Port Sarim")
    }

    private suspend fun ProtectedAccess.sail(dest: CoordGrid, place: String) {
        if (!carries(PirateObjs.coins, FARE)) {
            mes("You need $FARE coins to pay for the trip.")
            return
        }
        invDel(inv, PirateObjs.coins, FARE)
        mes("You pay $FARE coins and board the ship.")
        delay(SAIL_TICKS)
        telejump(dest)
        mes("The ship arrives at $place.")
    }

    companion object {
        private const val BANANA_MASK = 0b1111
        private const val RUM_IN_CRATE = 1 shl 4
        private const val RUM_AT_WYDIN = 1 shl 5
        private const val WYDIN_JOB = 1 shl 6
        private const val EMPLOYED = 1 shl 7
        private const val GARDENER_ANGRY = 1 shl 8

        private const val CRATE_SIZE = 10
        private const val CRATE_PAY = 30
        private const val FARE = 30
        private const val SAIL_TICKS = 3
        private const val BANANA_RESPAWN_TICKS = 100
        private const val CHEST_TICKS = 100
        private const val GARDENER_TICKS = 200
        private const val CASKET_COINS = 450
        private const val COOKING_XP = 800.0

        /** Falador Park, by the Saradomin statue (OSRS Wiki map pin). */
        private val TREASURE = CoordGrid(2999, 3383, 0)
        private val TO_KARAMJA = CoordGrid(2953, 3147, 0)
        private val TO_PORT_SARIM = CoordGrid(3029, 3217, 0)

        val PIRATES_TREASURE =
            QuestDef(
                name = "Pirate's Treasure",
                varp = { PirateVarps.hunt },
                complete = 4,
                questPoints = 2,
                rewards = listOf("One-Eyed Hector's Treasure Chest", "800 Cooking XP"),
                scrollObj = { PirateObjs.casket },
            )
    }
}

// Every cache ref Pirate's Treasure uses (re-check after the rev 243 cutover).

internal object PirateNpcs : NpcReferences() {
    val frank = find("redbeard_frank")
    val luthas = find("luthas")
    val customs = find("customs_officer")
    val seamen = listOf(find("seaman_lorris"), find("seaman_thresnor"), find("captain_tobias"))
    val wydin = find("wydin")
    val gardener = find("falador_gardener")
}

internal object PirateLocs : LocReferences() {
    val banana_crate = find("bananacrate")
    val banana_trees =
        listOf(
            find("bananatreefull"),
            find("bananatreefour"),
            find("bananatreethree"),
            find("bananatreetwo"),
            find("bananatreeone"),
        )
    val banana_tree_empty = find("bananatreeempty")
    val wydin_door = find("wydindoor")
    val grocery_crate = find("grocerycrate")
    val chest = find("piratechest")
    val chest_open = find("piratechestopen")
}

internal object PirateObjs : ObjReferences() {
    val rum = find("karamja_rum")
    val banana = find("banana")
    val chest_key = find("chest_key")
    val message = find("piratemessage")
    val casket = find("pirate_casket")
    val white_apron = find("white_apron")
    val coins = find("coins")
    val gold_ring = find("gold_ring")
    val emerald = find("emerald")
}

internal object PirateSeqs : SeqReferences() {
    val pick = find("human_pickupfloor")
}

internal object PirateVarps : VarpReferences() {
    val hunt = find("hunt")

    /** Server varp (65772): banana count, rum / job / dig flags. */
    val progress = find("pirate_progress")
}

internal object PirateVarpBuilds : VarpBuilder() {
    init {
        build("pirate_progress")
    }
}
