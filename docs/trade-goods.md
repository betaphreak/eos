# Trade goods

Every province in EU4/Anbennar produces one **trade good** (grain, iron, silk, …). Unlike a
per-plot [`Bonus`](plots.md) — a resource scattered onto individual build plots by the C2C
placement algorithm — a trade good tags the **whole province**. It is first-class engine reference
data joined to provinces by key, exactly like the [political layer](political-map.md)
(owner/culture/religion).

## Data pipeline

Canonical goods come from the Anbennar EU4 dev mod (`gitlab.com/anbennar/anbennar-eu4-dev`, pinned by
`map/anbennar-source.lock`), fetched on demand by `com.civstudio.data.AnbennarFiles` — never vendored.
Two sources, two derived (committed) resources:

- `history/provinces/<id> - <Name>.txt` — the top-level `trade_goods = grain` scalar (plus any dated
  `YYYY.M.D = { trade_goods = ... }` overrides applied up to the 1444.11.11 start). The EU4
  `unknown` placeholder (undiscovered province) is normalized to a **null** trade good.
- `common/tradegoods/00_tradegoods.txt` — one `goodname = { color = { r g b } … }` block per good.
  Note the colour channels are **floats in 0..1** (e.g. `0.96 0.93 0.58`), unlike the 0..255 ints in
  the culture/religion sources.

Two dev-tool exporters (`com.civstudio.geo.export`), mirroring the political stamp-chain:

- **`ProvinceHistoryExporter`** — already the owner/culture/religion stamper; now also stamps
  `trade_goods` onto `generated/map/provinces.json`. Same brace-aware scan + dated-override merge; the
  effective good at the start bookmark rides the existing logic.
- **`TradeGoodExporter`** — writes `generated/map/tradegoods.json` (`key → {name, color, category}`),
  the sibling of `Country`/`Culture`/`Religion` exporters. The `unknown` placeholder is skipped. EU4
  carries no economic category, so the `TradeGoodClass` mapping is **hand-authored** in the exporter
  (a good missing from it is emitted as `LUXURY` with a loud warning, so a mod update that adds a good
  is caught).

Re-run after any base `ProvinceExporter` regen (`TradeGoodExporter` is independent of it):

```
mvn -pl civstudio-engine exec:exec -Dsim.main=com.civstudio.geo.export.TradeGoodExporter
mvn -pl civstudio-engine exec:exec -Dsim.main=com.civstudio.geo.export.ProvinceHistoryExporter
```

## Engine model

`Province` gains a nullable `tradeGood` key (the good's `raw_key`, e.g. `"grain"`). `TradeGood` is a
record (`key`, `name`, `color`, `category`) loaded by `WorldMap` from the optional `tradegoods.json`
resource; `TradeGoodClass` is a five-bucket classification — `FOOD`, `LUXURY`, `STRATEGIC`,
`MANUFACTURED`, `MAGICAL` (the last covers the Anbennar magical goods: damestear, mithril, precursor
relics, fungi, serpentbloom). `WorldMap` exposes `tradeGood(key)`, the `tradeGoods()` collection, and
the derived `provincesOfTradeGood(key)` membership index (built like `provincesByReligion`).

An undiscovered/uncolonized province has a null trade good. **Nothing in the sim consumes trade goods
yet** — like the political layer, this is reference data: the seam a future export-sector / market
economy plugs into (a province's good could seed its strategic export, or weight what its firms
produce). The classification is deliberately a standalone enum today (it does not yet map to the
consumer-good `ResourceType` the way `BonusClass` does).
