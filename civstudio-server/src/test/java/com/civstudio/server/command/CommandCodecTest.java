package com.civstudio.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** The command codec round-trips a command through its persisted {@code (type, payload)} form. */
class CommandCodecTest {

	private final CommandCodec codec = new CommandCodec(new ObjectMapper());

	@Test
	void roundTripsSetTaxRate() {
		SetTaxRateCommand cmd = new SetTaxRateCommand(7, SetTaxRateCommand.Lever.NOBLE_INCOME, 0.42);

		String type = codec.type(cmd);
		String payload = codec.payload(cmd);
		assertEquals("setTaxRate", type);

		GameCommand back = codec.decode(type, 7, payload);
		SetTaxRateCommand s = assertInstanceOf(SetTaxRateCommand.class, back);
		assertEquals(7, s.tick());
		assertEquals(SetTaxRateCommand.Lever.NOBLE_INCOME, s.lever());
		assertEquals(0.42, s.rate(), 1e-9);
		assertNull(s.colony(), "no colony named → every colony, as before Phase 2");
	}

	@Test
	void roundTripsTheColonyItTargets() {
		SetTaxRateCommand cmd = new SetTaxRateCommand(9, "Dhenijansar",
				SetTaxRateCommand.Lever.BANK_PROFIT, 0.25);

		String payload = codec.payload(cmd);
		SetTaxRateCommand s = assertInstanceOf(SetTaxRateCommand.class,
				codec.decode(codec.type(cmd), 9, payload));
		assertEquals("Dhenijansar", s.colony(), "the target survives the log — a replay must move "
				+ "the same colony's lever, not everyone's");
		assertEquals(SetTaxRateCommand.Lever.BANK_PROFIT, s.lever());
		assertEquals(0.25, s.rate(), 1e-9);
	}

	/**
	 * Rows written before Phase 2 carry no {@code colony} and meant "every colony" when they were
	 * issued. They must still mean that, or replaying an old log reaches a state the run never had.
	 */
	@Test
	void aPrePhase2RowStillMeansEveryColony() {
		SetTaxRateCommand s = assertInstanceOf(SetTaxRateCommand.class,
				codec.decode("setTaxRate", 3, "{\"lever\":\"BANK_PROFIT\",\"rate\":0.1}"));
		assertNull(s.colony());
		assertEquals(0.1, s.rate(), 1e-9);
	}

	/** An all-colonies command still persists exactly as it always did — no stray null field. */
	@Test
	void anUntargetedCommandsPayloadIsUnchanged() {
		String payload = codec.payload(new SetTaxRateCommand(1, SetTaxRateCommand.Lever.BANK_PROFIT, 0.5));
		assertFalse(payload.contains("colony"), "wrote: " + payload);
	}
}
