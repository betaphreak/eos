package eos.settlement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eos.agent.Caravan;
import eos.calendar.LiturgicalCalendar;
import eos.mortality.Demography;
import eos.name.NameRegistry;
import eos.util.Rng;
import lombok.Getter;

/**
 * A game session owns the random-number seed and creates {@link Settlement}
 * instances from it. Each colony it creates gets its <b>own</b> economic
 * generator, derived from the session seed and the colony's index, so several
 * colonies in one session run on <em>independent</em> economic random streams
 * and never interleave; the same seed yields an identical run. The first colony
 * (index 0) uses the bare seed, so a single-colony run is byte-identical to one
 * with a single shared generator.
 * <p>
 * The session also owns the complete name sets ({@link NameRegistry}) and the
 * demographic service ({@link Demography}), each drawn from its own
 * <em>separate</em> generator (a salted copy of the seed) so naming and
 * mortality are deterministic yet never perturb the economic random stream.
 * Unlike the economic generator these are <b>shared</b> across the session's
 * colonies — that sharing is what makes dynasty surnames unique across every
 * colony in the session (the name pool is a single session-wide resource).
 * <p>
 * The session is also the home of the realm's <b>colony-less</b> bands: a {@link
 * Caravan} (a wandering following with a leader but no settlement) belongs to no
 * {@code Settlement}, so the session tracks it (see {@link #addCaravan} / {@link
 * #getCaravans()}) and is where a band <b>re-founds</b> — {@link
 * #newSettlement(Caravan, String, LocalDate, double, double, double, double)}
 * raises a fresh colony at the band's position, taking the next colony index so the
 * run stays deterministic (see {@code docs/caravan.md}).
 *
 * @author zhihongx
 */
public class GameSession {

	// decorrelate the naming/mortality/per-colony generators from the economic
	// one (and from each other), all seeded from the same session seed
	private static final long NAME_SEED_SALT = 0x9E3779B97F4A7C15L;
	private static final long MORTALITY_SEED_SALT = 0xD1B54A32D192ED03L;
	private static final long SKILL_SEED_SALT = 0xBF58476D1CE4E5B9L;
	private static final long COLONY_SEED_SALT = 0xA24BAED4963EE407L;

	// random-number seed for this session
	@Getter
	private final long seed;

	// the complete name sets for this session, with their own generator
	@Getter
	private final NameRegistry names;

	// the demographic service for this session, with its own generator
	@Getter
	private final Demography demography;

	// the precalculated slot table, loaded once at session start and shared by
	// every colony (it is pure geometry — independent of seed and location)
	@Getter
	private final SlotTable slotTable;

	// the liturgical calendar (curated universal feast list), loaded once and
	// shared by every colony — like the slot table it is independent of seed and
	// location, classifying any date as workday/weekend/holiday
	@Getter
	private final LiturgicalCalendar liturgicalCalendar;

	// number of colonies created so far; each gets an economic generator seeded
	// from (seed, index), so colonies don't share an economic random stream
	private int colonyCount = 0;

	// the session's wandering bands — colony-less Caravans that belong to no
	// Settlement (a band on the road after a collapse, or before it re-founds)
	private final List<Caravan> caravans = new ArrayList<>();

	/**
	 * Create a new game session with the given random-number seed.
	 *
	 * @param seed
	 *            the random-number seed for runs created from this session
	 */
	public GameSession(long seed) {
		this.seed = seed;
		this.names = new NameRegistry(new Rng(seed ^ NAME_SEED_SALT));
		this.demography = new Demography(new Rng(seed ^ MORTALITY_SEED_SALT),
				new Rng(seed ^ SKILL_SEED_SALT));
		this.slotTable = SlotTable.load();
		this.liturgicalCalendar = LiturgicalCalendar.load();
	}

	/**
	 * Create a new colony named <tt>name</tt> whose step 0 falls on
	 * <tt>startDate</tt>, drawing economic randomness from a fresh per-colony
	 * {@link Rng} (so colonies in this session are independent), and names and
	 * mortality from the session's shared {@link NameRegistry} and {@link
	 * Demography} (so surnames stay unique across colonies).
	 *
	 * @param name
	 *            the settlement's name (a display label)
	 * @param startDate
	 *            the in-game date of step 0
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkillMale
	 *            mean of the colony's male skill distribution
	 * @param meanSkillFemale
	 *            mean of the colony's female skill distribution
	 * @param latitude
	 *            the colony's geographic latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the colony's geographic longitude in decimal degrees (east positive)
	 * @return a fresh colony
	 */
	public Settlement newSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, double latitude, double longitude) {
		// index 0 -> bare seed (byte-identical to the old single shared rng);
		// later colonies get a distinct, decorrelated seed
		Rng colonyRng = new Rng(seed ^ (COLONY_SEED_SALT * colonyCount));
		colonyCount++;
		Settlement colony = new Settlement(name, startDate, colonyRng, names,
				demography, slotTable, liturgicalCalendar, meanInitAgeYears,
				targetNStock, meanSkillMale, meanSkillFemale, latitude, longitude);
		// the colony knows its session, so on dissolution it can register the band it
		// departs as (colony-less bands live at the session level — see docs/caravan.md)
		colony.setSession(this);
		return colony;
	}

	/**
	 * Re-found a colony for a wandering {@link Caravan band}: a fresh colony at the
	 * band's current {@linkplain Caravan#getLatitude() position}, taking the next
	 * colony index exactly as any {@link #newSettlement} call (so the band's new home
	 * runs on its own deterministic economic stream and "same seed → identical run"
	 * still holds). The geography is the band's — its latitude/longitude flow into the
	 * new settlement, giving it the climate of wherever the band chose to settle — and
	 * everything else is supplied by the caller as for a settlement founded from
	 * scratch.
	 * <p>
	 * This only raises the bare {@code Settlement}; binding the band's surviving people
	 * and carried hoard into it (seeding the new colony's {@link eos.agent.Retinue} from
	 * the band) is the foundry's job (see {@code docs/caravan.md} — the re-founding
	 * ascent {@code CARAVAN → HOLDING → VILLAGE}).
	 *
	 * @param band
	 *            the wandering band re-founding; its position becomes the new colony's
	 * @param name
	 *            the new settlement's name (a display label)
	 * @param startDate
	 *            the in-game date of the new colony's step 0
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkillMale
	 *            mean of the colony's male skill distribution
	 * @param meanSkillFemale
	 *            mean of the colony's female skill distribution
	 * @return a fresh colony positioned where the band settled
	 */
	public Settlement newSettlement(Caravan band, String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale) {
		return newSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, band.getLatitude(),
				band.getLongitude());
	}

	/**
	 * Register a colony-less {@link Caravan} with the session — a band that has left
	 * (or not yet founded) a settlement, so it has no {@code Settlement} to live on.
	 * The session is the home of such wandering bands (the level at which they re-found;
	 * see {@link #newSettlement(Caravan, String, LocalDate, double, double, double, double)}).
	 *
	 * @param band
	 *            the wandering band to track
	 */
	public void addCaravan(Caravan band) {
		caravans.add(band);
	}

	/**
	 * The session's wandering bands — the colony-less {@link Caravan}s it tracks (see
	 * {@link #addCaravan}). The returned list is unmodifiable.
	 *
	 * @return an unmodifiable view of the session's caravans
	 */
	public List<Caravan> getCaravans() {
		return Collections.unmodifiableList(caravans);
	}
}
