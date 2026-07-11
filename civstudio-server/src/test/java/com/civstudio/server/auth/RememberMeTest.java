package com.civstudio.server.auth;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.civstudio.server.SessionHost;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the remember-me persistent login (see {@code docs/authentication.md}): with a signing key
 * configured, a Steam sign-in issues a {@code civstudio-remember} cookie, and a <b>fresh</b> client
 * that presents only that cookie — no JSESSIONID, i.e. the state after a redeploy drops the in-memory
 * session — is re-authenticated as the same user. Reuses {@link SteamAuthTest.StubSteamConfig} to
 * stub the Steam verification.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"civstudio.demo.enabled=false",
		"civstudio.auth.remember-me.key=test-remember-me-signing-key-0123456789" })
@Import(SteamAuthTest.StubSteamConfig.class)
class RememberMeTest {

	private static final String STEAM_ID = "76561198000000001"; // matches StubSteamConfig

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
	void rememberMeCookieReauthenticatesWithoutASession() throws Exception {
		// sign in (its own cookie jar holds JSESSIONID + the remember-me cookie)
		HttpClient signIn = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
				.cookieHandler(new CookieManager()).build();
		HttpResponse<String> ret = signIn.send(
				HttpRequest.newBuilder(uri("/api/auth/steam/return?openid.mode=id_res")).GET().build(),
				ofString());
		assertEquals(302, ret.statusCode());

		// the login must have issued the remember-me cookie
		String remember = ret.headers().allValues("Set-Cookie").stream()
				.filter(h -> h.startsWith(RememberMeConfig.COOKIE_NAME + "="))
				.findFirst()
				.map(h -> h.substring(0, h.indexOf(';') < 0 ? h.length() : h.indexOf(';')))
				.orElse(null);
		assertTrue(remember != null && remember.length() > RememberMeConfig.COOKIE_NAME.length() + 1,
				"sign-in should issue a non-empty remember-me cookie");

		// a returning visitor after a redeploy: a brand-new client with NO cookie jar, presenting only
		// the remember-me cookie. The server has no session for it, so only the cookie can authenticate.
		HttpClient returning = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		JsonNode me = json.readTree(returning.send(
				HttpRequest.newBuilder(uri("/api/auth/me")).header("Cookie", remember).GET().build(),
				ofString()).body());
		assertTrue(me.get("authenticated").asBoolean(),
				"the remember-me cookie should re-authenticate without a session");
		assertEquals(STEAM_ID, me.get("displayName").asString(), "as the same Steam user");
	}

	@Test
	@Timeout(120)
	void noRememberCookieMeansAnonymous() throws Exception {
		// a fresh client with neither a session nor a remember-me cookie is anonymous
		HttpClient anon = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		JsonNode me = json.readTree(
				anon.send(HttpRequest.newBuilder(uri("/api/auth/me")).GET().build(), ofString()).body());
		assertFalse(me.get("authenticated").asBoolean(), "no cookie → anonymous");
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
