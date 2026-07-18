package com.civstudio.server.web;

import java.io.IOException;
import java.util.List;

/**
 * The bill of materials for the world-level resources this server can hand out — the generated
 * artifacts baked into the engine/server jars ({@code src/main/resources/generated/**}) as they are
 * actually served, i.e. named by endpoint rather than by file.
 *
 * <p>Why it exists: the web viewer used to discover these one at a time, lazily, on first use — the
 * tech tree fetched {@code /api/techs} the first time you opened it, which is exactly the moment you
 * least want to wait. With a manifest the client can total the bytes up front and prefetch the eager
 * set behind the loading screen, showing real progress instead of a spinner. Serving it as data
 * rather than hard-coding the list in the page keeps the two from drifting the way
 * {@code web/build.mjs} and {@link WorldBundle} did.
 *
 * <p>{@code eager} marks what the UI needs to be interactive, so it is worth fetching before the
 * page is shown; everything else is a legitimate fetch, just not one worth blocking boot for. Sizes
 * are the ON-THE-WIRE gzip lengths (what the client actually downloads), so a progress bar built
 * from them tracks reality. They come from the same cached byte arrays the controllers serve, so
 * asking for the manifest costs one assembly at most — never a re-read per request.
 *
 * <p>Exposed over REST by {@link ResourceController} and over MCP by
 * {@code com.civstudio.server.mcp.ResourceMcpTools}.
 */
public final class ResourceManifest {

	private ResourceManifest() {
	}

	/**
	 * One servable resource.
	 *
	 * @param id     stable short key, e.g. {@code "techs"}
	 * @param path   the endpoint that serves it, e.g. {@code "/api/techs"}
	 * @param bytes  gzip-encoded length in bytes — what the client downloads
	 * @param eager  true when the UI wants it before it can be interactive (prefetch set)
	 * @param origin the generated artifact(s) behind it, for humans/LLMs reading the BOM
	 */
	public record Entry(String id, String path, int bytes, boolean eager, String origin) {
	}

	/**
	 * The full bill. Assembled per call from cached byte arrays (cheap), so it always reports the
	 * sizes this process would actually serve — including after a rebake changes them.
	 */
	public static List<Entry> entries() throws IOException {
		return List.of(
				new Entry("bundle", "/api/bundle", WorldBundle.gzip().length, true,
						"generated/map/{provinces,areas,regions,superregions,adjacencies,edges,"
								+ "portals,terrain-art,tradegoods}.json + map/borders.json"),
				new Entry("techs", "/api/techs", TechBundle.gzip().length, true,
						"generated/techs.json + generated/building-unlocks.json + techs-meta.json"),
				new Entry("buildings", "/api/buildings", BuildingBundle.gzip().length, true,
						"generated/buildings.json + buildings-meta.json"),
				new Entry("units", "/api/units", UnitBundle.gzip().length, true,
						"generated/{units,unit-combats}.json + {units,unit-combats}-meta.json"),
				new Entry("tiers", "/api/tiers", AssetController.tiersGzipLength(), true,
						"map/tierborders.json"),
				// Per-province plot grids are generated on demand and cached per province, so they have
				// no fixed size and there are ~5k of them — the viewer streams the handful in view
				// (plotfetch.mjs). Listed for completeness, never prefetched.
				new Entry("plots", "/api/plots/{id}", -1, false,
						"ProvincePlotStore — generated per province on demand, cached"));
	}

	/** Total gzip bytes of the eager set — what a boot-time prefetch will actually pull. */
	public static long eagerBytes() throws IOException {
		return entries().stream().filter(Entry::eager).mapToLong(Entry::bytes).sum();
	}
}
