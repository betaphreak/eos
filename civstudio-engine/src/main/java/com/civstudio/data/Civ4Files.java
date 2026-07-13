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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand provider for the Caveman2Cosmos (Civ4) source files — the info/art/schema XML, the
 * planet-generator {@code .py}, the {@code GameFont} atlases and the {@code UnpackedArt} terrain
 * {@code .dds}. These files are <b>not</b> vendored in the repo: {@link #get} downloads an
 * individual file from the canonical <a href="https://github.com/caveman2cosmos/Caveman2Cosmos">C2C
 * GitHub repo</a> (at {@link #ref}) the first time it is needed and caches it under
 * {@link #cacheDir}; later requests return the cached copy. This is the sibling of
 * {@link AnbennarFiles}, and the design mirrors it — see {@code docs/civ4-files.md}.
 * <p>
 * <b>Dev-time only.</b> Unlike {@link AnbennarFiles} nothing in the running server reads Civ4:
 * these files feed the {@code geo/export/*} + {@code tech/export/*} exporters (which bake the
 * committed resource JSON) and the {@code web/*.mjs} bakers (which have their own {@code
 * web/civ4.mjs} fetcher). So there is no Spring bridge and no {@code configure()} call in practice
 * — the engine defaults plus a GitHub token are the whole configuration.
 * <p>
 * Callers pass a <b>committed-relative path</b> — the path a file used to have under
 * {@code data/civ4/} (e.g. {@code "CIV4TerrainInfos.xml"} or
 * {@code "res/Fonts/GameFont_120.tga"}). {@link #FILE_MAP} (plus the {@code assets/terrain/} art
 * prefix rule) translates that to the file's real location in the C2C tree, which the old flat
 * {@code data/civ4/} layout had collapsed.
 */
public final class Civ4Files {

	private Civ4Files() {
	}

	private static final String REPO = "caveman2cosmos/Caveman2Cosmos";
	// fallback if the lock resource is somehow absent (kept in sync with civ4-source.lock)
	private static final String FALLBACK_REF = "f174979b7336ee42839077e997bea1b3c129dce5";
	private static final String LOCK_RESOURCE = "/civ4-source.lock";

	// committed-relative path (what the file was under data/civ4/) -> its path in the C2C repo.
	// The old data/civ4/ tree flattened files from scattered C2C subtrees; this restores them.
	private static final Map<String, String> FILE_MAP = Map.ofEntries(
			Map.entry("CIV4TerrainInfos.xml", "Assets/XML/Terrain/CIV4TerrainInfos.xml"),
			Map.entry("CIV4FeatureInfos.xml", "Assets/XML/Terrain/CIV4FeatureInfos.xml"),
			Map.entry("CIV4ImprovementInfos.xml", "Assets/XML/Terrain/CIV4ImprovementInfos.xml"),
			Map.entry("CIV4BonusInfos.xml", "Assets/XML/Terrain/CIV4BonusInfos.xml"),
			Map.entry("CIV4BonusClassInfos.xml", "Assets/XML/Terrain/CIV4BonusClassInfos.xml"),
			Map.entry("Manufactured_CIV4BonusInfos.xml",
					"Assets/XML/Terrain/Manufactured_CIV4BonusInfos.xml"),
			Map.entry("SpecialBuildings_CIV4BuildingInfos.xml",
					"Assets/XML/Buildings/SpecialBuildings_CIV4BuildingInfos.xml"),
			Map.entry("zProviders_CIV4BuildingInfos.xml",
					"Assets/XML/Buildings/zProviders_CIV4BuildingInfos.xml"),
			Map.entry("Regular_CIV4BuildingInfos.xml",
					"Assets/XML/Buildings/Regular_CIV4BuildingInfos.xml"),
			Map.entry("CIV4RouteInfos.xml", "Assets/XML/Misc/CIV4RouteInfos.xml"),
			Map.entry("CIV4RouteModelInfos.xml", "Assets/XML/Art/CIV4RouteModelInfos.xml"),
			Map.entry("CIV4ArtDefines_Terrain.xml", "Assets/XML/Art/CIV4ArtDefines_Terrain.xml"),
			Map.entry("CIV4ArtDefines_Bonus.xml", "Assets/XML/Art/CIV4ArtDefines_Bonus.xml"),
			Map.entry("C2C_CIV4TerrainSchema.xml", "Assets/XML/Schema/C2C_CIV4TerrainSchema.xml"),
			Map.entry("Caveman2Cosmos.xsd", "Assets/XML/Schema/Caveman2Cosmos.xsd"),
			Map.entry("C2C_Planet_Generator_0_68.py", "PrivateMaps/C2C_Planet_Generator_0_68.py"),
			Map.entry("assets/XML/Technologies/CIV4TechInfos.xml",
					"Assets/XML/Technologies/CIV4TechInfos.xml"),
			Map.entry("assets/XML/GameText/Tech_CIV4GameText.xml",
					"Assets/XML/GameText/Tech_CIV4GameText.xml"),
			// building import (BuildingInfoExporter): the artDefineTag -> <Button> map, and the
			// six GameText files that hold the TXT_KEY_BUILDING_* name/help/pedia strings for the
			// kept-tech-gated buildings (see docs/c2c-building-import.md)
			Map.entry("assets/XML/Art/CIV4ArtDefines_Building.xml",
					"Assets/XML/Art/CIV4ArtDefines_Building.xml"),
			Map.entry("assets/XML/GameText/Buildings_CIV4GameText.xml",
					"Assets/XML/GameText/Buildings_CIV4GameText.xml"),
			Map.entry("assets/XML/GameText/Buildings_Animals_CIV4GameText.xml",
					"Assets/XML/GameText/Buildings_Animals_CIV4GameText.xml"),
			Map.entry("assets/XML/GameText/Slavery_CIV4GameText.xml",
					"Assets/XML/GameText/Slavery_CIV4GameText.xml"),
			Map.entry("assets/XML/GameText/Traditions_CIV4GameText.xml",
					"Assets/XML/GameText/Traditions_CIV4GameText.xml"),
			Map.entry("assets/XML/GameText/Human_Sacrifice_CIV4GameText.xml",
					"Assets/XML/GameText/Human_Sacrifice_CIV4GameText.xml"),
			Map.entry("assets/XML/GameText/Cannibalism_CIV4GameText.xml",
					"Assets/XML/GameText/Cannibalism_CIV4GameText.xml"),
			Map.entry("res/Fonts/GameFont.tga", "Assets/res/Fonts/GameFont.tga"),
			// C2C ships this atlas under an odd name; it was renamed on vendoring
			Map.entry("res/Fonts/GameFont_120.tga",
					"Assets/res/Fonts/GameFont_120_(unused prrof of concept).tga"));

	// mutable config — engine defaults, overridable via configure() for symmetry with AnbennarFiles
	private static volatile String ref = loadLockRef();
	private static volatile Path cacheDir = Path
			.of(prop("civstudio.civ4.cacheDir", "CIV4_CACHE_DIR", ".civ4-cache"));
	private static volatile String token = resolveToken();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
	// one lock per (ref, path) so concurrent exporter threads never download the same file twice
	private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

	/** Override the source configuration. Blank values are ignored (defaults survive). */
	public static synchronized void configure(String ref, String cacheDir, String token) {
		if (ref != null && !ref.isBlank())
			Civ4Files.ref = ref.trim();
		if (cacheDir != null && !cacheDir.isBlank())
			Civ4Files.cacheDir = Path.of(cacheDir.trim());
		if (token != null && !token.isBlank())
			Civ4Files.token = token.trim();
	}

	/** The locked C2C commit currently in effect (the committed lock, or an override). */
	public static String ref() {
		return ref;
	}

	/**
	 * The local file for a <b>committed-relative</b> path (what it was under {@code data/civ4/}),
	 * downloading it on a cache miss.
	 *
	 * @param committedRelativePath e.g. {@code "CIV4TerrainInfos.xml"} or
	 *                              {@code "res/Fonts/GameFont_120.tga"}
	 * @return the cached local path (guaranteed to exist on return)
	 * @throws FileNotFoundException if the upstream returns 404 at this {@code ref}
	 * @throws IOException           on any other fetch/IO failure
	 */
	public static Path get(String committedRelativePath) throws IOException {
		return getC2C(toC2C(committedRelativePath));
	}

	/**
	 * The local file for a path <b>as it lives in the C2C repo</b> (e.g.
	 * {@code "UnpackedArt/art/terrain/textures/land/GrassDetail.dds"}), downloading on a miss. Used
	 * when the caller already knows the C2C layout (the terrain-art {@code .dds} paths).
	 */
	public static Path getC2C(String c2cPath) throws IOException {
		String rel = normalize(c2cPath);
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

	/** Translate a committed-relative path to its C2C repo path. */
	static String toC2C(String committedRelativePath) {
		String rel = normalize(committedRelativePath);
		String mapped = FILE_MAP.get(rel);
		if (mapped != null)
			return mapped;
		// the terrain art tree: data/civ4/assets/terrain/... came from UnpackedArt/art/terrain/...
		if (rel.startsWith("assets/terrain/"))
			return "UnpackedArt/art/" + rel.substring("assets/".length());
		throw new IllegalArgumentException("no C2C mapping for civ4 path: " + committedRelativePath);
	}

	// download a single file to `local` via an atomic temp-then-move, so a partial/failed fetch
	// never leaves a truncated file in the cache
	private static void download(String c2cPath, Path local) throws IOException {
		String url = "https://api.github.com/repos/" + REPO + "/contents/" + encodePath(c2cPath)
				+ "?ref=" + enc(ref);
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/vnd.github.raw").GET();
		HttpResponse<InputStream> res = send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() == 404)
			throw new FileNotFoundException("civ4 file not found at " + ref + ": " + c2cPath);
		if (res.statusCode() != 200)
			throw new IOException("fetch " + res.statusCode() + " for " + c2cPath
					+ (res.statusCode() == 403 ? " (rate-limited? set GITHUB_TOKEN / gh auth login)" : ""));
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

	private static <T> HttpResponse<T> send(HttpRequest.Builder req, HttpResponse.BodyHandler<T> h)
			throws IOException {
		req.timeout(Duration.ofMinutes(2)).header("X-GitHub-Api-Version", "2022-11-28");
		if (!token.isBlank())
			req.header("Authorization", "Bearer " + token);
		try {
			return HTTP.send(req.build(), h);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted fetching from GitHub", e);
		}
	}

	private static String loadLockRef() {
		try (InputStream in = Civ4Files.class.getResourceAsStream(LOCK_RESOURCE)) {
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

	// GITHUB_TOKEN (or civstudio.civ4.token), else best-effort `gh auth token` — the C2C repo is
	// public but the unauthenticated GitHub API allows only 60 req/hour, far too few for a full bake
	private static String resolveToken() {
		String v = prop("civstudio.civ4.token", "GITHUB_TOKEN", "");
		if (!v.isBlank())
			return v;
		try {
			Process p = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			p.waitFor();
			return p.exitValue() == 0 ? out : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static String prop(String sysProp, String env, String fallback) {
		String v = System.getProperty(sysProp);
		if (v == null || v.isBlank())
			v = System.getenv(env);
		return (v == null || v.isBlank()) ? fallback : v.trim();
	}

	// forward slashes, no leading/trailing slash
	private static String normalize(String p) {
		return p.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
	}

	// url-encode a whole path, preserving '/' separators (spaces/parens appear in one atlas name)
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
}
