package com.civstudio.server.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.ColonyDetail;
import com.civstudio.server.render.ColonyProjections;
import com.civstudio.settlement.Settlement;

/**
 * Serves a colony's <b>composition</b> at {@code GET /api/sessions/{sid}/colony} — the rail panel a
 * spectator opens by clicking one of the live vitals figures in the top bar (a colony-average skill
 * profile + the full household roster). Read-only, no auth gate — like the SSE feed and {@link
 * CaravanController}/{@link PersonController}.
 *
 * <p>Defaults to the session's <b>POV colony</b> (its first colony — the one the top-bar vitals and
 * the advisor UI are scoped to), which is what the web rail asks for. An optional {@code ?colony=}
 * names another by name, so a <b>Timeline</b> — many colonies in one run — is inspectable rather than
 * showing only whichever seat happens to be first (see {@code docs/studio-control-plane-plan.md}
 * §C1). Served {@code no-cache} (the colony's makeup changes as it lives).
 */
@RestController
public class ColonyController {

	private final SessionHost host;

	public ColonyController(SessionHost host) {
		this.host = host;
	}

	@GetMapping("/api/sessions/{sid}/colony")
	public ResponseEntity<ColonyDetail> colony(@PathVariable String sid,
			@RequestParam(required = false) String colony) {
		HostedSession hs = host.getOrRestore(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		Settlement target = Colonies.resolve(hs, colony);
		if (target == null)
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok().cacheControl(CacheControl.noCache())
				.body(ColonyProjections.of(target));
	}
}
