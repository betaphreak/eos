package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;
import com.civstudio.data.Exports;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.civstudio.geo.Religion;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/anbennar/common/religions/*.txt} and
 * writes the committed {@code src/main/resources/map/religions.json} — the per-key
 * display name, group and map colour the religion layer joins each {@link
 * com.civstudio.geo.Province#religion()} to (loaded by {@link
 * com.civstudio.geo.WorldMap} into {@link com.civstudio.geo.Religion} records).
 * <p>
 * The source is nested {@code group = { religion_key = { color = { r g b } ... } }}.
 * A religion is identified as any depth-1 block that carries a {@code color} block
 * (this excludes non-religion group-level blocks such as
 * {@code flag_emblem_index_range}). Its real per-religion colour is used directly.
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ReligionExporter
 * </pre>
 */
public final class ReligionExporter {

	private static final String INPUT_DIR = "common/religions";
	private static final String OUTPUT = "civstudio-engine/target/generated/map/religions.json";

	private ReligionExporter() {
	}

	public static void main(String[] args) throws Exception {
		File[] files = AnbennarFiles.getDir(INPUT_DIR).toFile().listFiles((d, n) -> n.endsWith(".txt"));
		if (files == null)
			throw new IllegalStateException("religions dir not found: " + INPUT_DIR);

		List<Religion> religions = new ArrayList<>();
		for (File f : files) {
			String content = ClausewitzBlocks.stripComments(
					Files.readString(f.toPath(), StandardCharsets.ISO_8859_1));
			for (ClausewitzBlocks.Block group : ClausewitzBlocks.parse(content).blocks()) {
				for (ClausewitzBlocks.Block rel : ClausewitzBlocks.parse(group.body()).blocks()) {
					String color = religionColor(rel.body());
					if (color == null)
						continue; // a non-religion group-level block (no colour)
					religions.add(new Religion(rel.name(),
							ClausewitzBlocks.titleCase(rel.name()), group.name(), color));
				}
			}
		}
		religions.sort((a, b) -> a.key().compareTo(b.key()));

		File out = Exports.outFile(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, religions);
		System.out.println("wrote " + religions.size() + " religions to "
				+ out.getAbsolutePath());
	}

	private static String religionColor(String religionBody) {
		for (ClausewitzBlocks.Block b : ClausewitzBlocks.parse(religionBody).blocks())
			if (b.name().equals("color"))
				return ClausewitzBlocks.colorHex(b.body());
		return null;
	}
}
