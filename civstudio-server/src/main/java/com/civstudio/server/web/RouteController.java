package com.civstudio.server.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.ProvinceRoutes;
import com.civstudio.server.render.RoutePlotView;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.ProvincePlotPool;

/**
 * Serves one province's <b>route layer</b> for a session at {@code GET
 * /api/sessions/{sid}/routes/{provinceId}} — the viewport-windowed route feed (see {@code
 * docs/route-rendering.md} §Viewport-windowed route persistence). The client fetches a province's
 * routes as it enters view, and refetches only when the render snapshot's {@code routeDirty} list
 * names it. Read-only, no auth gate — like the SSE feed and {@link PersonController}.
 *
 * <p>Unlike the static, immutably-cached {@link PlotController} plot grid, routes are per-session
 * mutable, so this is served <b>fresh</b> ({@code no-cache}); the client dedupes redundant refetches
 * on the province's {@link ProvinceRoutes#rev()}. A province whose pool has not been built yet has
 * no routes — answered as an empty layer, never by paying the pool's generation cost.
 */
@RestController
public class RouteController {

	private final SessionHost host;

	public RouteController(SessionHost host) {
		this.host = host;
	}

	@GetMapping("/api/sessions/{sid}/routes/{provinceId}")
	public ResponseEntity<ProvinceRoutes> routes(@PathVariable String sid,
			@PathVariable int provinceId) {
		HostedSession hs = host.get(sid);
		if (hs == null)
			return ResponseEntity.notFound().build();
		ProvincePlotPool pool = hs.session().plotPoolIfPresent(provinceId);
		// no pool built for this province yet ⇒ nobody has been there ⇒ no routes. Answer empty
		// (rev 0) rather than generating the pool, which would be an expensive per-tile bake.
		if (pool == null)
			return fresh(new ProvinceRoutes(provinceId, 0, List.of()));
		// one atomic read of the layer + its rev, so a client deduping on rev never stores a rev
		// newer than the plots it holds (the sim thread may lay trails concurrently).
		ProvincePlotPool.RouteSnapshot snap = pool.routeSnapshot();
		List<RoutePlotView> plots = new ArrayList<>(snap.plots().size());
		for (Plot p : snap.plots())
			if (p.routeType() != null && p.x() >= 0 && p.y() >= 0)
				plots.add(new RoutePlotView(p.x(), p.y(), p.routeType().type()));
		return fresh(new ProvinceRoutes(provinceId, snap.rev(), plots));
	}

	private static ResponseEntity<ProvinceRoutes> fresh(ProvinceRoutes body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
	}
}
