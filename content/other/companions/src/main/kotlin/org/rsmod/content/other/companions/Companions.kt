package org.rsmod.content.other.companions

import jakarta.inject.Inject
import org.rsmod.api.config.refs.mesanims
import org.rsmod.api.player.events.StatAdvanced
import org.rsmod.api.player.features.CompanionPerks
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.content.quests.doricsquest.DoricsQuest.Companion.DORICS_QUEST
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.stat.StatType
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Companions v1 (design: ~/code/rsmod-design-companions.md). A companion is a clone of a real
 * npc (same type, so same model/anims) that follows the player like a pet (npc PlayerFollow mode);
 * the real npc stays where it is. One at a time.
 * - Summon / dismiss: talk to the real npc ("Ask to accompany you" / "Leave"); talking to the
 *   follower itself also offers "Leave". The Talk-to is the [QuestTalk] default conversation, so
 *   quests keep priority (Doric's Quest runs until it is complete).
 * - Saved per character: the active companion, an affinity per companion and My Arm's storage.
 *   The follower leaves at logout and comes back at login.
 * - Affinity: +1 per [AFFINITY_MINUTES] minutes together; tiers 0/1/2 at [TIER_POINTS].
 * - Perks: Doric gives a 5/8/10% chance of an extra ore ([CompanionPerks], read by Mining);
 *   My Arm carries 4/7/10 stacks (use an item on him to store, talk to him to retrieve).
 * - Knocked out: if the follower disappears it is "knocked out" and returns after [KO_TICKS].
 * - Chat: overhead lines from `companion_lines.txt` on summon, dismiss, level-up, every few
 *   minutes (idle) and on entering a remark area ([CompanionAreas]).
 * Unlocks: Doric = Doric's Quest complete; My Arm = varbit `myarm` >= 320 (cache quest endstate;
 * the quest is not implemented yet, so `::varbit myarm 320` to test).
 * Not in v1: Zanik and the Slayer masters (need combat AI / Slayer reward shop), the Companions
 * page, faster smithing, boss-fight toggle.
 */
