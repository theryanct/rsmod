package org.rsmod.api.market

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType

/**
 * Market prices from the OSRS Wiki real-time price snapshot (`prices.tsv`, written by
 * `tools/ge/fetch_prices.py`): the mid of the instant buy/sell prices for every GE-tradeable obj.
 * Objs missing from the snapshot fall back to their store value (`cost`), like
 * [DefaultMarketPrices]. Certed objs use their uncerted obj's price.
 */
@Singleton
public class SnapshotMarketPrices @Inject constructor(private val objTypes: ObjTypeList) :
    MarketPrices {
    private val prices: Map<Int, Int> by lazy { load() }

    /** True if [type] (or its uncerted form) has a market price in the snapshot. */
    public fun isListed(type: UnpackedObjType): Boolean = objTypes.uncert(type).id in prices

    override fun get(type: UnpackedObjType): Int {
        val base = objTypes.uncert(type)
        return prices[base.id] ?: base.cost
    }

    private fun load(): Map<Int, Int> {
        val stream =
            SnapshotMarketPrices::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        val out = HashMap<Int, Int>()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t")) {
                    continue
                }
                val parts = line.split('\t')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val mid = parts.getOrNull(2)?.toIntOrNull() ?: continue
                out[id] = mid.coerceAtLeast(1)
            }
        }
        return out
    }

    private companion object {
        private const val RESOURCE = "prices.tsv"
    }
}
