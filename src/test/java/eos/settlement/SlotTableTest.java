package eos.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;

/**
 * Verifies the precalculated {@link SlotTable} matches the design spreadsheet
 * and that a {@link Settlement} founds itself at the floor size and grows to fit
 * the occupants placed on its slots.
 */
class SlotTableTest {

	private final SlotTable table = SlotTable.load();

	@Test
	void tableCoversSizesZeroToNinetyFive() {
		assertEquals(95, table.maxSize(), "the bundled table runs to size 95");
		for (int s = 0; s <= table.maxSize(); s++)
			assertEquals(s, table.forSize(s).size());
	}

	@Test
	void spotChecksMatchSpreadsheet() {
		// size: total, road, wall, effective, maxSpecialSites (straight off the sheet)
		assertRow(0, 1, 0, 0, 1, 1); // hand-set lone slot the export firm takes
		assertRow(2, 12, 0, 7, 5, 1);
		assertRow(3, 28, 0, 13, 15, 1); // founding size: 15 effective slots
		assertRow(4, 50, 2, 19, 29, 2);
		assertRow(5, 78, 3, 26, 49, 2);
		assertRow(10, 314, 31, 57, 226, 3);
		assertRow(13, 530, 68, 76, 386, 3);
		assertRow(30, 2827, 848, 183, 1796, 4);
		assertRow(50, 7853, 3926, 308, 3619, 5);
		assertRow(57, 10207, 5817, 352, 4038, 6);
		assertRow(66, 13684, 9031, 409, 4244, 6); // effective peak
		assertRow(95, 28352, 26934, 591, 827, 6);
	}

	@Test
	void derivedColumnsAreSelfConsistent() {
		for (int s = 0; s <= table.maxSize(); s++) {
			SlotInfo r = table.forSize(s);
			assertEquals(r.total() - r.road() - r.wall(), r.effective(),
					"effective = total - road - wall at size " + s);
			int expectedTotal = s == 0 ? 1 : (int) Math.floor(Math.PI * s * s);
			assertEquals(expectedTotal, r.total(), "total at size " + s);
			assertEquals((int) Math.floor((s / 100.0) * r.total()), r.road(),
					"road at size " + s);
			assertTrue(r.maxSpecialSites() >= 1 && r.maxSpecialSites() <= 6,
					"special sites in [1,6] at size " + s);
		}
	}

	@Test
	void wallBuildTimePercentIsWallShareSquared() {
		// size 2: wall share 7/12 = 58.33%, squared ~ 3402.78%
		assertEquals(3402.78, table.forSize(2).wallBuildTimePercent(), 0.5);
		assertEquals(0, table.forSize(0).wallBuildTimePercent(), 0.0);
	}

	@Test
	void colonyFoundsAtFloorSizeWithVacantSlots() {
		Settlement colony = colony();
		assertEquals(SlotTable.MIN_SIZE, colony.getSize());
		assertEquals(15, colony.getSlots().size(), "size 3 has 15 effective slots");
		assertTrue(colony.getSlots().stream().allMatch(Slot::isVacant));
	}

	@Test
	void claimingPlacesOccupantsAndGrowsToFit() {
		Settlement colony = colony();
		// fill all 15 floor-size slots without growing
		Agent[] firms = new Agent[16];
		for (int i = 0; i < 15; i++) {
			firms[i] = stubAgent(colony);
			Slot slot = colony.claimSlot(firms[i]);
			assertFalse(slot.isVacant());
			assertSame(firms[i], slot.getOccupant());
		}
		assertEquals(SlotTable.MIN_SIZE, colony.getSize(), "15 firms still fit size 3");

		// the 16th forces a grow to size 4 (29 effective slots)
		firms[15] = stubAgent(colony);
		colony.claimSlot(firms[15]);
		assertEquals(4, colony.getSize());
		assertEquals(29, colony.getSlots().size());

		long occupied = colony.getSlots().stream().filter(s -> !s.isVacant()).count();
		assertEquals(16, occupied, "every claimed firm holds exactly one slot");
	}

	@Test
	void occupyingAnOccupiedSlotThrows() {
		Settlement colony = colony();
		Slot slot = new Slot();
		slot.occupy(stubAgent(colony));
		assertThrows(IllegalStateException.class,
				() -> slot.occupy(stubAgent(colony)));
	}

	private void assertRow(int size, int total, int road, int wall, int effective,
			int maxSpecialSites) {
		SlotInfo r = table.forSize(size);
		assertNotNull(r);
		assertEquals(total, r.total(), "total at size " + size);
		assertEquals(road, r.road(), "road at size " + size);
		assertEquals(wall, r.wall(), "wall at size " + size);
		assertEquals(effective, r.effective(), "effective at size " + size);
		assertEquals(maxSpecialSites, r.maxSpecialSites(),
				"maxSpecialSites at size " + size);
	}

	private Settlement colony() {
		return new Settlement("Test", LocalDate.of(1444, 12, 11), new eos.util.Rng(1L),
				null, null, table, 35, 26, 5, 51.5074, -0.1278);
	}

	// a minimal Agent usable only as a slot occupant (it never acts)
	private Agent stubAgent(Settlement colony) {
		return new Agent(null, colony) {
			@Override
			public void act() {
			}

			@Override
			public eos.good.Good getGood(String goodName) {
				return null;
			}
		};
	}
}
