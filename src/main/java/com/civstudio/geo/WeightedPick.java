package com.civstudio.geo;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.util.Rng;

/**
 * A faithful port of the Caveman2Cosmos planet generator's {@code probabilityArray}
 * (C2C_Planet_Generator_0_68.py L632–695) — the weighted bag its {@code addFeatures}
 * stage builds a feature choice in. The semantics the port must preserve exactly:
 * <ul>
 * <li><b>append, don't overwrite</b> — the script writes {@code arr[weight] = value}
 * which <em>appends</em> {@code [weight, value]} only when {@code weight > 0}; the
 * same {@code value} written at several weights therefore accumulates as several
 * entries (its effective weight is the sum). {@link #add} matches this.</li>
 * <li><b>{@code null} is a value</b> — the script stores {@code None} entries (a "no
 * feature" outcome); this bag stores {@code null} values likewise, so a pick can
 * legitimately return {@code null}.</li>
 * <li><b>proportional pick with a max-weight fallback</b> — {@link #randomItem}
 * mirrors {@code randomItem}: draw {@code rand·sum}, walk cumulative weight, return
 * the first entry whose running total is {@code > } the draw; if the walk finds none
 * (a floating-point edge), return the largest-weight entry's value. An empty bag
 * returns {@code null}.</li>
 * </ul>
 * Non-consuming ({@code randomItem} does not remove the picked entry), matching the
 * script's feature-weight usage. The spread-frontier seed list (which the script
 * pops from) is modelled separately by the caller. See {@code docs/c2c-generator-port.md}.
 *
 * @param <T> the value type (typically {@link Feature}, {@code null} = no feature)
 */
public final class WeightedPick<T> {

	private final List<Double> weights = new ArrayList<>();
	private final List<T> values = new ArrayList<>();
	private double sum;
	private double maxWeight;
	private int maxIndex = -1;

	/**
	 * Append a weighted entry, mirroring {@code __setitem__} (L639): a non-positive
	 * weight is ignored, and the same value may be added at several weights (they
	 * accumulate). A larger weight than any seen becomes the max-weight fallback.
	 *
	 * @param weight the entry weight; ignored when {@code <= 0}
	 * @param value  the value (may be {@code null} for a "no feature" outcome)
	 * @return this bag, for chaining
	 */
	public WeightedPick<T> add(double weight, T value) {
		if (weight > 0) {
			weights.add(weight);
			values.add(value);
			sum += weight;
			if (weight > maxWeight) {          // strictly greater, as in the script
				maxWeight = weight;
				maxIndex = weights.size() - 1;
			}
		}
		return this;
	}

	/** Whether nothing has been added (all writes had non-positive weight). */
	public boolean isEmpty() {
		return weights.isEmpty();
	}

	/**
	 * The number of entries appended (each positive-weight {@link #add}). Mirrors
	 * {@code len(probabilityArray)} — the script uses it for the spread stop-option
	 * weight ({@code posList[len(posList) / 2] = None}, L3071).
	 */
	public int size() {
		return weights.size();
	}

	/**
	 * Pick a value proportional to weight, porting {@code randomItem} (L679): draw
	 * {@code rng·sum}, return the first entry whose cumulative weight exceeds it, else
	 * the max-weight entry; {@code null} if empty. Consumes exactly one {@code rng}
	 * draw whenever the bag is non-empty, so the terrain stream stays deterministic.
	 *
	 * @param rng the dedicated terrain stream (not the economic one)
	 * @return the picked value (may be {@code null}), or {@code null} if empty
	 */
	public T randomItem(Rng rng) {
		if (weights.isEmpty())
			return null;
		double item = rng.uniform() * sum;
		double counted = 0;
		for (int i = 0; i < weights.size(); i++) {
			counted += weights.get(i);
			if (counted > item)            // strict >, matching randomItem (not random)
				return values.get(i);
		}
		return maxIndex >= 0 ? values.get(maxIndex) : null;
	}
}
