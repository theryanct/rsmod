package org.rsmod.content.generic.locs.pickables

import org.rsmod.api.config.refs.content
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.type.editors.loc.LocEditor
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.loc.LocType

internal typealias pickable_locs = PickableLocs

object PickableLocs : LocReferences() {
    val cabbage = find("cabbage", 1496620632455889241)
    val onion = find("onion", 3040472638361972606)
    val potato = find("potato", 6619139296550516438)
    val fai_varrock_wheat = find("fai_varrock_wheat", 7022213377670558384)
    val fai_varrock_wheat_corner = find("fai_varrock_wheat_corner", 7022213377670558385)
    val fai_varrock_wheat_small = find("fai_varrock_wheat_small", 7022213377670558386)
    val wheat = find("wheat")
    val brain_farm_wheat = find("brain_farm_wheat")
    val brain_wheat_small = find("brain_wheat_small")
    val brain_wheat_smallest = find("brain_wheat_smallest")
    val flax = find("flax")
    val avium_corn01 = find("avium_corn01")
    val avium_corn02 = find("avium_corn02")
    val avium_cabbage01 = find("avium_cabbage01")
    val avium_cabbage02 = find("avium_cabbage02")
    val brain_farm_cabbage = find("brain_farm_cabbage")
    val avium_onion01 = find("avium_onion01")
    val avium_onion02 = find("avium_onion02")
}

internal object PickableObjs : ObjReferences() {
    val flax = find("flax")
    val sweetcorn = find("sweetcorn")
}

internal object PickableLocEditor : LocEditor() {
    init {
        edit(pickable_locs.cabbage) {
            param[params.game_message] = "You pick a cabbage."
            param[params.game_message2] = "You don't have room for this cabbage."
            param[params.rewarditem] = objs.cabbage
            param[params.respawn_time] = 75
        }

        onion(pickable_locs.onion)
        onion(pickable_locs.avium_onion01)
        onion(pickable_locs.avium_onion02)

        // Varlamore/Mos Le'Harmless field cabbages: plain crops (no seed roll, unlike Draynor's).
        val fieldCabbages =
            listOf(
                pickable_locs.avium_cabbage01,
                pickable_locs.avium_cabbage02,
                pickable_locs.brain_farm_cabbage,
            )
        for (type in fieldCabbages) {
            edit(type) {
                contentGroup = content.pickable_crop
                param[params.game_message] = "You pick a cabbage."
                param[params.game_message2] = "You don't have room for this cabbage."
                param[params.rewarditem] = objs.cabbage
                param[params.respawn_time] = 75
            }
        }

        for (type in listOf(pickable_locs.avium_corn01, pickable_locs.avium_corn02)) {
            edit(type) {
                contentGroup = content.pickable_crop
                param[params.game_message] = "You pick some sweetcorn."
                param[params.game_message2] = "You can't carry any more sweetcorn."
                param[params.rewarditem] = PickableObjs.sweetcorn
                param[params.respawn_time] = 50
            }
        }

        // Flax: bound in Pickable (3/16 chance to deplete, Wiki), so no content group.
        edit(pickable_locs.flax) {
            param[params.game_message] = "You pick some flax."
            param[params.game_message2] = "You can't carry any more flax."
            param[params.rewarditem] = PickableObjs.flax
            param[params.respawn_time] = 10
        }

        edit(pickable_locs.potato) {
            contentGroup = content.pickable_crop
            param[params.game_message] = "You pick a potato."
            param[params.game_message2] = "You don't have room for this potato."
            param[params.rewarditem] = objs.potato
            param[params.respawn_time] = 50
        }

        grain(pickable_locs.fai_varrock_wheat)
        grain(pickable_locs.fai_varrock_wheat_corner)
        grain(pickable_locs.fai_varrock_wheat_small)
        grain(pickable_locs.wheat)
        grain(pickable_locs.brain_farm_wheat)
        grain(pickable_locs.brain_wheat_small)
        grain(pickable_locs.brain_wheat_smallest)
    }

    private fun onion(type: LocType) {
        edit(type) {
            contentGroup = content.pickable_crop
            param[params.game_message] = "You pick an onion."
            param[params.game_message2] = "You don't have room for this onion."
            param[params.rewarditem] = objs.onion
            param[params.respawn_time] = 50
        }
    }

    private fun grain(type: LocType) {
        edit(type) {
            contentGroup = content.pickable_crop
            param[params.game_message] = "You pick some grain."
            param[params.game_message2] = "You can't carry any more grain."
            param[params.rewarditem] = objs.grain
            param[params.respawn_time] = 20
        }
    }
}
