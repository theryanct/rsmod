package org.rsmod.api.combat.scripts

import jakarta.inject.Inject
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.combat.Dragonfire
import org.rsmod.api.combat.NpcRangedMagicData
import org.rsmod.api.combat.NvPCombat
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.player.aggressiveNpc
import org.rsmod.api.config.refs.categories
import org.rsmod.api.config.refs.params
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.player.isInPvnCombat
import org.rsmod.api.player.isInPvpCombat
import org.rsmod.api.script.advanced.onDefaultAiApPlayer2
import org.rsmod.api.script.advanced.onDefaultAiOpPlayer2
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.category.isType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

internal class NvPCombatScript
@Inject
constructor(
    private val combat: NvPCombat,
    private val dragonfire: Dragonfire,
    private val areaChecker: AreaChecker,
) : PluginScript() {
    private val rangedMagic by lazy { NpcRangedMagicData.attacks.associateBy { it.npc } }

    override fun ScriptContext.startup() {
        onDefaultAiOpPlayer2 { attemptCombatOp(it.target) }
        // Ranged/magic attackers fight through ap (see AiPlayerModeProcessor).
        onDefaultAiApPlayer2 { attemptCombatOp(it.target) }
    }

    private fun StandardNpcAccess.attemptCombatOp(target: Player) {
        if (!canAttack(target)) {
            resetMode()
            return
        }
        // Opt-in dragonfire (dragonfire.tsv): some attacks are a breath instead of melee.
        if (dragonfire.shouldBreathe(this)) {
            dragonfire.breathe(this, target)
            return
        }
        val rangedMagic = npc.visType.internalName?.let(rangedMagic::get)
        if (rangedMagic != null) {
            combat.attack(this, target, rangedMagic)
            return
        }
        val attackType = npc.resolveMeleeAttackType()
        val attack = CombatAttack.NpcMelee(attackType)
        combat.attack(this, target, attack)
    }

    private fun StandardNpcAccess.canAttack(target: Player): Boolean {
        val singleCombat = !mapMultiway(areaChecker)
        if (singleCombat) {
            if (target.isInPvpCombat()) {
                return false
            }

            if (target.isInPvnCombat()) {
                if (target.aggressiveNpc != null && target.aggressiveNpc != npc.uid) {
                    return false
                }
            }
        }
        return true
    }

    private fun Npc.resolveMeleeAttackType(): MeleeAttackType {
        val category = visType.paramOrNull(params.npc_attack_type)
        return when {
            category.isType(categories.attacktype_stab) -> MeleeAttackType.Stab
            category.isType(categories.attacktype_slash) -> MeleeAttackType.Slash
            else -> MeleeAttackType.Crush
        }
    }
}