class Companions
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val statTypes: StatTypeList,
    private val npcRepo: NpcRepository,
    private val random: GameRandom,
) : PluginScript() {
    /** Follower per player uid. */
    private val followers = HashMap<Int, Npc>()
    /** Per-login state (counters, level snapshot) per player uid. */
    private val sessions = HashMap<Int, Session>()

    private val statNames by lazy {
        statTypes.values.associate { (it.internalName ?: "") to it.displayName }
    }

    private class Session {
        var ticksTogether = 0
        var idleIn = 0
        var lastArea: String? = null
        var koTicks = 0
        val levels = HashMap<String, Int>()
    }

    override fun ScriptContext.startup() {
        CompanionPerks.installExtraOre { doricOreChance(it) }
        with(QuestTalk) {
            default(npcTypes, CompanionNpcs.doric) { talk(CompanionKind.Doric, it) }
            default(npcTypes, CompanionNpcs.myarm) { talk(CompanionKind.MyArm, it) }
        }
        onOpNpcU(CompanionNpcs.myarm) { storeItem(it.npc, it.invSlot, it.objType) }
        onEvent<StatAdvanced> { checkLevelUp(player, stat) }
        onPlayerLogin { login(player) }
        onPlayerLogout { logout(player) }
        onPlayerSoftTimer(CompanionTimers.tick) { tick(player) }
    }

    /* Saved state */

    private fun active(player: Player): CompanionKind? =
        CompanionKind.entries.getOrNull(player.vars[CompanionVarps.active] - 1)

    private fun setActive(player: Player, companion: CompanionKind?) {
        VarPlayerIntMapSetter.set(player, CompanionVarps.active, (companion?.ordinal ?: -1) + 1)
    }

    private fun affinity(player: Player, companion: CompanionKind): Int =
        player.vars[CompanionVarps.affinity.getValue(companion)]

    private fun tier(player: Player, companion: CompanionKind): Int {
        val points = affinity(player, companion)
        return TIER_POINTS.count { points >= it }
    }

    private fun isUnlocked(player: Player, companion: CompanionKind): Boolean =
        when (companion) {
            CompanionKind.Doric -> player.isQuestComplete(DORICS_QUEST)
            CompanionKind.MyArm -> player.vars[CompanionVarBits.myarm] >= MY_ARM_COMPLETE
        }

    private fun doricOreChance(player: Player): Int {
        if (active(player) != CompanionKind.Doric || followers[player.uid.packed] == null) return 0
        return DORIC_ORE_CHANCE[tier(player, CompanionKind.Doric)]
    }

    /* Talk-to (the real npc or the follower) */

    private suspend fun ProtectedAccess.talk(companion: CompanionKind, npc: Npc) {
        val isFollower = followers[player.uid.packed] === npc
        if (!isUnlocked(player, companion)) {
            lockedChat(companion, npc)
            return
        }
        val out = active(player) == companion && followers[player.uid.packed] != null
        val choice =
            when (companion) {
                CompanionKind.Doric -> {
                    var picked = 0
                    startDialogue(npc) {
                        if (!isFollower) {
                            chatNpc(happy, "Hello traveller, how is your metalworking coming along?")
                        }
                        picked =
                            choice3(
                                if (out) "Leave." else "Ask to accompany you.",
                                if (out) LEAVE else ACCOMPANY,
                                "How are we getting along?",
                                AFFINITY,
                                "Never mind.",
                                0,
                            )
                    }
                    picked
                }
                CompanionKind.MyArm -> {
                    var picked = 0
                    startDialogue(npc) {
                        if (!isFollower) chatNpc(happy, "Yep, My Arm. Wot you want?")
                        picked =
                            choice4(
                                if (out) "Leave." else "Ask to accompany you.",
                                if (out) LEAVE else ACCOMPANY,
                                "Store items.",
                                STORE,
                                "Retrieve items.",
                                RETRIEVE,
                                "How are we getting along?",
                                AFFINITY,
                            )
                    }
                    picked
                }
            }
        when (choice) {
            ACCOMPANY -> summon(companion)
            LEAVE -> dismiss(companion)
            STORE -> storeHelp(npc)
            RETRIEVE -> retrieveAll(npc)
            AFFINITY -> affinityChat(companion, npc)
        }
    }

    private suspend fun ProtectedAccess.lockedChat(companion: CompanionKind, npc: Npc) {
        when (companion) {
            CompanionKind.Doric ->
                chatNpc(npc, mesanims.happy, "Hello traveller, feel free to use my anvils any time.")
            CompanionKind.MyArm -> chatNpc(npc, mesanims.neutral, "My Arm busy. Go 'way, diddy human.")
        }
    }

    private suspend fun ProtectedAccess.affinityChat(companion: CompanionKind, npc: Npc) {
        val text =
            when (tier(player, companion)) {
                0 -> "We've only just started out together. (Affinity: ${affinity(player, companion)})"
                1 -> "We're getting on well. (Affinity: ${affinity(player, companion)})"
                else -> "Best of friends! (Affinity: ${affinity(player, companion)})"
            }
        chatNpc(npc, mesanims.happy, text)
        when (companion) {
            CompanionKind.Doric ->
                mes("Doric's extra ore chance: ${DORIC_ORE_CHANCE[tier(player, companion)]}%.")
            CompanionKind.MyArm -> mes("My Arm can carry ${storeSlots(player)} stacks of items.")
        }
    }

    private fun ProtectedAccess.summon(companion: CompanionKind) {
        val current = active(player)
        if (current != null && current != companion) {
            removeFollower(player)
            mes("${current.display} heads home.")
        }
        setActive(player, companion)
        session(player).koTicks = 0
        session(player).idleIn = nextIdle()
        if (followers[player.uid.packed] == null) spawnFollower(player, companion)
        sayLine(player, companion, "summon")
        startTimer(player)
    }

    private fun ProtectedAccess.dismiss(companion: CompanionKind) {
        sayLine(player, companion, "dismiss")
        removeFollower(player)
        setActive(player, null)
        mes("${companion.display} heads home.")
    }

    /* Followers */

    private fun spawnFollower(player: Player, companion: CompanionKind) {
        val type = npcTypes[companion.npc()]
        val npc = Npc(type, player.coords)
        npcRepo.add(npc, FOLLOWER_LIFETIME)
        npc.resetMovement()
        npc.mode = NpcMode.PlayerFollow
        npc.facePlayer(player)
        followers[player.uid.packed] = npc
    }

    private fun removeFollower(player: Player) {
        val npc = followers.remove(player.uid.packed) ?: return
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
    }

    private fun session(player: Player): Session =
        sessions.getOrPut(player.uid.packed) { Session().also { snapshotLevels(player, it) } }

    private fun snapshotLevels(player: Player, session: Session) {
        for (stat in statTypes.values) {
            session.levels[stat.internalName ?: continue] = player.statBase(stat)
        }
    }

    private fun startTimer(player: Player) {
        player.softTimer(CompanionTimers.tick, TICK_INTERVAL)
    }

    private fun login(player: Player) {
        sessions.remove(player.uid.packed)
        val companion = active(player) ?: return
        if (!isUnlocked(player, companion)) {
            setActive(player, null)
            return
        }
        session(player).idleIn = nextIdle()
        if (followers[player.uid.packed] == null) spawnFollower(player, companion)
        startTimer(player)
    }

    private fun logout(player: Player) {
        removeFollower(player)
        sessions.remove(player.uid.packed)
    }

    /* Periodic: affinity, knock-out return, idle and area remarks */

    private fun tick(player: Player) {
        val companion = active(player)
        if (companion == null) {
            player.clearSoftTimer(CompanionTimers.tick)
            return
        }
        val session = session(player)
        val npc = followers[player.uid.packed]
        if (npc != null && !npc.isSlotAssigned) {
            // Knocked out (v1 companions don't fight: this is a lost / removed follower).
            followers.remove(player.uid.packed)
            sayMessage(player, companion, "ko")
            player.mes("${companion.display} has been knocked out! They'll be back in about 2 minutes.")
            session.koTicks = KO_TICKS
            return
        }
        if (npc == null) {
            session.koTicks -= TICK_INTERVAL
            if (session.koTicks <= 0) {
                spawnFollower(player, companion)
                sayLine(player, companion, "return")
            }
            return
        }
        session.ticksTogether += TICK_INTERVAL
        if (session.ticksTogether >= AFFINITY_MINUTES * TICKS_PER_MINUTE) {
            session.ticksTogether = 0
            gainAffinity(player, companion)
        }
        val area = CompanionAreas.find(companion, player.coords)
        if (area != null && area != session.lastArea) {
            sayLine(player, companion, "area:$area")
            session.idleIn = nextIdle()
        }
        session.lastArea = area
        session.idleIn -= TICK_INTERVAL
        if (session.idleIn <= 0) {
            session.idleIn = nextIdle()
            sayLine(player, companion, "idle")
        }
    }

    private fun gainAffinity(player: Player, companion: CompanionKind) {
        val varp = CompanionVarps.affinity.getValue(companion)
        val before = tier(player, companion)
        val points = player.vars[varp]
        if (points >= MAX_AFFINITY) return
        VarPlayerIntMapSetter.set(player, varp, points + 1)
        val after = tier(player, companion)
        if (after > before) {
            sayLine(player, companion, "tier")
            player.mes("Your affinity with ${companion.display} has grown (tier $after).")
        }
    }

    private fun nextIdle(): Int = random.of(IDLE_MIN_TICKS, IDLE_MAX_TICKS)

    /* Level-ups */

    private fun checkLevelUp(player: Player, stat: StatType) {
        val companion = active(player) ?: return
        if (followers[player.uid.packed] == null) return
        val key = stat.internalName ?: return
        val session = session(player)
        val level = player.statBase(stat)
        val before = session.levels.put(key, level) ?: return
        if (level > before) {
            sayLine(player, companion, "levelup", skill = statNames[key] ?: key)
        }
    }

    /* Chat */

    private fun pickLine(companion: CompanionKind, event: String, player: Player, skill: String): String? {
        val lines = CompanionLines.lines[companion.key to event] ?: return null
        if (lines.isEmpty()) return null
        return lines[random.of(lines.size)]
            .replace("<player>", player.displayName)
            .replace("<skill>", skill)
    }

    /** Overhead chat on the follower; a game message when it isn't out. */
    private fun sayLine(player: Player, companion: CompanionKind, event: String, skill: String = "") {
        val text = pickLine(companion, event, player, skill) ?: return
        val npc = followers[player.uid.packed]
        if (npc != null && npc.isSlotAssigned) npc.say(text) else player.mes("${companion.display}: $text")
    }

    private fun sayMessage(player: Player, companion: CompanionKind, event: String) {
        val text = pickLine(companion, event, player, "") ?: return
        player.mes("${companion.display}: $text")
    }

    /* My Arm storage: CompanionVarps.storeIds / storeCounts, id + 1 (0 = empty) and count. */

    private fun storeSlots(player: Player): Int = MY_ARM_SLOTS[tier(player, CompanionKind.MyArm)]

    private suspend fun ProtectedAccess.storeHelp(npc: Npc) {
        chatNpc(npc, mesanims.happy, "You give My Arm da stuffs, My Arm carry it. Just use it on me.")
        mes("Use an item on My Arm to give him the whole stack (${storeSlots(player)} stacks max).")
    }

    private suspend fun ProtectedAccess.storeItem(npc: Npc, invSlot: Int, type: UnpackedObjType) {
        if (!isUnlocked(player, CompanionKind.MyArm)) {
            chatNpc(npc, mesanims.neutral, "I not sure what you want me to do with dat.")
            return
        }
        val obj = inv[invSlot] ?: return
        if (obj.vars != 0) {
            chatNpc(npc, mesanims.confused, "My Arm not know how to carry dat one.")
            return
        }
        val count = invTotal(inv, type)
        val slots = storeSlots(player)
        val ids = CompanionVarps.storeIds
        val counts = CompanionVarps.storeCounts
        val empty = (0 until slots).filter { player.vars[ids[it]] == 0 }
        // Slots work like inventory slots: a stackable obj shares one, others take one each.
        val stack =
            if (type.isStackable) {
                (0 until slots).firstOrNull { player.vars[ids[it]] == type.id + 1 }
            } else {
                null
            }
        val add =
            when {
                type.isStackable -> {
                    val slot = stack ?: empty.firstOrNull()
                    val room = Int.MAX_VALUE.toLong() - (slot?.let { player.vars[counts[it]] } ?: 0)
                    if (slot == null) 0 else minOf(count.toLong(), room).toInt()
                }
                else -> minOf(count, empty.size)
            }
        if (add <= 0) {
            chatNpc(npc, mesanims.sad, "My Arm hands is full! Take some stuffs back first.")
            return
        }
        if (invDel(inv, type, add).failure) return
        if (type.isStackable) {
            val slot = stack ?: empty.first()
            VarPlayerIntMapSetter.set(player, ids[slot], type.id + 1)
            VarPlayerIntMapSetter.set(player, counts[slot], player.vars[counts[slot]] + add)
        } else {
            for (slot in empty.take(add)) {
                VarPlayerIntMapSetter.set(player, ids[slot], type.id + 1)
                VarPlayerIntMapSetter.set(player, counts[slot], 1)
            }
        }
        mes("You hand My Arm ${if (add == 1) "the" else "$add x"} ${type.name}.")
    }

    private suspend fun ProtectedAccess.retrieveAll(npc: Npc) {
        val ids = CompanionVarps.storeIds
        val counts = CompanionVarps.storeCounts
        var any = false
        var left = false
        for (slot in ids.indices) {
            val id = player.vars[ids[slot]] - 1
            if (id < 0) continue
            val type = objTypes.types[id]
            val count = player.vars[counts[slot]]
            if (type == null || count <= 0) {
                clearSlot(player, slot)
                continue
            }
            val take = if (type.isStackable) count else minOf(count, inv.freeSpace())
            if (take <= 0 || invAdd(inv, type, take).failure) {
                left = true
                continue
            }
            any = true
            if (take >= count) {
                clearSlot(player, slot)
            } else {
                VarPlayerIntMapSetter.set(player, counts[slot], count - take)
                left = true
            }
        }
        when {
            !any && !left -> chatNpc(npc, mesanims.neutral, "My Arm not carrying nuffing for you.")
            left -> {
                chatNpc(npc, mesanims.happy, "Here, take what you can carry.")
                mes("My Arm is still carrying some of your items.")
            }
            else -> chatNpc(npc, mesanims.happy, "Here you go. All your stuffs.")
        }
    }

    private fun clearSlot(player: Player, slot: Int) {
        VarPlayerIntMapSetter.set(player, CompanionVarps.storeIds[slot], 0)
        VarPlayerIntMapSetter.set(player, CompanionVarps.storeCounts[slot], 0)
    }

    private companion object {
        const val ACCOMPANY = 1
        const val LEAVE = 2
        const val STORE = 3
        const val RETRIEVE = 4
        const val AFFINITY = 5

        /** Varbit `myarm` value when My Arm's Big Adventure is complete (cache quest endstate). */
        const val MY_ARM_COMPLETE = 320

        const val TICKS_PER_MINUTE = 100
        /** Soft timer period (ticks). */
        const val TICK_INTERVAL = 10
        /** Minutes together per affinity point. */
        const val AFFINITY_MINUTES = 5
        const val MAX_AFFINITY = 1000
        /** Affinity points for tier 1 (1 hour together) and tier 2 (4 hours). */
        val TIER_POINTS = intArrayOf(12, 48)
        val DORIC_ORE_CHANCE = intArrayOf(5, 8, 10)
        val MY_ARM_SLOTS = intArrayOf(4, 7, 10)
        /** ~2 minutes knocked out. */
        const val KO_TICKS = 200
        const val IDLE_MIN_TICKS = 300
        const val IDLE_MAX_TICKS = 600
        /** Not Int.MAX_VALUE: that marks an npc as a respawning map spawn. */
        const val FOLLOWER_LIFETIME = Int.MAX_VALUE - 1
    }
}

