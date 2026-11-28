package org.rsmod.content.generic.poison

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.hitmark_groups
import org.rsmod.api.npc.events.NpcAttackHitEvent
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.poison.POISON_HIT_INTERVAL
import org.rsmod.api.player.poison.applyPoison
import org.rsmod.api.player.poison.poisonDamage
import org.rsmod.api.player.poison.poisonValue
import org.rsmod.api.player.poison.setPoisonValue
import org.rsmod.api.random.GameRandom
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.type.refs.timer.TimerReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Poison (state and green hitpoints orb: [org.rsmod.api.player.poison]):
 * - Poisonous npcs (OSRS Wiki infobox_monster "poisonous", `poison.tsv`) poison the player on any
 *   damaging melee, ranged or magic hit with a 1/4 chance, starting at the Wiki damage.
 * - Every 30 ticks a poisoned player takes a poison hitsplat; damage N repeats five times and then
 *   drops to N - 1 until it wears off. Antipoison immunity counts down on the same timer.
 * - Cure and immunity come from antipoison potions (Herblore calls `curePoison`).
 */
class Poison @Inject constructor(private val random: GameRandom) : PluginScript() {
    private val logger = InlineLogger()

    private val damageByNpc: Map<String, Int> by lazy {
        val input =
            checkNotNull(Poison::class.java.getResourceAsStream("poison.tsv")) {
                "poison.tsv not found"
            }
        input.bufferedReader().use { it.readLines() }
            .filter { it.startsWith("poison\t") }
            .associate { line ->
                val p = line.split('\t')
                p[1] to p[2].toInt()
            }
    }

    override fun ScriptContext.startup() {
        onEvent<NpcAttackHitEvent> { onNpcHit(this) }
        onPlayerSoftTimer(PoisonTimers.tick) { tick(player) }
        onPlayerLogin {
            if (player.poisonValue != 0) {
                player.softTimer(PoisonTimers.tick, POISON_HIT_INTERVAL)
            }
        }
        logger.info { "Poison: ${damageByNpc.size} poisonous npc types." }
    }

    private fun onNpcHit(event: NpcAttackHitEvent) {
        if (event.damage <= 0) return
        val name = event.npc.visType.internalName ?: return
        val damage = damageByNpc[name] ?: return
        if (random.of(POISON_CHANCE) != 0) return
        val player = event.target
        if (player.applyPoison(damage)) {
            player.mes("You have been poisoned!")
            player.softTimer(PoisonTimers.tick, POISON_HIT_INTERVAL)
        }
    }

    private fun tick(player: Player) {
        val value = player.poisonValue
        when {
            value > 0 -> {
                player.queueHit(
                    delay = 1,
                    type = HitType.Typeless,
                    damage = player.poisonDamage,
                    hitmark = hitmark_groups.poison_damage,
                )
                player.setPoisonValue(value - 1)
                if (value - 1 == 0) {
                    player.mes("The poison has worn off.")
                }
            }
            value < 0 -> {
                player.setPoisonValue(value + 1)
                if (value + 1 == 0) {
                    player.mes("Your immunity against poison has worn off.")
                }
            }
        }
        if (player.poisonValue == 0) {
            player.clearSoftTimer(PoisonTimers.tick)
        }
    }

    private companion object {
        const val POISON_CHANCE = 4
    }
}

internal object PoisonTimers : TimerReferences() {
    val tick = find("poison_tick")
}
