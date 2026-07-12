# Civ6 SDK Asset Depot — reference & Civ4→Civ6 art map

**Purpose.** We intend to replace CivStudio's Civ4/Caveman2Cosmos (C2C) art with **Civilization VI** art.
This doc is the durable reference for *where Civ6 art lives, how it's addressed, and which Civ6 asset
answers each CivStudio art category*. It is the companion to [`civ4-files.md`](civ4-files.md) (the C2C
source side) and [`ported-terrain-art-system.md`](ported-terrain-art-system.md) (the current bake).

**Where the depot is.** The *Civ VI SDK Assets* Steam depot is mounted at a gitignored dev-only
junction: `C:\Code\eos\.civ6-cache` → `…\steamapps\common\Sid Meier's Civilization VI SDK Assets`.
It is **intermediate ("cooked") art only — no tools, no gameplay DB, no UI-DB** (`ReadMe.txt`:
"only intermediate assets"). ~65k files. Like `data/civ4/`, it is **dev-time build input only** —
nothing at runtime reads it; bakers turn it into the committed `web/assets`.

**Governing policy (owner's call, 2026-07-12): Civ6-first, C2C-fallback, per entity.** For every art
entity (terrain, feature, resource, improvement, …) use the Civ6 asset **if one exists**; otherwise keep
the current Civ4/C2C art for that specific entity. The two pipelines coexist — the bake resolves Civ6
first and falls through to the existing C2C path per key, so C2C-only content (exotic flora, synthetic
terrains, most of the 292 techs) simply retains its Civ4 art.

**Feasibility settled (2026-07-12).** Both gates from the first draft are resolved: (1) `web/dds.mjs`
now decodes Civ6's uncompressed DDS (§2a — implemented + unit-tested, proven end-to-end to WebP); (2)
the UI-icon question is answered — **resource icons and yield symbols ARE in the depot**, only per-tech
subject icons are not (§2b).

**The build plan** (decisions locked, phasing, per-category bake) lives in
[`civ6-art-replacement.md`](civ6-art-replacement.md). This doc is the *reference*; that one is the *plan*.

---

## 1. Layout

Three parallel "pantry" trees, each with the same subfolders
(`Animations, ArtDefs, Assets, Behaviors, DSGs, EnvironmentLights, Geometries, LightRigs, Lights,
Materials, ParticleEffects, Textures, XLPs`):

| Path | What |
| --- | --- |
| `Civ6/pantry/` | **Base game — the fullest tree; look here first.** Also holds `Civ6.Art.xml`, `Civ6.cfg`, `Civ6.prj`. |
| `pantry/` (top level) | Near-duplicate of the base pantry (older/partial). Prefer `Civ6/pantry`. |
| `Civ6/DLC/{Shared,Expansion1,Expansion2,CivRoyaleScenario}/pantry/` | Expansion/DLC content (new resources, features like Volcano, new improvements). Same subfolder shape; `Shared` has no ArtDefs, Exp1/Exp2 do. |
| `*-asset-deps.json` (root) | Build **dependency graph** (built-path → Perforce `.max/.ma` sources). Provenance only — **not** a name→file lookup. |

**Naming is flat and human-readable** (no hashing). Files sit in one flat namespace per folder, keyed
by strongly-typed prefixes:

| Prefix | Meaning | Example |
| --- | --- | --- |
| `SV_` / `StrategicView_` / `Features_` | **2D strategic-view sprites** (the flat-tile set — our primary source) | `SV_TerrainHexGrasslands_Color.dds`, `Features_Forest_Visible.dds` |
| `FOW_Ground_` | tileable base-terrain ground texture (fog-desaturated) | `FOW_Ground_Desert.dds` |
| `RES_` | resource 3D map art (geometry + textures) | `RES_Sheep_A.dds`, `RES_Wheat_Tuft01.geo` |
| `IMP_` / `LM_` | improvement / landmark art | `IMP_FarmTile_00_AN_A.geo` |
| `FEATURE_` / `Feature_` | terrain-feature 3D art | `FEATURE_Oasis.ast` |
| `DIS_` | district art | `DIS_ENT_Zoo_Grass_B.dds` |

Texture **channel suffixes**: `_B`/`_A`/`_albedo` (albedo), `_N` (normal), `_G` (gloss), `_M` (mask/metal),
`_S` (spec), `_H` (height), `_ao`, `_Color` (SV base), `_FOW` (fogged variant). **Resolution suffixes**:
`_512`, `_1k`, `_2k`, `_2048`. Every texture is a pair: `Name.dds` (pixels) + `Name.tex` (engine meta) —
read the `.dds`, ignore the `.tex`.

Counts (base `Civ6/pantry/Textures`, verified): 8,720 `.dds`; `SV_*`=120, `RES_*`=217, `IMP_*`=184.

---

## 2. File formats — feasibility (verified against headers)

### 2a. DDS — **uncompressed; `web/dds.mjs` now decodes it** ✅
Civ6 `.dds` are **classic uncompressed** surfaces, **not** DXT/FourCC. Verified pixelformat block of
`SV_TerrainHexGrasslands_Color.dds`: `dwFlags=0x41` (`DDPF_RGB|DDPF_ALPHAPIXELS`), `dwFourCC=0`,
`dwRGBBitCount=32`, masks `R=0x000000FF G=0x0000FF00 B=0x00FF0000 A=0xFF000000` → **R8G8B8A8**.
Single-channel maps (e.g. `RES_Sheep_A`) are `dwRGBBitCount=8`, `R=0xFF` only (L8).

**Implemented.** [`web/dds.mjs`](../web/dds.mjs) `decodeUncompressed()` reads `dwRGBBitCount` + the four
bitmasks and reorders bytes for any 8/16/24/32-bit layout (RGBA / BGRA / XRGB / L8-luminance).
`decodeDds` routes non-FourCC surfaces there instead of throwing. Covered by
[`web/dds.test.mjs`](../web/dds.test.mjs) (`node --test`, hermetic — synthesizes DDS buffers, no cache
dependency). Proven end-to-end: real `SV_*`/`Features_*`/`RES_*`/`Resources*` tiles decode → WebP via
sharp with correct colors.

> **Baker gotcha — alpha in `_Color`/`_Visible` tiles is a detail/edge mask, not opacity.**
> `SV_TerrainHexGrasslands_Color` is 91% low-alpha yet its **RGB carries the full painterly grass
> texture** (tufts included). A **terrain** baker should take **RGB and force alpha opaque** (the tile
> is the ground). A **feature/resource sprite** baker keeps alpha (there it *is* the cutout mask —
> forest canopy ~18% opaque, oasis ~24%). Rule of thumb: ground tiles → drop alpha; overlays → keep it.

### 2b. XLP — **XML alias index, not a binary blob**
`.xlp` ("large package") files are **plain XML**: `<AssetObjects..XLP>` with `m_ClassName`
(`StrategicView_Sprite`, `UITexture`, …), `m_PackageName`, and `m_Entries` → `<Element>` pairs of
`m_EntryID` (logical name) → `m_ObjectName` (texture object). They are the **name-resolution table**.

- **All XLP objects resolve to loose DDS — verified, `UITexture` included.** The object name is the
  file basename in `Textures/`. e.g. `StrategicView_Features.xlp` `Feature_Forest_Visible` →
  `Textures/Features_Forest_Visible.dds`; and (checked) `UI_Tree.xlp` `TechTree_GearButton` →
  `Textures/TechTree_GearButton.dds`, `Icons.xlp` `Stats25`/`PromotionsSmall`/`Adjacency128` → loose
  `.dds`. **No proprietary unpacking** — the XLP is just a name index.
- **Resource icons & yield symbols ARE present (b resolved):**
  - **Resource icon atlases**: `Resources.dds`, `Resources256/50/38/32.dds` (256/50/38/32-px cells;
    `Resources256.dds` is 2048² = 8×8×256px), plus `XP1_Resources*` / expansion sheets. Beautiful,
    complete, with Civ6's class-color backings (yellow=bonus, purple=luxury, red=strategic).
  - **Clean per-resource sprites** (no atlas index needed): `Resources_<Name>_Visible.dds` /
    `_Revealed.dds` (~94², e.g. `Resources_Wheat_Visible.dds`, `Resources_Copper_Visible.dds`).
  - **Yield/inline symbols**: `FontIcons.dds` (242×550 atlas) + `FontIcons16.dds` — the Civ6 analogue
    of C2C's GameFont HAMMER/GOLD/BEAKER glyphs.
- **The one true gap: per-tech *subject* icons.** `UI_Tree.xlp` contains only tech-tree **chrome**
  (gear buttons, meters, path connectors, timeline pips, key/legend tiles) — **no `ICON_TECH_*`**. A
  cache-wide search for tech subject art found none (the 107 `*Icon*.dds` are all frames/slots/backings).
  Civ6's per-tech icons live in the shipped base-game UI package, excluded from this depot. **Techs stay
  C2C** (and Civ6's ~87-tech set could not cover C2C's ~292 anyway — per the Civ6-first/C2C-fallback policy).

### 2c. Other formats (context)
`.geo`/`.gr2` = geometry (3D meshes), `.mtl` = material, `.ast` = asset binding (ties a `.geo`+`.mtl`+
textures), `.anm`/`.fgx` = animation, `.artdef` = art-definition index (§4), `.tex` = texture meta.
For a **2D** viewer we mostly ignore geometry; where only a 3D model exists (some resources/improvements),
render its `_B` albedo or bake the mesh to a sprite (cf. the shelved Civ4 NIF-bake, `docs/bonus-sprite-bake.md`).

---

## 3. The 2D "drop-in" set (primary source for a flat tile game)

`Civ6/pantry/Textures/` ships a **complete ready-made 2D hex art set** (the game's strategic-view mode).
These are flat, top-down, and directly bakeable — the cleanest replacement source:

- **Base terrain tiles** (`_Color` + `_FOW` each): `SV_TerrainHex{Grasslands,Plains,Desert,Tundra,Snow,Coast,Ocean}_Color.dds` — **7 base terrains only** (Civ6's whole land palette is Grassland/Plains/Desert/Tundra/Snow; Coast/Ocean water).
- **Hills** (3 variants/terrain): `SV_Sprites_Hills{Grasslands,Plains,Desert,Snow,Tundra}_Color_1..3.dds` (+ single `SV_Sprites_Hills_Color_<Name>_Hill_1.dds`).
- **Mountains**: `SV_TerrainMountain_<Name>_Color_01..06.dds`, `Features_Mountains_Visible.dds`.
- **Features** (`_Visible` + `_Revealed`): `Features_{Forest,Jungle,Marsh,Oasis,Floodplains,Icecaps}_Visible.dds`, plus `StrategicView_Terrain_{Forest,Jungle}.dds`.
- **Resources / improvements / routes 2D**: indexed by `StrategicView_{Features,Improvements,Routes,TerrainTypes}.xlp` and `Icons.xlp`; resolve the object name → loose `.dds` (fall back to the `RES_/IMP_` model albedo when no 2D sprite exists).

3D in-world alternative (higher fidelity, needs mesh render/albedo pull): `Grass_B/_G/_N.dds`,
`FOW_Ground_*.dds`, and the `RES_*`/`IMP_*`/`FEATURE_*` model+texture sets in `Geometries/`+`Textures/`+`Assets/`.

---

## 4. Addressing scheme — Civ4/CivStudio concept → Civ6 art file

1. **Map the concept to the Civ6 gameplay enum**: grass→`TERRAIN_GRASS`, forest→`FEATURE_FOREST`,
   wheat→`RESOURCE_WHEAT`, farm→`IMPROVEMENT_FARM`, road→`ROUTE_*_ROAD`.
2. **Open the matching artdef** in `Civ6/pantry/ArtDefs/` — the 46 `.artdef` files are the authoritative
   index (XML, keyed by the exact gameplay enum). Relevant ones: `Terrains`, `Features`, `Resources`,
   `Improvements`, `Routes`, `Farms` (crop×era combinatorics), `StrategicView` (→ the 2D `SV_*`/`Features_*`
   sprite names), `TerrainStyle`, `Minimap`, `FOW`, `Overlay`, `IconReferences` (logical icon → sized
   sprite alias). (Full 46: Appeal, Buildings, Camera, Cities, CityGenerators, Civilizations, Clutter,
   Cultures, Districts, Eras, FallbackLeaders, Farms, Features, FOW, GameLighting, GenericObject,
   GoodyHuts, GraphicsTweaks, IconReferences, Improvements, Landmarks, Leaders, Lenses, Minimap, Overlay,
   Resources, Routes, SkyBox, StrategicView, Terrains, TerrainStyle, UIPreview, Unit_Bins, UnitActivities,
   UnitOperations, Units_Great_People, Units, UserInterface, VFX, Walls, Water, WaterMaterials, Wave,
   WonderMovie, WorldViewRoutes.)
3. **For 2D (our case)**: follow the element's StrategicView reference → an `SV_*`/`Features_*` name →
   loose `.dds` in `Textures/`. **Fast path** (naming is predictable, skip the artdef):
   `SV_TerrainHex<Name>_Color.dds`, `SV_Sprites_Hills<Name>_Color_1..3.dds`, `Features_<Name>_Visible.dds`.
4. **For 3D**: element `Xref3DName`/`XrefName` → `.ast` in `Assets/` → `.geo`(Geometries)+`.mtl`(Materials)
   +`RES_/IMP_/FEATURE_*` textures; use the `_B` albedo for a flat render.
5. **Expansion content**: repeat under `Civ6/DLC/Expansion1|Expansion2/pantry/ArtDefs/`.

---

## 5. CivStudio art categories → Civ6 replacement source

The C2C side is fully inventoried in [`civ4-files.md`](civ4-files.md) / `ported-terrain-art-system.md`.
Mapping each baked `web/assets/*` category to its Civ6 source (and CivStudio's fine-grained set to Civ6's
coarser one — **this is where "some won't exist" bites**):

| CivStudio category (web asset) | CivStudio items | Civ6 source | Fidelity / gap |
| --- | --- | --- | --- |
| **Terrain** (`terrain/terrain-tiles.webp`, `terrain.webp`) | 16 land + 8 water + 9 synthetic keys | **In-world 2k ground** `Grass_B`/`Grass_Dark_B`/`FOW_Ground_*` (Grass, Plains, Desert, Tundra, Snow, Ice, Marsh, Floodplains, Salt, Cliff), recolored to display color | **Decided (Varied fold):** GRASSLAND→Grass, LUSH→Grass_Dark, PLAINS/SCRUB→Plains, MARSH/MUDDY→Marsh, ROCKY/BADLAND/JAGGED→Cliff, DESERT/DUNES/BARREN→Desert, SALT_FLATS→Salt, TAIGA/TUNDRA→Tundra, PERMAFROST→Snow. Synthetic 9 → nearest ground + recolor (existing machinery). Reuse `terrainDisplayColors()`; swap only the source texture. |
| **Hills / relief** | (relief ladder) | `SV_Sprites_Hills<Name>_Color_*`, `SV_TerrainMountain_*` | Good coverage for the 5 land bases. |
| **Water** (`water/{sea,shore,ice,river}.webp`, seaBands) | sea/shore ripple, ice, river ribbon | `Features_Icecaps_Visible.dds` (ice) | **Decided:** ice → Civ6; **river/sea/shore kept C2C** (Civ6 rivers are edge decals, no tile equivalent). |
| **Features / trees** (`trees/trees-{leafy,palm,swamp,bamboo,cactus,grass,city}.webp`) | FOREST, FOREST_ANCIENT, JUNGLE, BAMBOO, SAVANNA, VERY_TALL_GRASS, CACTUS, OASIS, SWAMP + city | `Features_{Forest,Jungle,Marsh,Oasis,Floodplains,Icecaps}_Visible.dds` (flat SV overlays) | **Decided (flat SV overlays):** FOREST/FOREST_ANCIENT→Forest, JUNGLE, SWAMP→Marsh, OASIS, FLOOD_PLAINS, ICE become one flat hex overlay per plot (new draw path). C2C-only flora (BAMBOO, CACTUS, VERY_TALL_GRASS, SAVANNA) **keep C2C billboards** — hybrid. |
| **Bonuses/resources** (`icons/bonus-icons.webp`) | 106 `BONUS_*` (GameFont cells) | **`Resources_<Name>_Visible.dds`** (clean per-resource) or `Resources256.dds` atlas cells | **Decided (Mixed):** **45 → Civ6** (34 Direct + 5 Close + gems→Diamonds + shellfish→Crabs), **61 → C2C**. Per-bonus split in §8. |
| **Yields/symbols** (GameFont HAMMER/GOLD/BEAKER) | production, gold, beaker glyphs | **`FontIcons.dds`** (+ `FontIcons16.dds`) | ✅ Present — Civ6 inline-symbol sheet; a clean upgrade path for the GameFont-derived glyphs. |
| **Techs** (`tech/tech-icons.webp`) | ~292 tech buttons | — (per-tech subject icons **absent** from depot) | ❌ **Keep C2C.** `UI_Tree.xlp` is chrome-only; no `ICON_TECH_*` in the depot, and Civ6's ~87 techs ≪ C2C's 292. Full C2C fallback. |
| **Improvements / routes** (data-only today) | 12 improvements, 350 route models | flat SV overlays exist only for `StrategicView_Improvements_{Farm,Mine,Quarry,Harbor}`; rest 3D-only | **Decided (in scope, greenfield):** bake Farm/Mine/Quarry SV overlays + a new frontend improvement layer; the rest (pasture, plantation, cottage-line, lumbermill, camp, winery) and **routes** are **deferred** (no flat SV; log the gap). |

**Takeaway.** Terrain, hills, features, **resource icons**, and improvements all have **excellent** Civ6
2D coverage via the `SV_*` / `Features_*` / `Resources_*` loose-DDS set (no unpacking; `dds.mjs` now reads
these). Under the **Civ6-first/C2C-fallback** policy the residual C2C-served set is small and well-defined:
per-**tech** icons (absent from the depot), C2C-specific **flora** (bamboo, cactus, tall-grass, ancient/
fey/blood forests, mushroom), the 9 **synthetic terrains**, the **river ribbon** (Civ6 rivers are edge
decals, not a tile), and the ~C2C-only exotic **resources**. Everything else upgrades to Civ6.

---

## 6. Key paths to bookmark

- `.civ6-cache/Civ6/pantry/ArtDefs/` — 46 `.artdef` indices (the authoritative enum→art map).
- `.civ6-cache/Civ6/pantry/Textures/` — all loose `.dds` incl. the `SV_*` / `Features_*` 2D tile set.
- `.civ6-cache/Civ6/pantry/XLPs/` — name-resolution + UI/strategic-view atlases (`StrategicView_*.xlp`,
  `Icons.xlp`, `UI_Tree.xlp`, `InWorld.xlp`, `FOWSprites.xlp`, `tilebases.xlp`).
- `.civ6-cache/Civ6/pantry/Geometries/`, `…/Assets/`, `…/Materials/` — 3D fallback.
- `.civ6-cache/Civ6/DLC/Expansion1|Expansion2/pantry/` — expansion art.

## 7. Status & remaining open questions

- ✅ **`dds.mjs` uncompressed path** — done (§2a): implemented, unit-tested, proven decode→WebP.
- ✅ **UI-icon pixels** — resolved (§2b): resource icons (`Resources_*`/`Resources256.dds`) and yield
  symbols (`FontIcons.dds`) are present; only per-tech subject icons are absent → techs stay C2C.
- ✅ **Policy** — Civ6-first, C2C-fallback per entity (owner, 2026-07-12).

Remaining before/within a build:

1. **Fine→coarse terrain mapping** — fold CivStudio's 16 land terrains onto Civ6's 7 bases. Proposed:
   pick the nearest Civ6 base per terrain, then apply the **existing** display-color recolor
   (`terrainDisplayColors()` in `web/build.mjs`) so LUSH/SCRUB/MUDDY/etc. read distinct — i.e. reuse the
   recolor machinery already used for the 9 synthetic terrains. Author variants only if recolor looks flat.
2. **Bake integration shape** — where Civ6-first resolution lives: a `civ6.mjs` sibling to `civ4.mjs`
   exposing `resolveCiv6(entity)` → loose `.dds` path (or null), with the bakers trying it before the
   C2C path. Keep the per-entity fallback explicit and logged (no silent gaps).
3. **Resource icon backing** — the atlas cells carry Civ6 class-color backings; the per-resource
   `Resources_<Name>_Visible.dds` are cleaner. Decide backing-on vs icon-only to match the CivStudio look.
4. **River ribbon** — keep the C2C-derived ribbon (Civ6 has no tile equivalent).
5. **Legal/licensing** — Firaxis SDK art redistribution terms for a public site (assets are dev-time,
   but baked derivatives ship in `web/assets`). **Owner decision required before shipping Civ6 art.**
6. **Multi-LoD bake for the zoom spine** (owner request, 2026-07-12) — bake **several detail levels per
   entity** so the continuous-zoom system ([`zoom-bands.md`](zoom-bands.md)) can cross-fade LoDs
   seamlessly instead of scaling one texture. The Civ6 depot is purpose-built for this: resources ship
   at **`Resources32/38/50/256.dds`**, terrain/ground textures carry **`_512`/`_1k`/`_2k`** variants,
   and features have distinct SV (far) vs 3D-model (near) representations. Design sketch: emit each
   `web/assets` sheet at 2–3 sizes (e.g. `bonus-icons@{32,64,256}.webp`), extend the manifest descriptor
   with a per-LoD `src`+cell size, and have the layer pick the LoD by zoom band (with a short alpha
   cross-fade across the boundary so the swap isn't visible). Applies per category — icons (atlas sizes),
   terrain tiles (`_2k`→`_512`), and the far-SV-tile ↔ near-3D-render split for features/resources.
   Keep the C2C-fallback entities single-LoD (upscaled) unless a matching C2C size exists.

---

## 8. Appendix — baked C2C bonus → Civ6 resource map

The **106 baked** `BONUS_*` (every key in `generated/bonuses.json`; all resolve to a GameFont cell →
`icons/bonus-icons.webp`) mapped to the **Civ6 resource roster** (`Resources.artdef`: 46 base gameplay
resources + Amber/Olives/Turtles from expansions; each has a `Resources_<Name>_Visible.dds` sprite and a
`Resources*.dds` atlas cell). Tiers: **D** direct same-resource · **C** close/renamed stand-in · **A**
approximate substitute (imperfect) · **—** no Civ6 equivalent (**keep C2C**, per the fallback policy).

**Final source split — Mixed collapse policy (owner, 2026-07-12):** Civ6 art for all **34 D + 5 C** plus
the **6 collapse-approved** approximates where the C2C icons are near-duplicates — gems (RUBIES,
SAPPHIRES, TURQUOISE → Diamonds) and shellfish (CLAM, LOBSTER, SHRIMP → Crabs) = **45 Civ6**. The other
**61 stay C2C**: the 48 no-match plus the **13 distinctive approximates kept on C2C** (BEAVERS, BISON,
DONKEY, HENNA, INDIGO, MUREX, MUSHROOMS, NATRON, RABBIT, RESIN, SULPHUR, VANILLA, WALRUS). The Tier column
below is the *conceptual* match; the A-tier rows split per this policy.

| C2C bonus | Civ6 | Tier | | C2C bonus | Civ6 | Tier |
| --- | --- | :-: | --- | --- | --- | :-: |
| ALMONDS | — | — | | MANGO | — | — |
| AMBER | Amber | D | | MANGANESE | — | — |
| ANCIENT_RELICS | Antiquity Site | C | | MARBLE | Marble | D |
| APPLE | — | — | | MELONS | — | — |
| BANANA | Bananas | D | | METHANE_ICE | — | — |
| BARLEY | — | — | | MUREX | Dyes | A |
| BAUXITE_ORE | Aluminum | C | | MUSHROOMS | Truffles | A |
| BEAVERS | Furs | A | | NATRON | Salt | A |
| BISON | Cattle | A | | NATURAL_GAS | — | — |
| CAMEL | — | — | | OBSIDIAN | — | — |
| CLAM | Crabs | A | | OIL | Oil | D |
| COAL | Coal | D | | OLIVES | Olives | D |
| COCA | — | — | | OPIUM | — | — |
| COCOA | Cocoa | D | | PAPAYA | — | — |
| COCONUT | — | — | | PAPYRUS | — | — |
| COFFEE | Coffee | D | | PARROTS | — | — |
| COPPER_ORE | Copper | D | | PEARLS | Pearls | D |
| CORN | — | — | | PEYOTE | — | — |
| COTTON | Cotton | D | | PIG | — | — |
| COW | Cattle | D | | PISTACHIO | — | — |
| CRAB | Crabs | D | | PLATINUM_ORE | — | — |
| DATES | — | — | | POMEGRANATE | — | — |
| DEER | Deer | D | | POTATOES | — | — |
| DIAMOND | Diamonds | D | | POULTRY | — | — |
| DONKEY | Horses | A | | PRICKLY_PEAR | — | — |
| ELEPHANTS | Ivory | C | | PRIME_TIMBER | — | — |
| FIG | — | — | | PUMPKIN | — | — |
| FINE_CLAY | — | — | | RABBIT | Furs | A |
| FISH | Fish | D | | RESIN | Amber | A |
| FLAX | — | — | | RICE | Rice | D |
| FOSSIL_BEDS | — | — | | RUBBER | — | — |
| GEODE | — | — | | RUBIES | Diamonds | A |
| GEOTHERMAL_ENERGY | — (feature) | — | | SALT | Salt | D |
| GOLD_ORE | Gold | D | | SAPPHIRES | Diamonds | A |
| GRAPES | Wine | C | | SEA_LION_AND_SEAL | — | — |
| GUAVAS | — | — | | SHEEP | Sheep | D |
| GUINEA_PIGS | — | — | | SHRIMP | Crabs | A |
| HEMP | — | — | | SILK | Silk | D |
| HENNA | Dyes | A | | SILVER_ORE | Silver | D |
| HORSE | Horses | D | | SPICES | Spices | D |
| HYDROTHERMAL_VENT | — | — | | SQUASH | — | — |
| INCENSE | Incense | D | | STONE | Stone | D |
| INDIGO | Dyes | A | | SUGAR | Sugar | D |
| IRON_ORE | Iron | D | | SULPHUR | Niter | A |
| JADE | Jade | D | | TEA | Tea | D |
| KANGAROO | — | — | | TIN_ORE | — | — |
| KAVA | — | — | | TITANIUM_ORE | — | — |
| LEAD_ORE | — | — | | TOBACCO | Tobacco | D |
| LEMONS | Citrus | C | | TURQUOISE | Diamonds | A |
| LLAMA | — | — | | URANIUM | Uranium | D |
| LOBSTER | Crabs | A | | VANILLA | Spices | A |
| MAMMOTH | — | — | | WALRUS | Ivory | A |
| | | | | WHALE | Whales | D |
| | | | | WHEAT | Wheat | D |

**Civ6 resources with no C2C-bake counterpart** (available but currently unused): Gypsum, Mercury,
Shipwreck, Turtles (plus Foxes = the Furs art model). Per the Mixed policy the gem/shellfish collapses
are accepted (rubies/sapphires/turquoise→Diamonds, clam/lobster/shrimp→Crabs); the Dyes (henna/indigo/
murex), Furs (beavers/rabbit) and Ivory (walrus) approximates were judged distinctive enough to **keep on
C2C** rather than collapse. Build path in [`civ6-art-replacement.md`](civ6-art-replacement.md) §B.

---

*Written 2026-07-12 from a two-sided exploration (C2C consumption side + Civ6 depot side), verified
against on-disk headers (DDS pixelformat, XLP XML, artdef/texture listings). Feasibility gates closed the
same day: `web/dds.mjs` extended for uncompressed DDS (+`web/dds.test.mjs`, proven decode→WebP), and the
UI-icon question resolved (resource icons + yield symbols present; per-tech icons absent → C2C fallback).
Governing policy: Civ6-first, C2C-fallback per entity. Companion to [`civ4-files.md`](civ4-files.md); when
a Civ6 bake ships, cross-link it here and add a one-line pointer in `CLAUDE.md`.*
