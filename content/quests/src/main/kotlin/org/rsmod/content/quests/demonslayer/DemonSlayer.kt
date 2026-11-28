package org.rsmod.content.quests.demonslayer

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onArea
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onModifyNpcHit
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.builders.area.AreaBuilder
import org.rsmod.api.type.builders.map.area.MapAreaBuilder
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.area.AreaReferences
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.cutscene.CutsceneRunner
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.area.polygon.VertexCoord
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Demon Slayer (OSRS Wiki). Varbit `demonslayer_main` (cache db end state 3): 0, 1 Aris's vision
 * (her random five-word incantation goes in `delrith_incantation_1..5`), 2 Sir Prysin handed over
 * Silverlight, 3 Delrith banished (3 QP, Silverlight kept).
 *
 * The three keys: Captain Rovin's (palace guard), the drain key (a bucket of water down the
 * kitchen drain washes it into the sewers; `delrith_drain_key` 0 in the drain / 1 in the mud /
 * 2 taken, which the cache multilocs follow) and Wizard Traiborn's (25 bones, counted in server
 * varp `ds_traiborn_bones`).
 *
 * The fight: entering the stone circle south of Varrock (custom map area `delrith_circle`) with
 * Silverlight plays the summoning cutscene (dark wizards, Delrith appears; `delrith_seen_summoning
 * _cutscene`) and sets Delrith on you. Only Silverlight wielded hurts him (NpcHitEvents.Modify);
 * at 0 hitpoints he becomes Weakened Delrith, whose Banish op takes the incantation - the right
 * five words banish him, a wrong word lets him recover.
 * Not done: the dark wizards fighting alongside, Aris's antique lamp, the wardrobe animation.
 */
