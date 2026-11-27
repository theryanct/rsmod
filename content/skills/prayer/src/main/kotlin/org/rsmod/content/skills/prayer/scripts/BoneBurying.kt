package org.rsmod.content.skills.prayer.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.content.skills.prayer.configs.prayer_seqs
import org.rsmod.game.MapClock
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.synth.SynthType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

// Out of scope for now: altars (gilded/chaos), Ectofuntus, bonecrusher, blessed bones/ashes.
class BoneBurying
@Inject
constructor(private val objTypes: ObjTypeList, private val mapClock: MapClock) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // Bind the "Bury" op of every bone obj in the cache that we have a Wiki xp value for. The
        // op slot is read from the cache rather than assumed, and noted (cert) variants have no
        // held ops so they are skipped naturally.
        for (type in objTypes.values) {
            val xp = buryXp[type.name.lowercase()] ?: continue
            for ((index, text) in type.iop.withIndex()) {
                if (!text.equals("Bury", ignoreCase = true)) {
                    continue
                }
                when (index + 1) {
                    1 -> onOpHeld1(type) { bury(it.inventory, it.slot, it.type, xp) }
                    2 -> onOpHeld2(type) { bury(it.inventory, it.slot, it.type, xp) }
                    3 -> onOpHeld3(type) { bury(it.inventory, it.slot, it.type, xp) }
                    4 -> onOpHeld4(type) { bury(it.inventory, it.slot, it.type, xp) }
                    5 -> onOpHeld5(type) { bury(it.inventory, it.slot, it.type, xp) }
                }
            }
        }
    }

    private fun ProtectedAccess.bury(
        inventory: Inventory,
        slot: Int,
        type: UnpackedObjType,
        xp: Double,
    ) {
        // Bones can be buried once every 2 ticks.
        if (actionDelay > mapClock) {
            return
        }
        val obj = inventory[slot] ?: return
        if (obj.id != type.id) {
            return
        }
        actionDelay = mapClock + 2

        invDel(inventory, type, count = 1, slot = slot)
        mes("You dig a hole in the ground...")
        anim(prayer_seqs.human_pickupfloor)
        soundSynth(bury_sound)
        // Base Wiki xp; the server-wide prayer xp multiplier is applied in `statAdvance`.
        statAdvance(stats.prayer, xp)
        mes("You bury the bones.")
    }

    private companion object {
        /** Synth 2738 (bury bones) has no symbol name in the rev 233 symbol dump. */
        val bury_sound = SynthType(internalId = 2738, internalName = "bury_bones")

        /** Prayer xp per bone, keyed by cache display name (OSRS Wiki, Nov 2026). */
        val buryXp: Map<String, Double> =
            mapOf(
                "bones" to 4.5,
                "wolf bones" to 4.5,
                "burnt bones" to 4.5,
                "bleached bones" to 4.5,
                "monkey bones" to 5.0,
                "small zombie monkey bones" to 5.0,
                "large zombie monkey bones" to 5.0,
                "bat bones" to 5.3,
                "big bones" to 15.0,
                "jogre bones" to 15.0,
                "burnt jogre bones" to 16.0,
                "small ninja monkey bones" to 16.0,
                "medium ninja monkey bones" to 18.0,
                "gorilla bones" to 18.0,
                "bearded gorilla bones" to 20.0,
                "wyrmling bones" to 21.0,
                "zogre bones" to 22.5,
                "shaikahan bones" to 25.0,
                "babydragon bones" to 30.0,
                "monodon bones" to 35.0,
                "wyrm bones" to 50.0,
                "strykewyrm bones" to 60.0,
                "dragon bones" to 72.0,
                "wyvern bones" to 72.0,
                "drake bones" to 80.0,
                "fayrg bones" to 84.0,
                "lava dragon bones" to 85.0,
                "raurg bones" to 96.0,
                "frost dragon bones" to 100.0,
                "hydra bones" to 110.0,
                "dagannoth bones" to 125.0,
                "ourg bones" to 140.0,
                "superior dragon bones" to 150.0,
            )
    }
}
