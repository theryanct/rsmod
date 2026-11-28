package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.fremenniktrials.FremennikTrials.Companion.FREMENNIK_TRIALS
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.stat.StatType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Olaf the Bard's trial (Wiki): a lyre (the swaying tree east of Rellekka -> a branch -> knife ->
 * unstrung lyre, strung with golden wool; or 1/17 from Lanzig, Borrokar, Lensa or Freidir once the
 * trial is on), Lalli's golden fleece (Askeladden's pet rock and a potato, cabbage and onion in his
 * stew make rock soup), spun outside Rellekka, Fossegrimen's blessing for a raw shark, manta ray,
 * sea turtle or bass on her altar, then past the bouncer and a song on the longhall stage.
 *
 * Simplified: the altar's "Summon" just points you at Fossegrimen (she stands by it already); the
 * ballad is the Wiki's achievement verse picked from 99s only (no guild checks); a few hecklers
 * shout from the crowd as messages.
 */
internal class FtOlaf(private val ft: FtContext) {
    private val lalliSpeakers by lazy { mapOf("Lalli" to FtNpcs.lalli) }
    private val bouncerSpeakers by lazy { mapOf("Longhall Bouncer" to FtNpcs.bouncer) }

    fun ScriptContext.bind() {
        with(QuestTalk) {
            register(ft.npcTypes, FtNpcs.lalli, FREMENNIK_TRIALS, wants = { player.onTrial(Trial.Olaf) }) { talkLalli(it) }
            register(ft.npcTypes, FtNpcs.fossegrimen, FREMENNIK_TRIALS, wants = { player.onTrial(Trial.Olaf) }) {
                talkFossegrimen(it)
            }
            register(ft.npcTypes, FtNpcs.bouncer, FREMENNIK_TRIALS, wants = { player.onTrial(Trial.Olaf) }) { talkBouncer(it) }
        }
        ft.bound("swaying_tree Cut-branch") { onLocOpLabel(ft.locTypes, FtLocs.swaying_tree, "Cut-branch") { cutBranch() } }
        onOpHeldU(FtObjs.knife, FtObjs.branch) { carveLyre() }
        onOpHeldU(FtObjs.golden_wool, FtObjs.unstrung_lyre) { stringLyre() }
        onOpHeldU(FtObjs.golden_fleece, FtObjs.unstrung_lyre) { mes("Nothing interesting happens.") }
        onOpHeldU(FtObjs.ball_of_wool, FtObjs.unstrung_lyre) { mes("Nothing interesting happens.") }
        for (type in ft.locTypes.values) {
            if (!type.name.trim().equals("Spinning wheel", ignoreCase = true)) continue
            onOpLocU(type, FtObjs.golden_fleece) { spinFleece(type.id == FtLocs.rellekka_spinning_wheel.id) }
        }
        for (item in listOf(FtObjs.pet_rock, FtObjs.potato, FtObjs.cabbage, FtObjs.onion)) {
            onOpLocU(FtLocs.lalli_stew, item) { addToStew(item) }
        }
        for (fish in OFFERINGS) {
            onOpLocU(FtLocs.strange_altar, fish()) { offer(fish()) }
        }
        onLocOpLabel(ft.locTypes, FtLocs.strange_altar, "Summon") {
            // Authored line (not Wiki): our Fossegrimen already stands by her altar.
            mes("Fossegrimen is already here. Place a raw fish on her altar, or speak with her.")
        }
        ft.bound("backstage_door Open") { onLocOpLabel(ft.locTypes, FtLocs.backstage_door, "Open") { backstageDoor(it) } }
        ft.bound("enchanted_lyre Play") { onHeldOpLabel(ft.objTypes, FtObjs.enchanted_lyre, "Play") { playEnchanted() } }
        onHeldOpLabel(ft.objTypes, FtObjs.lyre, "Play") {
            startDialogue { chatPlayer(neutral, "I really wouldn't know where to begin playing anything on this...") }
        }
        onEvent<NpcKilledEvent> { lyreDrop(this) }
    }

