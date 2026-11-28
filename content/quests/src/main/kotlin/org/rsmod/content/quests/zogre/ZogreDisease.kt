package org.rsmod.content.quests.zogre

import jakarta.inject.Inject
import org.rsmod.api.config.refs.hitmark_groups
import org.rsmod.api.config.refs.stats
import org.rsmod.api.npc.events.NpcAttackHitEvent
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Disease (OSRS Wiki "Disease"), from zogres, skogres and Slash Bash. State is the cache varp
 * `disease`: the 30-tick periods left (zogres 30 = 15 minutes, Slash Bash 50, since the Wiki needs
 * five balm doses rather than three for his). Every 30 ticks a random stat other than Hitpoints and
 * Prayer drops by 1-10 (never below 1); a stat already at 1 takes the damage from Hitpoints
 * instead. Relicym's balm (Herblore) takes 10 periods off per dose.
 *
 * Rum Deal's fever spiders (`deal_fever_spiders1`) spread it too, unless the player wears slayer
 * gloves (`deal_slayer_gloves`, Wiki).
 *
 * Simplified: a 1 in 4 chance on any zogre attack (the Wiki gives no rate); one severity for all
 * sources (Slash Bash's drain is up to 15 in OSRS); no Sanfew serum immunity (not on this server).
 */
class ZogreDisease @Inject constructor(private val random: GameRandom) : PluginScript() {
    private val sources: Set<Int> by lazy { DiseaseNpcs.all.mapTo(HashSet()) { it.id } }

    override fun ScriptContext.startup() {
        onEvent<NpcAttackHitEvent> { onNpcHit(this) }
        onPlayerSoftTimer(DiseaseTimers.tick) { tick(player) }
        onPlayerLogin {
            if (player.vars[DiseaseVarps.disease] > 0) player.softTimer(DiseaseTimers.tick, PERIOD)
        }
    }

    private fun onNpcHit(event: NpcAttackHitEvent) {
        val id = event.npc.type.id
        if (id !in sources) return
        val player = event.target
        if (id == DiseaseNpcs.fever_spider.id && DiseaseObjs.slayer_gloves in player.worn) return
        if (random.of(CHANCE) != 0) return
        val periods = if (id == DiseaseNpcs.slash_bash.id) BASH_PERIODS else ZOGRE_PERIODS
        val current = player.vars[DiseaseVarps.disease]
        if (current >= periods) return
        VarPlayerIntMapSetter.set(player, DiseaseVarps.disease, periods)
        if (current == 0) {
            player.mes("You have been infected with a disease!") // Authored (not Wiki).
            player.softTimer(DiseaseTimers.tick, PERIOD)
        }
    }

    private fun tick(player: Player) {
        val left = player.vars[DiseaseVarps.disease]
        if (left <= 0) {
            player.clearSoftTimer(DiseaseTimers.tick)
            return
        }
        val skill = DRAINED[random.of(0, DRAINED.size - 1)]
        val level = player.stat(skill)
        val amount = random.of(1, MAX_DRAIN)
        if (level > 1) {
            player.statSub(skill, amount.coerceAtMost(level - 1), 0)
        } else {
            player.queueHit(delay = 1, type = HitType.Typeless, damage = amount, hitmark = hitmark_groups.disease)
        }
        VarPlayerIntMapSetter.set(player, DiseaseVarps.disease, left - 1)
        if (left - 1 == 0) {
            player.mes("The disease has worn off.") // Authored (not Wiki).
            player.clearSoftTimer(DiseaseTimers.tick)
        }
    }

    private companion object {
        const val PERIOD = 30
        const val CHANCE = 4
        const val ZOGRE_PERIODS = 30
        const val BASH_PERIODS = 50
        const val MAX_DRAIN = 10

        val DRAINED =
            listOf(
                stats.attack,
                stats.defence,
                stats.strength,
                stats.ranged,
                stats.magic,
                stats.cooking,
                stats.woodcutting,
                stats.fletching,
                stats.fishing,
                stats.firemaking,
                stats.crafting,
                stats.smithing,
                stats.mining,
                stats.herblore,
                stats.agility,
                stats.thieving,
                stats.slayer,
                stats.farming,
                stats.runecrafting,
                stats.hunter,
                stats.construction,
            )
    }
}

internal object DiseaseNpcs : NpcReferences() {
    val slash_bash = find("zogre_slash_bash")
    val fever_spider = find("deal_fever_spiders1")
    val all =
        listOf(
            slash_bash,
            fever_spider,
            find("zogre_1"),
            find("zogre_2"),
            find("zogre_3"),
            find("zogre_4"),
            find("zogre_5"),
            find("zogre_6"),
            find("zogre_skele_dance"),
            find("zogre_dance_1"),
            find("zogre_dance_2"),
            find("zogre_dance_3"),
            find("zogre_dance_4"),
            find("zogre_drummer1"),
            find("zogre_drummer2"),
            find("zogre_skele"),
        )
}

internal object DiseaseObjs : ObjReferences() {
    val slayer_gloves = find("deal_slayer_gloves")
}

internal object DiseaseVarps : VarpReferences() {
    /** Cache varp: disease periods left (shared with Herblore's Relicym's balm). */
    val disease = find("disease")
}

internal object DiseaseTimers : TimerReferences() {
    val tick = find("zogre_disease")
}