class DemonSlayer
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val cutscenes: CutsceneRunner,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(DemonSlayer::class.java, "demon_slayer") }

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Delrith" to DsNpcs.delrith,
            "Wally" to DsNpcs.wally,
            "Denath" to DsNpcs.denath,
            "Dark wizard" to DsNpcs.wizards.first(),
            "Dark wizards" to DsNpcs.wizards.first(),
        )
    }

    /** The Delrith (or weakened Delrith) each player is fighting. */
    private val demons = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, DsNpcs.aris, "Talk-to") { talkAris(it) }
        for (prysin in DsNpcs.prysins) {
            onNpcOpLabel(npcTypes, prysin, "Talk-to") { talkPrysin(it) }
        }
        onNpcOpLabel(npcTypes, DsNpcs.rovin, "Talk-to") { talkRovin(it) }
        with(QuestTalk) { default(npcTypes, DsNpcs.traiborn) { talkTraiborn(it) } }
        onOpNpcU(DsNpcs.traiborn, DsObjs.bones) { talkTraiborn(it.npc) }
        for (drain in DsLocs.drains) {
            onLocOpLabel(locTypes, drain, "Search") { searchDrain() }
            onOpLocU(drain, DsObjs.bucket_water) { pourWater() }
        }
        onLocOpLabel(locTypes, DsLocs.sewer_key, "Take") { takeSewerKey() }
        onArea(DsAreas.delrith_circle) { enterCircle() }
        onModifyNpcHit(DsNpcs.delrith) { silverlightOnly(hit) }
        onEvent<NpcKilledEvent> { delrithDown(this) }
        onNpcOpLabel(npcTypes, DsNpcs.weakened, "Banish") { banish(it) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(DEMON_SLAYER)

    private fun ProtectedAccess.incantation(): List<String> =
        DsVarBits.incantation.map { WORDS[player.vars[it].coerceIn(WORDS.indices)] }

    private fun ProtectedAccess.tellIncantation() {
        mes("The incantation is: ${incantation().joinToString(" ")}.")
    }

    /* Aris */

    private suspend fun ProtectedAccess.talkAris(npc: Npc) {
        var tell = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have any money with them:" to {
                            !carries(DsObjs.coins)
                        },
                    ),
                options =
                    mapOf(
                        "What is the magical incantation?" to { tell = true },
                        "Okay, thanks. I'll do my best to stop the demon." to { start() },
                    ),
            )
        val section =
            when {
                state == 0 -> "Starting off"
                state == STARTED -> "Aris after starting the quest"
                state == SWORD -> "Aris after acquiring the sword"
                else -> "Talking to Aris"
            }
        runQuestSection(npc, dialogue, section, hooks, speakers)
        if (tell && state in STARTED..SWORD) tellIncantation()
    }

    private fun ProtectedAccess.start() {
        if (state != 0) return
        if (carries(DsObjs.coins)) invDel(inv, DsObjs.coins)
        val order = WORDS.indices.shuffled()
        for ((slot, varbit) in DsVarBits.incantation.withIndex()) {
            VarPlayerIntMapSetter.set(player, varbit, order[slot])
        }
        player.setQuestState(DEMON_SLAYER, STARTED)
        tellIncantation()
    }

    /* Sir Prysin and the keys */

    private suspend fun ProtectedAccess.talkPrysin(npc: Npc) {
        val keys = DsObjs.keys
        val held = keys.map { carries(it) }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the key from Captain Rovin:" to { held[0] },
                        "If the player has the key from the drain:" to { held[1] },
                        "If the player has the key from Wizard Traiborn:" to { held[2] },
                        "If the player has keys from Traiborn and Rovin:" to {
                            held[2] && held[0]
                        },
                        "If the player has keys from Rovin and the drain:" to {
                            held[0] && held[1]
                        },
                        "If the player has keys from Traiborn and the drain:" to {
                            held[2] && held[1]
                        },
                        "If the player has Silverlight:" to { carries(DsObjs.silverlight) },
                        "If the player does not have Silverlight:" to {
                            !carries(DsObjs.silverlight)
                        },
                    ),
            )
        val count = held.count { it }
        val section =
            when {
                state == STARTED && count == keys.size -> PRYSIN_ALL_KEYS
                state == STARTED && count == 2 -> "Talking to Sir Prysin with two keys"
                state == STARTED && count == 1 -> "Returning to Sir Prysin with one key"
                state == STARTED && player.vars[DsVarBits.drain_key] > 0 ->
                    "Talking to Sir Prysin again before getting keys"
                state == STARTED -> "Sir Prysin"
                state == SWORD -> "Talking to Sir Prysin before fighting Delrith"
                else -> null
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "Good day to you.") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (section == PRYSIN_ALL_KEYS) {
            for (key in keys) invDel(inv, key)
            invAddOrDrop(objRepo, DsObjs.silverlight)
            VarPlayerIntMapSetter.set(player, DsVarBits.silverlight_case, 1)
            player.setQuestState(DEMON_SLAYER, SWORD)
        }
        if (state == SWORD && !carries(DsObjs.silverlight) && !wieldsSilverlight()) {
            invAddOrDrop(objRepo, DsObjs.silverlight)
            mes("Sir Prysin hands you another Silverlight. Try not to lose this one.")
        }
    }

    private fun ProtectedAccess.wieldsSilverlight(): Boolean =
        invTotal(worn, DsObjs.silverlight) > 0

    private suspend fun ProtectedAccess.talkRovin(npc: Npc) {
        var gave = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the key in their inventory:" to {
                            carries(DsObjs.keys[0])
                        },
                        "If the player has the key in their bank:" to { false },
                    ),
                boxes =
                    mapOf(
                        "Captain Rovin hands you a key." to {
                            gave = true
                            false
                        },
                    ),
            )
        val section =
            if (state == STARTED && !carries(DsObjs.keys[0])) {
                "Getting the key from Captain Rovin"
            } else {
                "Talking to Captain Rovin again after obtaining the key"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (gave) invAddOrDrop(objRepo, DsObjs.keys[0])
    }

    /* The drain */

    private suspend fun ProtectedAccess.searchDrain() {
        val section =
            if (state == STARTED && player.vars[DsVarBits.drain_key] == 0) {
                "Searching the drain near the palace kitchen"
            } else {
                "Searching the drain after washing down the key/outside the quest"
            }
        runQuestSection(null, dialogue, section)
    }

    private suspend fun ProtectedAccess.pourWater() {
        invDel(inv, DsObjs.bucket_water)
        invAdd(inv, DsObjs.bucket)
        if (state == STARTED && player.vars[DsVarBits.drain_key] == 0) {
            runQuestSection(null, dialogue, "Pouring water in the drain")
            VarPlayerIntMapSetter.set(player, DsVarBits.drain_key, IN_MUD)
        } else {
            runQuestSection(null, dialogue, "Pouring more water down the drain")
        }
    }

    private suspend fun ProtectedAccess.takeSewerKey() {
        if (player.vars[DsVarBits.drain_key] != IN_MUD || carries(DsObjs.keys[1])) return
        runQuestSection(null, dialogue, "Picking up the key from the sewers")
        invAddOrDrop(objRepo, DsObjs.keys[1])
        VarPlayerIntMapSetter.set(player, DsVarBits.drain_key, TAKEN)
    }

    /* Wizard Traiborn's bones */

    private suspend fun ProtectedAccess.talkTraiborn(npc: Npc) {
        val given = player.vars[DsVarps.traiborn_bones]
        val key = DsObjs.keys[2]
        var asked = false
        var handed = 0
        val hooks =
            SimpleQuestHooks(
                options = mapOf("I'll get the bones for you." to { asked = true }),
                rewrites = { it.replace("[X]", handed.toString()) },
            )
        val carried = invTotal(inv, DsObjs.bones)
        val section =
            when {
                state != STARTED -> null
                carries(key) -> "Traiborn after obtaining the key"
                given >= BONES_NEEDED -> "Traiborn after losing the key"
                given == 0 && carried == 0 && !asked -> "Getting the key from Traiborn"
                carried == 0 -> "Returning with no bones to Traiborn"
                given + carried >= BONES_NEEDED -> TRAIBORN_LAST
                else -> "Returning with some bones to Traiborn"
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(neutral, "Ello young thingummywut.") }
            return
        }
        handed = minOf(carried, BONES_NEEDED - given).coerceAtLeast(0)
        if (section.startsWith("Returning with") && handed > 0) {
            invDel(inv, DsObjs.bones, handed)
            VarPlayerIntMapSetter.set(player, DsVarps.traiborn_bones, given + handed)
        }
        runQuestSection(npc, dialogue, section, hooks)
        val total = player.vars[DsVarps.traiborn_bones]
        if (total >= BONES_NEEDED && !carries(key)) invAddOrDrop(objRepo, key)
    }

    /* The stone circle */

    private suspend fun ProtectedAccess.enterCircle() {
        if (state != SWORD || (!carries(DsObjs.silverlight) && !wieldsSilverlight())) return
        val current = demons[player.uid.packed]
        if (current != null && current.isSlotAssigned) return
        if (player.vars[DsVarBits.seen_summoning] == 0) {
            val finished =
                cutscenes.play(this, template = null, returnTo = player.coords) {
                    val delrith = spawn(DsNpcs.delrith, ALTAR)
                    val denath = spawn(DsNpcs.denath, ALTAR.translate(0, -3))
                    for ((index, wizard) in DsNpcs.wizards.withIndex()) {
                        spawn(wizard, WIZARD_SPOTS[index]).faceTile(ALTAR)
                    }
                    denath.faceTile(ALTAR)
                    camMoveTo(CAMERA, height = 700)
                    camLookAt(ALTAR, height = 200)
                    fadeIn()
                    camShake(axis = 1, random = 0, amplitude = 6, rate = 30)
                    delay(3)
                    conversation {
                        runQuestSection(null, dialogue, "The final battle", speakers = speakers)
                    }
                    despawn(delrith)
                    denath.walkTo(ALTAR.translate(0, -10))
                    fadeOut()
                    delay(2)
                }
            if (!finished) return
            VarPlayerIntMapSetter.set(player, DsVarBits.seen_summoning, 1)
        }
        val delrith = Npc(npcTypes[DsNpcs.delrith], ALTAR)
        npcRepo.add(delrith, DEMON_TICKS)
        demons[player.uid.packed] = delrith
        delrith.opPlayer2(player, aiInteractions)
    }

    /** Only Silverlight wielded hurts Delrith. */
    private fun silverlightOnly(hit: HitBuilder) {
        if (hit.isFromPlayer && !hit.isRighthandObj(DsObjs.silverlight)) {
            hit.damage = 0
        }
    }

    private fun delrithDown(event: NpcKilledEvent) {
        if (event.npc.type.id != DsNpcs.delrith.id) return
        val player = event.hero
        if (player.questState(DEMON_SLAYER) != SWORD) return
        val weakened = Npc(npcTypes[DsNpcs.weakened], event.npc.coords)
        npcRepo.add(weakened, WEAKENED_TICKS)
        demons[player.uid.packed] = weakened
        player.mes("Delrith is weakened. Now is your chance to banish him!")
    }

    private suspend fun ProtectedAccess.banish(npc: Npc) {
        if (demons[player.uid.packed] != npc || state != SWORD) return
        val spoken = ArrayList<String>()
        repeat(WORDS.size) {
            var word = ""
            startDialogue {
                word = choice5(
                    WORDS[0], WORDS[0],
                    WORDS[1], WORDS[1],
                    WORDS[2], WORDS[2],
                    WORDS[3], WORDS[3],
                    WORDS[4], WORDS[4],
                    title = "Select the next word",
                )
            }
            spoken += word
        }
        chant(spoken)
        if (spoken != incantation()) {
            startDialogue { mesbox("The vortex collapses. That was the wrong incantation.") }
            if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
            demons.remove(player.uid.packed)
            val delrith = Npc(npcTypes[DsNpcs.delrith], npc.coords)
            npcRepo.add(delrith, DEMON_TICKS)
            demons[player.uid.packed] = delrith
            delrith.opPlayer2(player, aiInteractions)
            return
        }
        startDialogue {
            mesbox("Delrith is sucked into the vortex...")
            mesbox("...back into the dark dimension from which he came.")
        }
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
        demons.remove(player.uid.packed)
        completeQuest(DEMON_SLAYER)
    }

    private suspend fun ProtectedAccess.chant(words: List<String>) {
        for (word in words) {
            say("$word...")
            delay(1)
        }
    }

    companion object {
        const val STARTED = 1
        const val SWORD = 2
        const val COMPLETE = 3

        private const val PRYSIN_ALL_KEYS = "Returning to Sir Prysin with all the keys"
        private const val TRAIBORN_LAST = "Returning with the last of the bones to Traiborn"
        private const val IN_MUD = 1
        private const val TAKEN = 2
        private const val BONES_NEEDED = 25
        private const val DEMON_TICKS = 500
        private const val WEAKENED_TICKS = 100

        val WORDS = listOf("Carlem", "Aber", "Camerinthum", "Purchai", "Gabindo")

        private val ALTAR = CoordGrid(3227, 3369, 0)
        private val CAMERA = CoordGrid(3227, 3361, 0)
        private val WIZARD_SPOTS =
            listOf(
                CoordGrid(3224, 3369, 0),
                CoordGrid(3230, 3369, 0),
                CoordGrid(3227, 3372, 0),
                CoordGrid(3227, 3366, 0),
            )

        val DEMON_SLAYER =
            QuestDef(
                name = "Demon Slayer",
                varbit = { DsVarBits.main },
                complete = COMPLETE,
                questPoints = 3,
                rewards = listOf("Silverlight"),
                scrollObj = { DsObjs.silverlight },
            )
    }
}

