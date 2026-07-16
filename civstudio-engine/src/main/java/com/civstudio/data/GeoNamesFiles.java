package com.civstudio.data;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves the local <a href="https://www.geonames.org/">GeoNames</a> gazetteer
 * dump, the source of real Earth place names for plot naming (consumed by
 * {@code GeoNamesGazetteer} at bake time only).
 * <p>
 * Unlike {@link AnbennarFiles} / {@link Civ4Files}, this fetches nothing: the
 * dump is a single ~350&nbsp;MB archive (~1.5&nbsp;GB unzipped) the developer
 * drops into the cache directory once. Resolution mirrors the sibling caches — a
 * system property, then an environment variable, then a default directory:
 * <pre>
 *   -Dcivstudio.geonames.cacheDir   or   $GEONAMES_CACHE_DIR   (default: .geonames-cache)
 * </pre>
 * Any of {@code allCountries.txt}, {@code allCountries.txt.gz} or the raw
 * {@code allCountries.zip} is accepted and streamed transparently (see
 * {@link #open()}). This is a <em>dev/bake-time</em> dependency only — the names
 * are baked into the plot cache, so the running server and sim never touch it and
 * no deployment volume is needed.
 */
public final class GeoNamesFiles {

	private GeoNamesFiles() {
	}

	private static final String LOCK_RESOURCE = "/geonames-source.lock";
	// kept in sync with geonames-source.lock; "unpinned" until a bake records the dump date
	private static final String FALLBACK_VERSION = "unpinned";

	/** The GeoNames global dump, tab-separated (the {@code allCountries} table). */
	public static final String DUMP_TXT = "allCountries.txt";
	private static final String DUMP_GZ = "allCountries.txt.gz";
	private static final String DUMP_ZIP = "allCountries.zip";
	private static final String DOWNLOAD_URL = "https://download.geonames.org/export/dump/allCountries.zip";

	private static volatile Path cacheDir = Path
			.of(prop("civstudio.geonames.cacheDir", "GEONAMES_CACHE_DIR", ".geonames-cache"));
	private static volatile String dumpVersion = loadLockVersion();

	/**
	 * Override the cache directory (parity with the sibling caches; a blank value
	 * is ignored so the default survives a partial config).
	 */
	public static synchronized void configure(String cacheDir) {
		if (cacheDir != null && !cacheDir.isBlank())
			GeoNamesFiles.cacheDir = Path.of(cacheDir.trim());
	}

	/** The resolved cache directory. */
	public static Path cacheDir() {
		return cacheDir;
	}

	/** The recorded dump version/date ({@code "unpinned"} until a bake pins it). */
	public static String dumpVersion() {
		return dumpVersion;
	}

	/**
	 * The dump file in the cache, preferring {@code .txt}, then {@code .txt.gz},
	 * then {@code .zip}.
	 *
	 * @return the dump path, or empty if none is present
	 */
	public static Optional<Path> dumpFile() {
		for (String name : new String[] { DUMP_TXT, DUMP_GZ, DUMP_ZIP }) {
			Path p = cacheDir.resolve(name);
			if (Files.isRegularFile(p))
				return Optional.of(p);
		}
		return Optional.empty();
	}

	/** Whether the dump is present in the cache. */
	public static boolean isAvailable() {
		return dumpFile().isPresent();
	}

	/**
	 * Open a streaming reader over the {@code allCountries} table, decompressing a
	 * {@code .gz} or {@code .zip} transparently. The caller must close it.
	 *
	 * @return a buffered reader over the tab-separated rows
	 * @throws FileNotFoundException with a download hint if the dump is absent
	 * @throws IOException           on any read/decompress failure
	 */
	public static BufferedReader open() throws IOException {
		Path f = dumpFile().orElseThrow(() -> new FileNotFoundException(
				"GeoNames dump not found under " + cacheDir.toAbsolutePath()
						+ " — download " + DOWNLOAD_URL
						+ " and place the .zip (or its unzipped allCountries.txt) there."
						+ " Override the location with -Dcivstudio.geonames.cacheDir or $GEONAMES_CACHE_DIR."));
		return new BufferedReader(new InputStreamReader(openStream(f), StandardCharsets.UTF_8), 1 << 20);
	}

	private static InputStream openStream(Path f) throws IOException {
		String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
		InputStream in = new BufferedInputStream(Files.newInputStream(f), 1 << 20);
		if (name.endsWith(".zip")) {
			ZipInputStream zin = new ZipInputStream(in);
			for (ZipEntry e; (e = zin.getNextEntry()) != null;)
				if (e.getName().equalsIgnoreCase(DUMP_TXT))
					return zin; // positioned at the entry's data
			zin.close();
			throw new IOException("no " + DUMP_TXT + " entry inside " + f);
		}
		if (name.endsWith(".gz"))
			return new GZIPInputStream(in, 1 << 16);
		return in;
	}

	private static String loadLockVersion() {
		try (InputStream in = GeoNamesFiles.class.getResourceAsStream(LOCK_RESOURCE)) {
			if (in != null) {
				String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				if (!s.isEmpty())
					return s;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("failed reading " + LOCK_RESOURCE, e);
		}
		return FALLBACK_VERSION;
	}

	private static String prop(String sysProp, String env, String fallback) {
		String v = System.getProperty(sysProp);
		if (v == null || v.isBlank())
			v = System.getenv(env);
		return (v == null || v.isBlank()) ? fallback : v.trim();
	}
}
