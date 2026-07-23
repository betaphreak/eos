package com.civstudio.agent.firm;

import java.util.List;
import java.util.Set;

import com.civstudio.agent.Retinue;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.agent.Agent;
import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.market.LaborMarket;
import com.civstudio.settlement.BuildProject;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.Skill;
import lombok.Getter;

/**
 * The settlement's <b>builder</b>: a labor-only firm that physically expands the
 * colony. It is staffed <b>exclusively by peasants</b> — it hires on a dedicated
 * {@value #LABOR_MARKET} market whose only workers are the {@link
 * Retinue}'s peasants (the corvée labor of the poor), mirroring how
 * {@link StrategicFirm} draws on a noble-only market — and converts that labor into
 * <i>build-units</i> (its output is capped by the {@link BuilderConfig#scaffoldCap()
 * scaffold cap}), which it applies to the {@link BuildProject}s in the colony's
 * build queue — each one the land clearance to open one new {@link
 * com.civstudio.settlement.Plot plot}. The work it delivers is <b>billed to each
 * task's sponsor</b> at cost: the firm that requested a plot pays for the land it
 * will occupy (the disc model's ruler-funded road/wall public works are gone —
 * growth is now fully firm-funded). When a task is complete the colony appends the
 * plot (see {@link Settlement#completeFinishedPlots()}) and seats the firm that was
 * waiting on it.
 * <p>
 * Because the peasants are the ruler's wards (it feeds the pool), the wage the
 * builder pays for their labor is <b>reimbursed to the ruler</b>: the pool registers
 * each peasant with the ruler's account as the wage target, so the builder's wage
 * budget flows to the ruler when the labor market clears. The builder remains a
 * near zero-profit conduit (like {@link StrategicFirm}): sponsors fund the wages,
 * which the ruler — the peasants' patron — collects. It registers itself as the
 * colony's sole builder in its constructor; a colony with a builder grows only
 * through it (a live colony without one cannot grow at all).
 */
public class BuilderFirm extends Firm {

	/**
	 * Name of the dedicated labor market the builder hires from, whose only workers
	 * are the colony's peasants (see {@link Retinue}). Kept separate
	 * from the general {@code "Labor"} market so the two pools never mix.
	 */
	public static final String LABOR_MARKET = "PeasantLabor";

	@Getter
	private final BuilderConfig config;

	// the colony this firm builds for (Agent keeps its own colony reference private)
	private final Settlement colony;

	// the dedicated peasant labor market the builder hires its workers from
	private final LaborMarket lMkt;

	// build-units delivered in the last step, and cumulatively over the firm's life
	@Getter
	private double buildUnitsDelivered;
	@Getter
	private double totalDelivered;

	/**
	 * Create the colony's builder and register it (a colony has at most one). It is
	 * seeded with a checking balance equal to {@link BuilderConfig#initWageBudget()}
	 * so it can fund its first round of building labor before billing recoups the
	 * cost; thereafter it bids only when there is work queued. It posts no wage
	 * budget at construction (it has nothing to build yet), so adding it perturbs
	 * neither the pre-run labor clearing nor an idle colony.
	 *
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which the builder holds its account
	 * @param colony
	 *            the colony it builds for (must not already have a builder)
	 */
	public BuilderFirm(BuilderConfig config, Bank bank, Settlement colony) {
		// seed checking with one wage budget (no savings, no loans)
		super(config.initWageBudget(), 0, bank, colony);
		setName("Builder Firm");
		this.config = config;
		this.colony = colony;
		this.lMkt = (LaborMarket) colony.getMarket(LABOR_MARKET);
		this.wageBudget = 0; // nothing to build yet, so hire no one at founding
		colony.setBuilder(this); // register as the colony's sole builder (throws on a second)
	}

	/**
	 * Called by Settlement.newDay() in each step.
	 */
	public void act() {
		Bank bank = getBank();

		// 1. convert last step's hired labor into build-units, capped by the scaffold
		//    cap (the labor was already daylight-scaled when the workers delivered it)
		double laborQty = labor.getQuantity();
		double producible = convertToProduct(laborQty);
		// never produce more than the work that is actually waiting, so the builder
		// does not burn wages on output no task can absorb
		producible = Math.min(producible, remainingWork());

		// the wages that bought this labor are this step's cost basis; spread that
		// cost across the work delivered, so each sponsor pays its share at cost
		double costBasis = wageBudget;
		double unitCost = producible > 0 ? costBasis / producible : 0;

		// 2. apply the work to the queued plot tasks, billing each sponsor at cost for
		//    what it delivered
		double delivered = 0;
		for (BuildProject p : colony.activeProjects()) {
			double room = producible - delivered;
			if (room <= 0)
				break;
			// the requesting firm funds its own plot's land clearance
			Agent sponsor = p.getSponsor();
			if (sponsor == null)
				continue; // no one to bill — defer this work
			// a dead sponsor (an elite housing commissioner whose estate settled — the
			// default-flip's MayorReform finding) can no longer be billed: defer, so the
			// commission waits for a successor rather than NPE-ing the bank
			if (!sponsor.isAlive() || sponsor.getBank().getAcct(sponsor.getID()) == null)
				continue;
			double done = p.advance(room);
			if (done <= 0)
				continue;
			// bill the sponsor at cost; a sponsor short on cash overdraws into a
			// small loan (the bank turns a checking shortfall into savings debt) and
			// repays it from revenue, so building is never stalled by tight cash
			double bill = done * unitCost;
			sponsor.getBank().withdraw(sponsor.getID(), bill);
			bank.credit(getID(), bill, Bank.PRIIC);
			delivered += done;
		}
		buildUnitsDelivered = delivered;
		totalDelivered += delivered;
		output = delivered;
		revenue = delivered * unitCost;

		// 3. a fully-built plot is appended to the colony and seats the firm waiting on it
		colony.completeFinishedPlots();

		// 4. size next step's wage budget to whether there is work left (0 -> lay the
		//    workers off), and post it to the labor market
		double prevBudget = wageBudget;
		wageBudget = nextWageBudget();
		wage = laborQty > 0 ? prevBudget / laborQty : 0;
		totalCost = prevBudget;
		profit = revenue - totalCost;
		lMkt.addEmployer(this, labor, wageBudget);

		labor.decrease(labor.getQuantity()); // labor consumed in building
	}

	// bid the working wage budget while any task is queued; nothing when idle
	private double nextWageBudget() {
		List<BuildProject> active = colony.activeProjects();
		return active.isEmpty() ? 0 : config.targetWageBudget();
	}

	// total build-units still outstanding across the ring being built
	private double remainingWork() {
		double sum = 0;
		for (BuildProject p : colony.activeProjects())
			sum += p.getWorkRemaining();
		return sum;
	}

	/**
	 * Return build-units produced by <tt>labor</tt> amount of labor, capped by the
	 * scaffold cap.
	 *
	 * @param labor
	 *            amount of labor
	 * @return build-units produced, {@code min(scaffoldCap, A · labor^beta)}
	 */
	public double convertToProduct(double labor) {
		return Math.min(config.scaffoldCap(),
				config.A() * Math.pow(labor, config.beta()));
	}

	/**
	 * Return a reference to <tt>good</tt> owned by the firm.
	 */
	public Good getGood(String good) {
		if (good.equals("Labor"))
			return labor;
		return null;
	}

	/** Building (land clearance, roads, walls) trains {@link Skill#CONSTRUCTION}. */
	@Override
	public Set<Skill> laborSkills() {
		return Set.of(Skill.CONSTRUCTION);
	}
}
