package com.civstudio.server.mcp;

/**
 * The authorization seam for MCP write tools — resolves the caller's identity/role on the current
 * MCP request thread. Factored to an interface so the write tools can be unit-tested with a stub,
 * and so the one production implementation ({@link RequestMcpAuthz}) can keep the SecurityContext /
 * request plumbing out of the tools. Live-session writes are <b>admin-gated</b> (see
 * {@code docs/mcp-server.md} Phase 2): MCP calls carry no per-session owner identity as cleanly as
 * REST, so the surface reuses the server's {@code ROLE_ADMIN} / {@code civstudio.auth.admins}
 * allow-list rather than owner-gating.
 */
public interface McpAuthz {

	/** Whether the current caller is an administrator. */
	boolean isAdmin();

	/** The current caller's user id, or {@code null} if unauthenticated. */
	String userId();

	/** Throw unless the caller is an admin — the guard every write tool calls first. */
	default void requireAdmin() {
		if (!isAdmin())
			throw new SecurityException("this MCP tool requires administrator privileges");
	}
}
