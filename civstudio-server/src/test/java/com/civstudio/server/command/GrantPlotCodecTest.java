package com.civstudio.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link GrantPlotCommand} through the {@link CommandCodec} (vassalage P3): the ruler's fief grants
 * must survive persistence byte-faithfully, or a restored session replays a different enfeoffment
 * history — plots held by the wrong houses.
 */
class GrantPlotCodecTest {

	private final CommandCodec codec = new CommandCodec(JsonMapper.builder().build());

	@Test
	void roundTripsColonyPlotAndNoble() {
		GrantPlotCommand cmd = new GrantPlotCommand(42, "Dhenijansar", 7, 1234);
		assertEquals("grantPlot", codec.type(cmd));
		GrantPlotCommand back = (GrantPlotCommand) codec.decode("grantPlot", 42, codec.payload(cmd));
		assertEquals(42, back.tick());
		assertEquals("Dhenijansar", back.colony());
		assertEquals(7, back.plotIndex());
		assertEquals(1234, back.nobleId());
	}

	@Test
	void roundTripsTheNullColonyShape() {
		// null colony (= the one colony) — the single-colony default
		GrantPlotCommand back = (GrantPlotCommand) codec.decode("grantPlot", 3,
				codec.payload(new GrantPlotCommand(3, null, 0, 99)));
		assertEquals(3, back.tick());
		assertNull(back.colony());
		assertEquals(0, back.plotIndex());
		assertEquals(99, back.nobleId());
	}
}
