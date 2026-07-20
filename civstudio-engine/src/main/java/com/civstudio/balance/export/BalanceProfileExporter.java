package com.civstudio.balance.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.civstudio.balance.BalanceProfiles;

/**
 * Dev tool: writes the compiled balance profiles out as content — the seed for the content store's
 * {@code balance-profile} entries, and the file {@link BalanceProfiles#RESOURCE
 * /balance/profiles.json} is served from. Sibling of {@code era.export.EconomyExporter}.
 *
 * <p>Reads no external source: the numbers already live on the agent {@code *Config.DEFAULT}s; the
 * point is to move them from compiled constants to authored content so a tuned profile can be added
 * without a recompile. Emitting exactly the compiled defaults is what makes the cutover
 * behaviour-neutral — {@code BalanceProfileCodecTest} asserts the round-trip.
 *
 * <p>Usage: {@code mvn -pl civstudio-engine exec:exec
 * -Dsim.main=com.civstudio.balance.export.BalanceProfileExporter [-Dexec.args=<out>]}, or pass the
 * output path as the first argument. Default {@code src/main/resources/generated/balance/profiles.json}
 * (gitignored, flattened onto the classpath root at package time).
 */
public final class BalanceProfileExporter {

	private static final Path DEFAULT_OUT = Path.of("civstudio-engine", "src", "main", "resources",
			"generated", "balance", "profiles.json");

	private BalanceProfileExporter() {
	}

	public static void main(String[] args) throws IOException {
		Path out = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUT;
		Files.createDirectories(out.getParent());
		String json = BalanceProfiles.canonicalJson();
		Files.writeString(out, json + System.lineSeparator(), StandardCharsets.UTF_8);
		System.err.println("BalanceProfileExporter: wrote " + out.toAbsolutePath() + " ("
				+ json.lines().count() + " lines)");
	}
}
