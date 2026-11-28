package org.rsmod.api.market

import org.rsmod.module.ExtendedModule

public object MarketModule : ExtendedModule() {
    override fun bind() {
        // OSRS Wiki real-time price snapshot (falls back to obj cost when missing).
        bindBaseInstance<MarketPrices>(SnapshotMarketPrices::class.java)
    }
}
