package com.civstudio.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link QueueBuildCommand} through the {@link CommandCodec} (build-queue B6): the
 * player's construction decrees must survive persistence byte-faithfully, or a restored
 * session replays a different building history than the one the player ordered.
 */
class QueueBuildCodecTest {

	private final CommandCodec codec = new CommandCodec(JsonMapper.builder().build());

	@Test
	void roundTripsItemsColonyAndClear() {
		QueueBuildCommand cmd = new QueueBuildCommand(42, "Dhenijansar",
				List.of("BUILDING_GRANARY", "BUILDING_SMOKEHOUSE"), true);
		assertEquals("queueBuild", codec.type(cmd));
		QueueBuildCommand back = (QueueBuildCommand) codec.decode("queueBuild", 42,
				codec.payload(cmd));
		assertEquals(42, back.tick());
		assertEquals("Dhenijansar", back.colony());
		assertEquals(List.of("BUILDING_GRANARY", "BUILDING_SMOKEHOUSE"), back.items());
		assertTrue(back.clear());
	}

	@Test
	void roundTripsTheMinimalShape() {
		// null colony (= every build-economy colony), no clear — the single-colony default
		QueueBuildCommand back = (QueueBuildCommand) codec.decode("queueBuild", 7,
				codec.payload(new QueueBuildCommand(7, null, List.of("BUILDING_X"), false)));
		assertEquals(7, back.tick());
		assertEquals(List.of("BUILDING_X"), back.items());
		assertEquals(false, back.clear());
		assertEquals(null, back.colony());
	}
}
