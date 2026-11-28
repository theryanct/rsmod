package org.rsmod.content.quests.ragandboneman

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogue.Node
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rag and Bone Man I (OSRS Wiki). Cache quest varp `rag_quest`, end state 4 (no requirements).
 * - 1 the Odd Old Man (Silvarea, east of Varrock) wants eight bones: goblin, bear, big frog, ram,
 *   unicorn, monkey, giant rat and giant bat (each a guaranteed drop while needed). Fortunato, the
 *   Draynor wine merchant, sells jugs of vinegar; vinegar into an empty pot, the bone into the pot,
 *   and the pot on the Odd Old Man's pot-boiler (with logs and a tinderbox) polishes it.
 * - Polished bones are handed in as you go (server varp `rb_progress`, one bit per bone); the last
 *   one -> 4: 1 QP, 500 Cooking and 500 Prayer XP.
 *
 * Simplified: the pot-boiler boils every potted bone in one go (one log per batch) instead of
 * showing the pot and fire stages of the `rag_multi_potboiler` multiloc.
 */
class RagAndBoneManI
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(RagAndBoneManI::class.java, "rag_and_bone_man_1") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            default(npcTypes, RbNpcs.odd_old_man) { talkOldMan(it) }
            default(npcTypes, RbNpcs.fortunato) { talkFortunato(it) }
        }
        onEvent<NpcKilledEvent> { npcKilled(this) }
        onOpHeldU(RbObjs.vinegar, RbObjs.pot_empty) { fillPot() }
        for (bone in BONES) {
            onOpHeldU(bone.raw, RbObjs.pot_vinegar) { potBone(bone) }
            for (boiler in RbLocs.boilers) onOpLocU(boiler, bone.potted) { boil() }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(RAG_AND_BONE_MAN_I)

    private fun Player.handed(): Int = vars[RbVarps.progress]

    private fun ProtectedAccess.give(obj: ObjType) {
        invAddOrDrop(objRepo, obj)
    }

    /* The Odd Old Man */

    private suspend fun ProtectedAccess.talkOldMan(npc: Npc) {
        when {
            state == 0 -> {
                var start = false
                val hooks = SimpleQuestHooks(options = mapOf("Yes" to { start = true }))
                runQuestSection(npc, dialogue, "Starting off", hooks)
                if (start) player.setQuestState(RAG_AND_BONE_MAN_I, STARTED)
            }
            state >= COMPLETE ->
                // Authored (not Wiki): his quest is done; Rag and Bone Man II carries on from here.
                startDialogue(npc) { chatNpc(happy, "Thanks again for the bones! The displays are coming along.") }
            BONES.any { !isHanded(it) && carries(it.polished) } -> handIn(npc)
            BONES.any { !isHanded(it) && (carries(it.raw) || carries(it.potted)) } ->
                runQuestSection(npc, dialogue, "Trying to hand in unclean bones")
            else -> {
                val nodes = stillNeeded(dialogue.section("Before having any bones"))
                startDialogue(npc) { QuestDialogueRunner(SimpleQuestHooks()).run(this, nodes) }
            }
        }
    }

    private fun ProtectedAccess.isHanded(bone: Bone): Boolean = (player.handed() and bone.bit) != 0

    private suspend fun ProtectedAccess.handIn(npc: Npc) {
        var handed = player.handed()
        for (bone in BONES) {
            if (handed and bone.bit != 0 || !carries(bone.polished)) continue
            invDel(inv, bone.polished)
            handed = handed or bone.bit
        }
        VarPlayerIntMapSetter.set(player, RbVarps.progress, handed)
        if (BONES.all { handed and it.bit != 0 }) {
            runQuestSection(npc, dialogue, "Finishing up")
            statAdvance(stats.cooking, XP * xpMods.get(player, stats.cooking))
            statAdvance(stats.prayer, XP * xpMods.get(player, stats.prayer))
            completeQuest(RAG_AND_BONE_MAN_I)
        } else {
            runQuestSection(npc, dialogue, "Handing in some bones")
        }
    }

    /**
     * The Wiki's rundown lists all eight bones ("Sack: Mumblemumble", "You still need...", hint);
     * keep only those not yet handed in.
     */
    private fun ProtectedAccess.stillNeeded(section: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        var skip = 0
        for ((i, node) in section.withIndex()) {
            if (skip > 0) {
                skip--
                continue
            }
            val next = section.getOrNull(i + 1)
            val bone =
                (next as? Node.Line)?.let { line -> BONES.firstOrNull { line.text == "You still need to bring me a ${it.name} bone." } }
            if (node is Node.Line && node.speaker == "Sack" && bone != null && isHanded(bone)) {
                skip = 2 // this mumble, the "still need" line and its hint
                continue
            }
            if (node is Node.Action && node.tag.startsWith("Depending")) continue
            out += node
        }
        return out
    }

    /* Fortunato */

    private suspend fun ProtectedAccess.talkFortunato(npc: Npc) {
        if (state !in STARTED until COMPLETE) {
            // Authored (not Wiki).
            startDialogue(npc) { chatNpc(happy, "Welcome to my wine shop! Trade with me if you'd like a bottle.") }
            return
        }
        if (player.handed() and TALKED_FORTUNATO == 0) {
            runQuestSection(npc, dialogue, "Getting the vinegar")
            VarPlayerIntMapSetter.set(player, RbVarps.progress, player.handed() or TALKED_FORTUNATO)
            return
        }
        // Authored (not Wiki): the shop itself is his Trade option.
        val hooks = SimpleQuestHooks(actions = mapOf("Wine Shop. opens." to { mes("Trade with Fortunato to buy vinegar.") }))
        runQuestSection(npc, dialogue, "Need more vinegar", hooks)
    }

    /* Pots, vinegar and the pot-boiler */

    private fun ProtectedAccess.fillPot() {
        invDel(inv, RbObjs.vinegar)
        invReplace(inv, RbObjs.pot_empty, 1, RbObjs.pot_vinegar)
        give(RbObjs.jug_empty)
        mes("You pour the vinegar into the pot.") // Authored (not Wiki).
    }

    private fun ProtectedAccess.potBone(bone: Bone) {
        invDel(inv, bone.raw)
        invReplace(inv, RbObjs.pot_vinegar, 1, bone.potted)
        mes("You put the bone in the pot of vinegar.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.boil() {
        if (!carries(RbObjs.logs) || !carries(RbObjs.tinderbox)) {
            mes("You need some logs and a tinderbox to light the pot-boiler.") // Authored (not Wiki).
            return
        }
        invDel(inv, RbObjs.logs)
        mes("You light the logs under the pot-boiler.") // Authored (not Wiki).
        for (bone in BONES) {
            while (carries(bone.potted)) {
                delay(BOIL_TICKS)
                invReplace(inv, bone.potted, 1, bone.polished)
                give(RbObjs.pot_empty)
                mes("The vinegar boils away, leaving a nice clean bone.") // Authored (not Wiki).
            }
        }
    }

    /* Drops */

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        val state = player.questState(RAG_AND_BONE_MAN_I)
        if (state !in STARTED until COMPLETE) return
        val name = event.npc.type.name.lowercase()
        val bone = BONES.firstOrNull { name in it.monsters } ?: return
        if (player.handed() and bone.bit != 0) return
        val inv = player.inv
        if (listOf(bone.raw, bone.potted, bone.polished).any { inv.count(objTypes[it]) > 0 }) return
        objRepo.add(bone.raw, event.npc.coords, DROP_TICKS, player)
    }

    /** One wanted bone: its monsters (lower-case npc names) and its raw/potted/polished objs. */
    private class Bone(
        val name: String,
        val bit: Int,
        val monsters: Set<String>,
        val raw: ObjType,
        val potted: ObjType,
        val polished: ObjType,
    )

    companion object {
        const val STARTED = 1
        const val COMPLETE = 4

        private const val TALKED_FORTUNATO = 1 shl 8
        private const val XP = 500.0
        private const val BOIL_TICKS = 3
        private const val DROP_TICKS = 200

        private val BONES by lazy {
            listOf(
                Bone("Goblin", 1 shl 0, setOf("goblin"), RbObjs.goblin, RbObjs.goblin_pot, RbObjs.goblin_polished),
                Bone(
                    "Bear",
                    1 shl 1,
                    setOf("bear", "grizzly bear", "black bear"),
                    RbObjs.bear,
                    RbObjs.bear_pot,
                    RbObjs.bear_polished,
                ),
                Bone("Big Frog", 1 shl 2, setOf("big frog"), RbObjs.frog, RbObjs.frog_pot, RbObjs.frog_polished),
                Bone("Ram", 1 shl 3, setOf("ram"), RbObjs.ram, RbObjs.ram_pot, RbObjs.ram_polished),
                Bone("Unicorn", 1 shl 4, setOf("unicorn"), RbObjs.unicorn, RbObjs.unicorn_pot, RbObjs.unicorn_polished),
                Bone("Monkey", 1 shl 5, setOf("monkey"), RbObjs.monkey, RbObjs.monkey_pot, RbObjs.monkey_polished),
                Bone("Giant Rat", 1 shl 6, setOf("giant rat"), RbObjs.rat, RbObjs.rat_pot, RbObjs.rat_polished),
                Bone("Giant Bat", 1 shl 7, setOf("giant bat"), RbObjs.bat, RbObjs.bat_pot, RbObjs.bat_polished),
            )
        }

        val RAG_AND_BONE_MAN_I =
            QuestDef(
                name = "Rag and Bone Man I",
                varp = { RbVarps.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("500 Cooking XP", "500 Prayer XP"),
                scrollObj = { RbObjs.goblin_polished },
                listRow = "quest_ragandboneman1",
            )
    }
}

// Every cache ref Rag and Bone Man I uses (re-check after cache updates).

internal object RbNpcs : NpcReferences() {
    val odd_old_man = find("rag_odd_old_man")
    val fortunato = find("rag_wine_merchant")
}

internal object RbLocs : LocReferences() {
    /** The pot-boiler multiloc by the Odd Old Man and its stage children. */
    val boilers =
        listOf(
            find("rag_multi_potboiler"),
            find("rag_potboiler_nopot"),
            find("rag_potboiler_nologs"),
            find("rag_potboiler_with_pot"),
            find("rag_potboiler_onfire"),
            find("rag_potboiler_with_pot_boiled"),
        )
}

internal object RbObjs : ObjReferences() {
    val vinegar = find("rag_vinegar")
    val jug_empty = find("jug_empty")
    val pot_empty = find("pot_empty")
    val pot_vinegar = find("rag_pot_vinegar")
    val logs = find("logs")
    val tinderbox = find("tinderbox")
    val goblin = find("rag_goblin_bone")
    val goblin_pot = find("rag_pot_goblin_bone")
    val goblin_polished = find("rag_polished_goblin_bone")
    val bear = find("rag_bear_bone")
    val bear_pot = find("rag_pot_bear_bone")
    val bear_polished = find("rag_polished_bear_bone")
    val frog = find("rag_medium_frog_bone")
    val frog_pot = find("rag_pot_medium_frog_bone")
    val frog_polished = find("rag_polished_medium_frog_bone")
    val ram = find("rag_ram_bone")
    val ram_pot = find("rag_pot_ram_bone")
    val ram_polished = find("rag_polished_ram_bone")
    val unicorn = find("rag_unicorn_bone")
    val unicorn_pot = find("rag_pot_unicorn_bone")
    val unicorn_polished = find("rag_polished_unicorn_bone")
    val monkey = find("rag_monkey_bone")
    val monkey_pot = find("rag_pot_monkey_bone")
    val monkey_polished = find("rag_polished_monkey_bone")
    val rat = find("rag_giant_rat_bone")
    val rat_pot = find("rag_pot_giant_rat_bone")
    val rat_polished = find("rag_polished_giant_rat_bone")
    val bat = find("rag_giant_bat_bone")
    val bat_pot = find("rag_pot_giant_bat_bone")
    val bat_polished = find("rag_polished_giant_bat_bone")
}

internal object RbVarps : VarpReferences() {
    val quest = find("rag_quest")

    /** Server varp (65797): bits 0-7 the bones handed in (see [RagAndBoneManI]), 8 met Fortunato. */
    val progress = find("rb_progress")
}

internal object RbVarpBuilds : VarpBuilder() {
    init {
        build("rb_progress")
    }
}
