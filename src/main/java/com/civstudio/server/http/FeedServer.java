package com.civstudio.server.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.render.SessionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The thin-client transport for the spectator server (see {@code docs/client-server.md},
 * Phase A): a dependency-free {@link HttpServer} that streams a session's render {@link
 * SessionSnapshot snapshots} to the browser over <b>Server-Sent Events</b> and takes control
 * (pause/resume/step/rate) and command input as plain HTTP. SSE (not WebSocket) is the right
 * fit for a spectator — a one-way server→client push — and keeps the zero-runtime-dependency
 * ethos (only the JDK plus the already-present Jackson).
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET  /}                          — the live demo page ({@code web/live.html}).</li>
 * <li>{@code GET  /api/sessions}              — list the hosted sessions.</li>
 * <li>{@code POST /api/sessions}              — found+start a session from a spec.</li>
 * <li>{@code GET  /api/sessions/{id}/stream}  — subscribe to the snapshot feed (SSE).</li>
 * <li>{@code POST /api/sessions/{id}/control} — {@code {action:pause|resume|step|rate|stop}}.</li>
 * <li>{@code POST /api/sessions/{id}/commands}— submit a command (the Phase-B seam).</li>
 * </ul>
 * A slow spectator never stalls the sim: each SSE connection has its own bounded queue that
 * the session thread offers into (dropping the oldest frame if the client falls behind),
 * while the connection's own (virtual) thread drains it to the socket.
 */
public final class FeedServer {

	// per-connection SSE buffer depth; if a client falls this far behind, the oldest frames
	// are dropped rather than blocking the session thread
	private static final int SSE_QUEUE_CAPACITY = 64;

	private final SessionHost host;
	private final HttpServer http;
	private final ObjectMapper json = new ObjectMapper();

	/**
	 * Create a server bound to {@code port} (0 picks an ephemeral port) over {@code host}.
	 *
	 * @param host the session registry to serve
	 * @param port the TCP port (0 = ephemeral)
	 * @throws IOException if the server socket cannot be opened
	 */
	public FeedServer(SessionHost host, int port) throws IOException {
		this.host = host;
		this.http = HttpServer.create(new InetSocketAddress(port), 0);
		// a virtual thread per request — long-lived SSE connections are cheap this way
		this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		this.http.createContext("/", this::dispatch);
	}

	/** Start accepting connections. */
	public void start() {
		http.start();
	}

	/** Stop the server (does not stop the sessions — see {@link SessionHost#stopAll()}). */
	public void stop() {
		http.stop(0);
	}

	/** The bound port (useful when the server was created on port 0). */
	public int port() {
		return http.getAddress().getPort();
	}

	// route a request by method + path
	private void dispatch(HttpExchange ex) throws IOException {
		try {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			if (path.equals("/") || path.equals("/live.html")) {
				servePage(ex);
			} else if (path.equals("/api/sessions") && method.equals("GET")) {
				listSessions(ex);
			} else if (path.equals("/api/sessions") && method.equals("POST")) {
				createSession(ex);
			} else if (path.startsWith("/api/sessions/") && path.endsWith("/stream")
					&& method.equals("GET")) {
				streamSession(ex, idFrom(path, "/stream"));
			} else if (path.startsWith("/api/sessions/") && path.endsWith("/control")
					&& method.equals("POST")) {
				controlSession(ex, idFrom(path, "/control"));
			} else if (path.startsWith("/api/sessions/") && path.endsWith("/commands")
					&& method.equals("POST")) {
				sendJson(ex, 202, Map.of("accepted", true)); // Phase-B seam (no-op today)
			} else {
				sendJson(ex, 404, Map.of("error", "not found: " + method + " " + path));
			}
		} catch (RuntimeException e) {
			sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
		} finally {
			ex.close();
		}
	}

	// the {id} between "/api/sessions/" and the trailing "/<verb>"
	private static String idFrom(String path, String suffix) {
		return path.substring("/api/sessions/".length(), path.length() - suffix.length());
	}

