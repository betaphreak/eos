package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import java.time.LocalDate;

import com.civstudio.agent.AbstractHousehold;
import com.civstudio.agent.Agent;
import com.civstudio.agent.Member;
import com.civstudio.agent.firm.ChildrenFirm;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a time-series of the colony's <b>children</b>: how many there are, their
 * average age and skill, and how many of them the {@link ChildrenFirm civic school}
 * is training against its capacity. Aggregates over the <b>living</b> children read
 * from {@code colony.getAgents()} each cycle (the sub-working-age members across all
 * household types — laborer, noble and ruler), mirroring how {@link LaborersPrinter} /
 * {@link RetinuePrinter}
 * report the laborer population and the pool. Register with {@link
 * Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Count, AvgAge, AvgSkill, Enrolled (children trained by the school
 * last step), Capacity (the school's places). See {@code docs/births.md}.
 */
public class ChildrenPrinter extends Printer {

	private final ChildrenFirm school;

	/**
	 * Create a new {@code ChildrenPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param school   the colony's civic school (for the enrolled/capacity columns)
	 */
	public ChildrenPrinter(String fileName, ChildrenFirm school) {
		super(fileName);
		this.school = school;
	}

	@Override
	public String tableName() {
		return "children";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), integer("Count"), real("AvgAge"),
				real("AvgSkill"), integer("Enrolled"), integer("Capacity") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		LocalDate today = colony.getDate();
		int count = 0;
		double ageSum = 0, skillSum = 0;
		for (Agent a : colony.getAgents())
			if (a instanceof AbstractHousehold household && household.isAlive())
				for (Member m : household.getMembers())
					if (!m.isAdult(today)) {
						count++;
						ageSum += m.getAgeYears(today);
						skillSum += m.skills().overallLevel();
					}
		double avgAge = count > 0 ? ageSum / count : 0;
		double avgSkill = count > 0 ? skillSum / count : 0;
		sink.writeRow(today, count, avgAge, avgSkill, school.getLastEnrolled(),
				school.getCapacity());
	}
}
