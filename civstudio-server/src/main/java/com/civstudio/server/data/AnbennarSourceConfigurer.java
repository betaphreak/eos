package com.civstudio.server.data;

import org.springframework.stereotype.Component;

import com.civstudio.data.AnbennarFiles;
import com.civstudio.server.CivStudioProperties;

/**
 * Pushes the Spring-bound {@code civstudio.anbennar.*} config into the engine's {@link AnbennarFiles}
 * provider at startup, so a deployment can override the source host / cache location / token (and,
 * for testing, the ref). Configured in the constructor — which runs during context refresh, before
 * any {@code ApplicationRunner} (notably {@code DemoSessionSeeder}) founds a session and triggers an
 * on-demand raster fetch. A blank {@code ref} leaves the committed {@code anbennar-source.lock}
 * in effect (the single source of truth); see {@code docs/anbennar-files.md}.
 */
@Component
public class AnbennarSourceConfigurer {

	public AnbennarSourceConfigurer(CivStudioProperties props) {
		CivStudioProperties.Anbennar a = props.getAnbennar();
		AnbennarFiles.configure(a.getBaseUrl(), a.getRef(), a.getCacheDir(), a.getToken());
	}
}
