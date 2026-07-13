package com.civstudio.tech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 2 checks for the tech-effect schema and overlay loader: that the three
 * {@link TechEffect} kinds deserialize polymorphically by their {@code "kind"}
 * discriminator, that the object-keyed overlay parses into per-tech effect lists, and
 * that the shipped (empty) overlay leaves every tech effect-less. Applying effects to
 * a colony is covered by {@code eos.simulation.TechProductivityTest}.
 */
class TechEffectTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void sectorProductivityEffectDeserializes() throws Exception {
		TechEffect e = MAPPER.readValue(
				"{\"kind\":\"SECTOR_PRODUCTIVITY\",\"sector\":\"CAPITAL\",\"factor\":1.05}",
				TechEffect.class);
		TechEffect.SectorProductivity sp =
				assertInstanceOf(TechEffect.SectorProductivity.class, e);
		assertEquals(Sector.CAPITAL, sp.sector());
		assertEquals(1.05, sp.factor());
	}

	@Test
	void unlockEffectDeserializes() throws Exception {
		TechEffect e = MAPPER.readValue(
				"{\"kind\":\"UNLOCK\",\"target\":\"GOOD_PAPER\"}", TechEffect.class);
		assertEquals("GOOD_PAPER",
				assertInstanceOf(TechEffect.Unlock.class, e).target());
	}

	@Test
	void socialGateEffectDeserializes() throws Exception {
		TechEffect e = MAPPER.readValue(
				"{\"kind\":\"SOCIAL_GATE\",\"capability\":\"CLASS_BURGHER\"}",
				TechEffect.class);
		assertEquals("CLASS_BURGHER",
				assertInstanceOf(TechEffect.SocialGate.class, e).capability());
	}

	@Test
	void overlayParsesPerTechEffectLists() {
		// a test overlay (real schema, sample data) under src/test/resources
		Map<String, List<TechEffect>> overlay =
				TechEffects.load("/tech-effects-sample.json");
		assertEquals(3, overlay.size());
		List<TechEffect> merc = overlay.get("TECH_MERCANTILISM");
		assertEquals(2, merc.size());
		assertInstanceOf(TechEffect.SectorProductivity.class, merc.get(0));
		assertInstanceOf(TechEffect.Unlock.class, merc.get(1));
		assertInstanceOf(TechEffect.SocialGate.class,
				overlay.get("TECH_CORPORATION").get(0));
	}

	@Test
	void absentOverlayIsEmpty() {
		assertTrue(TechEffects.load("/no-such-overlay.json").isEmpty());
	}

	@Test
	void handAuthoredOverlayIsStillEmpty() {
		// the hand-authored /tech-effects.json carries no effects yet (plumbing without
		// coverage); the building UNLOCKs live in the separate generated
		// /building-unlocks.json and are merged in only by the public TechTree.load().
		assertTrue(TechEffects.load("/tech-effects.json").isEmpty());
	}

	@Test
	void buildingUnlocksMergeOntoTheirPrereqTech() {
		// Phase 4: TechTree.load() merges the generated /building-unlocks.json onto the
		// hand-authored overlay, so a tech carries an UNLOCK effect for each building it
		// unlocks (keyed by the building's primary prereq tech).
		TechTree tree = TechTree.load();
		List<TechEffect> fishing = tree.effectsOf("TECH_FISHING");
		assertTrue(
				fishing.stream().anyMatch(e -> e instanceof TechEffect.Unlock u
						&& u.target().equals("BUILDING_ABALONE_DIGGERS")),
				"TECH_FISHING should unlock BUILDING_ABALONE_DIGGERS");
		// every unlock target is a BUILDING_* id (the verbatim C2C type)
		assertTrue(fishing.stream()
				.filter(e -> e instanceof TechEffect.Unlock)
				.allMatch(e -> ((TechEffect.Unlock) e).target().startsWith("BUILDING_")));
	}
}
