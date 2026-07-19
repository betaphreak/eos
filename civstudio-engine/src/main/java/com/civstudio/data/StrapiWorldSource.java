package com.civstudio.data;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

/**
 * A {@link BundleWorldSource} that fetches the world bundle once from studio's {@code /api/world-bundle}
 * over the JDK {@link HttpClient} (no new dependency). Authenticates with a bearer token when one is
 * given (the {@code WORLD_BUNDLE_TOKEN} shared secret). Fetch happens at construction; thereafter the
 * bundle is served from memory. The sim core never sees this class — the composition root installs it
 * via {@link WorldSources#set}.
 */
public final class StrapiWorldSource extends BundleWorldSource {

	public StrapiWorldSource(URI bundleUrl, String token) {
		super(fetch(bundleUrl, token));
	}

	private static JsonNode fetch(URI url, String token) {
		HttpRequest.Builder rb = HttpRequest.newBuilder(url).GET().timeout(Duration.ofSeconds(120));
		if (token != null && !token.isBlank())
			rb.header("Authorization", "Bearer " + token);
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<java.io.InputStream> resp =
					client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200)
				throw new IllegalStateException("world-bundle fetch " + url + " → HTTP " + resp.statusCode());
			return parse(resp.body());
		} catch (java.io.IOException | InterruptedException e) {
			if (e instanceof InterruptedException)
				Thread.currentThread().interrupt();
			throw new IllegalStateException("world-bundle fetch failed: " + url, e);
		}
	}
}
