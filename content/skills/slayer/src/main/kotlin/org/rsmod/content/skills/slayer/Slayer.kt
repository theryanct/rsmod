package org.rsmod.content.skills.slayer

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.npc.queueCombatRetaliate
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.features.slayerPointMultiplier
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseSlayerLvl
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onNpcHit
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.slayer.SlayerData.Master
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Slayer (PR1: Turael and Mazchna), data from the OSRS Wiki (`slayer.tsv`, tools/slayer):
 * - Masters' "Talk-to"/"Assignment" ops give a task by wiki weight (amount from the wiki range),
 *   respecting the task's Slayer/Combat level requirements and the master's combat requirement.
 * - Kills of any npc in the task's category (credited to the hero via [NpcKilledEvent]) count
 *   down the task and give the npc's wiki slayer xp.
 * - Finishing a task gives the master's points (with streak milestones); Turael gives none and
 *   doesn't touch the streak.
 * - The enchanted gem's "Check" op reports the current task.
 *
 * Slayer level requirements to damage monsters are enforced by PvN combat (`slayer_levelrequire`).
 */
class Slayer
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
    private val npcRepo: NpcRepository,
    private val random: GameRandom,
    private val mapClock: MapClock,
) : PluginScript() {
    private var Player.slayerTask: Int by intVarp(SlayerVarps.task)
    private var Player.slayerTaskCount: Int by intVarp(SlayerVarps.count)
    private var Player.slayerPoints: Int by intVarp(SlayerVarps.points)
    private var Player.slayerStreak: Int by intVarp(SlayerVarps.streak)
    private var Player.slayerTaskMaster: Int by intVarp(SlayerVarps.master)
    private var Player.slayerGemGiven: Int by intVarp(SlayerVarps.gem_given)

    private val categoriesById by lazy { SlayerData.categories.associateBy { it.id } }
    private val mastersById by lazy { SlayerData.masters.associateBy { it.id } }
    private val categoryNpcIds by lazy {
        SlayerData.categories.associate { category ->
            category.id to category.npcs.mapNotNull { SlayerNpcs.byName[it]?.id }.toSet()
        }
    }
    private val xpByNpcId by lazy {
        SlayerData.xp.mapNotNull { (name, xp) -> SlayerNpcs.byName[name]?.let { it.id to xp } }.toMap()
    }

    override fun ScriptContext.startup() {
        for (master in SlayerData.masters) {
            val npc = SlayerNpcs.byName.getValue(master.npc)
            val ops = npcTypes[npc].op
            val talk = ops.indexOfFirst { it.equals("Talk-to", ignoreCase = true) } + 1
            val assign = ops.indexOfFirst { it.equals("Assignment", ignoreCase = true) } + 1
            onNpcOp(npc, talk) { talkTo(it, master) }
            onNpcOp(npc, assign) { assignment(it, master, greet = false) }
        }
        val gem = SlayerObjs.enchanted_gem
        when (objTypes[gem].iop.indexOfFirst { it.equals("Check", ignoreCase = true) } + 1) {
            1 -> if (!isHeldOpBound(gem, 1)) onOpHeld1(gem) { checkTask() }
            2 -> if (!isHeldOpBound(gem, 2)) onOpHeld2(gem) { checkTask() }
            3 -> if (!isHeldOpBound(gem, 3)) onOpHeld3(gem) { checkTask() }
            4 -> if (!isHeldOpBound(gem, 4)) onOpHeld4(gem) { checkTask() }
            5 -> if (!isHeldOpBound(gem, 5)) onOpHeld5(gem) { checkTask() }
        }
        onEvent<NpcKilledEvent> { onKill(hero, npc) }
        // Wiki: a superior only leaves after two minutes of being ignored, so each hit restarts
        // its lifetime. (The marble gargoyle's hits are handled by [Gargoyles].)
        for (name in SlayerData.superiors.map { it.superior }.distinct()) {
            if (name == "superior_gargoyle") continue
            onNpcHit(SlayerSuperiorNpcs.byName.getValue(name)) { refreshSuperiorLife(npc, mapClock) }
        }
    }

    private fun ScriptContext.onNpcOp(
        npc: NpcType,
        op: Int,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        when (op) {
            1 -> if (!isNpcOpBound(npc, 1)) onOpNpc1(npc) { this.action(it.npc) }
            2 -> if (!isNpcOpBound(npc, 2)) onOpNpc2(npc) { this.action(it.npc) }
            3 -> if (!isNpcOpBound(npc, 3)) onOpNpc3(npc) { this.action(it.npc) }
            4 -> if (!isNpcOpBound(npc, 4)) onOpNpc4(npc) { this.action(it.npc) }
            5 -> if (!isNpcOpBound(npc, 5)) onOpNpc5(npc) { this.action(it.npc) }
        }
    }

    private suspend fun ProtectedAccess.talkTo(npc: Npc, master: Master) {
        var choice = 0
        startDialogue(npc) {
            chatNpc(happy, "'Ello, and what are you after, then?")
            choice =
                choice4(
                    "I need another assignment.",
                    1,
                    "Do you have a spare enchanted gem?",
                    2,
                    "I'd like to unblock a task.",
                    4,
                    "Nothing, thanks.",
                    3,
                )
        }
        when (choice) {
            1 -> assignment(npc, master, greet = false)
            2 -> spareGem(npc)
            4 -> unblockTask(npc)
        }
    }

    /**
     * Unblock (OSRS: free, no points refunded). The rewards interface's own blocked list reads
     * client varbits we don't mirror yet, so unblocking is offered through the master instead.
     */
    private suspend fun ProtectedAccess.unblockTask(npc: Npc) {
        val blocked = SlayerBlockVarps.blocks.filter { vars[it] != 0 }
        if (blocked.isEmpty()) {
            startDialogue(npc) { chatNpc(neutral, "You haven't blocked any tasks.") }
            return
        }
        val names =
            blocked.map { slot ->
                categoriesById[vars[slot]]?.display ?: "Unknown task"
            }
        val picked = menu("Unblock which task?", hotkeys = true, choices = names + "Cancel")
        val slot = blocked.getOrNull(picked) ?: return
        val name = names[picked]
        vars[slot] = 0
        startDialogue(npc) {
            chatNpc(happy, "Fine. You may be assigned ${name.lowercase()} again.")
        }
    }

    /** OSRS: masters hand out a replacement gem for free when you have none. */
    private suspend fun ProtectedAccess.spareGem(npc: Npc) {
        val gem = SlayerObjs.enchanted_gem
        if (invTotal(inv, gem) > 0 || invTotal(bank, gem) > 0) {
            startDialogue(npc) { chatNpc(neutral, "You've already got one of those.") }
            return
        }
        invAddOrDrop(objRepo, gem)
        startDialogue(npc) { chatNpc(happy, "Here you go. Try not to lose it this time.") }
    }

    private suspend fun ProtectedAccess.assignment(npc: Npc, master: Master, greet: Boolean) {
        val current = categoriesById[player.slayerTask]
        if (current != null && player.slayerTaskCount > 0) {
            val count = player.slayerTaskCount
            startDialogue(npc) {
                chatNpc(
                    neutral,
                    "You're still hunting ${current.display.lowercase()}; you have $count to go. " +
                        "Come back when you've finished your task.",
                )
            }
            return
        }
        if (player.combatLevel < master.combatReq) {
            startDialogue(npc) {
                chatNpc(
                    neutral,
                    "You're too weak for my tasks. Come back when you have a combat level of " +
                        "${master.combatReq}.",
                )
            }
            return
        }
        if (player.baseSlayerLvl < master.slayerReq) {
            startDialogue(npc) {
                chatNpc(
                    neutral,
                    "You need a Slayer level of ${master.slayerReq} before I'll give you tasks.",
                )
            }
            return
        }
        val eligible =
            SlayerData.tasks.filter {
                it.master == master.key &&
                    player.baseSlayerLvl >= it.slayerReq &&
                    player.combatLevel >= it.combatReq &&
                    !player.isSlayerTaskBlocked(SlayerData.stableId(it.category))
            }
        if (eligible.isEmpty()) {
            startDialogue(npc) { chatNpc(neutral, "I don't have anything for you right now.") }
            return
        }
        val total = eligible.sumOf { it.weight }
        var roll = random.of(TASK_ROLL) * total / TASK_ROLL
        var task = eligible.last()
        for (candidate in eligible) {
            roll -= candidate.weight
            if (roll < 0) {
                task = candidate
                break
            }
        }
        val category = categoriesById.getValue(SlayerData.stableId(task.category))
        // A bought "longer" unlock for this category uses the Wiki's extended amount.
        val extMin = task.extMin
        val extMax = task.extMax
        val amount =
            if (extMin != null && extMax != null && player.hasExtendedTask(task.category)) {
                random.of(extMin, extMax)
            } else {
                random.of(task.min, task.max)
            }
        player.slayerTask = category.id
        player.slayerTaskCount = amount
        player.slayerTaskMaster = master.id
        val firstGem = player.slayerGemGiven == 0
        if (firstGem) {
            player.slayerGemGiven = 1
            invAddOrDrop(objRepo, SlayerObjs.enchanted_gem)
        }
        startDialogue(npc) {
            if (greet) {
                chatNpc(happy, "'Ello, and what are you after, then?")
            }
            chatNpc(happy, "Your new task is to kill $amount ${category.display.lowercase()}.")
            if (firstGem) {
                chatNpc(
                    happy,
                    "Here's a gem. Use it to check on your task or contact me if you need help.",
                )
            }
        }
    }

    private fun ProtectedAccess.checkTask() {
        val current = categoriesById[player.slayerTask]
        val count = player.slayerTaskCount
        if (current == null || count <= 0) {
            mes("You need something new to hunt.")
            return
        }
        mes("You're assigned to kill ${current.display.lowercase()}; only $count more to go.")
        mes("You have ${player.slayerPoints} Slayer reward points.")
    }

    /** Superior npc id -> its row; normal npc id -> the superior npc type it can turn into. */
    private val superiorsById by lazy {
        SlayerData.superiors.associateBy { SlayerSuperiorNpcs.byName.getValue(it.superior).id }
    }
    private val superiorByNormalId by lazy {
        SlayerData.superiors
            .flatMap { s -> s.normals.map { SlayerSuperiorNpcs.byName.getValue(it).id to s } }
            .toMap()
    }

    private fun onKill(hero: Player, npc: Npc) {
        val count = hero.slayerTaskCount
        if (count <= 0) {
            return
        }
        val npcs = categoryNpcIds[hero.slayerTask] ?: return
        val superior = superiorsById[npc.id]
        // A superior is on task whenever one of the monsters it replaces is (some, e.g. araxytes,
        // sit in more than one category).
        val onTask =
            npc.id in npcs ||
                (superior != null &&
                    superior.normals.any { SlayerSuperiorNpcs.byName.getValue(it).id in npcs })
        if (!onTask) {
            return
        }
        if (superior == null) {
            maybeSpawnSuperior(hero, npc)
        }
        // Superiors give their own Wiki Slayer xp and count as one task kill.
        val xp = superior?.xp ?: (xpByNpcId[npc.id] ?: 0.0)
        if (xp > 0) {
            hero.statAdvance(stats.slayer, xp * xpMods.get(hero, stats.slayer))
        }
        hero.slayerTaskCount = count - 1
        if (count - 1 > 0) {
            return
        }
        completeTask(hero)
    }

    /**
     * Bigger and Badder (slayer_unlock bit 35): an on-task kill has a 1/200 chance to summon the
     * monster's superior variant (Wiki), which attacks the player and leaves after two minutes.
     */
    private fun maybeSpawnSuperior(hero: Player, npc: Npc) {
        if (!hero.hasSlayerUnlock(BIGGER_AND_BADDER_BIT)) {
            return
        }
        val superior = superiorByNormalId[npc.id] ?: return
        if (random.of(1, SUPERIOR_CHANCE) != 1) {
            return
        }
        val type = npcTypes[SlayerSuperiorNpcs.byName.getValue(superior.superior)]
        val spawned = Npc(type, npc.coords)
        npcRepo.add(spawned, SUPERIOR_LIFE)
        spawned.queueCombatRetaliate(hero)
        hero.mes("A superior foe has appeared...")
    }

    private fun completeTask(hero: Player) {
        val master = mastersById[hero.slayerTaskMaster]
        hero.slayerTask = 0
        hero.slayerTaskMaster = 0
        if (master == null || master.points <= 0) {
            hero.mes("You have completed your task! Return to a Slayer master for a new one.")
            return
        }
        val streak = hero.slayerStreak + 1
        hero.slayerStreak = streak
        val basePoints =
            master.milestones.filter { streak % it.first == 0 }.maxByOrNull { it.first }?.second
                ?: master.points
        // ::features Slayer point multiplier (whole number, so no rounding is needed).
        val points = basePoints * hero.slayerPointMultiplier
        hero.slayerPoints = hero.slayerPoints + points
        hero.syncSlayerPointsDisplay()
        hero.mes(
            "You've completed $streak tasks in a row and received $points points, giving you a " +
                "total of ${hero.slayerPoints}; return to a Slayer master."
        )
    }

    internal companion object {
        const val TASK_ROLL = 1_000_000
        /** slayer_unlock "Bigger and Badder". */
        const val BIGGER_AND_BADDER_BIT = 35
        /** Wiki: 1/200 per on-task kill with Bigger and Badder unlocked. */
        const val SUPERIOR_CHANCE = 200
        /** Superiors leave after two minutes. */
        const val SUPERIOR_LIFE = 200
    }
}

/** Restart a summoned superior's despawn timer (it was hit, so it isn't being ignored). */
internal fun refreshSuperiorLife(npc: Npc, mapClock: MapClock) {
    if (npc.lifecycleDelCycle > 0) {
        npc.lifecycleDelCycle = mapClock.cycle + Slayer.SUPERIOR_LIFE
    }
}
