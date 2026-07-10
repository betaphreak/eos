package com.civstudio.good;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Bonus;
import com.civstudio.geo.BonusClass;
import com.civstudio.geo.TerrainRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the manufactured-goods data layer (step 1 of
 * {@code docs/manufactured-bonuses.md}): the committed
 * {@code /manufactured-bonuses.json}, {@code /recipes.json} and
 * {@code /tier1-providers.json} resources (emitted by
 * {@code geo.export.ManufacturedBonusExporter} / {@code geo.export.RecipeExporter}
 * from the C2C sources in {@code data/civ4/}) load, are internally consistent as a
 * producer graph, and spot-check against the source XML — the doc's own worked
 * example, hide + tannin → leather at the Tannery, and the wood → forest tier-1
 * plot source (M29).
 */
class RecipeCatalogTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static <T> List<T> load(String resource, TypeReference<List<T>> type) {
		try (InputStream in = RecipeCatalogTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, "resource not found: " + resource);
			return MAPPER.readValue(in, type);
		} catch (Exception e) {
			throw new IllegalStateException("failed to load " + resource, e);
		}
	}

	private static Bonus byType(List<Bonus> catalog, String type) {
		return catalog.stream().filter(b -> b.type().equals(type)).findFirst().orElseThrow();
	}

	private static List<Bonus> catalog() {
		return load("/manufactured-bonuses.json", new TypeReference<List<Bonus>>() {
		});
	}

	private static List<Recipe> recipes() {
		return load("/recipes.json", new TypeReference<List<Recipe>>() {
		});
	}

	private static List<TierOneSource> tierOne() {
		return load("/tier1-providers.json", new TypeReference<List<TierOneSource>>() {
		});
	}

	@Test
	void manufacturedCatalogMatchesXml() {
		List<Bonus> catalog = catalog();
		// the full C2C manufactured file: 313 MANUFACTURED + 13 WONDER pseudo-goods
		assertEquals(326, catalog.size(), "full manufactured catalog");
		assertEquals(13, catalog.stream()
				.filter(b -> b.bonusClass() == BonusClass.WONDER).count());

		Bonus leather = byType(catalog, "BONUS_LEATHER");
		assertEquals(BonusClass.MANUFACTURED, leather.bonusClass());
		assertEquals("TECH_TANNING", leather.techReveal());

		// alcohol carries a happiness amenity and a fermentation gate
		Bonus alcohol = byType(catalog, "BONUS_ALCOHOL");
		assertEquals("TECH_FERMENTATION", alcohol.techReveal());
		assertEquals(1, alcohol.happiness());

		// every good is tech-gated (the M18 producer gate reads this)
		assertTrue(catalog.stream().allMatch(b -> b.techReveal() != null),
				"every manufactured good has a TechReveal");

		// disjoint from the map-placed raw set — no key names two goods
		Set<String> raw = new HashSet<>();
		TerrainRegistry.load().bonuses().forEach(b -> raw.add(b.type()));
		assertTrue(catalog.stream().noneMatch(b -> raw.contains(b.type())),
				"manufactured catalog is disjoint from bonuses.json");
	}

	@Test
	void tanneryRecipeMatchesXml() {
		// the doc's worked example: HIDE + TANNIN -> LEATHER at TECH_TANNING
		Recipe tannery = recipes().stream()
				.filter(r -> r.type().equals("BUILDING_TANNERY")).findFirst().orElseThrow();
		assertEquals(List.of("BONUS_LEATHER"), tannery.outputs());
		assertEquals("BONUS_HIDE", tannery.bonus());
		assertEquals(List.of("BONUS_TANNIN"), tannery.prereqBonuses());
		assertEquals("TECH_TANNING", tannery.prereqTech());
		assertFalse(tannery.river());
	}

	@Test
	void recipeGraphIsConsistent() {
		List<Recipe> recipes = recipes();
		Set<String> catalogTypes = new HashSet<>();
		catalog().forEach(b -> catalogTypes.add(b.type()));

		Set<String> producers = new HashSet<>();
		for (Recipe r : recipes) {
			assertTrue(producers.add(r.type()), "duplicate recipe: " + r.type());
			assertFalse(r.outputs().isEmpty(), r.type() + " has no output");
			for (String out : r.outputs())
				assertTrue(catalogTypes.contains(out),
						r.type() + " outputs " + out + " not in the manufactured catalog");
		}
	}

	@Test
	void woodTierOneSourceIsForest() {
		// M29: the tier-1 good's raw plot source, read from its gatherers — a
		// lumber gatherer needs a forest/jungle feature on the plot
		TierOneSource wood = tierOne().stream()
				.filter(p -> p.output().equals("BONUS_WOOD")).findFirst().orElseThrow();
		assertEquals("BUILDING_RESOURCES_WOOD", wood.type());

		TierOneSource.Gatherer lumber = wood.gatherers().stream()
				.filter(g -> g.type().equals("BUILDING_LUMBER_GATHERER"))
				.findFirst().orElseThrow();
		assertEquals("TECH_GATHERING", lumber.prereqTech());
		assertTrue(lumber.prereqOrFeatures().contains("FEATURE_FOREST"));
		assertTrue(lumber.prereqOrFeatures().contains("FEATURE_JUNGLE"));
		assertTrue(lumber.prereqOrTerrains().isEmpty());
		assertNull(lumber.bonus());
	}

	@Test
	void tierOneProvidersAreConsistent() {
		List<TierOneSource> tierOne = tierOne();
		assertEquals(48, tierOne.size(), "the 48 BUILDING_RESOURCES_* providers");

		Set<String> catalogTypes = new HashSet<>();
		catalog().forEach(b -> catalogTypes.add(b.type()));
		Set<String> rawTypes = new HashSet<>();
		TerrainRegistry.load().bonuses().forEach(b -> rawTypes.add(b.type()));

		Set<String> outputs = new HashSet<>();
		for (TierOneSource p : tierOne) {
			assertTrue(outputs.add(p.output()), "two providers grant " + p.output());
			assertFalse(p.gatherers().isEmpty(), p.type() + " has no gatherers");
			// every extracted good is a known bonus — manufactured, or (salt
			// alone) a map-placed raw bonus that also has an extraction provider
			assertTrue(catalogTypes.contains(p.output()) || rawTypes.contains(p.output()),
					p.type() + " grants unknown bonus " + p.output());
		}
	}
}
