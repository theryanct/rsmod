package org.rsmod.content.skills.fishing.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fishingLvl
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.fishing.configs.fishing_objs
import org.rsmod.content.skills.fishing.configs.fishing_seqs
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.seq.SeqType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

// TODO (fishing PR 2): big net, harpoon, lobster pot, lure/fly fishing, fishing spot movement,
//  angler outfit, clue bottles, heron pet.
class Fishing
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val mapClock: MapClock,
) : PluginScript() {
    override fun ScriptContext.startup() {
        // Fishing spots all share the "Fishing spot" name, but each location uses its own npc
        // type and the op slot for a given method differs between types. Discover them from the
        // cache so every net/bait spot in the world is covered without hand-listing type ids.
        for (type in npcTypes.values) {
            if (!type.name.equals("Fishing spot", ignoreCase = true)) {
                continue
            }
            for ((index, text) in type.op.withIndex()) {
                val method = FishingMethod.fromOp(text) ?: continue
                registerOp(type, index + 1, method)
            }
        }
    }

    private fun ScriptContext.registerOp(type: UnpackedNpcType, op: Int, method: FishingMethod) {
        when (op) {
            1 -> onOpNpc1(type) { fish(it.npc, op, method) }
            2 -> onOpNpc2(type) { fish(it.npc, op, method) }
            3 -> onOpNpc3(type) { fish(it.npc, op, method) }
            4 -> onOpNpc4(type) { fish(it.npc, op, method) }
            // ProtectedAccess has no opNpc5 to re-trigger the loop; net/bait never sit on op5.
        }
    }

    private fun ProtectedAccess.fish(spot: Npc, op: Int, method: FishingMethod) {
        if (!canFish(method)) {
            resetAnim()
            return
        }

        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 5
            anim(method.anim)
        }

        if (actionDelay < mapClock) {
            // Fresh attempt, or the tick right after a roll. Rolls happen every 5 ticks.
            if (actionDelay < mapClock - 1) {
                spam(method.startMessage)
            }
            actionDelay = mapClock + 4
        } else if (actionDelay == mapClock) {
            rollCatch(method)
            if (!canFish(method)) {
                resetAnim()
                return
            }
        }

        reop(spot, op)
    }

    private fun ProtectedAccess.canFish(method: FishingMethod): Boolean {
        val lowest = method.catches.minOf { it.level }
        if (player.fishingLvl < lowest) {
            mes("You need a Fishing level of at least $lowest to ${method.verb} these fish.")
            return false
        }
        if (method.tool !in inv) {
            mes(method.missingToolMessage)
            return false
        }
        val bait = method.bait
        if (bait != null && bait !in inv) {
            mes(method.missingBaitMessage)
            return false
        }
        if (inv.isFull()) {
            mes("You can't carry any more fish.")
            soundSynth(synths.pillory_wrong)
            return false
        }
        return true
    }

    private fun ProtectedAccess.rollCatch(method: FishingMethod) {
        // Catches cascade: the highest-level fish the player can catch is rolled first, then the
        // next one down, each with its own OSRS Wiki skilling-success curve.
        for (catch in method.catches) {
            if (player.fishingLvl < catch.level) {
                continue
            }
            if (!statRandom(stats.fishing, catch.low, catch.high, invisibleLvls)) {
                continue
            }
            val bait = method.bait
            if (bait != null) {
                invDel(inv, bait, count = 1)
            }
            val xp = catch.xp * xpMods.get(player, stats.fishing)
            spam(catch.message)
            statAdvance(stats.fishing, xp)
            invAdd(inv, objTypes[catch.fish])
            return
        }
    }

    private fun ProtectedAccess.reop(spot: Npc, op: Int) {
        when (op) {
            1 -> opNpc1(spot)
            2 -> opNpc2(spot)
            3 -> opNpc3(spot)
            4 -> opNpc4(spot)
        }
    }

    private class Catch(
        val fish: ObjType,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        val message: String,
    )

    private enum class FishingMethod(
        val ops: Set<String>,
        val verb: String,
        val tool: ObjType,
        val bait: ObjType?,
        val anim: SeqType,
        val startMessage: String,
        val missingToolMessage: String,
        val missingBaitMessage: String,
        /** Highest level first: rolled in this order. */
        val catches: List<Catch>,
    ) {
        SmallNet(
            ops = setOf("net", "small net"),
            verb = "net",
            tool = fishing_objs.net,
            bait = null,
            anim = fishing_seqs.human_smallnet,
            startMessage = "You cast out your net...",
            missingToolMessage = "You need a small fishing net to catch these fish.",
            missingBaitMessage = "",
            catches =
                listOf(
                    Catch(fishing_objs.raw_anchovies, 15, 40.0, 24, 128, "You catch some anchovies."),
                    Catch(fishing_objs.raw_shrimps, 1, 10.0, 48, 256, "You catch some shrimps."),
                ),
        ),
        Bait(
            ops = setOf("bait"),
            verb = "bait",
            tool = fishing_objs.fishing_rod,
            bait = fishing_objs.fishing_bait,
            anim = fishing_seqs.human_fishing_casting,
            startMessage = "You cast out your line...",
            missingToolMessage = "You need a fishing rod to catch these fish.",
            missingBaitMessage = "You don't have any bait left.",
            catches =
                listOf(
                    Catch(fishing_objs.raw_herring, 10, 30.0, 24, 128, "You catch a herring."),
                    Catch(fishing_objs.raw_sardine, 5, 20.0, 32, 192, "You catch a sardine."),
                ),
        );

        companion object {
            fun fromOp(text: String?): FishingMethod? {
                val normalized = text?.trim()?.lowercase() ?: return null
                return entries.firstOrNull { normalized in it.ops }
            }
        }
    }
}
