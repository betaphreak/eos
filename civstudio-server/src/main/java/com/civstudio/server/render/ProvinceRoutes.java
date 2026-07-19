package com.civstudio.server.render;

import java.util.List;

/**
 * The route layer of <b>one province</b> in a session — the response of the viewport-windowed route
 * feed ({@code GET /api/sessions/{id}/routes/{provinceId}}). The client fetches this per province as
 * it enters view, then refetches only when the render snapshot names the province in its {@code
 * routeDirty} list. Because the whole standing layer of the province is served (not a per-band
 * window), a late-joining or reloading client sees the full network, not just recently-crossed plots
 * (docs/route-rendering.md §Viewport-windowed route persistence).
 *
 * @param provinceId the province this layer belongs to (echoed for the client's cache key)
 * @param rev        the province's route revision at the moment of the read ({@code
 *                   ProvincePlotPool.routeRev}); the client stores it to dedupe redundant refetches
 * @param plots      the province's routed plots — the whole standing network, in first-laid order
 */
public record ProvinceRoutes(int provinceId, int rev, List<RoutePlotView> plots) {
}
