package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.content.skills.hunter.HunterData.Creature
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Wasp swarms at Bea's farm (The Birds and the Wasps; OSRS Wiki "Swarm of wasps" / "Swarm of
 * killer wasps"; data in `hunter.tsv` as `swarm` rows).
 *
 * Each swarm is a multinpc on its own cache varbit (`baw_wasp_1_state` ...): 0 hidden, 1 the
 * catchable swarm, 2 the furious one. The quest sets the wasps to 1 when they move in and the killer
 * wasps when they arrive, so visibility doubles as the quest gate.
 * - "Catch" (Hunter 1 / 12) needs an insect net (or magic insect net) wielded and a bee hunter's
 *   hat or beekeeper's hat; without the hat the wasps sting you. One sting per tick while the
 *   swarm stays in reach, xp alternating 1/2 (2/3 for killer wasps).
 * - After some catches the swarm turns furious and catching stops; "Spray" it (or use insect
 *   repellent on it) to calm it back down. The repellent isn't used up.
 *
 * Simplified: the swarm doesn't give the Wiki's double catch when it stops moving; catching stops
 * when the swarm wanders more than [REACH] tiles away (click it again).
 */
class Wasps
@Inject
constructor(private val npcTypes: NpcTypeList, private val xpMods: XpModifiers) : PluginScript() {
    /** Catches since the swarm was last calmed, per player (xp alternation and the enrage roll). */
    private val catches = HashMap<Int, Int>()
    private val enrageAt = HashMap<Int, Int>()

    private val stateByMulti: Map<Int, VarBitType> by lazy {
        mapOf(
            WaspNpcs.wasp_1.id to BawVarBits.wasp_1_state,
            WaspNpcs.wasp_2.id to BawVarBits.wasp_2_state,
            WaspNpcs.wasp_3.id to BawVarBits.wasp_3_state,
            WaspNpcs.killer_wasp_1.id to BawVarBits.killer_wasp_1_state,
            WaspNpcs.killer_wasp_2.id to BawVarBits.killer_wasp_2_state,
            WaspNpcs.killer_wasp_3.id to BawVarBits.killer_wasp_3_state,
        )
    }

    override fun ScriptContext.startup() {
        for (creature in HunterData.creatures.filter { it.kind == HunterData.Kind.Swarm }) {
            val calm = HunterNpcs.byName.getValue(creature.npcs[0])
            val furious = HunterNpcs.byName.getValue(creature.npcs[1])
            bindOp(calm, "Catch") { catchWasps(it, creature) }
            bindOp(furious, "Spray") { spray(it) }
            onOpNpcU(furious, BawObjs.insect_repellent) { spray(it.npc) }
        }
    }

    private fun ScriptContext.bindOp(
        npc: NpcType,
        label: String,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        when (val op = npcTypes[npc].op.indexOfFirst { it.equals(label, ignoreCase = true) } + 1) {
            1 -> if (!isNpcOpBound(npc, op)) onOpNpc1(npc) { action(it.npc) }
            2 -> if (!isNpcOpBound(npc, op)) onOpNpc2(npc) { action(it.npc) }
            3 -> if (!isNpcOpBound(npc, op)) onOpNpc3(npc) { action(it.npc) }
            4 -> if (!isNpcOpBound(npc, op)) onOpNpc4(npc) { action(it.npc) }
            5 -> if (!isNpcOpBound(npc, op)) onOpNpc5(npc) { action(it.npc) }
        }
    }

    private suspend fun ProtectedAccess.catchWasps(npc: Npc, creature: Creature) {
        val state = stateByMulti[npc.id] ?: return
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to catch ${creature.title.lowercase()}.")
            return
        }
        if (!wieldingNet()) {
            mes("You need to be holding an insect net to catch these wasps.")
            return
        }
        if (!wearingHat()) {
            queueHit(1, HitType.Typeless, 1)
            mes("The wasps sting you in the face. Some sort of protective hat is probably in order.")
            return
        }
        val sting = HunterObjs.byName.getValue(creature.loot.first().obj)
        val key = player.uid.packed
        while (true) {
            if (player.vars[state] != CALM) return
            if (player.coords.chebyshevDistance(npc.coords) > REACH) return
            if (invTotal(inv, sting) == 0 && inv.freeSpace() == 0) {
                mes("You need more inventory space to catch these wasps.")
                return
            }
            val count = catches.getOrDefault(key, 0)
            if (count % SWING_EVERY == 0) anim(HunterSeqs.net_swing)
            delay(1)
            if (player.vars[state] != CALM) return
            invAdd(inv, sting, 1)
            val xp = if (count % 2 == 0) floor(creature.xp) else ceil(creature.xp)
            statAdvance(stats.hunter, xp * xpMods.get(player, stats.hunter))
            catches[key] = count + 1
            with(BawProgress) { waspCaught() }
            val limit = enrageAt.getOrPut(key) { random.of(ENRAGE_MIN, ENRAGE_MAX) }
            if (count + 1 >= limit) {
                VarPlayerIntMapSetter.set(player, state, FURIOUS)
                catches.remove(key)
                enrageAt.remove(key)
                mes("The wasps become enraged with you!")
                return
            }
        }
    }

    private fun ProtectedAccess.spray(npc: Npc) {
        val state = stateByMulti[npc.id] ?: return
        if (invTotal(inv, BawObjs.insect_repellent) <= 0) {
            mes("You need to have some insect repellent to spray these wasps.")
            return
        }
        anim(WaspSeqs.spray)
        VarPlayerIntMapSetter.set(player, state, CALM)
        mes("You calm the wasps down a bit by spraying them with insect repellent.")
    }

    private fun ProtectedAccess.wieldingNet(): Boolean =
        invTotal(worn, HunterObjs.butterfly_net) > 0 || invTotal(worn, BawObjs.magic_net) > 0

    private fun ProtectedAccess.wearingHat(): Boolean =
        invTotal(worn, BawObjs.bee_hunter_hat) > 0 || invTotal(worn, BawObjs.beekeeper_hat) > 0

    private companion object {
        const val CALM = 1
        const val FURIOUS = 2
        const val REACH = 2
        const val SWING_EVERY = 4
        const val ENRAGE_MIN = 10
        const val ENRAGE_MAX = 20
    }
}

/** The spawned swarm multinpcs (content/quests npcs.toml), each on its own state varbit. */
internal object WaspNpcs : NpcReferences() {
    val wasp_1 = find("baw_wasp_1_multi")
    val wasp_2 = find("baw_wasp_2_multi")
    val wasp_3 = find("baw_wasp_3_multi")
    val killer_wasp_1 = find("baw_killer_wasp_1_multi")
    val killer_wasp_2 = find("baw_killer_wasp_2_multi")
    val killer_wasp_3 = find("baw_killer_wasp_3_multi")
}

internal object WaspSeqs : SeqReferences() {
    val spray = find("human_use_insect_repellant")
}