internal enum class CompanionKind(val key: String, val display: String) {
    Doric("doric", "Doric"),
    MyArm("myarm", "My Arm");

    fun npc() =
        when (this) {
            Doric -> CompanionNpcs.doric
            MyArm -> CompanionNpcs.myarm
        }
}

internal object CompanionLines {
    /** (companion key, event) -> lines, from `companion_lines.txt`. */
    val lines: Map<Pair<String, String>, List<String>> by lazy {
        val input =
            CompanionLines::class.java.getResourceAsStream("companion_lines.txt")
                ?: error("Companions resource not found: companion_lines.txt")
        input.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
            .map { it.split('\t', limit = 3) }
            .filter { it.size == 3 }
            .groupBy({ it[0] to it[1] }, { it[2] })
    }
}

/** Remark areas: entering one makes the companion say its `area:<key>` line once. */
internal object CompanionAreas {
    private class Box(val key: String, val level: Int, val x1: Int, val z1: Int, val x2: Int, val z2: Int)

    private val doric =
        listOf(
            Box("doric_home", 0, 2945, 3445, 2958, 3457),
            Box("dwarven_mine", 0, 2980, 9790, 3070, 9860),
            Box("mine", 0, 3014, 9730, 3057, 9758), // Mining Guild
            Box("mine", 0, 2965, 3225, 2990, 3250), // Rimmington
            Box("mine", 0, 3280, 3360, 3295, 3375), // Varrock south-east
            Box("mine", 0, 3170, 3364, 3185, 3380), // Varrock south-west
            Box("mine", 0, 3290, 3275, 3310, 3320), // Al Kharid
            Box("smithy", 0, 3185, 3419, 3192, 3427), // Varrock west smithy
        )

    private val myArm =
        listOf(
            Box("trollheim", 0, 2800, 3580, 2950, 3760),
            Box("farm", 0, 3050, 3300, 3062, 3315), // Falador allotments
            Box("farm", 0, 2804, 3458, 2816, 3468), // Catherby
            Box("farm", 0, 2660, 3370, 2672, 3380), // Ardougne
            Box("farm", 0, 3597, 3521, 3606, 3530), // Morytania
            Box("sea", 0, 3026, 3190, 3050, 3220), // Port Sarim docks
        )

    fun find(companion: CompanionKind, coords: CoordGrid): String? {
        val boxes = if (companion == CompanionKind.Doric) doric else myArm
        return boxes
            .firstOrNull {
                coords.level == it.level &&
                    coords.x in it.x1..it.x2 &&
                    coords.z in it.z1..it.z2
            }
            ?.key
    }
}
