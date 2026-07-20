package com.civstudio.server.web;

import java.util.List;

import com.civstudio.server.HostedSession;
import com.civstudio.settlement.Settlement;

/**
 * Resolving <i>which</i> colony a session-scoped read is about — one rule, in one place.
 * <p>
 * The per-colony detail endpoints ({@link ColonyController}, {@link PersonController}) were written
 * when a run meant one colony, so they read {@code colonies().get(0)} directly. A Timeline is many
 * colonies in one run, which made everything past the first seat invisible over HTTP. Both now route
 * through here so "no name means the POV colony" is stated once rather than twice.
 */
final class Colonies {

	private Colonies() {
	}

	/**
	 * The colony a request names, or the POV colony when it names none.
	 * <p>
	 * The POV colony is the run's <b>first</b> colony — what the top-bar vitals and the advisor UI are
	 * scoped to — so omitting the parameter keeps the existing web clients working unchanged.
	 *
	 * @param hs   the session
	 * @param name the colony name, or {@code null}/blank for the POV colony
	 * @return the colony, or {@code null} if the run has none or the name matches none (the caller
	 *         turns that into a 404 — an unknown colony is not the POV colony by default, since
	 *         silently substituting one would answer a question nobody asked)
	 */
	static Settlement resolve(HostedSession hs, String name) {
		if (name != null && !name.isBlank())
			return hs.colonyByName(name);
		List<Settlement> colonies = hs.colonies();
		return colonies.isEmpty() ? null : colonies.get(0);
	}
}