/** The stone circle south of Varrock where Delrith is summoned (stone table at 3227,3369). */
internal object DelrithCircleArea : MapAreaBuilder() {
    override fun onPackMapTask() {
        area(DsAreas.delrith_circle) {
            polygon(levels = 0..0) {
                vertex(VertexCoord(50, 52, 21, 34))
                vertex(VertexCoord(50, 52, 36, 34))
                vertex(VertexCoord(50, 52, 36, 50))
                vertex(VertexCoord(50, 52, 21, 50))
            }
        }
    }
}

// Every cache ref Demon Slayer uses (re-check after the rev 243 cutover).

internal object DsAreas : AreaReferences() {
    /** Custom map area (32763). */
    val delrith_circle = find("delrith_circle")
}

/** Creates the custom `delrith_circle` area type (the map polygon only places it). */
internal object DsAreaBuilds : AreaBuilder() {
    init {
        build("delrith_circle")
    }
}

internal object DsNpcs : NpcReferences() {
    val aris = find("aris")
    val prysins = listOf(find("sir_prysin"), find("sir_prysin_silverlight"))
    val rovin = find("captain_rovin")
    val traiborn = find("traiborn")
    val delrith = find("delrith")
    val weakened = find("delrith_weakened")
    val wally = find("qip_ds_wally")
    val denath = find("qip_ds_dark_wizard_denath")
    val wizards =
        listOf(
            find("qip_ds_young_dark_wizard1"),
            find("qip_ds_young_dark_wizard2"),
            find("qip_ds_young_dark_wizard3"),
            find("qip_ds_young_dark_wizard4"),
        )
}

