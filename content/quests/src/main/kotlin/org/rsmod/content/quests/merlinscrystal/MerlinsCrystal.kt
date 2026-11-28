package org.rsmod.content.quests.merlinscrystal

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
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
import org.rsmod.content.quests.framework.onLocOpLabel
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
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Merlin's Crystal (OSRS Wiki). Cache quest varp `arthur`, end state 7:
 * - 1 King Arthur (Camelot) accepts you; 2 Sir Gawain tells how Merlin was trapped (Morgan Le
 *   Faye); 3 Sir Lancelot suggests sneaking into Keep Le Faye; hiding in Arhein's crate (Catherby)
 *   ships you into the keep, where Sir Mordred (spawned for you) fights; 4 Morgan Le Faye, once he
 *   is down, explains the ritual: Excalibur (the Lady of the Lake, after bread for her beggar
 *   disguise at the Port Sarim jeweller's), a lit black candle (the candle maker, for a bucket of
 *   wax from the Catherby beehives with insect repellent), bat bones and the chaos altar's words;
 *   5 the bat bones used on the star in Camelot's grounds with a lit black candle (words read)
 *   summon Thrantax, bound by "Snarthon Candtrick Termanto"; 6 Excalibur smashes the crystal;
 *   7 King Arthur: 6 QP, Excalibur.
 * - Server varp `mc_progress` (65790): bit 0 the altar's words read, bit 1 Mordred defeated, bit 2
 *   the beggar was refused (his "again" dialogue), bit 3 the candle maker asked about black
 *   candles.
 *
 * Simplified: no cutscenes (the crate voyage is the Wiki's boxes, then a telejump), no renegade
 * knight ambush beyond the spawned ones, and the ritual uses bat bones on the star (the Wiki
 * drops them there).
 */
class MerlinsCrystal
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MerlinsCrystal::class.java, "merlins_crystal") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Thrantax the Mighty" to McNpcs.thrantax,
            "The Lady of the Lake" to McNpcs.lady,
            "Morgan Le Faye" to McNpcs.morgan,
            "Merlin" to McNpcs.merlin,
            "Sir Mordred" to McNpcs.mordred,
        )
    }

    /** Each player's Sir Mordred, by player uid. */
    private val mordreds = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, McNpcs.arthur, MERLINS_CRYSTAL, wants = { state < COMPLETE }) {
                talkArthur(it)
            }
            for ((name, knight) in McNpcs.knights) {
                register(npcTypes, knight, MERLINS_CRYSTAL, wants = { state in STARTED until SMASHED }) {
                    talkKnight(it, name)
                }
            }
            register(npcTypes, McNpcs.arhein, MERLINS_CRYSTAL, wants = { state == KEEP }) {
                runQuestSection(it, dialogue, "Talking to Arhein")
            }
            register(npcTypes, McNpcs.morgan, MERLINS_CRYSTAL, wants = { wantsMorgan() }) {
                talkMorgan(it)
            }
            register(npcTypes, McNpcs.candle_maker, MERLINS_CRYSTAL, wants = { state >= RITUAL }) {
                talkCandleMaker(it)
            }
            register(npcTypes, McNpcs.lady, MERLINS_CRYSTAL, wants = { state >= RITUAL }) {
                runQuestSection(it, dialogue, "The Lady of the Lake")
            }
            register(npcTypes, McNpcs.beggar, MERLINS_CRYSTAL, wants = { wantsBeggar() }) {
                talkBeggar(it, again = true)
            }
        }
        onLocOpLabel(locTypes, McLocs.crate, "Hide-in") { hideInCrate() }
        onLocOpLabel(locTypes, McLocs.keep_crate, "Hide-in") { telejump(CATHERBY_CRATE) }
        onLocOpLabel(locTypes, McLocs.jeweller_door, "Open") { jewellerDoor(it) }
        onLocOpLabel(locTypes, McLocs.altar, "Check") { checkAltar() }
        onLocOpLabel(locTypes, McLocs.crystal, "Smash") { smash() }
        onLocOpLabel(locTypes, McLocs.beehive, "Take-from") { beehive() }
        onOpLocU(McLocs.beehive, McObjs.repellent) { beehive() }
        onOpLocU(McLocs.crystal, McObjs.excalibur) { smash() }
        onOpLocU(McLocs.star, McObjs.bat_bones) { ritual() }
        onOpHeldU(McObjs.tinderbox, McObjs.black_candle) { lightCandle() }
        onEvent<NpcKilledEvent> { mordredDown(this) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(MERLINS_CRYSTAL)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(MERLINS_CRYSTAL, value)
    }

    private fun ProtectedAccess.hasFlag(bit: Int): Boolean =
        (player.vars[McVarps.progress] and (1 shl bit)) != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        VarPlayerIntMapSetter.set(player, McVarps.progress, player.vars[McVarps.progress] or (1 shl bit))
    }

    private fun ProtectedAccess.hasExcalibur(): Boolean =
        carries(McObjs.excalibur) || invTotal(worn, McObjs.excalibur) > 0

    /* King Arthur and his knights */

    private suspend fun ProtectedAccess.talkArthur(npc: Npc) {
        when (state) {
            0 -> {
                val hooks = SimpleQuestHooks(options = mapOf("Yes." to { if (state == 0) setState(STARTED) }))
                runQuestSection(npc, dialogue, "Starting off", hooks)
            }
            SMASHED -> {
                runQuestSection(npc, dialogue, "Finishing up")
                completeQuest(MERLINS_CRYSTAL)
            }
            else -> runQuestSection(npc, dialogue, "Subsequent dialogue")
        }
    }

    private suspend fun ProtectedAccess.talkKnight(npc: Npc, name: String) {
        val section =
            when {
                state == STARTED -> name
                state in LEARNED..KEEP -> "$name (2)"
                else -> "$name (3)"
            }
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Do you know how Merlin got trapped?" to {
                            if (state == STARTED) setState(LEARNED)
                        },
                        "Any ideas on how to get into Morgan Le Faye's stronghold?" to {
                            if (state == LEARNED) setState(KEEP)
                        },
                    )
            )
        runQuestSection(npc, dialogue, section, hooks)
    }

    /* Keep Le Faye */

    private suspend fun ProtectedAccess.hideInCrate() {
        var hide = false
        var out = false
        val hooks =
            SimpleQuestHooks(
                options =
                    mapOf(
                        "Yes." to {
                            if (!hide) hide = true else out = true
                        }
                    )
            )
        if (state != KEEP) {
            // Authored line (not Wiki): the crate before the quest needs it.
            mes("The crate is empty. You have no reason to hide in it.")
            return
        }
        runQuestSection(null, dialogue, "Hiding in Arhein's crate", hooks)
        if (!hide) return
        telejump(KEEP_ARRIVAL)
        summonMordred()
    }

    private fun ProtectedAccess.summonMordred() {
        if (hasFlag(MORDRED_DOWN)) return
        val current = mordreds[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        val mordred = Npc(npcTypes[McNpcs.mordred], MORDRED_SPOT)
        npcRepo.add(mordred, MORDRED_TICKS)
        mordreds[player.uid.packed] = mordred
        mordred.opPlayer2(player, aiInteractions)
    }

    private fun mordredDown(event: NpcKilledEvent) {
        if (event.npc.type.id != McNpcs.mordred.id) return
        val player = event.hero
        mordreds.remove(player.uid.packed)
        if (player.questState(MERLINS_CRYSTAL) != KEEP) return
        VarPlayerIntMapSetter.set(player, McVarps.progress, player.vars[McVarps.progress] or (1 shl MORDRED_DOWN))
        // Authored line (not Wiki): Morgan's entrance is a cutscene in OSRS.
        player.mes("Morgan Le Faye appears and begs you to spare Sir Mordred. Speak to her.")
    }

    private fun ProtectedAccess.wantsMorgan(): Boolean = state == KEEP && hasFlag(MORDRED_DOWN)

    private suspend fun ProtectedAccess.talkMorgan(npc: Npc) {
        runQuestSection(npc, dialogue, "Defeating Sir Mordred")
        setState(RITUAL)
    }

    /* The black candle, the wax and Excalibur */

    private suspend fun ProtectedAccess.talkCandleMaker(npc: Npc) {
        if (!hasFlag(CANDLE_ASKED)) {
            runQuestSection(npc, dialogue, "Talking to the candle maker")
            setFlag(CANDLE_ASKED)
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the bucket of wax in their inventory:" to {
                            carries(McObjs.bucket_wax)
                        },
                        "If the player does not have the bucket of wax in their inventory:" to {
                            !carries(McObjs.bucket_wax)
                        },
                    ),
                boxes =
                    mapOf(
                        "You exchange the wax with the candle maker for a black candle." to {
                            invDel(inv, McObjs.bucket_wax)
                            invAddOrDrop(objRepo, McObjs.black_candle)
                            false
                        }
                    ),
            )
        runQuestSection(npc, dialogue, "Returning to the candle maker", hooks)
    }

    private fun ProtectedAccess.beehive() {
        if (!carries(McObjs.repellent)) {
            // Authored line (not Wiki).
            mes("The bees fly out angrily as you reach in. You'd better repel them first.")
            return
        }
        if (!carries(McObjs.bucket)) {
            mes("You'll need an empty bucket to collect the wax.")
            return
        }
        invDel(inv, McObjs.bucket)
        invAdd(inv, McObjs.bucket_wax)
        mes("You use the insect repellent, and the bees calm down. You fill the bucket with wax.")
    }

    private fun ProtectedAccess.lightCandle() {
        invDel(inv, McObjs.black_candle)
        invAdd(inv, McObjs.lit_black_candle)
        mes("You light the black candle.")
    }

    private fun ProtectedAccess.wantsBeggar(): Boolean =
        state >= RITUAL && hasFlag(BEGGAR_REFUSED) && !hasExcalibur()

    private suspend fun ProtectedAccess.jewellerDoor(door: BoundLocInfo) {
        if (state >= RITUAL && !hasExcalibur() && player.coords.z < door.coords.z) {
            val beggar =
                npcRepo.findAll(ZoneKey.from(door.coords), 1).firstOrNull {
                    it.id == McNpcs.beggar.id && it.coords.chebyshevDistance(door.coords) <= BEGGAR_RANGE
                }
            talkBeggar(beggar, again = hasFlag(BEGGAR_REFUSED))
            if (!hasExcalibur()) return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkBeggar(npc: Npc?, again: Boolean) {
        val hasBread = carries(McObjs.bread)
        var refused = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has bread in their inventory:" to { hasBread },
                        "If the player has no bread in their inventory:" to { !hasBread },
                    ),
                options =
                    mapOf(
                        "No I don't have any bread with me." to { refused = true },
                        "No, I still have none." to { refused = true },
                    ),
                boxes =
                    mapOf(
                        "You give the bread to the beggar." to {
                            invDel(inv, McObjs.bread)
                            false
                        },
                        "The beggar has turned into the Lady of the Lake!" to {
                            invAddOrDrop(objRepo, McObjs.excalibur)
                            false
                        },
                    ),
            )
        val section =
            if (again) "Talking to the Beggar again if they were not given bread initially"
            else "Attempting to enter the jewellery shop"
        runQuestSection(npc, dialogue, section, hooks, speakers)
        if (refused || !hasBread) setFlag(BEGGAR_REFUSED)
    }

    /* The ritual and the crystal */

    private suspend fun ProtectedAccess.checkAltar() {
        runQuestSection(null, dialogue, "Searching the altar for the spell")
        if (state >= RITUAL) setFlag(WORDS_READ)
    }

    private suspend fun ProtectedAccess.ritual() {
        if (state != RITUAL || player.coords.chebyshevDistance(STAR) > 1) return
        if (!carries(McObjs.lit_black_candle)) {
            runQuestSection(
                null,
                dialogue,
                "Dropping bat bones on the ritual circle without a lit black candle",
            )
            return
        }
        if (!hasFlag(WORDS_READ)) {
            runQuestSection(
                null,
                dialogue,
                "Dropping bat bones on the ritual circle with a lit black candle without having read the incantation",
            )
            return
        }
        invDel(inv, McObjs.bat_bones)
        val thrantax = Npc(npcTypes[McNpcs.thrantax], STAR.translate(1, 1))
        npcRepo.add(thrantax, THRANTAX_TICKS)
        var bound = false
        val hooks = SimpleQuestHooks(options = mapOf("Snarthon Candtrick Termanto" to { bound = true }))
        runQuestSection(
            thrantax,
            dialogue,
            "Dropping bat bones on the ritual circle with a lit black candle after reading the incantation",
            hooks,
            speakers,
        )
        npcRepo.del(thrantax, 1)
        if (bound) {
            invDel(inv, McObjs.lit_black_candle)
            setState(BOUND)
        }
    }

    private suspend fun ProtectedAccess.smash() {
        if (state < RITUAL) {
            mes("The crystal is far too hard to damage.")
            return
        }
        val excalibur = hasExcalibur()
        val boon = state >= BOUND
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have Excalibur:" to { !excalibur && !carries(McObjs.hammer) },
                        "If the player doesn't have Excalibur but has a hammer:" to {
                            !excalibur && carries(McObjs.hammer)
                        },
                        "If the player has Excalibur but doesn't have Thrantax's boon:" to {
                            excalibur && !boon
                        },
                        "If the player has Excalibur and Thrantax's boon:" to { excalibur && boon },
                    )
            )
        runQuestSection(null, dialogue, "Smashing!", hooks, speakers)
        if (excalibur && boon && state == BOUND) setState(SMASHED)
    }

    companion object {
        const val STARTED = 1
        const val LEARNED = 2
        const val KEEP = 3
        const val RITUAL = 4
        const val BOUND = 5
        const val SMASHED = 6
        const val COMPLETE = 7

        private const val WORDS_READ = 0
        private const val MORDRED_DOWN = 1
        private const val BEGGAR_REFUSED = 2
        private const val CANDLE_ASKED = 3
        private const val MORDRED_TICKS = 500
        private const val THRANTAX_TICKS = 100
        private const val BEGGAR_RANGE = 6

        /** Arhein's crate in Catherby and its twin inside Keep Le Faye (2779,3401 plane 1). */
        private val CATHERBY_CRATE = CoordGrid(2801, 3441, 0)
        private val KEEP_ARRIVAL = CoordGrid(2779, 3402, 1)
        private val MORDRED_SPOT = CoordGrid(2770, 3402, 2)
        private val STAR = CoordGrid(2780, 3515, 0)

        val MERLINS_CRYSTAL =
            QuestDef(
                name = "Merlin's Crystal",
                varp = { McVarps.quest },
                complete = COMPLETE,
                questPoints = 6,
                rewards = listOf("Excalibur"),
                scrollObj = { McObjs.excalibur },
                listRow = "quest_merlinscrystal",
            )
    }
}

