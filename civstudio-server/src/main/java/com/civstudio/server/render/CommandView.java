package com.civstudio.server.render;

/**
 * One applied command in a session's replay log — the render-side projection of a
 * {@link com.civstudio.server.command.GameCommand}.
 * <p>
 * Shared deliberately by the HTTP route ({@code GET /api/sessions/{id}/commands}) and the MCP
 * {@code get_command_log} tool, so the two can never disagree about what a command looks like.
 * {@code lever}/{@code rate} are null for any command type without a codec — an unknown future
 * command still projects to {@code {tick, type}} rather than crashing the log.
 *
 * @param tick  the tick the command was applied at
 * @param type  the command's wire type ({@code "setTaxRate"}), else its simple class name
 * @param lever the tax lever, or {@code null}
 * @param rate  the tax rate 0..1, or {@code null}
 */
public record CommandView(long tick, String type, String lever, Double rate) {}
