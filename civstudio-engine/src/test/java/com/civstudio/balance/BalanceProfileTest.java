package com.civstudio.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.market.WeddingConfig;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * {@link BalanceProfile} — the aggregate agent-tuning seam ({@code docs/studio-control-plane-plan.md}
 * §A0): applying {@code DEFAULT} changes nothing, and a tuned profile reaches founding through one
 * call rather than a dozen per-agent setters.
 */
class BalanceProfileTest {

	@Test
	void theDefaultProfileIsExactlyTheComposedDefaults() {
		BalanceProfile p = BalanceProfile.DEFAULT;
		assertSame(com.civstudio.agent.firm.FirmConfig.DEFAULT, p.firm());
		assertSame(com.civstudio.bank.BankConfig.DEFAULT, p.bank());
		assertSame(com.civstudio.agent.noble.NobleConfig.DEFAULT, p.noble());
		assertSame(com.civstudio.agent.RetinueConfig.DEFAULT, p.retinue());
		assertSame(com.civstudio.market.WeddingConfig.DEFAULT, p.wedding());
		assertSame(com.civstudio.agent.firm.BuilderConfig.DEFAULT, p.builderFirm());
	}

	@Test
	void applyingTheDefaultProfileIsBehaviourNeutral() {
		// the A0 ship criterion: setBalanceProfile(DEFAULT) must leave a colony byte-identical. Run
		// two short colonies on the same seed, one untouched and one handed DEFAULT, and compare a
		// population outcome that would move if any config had shifted.
		int a = runToLaborerCount(null);
		int b = runToLaborerCount(BalanceProfile.DEFAULT);
		assertEquals(a, b, "applying DEFAULT changed the run — the profile is not behaviour-neutral");
	}

	@Test
	void aTunedProfileReachesFounding() {
		// weddingConfig.capacity 0 disables the wedding market; if setBalanceProfile actually wired
		// the wedding config through, the harness's weddingConfig reflects it before founding
		BalanceProfile noWeddings = BalanceProfile.DEFAULT.toBuilder()
				.wedding(WeddingConfig.DEFAULT.toBuilder().capacity(0).build())
				.build();
		SimulationHarness h = SimulationHarness.create(SimulationConfig.DEFAULT, 7654321L);
		h.setBalanceProfile(noWeddings);
		assertEquals(0, h.getWeddingConfig().capacity(),
				"the profile's wedding capacity did not reach the harness");
	}

	@Test
	void theProfileDoesNotOverrideTheEconomyOwnedLaborShare() {
		// laborShare is an Era.Economy field, not a FirmConfig one the profile owns — so a profile
		// carrying a different firm.laborShare must NOT win over the colony's economy (mirrors
		// tuneEconomy's splice). Otherwise two authorities would fight over one number.
		SimulationHarness h = SimulationHarness.create(SimulationConfig.DEFAULT, 7654321L);
		double economyShare = h.getColony().getEconomy().laborShare();
		h.setBalanceProfile(BalanceProfile.DEFAULT.toBuilder()
				.firm(com.civstudio.agent.firm.FirmConfig.DEFAULT.toBuilder().laborShare(0.99).build())
				.build());
		assertEquals(economyShare, h.getFirmConfig().laborShare(),
				"the economy remains the authority on labor share; the profile must not override it");
	}

	// found a standard colony, run a short horizon, and report its living laborer households — a
	// number sensitive to almost any config change (pool sizing, firm output, wages, births)
	private static int runToLaborerCount(BalanceProfile profile) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(2).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L);
		if (profile != null)
			h.setBalanceProfile(profile);
		h.foundStandardColony();
		h.run();
		int labs = 0;
		for (com.civstudio.agent.Agent ag : h.getColony().getAgents())
			if (ag instanceof com.civstudio.agent.laborer.Laborer l && l.isAlive())
				labs++;
		return labs;
	}
}
