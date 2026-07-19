package com.civstudio.server.data;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import com.civstudio.data.BundleWorldSource;
import com.civstudio.data.ClasspathWorldSource;
import com.civstudio.data.FixtureWorldSource;
import com.civstudio.data.StrapiWorldSource;
import com.civstudio.data.WorldSources;

/**
 * Installs the engine's {@link com.civstudio.data.WorldSource} at the earliest safe point in the
 * server lifecycle — on {@link ApplicationEnvironmentPreparedEvent}, after config is resolved but
 * <b>before</b> the application context, any bean, or any engine world-data class loads. That ordering
 * is required: {@code UnitCatalog} is an eager static singleton that captures the source at class-load,
 * so a regular {@code @Component}/configurer (which runs during context refresh) could run too late.
 * Registered in {@link com.civstudio.server.ServerMain#main} via {@code SpringApplication.addListeners}
 * — so it applies to a real server boot but not to {@code @SpringBootTest} contexts (which keep the
 * classpath default, as the offline test suite wants).
 *
 * <p>Config ({@code civstudio.world-source.*}): {@code mode=classpath|strapi|fixture} — {@code strapi}
 * uses {@code url}+{@code token}, {@code fixture} uses a snapshot {@code fixture} path. Default
 * {@code classpath} keeps the committed {@code generated/*.json}, so this is inert until a deployment
 * opts in. A {@code strapi}/{@code fixture} source that can't be built fails startup loudly (no silent
 * fallback to possibly-stale classpath data).
 */
public class WorldSourceInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		apply(event.getEnvironment());
	}

	/** Install the configured source from a resolved environment (extracted for testability). */
	static void apply(ConfigurableEnvironment env) {
		String mode = env.getProperty("civstudio.world-source.mode", "classpath").trim().toLowerCase(Locale.ROOT);
		switch (mode) {
			case "strapi" -> {
				String url = env.getProperty("civstudio.world-source.url", "http://localhost:1337/api/world-bundle");
				String token = env.getProperty("civstudio.world-source.token", "");
				BundleWorldSource src = new StrapiWorldSource(URI.create(url), token);
				WorldSources.set(src);
				log("strapi " + url + version(src));
			}
			case "fixture" -> {
				String path = env.getProperty("civstudio.world-source.fixture", "");
				if (path.isBlank())
					throw new IllegalStateException(
							"civstudio.world-source.mode=fixture but civstudio.world-source.fixture is unset");
				BundleWorldSource src = new FixtureWorldSource(Path.of(path));
				WorldSources.set(src);
				log("fixture " + path + version(src));
			}
			case "classpath" -> {
				WorldSources.set(new ClasspathWorldSource());
				log("classpath (committed generated/*.json)");
			}
			default -> throw new IllegalStateException("unknown civstudio.world-source.mode: " + mode);
		}
	}

	// The content-version a bundle source booted at — recorded so a run's world snapshot is traceable
	// (reproducibility = seed + content-version + command log).
	private static String version(BundleWorldSource src) {
		return " (mapVersion=" + src.mapVersion() + ", contentVersion=" + src.contentVersion() + ")";
	}

	private static void log(String desc) {
		// The logging system isn't fully initialized at this early phase — stderr is the reliable channel.
		System.err.println("[WorldSource] engine world data ← " + desc);
	}
}
