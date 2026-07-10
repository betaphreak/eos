package com.civstudio.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
	}
}
