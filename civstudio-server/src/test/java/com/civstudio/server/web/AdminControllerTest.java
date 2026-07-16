package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The admin console's endpoints ({@code /api/admin/**}) are gated on {@code ROLE_ADMIN} (the
 * {@code civstudio.auth.admins} allow-list; see {@code docs/admin-console.md}): an allow-listed
 * admin may call them, an authenticated non-admin and an anonymous caller get {@code 403}.
 * Identity is supplied through the dev {@code X-CivStudio-User} header (trusted here), exactly as
 * {@link SessionOwnershipTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"civstudio.demo.enabled=false",
		"civstudio.auth.trust-dev-user-header=true",
		"civstudio.auth.admins=super-admin" })
class AdminControllerTest {

	@LocalServerPort
	int port;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	@Timeout(120)
	void adminEndpointsRequireTheAdminRole() throws Exception {
		// the plot-cache drop: admin only
		assertEquals(403, post("/api/admin/plots/clear", null), "anonymous is forbidden");
		assertEquals(403, post("/api/admin/plots/clear", "bob"), "a plain authenticated user is forbidden");
		HttpResponse<String> ok = send("POST", "/api/admin/plots/clear", "super-admin");
		assertEquals(200, ok.statusCode(), "an allow-listed admin may drop the cache");
		assertTrue(ok.body().contains("cleared"), "the drop reports how many grids it removed");

		// the status readout: admin only too
		assertEquals(403, get("/api/admin/status", "bob"));
		HttpResponse<String> status = send("GET", "/api/admin/status", "super-admin");
		assertEquals(200, status.statusCode());
		assertTrue(status.body().contains("\"plots\"") && status.body().contains("\"server\""),
				"status carries the plot-cache + server readout");

		// the read-only region->country map: admin only, and carries the mapping + count
		assertEquals(403, get("/api/admin/region-map", "bob"));
		HttpResponse<String> map = send("GET", "/api/admin/region-map", "super-admin");
		assertEquals(200, map.statusCode());
		assertTrue(map.body().contains("\"entries\"") && map.body().contains("\"count\"")
				&& map.body().contains("\"region\""), "region-map carries the entries + count");
	}

	private int post(String path, String user) throws Exception {
		return send("POST", path, user).statusCode();
	}

	private int get(String path, String user) throws Exception {
		return send("GET", path, user).statusCode();
	}

	private HttpResponse<String> send(String method, String path, String user) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.noBody());
		if (user != null)
			b.header(CurrentUserResolver.DEV_USER_HEADER, user);
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}
}
