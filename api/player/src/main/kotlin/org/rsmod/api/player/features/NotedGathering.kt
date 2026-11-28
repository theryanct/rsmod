package org.rsmod.api.player.features

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.inv.InvObj
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.objtx.TransactionResultList

/**
 * Adds a gathered product (logs, ore, fish, essence, loot) to the inventory: as a note when the
 * "Noted gathering" feature is on and the obj has a noted form (objs without one, and stackables,
 * are added as they are).
 */
public fun ProtectedAccess.invAddGathered(
    type: ObjType,
    count: Int = 1,
): TransactionResultList<InvObj> =
    invAdd(inv, type, count, cert = player.isFeatureOn(Feature.NotedGathering))

/**
 * Gathering's "inventory too full" check: `false` while there is a free slot, or when noted
 * gathering is on and one of [products] already has a note stack to grow.
 */
public fun ProtectedAccess.invFullForGathering(
    objTypes: ObjTypeList,
    products: Collection<ObjType>,
): Boolean {
    if (!inv.isFull()) {
        return false
    }
    if (!player.isFeatureOn(Feature.NotedGathering)) {
        return true
    }
    return products.none { product ->
        val base = objTypes[product]
        val noted = objTypes.cert(base)
        noted.id != base.id && invTotal(inv, noted) > 0
    }
}
