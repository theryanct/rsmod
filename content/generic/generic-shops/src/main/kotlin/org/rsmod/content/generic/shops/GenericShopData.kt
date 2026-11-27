package org.rsmod.content.generic.shops

/**
 * Shops generated from OSRS Wiki store pages by `tools/shops/wiki_shops_to_tsv.py` into the
 * `shops.tsv` resource:
 * - **shops** name a cache shop inv, its stock (by internal obj name) and the shopkeeper npcs with
 *   the op slot of their "Trade" option;
 * - **vendors** are dialogue shops (e.g. bartenders): an npc whose "Talk-to" op offers a few
 *   fixed-price items.
 */
internal object GenericShopData {
    class StockDef(val obj: String, val count: Int, val restockCycles: Int)

    class ShopDef(
        val inv: String,
        val size: Int,
        val general: Boolean,
        val sellPercentage: Int,
        val buyPercentage: Int,
        val changePercentage: Int,
        val title: String,
        val npcs: List<Pair<String, Int>>,
        val stock: List<StockDef>,
    )

    class VendorItem(val obj: String, val price: Int, val label: String)

    class VendorDef(val npc: String, val op: Int, val title: String, val items: List<VendorItem>)

    private class Parsed(val shops: List<ShopDef>, val vendors: List<VendorDef>)

    private val parsed: Parsed by lazy { load() }

    val shops: List<ShopDef>
        get() = parsed.shops

    val vendors: List<VendorDef>
        get() = parsed.vendors

    private fun load(): Parsed {
        val input =
            GenericShopData::class.java.getResourceAsStream("shops.tsv")
                ?: error("Generic shop resource not found: shops.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val shops = ArrayList<ShopDef>()
        val vendors = ArrayList<VendorDef>()

        var shopHeader: List<String>? = null
        var vendorHeader: List<String>? = null
        var npcs = ArrayList<Pair<String, Int>>()
        var stock = ArrayList<StockDef>()
        var items = ArrayList<VendorItem>()

        fun flush() {
            val s = shopHeader
            if (s != null) {
                shops.add(
                    ShopDef(
                        inv = s[1],
                        size = s[2].toInt(),
                        general = s[3] == "1",
                        sellPercentage = s[4].toInt(),
                        buyPercentage = s[5].toInt(),
                        changePercentage = s[6].toInt(),
                        title = s[7],
                        npcs = npcs,
                        stock = stock,
                    )
                )
            }
            val v = vendorHeader
            if (v != null) {
                vendors.add(VendorDef(npc = v[1], op = v[2].toInt(), title = v[3], items = items))
            }
            shopHeader = null
            vendorHeader = null
            npcs = ArrayList()
            stock = ArrayList()
            items = ArrayList()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val parts = line.split('\t')
            when (parts[0]) {
                "shop" -> {
                    flush()
                    shopHeader = parts
                }
                "vendor" -> {
                    flush()
                    vendorHeader = parts
                }
                "npc" -> npcs.add(parts[1] to parts[2].toInt())
                "stock" -> stock.add(StockDef(parts[1], parts[2].toInt(), parts[3].toInt()))
                "item" -> items.add(VendorItem(parts[1], parts[2].toInt(), parts[3]))
                else -> error("Unknown generic shop line: $raw")
            }
        }
        flush()
        return Parsed(shops, vendors)
    }
}
