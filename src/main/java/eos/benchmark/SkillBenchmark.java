package eos.benchmark;

import java.util.Random;

import eos.skill.Passion;
import eos.skill.Skill;
import eos.skill.SkillColumns;
import eos.skill.SkillRecord;
import eos.skill.SkillTracker;

/**
 * <b>Spike benchmark: object-layout vs. columnar skill store.</b>
 * <p>
 * Isolates the Retinue's daily hot loop — {@link SkillTracker#tick() decay} over
 * the whole pool plus the {@link SkillTracker#overallLevel() overall-level}
 * reductions it drives (starvation sort, promotion, spouse choice) — and compares
 * two layouts holding the <em>same</em> population:
 * <ul>
 * <li><b>AoS / object:</b> {@code N} {@link SkillTracker}s, each an
 * {@link java.util.EnumMap} of twelve heap {@link SkillRecord}s (the live model).</li>
 * <li><b>SoA / columnar:</b> one {@link SkillColumns} holding flat primitive
 * arrays (the spike).</li>
 * </ul>
 * It first asserts the two layouts evolve <b>bit-for-bit identically</b> over a
 * full run (same final levels, same per-day reduction checksum), then times each.
 * <p>
 * Run after {@code mvn compile} with the project classpath, e.g.
 * {@code java -cp target/classes eos.benchmark.SkillBenchmark [persons] [days] [meanSkill]}.
 */
public final class SkillBenchmark {

	public static void main(String[] args) {
		int persons = args.length > 0 ? Integer.parseInt(args[0]) : 900;
		int days = args.length > 1 ? Integer.parseInt(args[1]) : 9131;
		int meanSkill = args.length > 2 ? Integer.parseInt(args[2]) : 5;

		System.out.printf(
				"SkillBenchmark: persons=%d days=%d meanSkill=%d (skills/person=%d)%n",
				persons, days, meanSkill, SkillColumns.SKILL_COUNT);
		System.out.printf("  daily decay ops = %,d  | full-run decay ops = %,d%n%n",
				(long) persons * SkillColumns.SKILL_COUNT,
				(long) persons * SkillColumns.SKILL_COUNT * days);

		// the standard realistic pool (mean 5: most skills below the decay floor),
		// plus a high-skill regime (mean 12: decay and level-drops fire heavily) so
		// the comparison is shown where the arithmetic actually runs, not just where
		// it is masked off
		runScenario("realistic pool (mean 5)", persons, days, 5);
		runScenario("high-skill pool (mean 12)", persons, days, 12);

		// honour the meanSkill arg as a third, user-chosen scenario when given
		if (meanSkill != 5 && meanSkill != 12)
			runScenario("custom (mean " + meanSkill + ")", persons, days, meanSkill);
	}

	private static void runScenario(String label, int persons, int days, int mean) {
		System.out.println("=== " + label + " ===");

		// build matched populations from one deterministic generator
		SkillTracker[] aos = generate(persons, mean, 1234L);
		SkillColumns soa = new SkillColumns(persons);
		for (SkillTracker t : copyOf(aos))
			soa.add(t); // copy in identical starting state

		// --- correctness: evolve both a full run, compare ---
		long aosChk = runAos(copyOf(aos), days);
		SkillColumns soaCheck = freshColumns(aos, persons);
		long soaChk = runSoa(soaCheck, persons, days);
		boolean checksumOk = aosChk == soaChk;
		// compare final levels element-by-element
		SkillTracker[] aosFinal = copyOf(aos);
		runAos(aosFinal, days); // re-run to get the mutated final state
		boolean levelsOk = levelsMatch(aosFinal, soaCheck, persons);
		System.out.printf("  correctness: checksum %s (aos=%d soa=%d), levels %s%n",
				checksumOk ? "OK" : "MISMATCH", aosChk, soaChk,
				levelsOk ? "OK" : "MISMATCH");
		if (!checksumOk || !levelsOk)
			throw new AssertionError("layouts diverged — port is not faithful");

		// --- timing ---
		final int warmup = 2, trials = 5;
		double aosMin = timeAos(aos, persons, days, warmup, trials);
		double soaMin = timeSoa(aos, persons, days, warmup, trials);
		System.out.printf("  AoS (EnumMap)  : %8.2f ms/run%n", aosMin);
		System.out.printf("  SoA (columnar) : %8.2f ms/run   (%.2fx faster)%n",
				soaMin, aosMin / soaMin);
		System.out.println();
	}

