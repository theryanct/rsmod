package org.rsmod.content.skills.hunter

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.skills.hunter.HunterData.Creature

/**
 * The Birds and the Wasps bookkeeping that happens while hunting (content/quests birdsandwasps
 * owns the quest itself). Only the quest's cache varbits are shared, so the hunter module needs no
 * dependency on the quests module; the stage numbers must match `BirdsAndWasps.kt`.
 * - Stage [WASPS]: once the player holds 50 wasp stings they remark they've caught enough (once,
 *   `baw_wasps_caught_notification`).
 * - Stage [BIRDS]: each honey buzzard checked counts in `baw_birds_caught` (4 bits); at 5+ birds
 *   and 50 brown feathers the same remark (once, `baw_birds_caught_notification`).
 */
internal object BawProgress {
    const val WASPS = 25
    const val BIRDS = 35

    private const val WASP_STINGS_NEEDED = 50
    private const val BIRDS_NEEDED = 5
    private const val FEATHERS_NEEDED = 50
    private const val MAX_BIRDS_COUNTED = 15
    private const val HONEY_BUZZARD = "baw_honey_buzzard"
    private const val ENOUGH = "Looks like I've caught enough. I'd best let Bea know."

    suspend fun ProtectedAccess.waspCaught() {
        if (player.vars[BawVarBits.quest] != WASPS) return
        if (player.vars[BawVarBits.wasps_notified] != 0) return
        if (invTotal(inv, BawObjs.wasp_sting) < WASP_STINGS_NEEDED) return
        VarPlayerIntMapSetter.set(player, BawVarBits.wasps_notified, 1)
        startDialogue { chatPlayer(neutral, ENOUGH) }
    }

    suspend fun ProtectedAccess.birdCaught(creature: Creature) {
        if (HONEY_BUZZARD !in creature.npcs) return
        if (player.vars[BawVarBits.quest] != BIRDS) return
        val birds = player.vars[BawVarBits.birds_caught]
        if (birds < MAX_BIRDS_COUNTED) {
            VarPlayerIntMapSetter.set(player, BawVarBits.birds_caught, birds + 1)
        }
        if (player.vars[BawVarBits.birds_notified] != 0) return
        if (player.vars[BawVarBits.birds_caught] < BIRDS_NEEDED) return
        if (invTotal(inv, BawObjs.brown_feather) < FEATHERS_NEEDED) return
        VarPlayerIntMapSetter.set(player, BawVarBits.birds_notified, 1)
        startDialogue { chatPlayer(neutral, ENOUGH) }
    }
}

internal object BawVarBits : VarBitReferences() {
    /** The quest varbit (varp `baw_primary` bits 0-6, db end state 55). */
    val quest = find("baw")
    val birds_caught = find("baw_birds_caught")
    val wasps_notified = find("baw_wasps_caught_notification")
    val birds_notified = find("baw_birds_caught_notification")

    /** Each wasp swarm multinpc's state: 0 hidden, 1 catchable, 2 furious (Spray). */
    val wasp_1_state = find("baw_wasp_1_state")
    val wasp_2_state = find("baw_wasp_2_state")
    val wasp_3_state = find("baw_wasp_3_state")
    val killer_wasp_1_state = find("baw_killer_wasp_1_state")
    val killer_wasp_2_state = find("baw_killer_wasp_2_state")
    val killer_wasp_3_state = find("baw_killer_wasp_3_state")
}

internal object BawObjs : ObjReferences() {
    val wasp_sting = find("baw_wasp_sting")
    val brown_feather = find("baw_brown_feather")
    val bee_hunter_hat = find("baw_bee_hunter_hat")
    val beekeeper_hat = find("beekeeper_hat")
    val magic_net = find("ii_magic_butterfly_net")
    val insect_repellent = find("insect_repellent")
}
