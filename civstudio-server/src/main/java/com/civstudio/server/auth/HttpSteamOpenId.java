package com.civstudio.server.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link SteamOpenId}: talks to {@code https://steamcommunity.com/openid/login} over
 * HTTPS. The redirect uses OpenID 2.0 {@code checkid_setup} with {@code identifier_select} (Steam
 * picks the logged-in user); {@link #verify} re-posts the returned assertion with {@code
 * check_authentication} — the anti-forgery step — and only then trusts the claimed SteamID.
 */
public final class HttpSteamOpenId implements SteamOpenId {

	private static final Logger log = LoggerFactory.getLogger(HttpSteamOpenId.class);

	private static final String OPENID_ENDPOINT = "https://steamcommunity.com/openid/login";
	private static final String OPENID_NS = "http://specs.openid.net/auth/2.0";
	private static final String IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";

	// the claimed_id Steam asserts on success: https://steamcommunity.com/openid/id/<steamid64>
	private static final Pattern CLAIMED_ID = Pattern
			.compile("^https://steamcommunity\\.com/openid/id/(\\d{17})$");

	private final HttpClient http;

	public HttpSteamOpenId() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
	}

	HttpSteamOpenId(HttpClient http) {
		this.http = http;
	}

	@Override
	public String authenticationRequestUrl(String realm, String returnTo) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("openid.ns", OPENID_NS);
		params.put("openid.mode", "checkid_setup");
		params.put("openid.return_to", returnTo);
		params.put("openid.realm", realm);
		params.put("openid.identity", IDENTIFIER_SELECT);
		params.put("openid.claimed_id", IDENTIFIER_SELECT);
		return OPENID_ENDPOINT + "?" + form(params);
	}

	@Override
	public Optional<String> verify(Map<String, String> params) {
		// only Steam's positive assertion is verifiable; a user who cancels comes back with
		// openid.mode=cancel (or nothing) — reject without a network round-trip
		if (!"id_res".equals(params.get("openid.mode"))) {
			log.debug("steam openid: non-positive assertion (mode={})", params.get("openid.mode"));
			return Optional.empty();
		}
		// echo every returned openid.* field back, flipping mode to check_authentication, so Steam
		// re-signs and confirms it actually issued this assertion (the mandatory verification)
		Map<String, String> check = new LinkedHashMap<>(params);
		check.put("openid.mode", "check_authentication");
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(OPENID_ENDPOINT))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(form(check))).build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			// the key-value response must contain is_valid:true; anything else is a forged/expired
			// assertion and must be rejected
			boolean valid = res.statusCode() == 200 && res.body().lines()
					.anyMatch(l -> l.equals("is_valid:true"));
			if (!valid) {
				log.warn("steam openid: check_authentication did not return is_valid:true");
				return Optional.empty();
			}
			return steamId(params.get("openid.claimed_id"));
		} catch (Exception e) {
			log.warn("steam openid: verification call failed", e);
			return Optional.empty();
		}
	}

	// extract the 17-digit SteamID64 from the verified claimed_id
	private static Optional<String> steamId(String claimedId) {
		if (claimedId == null)
			return Optional.empty();
		Matcher m = CLAIMED_ID.matcher(claimedId);
		return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
	}

	private static String form(Map<String, String> params) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (sb.length() > 0)
				sb.append('&');
			sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		return sb.toString();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}
}
