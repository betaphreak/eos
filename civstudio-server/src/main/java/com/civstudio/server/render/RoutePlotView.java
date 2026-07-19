package com.civstudio.server.render;

/**
 * One routed plot — a plot that carries a {@link com.civstudio.geo.RouteType route} (a trail a band
 * pioneered, a paved road), projected as the client needs it: the plot's global raster position
 * {@code (x, y)} (the same coordinate space the web plot feed uses, so the client matches it to a
 * loaded plot) and the {@code ROUTE_*} type key (which the client maps to a draw tier via {@code
 * BUNDLE.routes.byType}).
 *
 * <p>{@code Plot.routeType} is per-session mutable and excluded from the static plot cache, so it
 * travels on a live channel. Two carry it (see {@code docs/route-rendering.md} §Viewport-windowed
 * route persistence): the legacy per-band snapshot broadcast ({@code SessionSnapshot.routePlots},
 * being retired) and the viewport-windowed feed ({@code GET /api/sessions/{id}/routes/{provinceId}}
 * → {@link ProvinceRoutes}), which serves the whole standing layer of one province on demand. This
 * record is the element of both.
 *
 * @param x    the plot's global raster x (matches the web feed's plot {@code x})
 * @param y    the plot's global raster y
 * @param type the {@code ROUTE_*} type key (e.g. {@code ROUTE_TRAIL})
 */
public record RoutePlotView(int x, int y, String type) {
}
