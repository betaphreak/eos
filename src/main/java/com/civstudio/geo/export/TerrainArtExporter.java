package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.TerrainArtInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/CIV4ArtDefines_Terrain.xml} (the
 * {@code TerrainArtInfo} art bindings) and emits the curated land subset to the
 * committed {@code /map/terrain-art.json} resource, keyed by the <b>gameplay</b>
 * {@code TERRAIN_*} type. The art defines key on {@code ART_DEF_TERRAIN_*}; this
 * exporter joins them to gameplay terrain via {@code TerrainInfo.ArtDefineTag}
 * (read from {@code data/civ4/CIV4TerrainInfos.xml}) at export time, so the output
 * aligns 1:1 with {@code TerrainExporter}'s {@code /terrains.json} and the runtime
 * needs no join. The running simulation never touches the XML. Run manually, like
 * the other geo exporters:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.TerrainArtExporter
 * </pre>
 *
 * The curated set mirrors {@link TerrainExporter#KEEP the gameplay curation}
 * (settleable land only; hills/peaks are a {@code PlotType} axis, water/space are
 * skipped). The {@code .dds} paths it records point into the {@code UnpackedArt/art}
 * source tree; the web build (see {@code docs/ported-terrain-art-system.md} §10)
 * decodes and bakes them — the raw {@code .dds} never ships. See also §4.1.
 */
public final class TerrainArtExporter {

	private static final String ART = "data/civ4/CIV4ArtDefines_Terrain.xml";
	private static final String INFOS = "data/civ4/CIV4TerrainInfos.xml";
	private static final String OUTPUT = "src/main/resources/map/terrain-art.json";

	/** The curated land subset — the same 16 terrains {@link TerrainExporter} keeps. */
	private static final Set<String> KEEP = new LinkedHashSet<>(List.of(
			"TERRAIN_GRASSLAND", "TERRAIN_LUSH", "TERRAIN_PLAINS", "TERRAIN_SCRUB",
			"TERRAIN_MARSH", "TERRAIN_MUDDY", "TERRAIN_ROCKY", "TERRAIN_BADLAND",
			"TERRAIN_JAGGED", "TERRAIN_BARREN", "TERRAIN_DESERT", "TERRAIN_DUNES",
			"TERRAIN_SALT_FLATS", "TERRAIN_TAIGA", "TERRAIN_TUNDRA",
			"TERRAIN_PERMAFROST"));

	private TerrainArtExporter() {
	}

	public static void main(String[] args) throws Exception {
		// 1. gameplay terrain -> its art define tag (TerrainInfo.ArtDefineTag)
		Document infos = Civ4Xml.parse(INFOS);
		Map<String, String> artTag = new LinkedHashMap<>();
		for (Element info : Civ4Xml.infos(infos, "TerrainInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type != null && KEEP.contains(type))
				artTag.put(type, Civ4Xml.text(info, "ArtDefineTag"));
		}

		// 2. art tag -> its TerrainArtInfo element
		Document art = Civ4Xml.parse(ART);
		Map<String, Element> byTag = new HashMap<>();
		for (Element ai : Civ4Xml.infos(art, "TerrainArtInfo"))
			byTag.put(Civ4Xml.text(ai, "Type"), ai);

		// 3. emit one record per curated terrain, in the curated order
		List<TerrainArtInfo> out = new ArrayList<>();
		for (String terrain : KEEP) {
			String tag = artTag.get(terrain);
			if (tag == null)
				throw new IllegalStateException("no ArtDefineTag for curated terrain " + terrain
						+ " in " + INFOS);
			Element ai = byTag.get(tag);
			if (ai == null)
				throw new IllegalStateException("no TerrainArtInfo for " + tag
						+ " (terrain " + terrain + ") in " + ART);
			out.add(new TerrainArtInfo(
					terrain, tag,
					Civ4Xml.text(ai, "Path"),
					Civ4Xml.text(ai, "Grid"),
					Civ4Xml.text(ai, "Detail"),
					Civ4Xml.intVal(ai, "LayerOrder", 0),
					Civ4Xml.boolVal(ai, "AlphaShader"),
					blendTable(ai)));
		}

		File f = new File(OUTPUT);
		f.getParentFile().mkdirs();
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " terrain-art entries to " + f.getAbsolutePath());
	}

	// the 16-way blend table: neighbour bitmask "01".."15" -> "index,rotation ..." entries
	private static Map<String, String> blendTable(Element ai) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int mask = 1; mask <= 15; mask++) {
			String v = Civ4Xml.text(ai, String.format("TextureBlend%02d", mask));
			if (v != null && !v.isEmpty())
				m.put(String.format("%02d", mask), v);
		}
		return m;
	}
}
