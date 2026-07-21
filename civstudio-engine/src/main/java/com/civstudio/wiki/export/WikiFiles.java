package com.civstudio.wiki.export;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand fetcher + local cache for the Anbennar Fandom wiki, modelled on
 * {@link com.civstudio.data.AnbennarFiles}: the MediaWiki API is queried over HTTPS the first time a
 * request is needed and the raw JSON body is cached under {@link #cacheDir}; later identical requests
 * return the cached copy. Fetches are pinned to a {@link #snapshot} (the committed
 * {@code /wiki-source.lock} — the dump/scrape date) so a bake is reproducible and offline once warm.
 * <p>
 * Dev-tool infrastructure only (the {@link WikiExporter}). This P0 slice uses the public MediaWiki
 * API ({@code /api.php}); P1 will add the {@code Special:Statistics} {@code .7z} dump as the bulk
 * source-of-record, reusing this same cache/lock/backoff machinery for images and incremental refresh.
 *
 * @see <a href="https://anbennar.fandom.com/api.php">anbennar.fandom.com/api.php</a>
 */
public final class WikiFiles {

	private WikiFiles() {
	}

	private static final String LOCK_RESOURCE = "/wiki-source.lock";
	private static final String FALLBACK_SNAPSHOT = "2026-07-21";

	// A descriptive User-Agent is required etiquette for the MediaWiki API (and avoids generic-client
	// throttling). Fandom asks automated clients to identify themselves and a contact.
	private static final String USER_AGENT =
			"CivStudioWikiExporter/1.0 (+https://civstudio.com; contact alexandru.giurovici@outlook.com)";

	// mutable config — dev defaults, overridable via system property / env
	private static volatile String baseUrl = prop("civstudio.wiki.baseUrl", "WIKI_BASE_URL",
			"https://anbennar.fandom.com");
	private static volatile String snapshot = loadSnapshot();
	private static volatile Path cacheDir = Path
			.of(prop("civstudio.wiki.cacheDir", "WIKI_CACHE_DIR", ".wiki-cache"));

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
	// one lock per cache file so concurrent callers never download the same request twice
	private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
	private static final int MAX_RETRIES = 8;
	private static final Random JITTER = new Random();

	/** The scrape snapshot currently in effect (the committed lock, or a fallback). */
	public static String snapshot() {
		return snapshot;
	}

	/**
	 * Issue a MediaWiki API query and return the JSON response body. {@code format=json} is appended;
	 * the response is cached under {@code <cacheDir>/<snapshot>/api/<sha1(query)>.json}, so an identical
	 * query in a later run is served from disk.
	 *
	 * @param query the query string after {@code api.php?} (e.g.
	 *              {@code "action=query&list=categorymembers&cmtitle=Category:Countries"}); callers must
	 *              URL-encode individual values that need it
	 */
	public static String api(String query) throws IOException {
		String url = baseUrl + "/api.php?" + query + "&format=json";
		Path cached = cacheDir.resolve(snapshot).resolve("api").resolve(sha1(query) + ".json");
		return Files.readString(getUrl(url, cached), StandardCharsets.UTF_8);
	}

	/**
	 * Fetch {@code url} into the cache path {@code local} (atomically), returning it; a cache hit skips
	 * the network. Public so P1's image fetch and dump download can reuse the same atomic-write + lock.
	 */
	public static Path getUrl(String url, Path local) throws IOException {
		if (Files.isRegularFile(local))
			return local;
		Object lock = LOCKS.computeIfAbsent(local.toString(), k -> new Object());
		synchronized (lock) {
			if (Files.isRegularFile(local))
				return local;
			download(url, local);
			return local;
		}
	}

	// download to `local` via a temp-then-move, so a partial/failed fetch never leaves a truncated file
	private static void download(String url, Path local) throws IOException {
		HttpResponse<InputStream> res = send(url);
		if (res.statusCode() == 404)
			throw new FileNotFoundException("wiki resource not found: " + url);
		if (res.statusCode() != 200)
			throw new IOException("fetch " + res.statusCode() + " for " + url);
		Files.createDirectories(local.getParent());
		Path tmp = Files.createTempFile(local.getParent(), ".dl-", ".part");
		try (InputStream in = res.body()) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(tmp, local, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicUnsupported) {
				Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	// Fandom throttles bursts (429/503); retry honouring Retry-After, else exponential backoff, so a
	// bulk export self-tunes to the allowed rate rather than failing outright.
	private static HttpResponse<InputStream> send(String url) throws IOException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).GET().build();
		for (int attempt = 0;; attempt++) {
			HttpResponse<InputStream> res;
			try {
				res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted fetching from the wiki", e);
			}
			int sc = res.statusCode();
			if ((sc == 429 || sc == 503) && attempt < MAX_RETRIES) {
				try (InputStream in = res.body()) {
					in.readAllBytes(); // drain the throttle body so the connection is freed
				} catch (IOException ignore) {
					// best-effort
				}
				backoff(res, attempt);
				continue;
			}
			return res;
		}
	}

	private static void backoff(HttpResponse<?> res, int attempt) throws IOException {
		long ms = res.headers().firstValue("retry-after")
				.map(String::trim).filter(s -> s.chars().allMatch(Character::isDigit))
				.map(s -> Math.min(Long.parseLong(s), 30) * 1000L)
				.orElseGet(() -> Math.min(1000L << attempt, 30_000L));
		ms += (long) (ms * 0.25 * JITTER.nextDouble()); // jitter to de-sync threads
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted during rate-limit backoff", e);
		}
	}

	private static String loadSnapshot() {
		try (InputStream in = WikiFiles.class.getResourceAsStream(LOCK_RESOURCE)) {
			if (in != null) {
				String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				if (!s.isEmpty())
					return s;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("failed reading " + LOCK_RESOURCE, e);
		}
		return FALLBACK_SNAPSHOT;
	}

	private static String sha1(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // SHA-1 is always present
		}
	}

	private static String prop(String sysProp, String env, String fallback) {
		String v = System.getProperty(sysProp);
		if (v == null || v.isBlank())
			v = System.getenv(env);
		return (v == null || v.isBlank()) ? fallback : v.trim();
	}
}
