package com.civstudio.server.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * The real {@link SteamPersonaLookup}: calls Steam's {@code ISteamUser/GetPlayerSummaries} with the
 * configured Web API key. A blank key (the default) short-circuits to empty, so Steam sign-in still
 * works without a key — the SteamID is just used as the display name until one is set. Any
 * network/parse failure also degrades to empty rather than failing the sign-in.
 */
public final class HttpSteamPersonaLookup implements SteamPersonaLookup {

	private static final Logger log = LoggerFactory.getLogger(HttpSteamPersonaLookup.class);
	private static final String ENDPOINT = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/";

	private final String apiKey;
	private final HttpClient http;
	private final ObjectMapper json = new ObjectMapper();

	public HttpSteamPersonaLookup(String apiKey) {
		this(apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
	}

	HttpSteamPersonaLookup(String apiKey, HttpClient http) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.http = http;
	}

	@Override
	public Optional<SteamPersona> lookup(String steamId64) {
		if (apiKey.isBlank() || steamId64 == null || steamId64.isBlank())
			return Optional.empty();
		try {
			String url = ENDPOINT + "?key=" + enc(apiKey) + "&steamids=" + enc(steamId64);
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10)).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				log.warn("steam persona lookup: HTTP {} for {}", res.statusCode(), steamId64);
				return Optional.empty();
			}
			Map<?, ?> root = json.readValue(res.body(), Map.class);
			if (!(root.get("response") instanceof Map<?, ?> response)
					|| !(response.get("players") instanceof List<?> players)
					|| players.isEmpty() || !(players.get(0) instanceof Map<?, ?> player))
				return Optional.empty();
			String name = str(player.get("personaname"));
			if (name == null || name.isBlank())
				return Optional.empty();
			return Optional.of(new SteamPersona(name, str(player.get("avatarmedium"))));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			log.warn("steam persona lookup failed for {}: {}", steamId64, e.toString());
			return Optional.empty();
		}
	}

	private static String str(Object o) {
		return o instanceof String s ? s : null;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