    /* Olaf */

    suspend fun ProtectedAccess.talk(npc: Npc) {
        when {
            !player.accepted(Trial.Olaf) -> {
                var accepted = false
                runQuestSection(npc, ft.dialogue, "Olaf the Bard", SimpleQuestHooks(options = mapOf("Yes" to { accepted = true })))
                if (accepted) player.accept(Trial.Olaf)
            }
            player.voted(Trial.Olaf) -> runQuestSection(npc, ft.dialogue, "Subsequent dialogue with Olaf")
            else -> runQuestSection(npc, ft.dialogue, "Talking to him again")
        }
    }

    /* The lyre */

    private fun ProtectedAccess.hasAxe(): Boolean =
        FtObjs.axes.any { carries(it) || invTotal(worn, it) > 0 }

    private suspend fun ProtectedAccess.cutBranch() {
        when {
            !player.onTrial(Trial.Olaf) -> mes("Nothing interesting happens.")
            !hasAxe() -> mes("You need an axe to cut a branch from this tree.") // Authored (not Wiki).
            stat(stats.woodcutting) < WOODCUTTING_LEVEL ->
                mes("You need a Woodcutting level of $WOODCUTTING_LEVEL to cut this tree.") // Authored.
            inv.isFull() -> mesbox("You do not have enough free space in your inventory to chop this tree.")
            else -> {
                delay(3)
                invAdd(inv, FtObjs.branch)
                mes("You cut a branch from the strangely musical tree.")
            }
        }
    }

    private fun ProtectedAccess.carveLyre() {
        if (stat(stats.crafting) < CRAFTING_LEVEL) {
            mes("You need a Crafting level of $CRAFTING_LEVEL to craft a lyre.") // Authored (not Wiki).
            return
        }
        invReplace(inv, FtObjs.branch, 1, FtObjs.unstrung_lyre)
        mes("You craft an unstrung lyre out of the branch.")
    }

    private suspend fun ProtectedAccess.stringLyre() {
        if (stat(stats.fletching) < FLETCHING_LEVEL) {
            mesbox("You need to have a Fletching level of $FLETCHING_LEVEL to string the lyre.")
            return
        }
        invDel(inv, FtObjs.golden_wool)
        invReplace(inv, FtObjs.unstrung_lyre, 1, FtObjs.lyre)
        mes("You attach the golden strings to the lyre.")
    }

    private fun ProtectedAccess.spinFleece(rellekka: Boolean) {
        if (rellekka && !player.ftComplete) {
            mes("Only Fremenniks may use this spinning wheel.")
            return
        }
        invReplace(inv, FtObjs.golden_fleece, 1, FtObjs.golden_wool)
        mes("You spin the golden fleece into a ball of golden wool.") // Authored (Wiki gives no message).
    }

    /* Lalli and the rock soup */

