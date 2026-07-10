package com.civstudio.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges a session's per-settlement CSVs into one <b>tidy</b> file per table.
 * <p>
 * Each colony writes its own prefixed CSVs ({@code Withacen-Prices.csv},
 * {@code Hopespeak-Prices.csv}, …) into the run's {@code output/<seed>/} folder
 * (see {@code Settlement.setSession} / {@link SimLog}). After every colony of a
 * {@link com.civstudio.settlement.GameSession} has finished — when the files are
 * flushed and closed — {@link #mergeSessionOutput} folds the per-settlement files
 * of each table into a single top-level {@code output/<seed>/<Table>.csv} with a
 * leading {@code Settlement} column telling the rows apart, and moves the raw
 * per-settlement files down into a {@code by-settlement/} subfolder. This is the
 * same "one file, told apart by a column" consolidation the printers already do
 * within a colony (a {@code Bank}/{@code Good} column), lifted one level to the
 * whole session — the long/tidy shape a cross-settlement analysis wants
 * ({@code (Settlement, Date, …)} as the key).
 * <p>
 * The merge runs once, single-threaded, after the concurrent run completes (see
 * {@code SessionRunner.runConcurrently}), so it is deterministic regardless of how
 * many colonies ran: settlements appear in the given order, each colony's rows in
 * their original chronological order. It scales flat with the colony count (a
 * sequential concat), unlike a shared live sink, which would contend a lock and
 * interleave non-deterministically across colony threads.
 */
public final class CsvMerger {

	/** The subfolder the raw per-settlement CSVs are moved into after merging. */
	public static final String BY_SETTLEMENT_DIR = "by-settlement";

	/** The column prepended to every merged row, naming the source settlement. */
	public static final String SETTLEMENT_COLUMN = "Settlement";

	private CsvMerger() {
	}

	/**
	 * Merge the per-settlement CSVs in {@code output/<seed>/} into one tidy file per
	 * table and demote the raw files to a {@code by-settlement/} subfolder. A
	 * no-op if the run folder does not exist or no matching files are found (e.g. a
	 * single colony whose printers used no settlement prefix), so it is always safe
	 * to call.
	 * <p>
	 * Each per-settlement file is assumed to be named {@code <name>-<Table>.csv},
	 * the convention the multi-colony sims use (the printer prefix is the colony's
	 * name plus a hyphen); a colony whose files do not match its name prefix is
	 * simply left untouched.
	 *
	 * @param seed            the session seed naming the run folder ({@code output/<seed>/})
	 * @param settlementNames the colonies' names, in the order rows should appear in
	 *                        each merged file (the run's colony order)
	 */
	public static void mergeSessionOutput(long seed, List<String> settlementNames) {
		Path dir = Paths.get("output", Long.toString(seed));
		if (!Files.isDirectory(dir))
			return;
		try {
			// table -> (settlement name -> its raw CSV), tables sorted for a stable
			// creation order, settlements in the given (colony) order
			Map<String, Map<String, Path>> tables = new TreeMap<>();
			for (String name : settlementNames) {
				String prefix = name + "-";
				try (DirectoryStream<Path> files =
						Files.newDirectoryStream(dir, prefix + "*.csv")) {
					for (Path file : files) {
						String fn = file.getFileName().toString();
						// strip the "<name>-" prefix and the ".csv" suffix -> table name
						String table = fn.substring(prefix.length(), fn.length() - 4);
						tables.computeIfAbsent(table, t -> new LinkedHashMap<>())
								.put(name, file);
					}
				}
			}
			if (tables.isEmpty())
				return;

			for (Map.Entry<String, Map<String, Path>> e : tables.entrySet())
				writeMergedTable(dir.resolve(e.getKey() + ".csv"), e.getValue());

			// demote the now-merged raw files into by-settlement/
			Path rawDir = dir.resolve(BY_SETTLEMENT_DIR);
			Files.createDirectories(rawDir);
			for (Map<String, Path> bySettlement : tables.values())
				for (Path file : bySettlement.values())
					Files.move(file, rawDir.resolve(file.getFileName()),
							StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			throw new UncheckedIOException(
					"failed to merge per-settlement CSVs in " + dir, ex);
		}
	}

	// write one merged table: the header once (with a leading Settlement column),
	// then every settlement's data rows in turn, each prefixed with its name
	private static void writeMergedTable(Path out, Map<String, Path> bySettlement)
			throws IOException {
		try (BufferedWriter w =
				Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
			boolean headerWritten = false;
			for (Map.Entry<String, Path> e : bySettlement.entrySet()) {
				String name = e.getKey();
				List<String> lines = Files.readAllLines(e.getValue(),
						StandardCharsets.UTF_8);
				if (lines.isEmpty())
					continue;
				if (!headerWritten) {
					w.write(SETTLEMENT_COLUMN + "," + lines.get(0));
					w.newLine();
					headerWritten = true;
				}
				for (int i = 1; i < lines.size(); i++) {
					w.write(name + "," + lines.get(i));
					w.newLine();
				}
			}
		}
	}
}
