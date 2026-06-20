package com.civstudio.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.civstudio.io.sink.OutputMode;
import com.civstudio.simulation.HanseaticEconomy;
import com.civstudio.simulation.HomogeneousEconomy;
import com.civstudio.simulation.Persistence;
import com.civstudio.simulation.SmallOpenEconomy;

import lombok.extern.java.Log;

/**
 * Runs one simulation scenario with persistence wired in, then exits. Selects the
 * scenario from the {@code --sim=<name>} argument (default {@code
 * HomogeneousEconomy}); installs {@link DbColonyPersistence} as the active {@link
 * Persistence} handler (unless output is CSV-only) so every colony the scenario
 * creates is persisted, invokes the scenario's {@code run()}, and finally clears
 * the handler and stamps the run finished.
 */
@Component
@Log
public class SimRunner implements CommandLineRunner {

	private final DbColonyPersistence persistence;
	private final OutputMode outputMode;

	public SimRunner(DbColonyPersistence persistence,
			@Value("${eos.output-mode:BOTH}") OutputMode outputMode) {
		this.persistence = persistence;
		this.outputMode = outputMode;
	}

	@Override
	public void run(String... args) {
		String sim = argValue(args, "--sim=", "HomogeneousEconomy");
		log.info("Running scenario " + sim + " with output mode " + outputMode);
		persistence.setScenario(sim);

		boolean persisting = outputMode != OutputMode.CSV;
		if (persisting)
			Persistence.setHandler(persistence);
		try {
			dispatch(sim);
		} finally {
			if (persisting) {
				Persistence.setHandler(null);
				persistence.finish();
			}
		}
	}

	// invoke the chosen scenario's run(); each builds and runs its colony/colonies
	private void dispatch(String sim) {
		switch (sim) {
			case "HomogeneousEconomy" -> HomogeneousEconomy.run();
			case "SmallOpenEconomy" -> SmallOpenEconomy.run();
			case "HanseaticEconomy" -> HanseaticEconomy.run();
			default -> throw new IllegalArgumentException("Unknown --sim: " + sim
					+ " (expected HomogeneousEconomy, SmallOpenEconomy or HanseaticEconomy)");
		}
	}

	// the value of the first arg starting with prefix, or fallback if none
	private static String argValue(String[] args, String prefix, String fallback) {
		for (String a : args)
			if (a.startsWith(prefix))
				return a.substring(prefix.length());
		return fallback;
	}
}
