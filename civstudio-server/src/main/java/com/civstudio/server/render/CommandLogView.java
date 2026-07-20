package com.civstudio.server.render;

import java.util.List;

/**
 * A session's command log as served to a client — the applied {@code history} (the ordered replay
 * log, i.e. the savegame) plus the number of commands submitted but not yet due.
 * <p>
 * {@code pending} is deliberately a count rather than the commands themselves: a pending command is
 * an intention that has not happened yet, and showing it as though it had would misreport the run.
 * The count is enough to tell an operator that input is in flight.
 *
 * @param history the applied commands, in application order
 * @param pending how many are submitted but not yet applied
 */
public record CommandLogView(List<CommandView> history, int pending) {}