	// ---- generation -------------------------------------------------------

	// build N trackers with levels around `mean` (sd 3, clamped [0,20]) and a
	// 60/30/10 NONE/MINOR/MAJOR passion mix — the Demography draw, reproduced here
	// with a plain Random so the benchmark has no economy dependency
	private static SkillTracker[] generate(int n, int mean, long seed) {
		Random rng = new Random(seed);
		SkillTracker[] out = new SkillTracker[n];
		for (int i = 0; i < n; i++) {
			var records = new java.util.EnumMap<Skill, SkillRecord>(Skill.class);
			for (Skill s : Skill.values()) {
				int lvl = (int) Math.round(mean + rng.nextGaussian() * 3);
				lvl = Math.max(0, Math.min(20, lvl));
				double r = rng.nextDouble();
				Passion p = r < 0.60 ? Passion.NONE
						: r < 0.90 ? Passion.MINOR : Passion.MAJOR;
				records.put(s, new SkillRecord(lvl, p));
			}
			out[i] = SkillTracker.of(records);
		}
		return out;
	}

	// deep copy so each trial starts from identical, unmutated state
	private static SkillTracker[] copyOf(SkillTracker[] src) {
		SkillTracker[] out = new SkillTracker[src.length];
		for (int i = 0; i < src.length; i++) {
			var records = new java.util.EnumMap<Skill, SkillRecord>(Skill.class);
			for (Skill s : Skill.values()) {
				SkillRecord r = src[i].getSkill(s);
				records.put(s, new SkillRecord(r.getLevel(), r.getPassion()));
			}
			out[i] = SkillTracker.of(records);
		}
		return out;
	}

	private static SkillColumns freshColumns(SkillTracker[] template, int persons) {
		SkillColumns c = new SkillColumns(persons);
		for (SkillTracker t : copyOf(template))
			c.add(t);
		return c;
	}

	// ---- evolution (decay + reduction), returning a checksum --------------

	private static long runAos(SkillTracker[] pop, int days) {
		long checksum = 0;
		for (int d = 0; d < days; d++) {
			for (SkillTracker t : pop)
				t.tick();
			for (SkillTracker t : pop)
				checksum += t.overallLevel(); // the reductions the pool drives
		}
		return checksum;
	}

	private static long runSoa(SkillColumns c, int persons, int days) {
		int[] out = new int[persons];
		long checksum = 0;
		for (int d = 0; d < days; d++) {
			c.tickAll();
			c.overallLevels(out);
			for (int i = 0; i < persons; i++)
				checksum += out[i];
		}
		return checksum;
	}

	private static boolean levelsMatch(SkillTracker[] aos, SkillColumns soa,
			int persons) {
		for (int i = 0; i < persons; i++)
			for (Skill s : Skill.values())
				if (aos[i].level(s) != soa.levelAt(i, s))
					return false;
		return true;
	}

	// ---- timing -----------------------------------------------------------

	private static double timeAos(SkillTracker[] template, int persons, int days,
			int warmup, int trials) {
		double best = Double.MAX_VALUE;
		for (int t = 0; t < warmup + trials; t++) {
			SkillTracker[] pop = copyOf(template);
			long start = System.nanoTime();
			long chk = runAos(pop, days);
			double ms = (System.nanoTime() - start) / 1e6;
			if (chk == Long.MIN_VALUE)
				System.out.print(""); // keep chk live (defeat DCE)
			if (t >= warmup)
				best = Math.min(best, ms);
		}
		return best;
	}

	private static double timeSoa(SkillTracker[] template, int persons, int days,
			int warmup, int trials) {
		double best = Double.MAX_VALUE;
		for (int t = 0; t < warmup + trials; t++) {
			SkillColumns c = freshColumns(template, persons);
			long start = System.nanoTime();
			long chk = runSoa(c, persons, days);
			double ms = (System.nanoTime() - start) / 1e6;
			if (chk == Long.MIN_VALUE)
				System.out.print("");
			if (t >= warmup)
				best = Math.min(best, ms);
		}
		return best;
	}
}
