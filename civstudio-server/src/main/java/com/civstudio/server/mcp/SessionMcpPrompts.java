package com.civstudio.server.mcp;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Server-shipped MCP prompts — the "skills the server hands the agent" of
 * {@code docs/mcp-server.md} §How an LLM / skill consumes this (pattern 3). A prompt is a canned
 * playbook the client surfaces slash-command-style; this one sequences the read-only
 * {@link SessionMcpTools} into the plan's collapse-diagnosis procedure, so an analyst LLM runs the
 * same steps every time instead of improvising them. Pure text (no session state is touched here),
 * discovered by the annotation scanner like the tools/resources.
 */
@Component
public class SessionMcpPrompts {

	@McpPrompt(name = "diagnose-live-colony",
			description = "Playbook: diagnose why a live colony is declining or has collapsed, using "
					+ "the read-only session tools. Encodes the 'is the collapse clean?' question.")
	public GetPromptResult diagnoseLiveColony(
			@McpArg(name = "sessionId",
					description = "The session to diagnose; omit to start from list_sessions.",
					required = false) String sessionId) {
		String target = (sessionId == null || sessionId.isBlank())
				? "First call `list_sessions` and pick the session to diagnose."
				: "Diagnose session `" + sessionId + "`.";
		String playbook = """
				You are diagnosing a CivStudio colony's decline using the read-only MCP tools. \
				%s

				Steps:
				1. `get_snapshot(sessionId)` — for each colony read `alive`, `population`, `children`, \
				`poolSize`, `nobles`, `firms`, `cpi`, `necessityPrice`, `enjoymentPrice`, \
				`bankProfitTax`, `nobleIncomeTax`. A draining `poolSize` with rising \
				`necessityPrice` is the classic food-balance collapse; note the in-game `date`.
				2. `get_command_log(sessionId)` — see which levers were pulled and when (setTaxRate: \
				BANK_PROFIT / NOBLE_INCOME). Correlate lever changes with the population trend.
				3. `get_person(sessionId, personId)` — inspect the ruler and each advisor \
				(personIds are in the snapshot's `advisors`): ages, skills, household. A dying \
				aristocracy vs. a starving labor pool are different failure modes.
				4. Read recent event lines from `get_snapshot(...).log` (foundings, deaths, \
				starvation, ennoblement) around the decline.

				Report the proximate cause and whether the collapse is *clean* — the labor model is \
				replacement-only, so a colony winding down as its pool reserve drains is by design \
				(a clean collapse); a crash from a mis-set tax lever or a price spiral is not. \
				Ground every claim in a tool result, not prose.""".formatted(target);
		return new GetPromptResult("Diagnose a live colony's decline",
				List.of(new PromptMessage(Role.USER, new TextContent(playbook))));
	}
}
