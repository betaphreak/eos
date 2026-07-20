package com.civstudio.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.agent.Household;
import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.PersonDetail;
import com.civstudio.server.render.PersonProjections;
import com.civstudio.settlement.Settlement;

/**
 * Serves a court member's character sheet at {@code GET /api/sessions/{sid}/person/{id}} — the
 * advisor rail's on-demand person + household detail (see {@code docs/privy-council.md} §2b).
 * Read-only (no auth gate, like the SSE feed).
 *
 * <p>Agent ids are unique only <b>within</b> a colony, so the id is resolved against the session's
 * POV colony (its first) unless {@code ?colony=} names another — the same rule as {@link
 * ColonyController}, shared via {@link Colonies}. Without it a Timeline's non-first seats had no
 * reachable people at all, and an id that happened to collide would resolve to the wrong person.
 */
@RestController
public class PersonController {

	private final SessionHost host;

	public PersonController(SessionHost host) {
		this.host = host;
	}

	@GetMapping("/api/sessions/{sid}/person/{id}")
	public ResponseEntity<PersonDetail> person(@PathVariable String sid, @PathVariable int id,
			@RequestParam(required = false) String colony) {
		HostedSession hs = host.getOrRestore(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		Settlement target = Colonies.resolve(hs, colony);
		if (target == null)
			return ResponseEntity.notFound().build();
		Household h = target.getHouseholdById(id);
		if (h == null)
			return ResponseEntity.notFound().build();
		String culture = target.getProvince() == null ? null : target.getProvince().culture();
		return ResponseEntity.ok(PersonProjections.of(h, target.getDate(), culture));
	}
}
