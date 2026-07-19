package com.civstudio.server.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.ColonyDetail;
import com.civstudio.server.render.ColonyProjections;
import com.civstudio.settlement.Settlement;

/**
 * Serves the POV colony's <b>composition</b> at {@code GET /api/sessions/{sid}/colony} — the rail
 * panel a spectator opens by clicking one of the live vitals figures in the top bar (a colony-average
 * skill profile + the full household roster). Read-only, no auth gate — like the SSE feed and {@link
 * CaravanController}/{@link PersonController}.
 *
 * <p>Resolves the session's <b>POV colony</b> (its first colony — the one the top-bar vitals and the
 * advisor UI are scoped to). Served {@code no-cache} (the colony's makeup changes as it lives).
 */
@RestController
public class ColonyController {

	private final SessionHost host;

	public ColonyController(SessionHost host) {
		this.host = host;
	}

	@GetMapping("/api/sessions/{sid}/colony")
	public ResponseEntity<ColonyDetail> colony(@PathVariable String sid) {
		HostedSession hs = host.get(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		List<Settlement> colonies = hs.colonies();
		if (colonies.isEmpty())
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok().cacheControl(CacheControl.noCache())
				.body(ColonyProjections.of(colonies.get(0)));
	}
}
