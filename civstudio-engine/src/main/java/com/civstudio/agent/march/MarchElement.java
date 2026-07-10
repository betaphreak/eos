package com.civstudio.agent.march;

/**
 * The <b>order of march</b>: the fixed sequence of blocks a band's column files out of
 * camp in, front &rarr; back — the {@code enum} whose {@link #ordinal() ordinal} <em>is</em>
 * the position in the column (see {@code docs/caravan-march.md} §5). Each element carries
 * the share of the band it holds in each <b>flavor</b> (a settler/admin band fields only a
 * reduced subset; a military band the full order), from which its head-count, column
 * length and file-out time follow.
 * <p>
 * {@code FLANK_GUARD} is <b>roaming</b> — it trickles out over the day to screen the
 * column's flanks, so it has no single depart/arrive time and sits outside the ordered
 * sequence; it is intentionally omitted from this ordered enum (a later cut may hold it
 * apart). An element a flavor does not field has share {@code 0}, contributing no length
 * or time, so the schedule collapses to whatever blocks are actually present.
 */
public enum MarchElement {

	/** Recon screen (light cavalry + archers), given a head start. */
	SCOUTS(0.00, 0.05),
	/** Lead fighting force; for a settler band, the people who break trail. */
	VANGUARD(0.15, 0.15),
	/** Engineers + escort — go ahead to lay out the next camp. */
	SURVEYORS(0.00, 0.05),
	/** General, staff and bodyguard. */
	COMMAND(0.00, 0.05),
	/** The bulk of the band — heavy infantry, or the settler population. */
	MAIN_BODY(0.55, 0.45),
	/** The impedimenta — the single largest, slowest block (wagons, animals, stores). */
	BAGGAGE_TRAIN(0.30, 0.15),
	/** Rear screen (cavalry + archers), no baggage. */
	REAR_GUARD(0.00, 0.10);

	private final double settlerShare;
	private final double militaryShare;

	MarchElement(double settlerShare, double militaryShare) {
		this.settlerShare = settlerShare;
		this.militaryShare = militaryShare;
	}

	/**
	 * The fraction of the band this element holds in the given {@link MarchFlavor}.
	 *
	 * @param flavor the band's flavor
	 * @return the element's share of the band's head-count (0 if unfielded)
	 */
	public double share(MarchFlavor flavor) {
		return flavor == MarchFlavor.MILITARY ? militaryShare : settlerShare;
	}

	/**
	 * Whether this element carries the heavier <b>baggage</b> footprint (wagons and
	 * animals pack less efficiently than marching people, so a baggage block is longer
	 * per head — see {@code docs/caravan-march.md} §3).
	 *
	 * @return {@code true} for the baggage train
	 */
	public boolean isBaggage() {
		return this == BAGGAGE_TRAIN;
	}
}
