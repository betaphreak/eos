package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.Terrain;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/CIV4TerrainInfos.xml} and emits the curated
 * settleable-land subset to the committed {@code /terrains.json} resource the
 * core {@link com.civstudio.geo.TerrainRegistry} loads. The curation (which
 * terrains to keep) and the XML&rarr;record field mapping live here; the running
 * simulation never touches the XML. Run manually, like the geo exporters:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.TerrainExporter
 * </pre>
 *
 * Hills and peaks are deliberately excluded — they are a per-plot {@code PlotType}
 * axis, not a terrain (see {@code docs/plots.md}). The <b>shelf water</b> terrains
 * (coast/sea + their polar/tropical climate variants, lake shore/lake) are kept so
 * the coastal-shelf plot generation can ground water plots and the sea bonuses'
 * {@code validTerrains} resolve (see {@code docs/coastlines.md}); the deep-ocean /
 * trench terrains stay excluded (the map generates no deep-water plots).
 */
public final class TerrainExporter {

	private static final String INPUT = "data/civ4/CIV4TerrainInfos.xml";
	private static final String OUTPUT = "src/main/resources/terrains.json";

	/**
	 * The curated subset: the settleable land terrains (in {@code docs/plots.md}
	 * order) followed by the <b>shelf water</b> terrains — coast/sea with their
	 * polar/tropical climate variants, plus lake shore/lake — that the coastal-shelf
	 * generation grounds water plots on. Deep sea/ocean/trench stay out.
	 */
	private static final Set<String> KEEP = new LinkedHashSet<>(List.of(
			"TERRAIN_GRASSLAND", "TERRAIN_LUSH", "TERRAIN_PLAINS", "TERRAIN_SCRUB",
			"TERRAIN_MARSH", "TERRAIN_MUDDY", "TERRAIN_ROCKY", "TERRAIN_BADLAND",
			"TERRAIN_JAGGED", "TERRAIN_BARREN", "TERRAIN_DESERT", "TERRAIN_DUNES",
			"TERRAIN_SALT_FLATS", "TERRAIN_TAIGA", "TERRAIN_TUNDRA",
			"TERRAIN_PERMAFROST",
			// shelf water — the coastal plots the sea bonuses attach to
			"TERRAIN_COAST", "TERRAIN_COAST_POLAR", "TERRAIN_COAST_TROPICAL",
			"TERRAIN_SEA", "TERRAIN_SEA_POLAR", "TERRAIN_SEA_TROPICAL",
			"TERRAIN_LAKE_SHORE", "TERRAIN_LAKE"));

	/**
	 * Authored terrains with <b>no Civ4 XML source</b> — appended after the curated XML
	 * subset. These are Anbennar-specific grounds the base game has no peer for, with
	 * hand-set yields:
	 * <ul>
	 * <li>{@code TERRAIN_CAVERN} — the underground Serpentspine cave floor (see {@code
	 * docs/underworld.md}). Food-scarce (meager cave-fungus farming) but production-rich
	 * (ore/stone): {@code [food 1, prod 2, commerce 0]}. Assigned to {@link
	 * com.civstudio.geo.ProvinceType#CAVERN} provinces by {@link
	 * com.civstudio.geo.ProvincePlotField}, replacing the mountain the raster reads.</li>
	 * <li>{@code TERRAIN_MUSHROOM_FOREST} — the <em>surface</em> fungal woodland of the
	 * Haless {@code mushroom_forest_region} (not underground). Food-bearing: {@code
	 * [food 2, prod 1, commerce 0]}.</li>
	 * </ul>
	 */
	private static final List<Terrain> SYNTHETIC = List.of(
			new Terrain("TERRAIN_CAVERN", new int[] { 1, 2, 0 }, true, 0, 0, 1),
			new Terrain("TERRAIN_MUSHROOM_FOREST", new int[] { 2, 1, 0 }, true, 0, 0, 1));

	private TerrainExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.parse(INPUT);
		List<Terrain> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(doc, "TerrainInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || !KEEP.contains(type))
				continue;
			out.add(new Terrain(
					type,
					Civ4Xml.yields(info, "Yields", "iYield"),
					Civ4Xml.boolVal(info, "bFound"),
					Civ4Xml.intVal(info, "iBuildModifier", 0),
					Civ4Xml.intVal(info, "iHealthPercent", 0),
					Civ4Xml.intVal(info, "iMovement", 1)));
			seen.add(type);
		}
		Set<String> missing = new LinkedHashSet<>(KEEP);
		missing.removeAll(seen);
		if (!missing.isEmpty())
			throw new IllegalStateException("curated terrains not found in XML: " + missing);

		// the authored, source-less terrains (cavern floor, surface mushroom forest)
		out.addAll(SYNTHETIC);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " terrains to " + f.getAbsolutePath());
	}
}
