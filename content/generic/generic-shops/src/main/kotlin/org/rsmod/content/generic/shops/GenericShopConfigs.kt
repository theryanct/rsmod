package org.rsmod.content.generic.shops

import org.rsmod.api.type.builders.map.npc.MapNpcSpawnBuilder
import org.rsmod.api.type.editors.inv.InvEditor
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.inv.InvScope
import org.rsmod.game.type.inv.InvStackType
import org.rsmod.game.type.inv.InvType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType

object GenericShopInvs : InvReferences() {
    val byName: Map<String, InvType> =
        GenericShopData.shops.map { it.inv }.distinct().associateWith { find(it) }
}

object GenericShopObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        (GenericShopData.shops.flatMap { shop -> shop.stock.map { it.obj } } +
                GenericShopData.vendors.flatMap { vendor -> vendor.items.map { it.obj } })
            .distinct()
            .associateWith { find(it) }
}

object GenericShopNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        (GenericShopData.shops.flatMap { shop -> shop.npcs.map { it.first } } +
                GenericShopData.vendors.map { it.npc })
            .distinct()
            .associateWith { find(it) }
}

object GenericShopInvEditor : InvEditor() {
    init {
        for (shop in GenericShopData.shops) {
            edit(GenericShopInvs.byName.getValue(shop.inv)) {
                scope = InvScope.Shared
                stack = InvStackType.Always
                size = shop.size
                restock = true
                allStock = shop.general
                for (def in shop.stock) {
                    val obj = GenericShopObjs.byName.getValue(def.obj)
                    stock.add(stock(obj, count = def.count, restockCycles = def.restockCycles))
                }
            }
        }
    }
}

/** Shopkeepers that were not spawned by any other module (wiki {{Map}} point of their page). */
object GenericShopNpcSpawns : MapNpcSpawnBuilder() {
    override fun onPackMapTask() {
        resourceFile<GenericShopNpcSpawns>("npcs.toml")
    }
}
