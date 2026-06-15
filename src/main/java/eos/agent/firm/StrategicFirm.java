package eos.agent.firm;

import java.util.Set;

import eos.bank.Bank;
import eos.calendar.DayType;
import eos.settlement.Settlement;
import eos.good.Good;
import eos.good.Strategic;
import eos.market.LaborMarket;
import eos.skill.Skill;
import lombok.Getter;

/**
 * The settlement's single <b>export firm</b>. It hires labor on the {@link
 * LaborMarket} and converts it into the {@link Strategic} good, which it then
 * <b>exports out of the economy</b>: the good leaves circulation and, in return,
 * external money equal to its export value ({@code exportPrice · quantity}) is
 * added to the {@link Bank}'s <b>equity</b>. The wage bill is funded back out of
 * that equity (via {@link Bank#payFromEquity}), so the firm is a pure conduit —
 * each step the colony's bank equity rises by the export earnings net of wages,
 * while the workers it employs are paid into circulation.
 * <p>
 * There is at most one StrategicFirm per {@link Settlement}, enforced by {@link
 * Settlement#setStrategicFirm(StrategicFirm)} (the constructor registers it). The
 * firm holds no capital and sells into no domestic market: labor is its only
 * input and export its only output.
 * <p>
 * Its labor pool is constrained to <b>nobles</b>: the firm employs from a
 * dedicated {@value #LABOR_MARKET} market whose only employees are {@link
 * eos.agent.noble.Noble}s, who supply skill-scaled labor exactly as laborers do
 * on the general labor market. So the strategic sector is worked by the
 * aristocracy, while laborers staff the consumer and capital firms.
 *
 * @author zhihongx
 */
public class StrategicFirm extends Firm {

	/** Name of the labor market the strategic firm employs from (nobles only). */
	public static final String LABOR_MARKET = "NobleLabor";

	// tunable model parameters
	private final StrategicFirmConfig config;

	// the noble-only labor market this firm employs from
	private final LaborMarket lMkt;

	// the strategic good: produced from labor, then immediately exported, so the
	// firm never holds a stock of it
	private final Strategic product;

	// units of strategic good exported in the last step
	@Getter
	private double exported;

	// cumulative units of strategic good exported over the firm's life
	@Getter
	private double totalExported;

	/**
	 * Create the settlement's export firm and register it as the (sole) strategic
	 * firm. The firm is seeded with a checking balance equal to its initial wage
	 * budget so the pre-run labor clearing can pay its first workers; thereafter
	 * its wage bill is funded out of bank equity from export earnings.
	 *
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which this firm holds its accounts and into whose
	 *            equity its export earnings flow
	 * @param colony
	 *            the colony this firm belongs to (must not already have a
	 *            strategic firm)
	 */
	public StrategicFirm(StrategicFirmConfig config, Bank bank,
			Settlement colony) {
		// seed checking with the wage budget (no savings, no loans)
		super(config.wageBudget(), 0, bank, colony);
		setName("Strategic Firm");
		this.config = config;
		this.product = new Strategic(0);
		this.lMkt = (LaborMarket) colony.getMarket(LABOR_MARKET);
		if (lMkt == null)
			throw new IllegalStateException("StrategicFirm requires a \""
					+ LABOR_MARKET + "\" market (its noble labor pool)");
		wageBudget = config.wageBudget();

		// register as the colony's sole export firm (throws if one already exists)
		colony.setStrategicFirm(this);

		// post the initial wage budget so the firm has workers before step 0
		lMkt.addEmployer(this, labor, wageBudget);
	}

	/**
	 * Called by Settlement.newDay() in each step.
	 */
	public void act() {
		Bank bank = getBank();

		// 1. convert the labor hired last step into the strategic good
		double laborQty = labor.getQuantity();
		double produced = convertToProduct(laborQty);
		product.increase(produced);

		// 2. export it: the good leaves the economy and external money equal to
		// its export value flows into the bank's equity
		double qty = product.getQuantity();
		product.decrease(qty);
		exported = qty;
		totalExported += qty;
		output = produced;
		revenue = config.exportPrice() * qty;
		bank.injectExternalFunds(revenue);

		// 3. bid a fixed wage budget for next step's labor, funded back out of
		// equity so the labor market can pay the workers; the export earnings net
		// of this budget are what stays in equity. The export sector runs every
		// day (see operatesOn), so it always bids.
		double newWageBudget = config.wageBudget();
		bank.payFromEquity(getID(), newWageBudget);

		wage = laborQty > 0 ? wageBudget / laborQty : 0;
		totalCost = wageBudget;
		profit = revenue - totalCost;
		wageBudget = newWageBudget;

		// post the wage budget to the labor market for the next round
		lMkt.addEmployer(this, labor, newWageBudget);

		labor.decrease(labor.getQuantity()); // labor consumed in production
	}

	/**
	 * Return export output produced by <tt>labor</tt> amount of labor.
	 *
	 * @param labor
	 *            amount of labor
	 * @return output produced, {@code A · labor^beta}
	 */
	public double convertToProduct(double labor) {
		return config.A() * Math.pow(labor, config.beta());
	}

	/**
	 * Return a reference to <tt>good</tt> owned by the firm.
	 */
	public Good getGood(String good) {
		if (good.equals("Labor"))
			return labor;
		if (good.equals("Strategic"))
			return product;
		return null;
	}

	/** The nobles who work the export sector train {@link Skill#SOCIAL}. */
	@Override
	public Set<Skill> laborSkills() {
		return Set.of(Skill.SOCIAL);
	}

	/**
	 * The export sector runs <b>every day</b>. It is the colony's strategic
	 * lifeline, worked by a dedicated noble class rather than by the laboring
	 * population, so it does not observe the rest-day calendar the consumer firms
	 * follow. In particular it keeps running on feast days when the laborer firms
	 * are shut — so the nobles are the only ones working then, as required — and
	 * on the weekly day of rest, so its noble workers keep building their export
	 * skill instead of idling every Sunday.
	 */
	@Override
	public boolean operatesOn(DayType day) {
		return true;
	}
}
