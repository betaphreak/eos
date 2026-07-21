package com.civstudio.era.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.civstudio.era.EconomyCatalog;

/**
 * Dev tool: writes the compiled era × race economy matrix out as content — the seed for the content
 * store's {@code balance-profile}/economy entries, and the file
 * {@link EconomyCatalog#RESOURCE /balance/economies.json} is served from.
 *
 * <p>Unlike the {@code geo} exporters this reads no external source: the numbers already live on
 * {@link com.civstudio.era.Era}, and the point of the export is to move them from <em>compiled
 * constants</em> to <em>authored content</em> so a race column can be added without a recompile
 * (see {@code docs/studio-control-plane-plan.md} §A1). Emitting exactly what the engine currently
 * runs on is what makes the cutover behaviour-neutral — {@code EconomyCatalogTest} asserts the
 * round-trip.
 *
 * <p>Usage: {@code mvn -pl civstudio-engine exec:exec
 * -Dsim.main=com.civstudio.era.export.EconomyExporter [-Dexec.args=<out>]}, or pass the output path
 * as the first argument. Default {@code target/generated/balance/economies.json}.
 */
public final class EconomyExporter {

	// gitignored exporter build-scratch (under Maven target/); nothing loads it from here. Its content
	// reaches EconomyCatalog (/balance/economies.json) via the world-bundle — studio seeds from the
	// committed bundle, and the bundle is snapshotted from a local studio seeded off this scratch.
	private static final Path DEFAULT_OUT = Path.of("civstudio-engine", "target",
			"generated", "balance", "economies.json");

	private EconomyExporter() {
	}

	public static void main(String[] args) throws IOException {
		Path out = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUT;
		Files.createDirectories(out.getParent());
		String json = EconomyCatalog.canonicalJson();
		Files.writeString(out, json + System.lineSeparator(), StandardCharsets.UTF_8);
		System.err.println("EconomyExporter: wrote " + out.toAbsolutePath() + " ("
				+ json.lines().count() + " lines)");
	}
}
