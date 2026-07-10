package com.civstudio.server;

import java.util.concurrent.CountDownLatch;

import com.civstudio.server.http.FeedServer;

/**
 * Launches the Phase-A spectator server: hosts the caravan-demo session and streams it live
 * to the browser (see {@code docs/client-server.md}). Run it, then open the printed URL to
 * watch the six caravans march over the world map.
 *
 * <pre>
 *   mvn -q compile exec:exec -Dsim.main=com.civstudio.server.ServerMain
 *   # then open http://localhost:8080/
 * </pre>
 *
 * Optional args: {@code [port] [seed] [provinceId]}.
 */
public final class ServerMain {

	private static final int DEFAULT_PORT = 8080;
	private static final long DEFAULT_SEED = 7654321L;
	private static final int DEFAULT_PROVINCE = 4411; // Dhenijansar (the default demo home)

	private ServerMain() {
	}

	public static void main(String[] args) throws Exception {
		// port precedence: CLI arg > $PORT (the container-ingress convention) > default
		int port = args.length > 0 ? Integer.parseInt(args[0]) : envInt("PORT", DEFAULT_PORT);
		long seed = args.length > 1 ? Long.parseLong(args[1]) : DEFAULT_SEED;
		int province = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PROVINCE;

		SessionHost host = new SessionHost();
		FeedServer server = new FeedServer(host, port);
		server.start();

		HostedSession session = host.create(SessionSpec.caravanDemo(seed, province));
		session.setTickRateMillis(1000); // the server ticks ~one in-game day per second
		session.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			host.stopAll();
			server.stop();
		}));

		System.out.println("CivStudio spectator server up:");
		System.out.println("  open   http://localhost:" + server.port() + "/");
		System.out.println("  session " + session.id() + " (6 caravans marching, ~1 day/sec)");
		new CountDownLatch(1).await(); // park the main thread; the server + session run on
		// their own threads until the JVM is stopped
	}

	// read an int environment variable, falling back to def when unset or unparseable
	private static int envInt(String name, int def) {
		String v = System.getenv(name);
		if (v == null || v.isBlank())
			return def;
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
