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
 * The upper settlement tier — a settlement with <b>districts beyond its city center</b>, so it
 * is a {@link DistrictHost}. A City is founded into a multi-urban-plot site: an Anbennar
 * {@code city_terrain} province (its several urban core plots become the city's districts,
 * dev-scaled — see {@code docs/urban-plots.md}). Its buildings can spread across those district
 * plots, and it is {@link #isPermanent() permanent} (does not collapse when depopulated).
 * <p>
 * All behaviour lives in {@link Settlement}; a City only widens the urban surface (districts)
 * and flips permanence. Founded by {@link GameSession#newSettlement} when the province is
 * {@code city_terrain}; an ordinary province yields a {@link Village}. A City is always
 * province-founded (there is no bare-coordinate City). See {@code docs/settlement-tiers.md}.
 */
public final class City extends Settlement implements DistrictHost {

	/** Create a City founded into a {@code city_terrain} (multi-urban-plot) {@link Province}. */
	public City(String name, LocalDate startDate, Rng rng, NameRegistry names,
			Demography demography, TerrainRegistry terrainRegistry, Rng terrainRng,
			LiturgicalCalendar liturgicalCalendar, double meanInitAgeYears, double targetNStock,
			double meanSkillMale, double meanSkillFemale, double latitude, double longitude,
			Race foundingRace, Map<Race, Double> raceMix, Province province) {
		super(name, startDate, rng, names, demography, terrainRegistry, terrainRng,
				liturgicalCalendar, meanInitAgeYears, targetNStock, meanSkillMale,
				meanSkillFemale, latitude, longitude, foundingRace, raceMix, province);
	}

	/**
	 * A City is <b>permanent</b> — it survives depopulation (unlike a collapse-prone {@link
	 * Village}). Metadata only for now: the collapse machinery does not yet read this (see
	 * {@link Settlement#isPermanent()}).
	 */
	@Override
	public boolean isPermanent() {
		return true;
	}
}
