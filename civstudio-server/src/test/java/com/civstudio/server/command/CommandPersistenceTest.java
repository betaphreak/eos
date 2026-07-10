package com.civstudio.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;

/**
 * With a datasource configured (H2 here), a submitted command is persisted and a re-founded
 * session replays it — the durable-savegame contract (state = f(spec, command log)). Verifies
 * that {@link com.civstudio.server.PersistenceConfig} wires the {@link JdbcCommandStore} and
 * that {@link SessionHost} loads it on (re)build.
 */
@SpringBootTest(properties = {
		"civstudio.demo.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:cmdlog;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=" })
class CommandPersistenceTest {

	private static final int DHENIJANSAR = 4411;

	@Autowired
	SessionHost host;

	@Autowired
	CommandStore store;

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	@Test
	void persistenceIsBackedByJdbc() {
		assertInstanceOf(JdbcCommandStore.class, store, "a datasource is configured, so JDBC");
	}

	@Test
	void persistsAndReplaysATaxCommand() {
		HostedSession hs = host.create(SessionSpec.caravanDemo(555L, DHENIJANSAR));
		hs.startPaused(); // paused at tick 0; the command lands on the next tick

		hs.submit(new SetTaxRateCommand(hs.tick() + 1, SetTaxRateCommand.Lever.BANK_PROFIT, 0.3));

		// the command is durably stored
		List<GameCommand> stored = store.load(hs.id());
		assertEquals(1, stored.size(), "the submitted command should be persisted");
		assertInstanceOf(SetTaxRateCommand.class, stored.get(0));

		// simulate a restart: drop the in-memory session and re-found it from the same spec
		host.remove(hs.id());
		HostedSession resumed = host.create(SessionSpec.caravanDemo(555L, DHENIJANSAR));

		// the resumed session's log carries the replayed command (pending, due at tick 1)
		assertEquals(1, resumed.commandLog().pendingCount(),
				"the re-founded session should replay its persisted command log");
	}
}
