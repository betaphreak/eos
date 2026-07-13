package com.civstudio.server.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.agent.Household;
import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.PersonDetail;
import com.civstudio.server.render.PersonProjections;
import com.civstudio.settlement.Settlement;

/**
 * Serves a court member's character sheet at {@code GET
 * /api/sessions/{sid}/person/{id}} — the advisor rail's on-demand person +
 * household detail (see {@code docs/privy-council.md} §2b). Read-only (no
 * auth gate, like the SSE feed); resolves the agent id against the session's
 * <b>POV colony</b> (its first colony — the leader colony the advisor UI is
 * scoped to), since agent ids are unique only within a colony.
 */
@RestController
public class PersonController {

	private final SessionHost host;

	public PersonController(SessionHost host) {
		this.host = host;
	}

	@GetMapping("/api/sessions/{sid}/person/{id}")
	public ResponseEntity<PersonDetail> person(@PathVariable String sid,
			@PathVariable int id) {
		HostedSession hs = host.get(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		List<Settlement> colonies = hs.colonies();
		if (colonies.isEmpty())
			return ResponseEntity.notFound().build();
		Settlement pov = colonies.get(0);
		Household h = pov.getHouseholdById(id);
		if (h == null)
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok(PersonProjections.of(h, pov.getDate()));
	}
}
