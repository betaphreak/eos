package com.civstudio.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.civstudio.server.SessionHost;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the Steam OpenID 2.0 sign-in end to end (see {@code docs/authentication.md}, phase 2)
 * with the network call stubbed: login redirects to Steam, the verified return establishes an
 * authenticated session (a JSESSIONID cookie), {@code /api/auth/me} reflects it, and — the point
 * — a session founded while signed in is owned by that user, so only the signed-in client (its
 * cookie) can command it while an anonymous client is forbidden. The demo is disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "civstudio.demo.enabled=false")
@Import(SteamAuthTest.StubSteamConfig.class)
class SteamAuthTest {

	private static final int DHENIJANSAR = 4411;
	private static final String SCENARIO = "steam-auth-test";
	private static final String STEAM_ID = "76561198000000001";

	/** Replaces the real HTTP Steam client with one that "verifies" to a fixed SteamID64. */
	@TestConfiguration
	static class StubSteamConfig {
		@Bean
		@Primary
		SteamOpenId stubSteamOpenId() {
			return new SteamOpenId() {
				@Override
				public String authenticationRequestUrl(String realm, String returnTo) {
					return "https://steamcommunity.com/openid/login?openid.mode=checkid_setup"
							+ "&openid.return_to=" + returnTo;
				}

				@Override
				public Optional<String> verify(Map<String, String> params) {
					return Optional.of(STEAM_ID);
				}
			};
		}
	}

	@LocalServerPort
	int port;

	@Autowired
	SessionHost host;

	private final ObjectMapper json = new ObjectMapper();

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	@Test
	@Timeout(120)
	void providersListsSteamOnlyWhenNoOidcConfigured() throws Exception {
		HttpClient c = client();
		JsonNode body = json.readTree(
				c.send(get(c, "/api/auth/providers"), HttpResponse.BodyHandlers.ofString()).body());
		var names = new java.util.ArrayList<String>();
		body.get("providers").forEach(n -> names.add(n.asString()));
		assertTrue(names.contains("steam"), "steam is always offered");
		assertFalse(names.contains("google"), "google is absent unless configured");
	}

	@Test
	@Timeout(120)
	void loginRedirectsToSteam() throws Exception {
		HttpClient c = client();
		HttpResponse<String> res = c.send(get(c, "/api/auth/steam/login"),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, res.statusCode());
		assertTrue(res.headers().firstValue("Location").orElse("")
				.startsWith("https://steamcommunity.com/openid/login"), "should redirect to Steam");
	}

	@Test
	@Timeout(120)
	void signInEstablishesOwnershipAnonymousCannotCommand() throws Exception {
		HttpClient signedIn = client();

		// the Steam callback (params ignored by the stub verifier) → authenticated session cookie
		HttpResponse<String> ret = signedIn.send(
				get(signedIn, "/api/auth/steam/return?openid.mode=id_res&openid.claimed_id="
						+ "https://steamcommunity.com/openid/id/" + STEAM_ID),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, ret.statusCode());

		// /me now reflects the signed-in Steam user
		JsonNode me = json.readTree(
				signedIn.send(get(signedIn, "/api/auth/me"), HttpResponse.BodyHandlers.ofString())
						.body());
		assertTrue(me.get("authenticated").asBoolean(), "should be authenticated after sign-in");
		assertEquals(AppUser.STEAM, me.get("provider").asString());
		assertEquals(STEAM_ID, me.get("displayName").asString());

		// found a session while signed in — it is owned by this user
		HttpResponse<String> created = signedIn.send(
				post(signedIn, "/api/sessions", "{\"seed\":9001,\"scenario\":\"" + SCENARIO
						+ "\",\"provinceId\":" + DHENIJANSAR + "}"),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(201, created.statusCode());
		String id = json.readTree(created.body()).get("id").asString();

		// the owner (its cookie carries the session) may control it; an anonymous client may not
		// (control requires authentication → 401)
		assertEquals(200, signedIn.send(post(signedIn, "/api/sessions/" + id + "/control",
				"{\"action\":\"pause\"}"), HttpResponse.BodyHandlers.ofString()).statusCode());
		HttpClient anon = client();
		assertEquals(401, anon.send(post(anon, "/api/sessions/" + id + "/control",
				"{\"action\":\"pause\"}"), HttpResponse.BodyHandlers.ofString()).statusCode());

		// logout drops the session; /me is anonymous again
		assertEquals(204, signedIn.send(post(signedIn, "/api/auth/logout", ""),
				HttpResponse.BodyHandlers.ofString()).statusCode());
		JsonNode after = json.readTree(
				signedIn.send(get(signedIn, "/api/auth/me"), HttpResponse.BodyHandlers.ofString())
						.body());
		assertFalse(after.get("authenticated").asBoolean(), "should be anonymous after logout");
	}

	// a fresh client with its own cookie jar and no auto-redirect (so 302s are observable)
	private HttpClient client() {
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
				.cookieHandler(new CookieManager()).build();
	}

	private HttpRequest get(HttpClient c, String path) {
		return HttpRequest.newBuilder(uri(path)).GET().build();
	}

	private HttpRequest post(HttpClient c, String path, String body) {
		return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
