package com.civstudio.server;

/**
 * What <em>kind</em> of run a hosted session is — the <b>auth / visibility category</b>, and the one
 * axis that decides who may see and control it. Named explicitly (and {@linkplain
 * com.civstudio.server.registry.SessionRecord persisted}) rather than inferred from {@code (owner ==
 * null, scenario)}, which was a load-bearing coincidence: "demo = unowned" broke the moment a public
 * sandbox or a tutorial existed. See {@code docs/session-management.md}.
 *
 * <p>Kind is the <em>category</em>; the specific variant within it is the open-set {@code mode} string
 * (e.g. {@code "sandbox"}, {@code "royale"}), and the challenge is {@code difficulty} (a Civ4 handicap
 * key). Those are additive — new modes / difficulties need no new enum value here.
 *
 * <ul>
 * <li>{@link #DEMO} — the unowned shop-window run anyone may watch (the server-seeded caravan demo).</li>
 * <li>{@link #SINGLE_PLAYER} — an owned save slot; private to its owner.</li>
 * <li>{@link #MULTIPLAYER} — a shared, non-ranked run (co-op / free-play modes to come). Reserved; no
 *     founding path creates one yet.</li>
 * <li>{@link #TIMELINE} — the shared ranked Timeline (a last-colony-standing royale). Public to watch;
 *     its clock is admins-only, and its seats are owned per colony.</li>
 * </ul>
 */
public enum SessionKind {

	DEMO,
	SINGLE_PLAYER,
	MULTIPLAYER,
	TIMELINE;

	/**
	 * The kind of a run founded with this {@code owner} and {@code spec} — the single place the rule
	 * lives. A Timeline scenario is a {@link #TIMELINE} whoever owns it (the house owns the run, the
	 * seats are owned per colony); an unowned run is the {@link #DEMO}; anything else owned is a
	 * {@link #SINGLE_PLAYER} save slot. {@link #MULTIPLAYER} is not derived — it is reserved for the
	 * modes still to come, and is chosen explicitly when they land.
	 *
	 * @param owner the owning {@code app_user} id, or {@code null} for an unowned/public run
	 * @param spec  the founding spec
	 * @return the run's kind
	 */
	public static SessionKind of(String owner, SessionSpec spec) {
		if (spec != null && spec.isTimeline())
			return TIMELINE;
		return owner == null || owner.isBlank() ? DEMO : SINGLE_PLAYER;
	}

	/**
	 * The kind for a persisted {@code kind} string, tolerant of legacy rows: an unknown or absent value
	 * falls back to a derivation from {@code scenario} + {@code owner} so a row written before this
	 * column existed still classifies correctly.
	 *
	 * @param kind     the stored kind, or {@code null}
	 * @param scenario the run's scenario (the legacy signal for a Timeline)
	 * @param owner    the run's owner (the legacy signal for the demo)
	 * @return the resolved kind
	 */
	public static SessionKind fromRecord(String kind, String scenario, String owner) {
		if (kind != null && !kind.isBlank())
			try {
				return valueOf(kind);
			} catch (IllegalArgumentException ignored) {
				// an unrecognised stored value — fall through to the legacy derivation
			}
		if (SessionSpec.TIMELINE.equals(scenario))
			return TIMELINE;
		return owner == null || owner.isBlank() ? DEMO : SINGLE_PLAYER;
	}

	/**
	 * Whether anyone may watch a run of this kind — a {@link #DEMO} or a {@link #TIMELINE} (a
	 * leaderboard is for watching); an owned {@link #SINGLE_PLAYER} / {@link #MULTIPLAYER} run is
	 * private to its participants. This is the lobby-visibility rule.
	 */
	public boolean isPublic() {
		return this == DEMO || this == TIMELINE;
	}

	/** Whether a run of this kind is one of a player's finite save slots — only {@link #SINGLE_PLAYER}. */
	public boolean isSaveSlot() {
		return this == SINGLE_PLAYER;
	}

	/**
	 * The stable wire token the web client keys on ({@code "demo"}, {@code "single-player"},
	 * {@code "multiplayer"}, {@code "timeline"}) — the lowercase, hyphenated name the lobby rows used
	 * before kind was modelled, kept identical so the client is unchanged.
	 */
	public String wire() {
		return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}
}
