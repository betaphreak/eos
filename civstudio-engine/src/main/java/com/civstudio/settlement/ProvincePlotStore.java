package com.civstudio.settlement;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.civstudio.geo.PlotGeo;
import com.civstudio.geo.PlotType;
import com.civstudio.geo.TerrainRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists a province's generated plot field to {@code map/provinces/<id>.json} and loads
 * it back, so the (expensive) per-tile generation is paid <b>once</b> and the canonical
 * field is reused by every run. Because the field is now generated seed-independently
 * (see {@link com.civstudio.util.RngSeed#forProvinceCanonical}), the persisted file always
 * equals what any run would produce — a province's geography is a property of the map, not
 * of a particular run (see {@code docs/province-plots.md}).
 * <p>
 * A plot is stored as its raster position, river code, and the Civ4 type keys of its
 * terrain / relief / feature / bonus (resolved back through the {@link TerrainRegistry} on
 * load). The record is <b>gzip-compressed JSON</b> ({@code <id>.json.gz}) — the field is
 * highly repetitive (repeated type keys and field names), so gzip shrinks it ~25&times; over
 * plain JSON while staying trivially (de)serialized. Writes go to {@code
 * civstudio-engine/src/main/resources/map/provinces/} (relative to the working directory, the
 * repo root under Maven — the engine module's committed resource location after the reactor
 * split); reads prefer that file, then fall back to the packaged classpath resource.
 */
public final class ProvincePlotStore {

	private static final String WRITE_DIR = "civstudio-engine/src/main/resources/map/provinces";
	private static final String RESOURCE_DIR = "/map/provinces";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	// gzipped-JSON file name for a province
	private static String fileName(int provinceId) {
		return provinceId + ".json.gz";
	}

	private ProvincePlotStore() {
	}

	/**
	 * One persisted plot — the {@link Plot}'s {@link PlotGeo raster scalars} (kept flat, so the
	 * JSON the web reads is unchanged) plus its terrain/relief/feature/bonus as Civ4 type keys.
	 * The object&harr;key mapping lives here in {@link #of}/{@link #toPlot} rather than scattered
	 * through {@code save}/{@code load}, so adding a field is a one-record change.
	 */
	private record StoredPlot(int x, int y, int river, String terrain, String plotType,
			String feature, String bonus, int elevation, int coast) {

		/** The persisted form of a runtime plot: its raster scalars + its type keys. */
		static StoredPlot of(Plot p) {
			PlotGeo g = p.geo();
			return new StoredPlot(g.x(), g.y(), g.river(), p.terrain().type(), p.plotType().name(),
					p.feature() == null ? null : p.feature().type(),
					p.bonus() == null ? null : p.bonus().type(), g.elevation(), g.coast());
		}

		/** Resolve back to a runtime plot, looking the type keys up in {@code registry}. */
		Plot toPlot(TerrainRegistry registry) {
			return new Plot(new PlotGeo(x, y, river, elevation, coast),
					registry.terrain(terrain), PlotType.valueOf(plotType),
					feature == null ? null : registry.feature(feature),
					bonus == null ? null : registry.bonus(bonus));
		}
	}

	/**
	 * Load a province's persisted plot field, or {@code null} if none is stored yet.
	 *
	 * @param provinceId the province id
	 * @param registry   the terrain/feature/bonus registry to resolve the type keys
	 * @return the plots, or {@code null} if the province has not been persisted
	 */
	public static List<Plot> load(int provinceId, TerrainRegistry registry) {
		try (InputStream in = open(provinceId)) {
			if (in == null)
				return null;
			List<StoredPlot> stored = MAPPER.readValue(in,
					new TypeReference<List<StoredPlot>>() {
					});
			List<Plot> plots = new ArrayList<>(stored.size());
			for (StoredPlot sp : stored)
				plots.add(sp.toPlot(registry));
			return plots;
		} catch (IOException e) {
			throw new UncheckedIOException(
					"failed to load persisted plot field for province " + provinceId, e);
		}
	}

	/**
	 * Persist a province's generated plot field to {@code map/provinces/<id>.json}.
	 *
	 * @param provinceId the province id
	 * @param plots      the generated plots to store
	 */
	public static void save(int provinceId, List<Plot> plots) {
		List<StoredPlot> stored = new ArrayList<>(plots.size());
		for (Plot p : plots)
			stored.add(StoredPlot.of(p));
		try {
			File dir = new File(WRITE_DIR);
			dir.mkdirs();
			try (OutputStream out = new GZIPOutputStream(
					Files.newOutputStream(new File(dir, fileName(provinceId)).toPath()))) {
				MAPPER.writeValue(out, stored);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(
					"failed to persist plot field for province " + provinceId, e);
		}
	}

	// the persisted field's decompressed stream: the writable file first (so a run reuses
	// what an earlier run generated), then the packaged classpath resource; null if neither
	// exists. The stored form is gzipped JSON, so the raw stream is wrapped in GZIP.
	private static InputStream open(int provinceId) throws IOException {
		File f = new File(WRITE_DIR, fileName(provinceId));
		if (f.exists())
			return new GZIPInputStream(Files.newInputStream(f.toPath()));
		InputStream res = ProvincePlotStore.class.getResourceAsStream(
				RESOURCE_DIR + "/" + fileName(provinceId));
		return res == null ? null : new GZIPInputStream(res);
	}
}