	private void servePage(HttpExchange ex) throws IOException {
		Path page = Path.of("web", "live.html");
		if (!Files.exists(page)) {
			sendJson(ex, 404, Map.of("error", "web/live.html not found (run from repo root)"));
			return;
		}
		byte[] body = Files.readAllBytes(page);
		ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(body);
		}
	}

	private void listSessions(HttpExchange ex) throws IOException {
		List<Map<String, Object>> out = new ArrayList<>();
		for (HostedSession hs : host.list())
			out.add(Map.of("id", hs.id(), "scenario", hs.spec().scenario(), "seed",
					hs.spec().seed(), "state", hs.state().name(), "tick", hs.tick()));
		sendJson(ex, 200, out);
	}

	@SuppressWarnings("unchecked")
	private void createSession(HttpExchange ex) throws IOException {
		Map<String, Object> body = readJson(ex);
		long seed = ((Number) body.getOrDefault("seed", 7654321L)).longValue();
		String scenario = (String) body.getOrDefault("scenario", SessionSpec.CARAVAN_DEMO);
		int provinceId = ((Number) body.getOrDefault("provinceId", 4411)).intValue();
		HostedSession hs = host.create(new SessionSpec(seed, scenario, provinceId));
		if (hs.state() == HostedSession.State.CREATED)
			hs.start();
		sendJson(ex, 201, Map.of("id", hs.id(), "state", hs.state().name()));
	}

	private void controlSession(HttpExchange ex, String id) throws IOException {
		HostedSession hs = host.get(id);
		if (hs == null) {
			sendJson(ex, 404, Map.of("error", "no session " + id));
			return;
		}
		Map<String, Object> body = readJson(ex);
		String action = String.valueOf(body.getOrDefault("action", ""));
		switch (action) {
			case "pause" -> hs.pause();
			case "resume" -> hs.resume();
			case "step" -> hs.step(((Number) body.getOrDefault("value", 1)).intValue());
			case "rate" -> hs.setTickRateMillis(
					((Number) body.getOrDefault("value", 1000)).longValue());
			case "stop" -> hs.stop();
			default -> {
				sendJson(ex, 400, Map.of("error", "unknown action: " + action));
				return;
			}
		}
		sendJson(ex, 200, Map.of("id", id, "state", hs.state().name(), "tick", hs.tick()));
	}

	// open a Server-Sent-Events stream of the session's snapshots. The session thread offers
	// serialized frames into this connection's bounded queue; this (virtual) thread drains
	// it to the socket until the client disconnects (a write throws) or the session ends.
	private void streamSession(HttpExchange ex, String id) throws IOException {
		HostedSession hs = host.get(id);
		if (hs == null) {
			sendJson(ex, 404, Map.of("error", "no session " + id));
			return;
		}
		ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("Connection", "keep-alive");
		ex.sendResponseHeaders(200, 0); // 0 = chunked, open-ended

		BlockingQueue<String> frames = new ArrayBlockingQueue<>(SSE_QUEUE_CAPACITY);
		// runs on the session thread: serialize + enqueue, dropping the oldest frame if the
		// client has fallen behind, so a slow spectator never blocks the simulation
		AutoCloseable sub = hs.subscribe(snap -> {
			String frame = toJson(snap);
			if (frame != null && !frames.offer(frame)) {
				frames.poll();
				frames.offer(frame);
			}
		});
		try (OutputStream os = ex.getResponseBody()) {
			while (true) {
				String frame = frames.take();
				os.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
		} catch (IOException disconnected) {
			// client closed the tab — fall through to unsubscribe
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			try {
				sub.close();
			} catch (Exception ignored) {
				// unsubscribe is best-effort
			}
		}
	}

	private String toJson(SessionSnapshot snap) {
		try {
			return json.writeValueAsString(snap);
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readJson(HttpExchange ex) throws IOException {
		byte[] in = ex.getRequestBody().readAllBytes();
		if (in.length == 0)
			return Map.of();
		return json.readValue(in, Map.class);
	}

	private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
		byte[] out = json.writeValueAsBytes(body);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}
}
