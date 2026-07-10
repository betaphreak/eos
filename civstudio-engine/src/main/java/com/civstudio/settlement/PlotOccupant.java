package com.civstudio.settlement;

import com.civstudio.agent.Agent;

/**
 * Something that can stand on a {@link Plot} in a {@link Settlement}. This is the
 * common occupant type the plot machinery speaks, so that placement, growth and
 * vacating are not tied to any one concrete kind of occupant.
 * <p>
 * Today the only occupants are <b>firms</b>, which are {@link Agent}s, so {@code
 * Agent} implements this interface and every occupant is in fact an {@code Agent}.
 * The interface exists as the seam the design anticipates: when permanent housing,
 * a village hall and other non-firm (and possibly non-agent) buildings arrive,
 * they implement {@code PlotOccupant} without the plot code caring. The one place
 * that still assumes an occupant is an {@code Agent} is the land-funding bridge in
 * {@link Settlement} (an on-plot occupant pays for its own land clearance, which
 * requires a bank account) — that is where billing must generalize once a non-agent
 * occupant can take a plot.
 * <p>
 * It carries no methods: a plot only holds, compares and (for diagnostics) prints
 * its occupant. It is a pure marker.
 */
public interface PlotOccupant {
}
