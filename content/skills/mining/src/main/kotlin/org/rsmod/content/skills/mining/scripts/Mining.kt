package org.rsmod.content.skills.mining.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.locParam
import org.rsmod.api.config.locXpParam
import org.rsmod.api.config.objParam
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.features.Feature
import org.rsmod.api.player.features.isFeatureOn
import org.rsmod.api.player.isInCombat
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.content.skills.mining.configs.mining_content
import org.rsmod.content.skills.mining.configs.mining_objs
import org.rsmod.content.skills.mining.configs.mining_params
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.obj.isType
import org.rsmod.game.type.seq.SeqType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mining for the F2P rocks configured in `MiningRocks`.
 *
 * As in OSRS, the pickaxe sets how often a roll happens (ticks between rolls) and the Mining level
 * sets the chance of each roll succeeding. Every success yields one ore and depletes the rock to
 * its empty variant for the rock's respawn time. Before each ore roll there is a 1/256 roll on the
 * mining gem table; if it hits, the attempt gives a gem (or nothing) instead of an ore, with no xp
 * and no depletion (OSRS Wiki, Template:MiningPreGemDropTable).
 */
// TODO:
// - charged amulet of glory gem bonus (extra 3/256 roll, 1/64.2 overall)
// - members rocks (silver/gold/mithril/adamantite/runite, gem rocks)
class Mining
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val locTypes: LocTypeList,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val mapClock: MapClock,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLoc1(mining_content.rock) { mine(it.loc, it.type) }
        onOpLocU(mining_content.rock, mining_content.pickaxe) { mine(it.loc, it.type) }
        onPlayerQueueWithArgs<ResumeTarget>(MiningQueues.resume) { resume(it.args) }
    }

    private fun ProtectedAccess.mine(rock: BoundLocInfo, type: UnpackedLocType) {
        val pickaxe = findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine this rock.")
            mes("You do not have a pickaxe which you have the Mining level to use.")
            return
        }

        if (player.miningLvl < type.rockLevelReq) {
            mes("You need a Mining level of ${type.rockLevelReq} to mine this rock.")
            return
        }

        val ore = objTypes[type.rockOre]
        if (inv.isFull()) {
            mes("Your inventory is too full to hold any more ${ore.oreName()}.")
            soundSynth(synths.pillory_wrong)
            return
        }

        val pickaxeType = objTypes[pickaxe]
        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 4
            anim(pickaxeType.pickaxeAnim)
        }

        if (actionDelay < mapClock) {
            // Starting (or restarting) a mining session: the first roll happens after one interval.
            actionDelay = mapClock + rollTicks(pickaxeType)
            spam("You swing your pick at the rock.")
        } else if (actionDelay == mapClock) {
            val (low, high) = type.rockSuccessRates
            if (random.of(GEM_TABLE_CHANCE) == 0) {
                rollGemTable()
            } else if (statRandom(stats.mining, low, high, invisibleLvls)) {
                mineOre(rock, type, ore)
                return
            }
            actionDelay = mapClock + rollTicks(pickaxeType)
        }

        opLoc1(rock)
    }

    private fun ProtectedAccess.mineOre(
        rock: BoundLocInfo,
        type: UnpackedLocType,
        ore: UnpackedObjType,
    ) {
        val xp = type.rockXp * xpMods.get(player, stats.mining)
        spam("You manage to mine some ${ore.oreName()}.")
        statAdvance(stats.mining, xp)
        invAdd(inv, ore)

        locRepo.change(rock, type.rockEmpty, type.rockRespawnTime)
        resetAnim()
        queueResume(rock, type, type.rockRespawnTime)
    }

    /**
     * Mining gem table (out of 128): nothing 70, sapphire 32, emerald 16, ruby 8, diamond 2. A
     * charged amulet of glory adds an extra 3/256 roll in OSRS; not implemented yet.
     */
    private fun ProtectedAccess.rollGemTable() {
        val roll = random.of(1, 128)
        val gem =
            when {
                roll <= 70 -> return
                roll <= 102 -> mining_objs.uncut_sapphire
                roll <= 118 -> mining_objs.uncut_emerald
                roll <= 126 -> mining_objs.uncut_ruby
                else -> mining_objs.uncut_diamond
            }
        val gemType = objTypes[gem]
        invAdd(inv, gemType)
        val gemName = gemType.name.removePrefix("Uncut ").replaceFirstChar(Char::uppercaseChar)
        mes("You just found ${gemName.withArticle()}!")
    }

    private fun ProtectedAccess.rollTicks(pickaxe: UnpackedObjType): Int {
        val ticks = pickaxe.pickaxeTicks
        // Dragon pickaxe: 3 ticks by default with a 1/6 chance of 2 (OSRS Wiki).
        if (pickaxe.isType(mining_objs.dragon_pickaxe) && random.of(1, 6) == 1) {
            return ticks - 1
        }
        return ticks
    }

    private fun UnpackedObjType.oreName(): String = name.lowercase().removeSuffix(" ore")

    private fun String.withArticle(): String {
        val article = if (first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $this"
    }

    /**
     * Auto-resume gathering (feature toggle, default on): after the rock depletes, wait in place
     * and start mining again when that same rock has respawned. The weak queue is cleared by
     * walking or clicking something else; combat cancels it too, and a full inventory stops it in
     * the normal checks.
     */
    private fun ProtectedAccess.queueResume(loc: BoundLocInfo, type: UnpackedLocType, ticks: Int) {
        if (!player.isFeatureOn(Feature.AutoResumeGathering)) {
            return
        }
        val target = ResumeTarget(loc.coords, type.id, RESUME_RETRIES)
        weakQueue(MiningQueues.resume, ticks + 1, target)
    }

    private fun ProtectedAccess.resume(target: ResumeTarget) {
        if (player.isInCombat()) {
            return
        }
        val type = locTypes[target.locId] ?: return
        val loc = locRepo.findExact(target.coords, type)
        if (loc == null) {
            // Respawn can lag a tick behind the timer; keep waiting briefly.
            if (target.retries > 0) {
                weakQueue(MiningQueues.resume, 1, target.copy(retries = target.retries - 1))
            }
            return
        }
        opLoc1(BoundLocInfo(loc, type))
    }

    private data class ResumeTarget(val coords: CoordGrid, val locId: Int, val retries: Int)

    companion object {
        private const val GEM_TABLE_CHANCE = 256
        private const val RESUME_RETRIES = 10

        val UnpackedObjType.pickaxeLevelReq: Int by objParam(params.levelrequire)
        val UnpackedObjType.pickaxeAnim: SeqType by objParam(params.skill_anim)
        val UnpackedObjType.pickaxeTicks: Int by objParam(mining_params.pickaxe_ticks)

        val UnpackedLocType.rockLevelReq: Int by locParam(params.levelrequire)
        val UnpackedLocType.rockOre: ObjType by locParam(params.skill_productitem)
        val UnpackedLocType.rockXp: Double by locXpParam(params.skill_xp)
        val UnpackedLocType.rockEmpty: LocType by locParam(params.next_loc_stage)
        val UnpackedLocType.rockRespawnTime: Int by locParam(params.respawn_time)
        private val UnpackedLocType.rockSuccessRatesPacked: Int by
            locParam(mining_params.success_rates)

        val UnpackedLocType.rockSuccessRates: Pair<Int, Int>
            get() = (rockSuccessRatesPacked shr 16) to (rockSuccessRatesPacked and 0xFFFF)

        /** Best usable pickaxe: worn or carried, whichever has the higher Mining requirement. */
        fun findPickaxe(player: Player, objTypes: ObjTypeList): InvObj? {
            val worn = player.righthand?.takeIf { objTypes[it].isUsablePickaxe(player.miningLvl) }
            val carried =
                player.inv
                    .filterNotNull { objTypes[it].isUsablePickaxe(player.miningLvl) }
                    .maxByOrNull { objTypes[it].pickaxeLevelReq }
            if (worn != null && carried != null) {
                val wornReq = objTypes[worn].pickaxeLevelReq
                return if (wornReq >= objTypes[carried].pickaxeLevelReq) worn else carried
            }
            return worn ?: carried
        }

        private fun UnpackedObjType.isUsablePickaxe(miningLevel: Int): Boolean =
            isContentType(mining_content.pickaxe) && miningLevel >= pickaxeLevelReq
    }
}

internal object MiningQueues : QueueReferences() {
    val resume = find("mining_resume")
}
