package eos.tech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

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
		assertEquals(2, overlay.size());
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
	void shippedOverlayIsEmptySoTechsHaveNoEffectsYet() {
		// the shipped /tech-effects.json is {} for now (plumbing without coverage)
		TechTree tree = TechTree.load();
		assertTrue(tree.effectsOf("TECH_MERCANTILISM").isEmpty());
		assertTrue(tree.effectsOf("TECH_RENAISSANCE_LIFESTYLE").isEmpty());
	}
}
