package eos.tech;

/**
 * A productive sector of the colony — the granularity at which the tech tree's
 * {@link TechEffect.SectorProductivity} effect raises total-factor productivity. Each
 * maps to one firm type's output:
 * <ul>
 * <li>{@link #NECESSITY} — food ({@link eos.agent.firm.NFirm});</li>
 * <li>{@link #ENJOYMENT} — leisure goods ({@link eos.agent.firm.EFirm});</li>
 * <li>{@link #CAPITAL} — machines ({@link eos.agent.firm.CFirm});</li>
 * <li>{@link #EXPORT} — the strategic export good ({@link eos.agent.firm.StrategicFirm}).</li>
 * </ul>
 * The labor-only builder has no sector (its output is construction, not a tech-scaled
 * good), so it is deliberately absent; {@link eos.agent.firm.Firm#sector()} returns
 * {@code null} for it, and the colony's per-sector tech multiplier defaults to 1 for
 * any firm without a sector.
 */
public enum Sector {
	NECESSITY,
	ENJOYMENT,
	CAPITAL,
	EXPORT;
}