internal object DsLocs : LocReferences() {
    /* Children of the questdrain multiloc (delrith_drain_key). */
    val drains = listOf(find("qip_ds_questdrain_key"), find("qip_ds_questdrain_nokey"))
    val sewer_key = find("qip_ds_rustykey_mud")
}

internal object DsObjs : ObjReferences() {
    /* Rovin's, the drain's, Traiborn's. */
    val keys =
        listOf(find("silverlight_key_1"), find("silverlight_key_2"), find("silverlight_key_3"))
    val silverlight = find("silverlight")
    val bones = find("bones")
    val bucket_water = find("bucket_water")
    val bucket = find("bucket_empty")
    val coins = find("coins")
}

internal object DsVarBits : VarBitReferences() {
    val main = find("demonslayer_main")
    val incantation =
        listOf(
            find("delrith_incantation_1"),
            find("delrith_incantation_2"),
            find("delrith_incantation_3"),
            find("delrith_incantation_4"),
            find("delrith_incantation_5"),
        )
    val drain_key = find("delrith_drain_key")
    val silverlight_case = find("delrith_silverlight_case")
    val seen_summoning = find("delrith_seen_summoning_cutscene")
}

internal object DsVarps : VarpReferences() {
    /** Server varp (65776): bones given to Traiborn. */
    val traiborn_bones = find("ds_traiborn_bones")
}

internal object DsVarpBuilds : VarpBuilder() {
    init {
        build("ds_traiborn_bones")
    }
}
