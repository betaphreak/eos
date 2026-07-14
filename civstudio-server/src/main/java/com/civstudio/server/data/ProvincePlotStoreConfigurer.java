package com.civstudio.server.data;

import org.springframework.stereotype.Component;

import com.civstudio.server.CivStudioProperties;
import com.civstudio.settlement.ProvincePlotStore;

/**
 * Points the engine's {@link ProvincePlotStore} (the sim's per-province plot-field cache used by
 * colony founding and caravan crossings) at the same {@code civstudio.plots.cache-dir} the server's
 * on-demand {@link com.civstudio.server.web.PlotService} uses — so the sim and the web plot feed
 * share <b>one</b> cache ({@code <cache-dir>/v<GEN_VERSION>}) instead of the sim writing a second
 * copy into the source tree. Mirrors {@link AnbennarSourceConfigurer}: configured in the
 * constructor, which runs during context refresh, before {@code DemoSessionSeeder} founds a session
 * and triggers plot generation. In prod this is the persistent volume, so a province is generated
 * once ever and reused by both. See {@code docs/plot-serving.md}.
 */
@Component
public class ProvincePlotStoreConfigurer {

	public ProvincePlotStoreConfigurer(CivStudioProperties props) {
		ProvincePlotStore.configure(props.getPlots().getCacheDir());
	}
}