    private suspend fun ProtectedAccess.talkLalli(npc: Npc) {
        val olaf = FtVarps.olaf
        when {
            !player.ftHas(olaf, LALLI_TOLD) -> {
                var told = false
                runQuestSection(npc, ft.dialogue, "Talking to Lalli", SimpleQuestHooks(options = mapOf("Other human?" to { told = true })))
                if (told) player.ftSet(olaf, LALLI_TOLD)
            }
            !player.ftHas(olaf, ROCK_REFUSED) ->
                if (carries(FtObjs.pet_rock)) {
                    runQuestSection(npc, ft.dialogue, "Talking to Lalli again with the pet rock")
                    player.ftSet(olaf, ROCK_REFUSED)
                } else {
                    runQuestSection(npc, ft.dialogue, "Talking to Lalli")
                }
            !player.ftHas(olaf, SOUP_DONE) -> runQuestSection(npc, ft.dialogue, "Talking to Lalli again")
            else -> {
                val has = carries(FtObjs.golden_fleece)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have the golden fleece in their inventory:" to { !has },
                                "If the player already has golden fleece in their inventory:" to { has },
                            )
                    )
                runQuestSection(npc, ft.dialogue, "Talking to Lalli after making the soup", hooks)
                if (has) {
                    mes("You already have the golden fleece.")
                } else {
                    invAddOrDrop(ft.objRepo, FtObjs.golden_fleece)
                    player.ftSet(olaf, FLEECE_GIVEN)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.addToStew(item: ObjType) {
        val olaf = FtVarps.olaf
        if (!player.onTrial(Trial.Olaf) || !player.ftHas(olaf, ROCK_REFUSED) || player.ftHas(olaf, SOUP_DONE)) {
            mes("Nothing interesting happens.")
            return
        }
        val bit = STEW_BITS.getValue(item.id)
        if (player.ftHas(olaf, bit)) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, item)
        player.ftSet(olaf, bit)
        mes(
            when (item.id) {
                FtObjs.pet_rock.id -> "You put your pet rock into the cauldron."
                FtObjs.onion.id -> "You put an onion into the cauldron."
                FtObjs.potato.id -> "You put a potato into the cauldron."
                else -> "You put a cabbage into the cauldron."
            }
        )
        val done = STEW_BITS.values.all { player.ftHas(olaf, it) }
        val condText =
            if (done) {
                "If the player is done adding the vegetables and pet rock into the soup:"
            } else {
                "If the player adds vegetable into the cauldron:"
            }
        with(ft) { cond(null, "Making rock soup", condText, speakers = lalliSpeakers) }
        if (done) player.ftSet(olaf, SOUP_DONE)
    }

    /* Askeladden (his own conversation; FremennikTrials routes the merchant's trial) */

    suspend fun ProtectedAccess.talkAskeladden(npc: Npc) {
        val olaf = FtVarps.olaf
        if (!player.onTrial(Trial.Olaf) || !player.ftHas(olaf, LALLI_TOLD)) {
            runQuestSection(npc, ft.dialogue, "Askeladden")
            return
        }
        if (!player.ftHas(olaf, ROCK_GIVEN)) {
            runQuestSection(npc, ft.dialogue, "Talking to Askeladden")
            invAddOrDrop(ft.objRepo, FtObjs.pet_rock)
            player.ftSet(olaf, ROCK_GIVEN)
            return
        }
        val has = carries(FtObjs.pet_rock) || player.ftHas(olaf, BIT_ROCK)
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has the pet rock:" to { has },
                        "If the player does not have the pet rock:" to { !has },
                    )
            )
        runQuestSection(npc, ft.dialogue, "Talking to Askeladden again", hooks)
        if (!has) invAddOrDrop(ft.objRepo, FtObjs.pet_rock)
    }

    /* Fossegrimen */

    private suspend fun ProtectedAccess.talkFossegrimen(npc: Npc) {
        var offered: ObjType? = null
        val conditions = HashMap<String, () -> Boolean>()
        val options = HashMap<String, suspend () -> Unit>()
        for ((label, fishRef) in FISH_OPTIONS) {
            val fish = fishRef()
            val name = label.removeSuffix(".").lowercase()
            conditions["If the player does not have a $name:"] = { !carries(fish) }
            conditions["If the player has a $name:"] = { carries(fish) }
            options[label] = { if (carries(fish)) offered = fish }
        }
        // Wiki markup slip: "If the player has a manta ray:".
        conditions["If the player has a manta ray:"] = { carries(FtObjs.raw_manta_ray) }
        runQuestSection(npc, ft.dialogue, "Talking to the Fossegrimen", SimpleQuestHooks(conditions = conditions, options = options))
        val fish = offered ?: return
        offer(fish, npc)
    }

    private suspend fun ProtectedAccess.offer(fish: ObjType, npc: Npc? = null) {
        if (!player.onTrial(Trial.Olaf)) {
            mes("Nothing interesting happens.")
            return
        }
        if (!carries(FtObjs.lyre)) {
            mes("You have no lyre for Fossegrimen to enchant.") // Authored (not Wiki).
            return
        }
        invDel(inv, fish)
        val hooks = SimpleQuestHooks(conditions = mapOf("If the player deposits a raw fish:" to { true }))
        runQuestSection(npc, ft.dialogue, "Fossegrimen's blessing", hooks, mapOf("Fossegrimen" to FtNpcs.fossegrimen))
        invReplace(inv, FtObjs.lyre, 1, FtObjs.enchanted_lyre)
        mes("Fossegrimen has enchanted your lyre so that you may play it.")
    }

    /* The longhall stage */

    private suspend fun ProtectedAccess.talkBouncer(npc: Npc) {
        if (carries(FtObjs.enchanted_lyre)) {
            runQuestSection(npc, ft.dialogue, "Talking to the Longhall with the blessed lyre")
            return
        }
        // Authored line (not Wiki): no instrument, no stage.
        startDialogue(npc) { chatNpc(neutral, "Performers only through that door, outerlander. Come back when you have a blessed instrument.") }
    }

    private suspend fun ProtectedAccess.backstageDoor(door: BoundLocInfo) {
        val inside = player.ftHas(FtVarps.olaf, BACKSTAGE) && player.coords.chebyshevDistance(STAGE) < door.coords.chebyshevDistance(STAGE)
        if (!inside && !player.voted(Trial.Olaf)) {
            if (!player.onTrial(Trial.Olaf) || !carries(FtObjs.enchanted_lyre)) {
                // Authored line (not Wiki).
                mes("The bouncer won't let you backstage without a blessed instrument.")
                return
            }
            runQuestSection(null, ft.dialogue, "Entering the backstage", speakers = bouncerSpeakers)
        }
        passThroughWall(door)
        player.ftSet(FtVarps.olaf, BACKSTAGE)
    }

    private suspend fun ProtectedAccess.playEnchanted() {
        val onStage =
            player.ftHas(FtVarps.olaf, BACKSTAGE) && player.coords.level == STAGE.level && player.coords.chebyshevDistance(STAGE) <= STAGE_RADIUS
        if (!player.onTrial(Trial.Olaf) || !onStage) {
            val lines = ft.dialogue.section("Tuning the lyre").filterIsInstance<Node.Line>()
            val line = lines[ft.random.of(0, lines.lastIndex)]
            startDialogue { QuestDialogueRunner(SimpleQuestHooks()).run(this, listOf(line)) }
            mes("Your lyre is perfectly tuned.")
            return
        }
        mes("You withdraw your lyre.")
        val skill99 = SKILL_NAMES.entries.firstOrNull { (stat, _) -> statBase(stat()) >= 99 }?.value
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has level 99 in any skill:" to { skill99 != null },
                        "If the player doesn't have any achievements:" to { skill99 == null },
                    ),
                rewrites = { it.replace("[skill]", skill99 ?: "") },
            )
        repeat(HECKLES) {
            val heckle = HECKLER_LINES[ft.random.of(0, HECKLER_LINES.lastIndex)]
            mes(HECKLERS[ft.random.of(0, HECKLERS.lastIndex)] + ": $heckle")
        }
        runQuestSection(null, ft.dialogue, "Performing with the Lyre", hooks, mapOf("Olaf the Bard" to FtNpcs.olaf))
        invReplace(inv, FtObjs.enchanted_lyre, 1, FtObjs.lyre)
        player.giveVote(Trial.Olaf)
        mes("Congratulations! You have completed the Bard's Trial!")
        mes("As you finished playing you felt Fossegrimen's power leave you...")
    }

    /* Lyre drops */

    private fun lyreDrop(event: NpcKilledEvent) {
        if (event.npc.type.id !in DROPPERS) return
        val player = event.hero
        if (!player.onTrial(Trial.Olaf)) return
        if (ft.random.of(1, LYRE_DROP_RATE) != 1) return
        ft.objRepo.add(FtObjs.lyre, event.npc.coords, DROP_TICKS, player)
        player.mes("The Fremennik drops a lyre!") // Authored (the Wiki lists it as a 1/17 drop).
    }

    private val DROPPERS: Set<Int> by lazy {
        setOf(FtNpcs.lanzig.id, FtNpcs.borrokar.id, FtNpcs.lensa.id, FtNpcs.freidir.id)
    }

    private companion object {
        const val LALLI_TOLD = 1 shl 0
        const val ROCK_GIVEN = 1 shl 1
        const val ROCK_REFUSED = 1 shl 2
        const val BIT_ROCK = 1 shl 3
        const val BIT_POTATO = 1 shl 4
        const val BIT_CABBAGE = 1 shl 5
        const val BIT_ONION = 1 shl 6
        const val SOUP_DONE = 1 shl 7
        const val FLEECE_GIVEN = 1 shl 8
        const val BACKSTAGE = 1 shl 9

        const val WOODCUTTING_LEVEL = 40
        const val CRAFTING_LEVEL = 40
        const val FLETCHING_LEVEL = 25
        const val LYRE_DROP_RATE = 17
        const val DROP_TICKS = 200
        const val HECKLES = 2

        val STEW_BITS: Map<Int, Int> by lazy {
            mapOf(
                FtObjs.pet_rock.id to BIT_ROCK,
                FtObjs.potato.id to BIT_POTATO,
                FtObjs.cabbage.id to BIT_CABBAGE,
                FtObjs.onion.id to BIT_ONION,
            )
        }

        val OFFERINGS: List<() -> ObjType> =
            listOf({ FtObjs.raw_shark }, { FtObjs.raw_manta_ray }, { FtObjs.raw_sea_turtle }, { FtObjs.raw_bass })

        val FISH_OPTIONS: List<Pair<String, () -> ObjType>> =
            listOf(
                "Raw shark." to { FtObjs.raw_shark },
                "Raw manta ray." to { FtObjs.raw_manta_ray },
                "Raw sea turtle." to { FtObjs.raw_sea_turtle },
                "Raw bass." to { FtObjs.raw_bass },
            )

        /** The longhall stage, in front of the backstage door (approximate: we have no stage area). */
        val STAGE = CoordGrid(2664, 3683, 0)
        const val STAGE_RADIUS = 6

        val SKILL_NAMES: Map<() -> StatType, String> =
            linkedMapOf(
                { stats.attack } to "Attack",
                { stats.defence } to "Defence",
                { stats.strength } to "Strength",
                { stats.hitpoints } to "Hitpoints",
                { stats.ranged } to "Ranged",
                { stats.prayer } to "Prayer",
                { stats.magic } to "Magic",
                { stats.cooking } to "Cooking",
                { stats.woodcutting } to "Woodcutting",
                { stats.fletching } to "Fletching",
                { stats.fishing } to "Fishing",
                { stats.firemaking } to "Firemaking",
                { stats.crafting } to "Crafting",
                { stats.smithing } to "Smithing",
                { stats.mining } to "Mining",
                { stats.herblore } to "Herblore",
                { stats.agility } to "Agility",
                { stats.thieving } to "Thieving",
                { stats.slayer } to "Slayer",
                { stats.farming } to "Farming",
                { stats.runecrafting } to "Runecraft",
                { stats.hunter } to "Hunter",
                { stats.construction } to "Construction",
            )

        val HECKLERS = listOf("Fridgeir", "Ospak", "Styrmir", "Torbrund")

        /** Wiki "Audience member dialogue". */
        val HECKLER_LINES =
            listOf(
                "I have met cows more musical than you!",
                "I thought somebody was murdering a cat!",
                "Is your singing some kind of outerlander weapon?",
                "Worst. Bard. EVER.",
                "You call THAT singing?",
                "Don't give up your day job outerlander!",
                "You know, I actually quite liked that verse...",
                "That's kind of catchy. Irritating. But catchy.",
                "When do you get to the good parts?",
                "YOU ARE A TERRIBLE BARD!",
            )
    }
}
