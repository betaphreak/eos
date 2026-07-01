package com.civstudio.agent.march;

/**
 * The flavor of a marching band — which {@link MarchElement}s it fields (see {@code
 * docs/caravan-march.md} §5, <i>Flavor note</i>). A {@link #SETTLER} band (the migration
 * caravan) has no legions: it marches a reduced column of vanguard + main body + baggage.
 * A {@link #MILITARY} band (the future army) fields the full seven-element order with
 * scouts, surveyors, command and guards. The pace and camp rules are the same for both in
 * this cut; only the fielded elements differ.
 */
public enum MarchFlavor {
	/** A settler/admin band — vanguard + main body + baggage only. */
	SETTLER,
	/** A military band — the full order of march. */
	MILITARY
}
