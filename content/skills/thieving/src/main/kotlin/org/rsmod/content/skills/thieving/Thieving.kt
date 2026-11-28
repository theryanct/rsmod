package org.rsmod.content.skills.thieving

import jakarta.inject.Inject
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.spotanims
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.thievingLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.thieving.ThievingData.Loot
import org.rsmod.content.skills.thieving.ThievingData.Pickpocket
import org.rsmod.content.skills.thieving.ThievingData.Stall
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Thieving from OSRS Wiki data:
 * - **Pickpocketing**: success uses the wiki's low/high values with `statRandom`; success gives
 *   xp and one weighted loot roll (plus Always lines); failure makes the npc shout, hits for the
 *   wiki damage and stuns the player for the wiki stun time.
 * - **Stalls**: no failure roll (guard aggro is not modelled yet); a theft gives xp and a weighted
 *   loot roll, then the stall turns into its region's empty stall for the respawn time, or - when
 *   there is no empty variant - simply can't be stolen from until then.
 *
 * The op slot is whichever slot the npc/loc type labels "Pickpocket" / "Steal-from".
 */
class Thieving
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    /** Stall coords -> map clock cycle at which it can be stolen from again (no empty variant). */
    private val stallCooldowns = HashMap<CoordGrid, Int>()

    override fun ScriptContext.startup() {
        val registeredNpcs = HashSet<Int>()
        for (pickpocket in ThievingData.pickpockets) {
            for (name in pickpocket.npcs) {
                val npc = ThievingNpcs.byName.getValue(name)
                if (!registeredNpcs.add(npc.id)) continue
                val op = npcTypes[npc].op.indexOfFirst { it.equals("Pickpocket", ignoreCase = true) }
                when (op + 1) {
                    1 -> onOpNpc1(npc) { pickpocket(it.npc, pickpocket) }
                    2 -> onOpNpc2(npc) { pickpocket(it.npc, pickpocket) }
                    3 -> onOpNpc3(npc) { pickpocket(it.npc, pickpocket) }
                    4 -> onOpNpc4(npc) { pickpocket(it.npc, pickpocket) }
                    5 -> onOpNpc5(npc) { pickpocket(it.npc, pickpocket) }
                }
            }
        }
        val registeredLocs = HashSet<Int>()
        for (stall in ThievingData.stalls) {
            for ((index, name) in stall.locs.withIndex()) {
                val loc = ThievingLocs.byName.getValue(name)
                if (!registeredLocs.add(loc.id)) continue
                val empty = stall.empties.getOrNull(index)?.let { ThievingLocs.byName.getValue(it) }
                val op = locTypes[loc].op.indexOfFirst { it.equals("Steal-from", ignoreCase = true) }
                when (op + 1) {
                    1 -> onOpLoc1(loc) { steal(it.loc, stall, empty) }
                    2 -> onOpLoc2(loc) { steal(it.loc, stall, empty) }
                    3 -> onOpLoc3(loc) { steal(it.loc, stall, empty) }
                    4 -> onOpLoc4(loc) { steal(it.loc, stall, empty) }
                    5 -> onOpLoc5(loc) { steal(it.loc, stall, empty) }
                }
            }
        }
    }

    private suspend fun ProtectedAccess.pickpocket(npc: Npc, pickpocket: Pickpocket) {
        val name = npc.name.lowercase()
        if (player.thievingLvl < pickpocket.level) {
            mes("You need a Thieving level of ${pickpocket.level} to pick the $name's pocket.")
            return
        }
        if (inv.freeSpace() < 1) {
            mes("You don't have enough inventory space.")
            return
        }
        mes("You attempt to pick the $name's pocket.")
        anim(ThievingSeqs.pickpocket)
        delay(2)
        if (statRandom(stats.thieving, pickpocket.low, pickpocket.high, 0)) {
            mes("You pick the $name's pocket.")
            giveLoot(pickpocket.loot)
            statAdvance(stats.thieving, pickpocket.xp * xpMods.get(player, stats.thieving))
            return
        }
        npc.say("What do you think you're doing?")
        mes("You fail to pick the $name's pocket.")
        queueHit(npc, 1, HitType.Typeless, pickpocket.damage)
        spotanim(spotanims.stunned, height = 100)
        mes("You have been stunned!")
        delay(pickpocket.stunTicks)
    }

    private suspend fun ProtectedAccess.steal(loc: BoundLocInfo, stall: Stall, empty: LocType?) {
        if (player.thievingLvl < stall.level) {
            mes("You need a Thieving level of ${stall.level} to steal from this stall.")
            return
        }
        val readyAt = stallCooldowns[loc.coords]
        if (readyAt != null && mapClock < readyAt) {
            mes("There's nothing left to steal right now.")
            return
        }
        if (inv.freeSpace() < 1) {
            mes("You don't have enough inventory space.")
            return
        }
        anim(seqs.human_pickuptable)
        delay(1)
        giveLoot(stall.loot)
        statAdvance(stats.thieving, stall.xp * xpMods.get(player, stats.thieving))
        mes("You steal from the ${stall.title.lowercase()}.")
        if (empty != null) {
            locRepo.change(loc, empty, stall.respawnTicks)
        } else {
            // ProtectedAccess.mapClock is the current map clock cycle (an Int).
            stallCooldowns[loc.coords] = mapClock + stall.respawnTicks
        }
    }

    /** Always lines are always given; the rest is one weighted roll (remainder = nothing). */
    private fun ProtectedAccess.giveLoot(loot: List<Loot>) {
        for (entry in loot) {
            if (entry.always) {
                give(entry)
            }
        }
        val weighted = loot.filter { !it.always }
        if (weighted.isEmpty()) {
            return
        }
        val roll = random.of(PPM)
        var cumulative = 0L
        for (entry in weighted) {
            cumulative += entry.numerator.toLong() * PPM / entry.denominator
            if (roll < cumulative) {
                give(entry)
                return
            }
        }
    }

    private fun ProtectedAccess.give(entry: Loot) {
        val obj = ThievingObjs.byName.getValue(entry.obj)
        val count = if (entry.max > entry.min) random.of(entry.min, entry.max) else entry.min
        invAddGathered(obj, count)
    }

    private companion object {
        const val PPM = 1_000_000
    }
}
