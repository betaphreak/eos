package com.civstudio.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.civstudio.agent.ruler.Ruler;
import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.render.SessionSnapshot;

/**
 * The first interactive player action (see {@code docs/client-server.md}, Phase B): a {@link
 * SetTaxRateCommand} submitted to a hosted session moves the ruler's tax lever at the top of
 * its tick, the rate is clamped to [0, 1], and the change is visible in the render snapshot.
 * Single-stepped (not wall-clock-paced) so it is deterministic and fast.
 */
class SetTaxRateCommandTest {

	private static final int DHENIJANSAR = 4411;

	// block until the session has emitted a snapshot at or beyond `tick`, or fail on timeout
	private static SessionSnapshot awaitSnapshot(HostedSession hs, long tick, long timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		while (true) {
			SessionSnapshot snap = hs.currentSnapshot();
			if (snap != null && snap.tick() >= tick)
				return snap;
			if (System.nanoTime() > deadline)
				fail("timed out waiting for tick " + tick);
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("interrupted");
			}
		}
	}

	@Test
	@Timeout(90)
	void movesTheRulerLeverAtItsTickClampsAndShowsInSnapshot() {
		SessionHost host = new SessionHost();
		// a plain (non-demo) spec founds the same standard, ruler-bearing colony without the
		// caravans — all we need for the tax lever
		HostedSession hs = host.create(new SessionSpec(4242L, "tax-test", DHENIJANSAR));
		try {
			Ruler ruler = hs.colonies().get(0).getRuler();
			assertNotNull(ruler, "a standard colony has a ruler to tax");

			hs.submit(new SetTaxRateCommand(2, SetTaxRateCommand.Lever.BANK_PROFIT, 0.25));
			// 1.5 is out of range — the ruler clamps it to 1.0
			hs.submit(new SetTaxRateCommand(2, SetTaxRateCommand.Lever.NOBLE_INCOME, 1.5));

			hs.startPaused();
			hs.step(3);
			SessionSnapshot snap = awaitSnapshot(hs, 3, 60_000);

			// re-fetch the ruler (a successor, if the sovereign died in these days, inherits
			// the live value)
			Ruler now = hs.colonies().get(0).getRuler();
			assertEquals(0.25, now.getBankProfitTaxRate(), 1e-9,
					"the bank-profit lever moved to the commanded rate");
			assertEquals(1.0, now.getNobleIncomeTaxRate(), 1e-9,
					"an out-of-range rate is clamped to [0,1]");
			assertEquals(0.25, snap.colonies().get(0).bankProfitTax(), 1e-9,
					"the snapshot surfaces the lever so a client sees it land");
			assertEquals(2, hs.commandLog().history().size(),
					"both commands entered the replay log");
		} finally {
			hs.stop();
		}
	}
}
