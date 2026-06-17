package eos.settlement;

/**
 * Something that can stand on a build site in a {@link Settlement} — an
 * {@link Slot effective slot} today, and (later) a special site. This is the
 * common occupant type the slot machinery speaks, so that placement, growth and
 * vacating are not tied to any one concrete kind of occupant.
 * <p>
 * Today the only occupants are <b>firms</b>, which are {@link eos.agent.Agent}s,
 * so {@code Agent} implements this interface and every occupant is in fact an
 * {@code Agent}. The interface exists as the seam the design anticipates: when
 * permanent housing, a village hall and other non-firm (and possibly non-agent)
 * buildings arrive, they implement {@code SlotOccupant} without the slot code
 * caring. The one place that still assumes an occupant is an {@code Agent} is the
 * land-funding bridge in {@link Settlement} (an effective-slot occupant pays for
 * its own land clearance, which requires a bank account) — that is where billing
 * must generalize once a non-agent occupant can take an effective slot.
 * <p>
 * It carries no methods: a slot only holds, compares and (for diagnostics) prints
 * its occupant. It is a pure marker, so adding it leaves the simulation
 * byte-identical.
 */
public interface SlotOccupant {
}
