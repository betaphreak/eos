package com.civstudio.name;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.ObjectMapper;

/**
 * On-demand cache for the non-human races' name tables. A race's tables are <b>generated</b> from the
 * Anbennar source ({@link RaceNameGenerator}) the first time that race is used and written as tiered
 * JSON under {@link #cacheDir}{@code /<race>/<kind>.json}; later requests (this run or a later one)
 * load the cached file. The generated files are <b>not committed</b> — only the hand-authored human
 * names ({@code /human-names/}) ship in the repo. This mirrors the per-province plot-on-demand model
 * ({@link com.civstudio.settlement.ProvincePlotStore}): expensive derivation is paid once and cached.
 * <p>
 * The default cache dir is the engine module's {@code generated/names} resource dir (writable when
 * running from source — the repo root is the Maven working dir — and gitignored); the server overrides
 * it to a writable volume via {@link #configure} (system property {@code civstudio.names.cacheDir} /
 * env {@code CIVSTUDIO_NAMES_CACHE_DIR}). Generation is per-race locked, so concurrent colony threads
 * never generate the same race twice.
 */
public final class NameStore {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// writable cache dir; default resolves under the Maven working dir (repo root) when run from source
	private static volatile Path cacheDir = Path.of(prop(
			"civstudio.names.cacheDir", "CIVSTUDIO_NAMES_CACHE_DIR",
			"civstudio-engine/target/generated/names"));

	// one lock per race so concurrent colony threads never generate the same race twice; a race known
	// to be sparse/absent is remembered so we don't re-parse the 0.5 MB source on every fallback
	private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
	private static final java.util.Set<String> NOT_GENERATABLE = ConcurrentHashMap.newKeySet();

	private NameStore() {
	}

	/** Override the cache dir (called by the server from {@code civstudio.names.cacheDir}); blank ignored. */
	public static synchronized void configure(String dir) {
		if (dir != null && !dir.isBlank())
			cacheDir = Path.of(dir.trim());
	}

	/**
	 * The {@link NameTable} for a race and kind ({@code dynasty}/{@code male}/{@code female}), generating
	 * and caching the race's tables from Anbennar on first use.
	 *
	 * @param raceId the eos race id
	 * @param kind   one of {@code dynasty}/{@code male}/{@code female}
	 * @return the loaded table, or {@code null} if the race is not generatable (absent/sparse in the
	 *         Anbennar source) — the caller falls back to the human table
	 */
	public static NameTable table(String raceId, String kind) {
		if (NOT_GENERATABLE.contains(raceId))
			return null;
		Path file = cacheDir.resolve(raceId).resolve(kind + ".json");
		if (Files.isRegularFile(file))
			return NameTable.load(file);
		Object lock = LOCKS.computeIfAbsent(raceId, k -> new Object());
		synchronized (lock) {
			if (Files.isRegularFile(file))
				return NameTable.load(file);
			if (NOT_GENERATABLE.contains(raceId))
				return null;
			Map<String, List<Map<String, Object>>> tables = RaceNameGenerator.generate(raceId);
			if (tables.isEmpty()) {
				NOT_GENERATABLE.add(raceId); // absent/sparse — don't re-parse the source for it again
				return null;
			}
			writeAll(raceId, tables);
			return NameTable.load(file);
		}
	}

	// write all three kinds of a freshly generated race, atomically per file (temp-then-move), so a
	// concurrent reader never sees a half-written file and a later run finds a complete cache
	private static void writeAll(String raceId, Map<String, List<Map<String, Object>>> tables) {
		Path dir = cacheDir.resolve(raceId);
		try {
			Files.createDirectories(dir);
			for (Map.Entry<String, List<Map<String, Object>>> e : tables.entrySet()) {
				Path out = dir.resolve(e.getKey() + ".json");
				Path tmp = Files.createTempFile(dir, ".gen-", ".part");
				MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), e.getValue());
				try {
					Files.move(tmp, out, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException atomicUnsupported) {
					Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("failed to write generated names for race " + raceId, e);
		}
	}

	private static String prop(String sysProp, String env, String fallback) {
		String v = System.getProperty(sysProp);
		if (v == null || v.isBlank())
			v = System.getenv(env);
		return (v == null || v.isBlank()) ? fallback : v.trim();
	}
}
