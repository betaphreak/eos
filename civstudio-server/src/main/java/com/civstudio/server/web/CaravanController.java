package com.civstudio.server.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.agent.Caravan;
import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.CaravanDetail;
import com.civstudio.server.render.CaravanProjections;
import com.civstudio.settlement.Settlement;

/**
 * Serves one wandering band's <b>composition</b> at {@code GET /api/sessions/{sid}/caravan/{id}} —
 * the rail panel a spectator opens by clicking the band's map icon (band-average skill profile + the
 * survival-sorted roster). Read-only, no auth gate — like the SSE feed and {@link PersonController}.
 *
 * <p>Resolves the band by its stable {@link Caravan#getId() id} across the session's wandering bands
 * ({@code getCaravans()}) and each colony's outstanding explorer levies ({@code getExcursions()}) —
 * the same two sets {@code Snapshots} projects. Served {@code no-cache} (a band's makeup changes as
 * it marches).
 */
@RestController
public class CaravanController {

	private final SessionHost host;

	public CaravanController(SessionHost host) {
		this.host = host;
	}

	@GetMapping("/api/sessions/{sid}/caravan/{id}")
	public ResponseEntity<CaravanDetail> caravan(@PathVariable String sid, @PathVariable long id) {
		HostedSession hs = host.get(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		Caravan band = findBand(hs, id);
		if (band == null)
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok().cacheControl(CacheControl.noCache())
				.body(CaravanProjections.of(band, hs.date()));
	}

	// the band with this id among the session's wandering bands, then each colony's explorer levies
	// (colony-owned excursions, not in the session's caravan list); null if none matches
	private static Caravan findBand(HostedSession hs, long id) {
		for (Caravan b : hs.session().getCaravans())
			if (b.getId() == id)
				return b;
		for (Settlement c : hs.colonies())
			for (ExplorerCaravan e : c.getExcursions())
				if (e.getId() == id)
					return e;
		return null;
	}
}
