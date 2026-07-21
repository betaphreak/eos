package com.civstudio.data;

import java.io.File;

/**
 * Small shared helper for the content <b>exporters</b> (dev tools that write the
 * {@code target/generated/} build-scratch tree, see {@code docs/exporter-outputs.md}).
 * <p>
 * The scratch tree lives under Maven {@code target/} and so does not pre-exist on a fresh checkout or
 * after {@code mvn clean} — and {@code ObjectMapper.writeValue(File, …)} does <b>not</b> create parent
 * directories. So every exporter must ensure its output directory exists before writing;
 * {@link #outFile(String)} does exactly that, returning the {@link File} to write to.
 */
public final class Exports {

	private Exports() {
	}

	/**
	 * The output {@link File} for {@code path}, with its parent directory created if absent — so an
	 * exporter can write straight into a not-yet-existing {@code target/generated/…} location.
	 *
	 * @param path the output path (e.g. {@code "civstudio-engine/target/generated/techs.json"})
	 * @return the file to write to (its parent directory now exists)
	 */
	public static File outFile(String path) {
		File f = new File(path);
		File parent = f.getParentFile();
		if (parent != null)
			parent.mkdirs();
		return f;
	}
}
