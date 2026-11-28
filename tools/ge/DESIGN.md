# Grand Exchange (local server) — design note

Status: design only. Build after the rev 243 cutover; ids below are from cache 2777 (rev 243).

## Ryan's spec
"Buy any tradeable at market price, sell any at market value." Offers fill **instantly**: no
order book, no waiting, no other players. Market price = the OSRS Wiki real-time mid price.

## Prices (done in this branch)
- `tools/ge/fetch_prices.py` writes `api/market/.../org/rsmod/api/market/prices.tsv`
  (`id name mid high low limit`, header `# fetched=<UTC>`). Only objs with the cache
  stockmarket flag (obj opcode 65) are included; no recent trades -> wiki `value`.
- Refresh = re-run the script and reboot (optionally a `::geprices` reload command later).
- Server side (to build): `SnapshotMarketPrices : MarketPrices` loading `prices.tsv` once;
  `get(type)` = snapshot mid for the uncerted obj id, else the current fallback (obj `cost`).
  Bind it in `MarketModule` instead of `DefaultMarketPrices`. This also fixes examine prices.

## Interfaces (rev 243 ids)
| Purpose | Interface | Notes |
|---|---|---|
| Offers screen | `ge_offers` (465) | main modal; slots `index_0`..`index_7`, `setup`, `details`, `collectall`, `back`, `history` |
| Inventory side | `ge_offers_side` (467) | `items` (offer an item by clicking it) |
| Collect box | `ge_collect` (402) | already opened by bankers (`Banker.kt`) |
| Item search | chatbox obj search | `ClientScripts.mesLayerMode14` (stockmarket restriction) -> `ResumePObjDialog` |
| Price checker / history | `ge_pricechecker` (464) / `ge_history` (383) | later |

Vars the client scripts read (confirm exact semantics from the 243 `ge_offers_*` cs2 before
building): varbits `ge_selectedslot`, `ge_newoffer_type` (buy/sell), `ge_newoffer_quantity`,
`ge_price_custom`, `ge_transmit_taxrate`; varps `ge_newoffer_price`, `ge_last_offer_item/
quantity/price/type`, `ge_last_searched`. Slot contents are sent with
`UpdateStockMarketSlotV2(slot, SetStockMarketSlot(status, obj, price, count, completedCount,
completedGold))` / `ResetStockMarketSlot` (both V1 and V2 encoders are bound in rsprot 243; V2
carries 64-bit prices — prefer it).

## Flow
1. Open: "Exchange" op on Grand Exchange clerks / booths (bind by op label, like shops and
   furnaces) -> `ge_offers` + `ge_offers_side`; send all 8 slot states.
2. Buy: empty slot "Buy" -> setup screen, client opens the obj search; on `ResumePObjDialog`
   set the setup vars with obj, quantity 1 and **price = market mid**. Quantity buttons /
   Enter-X adjust quantity (count dialog). Price buttons are ignored or disabled: the price is
   always market price (per spec).
3. Sell: click an inventory item (noted is fine) -> setup screen with obj (uncerted) and
   price = market mid, quantity = held amount.
4. Confirm: fill instantly.
   - Buy: requires `price * qty` coins in the inventory (fail with the OSRS message
     otherwise); delete the coins, put `qty` objs (noted if not stackable and qty > 1? -> keep
     OSRS behaviour: the collect box gives noted objs) into the slot's collection.
   - Sell: delete the objs, put `price * qty` coins into the slot's collection.
   - Send the slot as completed (`completedCount = count`, `completedGold`).
5. Collect: `collectall` / per-slot collect moves collection contents to the inventory (or
   bank, matching the OSRS "collect to bank" option) and resets the slot.

## Persistence
Instant fill means a slot only ever holds uncollected results. Store per character: slot ->
(type, obj, count, price, collection items). Proposed: migration **V8** (ui-owned) table
`ge_slots(character_id, slot, type, obj, count, price, coll_obj, coll_count)` with a load/save
pipeline like `CharacterSocialPipeline`.

## Decisions to confirm with Ryan
- Tax: OSRS takes 2% on sells (`ge_transmit_taxrate`). Spec says "market value" -> default
  **no tax** (optionally a feature toggle).
- Buy limits (`limit` column) -> default **ignored**.
- Items with no market data (rare) -> wiki `value`, else `cost`.
