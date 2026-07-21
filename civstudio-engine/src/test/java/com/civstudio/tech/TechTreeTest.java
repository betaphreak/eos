package com.civstudio.tech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.era.Era;

/**
 * Phase 1 graph-integrity checks for the {@link TechTree}: that {@code techs.json}
 * loads into the expected in-scope node set, every prerequisite resolves, the era
 * partition matches the source counts, and the pre-known / frontier graph queries
 * compute the expected sets for a Medieval-complete start (the case the simulation
 * will use). Nothing here exercises the economy — Phase 1 is pure reference data.
 */
class TechTreeTest {

	private static final TechTree TREE = TechTree.load();

	@Test
	void keepsOnlyInScopeTechs() {
		// the C2C source has 943 techs across many eras; the converter
		// (TechInfoExporter) keeps only Prehistoric..Atomic and drops the
		// religion-founding techs, Clockpunk, and disabled placeholders -> 507 kept
		assertEquals(507, TREE.size());
		assertNull(TREE.get("TECH_INFORMATION_LIFESTYLE"),
				"the out-of-scope Information end-cap tech should be dropped");
		assertNull(TREE.get("TECH_CHRISTIANITY"),
				"religion-founding techs should be dropped");
		assertNull(TREE.get("TECH_DUMMY"),
				"the disabled placeholder tech should be dropped");
		assertTrue(TREE.getAll().stream().allMatch(t -> t.era() != null),
				"every kept tech maps to an in-scope era");
	}

	@Test
	void eraPartitionMatchesSourceCounts() {
		Map<Era, Integer> counts = new EnumMap<>(Era.class);
		for (Tech t : TREE.getAll())
			counts.merge(t.era(), 1, Integer::sum);
		assertEquals(89, counts.get(Era.PREHISTORIC));
		assertEquals(88, counts.get(Era.ANCIENT));
		assertEquals(52, counts.get(Era.CLASSICAL));
		assertEquals(51, counts.get(Era.MEDIEVAL));
		assertEquals(58, counts.get(Era.RENAISSANCE));
		assertEquals(67, counts.get(Era.INDUSTRIAL));
		assertEquals(102, counts.get(Era.ATOMIC));
	}

	@Test
	void everyPrereqResolvesToAKeptTech() {
		// load() validates this and would have thrown; re-assert directly as a guard
		for (Tech t : TREE.getAll()) {
			for (String p : t.orPrereqs())
				assertNotNull(TREE.get(p), t.type() + " or-prereq " + p);
			for (String p : t.andPrereqs())
				assertNotNull(TREE.get(p), t.type() + " and-prereq " + p);
		}
	}

	@Test
	void rootTechHasNoPrereqsAndIsTriviallyResearchable() {
		// the prehistoric tree opens on a few prereq-less roots (cave dwelling,
		// nomadism, language); any is researchable from an empty known set
		Tech root = TREE.get("TECH_CAVE_DWELLING");
		assertNotNull(root);
		assertTrue(root.orPrereqs().isEmpty() && root.andPrereqs().isEmpty());
		assertTrue(TREE.prereqsSatisfied(root, Set.of()),
				"a root tech is researchable from nothing");
	}

	@Test
	void preKnownThroughMedievalIsEveryPreRenaissanceTech() {
		Set<String> known = TREE.preKnownThrough(Era.MEDIEVAL);
		// 89 + 88 + 52 + 51 = 280 techs at or before the Medieval era
		assertEquals(280, known.size());
		assertTrue(known.contains("TECH_CAVE_DWELLING"));
		assertTrue(known.contains("TECH_MEDIEVAL_LIFESTYLE"));
		assertFalse(known.contains("TECH_RENAISSANCE_LIFESTYLE"),
				"a Renaissance tech is not pre-known at a Medieval start");
	}

	@Test
	void frontierFromMedievalStartIsTheRenaissanceEntryTech() {
		Set<String> known = TREE.preKnownThrough(Era.MEDIEVAL);
		Set<Tech> frontier = TREE.researchableFrontier(known);
		// the Renaissance era is a chain off a single entry node, so a
		// Medieval-complete colony's only immediately-researchable tech is that node
		assertEquals(1, frontier.size());
		Tech entry = frontier.iterator().next();
		assertSame(TREE.get("TECH_RENAISSANCE_LIFESTYLE"), entry);
		assertEquals(Era.RENAISSANCE, entry.era());
		// the frontier never includes an already-known tech
		assertTrue(frontier.stream().noneMatch(t -> known.contains(t.type())));
	}
}
