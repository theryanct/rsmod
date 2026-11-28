package org.rsmod.content.quests.shilovillage

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestBranch
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.junglepotion.JunglePotion.Companion.JUNGLE_POTION
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shilo Village (OSRS Wiki). All progress lives in the cache quest varp `zombiequeen` (116, cache
 * db end state 15; the cache's multilocs open the village furnace and the broken-cart climb at 15),
 * plus the cache varbit `zqdoor_multi` (the hidden hillside doors: 0 jungle plants, 1 carved doors).
 * The stages are ours:
 * - Mosol Rei (outside the village) gives the Wampum belt (the varp stays 0, as on the Wiki); using
 *   it on Trufitus and agreeing to look for Ah Za Rhoon starts the quest (1).
 * - Ah Za Rhoon: dig the mound (spade), drop a lit light into the fissure (2), tie a rope (3) and
 *   climb down (32 Agility). The stone-plaque (chisel), tattered scroll, crumpled scroll and
 *   Zadimus' corpse; bury the corpse at Tai Bwo Wannai's tribal statue for the bone shard (4).
 *   Trufitus explains "keys and kin" (5).
 * - Tomb of Bervirius (Cairn Isle, 32 Agility crawl): the dolmen gives the sword pommel, locating
 *   crystal and Bervirius notes (6); chisel the pommel into bone beads, bronze wire (Smithing 4)
 *   makes the Beads of the Dead.
 * - Rashiliyia's Tomb: search the palm trees (doors appear), chisel the bone shard into the bone
 *   key (20 Crafting, once the doors are found), the key opens the doors (7); wearing the beads,
 *   open the gate, climb down, three bones in the tomb doors (8, 9, 10). Nazastarool's three forms
 *   (11, 12, 13) leave Rashiliyia's remains; placing them on Bervirius' dolmen completes the quest
 *   (15): 2 QP, 3,875 Crafting XP (cache db 38750 tenths; Wiki 3,875).
 *
 * Simplified: the quest-start combat-level warning only checks the skills; the Zadimus spirit and
 * Rashiliyia only speak (no visible apparition npc); the locating crystal's glow distances other
 * than the Wiki's 200 squares are ours and the crystal keeps its plain look; dropped quest items
 * just vanish (Wiki: they can be found again where they were); the "always let it draw" choice and
 * the smashed table's wood last until logout.
 */
class ShiloVillage
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(ShiloVillage::class.java, "shilo_village") }
    private val mosolDialogue by lazy { QuestDialogue.load(ShiloVillage::class.java, "mosol_rei") }

    /** Players who chose "Yes, always let it draw from my spiritual energy." (until logout). */
    private val crystalAlways = HashSet<Int>()

    override fun ScriptContext.startup() {
        // Mosol Rei: the multinpc (Talk-to op1 before the quest, op3 after) and both children.
        onOpNpc1(SvNpcs.mosol_multi) { talkMosol(it.npc) }
        onOpNpc3(SvNpcs.mosol_multi) { talkMosol(it.npc) }
        onOpNpc1(SvNpcs.mosol) { talkMosol(it.npc) }
        onOpNpc3(SvNpcs.mosol_complete) { talkMosol(it.npc) }

        // Trufitus: Jungle Potion's conversation is the QuestTalk default.
        with(QuestTalk) {
            register(npcTypes, SvNpcs.trufitus, SHILO_VILLAGE, wants = { state in STARTED until COMPLETE }) {
                talkTrufitus(it)
            }
        }
        onOpNpcU(SvNpcs.trufitus) { useOnTrufitus(it.npc, it.objType) }

        // Quest items.
        onHeldOpLabel(objTypes, SvObjs.tattered_scroll, "Read") { readScroll(SvObjs.tattered_scroll) }
        onHeldOpLabel(objTypes, SvObjs.crumpled_scroll, "Read") { readScroll(SvObjs.crumpled_scroll) }
        onHeldOpLabel(objTypes, SvObjs.plaque, "Read") { svSection(dialogue, null, "Read Stone-plaque") }
        onHeldOpLabel(objTypes, SvObjs.notes, "Read") { readNotes() }
        onHeldOpLabel(objTypes, SvObjs.bone_shard, "Inspect") { svSection(dialogue, null, "Inspect Bone shard") }
        onHeldOpLabel(objTypes, SvObjs.zadimus_corpse, "Bury") { buryZadimus() }
        onHeldOpLabel(objTypes, SvObjs.crystal, "Activate") { activateCrystal() }
        bindHeldU(SvObjs.chisel, SvObjs.bone_shard) { chiselShard() }
        bindHeldU(SvObjs.chisel, SvObjs.pommel) { chiselPommel() }
        bindHeldU(SvObjs.wire, SvObjs.bone_beads) { wireBeads() }

        // Dropping the delicate quest items.
        bindDrop(SvObjs.tattered_scroll) { dropScroll(SvObjs.tattered_scroll, "Dropping the Tattered scroll") }
        bindDrop(SvObjs.crumpled_scroll) { dropScroll(SvObjs.crumpled_scroll, "Dropping the Crumpled scroll") }
        bindDrop(SvObjs.plaque) { dropScroll(SvObjs.plaque, "Dropping the Stone-plaque") }
        bindDrop(SvObjs.crystal) { dropCrystal() }
        bindDrop(SvObjs.remains) { dropRemains() }
        bindDrop(SvObjs.pommel) { dropToDust(SvObjs.pommel) }
        bindDrop(SvObjs.bone_beads) { dropToDust(SvObjs.bone_beads) }
    }

    /** Binds [first] used on [second] unless either order is already taken (duplicates crash boot). */
    private fun ScriptContext.bindHeldU(first: ObjType, second: ObjType, action: suspend ProtectedAccess.() -> Unit) {
        val type = HeldUEvents.Type::class.java
        if (eventBus.contains(type, EventBus.composeLongKey(first.id, second.id))) return
        if (eventBus.contains(type, EventBus.composeLongKey(second.id, first.id))) return
        onOpHeldU(first, second) { action() }
    }

    private fun ScriptContext.bindDrop(obj: ObjType, action: suspend ProtectedAccess.() -> Unit) {
        if (isHeldOpBound(obj, DROP_OP)) return
        onOpHeld5(obj) { action() }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(SHILO_VILLAGE)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(SHILO_VILLAGE, value)
    }

    /* Mosol Rei */

    private suspend fun ProtectedAccess.talkMosol(npc: Npc) {
        when {
            !player.isQuestComplete(JUNGLE_POTION) ->
                runQuestSection(npc, mosolDialogue, "Before Jungle Potion")
            state >= COMPLETE -> {
                var enter = false
                val hooks =
                    SvHooks(
                        this,
                        options = mapOf("Yes, Ok, I'll go into the village!" to { enter = true }),
                        mes = MOSOL_MES,
                    )
                runQuestSection(npc, mosolDialogue, "Standard dialogue after Shilo Village", hooks)
                if (enter) telejump(VILLAGE_INSIDE_GATE)
            }
            state >= STARTED ->
                startDialogue(npc) {
                    // Authored (not Wiki): Mosol during the quest.
                    chatNpc(neutral, "Please hurry Bwana! We cannot hold the undead back for ever.")
                }
            svOwns(SvObjs.wampum_belt) ->
                runQuestSection(npc, dialogue, "Talking to Mosol Rei after getting the Wampum belt", SvHooks(this), svSpeakers)
            else -> {
                val hooks =
                    SvHooks(
                        this,
                        actions =
                            mapOf(
                                "Player receives the Wampum belt, or it drops to the floor if the player has no inventory space." to
                                    { invAddOrDrop(objRepo, SvObjs.wampum_belt) },
                                "Two undead ones appear" to { spawnUndead(player, 2) },
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to Mosol Rei", hooks, svSpeakers)
            }
        }
    }

    /* Trufitus */

    private suspend fun ProtectedAccess.talkTrufitus(npc: Npc) {
        when (state) {
            STARTED,
            FISSURE_LIT -> svSection(dialogue, npc, "Talking to Trufitus again")
            ROPE_TIED -> svSection(dialogue, npc, "Talking to Trufitus after visiting Ah Za Rhoon")
            ZADIMUS_BURIED,
            KIN_TOLD -> {
                var help: String? = null
                val options = HashMap<String, suspend () -> Unit>()
                for (label in HELP_OPTIONS) options[label] = { help = label }
                options["The spirit said something about keys and kin?"] = {
                    if (state == ZADIMUS_BURIED) setState(KIN_TOLD)
                }
                svSection(dialogue, npc, "Talking to Trufitus after burying Zadimus' corpse", SvHooks(this, options = options))
                help?.let { helpBranch(npc, it) }
            }
            BERVIRIUS_FOUND -> {
                var help: String? = null
                val options = HashMap<String, suspend () -> Unit>()
                for (label in HELP_OPTIONS) options[label] = { help = label }
                svSection(dialogue, npc, "Talking to Trufitus after visiting Bervirius' Tomb", SvHooks(this, options = options))
                help?.let { helpBranch(npc, it) }
            }
            else -> svSection(dialogue, npc, "Talking to Trufitus after visiting the Tomb of Rashiliyia")
        }
    }

    /**
     * The later conversations' "I need help with ..." options only point back ("above") to the
     * Ah Za Rhoon conversation's answers; run that answer.
     */
    private suspend fun ProtectedAccess.helpBranch(npc: Npc, label: String) {
        val option = if (label == ITEMS_SOME_HELP) ITEMS_HELP else label
        // The top-level answer (a nested "Temple" option there is only a "below" pointer).
        val nodes =
            dialogue.section("Talking to Trufitus after visiting Ah Za Rhoon").firstOrNull {
                it is QuestDialogue.Node.Option && it.label == option
            }?.children ?: return
        startDialogue(npc) { QuestDialogueRunner(SvHooks(this@helpBranch), svSpeakers).run(this, nodes) }
    }

    private suspend fun ProtectedAccess.useOnTrufitus(npc: Npc, obj: ObjType) {
        val section =
            when (obj.id) {
                SvObjs.wampum_belt.id -> {
                    showBelt(npc)
                    return
                }
                SvObjs.zadimus_corpse.id -> "Using the Zadimus corpse on Trufitus"
                SvObjs.plaque.id -> "Using the stone-plaque on Trufitus"
                SvObjs.tattered_scroll.id -> "Using the tattered scroll on Trufitus"
                SvObjs.crumpled_scroll.id -> "Using the crumpled scroll on Trufitus"
                SvObjs.bone_shard.id -> "Using the bone shard on Trufitus"
                SvObjs.pommel.id -> "Using the Sword pommel on Trufitus"
                SvObjs.crystal.id -> "Using the Locating crystal on Trufitus"
                SvObjs.notes.id -> "Using Bervirius notes on Trufitus"
                SvObjs.bone_beads.id -> "Using Bone beads on Trufitus"
                SvObjs.dead_beads.id -> "Using Beads of the Dead on Trufitus"
                SvObjs.bone_key.id -> "Using the Bone key on Trufitus"
                SvObjs.remains.id -> "Using Rashiliyias' remains on Trufitus"
                else -> "Use any unrelated item on Trufitus"
            }
        svSection(dialogue, npc, section)
    }

    private suspend fun ProtectedAccess.showBelt(npc: Npc) {
        if (state >= STARTED) {
            svSection(dialogue, npc, "Use any unrelated item on Trufitus")
            return
        }
        if (!player.isQuestComplete(JUNGLE_POTION)) {
            startDialogue(npc) {
                // Authored (not Wiki): the belt before Jungle Potion is done.
                chatNpc(neutral, "Please Bwana, first help me with the herbs for my potion.")
            }
            return
        }
        val missing = stat(stats.agility) < AGILITY_LEVEL || stat(stats.crafting) < CRAFTING_LEVEL
        val hooks =
            SvHooks(
                this,
                conditions =
                    mapOf(
                        "If the player does not have all the requirements and has a combat level below 45:" to { missing }
                    ),
                options =
                    mapOf(
                        "Yes." to {
                            if (state == 0 && carries(SvObjs.wampum_belt)) {
                                invDel(inv, SvObjs.wampum_belt)
                                setState(STARTED)
                            }
                        }
                    ),
            )
        svSection(dialogue, npc, "Using the Wampum belt on Trufitus", hooks)
    }

    /* Reading */

    private suspend fun ProtectedAccess.readScroll(scroll: ObjType) {
        val tattered = scroll.id == SvObjs.tattered_scroll.id
        val section = if (tattered) "Read Tattered scroll" else "Read Crumpled scroll"
        val tag = if (tattered) "Opens Transcript:Tattered scroll" else "Opens Transcript:Crumpled scroll"
        val text = if (tattered) TATTERED_TEXT else CRUMPLED_TEXT
        var open = false
        svSection(dialogue, null, section, SvHooks(this, actions = mapOf(tag to { open = true })))
        if (open) mesbox(text)
    }

    private suspend fun ProtectedAccess.readNotes() {
        var page: String? = null
        val hooks =
            SvHooks(
                this,
                actions =
                    mapOf(
                        "Opens Transcript:Bervirius notes#Tattered Yellow papyrus" to { page = NOTES_YELLOW },
                        "Opens Transcript:Bervirius notes#Decayed White papyrus" to { page = NOTES_WHITE },
                        "Opens Transcript:Bervirius notes#Crusty Orange papyrus" to { page = NOTES_ORANGE },
                    ),
            )
        svSection(dialogue, null, "Read the Bervirius notes", hooks)
        page?.let { mesbox(it) }
    }

    /* Zadimus */

    private suspend fun ProtectedAccess.buryZadimus() {
        if (player.coords.chebyshevDistance(TRIBAL_STATUE) > STATUE_RADIUS || state < ROPE_TIED) {
            svSection(dialogue, null, "Burying the Zadimus corpse in the wrong place")
            return
        }
        svSection(dialogue, null, "Burying the Zadimus corpse in the right place")
        invDel(inv, SvObjs.zadimus_corpse)
        invAddOrDrop(objRepo, SvObjs.bone_shard)
        if (state == ROPE_TIED) setState(ZADIMUS_BURIED)
    }

    /* Crafting the ward and the key */

    private suspend fun ProtectedAccess.chiselShard() {
        if (bit(SvVarBits.door_multi) == 0) {
            svSection(dialogue, null, "Using chisel on bone shard")
            return
        }
        val skilled = stat(stats.crafting) >= CRAFTING_LEVEL
        val hooks =
            SvHooks(
                this,
                conditions =
                    mapOf(
                        "If the player does not have 20 Crafting:" to { !skilled },
                        "If the player has 20 Crafting:" to { skilled },
                    ),
            )
        svSection(dialogue, null, "Use chisel on Bone shard", hooks)
        if (skilled && carries(SvObjs.bone_shard)) {
            invDel(inv, SvObjs.bone_shard)
            invAdd(inv, SvObjs.bone_key)
        }
    }

    private suspend fun ProtectedAccess.chiselPommel() {
        svSection(dialogue, null, "Use chisel on Sword pommel")
        if (!carries(SvObjs.pommel)) return
        invDel(inv, SvObjs.pommel)
        invAdd(inv, SvObjs.bone_beads)
    }

    private suspend fun ProtectedAccess.wireBeads() {
        svSection(dialogue, null, "Use Bronze wire on Bone beads")
        if (!carries(SvObjs.bone_beads) || !carries(SvObjs.wire)) return
        invDel(inv, SvObjs.bone_beads)
        invDel(inv, SvObjs.wire)
        invAdd(inv, SvObjs.dead_beads)
    }

    /* The locating crystal */

    private suspend fun ProtectedAccess.activateCrystal() {
        val distance = player.coords.chebyshevDistance(RASHILIYIA_TOMB)
        val surface = player.coords.level == 0 && player.coords.z < UNDERGROUND_Z
        // Wiki: faint within 200 squares; the brighter tiers' distances are ours (authored).
        val tier =
            when {
                !surface || distance > 200 -> 0
                distance > 100 -> 1
                distance > 50 -> 2
                distance > 15 -> 3
                else -> 4
            }
        val glow =
            listOf(
                "There is nothing different about the crystal. You're most likely not close enough for it to be active.",
                "The crystal glows faintly.",
                "The crystal glows brightly.",
                "The crystal is very bright.",
                "The crystal blazes brilliantly.",
            )[tier]
        val key = player.uid.packed
        if (key in crystalAlways) {
            mesbox(glow)
            return
        }
        var answer = 0
        val hooks =
            SvHooks(
                this,
                options =
                    mapOf(
                        "Yes, just this once." to { answer = 1 },
                        "Yes, always let it draw from my spiritual energy." to { answer = 2 },
                    ),
                conditions = mapOf("If the player is too far from the Tomb of Rashiliyia:" to { false }),
            )
        svSection(dialogue, null, "Activate the Locating crystal", hooks)
        if (answer == 2) crystalAlways += key
        if (answer > 0) mesbox(glow)
    }

    /* Dropping */

    private suspend fun ProtectedAccess.dropScroll(obj: ObjType, section: String) {
        var drop = false
        svSection(dialogue, null, section, SvHooks(this, options = mapOf("Yes, I'm quite sure." to { drop = true })))
        if (drop) invDel(inv, obj)
    }

    private suspend fun ProtectedAccess.dropCrystal() {
        var drop = false
        svSection(dialogue, null, "Dropping the Locating crystal", SvHooks(this, options = mapOf("Yes, I am sure." to { drop = true })))
        if (!drop || !carries(SvObjs.crystal)) return
        invDel(inv, SvObjs.crystal)
        player.queueHit(delay = 0, type = HitType.Typeless, damage = CRYSTAL_DAMAGE)
    }

    private suspend fun ProtectedAccess.dropRemains() {
        var drop = false
        val hooks = SvHooks(this, options = mapOf("Yes, I am sure." to { drop = true }))
        svSection(dialogue, null, "Dropping Rashiliyias' remains on the ground", hooks)
        if (drop) invDel(inv, SvObjs.remains)
    }

    private fun ProtectedAccess.dropToDust(obj: ObjType) {
        invDel(inv, obj)
        mes("As you drop the item, it crumbles to dust.") // Authored (Wiki: it turns to dust).
    }

    /* Shared */

    private fun spawnUndead(player: Player, count: Int) =
        spawnUndeadAt(player, count, npcRepo, npcTypes, aiInteractions)

    companion object {
        const val STARTED = 1
        const val FISSURE_LIT = 2
        const val ROPE_TIED = 3
        const val ZADIMUS_BURIED = 4
        const val KIN_TOLD = 5
        const val BERVIRIUS_FOUND = 6
        const val TOMB_ENTERED = 7
        const val BONE_1 = 8
        const val BONE_2 = 9
        const val BONE_3 = 10
        const val NAZ_1 = 11
        const val NAZ_2 = 12
        const val NAZ_3 = 13
        const val COMPLETE = 15

        const val AGILITY_LEVEL = 32
        const val CRAFTING_LEVEL = 20
        const val REWARD_XP = 3875.0 // Cache db stat_xp_awarded crafting 38750 (tenths); Wiki 3,875.

        private const val DROP_OP = 5
        private const val ITEMS_HELP = "I have some items that I need help with."
        private const val ITEMS_SOME_HELP = "I have some items that I need some help with."
        private val HELP_OPTIONS =
            listOf(
                "I need help with Bervirius.",
                "I need help with Zadimus.",
                "I need help with Rashiliyia.",
                "I need some help with the Temple of Ah Za Rhoon.",
                ITEMS_HELP,
                ITEMS_SOME_HELP,
            )
        private const val CRYSTAL_DAMAGE = 10
        private const val STATUE_RADIUS = 4
        private const val UNDERGROUND_Z = 6400

        /** Tai Bwo Wannai's tribal statue (rev 243 map `tbwt_tribal_statue`), the sacred ground. */
        private val TRIBAL_STATUE = CoordGrid(2795, 3090, 0)
        /** The hidden hillside doors of Rashiliyia's tomb (`hillsidedoorl_multi`). */
        private val RASHILIYIA_TOMB = CoordGrid(2916, 3090, 0)
        /** Just inside Shilo Village's wooden gate (2867,2952). */
        val VILLAGE_INSIDE_GATE = CoordGrid(2866, 2952, 0)

        private val MOSOL_MES =
            setOf("Mosol leaves you by the gate and walks back out into the jungle.", "You decide not to visit the village.")

        private const val TATTERED_TEXT =
            "Bervirius, son of King Danthalas, was killed in battle. His devout mother Rashiliyia was so heartbroken that " +
                "she swore fealty to Zamorak if he would return her son to her. Bervirius returned as an undead creature " +
                "and terrorized the King and Queen. Many guards died fighting the undead Bervirius. Eventually the undead " +
                "Bervirius was set on fire and soon only the bones remained. His remains were taken far to the South, and " +
                "then towards the setting sun to a tomb that is surrounded by and level with the sea. This is the only " +
                "way to contain the spirits of witches and the undead."
        private const val CRUMPLED_TEXT =
            "Rashiliyia's rage went unchecked. She killed without mercy for revenge of her son's life. Like a spectre " +
                "through the night she entered houses and one by one quietly strangled life from the occupants. It is " +
                "said only a handful survived, protected by the necklace wards to keep the Witch Queen at bay."
        private const val NOTES_YELLOW =
            "...and rest like your mother who is silent in the peace of her tomb far to the North of Ah Za Rhoon. Near " +
                "the sea, and under the hills deep in the underground to watch all nature from the darkness of her " +
                "final resting place."
        private const val NOTES_ORANGE =
            "...the sphere is activated when power of a spiritual nature is expended upon it, this can be very " +
                "draining on the body..."
        private const val NOTES_WHITE =
            "...Rashiliyia did so love objects of beauty. Her tomb was adorned with crystals that glowed brightly when " +
                "near to each other."

        val SHILO_VILLAGE =
            QuestDef(
                name = "Shilo Village",
                varp = { SvVarps.zombiequeen },
                complete = COMPLETE,
                questPoints = 2,
                rewards = listOf("3,875 Crafting XP", "Access to Shilo Village", "The cart to Brimhaven"),
                scrollObj = { SvObjs.remains },
            )
    }
}

/* Shared by the Shilo Village scripts. */

/**
 * Hooks for the Shilo Village transcripts: like SimpleQuestHooks, but the transcript's {{mes}}
 * lines (SV_MES, plus [mes]) go to the chatbox as game messages instead of a message box.
 */
internal class SvHooks(
    private val access: ProtectedAccess,
    private val conditions: Map<String, () -> Boolean> = emptyMap(),
    private val options: Map<String, suspend () -> Unit> = emptyMap(),
    private val boxes: Map<String, suspend () -> Boolean> = emptyMap(),
    private val actions: Map<String, suspend () -> Unit> = emptyMap(),
    private val rewrites: (String) -> String = { it },
    private val mes: Set<String> = emptySet(),
) : QuestDialogueHooks {
    override fun condition(text: String): Boolean = conditions[text]?.invoke() ?: false

    override suspend fun Dialogue.onOption(label: String) {
        options[label]?.invoke()
    }

    override suspend fun Dialogue.onBox(text: String): Boolean {
        val handler = boxes[text]
        if (handler != null && handler()) return true
        if (text in SV_MES || text in mes) {
            access.mes(rewrites(text)) // Wiki {{mes}}
            return true
        }
        return false
    }

    override suspend fun Dialogue.onAction(tag: String) {
        actions[tag]?.invoke()
    }

    override fun rewrite(text: String): String = rewrites(text)
}

internal val svSpeakers: Map<String, NpcType> by lazy {
    mapOf(
        "Spirit of Zadimus" to SvNpcs.zadimus,
        "Rashiliyia" to SvNpcs.rashiliyia,
        "Nazastarool" to SvNpcs.nazastarool.first(),
        "Undead one" to SvNpcs.undead.last(),
    )
}

internal suspend fun ProtectedAccess.svSection(
    dialogue: QuestDialogue,
    npc: Npc?,
    section: String,
    hooks: QuestDialogueHooks = SvHooks(this),
) {
    runQuestSection(npc, dialogue, section, hooks, svSpeakers)
}

internal suspend fun ProtectedAccess.svBranch(
    dialogue: QuestDialogue,
    section: String,
    option: String,
    hooks: QuestDialogueHooks = SvHooks(this),
) {
    runQuestBranch(null, dialogue, section, option, hooks, svSpeakers)
}

internal fun ProtectedAccess.svOwns(obj: ObjType): Boolean =
    carries(obj) || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

internal fun ProtectedAccess.bit(varbit: VarBitType): Int = player.vars[varbit]

/** Rashiliyia's minions: [count] undead ones (temporary, 2 minutes) rise around [player] and attack. */
internal fun spawnUndeadAt(
    player: Player,
    count: Int,
    npcRepo: NpcRepository,
    npcTypes: NpcTypeList,
    aiInteractions: AiPlayerInteractions,
) {
    for (index in 0 until count) {
        val (dx, dz) = UNDEAD_OFFSETS[index % UNDEAD_OFFSETS.size]
        val type = SvNpcs.undead[index % SvNpcs.undead.size]
        val undead = Npc(npcTypes[type], player.coords.translate(dx, dz))
        npcRepo.add(undead, UNDEAD_TICKS)
        undead.opPlayer2(player, aiInteractions)
    }
}

private const val UNDEAD_TICKS = 200
private val UNDEAD_OFFSETS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

/** The transcript's {{mes}} lines (game messages, not message boxes). */
internal val SV_MES: Set<String> =
    setOf(
        "You start digging...",
        "You dig a small hole and almost immediately hit granite.",
        "You excavate the hole a bit more...",
        "And see that there is a small fissure...",
        "You might just be able to crawl through it...",
        "You start to contort your body...",
        "With some difficulty you manage to push yourself through the small crack in the rock.",
        "You cleverly use the rope to slowly lower yourself to the floor.",
        "You manage to wriggle through the rubble.",
        "You decide to stay where you are.",
        "You decide to leave the scroll well alone.",
        "You search the gallows...",
        "You place it carefully on the water!",
        "You board the raft!",
        "You push off!",
        "You come to a huge waterfall...",
        "...And plough through it!",
        "The raft soon breaks up.",
        "You start carefully edging along the slippery path...",
        "You climb your way out of the cavern into the heat of the Jungle.",
        "You fall!",
        "You're pumelled as the thrashing water throws",
        "you against the rocks.",
        "You feel an unearthly compunction to bury this corpse!",
        "You dig a hole for the remains.",
        "You lay the remains of Zadimus to rest.",
        "You attempt to climb the granite rock.",
        "You manage to climb back out again!",
        "You search the palm trees...",
        "...and reveal an ancient doorway set into the side of the hill!",
        "You try the bone key with the lock.",
        "A shimmering light dances over the doors, before you can blink, the doors creak open.",
        "Rashiliyia appears!",
        "You feel invisible hands starting to choke you...",
        "The Beads of the Dead start to glow...",
        "You carefully try to pick your way down the rocks.",
        "You manage to carefully clamber down.",
        "As soon as you touch the coins you hear the grinding sound of bones against stone as you see skeletons and zombies rising up out of the ground. The coins turn to dust in your hands.",
        "This door is completely sealed, it is very ornately carved.",
        "You carefully place a bone in the door...",
        "You fit the bone into the recess.",
        "You carefully place another bone in the door...",
        "You fit the last bone into the recess.",
        "Nazastarool: May you perish in the fires of Zamorak's furnace! Rashiliyia's",
        "Nazastarool: curse is upon you!",
        "The door seems to be locked!",
        "You unlock the door with the key.",
        "The doors creak open revealing bright daylight.",
        "You walk outside into the warmth of the jungle heat.",
        "You get a sense that something seems incomplete.",
        "You have already completed this quest.",
        "Rashiliyia's remains have been put to rest.",
    )
