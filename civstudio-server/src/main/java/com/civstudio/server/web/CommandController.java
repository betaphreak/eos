package com.civstudio.server.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.CommandLogView;
import com.civstudio.server.render.CommandProjections;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves a session's command log at {@code GET /api/sessions/{sid}/commands} — the ordered,
 * tick-stamped replay log that <i>is</i> the session's savegame (see {@code docs/client-server.md}),
 * plus how many commands are still in flight.
 * <p>
 * Until now the log was reachable only through the MCP {@code get_command_log} tool; the admin
 * session-detail page needs it over plain HTTP (see {@code docs/studio-control-plane-plan.md} §C1).
 * Both go through {@link CommandProjections}, so the two views cannot drift apart.
 * <p>
 * <b>Unlike every other session read, this one is gated</b> ({@link SessionAuthz#denyCommandLog}):
 * spectating a run is public, but the record of what its owner <em>did</em> is not.
 */
@RestController
public class CommandController {

	private final SessionHost host;
	private final SessionAuthz authz;

	public CommandController(SessionHost host, SessionAuthz authz) {
		this.host = host;
		this.authz = authz;
	}

	@GetMapping("/api/sessions/{sid}/commands")
	public ResponseEntity<?> commands(@PathVariable String sid, HttpServletRequest http) {
		// getOrRestore, not get: a recorded run's command log is exactly what survives it not being
		// loaded, so refusing to serve it for an unloaded run would withhold the one thing that is
		// certainly still there.
		HostedSession hs = host.getOrRestore(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		ResponseEntity<Object> denied = authz.denyCommandLog(hs, http);
		if (denied != null)
			return denied;
		CommandLogView view = CommandProjections.of(hs.commandLog());
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(view);
	}
}
