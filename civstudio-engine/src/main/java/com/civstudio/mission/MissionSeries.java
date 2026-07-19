package com.civstudio.mission;

import java.util.List;

/**
 * One Anbennar mission <b>series</b> — a country's tree in one of the five mission slots, imported
 * from {@code missions/*.txt} by {@link com.civstudio.mission.export.MissionExporter}. This is the unit
 * the {@linkplain com.civstudio.mission campaign} layer keys on: a series' {@link #tag} is the country
 * whose flavored campaign it is (see {@code docs/campaign-selector.md}).
 *
 * @param id               the series id (e.g. {@code arakeprun1_missions})
 * @param tag              the country tag from {@code potential { tag = … }}, or {@code null} when the
 *                         series is generic ({@code always = yes}) or gated by a non-tag condition
 *                         (culture/region formables) — see {@link #potential}
 * @param slot             the mission slot (1–5), or {@code null}
 * @param generic          {@code generic = yes} — a shared mission pack rather than a nation's own tree
 * @param ai               {@code ai = yes} — the AI takes this series
 * @param hasCountryShield {@code has_country_shield = yes} — it flies a unique coat of arms
 * @param potential        the raw {@code potential} body — the full gating condition, kept verbatim for
 *                         the series whose tag could not be resolved to a single {@link #tag}
 * @param file             the source mission file (for provenance)
 * @param missions         the tree's nodes
 */
public record MissionSeries(String id, String tag, Integer slot, boolean generic, boolean ai,
		boolean hasCountryShield, String potential, String file, List<Mission> missions) {
}
