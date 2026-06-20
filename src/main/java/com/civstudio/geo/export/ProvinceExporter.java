package com.civstudio.geo.export;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: exports the Strapi world content's geographic tables to the
 * {@code /provinces.json} resource the core {@link com.civstudio.geo.WorldMap}
 * loads. This keeps the running simulation free of any database dependency —
 * the export is a build-time/manual step, its output committed to the repo (the
 * core never reads Postgres).
 * <p>
 * It flattens {@code province -> province_area -> region} to the region's stable
 * {@code raw_key} (a "used id", not a DB surrogate), derives a {@code longitude}
 * from the province's map bounding-box centroid (the table stores only
 * latitude), and materializes the {@code provinces_neighbors_lnk} edges into a
 * <em>symmetric</em> adjacency. Both the province key and the neighbor
 * references use the game's {@code province_id} (the "used id"), not the Strapi
 * surrogate {@code id} — the adjacency surrogate ids are translated to
 * {@code province_id} via a join. (This assumes {@code province_id} is unique;
 * two double-imported duplicates were merged in the source so it is.)
 * <p>
 * Connection parameters come from the environment so no credentials are
 * committed: {@code GEO_DB_URL} (default {@code
 * jdbc:postgresql://localhost:5432/strapi-civbox}), {@code GEO_DB_USER} (default
 * {@code civbox}), and {@code PGPASSWORD} (required). Run via:
 *
 * <pre>
 * mvn exec:exec -Dsim.main=com.civstudio.geo.export.ProvinceExporter
 * </pre>
 *
 * (the forked JVM inherits the shell environment, so set {@code PGPASSWORD}
 * first). See {@code docs/geography.md}.
 */
public final class ProvinceExporter {

	private static final String OUTPUT = "src/main/resources/provinces.json";

	// The world content is a single equirectangular-ish image: latitude is
	// precomputed per province; longitude is a linear map of the bounding-box
	// centroid x over the global x extent [min(min_x), max(max_x)] -> [-180, 180].
	private static final String SQL = """
			WITH bounds AS (
			  SELECT min(min_x)::numeric AS xmin, max(max_x)::numeric AS xmax FROM provinces
			),
			edges AS (
			  -- translate the surrogate-id adjacency to province_id, both directions
			  SELECT pa.province_id AS a, pb.province_id AS b
			  FROM provinces_neighbors_lnk l
			  JOIN provinces pa ON pa.id = l.province_id
			  JOIN provinces pb ON pb.id = l.inv_province_id
			  WHERE pa.province_id <> pb.province_id
			  UNION
			  SELECT pb.province_id AS a, pa.province_id AS b
			  FROM provinces_neighbors_lnk l
			  JOIN provinces pa ON pa.id = l.province_id
			  JOIN provinces pb ON pb.id = l.inv_province_id
			  WHERE pa.province_id <> pb.province_id
			),
			nbr AS (
			  SELECT a AS pid, json_agg(b ORDER BY b) AS neighbors FROM edges GROUP BY a
			),
			reg AS (
			  SELECT p.province_id AS pid, min(rg.raw_key) AS region_key
			  FROM provinces p
			  JOIN provinces_province_area_lnk ppl ON ppl.province_id = p.id
			  JOIN province_areas_region_lnk parl ON parl.province_area_id = ppl.province_area_id
			  JOIN regions rg ON rg.id = parl.region_id
			  GROUP BY p.province_id
			)
			SELECT json_agg(
			  json_build_object(
			    'id',         p.province_id,
			    'name',       p.name,
			    'lat',        p.latitude,
			    'lon',        round((((p.min_x + p.max_x) / 2.0 - b.xmin) / (b.xmax - b.xmin) * 360 - 180)::numeric, 2),
			    'plots',      p.plots,
			    'waterPlots', p.water_plots,
			    'type',       p.province_type,
			    'region',     r.region_key,
			    'neighbors',  COALESCE(n.neighbors, '[]'::json)
			  ) ORDER BY p.province_id
			)
			FROM provinces p
			CROSS JOIN bounds b
			LEFT JOIN nbr n ON n.pid = p.province_id
			LEFT JOIN reg r ON r.pid = p.province_id
			""";

	private ProvinceExporter() {
	}

	public static void main(String[] args) throws Exception {
		String url = env("GEO_DB_URL", "jdbc:postgresql://localhost:5432/strapi-civbox");
		String user = env("GEO_DB_USER", "civbox");
		String password = System.getenv("PGPASSWORD");
		if (password == null || password.isBlank())
			throw new IllegalStateException(
					"set PGPASSWORD (and optionally GEO_DB_URL / GEO_DB_USER)");

		String json;
		try (Connection c = DriverManager.getConnection(url, user, password);
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(SQL)) {
			if (!rs.next() || rs.getString(1) == null)
				throw new IllegalStateException("export query returned no rows");
			json = rs.getString(1);
		}

		// reparse + pretty-print so the committed resource is diffable
		ObjectMapper mapper = new ObjectMapper();
		Object tree = mapper.readValue(json, Object.class);
		File out = new File(OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(out, tree);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	private static String env(String key, String fallback) {
		String v = System.getenv(key);
		return (v == null || v.isBlank()) ? fallback : v;
	}
}
