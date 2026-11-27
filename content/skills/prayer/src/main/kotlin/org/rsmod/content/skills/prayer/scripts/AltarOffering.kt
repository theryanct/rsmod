package org.rsmod.content.skills.prayer.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onOpLocU
import org.rsmod.content.skills.prayer.configs.BoneXp
import org.rsmod.content.skills.prayer.configs.prayer_seqs
import org.rsmod.content.skills.prayer.configs.prayer_spotanims
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Local-server tweak (Ryan): bones can be offered on ANY prayer altar in the game for gilded-altar
 * xp ([BoneXp.ALTAR_OFFER_MULTIPLIER] x bury xp). Offering continues through the inventory until
 * the player runs out of that bone or does something else.
 */
class AltarOffering
@Inject
constructor(private val locTypes: LocTypeList, private val worldRepo: WorldRepository) :
    PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        // Prayer altars are any loc whose name mentions "altar" and that has a pray op
        // ("Pray-at", "Pray"). This skips runecrafting altars and decorative altar parts.
        var count = 0
        for (type in locTypes.values) {
            if (!type.isPrayerAltar()) {
                continue
            }
            onOpLocU(type) { offerLoop(it.loc, it.objType) }
            count++
        }
        logger.info { "Bone offering enabled on $count prayer altar loc types." }
    }

    private fun UnpackedLocType.isPrayerAltar(): Boolean {
        if (!name.contains("altar", ignoreCase = true)) {
            return false
        }
        return op.any { it != null && it.startsWith("Pray", ignoreCase = true) }
    }

    private suspend fun ProtectedAccess.offerLoop(altar: BoundLocInfo, bone: UnpackedObjType) {
        val baseXp = BoneXp.buryXp(bone)
        if (baseXp == null) {
            mes("Nothing interesting happens.")
            return
        }
        while (bone in inv) {
            val removed = invDel(inv, bone, count = 1)
            if (removed.failure) {
                return
            }
            faceSquare(altar.coords)
            anim(prayer_seqs.human_bone_sacrifice)
            spotanimMap(worldRepo, prayer_spotanims.poh_bone_sacrifice, altar.coords)
            statAdvance(stats.prayer, baseXp * BoneXp.ALTAR_OFFER_MULTIPLIER)
            mes("The gods are pleased with your offering.")
            delay(OFFER_TICKS)
        }
    }

    private companion object {
        /** Gilded altar offering cadence. */
        const val OFFER_TICKS = 3
    }
}
