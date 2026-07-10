package com.civstudio.geo;

/**
 * The shared shape of a geographic grouping above the {@link Province}: an
 * {@link Area}, {@link Region}, {@link SuperRegion} or {@link Continent}. Each
 * has a stable {@code raw_key} identity and a human-readable display name, so the
 * {@link WorldMap} and its callers can treat any tier uniformly.
 * <p>
 * {@link #displayName()} (rather than {@code name()}) is used for the readable
 * name because {@link Continent} is an {@code enum}, whose {@link Enum#name()} is
 * {@code final} and returns the constant identifier; the record tiers implement it
 * as an alias of their {@code name} component.
 */
public interface GeoTier {

	/** The stable {@code raw_key} identity (e.g. {@code "rahen_coast_region"}). */
	String rawKey();

	/** The human-readable display name (e.g. {@code "Rahen Coast"}). */
	String displayName();
}
