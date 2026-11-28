package org.rsmod.content.skills.prayer.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.basePrayerLvl
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpLoc4
import org.rsmod.api.script.onOpLoc5
import org.rsmod.content.skills.prayer.configs.prayer_seqs
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.synth.SynthType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * "Pray-at" / "Pray" on any loc restores Prayer points to the base level (church altars, chaos
 * altar, god altars...). Bound by op label, so it never overlaps [AltarOffering]'s use-item
 * (bones on altar) binding.
 */
class AltarPraying @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var count = 0
        for (type in locTypes.values) {
            for ((index, text) in type.op.withIndex()) {
                val label = text?.trim() ?: continue
                if (!label.equals("Pray-at", ignoreCase = true) && !label.equals("Pray", ignoreCase = true)) {
                    continue
                }
                bind(type, index + 1)
                count++
            }
        }
        logger.info { "Prayer: bound Pray-at on $count loc types." }
    }

    private fun ScriptContext.bind(type: UnpackedLocType, op: Int) {
        when (op) {
            1 -> if (!isLocOpBound(type, 1)) onOpLoc1(type) { pray(it.loc) }
            2 -> if (!isLocOpBound(type, 2)) onOpLoc2(type) { pray(it.loc) }
            3 -> if (!isLocOpBound(type, 3)) onOpLoc3(type) { pray(it.loc) }
            4 -> if (!isLocOpBound(type, 4)) onOpLoc4(type) { pray(it.loc) }
            5 -> if (!isLocOpBound(type, 5)) onOpLoc5(type) { pray(it.loc) }
        }
    }

    private fun ProtectedAccess.pray(altar: BoundLocInfo) {
        faceSquare(altar.coords)
        if (player.prayerLvl >= player.basePrayerLvl) {
            mes("You already have full Prayer points.")
            return
        }
        anim(prayer_seqs.human_pray)
        soundSynth(prayer_recharge)
        player.statRestore(stats.prayer)
        mes("You recharge your Prayer points.")
    }

    private companion object {
        /** Synth 2674 (prayer recharge) has no symbol name in the rev 233 symbol dump. */
        val prayer_recharge = SynthType(internalId = 2674, internalName = "prayer_recharge")
    }
}
