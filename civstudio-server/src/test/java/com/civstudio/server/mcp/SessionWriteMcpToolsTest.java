package com.civstudio.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;

/**
 * Unit coverage for the admin-gated live-session write tools, Spring-free with a stub {@link McpAuthz}:
 * a non-admin is refused every write, and an admin can create a session, submit a tax-rate command,
 * step past its tick and see the ruler's lever move — the one legal write path
 * ({@code HostedSession.submit}) end to end.
 */
class SessionWriteMcpToolsTest {

	private static McpAuthz authz(boolean admin) {
		return new McpAuthz() {
			@Override public boolean isAdmin() { return admin; }
			@Override public String userId() { return admin ? "admin" : null; }
		};
	}

	@Test
	void nonAdminIsRefusedEveryWrite() {
		SessionWriteMcpTools t = new SessionWriteMcpTools(new SessionHost(), authz(false));
		assertThrows(SecurityException.class, () -> t.createSession(null, null, null));
		assertThrows(SecurityException.class, () -> t.controlSession("x", "pause", null));
		assertThrows(SecurityException.class, () -> t.submitCommand("x", "setTaxRate", "bankProfit", 0.3, null, null));
	}

	@Test
	@Timeout(180)
	void adminCreatesControlsAndCommandsASession() throws Exception {
		SessionHost host = new SessionHost();
		SessionWriteMcpTools t = new SessionWriteMcpTools(host, authz(true));

		SessionWriteMcpTools.CreateResult created = t.createSession(SessionSpec.CARAVAN_DEMO, 888L, 4411);
		String id = created.id();
		HostedSession hs = host.get(id);
		assertNotNull(hs, "create_session should register the session");
		// the result reports what it founded — the scenario's shape/profile and the content version
		assertEquals("STANDARD_COLONY", created.shape(), "caravan-demo founds a standard colony");
		assertEquals("default", created.balanceProfile());

		// an unknown scenario is rejected up front (not silently founded as standard, which is only
		// the RESTORE fallback), and the error names the valid keys
		IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
				() -> t.createSession("no-such-scenario", 1L, 4411));
		assertTrue(bad.getMessage().contains("caravan-demo"),
				"the rejection should list valid scenario keys: " + bad.getMessage());

		t.controlSession(id, "pause", null); // pause so stepping is deterministic
		assertNotNull(hs.colonies().get(0).getRuler(), "the demo colony has a ruler");

		SessionWriteMcpTools.CommandResult cmd = t.submitCommand(id, "setTaxRate", "bankProfit", 0.3, null, null);
		assertTrue(cmd.accepted());
		assertEquals("BANK_PROFIT", cmd.lever());

		// step past the command's tick; the ruler's bank-profit lever moves to the commanded rate
		t.controlSession(id, "step", 3L);
		long deadline = System.nanoTime() + 120_000_000_000L;
		while (hs.colonies().get(0).getRuler().getBankProfitTaxRate() < 0.3 && System.nanoTime() < deadline)
			Thread.sleep(5);
		assertEquals(0.3, hs.colonies().get(0).getRuler().getBankProfitTaxRate(), 1e-9,
				"the submitted command should have moved the lever");

		// validation: unknown action / lever / out-of-range rate are rejected
		assertThrows(IllegalArgumentException.class, () -> t.controlSession(id, "bogus", null));
		assertThrows(IllegalArgumentException.class, () -> t.submitCommand(id, "setTaxRate", "nope", 0.3, null, null));
		assertThrows(IllegalArgumentException.class, () -> t.submitCommand(id, "setTaxRate", "bankProfit", 1.5, null, null));

		host.stopAll();
	}
}
