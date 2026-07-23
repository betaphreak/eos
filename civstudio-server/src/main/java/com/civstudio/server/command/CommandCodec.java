package com.civstudio.server.command;

import java.util.LinkedHashMap;
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
		if (command instanceof QueueBuildCommand)
			return "queueBuild";
		throw new IllegalArgumentException("no codec for command " + command.getClass().getName());
	}

	/** The command-specific fields as a JSON object (the {@code tick} is a separate column). */
	public String payload(GameCommand command) {
		if (command instanceof QueueBuildCommand q) {
			Map<String, Object> fields = new LinkedHashMap<>();
			if (q.colony() != null)
				fields.put("colony", q.colony());
			fields.put("items", q.items());
			if (q.clear())
				fields.put("clear", true);
			return json.writeValueAsString(fields);
		}
		if (command instanceof SetTaxRateCommand s) {
			// `colony` is written only when the command names one — an all-colonies command stays
			// byte-identical to what this codec has always written, and Map.of would reject the null
			Map<String, Object> fields = new LinkedHashMap<>();
			if (s.colony() != null)
				fields.put("colony", s.colony());
			fields.put("lever", s.lever().name());
			fields.put("rate", s.rate());
			return json.writeValueAsString(fields);
		}
		throw new IllegalArgumentException("no codec for command " + command.getClass().getName());
	}

	/** Reconstruct a command from a stored row. */
	public GameCommand decode(String type, long tick, String payload) {
		JsonNode n = json.readTree(payload);
		return switch (type) {
			// a row with no `colony` predates Phase 2 and meant "every colony" when it was issued —
			// null preserves that, so replaying an old log reaches the state it originally did
			case "setTaxRate" -> new SetTaxRateCommand(tick,
					n.hasNonNull("colony") ? n.get("colony").asText() : null,
					SetTaxRateCommand.Lever.valueOf(n.get("lever").asText()),
					n.get("rate").asDouble());
			case "queueBuild" -> {
				java.util.List<String> items = new java.util.ArrayList<>();
				if (n.has("items"))
					n.get("items").forEach(i -> items.add(i.asText()));
				yield new QueueBuildCommand(tick,
						n.hasNonNull("colony") ? n.get("colony").asText() : null,
						items, n.has("clear") && n.get("clear").asBoolean());
			}
			default -> throw new IllegalArgumentException("unknown persisted command type: " + type);
		};
	}
}
