package com.civstudio.server.dev;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.civstudio.server.CivStudioProperties;

import jakarta.annotation.PreDestroy;

/**
 * Local-development convenience: once the server is <b>fully started</b> (the context is ready and
 * {@link com.civstudio.server.DemoSessionSeeder} has founded the demo session), serve the {@code
 * web/} site with a small zero-dependency node static server ({@code web/dev-server.mjs}), pointed at
 * this server for {@code window.BUNDLE} (?live=…), and <b>log its URL</b>. This turns
 * a plain {@code mvn spring-boot:run} into a one-command "run the whole thing locally" — the goal
 * being to debug the real map site against a live local server with no manual `npx serve` step.
 *
 * <p>It is gated to the {@code dev} Spring profile, which the {@code spring-boot-maven-plugin} run
 * goal activates (see the server {@code pom.xml}) — so it never ships in the packaged production
 * jar. Everything here works <b>with no internet connection</b>: the node server has no
 * dependencies, and the engine resolves its mod sources from the local caches (the {@code
 * .anbennar-cache}/{@code .civ4-cache} junctions), so run Maven offline ({@code mvn -o …}).
 *
 * <p>The node process is a child of this JVM: it is destroyed on shutdown ({@link #stop()}), and it
 * also self-terminates when its stdin closes (the JVM exiting), so no orphan is left listening on
 * the web port.
 */
@Component
@Profile("dev")
public class DevFrontendLauncher {

	private static final Logger log = LoggerFactory.getLogger(DevFrontendLauncher.class);

	/** Emitted by {@code dev-server.mjs} on its listen callback — we log the URL only once it answers. */
	private static final String READY_MARKER = "DEV-SERVER-READY";

	private final CivStudioProperties.Dev.Frontend cfg;

	/** The server's actual bound HTTP port, captured from {@link WebServerInitializedEvent}. */
	private volatile int serverPort;

	private volatile Process node;

	public DevFrontendLauncher(CivStudioProperties props) {
		this.cfg = props.getDev().getFrontend();
	}

	/** Capture the real port (handles {@code server.port=0}) before the app-ready launch. */
	@EventListener
	void onWebServerReady(WebServerInitializedEvent event) {
		this.serverPort = event.getWebServer().getPort();
	}

	@EventListener
	void onApplicationReady(ApplicationReadyEvent event) {
		if (!cfg.isEnabled()) {
			log.info("dev frontend disabled (civstudio.dev.frontend.enabled=false)");
			return;
		}
		Path script = Path.of("web", "dev-server.mjs").toAbsolutePath();
		if (!Files.exists(script)) {
			log.warn("dev frontend: {} not found (run from the repo root) — skipping", script);
			return;
		}
		try {
			launch(script);
		} catch (IOException e) {
			log.warn("dev frontend: could not start node ('{}') — is it on PATH? {}",
					cfg.getNode(), e.getMessage());
		}
	}

	private void launch(Path script) throws IOException {
		int webPort = cfg.getWebPort();
		ProcessBuilder pb = new ProcessBuilder(
				cfg.getNode(), script.toString(), "--port", Integer.toString(webPort))
				.redirectErrorStream(true);
		// serve the web/ folder relative to the working dir (the repo root under spring-boot:run)
		pb.environment().put("WEB_ROOT", script.getParent().toString());
		Process p = pb.start();
		this.node = p;

		String url = "http://localhost:" + webPort + openUrlPath(webPort);
		CountDownLatch ready = new CountDownLatch(1);
		Thread pump = new Thread(() -> pumpOutput(p, ready), "dev-frontend-node");
		pump.setDaemon(true);
		pump.start();

		// Wait for node to report it is listening, so the URL we print is one that actually answers
		// (fall back after a short timeout rather than hanging on a missed marker).
		try {
			if (!ready.await(10, TimeUnit.SECONDS))
				log.warn("dev frontend: node did not report ready within 10s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (!p.isAlive()) {
			log.warn("dev frontend: node exited before it was ready");
			return;
		}
		// Printed, not opened: starting a server should not seize the screen. Open it yourself, or
		// leave a tab on it across restarts — which is the usual case anyway.
		log.info("dev frontend live: {}", url);
	}

	// The path+query to open, from civstudio.dev.frontend.open-path, with {live}/{server}/{webPort}
	// substituted. {live} is this server's base URL (what the page's ?live= expects). Lets a test run
	// land on a webverify-style deep link (?p=&z=&live=…#none).
	private String openUrlPath(int webPort) {
		String live = "http://localhost:" + serverPort;
		return cfg.getOpenPath()
				.replace("{live}", live)
				.replace("{server}", Integer.toString(serverPort))
				.replace("{webPort}", Integer.toString(webPort));
	}

	/** Relay node's output to the log and trip the latch on the ready marker. */
	private void pumpOutput(Process p, CountDownLatch ready) {
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.startsWith(READY_MARKER))
					ready.countDown();
				else
					log.info("[dev-server] {}", line);
			}
		} catch (IOException ignored) {
			// stream closed on process exit
		} finally {
			ready.countDown();
		}
	}

	@PreDestroy
	void stop() {
		Process p = this.node;
		if (p != null && p.isAlive()) {
			p.destroy();
			try {
				if (!p.waitFor(3, TimeUnit.SECONDS))
					p.destroyForcibly();
			} catch (InterruptedException e) {
				p.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}
	}
}
