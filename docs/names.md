# Names

Every person carries a race-keyed given name + dynasty surname (see `com.civstudio.name`:
`NameTable`, `DynastyPool`, `NameRegistry`). There are two sources, split by whether the names
are unique to eos or come from Anbennar.

## Human — hand-authored, committed

The **only** hand-authored, committed name set is **human**, at `civstudio-engine/src/main/resources/human-names/`
(`dynasty.json`, `female.json`, `male.json` — the big real-world corpora, e.g. the ~150k-surname
`dynasty.json`). Humans are not an Anbennar culture, so their names can't be generated — they ship in
the repo and load from the classpath (`/human-names/…`).

## Every other race — generated on demand from EU4

All non-human races' names are **generated from the Anbennar `common/cultures/anb_cultures.txt`** (each
top-level culture group's `dynasty_names` / `male_names` / `female_names` blocks, merged per race with a
synthesized Zipfian common→rare rarity curve). They are **not committed** — produced on first use and
cached, mirroring the per-province plot-on-demand model:

- **`RaceNameGenerator`** — turns a race id's Anbennar block into the tiered, weighted tables the
  `NameTable` loads. A race absent from the source, or with fewer than 10 names in any kind, is
  *sparse* → the caller falls back to the human tables.
- **`NameStore`** — the on-demand cache. `table(raceId, kind)` returns the cached
  `generated/names/<race>/<kind>.json`, generating (and writing) the race's three files on a miss;
  returns `null` for a non-generatable race (→ human fallback). Per-race locked, so concurrent colony
  threads generate a race once. The cache dir defaults to the engine module's `generated/names/`
  (writable + gitignored when run from source) and is overridable for the server via
  `civstudio.names.cacheDir` / `CIVSTUDIO_NAMES_CACHE_DIR`.
- **`GameSession`** wires it: `Race.HUMAN` tables load eagerly from `/human-names/`; `givenNames` /
  `dynastyPool` route every other race through `NameStore`, falling back to the human table when a race
  isn't generatable. Reproducibility is unaffected — generation is a pure function of the pinned
  `anb_cultures.txt`, and each race's surname pool is shuffled on its own decorrelated
  `RngSeed.forDynastyPool(race)` stream.

The Anbennar source is fetched on demand by `com.civstudio.data.AnbennarFiles` (pinned by
`anbennar-source.lock`), so a cold run fetches `anb_cultures.txt` once, then generates from the cache.

> History note: the 57 non-human race folders (and the old `AnbennarNameExporter` dev tool that
> committed them under `names/<race>/`) were removed and purged from git history; only `human-names/`
> remains committed. `harimari` — previously hand-curated — is now generated like the rest (its clan
> epithets are in the Anbennar source, so nothing is lost).
