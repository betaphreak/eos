package com.civstudio.server.render;

/**
 * One routed plot in a render snapshot — a plot that carries a {@link
 * com.civstudio.geo.RouteType route} (a trail a band pioneered, a paved road), projected as the
 * client needs it: the plot's global raster position {@code (x, y)} (the same coordinate space the
 * web plot feed uses, so the client matches it to a loaded plot) and the {@code ROUTE_*} type key
 * (which the client maps to a draw tier via {@code BUNDLE.routes.byType}).
 *
 * <p>This is the live half of the per-plot route data: {@code Plot.routeType} is per-session mutable
 * and excluded from the static plot cache, so it travels on the snapshot instead (gap B, {@code
 * docs/route-rendering.md}). The client accumulates these so a route persists once seen.
 *
 * @param x    the plot's global raster x (matches the web feed's plot {@code x})
 * @param y    the plot's global raster y
 * @param type the {@code ROUTE_*} type key (e.g. {@code ROUTE_TRAIL})
 */
public record RoutePlotView(int x, int y, String type) {
}
