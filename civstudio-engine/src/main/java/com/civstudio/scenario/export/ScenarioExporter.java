package com.civstudio.scenario.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.civstudio.scenario.ScenarioRegistry;

/**
 * Dev tool: writes the compiled scenario built-ins out as content — the seed for the content store's
 * {@code scenario} rows, and the file {@link ScenarioRegistry#RESOURCE /scenarios.json} is served
 * from. Sibling of {@code era.export.EconomyExporter} and {@code balance.export.BalanceProfileExporter}.
 *
 * <p>Usage: {@code mvn -pl civstudio-engine exec:exec
 * -Dsim.main=com.civstudio.scenario.export.ScenarioExporter [-Dexec.args=<out>]}. Default
 * {@code src/main/resources/generated/scenarios.json} (gitignored, flattened onto the classpath root).
 */
public final class ScenarioExporter {

	private static final Path DEFAULT_OUT = Path.of("civstudio-engine", "src", "main", "resources",
			"generated", "scenarios.json");

	private ScenarioExporter() {
	}

	public static void main(String[] args) throws IOException {
		Path out = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUT;
		Files.createDirectories(out.getParent());
		String json = ScenarioRegistry.canonicalJson();
		Files.writeString(out, json + System.lineSeparator(), StandardCharsets.UTF_8);
		System.err.println("ScenarioExporter: wrote " + out.toAbsolutePath() + " ("
				+ json.lines().count() + " lines)");
	}
}
