package com.civstudio.server.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.render.SessionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises the SSE transport end to end: found a session on the host, start the {@link
 * FeedServer} on an ephemeral port, subscribe over HTTP, and assert real snapshot frames
 * arrive and advance (see {@code docs/client-server.md}, Phase A).
 */
class FeedServerTest {

	private static final int DHENIJANSAR = 4411;
	private final ObjectMapper json = new ObjectMapper();

	// read the next "data: {…}" SSE line's payload, skipping blanks/comments
	private static String nextData(BufferedReader r) throws IOException {
		String line;
		while ((line = r.readLine()) != null)
			if (line.startsWith("data: "))
				return line.substring("data: ".length());
		throw new IOException("stream ended before a data frame");
	}

	@Test
	@Timeout(90)
	void streamsSnapshotFramesOverSse() throws Exception {
		SessionHost host = new SessionHost();
		HostedSession hs = host.create(SessionSpec.caravanDemo(7654321L, DHENIJANSAR));
		hs.startPaused(); // emits the tick-0 snapshot; a subscriber gets it immediately

		FeedServer server = new FeedServer(host, 0);
		server.start();
		HttpClient client = HttpClient.newHttpClient();
		try {
			// the session list reports the hosted session
			HttpResponse<String> list = client.send(
					HttpRequest.newBuilder(uri(server, "/api/sessions")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(200, list.statusCode());
			assertTrue(list.body().contains(hs.id()), "the session should be listed");

			// subscribe to the SSE stream
			HttpResponse<java.io.InputStream> stream = client.send(
					HttpRequest.newBuilder(uri(server, "/api/sessions/" + hs.id() + "/stream"))
							.timeout(Duration.ofSeconds(30)).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			assertEquals(200, stream.statusCode());
			assertTrue(stream.headers().firstValue("Content-Type").orElse("")
					.startsWith("text/event-stream"));

			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
				// first frame is the cached tick-0 snapshot pushed on subscribe
				SessionSnapshot first = json.readValue(nextData(r), SessionSnapshot.class);
				assertEquals(hs.id(), first.sessionId());
				assertEquals(6, first.caravans().size(), "the demo streams six caravans");
				assertEquals(1, first.colonies().size());

				// stepping produces a fresh frame at a later tick
				hs.step(2);
				SessionSnapshot next;
				do {
					next = json.readValue(nextData(r), SessionSnapshot.class);
				} while (next.tick() <= first.tick());
				assertTrue(next.tick() >= 1, "the streamed tick advances after stepping");
			}
		} finally {
			hs.stop();
			server.stop();
		}
	}

	private static URI uri(FeedServer server, String path) {
		return URI.create("http://localhost:" + server.port() + path);
	}
}
