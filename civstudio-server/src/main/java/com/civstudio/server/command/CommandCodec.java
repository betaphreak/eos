package com.civstudio.server.command;

import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes a {@link GameCommand} to a durable {@code (type, payload-JSON)} pair and back, so
 * the {@link JdbcCommandStore} can persist and replay it. The {@code tick} is stored in its own
 * column (not the payload); the payload carries only the command-specific fields.
 * <p>
 * One command type today ({@code setTaxRate}); add a {@code case} here (and to {@link #type})
 * when a new {@link GameCommand} needs to be persisted.
 */
public final class CommandCodec {

	private final ObjectMapper json;

	public CommandCodec(ObjectMapper json) {
		this.json = json;
	}

	/** The stable wire type id under which a command is stored. */
	public String type(GameCommand command) {
		if (command instanceof SetTaxRateCommand)
			return "setTaxRate";
		throw new IllegalArgumentException("no codec for command " + command.getClass().getName());
	}

	/** The command-specific fields as a JSON object (the {@code tick} is a separate column). */
	public String payload(GameCommand command) {
		if (command instanceof SetTaxRateCommand s)
			return json.writeValueAsString(Map.of("lever", s.lever().name(), "rate", s.rate()));
		throw new IllegalArgumentException("no codec for command " + command.getClass().getName());
	}

	/** Reconstruct a command from a stored row. */
	public GameCommand decode(String type, long tick, String payload) {
		JsonNode n = json.readTree(payload);
		return switch (type) {
			case "setTaxRate" -> new SetTaxRateCommand(tick,
					SetTaxRateCommand.Lever.valueOf(n.get("lever").asText()),
					n.get("rate").asDouble());
			default -> throw new IllegalArgumentException("unknown persisted command type: " + type);
		};
	}
}
