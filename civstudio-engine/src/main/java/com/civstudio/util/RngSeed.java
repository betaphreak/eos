package com.civstudio.util;

/**
 * The seed a {@link com.civstudio.settlement.GameSession} derives all its random
 * streams from, and the one place the decorrelation salts live. A session draws
 * many <em>independent</em> streams off a single seed — the economic stream, the
 * naming/mortality/skill/terrain streams, the wandering-band stream — and they
 * must stay <b>mutually decorrelated</b> (one stream's draws never predict
 * another's) yet <b>reproducible</b> (the same seed yields an identical run). This
 * class encapsulates how: each stream is the seed XOR-ed with a fixed per-{@link
 * Stream} salt, and a per-colony stream is additionally XOR-ed with the colony's
 * index times a colony salt (so colonies of one session don't interleave their
 * streams either).
 * <p>
 * The salts are arbitrary fixed 64-bit constants chosen only to be well-spread;
 * their <em>values</em> are load-bearing for reproducibility (changing one shifts
 * that stream for every run), so they are pinned here. {@link Stream#ECONOMIC} has
 * salt {@code 0} on purpose: a lone colony (index 0) then draws its economics off
 * the bare seed, exactly as a single shared generator would.
 */
public final class RngSeed {

	// decorrelates the colonies of one session: a per-colony stream is salted by
	// the colony's index times this, so colony 0 is unsalted (bare seed per stream)
	// and later colonies get distinct, decorrelated streams.
	private static final long COLONY_SALT = 0xA24BAED4963EE407L;

	// the dynasty-pool surname shuffle is derived off the NAME stream with two
	// further salts: one decorrelating the one-time pool shuffle from any colony's
	// name draws, one (times the race ordinal) decorrelating each race's pool from
	// the others. HUMAN (ordinal 0) uses no race salt, so its pool is unchanged.
	private static final long DYNASTY_SHUFFLE_SALT = 0x2545F4914F6CDD1DL;
	private static final long RACE_POOL_SALT = 0x3C79AC492BA7B653L;

	// decorrelates a per-province stream (the shared province plot field, generated
	// once per province and claimed by every settlement in it) by the province id,
	// kept apart from the per-colony streams.
	private static final long PROVINCE_SALT = 0x8EBC6AF09C88C6E3L;

	/**
	 * A decorrelated random stream a session derives off its seed. Each carries the
	 * fixed salt that separates it from the others; {@link #ECONOMIC} is salt-free so
	 * a single-colony run draws its economics off the bare seed (byte-identical to a
	 * lone shared generator).
	 */
	public enum Stream {
		/** The economic stream (firm/market/laborer decisions). Salt-free by design. */
		ECONOMIC(0L),
		/** Names: given-name and surname draws (never perturbs the economic stream). */
		NAME(0x9E3779B97F4A7C15L),
		/** Mortality: per-step old-age death draws. */
		MORTALITY(0xD1B54A32D192ED03L),
		/** Skill: a person's starting skills and gender. */
		SKILL(0xBF58476D1CE4E5B9L),
		/** Terrain: procedural plot-terrain generation. */
		TERRAIN(0x6A09E667F3BCC909L),
		/** The session-level wandering-band stream (caravan movement / settle decisions). */
		BAND(0x94D049BB133111EBL),
		/** Per-colony explorer-levy stream (ExplorerCaravan muster/march — docs/explorer-caravan.md). */
		EXCURSION(0xC2B2AE3D27D4EB4FL);

		private final long salt;

		Stream(long salt) {
			this.salt = salt;
		}

		private long salt() {
			return salt;
		}
	}

	private final long seed;

	/**
	 * Create a seed deriver for the given session seed.
	 *
	 * @param seed the session's random-number seed
	 */
	public RngSeed(long seed) {
		this.seed = seed;
	}

	/** The underlying session seed. */
	public long seed() {
		return seed;
	}

	/**
	 * A <b>per-colony</b> stream: decorrelated by both the stream kind and the
	 * colony's index, so colonies of one session run on independent streams. Colony
	 * 0 of the {@link Stream#ECONOMIC} stream is the bare seed (byte-identical to a
	 * single shared generator).
	 *
	 * @param stream      the stream kind
	 * @param colonyIndex the colony's index within the session (0-based)
	 * @return a fresh generator for that stream of that colony
	 */
	public Rng forColony(Stream stream, int colonyIndex) {
		return new Rng(seed ^ stream.salt() ^ (COLONY_SALT * colonyIndex));
	}

	/**
	 * A <b>per-province</b> stream: decorrelated by the province id, for state a
	 * province generates once and shares across every settlement founded into it (the
	 * province plot field — see {@code docs/province-plots.md}). Kept apart from the
	 * per-colony streams so a province's terrain is the same regardless of which
	 * colony first builds its field.
	 *
	 * @param stream     the stream kind (e.g. {@link Stream#TERRAIN})
	 * @param provinceId the province's id
	 * @return a fresh generator for that stream of that province
	 */
	public Rng forProvince(Stream stream, int provinceId) {
		return new Rng(seed ^ stream.salt() ^ (PROVINCE_SALT * provinceId));
	}

	/**
	 * A <b>seed-independent</b> per-province stream — decorrelated by the province id and
	 * stream salt but <em>not</em> the session seed, so every run of every seed generates
	 * the <b>same canonical field</b> for a province. This is what lets the province plot
	 * field be persisted once and reused by all runs (see {@code
	 * ProvincePlotStore} / {@code docs/province-plots.md}); a province's geography is a
	 * property of the map, not of a particular run.
	 *
	 * @param stream     the stream kind (e.g. {@link Stream#TERRAIN})
	 * @param provinceId the province's id
	 * @return a fresh generator seeded from the province id and stream alone
	 */
	public Rng forProvinceCanonical(Stream stream, int provinceId) {
		return new Rng(stream.salt() ^ (PROVINCE_SALT * provinceId));
	}

	/**
	 * A <b>session-level</b> stream — one shared across the session, not
	 * per-colony (e.g. {@link Stream#BAND}, the wandering bands).
	 *
	 * @param stream the stream kind
	 * @return a fresh generator for that session-level stream
	 */
	public Rng forSession(Stream stream) {
		return new Rng(seed ^ stream.salt());
	}

	/**
	 * The surname-pool shuffle stream for a race — the {@link Stream#NAME} stream
	 * composed with the dynasty-shuffle salt and a per-race salt (so each race's
	 * pool is shuffled independently). HUMAN (ordinal 0) takes no race salt, so its
	 * pool is built exactly as a pure-human session's always was.
	 *
	 * @param raceOrdinal the race's enum ordinal (HUMAN is 0)
	 * @return a fresh generator for that race's surname-pool shuffle
	 */
	public Rng forDynastyPool(int raceOrdinal) {
		return new Rng(seed ^ Stream.NAME.salt() ^ DYNASTY_SHUFFLE_SALT
				^ (RACE_POOL_SALT * raceOrdinal));
	}
}