// Every cache ref Merlin's Crystal uses (re-check after cache updates).

internal object McNpcs : NpcReferences() {
    val arthur = find("king_arthur")
    val arhein = find("arhein")
    val candle_maker = find("candle_maker")
    val morgan = find("morgan_le_faye")
    val mordred = find("sir_mordred")
    val merlin = find("merlin")
    val lady = find("ladyofthelake")
    val beggar = find("lake_beggar")
    val thrantax = find("thrantax")

    /** The Wiki's section titles (one per knight) and the knight. */
    val knights: List<Pair<String, NpcType>> =
        listOf(
            "Sir Bedivere" to find("sir_bedivere"),
            "Sir Kay" to find("sir_kay"),
            "Sir Lancelot" to find("sir_lancelot"),
            "Sir Lucan" to find("sir_lucan"),
            "Sir Palomedes" to find("sir_palomedes"),
            "Sir Pelleas" to find("sir_pelleas"),
            "Sir Tristram" to find("sir_tristram"),
            "Sir Gawain" to find("sir_gawain"),
        )
}

internal object McLocs : LocReferences() {
    val crate = find("merlincrate_empty")
    val keep_crate = find("merlincrate_empty2")
    val jeweller_door = find("jewellersdoor")
    val altar = find("thrantaxaltar")
    val star = find("merlin_star")
    val crystal = find("merlins_crystal")
    val beehive = find("merlin_beehive")
}

internal object McObjs : ObjReferences() {
    val excalibur = find("excalibur")
    val black_candle = find("unlit_black_candle")
    val lit_black_candle = find("lit_black_candle")
    val bat_bones = find("bat_bones")
    val bucket_wax = find("bucket_wax")
    val bucket = find("bucket_empty")
    val repellent = find("insect_repellent")
    val tinderbox = find("tinderbox")
    val bread = find("bread")
    val hammer = find("hammer")
}

internal object McVarps : VarpReferences() {
    val quest = find("arthur")

    /** Server varp (65790): see [MerlinsCrystal]. */
    val progress = find("mc_progress")
}

internal object McVarpBuilds : VarpBuilder() {
    init {
        build("mc_progress")
    }
}
