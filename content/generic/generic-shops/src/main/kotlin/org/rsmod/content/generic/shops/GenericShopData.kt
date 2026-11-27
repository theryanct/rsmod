package org.rsmod.content.generic.shops

/**
 * Shops generated from OSRS Wiki store pages by `tools/shops/wiki_shops_to_tsv.py` into the
 * `shops.tsv` resource. Each shop names a cache shop inv, its stock (by internal obj name) and the
 * shopkeeper npcs with the op slot of their "Trade" option.
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

    val shops: List<ShopDef> by lazy { load() }

    private fun load(): List<ShopDef> {
        val input =
            GenericShopData::class.java.getResourceAsStream("shops.tsv")
                ?: error("Generic shop resource not found: shops.tsv")
        val lines = input.bufferedReader().use { it.readLines() }
        val shops = ArrayList<ShopDef>()
        var header: List<String>? = null
        var npcs = ArrayList<Pair<String, Int>>()
        var stock = ArrayList<StockDef>()

        fun flush() {
            val h = header ?: return
            shops.add(
                ShopDef(
                    inv = h[1],
                    size = h[2].toInt(),
                    general = h[3] == "1",
                    sellPercentage = h[4].toInt(),
                    buyPercentage = h[5].toInt(),
                    changePercentage = h[6].toInt(),
                    title = h[7],
                    npcs = npcs,
                    stock = stock,
                )
            )
            header = null
            npcs = ArrayList()
            stock = ArrayList()
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
                    header = parts
                }
                "npc" -> npcs.add(parts[1] to parts[2].toInt())
                "stock" -> stock.add(StockDef(parts[1], parts[2].toInt(), parts[3].toInt()))
                else -> error("Unknown generic shop line: $raw")
            }
        }
        flush()
        return shops
    }
}
