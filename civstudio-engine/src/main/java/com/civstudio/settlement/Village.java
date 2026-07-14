package com.civstudio.settlement;

import java.time.LocalDate;
import java.util.Map;

import com.civstudio.calendar.LiturgicalCalendar;
import com.civstudio.geo.Province;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.mortality.Demography;
import com.civstudio.name.NameRegistry;
import com.civstudio.race.Race;
import com.civstudio.util.Rng;

/**
 * The lower settlement tier — a settlement with <b>only a city center</b>, no districts. A
 * Village is founded into a single-urban-plot site: an ordinary land province (which carries
 * exactly one urban plot) or a bare-coordinate analytical colony. Its buildings all sit at the
 * center; it is <b>not</b> a {@link DistrictHost}. Today's collapse-prone colony is a Village.
 * <p>
 * All behaviour lives in {@link Settlement}; a Village only narrows the urban surface — its
 * {@linkplain #getStartingDistrictCount() starting district count} is its single center. See
 * {@code docs/settlement-tiers.md}. Founded by {@link GameSession#newSettlement} when the
 * province is ordinary (or absent); an Anbennar {@code city_terrain} province yields a {@link
 * City} instead.
 */
public final class Village extends Settlement {

	/** Create a bare-coordinate (province-less) Village — the analytical colony. */
	public Village(String name, LocalDate startDate, Rng rng, NameRegistry names,
			Demography demography, TerrainRegistry terrainRegistry, Rng terrainRng,
			LiturgicalCalendar liturgicalCalendar, double meanInitAgeYears, double targetNStock,
			double meanSkillMale, double meanSkillFemale, double latitude, double longitude) {
		super(name, startDate, rng, names, demography, terrainRegistry, terrainRng,
				liturgicalCalendar, meanInitAgeYears, targetNStock, meanSkillMale,
				meanSkillFemale, latitude, longitude);
	}

	/** Create a Village founded into an ordinary (single-urban-plot) {@link Province}. */
	public Village(String name, LocalDate startDate, Rng rng, NameRegistry names,
			Demography demography, TerrainRegistry terrainRegistry, Rng terrainRng,
			LiturgicalCalendar liturgicalCalendar, double meanInitAgeYears, double targetNStock,
			double meanSkillMale, double meanSkillFemale, double latitude, double longitude,
			Race foundingRace, Map<Race, Double> raceMix, Province province) {
		super(name, startDate, rng, names, demography, terrainRegistry, terrainRng,
				liturgicalCalendar, meanInitAgeYears, targetNStock, meanSkillMale,
				meanSkillFemale, latitude, longitude, foundingRace, raceMix, province);
	}

	/**
	 * A Village has only its <b>city center</b> — no districts — so its starting district count
	 * is the single center (capped by the base at its province development / plots, so a
	 * province-less Village reports {@code 0}).
	 */
	@Override
	public int getStartingDistrictCount() {
		return Math.min(1, super.getStartingDistrictCount());
	}
}
