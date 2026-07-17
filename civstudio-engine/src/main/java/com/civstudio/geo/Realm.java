package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A <b>realm</b> of the planet Halann: a partition of the imported world map into the separate
 * <em>maps</em> Anbennar's modders faked onto one EU4 cylinder. There are three real realms plus
 * {@link #NONE} (a province that belongs to no realm and renders nowhere). See {@code docs/realms.md}.
 *
 * <ul>
 * <li>{@link #HALCANN} — the Old World: {@code europe}/{@code asia}/{@code africa} and the
 *     {@code serpentspine} underworld. Playable. Named for Anbennar's <i>earth-center</i> — the
 *     antonym of Aelantir — while {@link #HINUILANDS Halann} stays the planet's own name.</li>
 * <li>{@link #AELANTIR} — the New World: both Americas ({@code north_america}/{@code south_america},
 *     one landmass across a real ocean). Playable.</li>
 * <li>{@link #HINUILANDS} — {@code oceania}: two provinces Anbennar painted of a realm it reserved
 *     245 for. Viewable only, not playable — no route reaches it.</li>
 * <li>{@link #NONE} — no realm: the three projection/ice quirks and the deep-ocean provinces that
 *     touch no land. They keep their id, neighbours, plots and (for land) settleability, but belong
 *     to no map and are fogged everywhere. This is a real member — the thing the settleable filter
 *     tests — not an oversight.</li>
 * </ul>
 *
 * <p>Realm is <em>resolved at export</em> ({@link com.civstudio.geo.export.RealmExporter}) as a pure
 * function of the map — {@link Continent} for land, adjacent land for water — and stamped onto
 * {@code provinces.json} as {@link Province#realm()}, mirrored into the web bundle. It is <b>not</b>
 * re-derived on the client: the exporter is the single source of truth (two of its rules are graph
 * walks, not table lookups). Like {@link Continent} it is a small fixed taxonomy, so an {@code enum}.
 *
 * <p>Realm is orthogonal to <b>z</b> (the underworld plane): {@code (Halcann, z=-1)} is a partition
 * <em>and</em> a plane. See {@code docs/realms.md} §Realm is not z.
 */
public enum Realm {

	HALCANN("halcann", "Halcann", true),
	AELANTIR("aelantir", "Aelantir", true),
	HINUILANDS("hinuilands", "Hinuilands", false),
	/** No realm — fogged everywhere. Its {@code raw_key} is {@code null}: an absent realm key. */
	NONE(null, "None", false);

	private final String rawKey;
	private final String displayName;
	private final boolean playable;

	Realm(String rawKey, String displayName, boolean playable) {
		this.rawKey = rawKey;
		this.displayName = displayName;
		this.playable = playable;
	}

	/** The stable {@code raw_key} (e.g. {@code "halcann"}); {@code null} for {@link #NONE}. */
	@JsonValue
	public String rawKey() {
		return rawKey;
	}

	/** The display name (e.g. {@code "Halcann"}). */
	public String displayName() {
		return displayName;
	}

	/**
	 * Whether a colony may be founded into this realm — {@code true} for {@link #HALCANN}/{@link
	 * #AELANTIR}, {@code false} for {@link #HINUILANDS} (view-only) and {@link #NONE}. A realm's
	 * playability gates the settleable/site set (see {@code TimelineSites}).
	 */
	public boolean isPlayable() {
		return playable;
	}

	/**
	 * The realm for a {@code raw_key}, for Jackson and consumers. A {@code null} key is {@link #NONE}
	 * (an absent realm), matching the {@code null} {@link #rawKey()} the exporter stamps for a
	 * realm-less province.
	 *
	 * @param key a realm {@code raw_key} (e.g. {@code "halcann"}), or {@code null}
	 * @return the matching realm; {@link #NONE} if {@code key} is {@code null}
	 * @throws IllegalArgumentException if {@code key} is non-null but unknown
	 */
	@JsonCreator
	public static Realm fromKey(String key) {
		if (key == null)
			return NONE;
		for (Realm r : values())
			if (key.equals(r.rawKey))
				return r;
		throw new IllegalArgumentException("unknown realm key: " + key);
	}

	/**
	 * The realm a land province with this {@link Continent} belongs to (rule 1 of {@code
	 * docs/realms.md} §The model): both Americas → {@link #AELANTIR}, {@code oceania} → {@link
	 * #HINUILANDS}, the rest → {@link #HALCANN}. A {@code null} continent is {@link #NONE}.
	 *
	 * @param c the land province's continent, or {@code null}
	 * @return the resolved realm
	 */
	public static Realm fromContinent(Continent c) {
		if (c == null)
			return NONE;
		return switch (c) {
			case NORTH_AMERICA, SOUTH_AMERICA -> AELANTIR;
			case OCEANIA -> HINUILANDS;
			case EUROPE, ASIA, AFRICA, SERPENTSPINE -> HALCANN;
		};
	}
}
