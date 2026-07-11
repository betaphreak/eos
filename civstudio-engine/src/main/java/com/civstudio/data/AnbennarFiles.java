package com.civstudio.data;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * On-demand provider for the Anbennar EU4 mod files (map rasters + Clausewitz metadata). These
 * files are <b>not</b> vendored in the repo — {@link #get} downloads an individual file from the
 * canonical GitLab source (pinned to {@link #ref}) the first time it is needed and caches it under
 * {@link #cacheDir}; later requests return the cached copy. This is the one place that knows how to
 * turn a <b>mod-relative path</b> (e.g. {@code "map/provinces.bmp"}) into a local {@link Path}.
 * <p>
 * The engine carries standalone defaults so {@code exec:exec} and the test suite work without any
 * server; the Spring Boot server overrides them at startup via {@link #configure} (see
 * {@code CivStudioProperties.Anbennar} / {@code docs/anbennar-files.md}). The locked commit is the
 * committed {@code /map/anbennar-source.lock} resource, so the raster the server downloads always
 * matches the committed {@code map/*.json} resources it was generated from.
 */
public final class AnbennarFiles {

	private AnbennarFiles() {
	}

	// fallback if the lock resource is somehow absent (kept in sync with map/anbennar-source.lock)
	private static final String FALLBACK_REF = "7216a7525bc971eac989ebfcddf34833814802df";
	private static final String LOCK_RESOURCE = "/map/anbennar-source.lock";

	// mutable config — engine defaults, overridable by the server's configure() call
	private static volatile String baseUrl = "https://gitlab.com/anbennar/anbennar-eu4-dev";
	private static volatile String ref = loadLockRef();
	private static volatile Path cacheDir = Path
			.of(prop("civstudio.anbennar.cacheDir", "ANBENNAR_CACHE_DIR", ".anbennar-cache"));
	private static volatile String token = prop("civstudio.anbennar.token", "ANBENNAR_TOKEN", "");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
	// one lock per (ref, path) so concurrent colony threads never download the same file twice
	private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
	// concurrency for a bulk getDir() fetch — the download is round-trip-latency bound, so a small
	// pool speeds a cold fetch of a big directory (e.g. ~5k history/provinces files). Kept modest, and
	// paired with retry/backoff in send(), so it settles near GitLab's rate limit rather than tripping it.
	private static final int GETDIR_THREADS = 8;
	// retry budget and jitter source for the rate-limit backoff in send() (dev-tool infra — not the
	// sim's reproducible RNG, so a plain Random is fine here)
	private static final int MAX_RETRIES = 8;
	private static final java.util.Random ATTEMPT_JITTER = new java.util.Random();

	/**
	 * Override the source configuration (called once by the server from {@code civstudio.anbennar.*}).
	 * Blank values are ignored, so the engine's defaults survive a partial config.
	 */
	public static synchronized void configure(String baseUrl, String ref, String cacheDir,
			String token) {
		if (baseUrl != null && !baseUrl.isBlank())
			AnbennarFiles.baseUrl = stripTrailingSlash(baseUrl.trim());
		if (ref != null && !ref.isBlank())
			AnbennarFiles.ref = ref.trim();
		if (cacheDir != null && !cacheDir.isBlank())
			AnbennarFiles.cacheDir = Path.of(cacheDir.trim());
		if (token != null)
			AnbennarFiles.token = token.trim();
	}

	/** The locked commit currently in effect (the committed lock, or a server override). */
	public static String ref() {
		return ref;
	}

	/**
	 * The local file for a mod-relative path, downloading it on a cache miss.
	 *
	 * @param modRelativePath e.g. {@code "map/provinces.bmp"} or {@code "common/cultures/anb_cultures.txt"}
	 * @return the cached local path (guaranteed to exist on return)
	 * @throws FileNotFoundException if the upstream returns 404 (no such file at this {@code ref})
	 * @throws IOException           on any other fetch/IO failure
	 */
	public static Path get(String modRelativePath) throws IOException {
		String rel = normalize(modRelativePath);
		Path local = cacheDir.resolve(ref).resolve(rel);
		if (Files.isRegularFile(local))
			return local;
		Object lock = LOCKS.computeIfAbsent(ref + '\0' + rel, k -> new Object());
		synchronized (lock) {
			if (Files.isRegularFile(local))
				return local;
			download(rel, local);
			return local;
		}
	}

	/**
	 * Like {@link #get} but returns empty on a 404 instead of throwing — for genuinely optional
	 * files (terrain/tree/height overlays, {@code default.map}) whose absence the caller tolerates.
	 * Any non-404 failure still propagates.
	 */
	public static Optional<Path> getOptional(String modRelativePath) throws IOException {
		try {
			return Optional.of(get(modRelativePath));
		} catch (FileNotFoundException e) {
			return Optional.empty();
		}
	}

	/**
	 * Fetch <em>every</em> file under a mod-relative directory (recursively) and return the local
	 * cache directory holding them — so a caller that walks the directory ({@code File.listFiles})
	 * works unchanged. Uses the GitLab tree API for the listing, then downloads the blobs
	 * concurrently ({@value #GETDIR_THREADS}-wide); {@link #get} is per-path thread-safe, so already
	 * cached files are skipped and a re-run resumes cheaply. Dev-time only (the exporters).
	 */
	public static Path getDir(String modRelativeDir) throws IOException {
		String dir = normalize(modRelativeDir);
		fetchAll(list(dir));
		return cacheDir.resolve(ref).resolve(dir);
	}

	// download many blobs concurrently over a bounded pool, surfacing the first failure. get() is
	// per-(ref,path) locked, so parallel calls never double-download and cached files return at once.
	private static void fetchAll(List<String> blobs) throws IOException {
		if (blobs.size() <= 1) {
			for (String blob : blobs)
				get(blob);
			return;
		}
		ExecutorService pool = Executors.newFixedThreadPool(
				Math.min(GETDIR_THREADS, blobs.size()));
		try {
			List<Future<?>> futures = new ArrayList<>(blobs.size());
			for (String blob : blobs)
				futures.add(pool.submit(() -> {
					get(blob);
					return null;
				}));
			for (Future<?> f : futures)
				await(f);
		} finally {
			pool.shutdownNow();
		}
	}

	// wait for one download future, unwrapping its cause to the original IOException/runtime error
	private static void await(Future<?> f) throws IOException {
		try {
			f.get();
		} catch (ExecutionException e) {
			switch (e.getCause()) {
				case IOException io -> throw io;
				case RuntimeException re -> throw re;
				case null -> throw new IOException("fetch failed", e);
				default -> throw new IOException("fetch failed", e.getCause());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted fetching from GitLab", e);
		}
	}

	/** Blob paths under a mod-relative directory (recursive), via the GitLab tree API. */
	public static List<String> list(String modRelativeDir) throws IOException {
		String dir = normalize(modRelativeDir);
		List<String> blobs = new ArrayList<>();
		ObjectMapper mapper = new ObjectMapper();
		String apiBase = projectApiBase();
		int page = 1;
		while (true) {
			String url = apiBase + "/repository/tree?path=" + enc(dir) + "&ref=" + enc(ref)
					+ "&recursive=true&per_page=100&page=" + page;
			HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).GET(),
					HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200)
				throw new IOException("GitLab tree API " + res.statusCode() + " for " + dir);
			List<Map<String, Object>> entries = mapper.readValue(res.body(),
					new TypeReference<List<Map<String, Object>>>() {
					});
			for (Map<String, Object> e : entries)
				if ("blob".equals(e.get("type")))
					blobs.add((String) e.get("path"));
			String next = res.headers().firstValue("x-next-page").orElse("");
			if (next.isBlank())
				break;
			page = Integer.parseInt(next.trim());
		}
		return blobs;
	}

	// download a single file to `local` via an atomic temp-then-move, so a partial/failed fetch
	// never leaves a truncated file in the cache
	private static void download(String rel, Path local) throws IOException {
		String url = baseUrl + "/-/raw/" + enc(ref) + "/" + encodePath(rel);
		HttpResponse<InputStream> res = send(HttpRequest.newBuilder(URI.create(url)).GET(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() == 404)
			throw new FileNotFoundException("anbennar file not found at " + ref + ": " + rel);
		if (res.statusCode() != 200)
			throw new IOException("fetch " + res.statusCode() + " for " + rel);
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

	// GitLab throttles the raw/tree endpoints (HTTP 429), so a wide getDir() will trip it. Retry a
	// throttled (or transiently unavailable) request, waiting the server's Retry-After when given, so
	// concurrent fetches self-tune to the allowed rate instead of failing the whole export.
	private static <T> HttpResponse<T> send(HttpRequest.Builder req, HttpResponse.BodyHandler<T> h)
			throws IOException {
		req.timeout(Duration.ofMinutes(2));
		if (!token.isBlank())
			req.header("PRIVATE-TOKEN", token);
		HttpRequest built = req.build();
		for (int attempt = 0;; attempt++) {
			HttpResponse<T> res;
			try {
				res = HTTP.send(built, h);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted fetching from GitLab", e);
			}
			int sc = res.statusCode();
			if ((sc == 429 || sc == 503) && attempt < MAX_RETRIES) {
				// discard any streaming body before retrying so the connection is freed
				if (res.body() instanceof InputStream in)
					try {
						in.close();
					} catch (IOException ignore) {
						// closing the discarded 429 body is best-effort
					}
				backoff(res, attempt);
				continue;
			}
			return res;
		}
	}

	/** Sleep before a retry: the server's {@code Retry-After} (capped), else exponential backoff. */
	private static void backoff(HttpResponse<?> res, int attempt) throws IOException {
		long ms = res.headers().firstValue("retry-after")
				.map(String::trim).filter(s -> s.chars().allMatch(Character::isDigit))
				.map(s -> Math.min(Long.parseLong(s), 30) * 1000L)
				.orElseGet(() -> Math.min(1000L << attempt, 30_000L));
		ms += (long) (ms * 0.25 * ATTEMPT_JITTER.nextDouble()); // jitter to de-sync threads
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted during rate-limit backoff", e);
		}
	}

	// https://gitlab.com/api/v4/projects/<url-encoded group/project>
	private static String projectApiBase() {
		URI u = URI.create(baseUrl);
		String host = u.getScheme() + "://" + u.getRawAuthority();
		String project = u.getRawPath().replaceAll("^/+", "").replaceAll("\\.git$", "");
		return host + "/api/v4/projects/" + enc(project);
	}

	private static String loadLockRef() {
		try (InputStream in = AnbennarFiles.class.getResourceAsStream(LOCK_RESOURCE)) {
			if (in != null) {
				String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				if (!s.isEmpty())
					return s;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("failed reading " + LOCK_RESOURCE, e);
		}
		return FALLBACK_REF;
	}

	private static String prop(String sysProp, String env, String fallback) {
		String v = System.getProperty(sysProp);
		if (v == null || v.isBlank())
			v = System.getenv(env);
		return (v == null || v.isBlank()) ? fallback : v.trim();
	}

	// mod-relative path with forward slashes, no leading slash
	private static String normalize(String p) {
		return p.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
	}

	// url-encode a whole path, preserving '/' separators (spaces → %20; history filenames have them)
	private static String encodePath(String p) {
		String[] segs = p.split("/");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < segs.length; i++) {
			if (i > 0)
				sb.append('/');
			sb.append(enc(segs[i]));
		}
		return sb.toString();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}
