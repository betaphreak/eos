package com.civstudio.bank;

import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.market.WeddingMarket;
import com.civstudio.agent.Property;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Averager;
import lombok.Getter;

/**
 * Bank. The colony may contain more than one bank; each agent holds its
 * accounts at a specific bank instance and payments are routed through the
 * payer's and payee's banks. Each agent has a checking account and a savings
 * account. A positive balance in the savings account signifies deposit and
 * earns interest. A negative balance signifies loans and pays interest. There
 * are two interest rates - loan interest rate and deposit interest rate. Loan
 * interest rate is determined by the demand and supply of loans. Deposit
 * interest rate is computed by distributing interest payment from all debtors
 * to creditors.
 *
 * @author zhihongx
 *
 */
public class Bank implements Property {

	/* payment purposes (shared across all banks) */

	/**
	 * primary income: wage for laborers, sales revenue for firms
	 */
	public static final int PRIIC = 0;

	/**
	 * secondary income: e.g. dividend
	 */
	public static final int SECIC = 1;

	/**
	 * other payment
	 */
	public static final int OTHER = 2;

	// tunable model parameters
	private final BankConfig config;

	// the colony this bank belongs to
	private final Settlement colony;

	// display name, defaulted from the colony's bank sequence (e.g. "Bank 1")
	@Getter
	private final String name;

	// the currency this bank denominates its accounts in
	@Getter
	private final CurrencyType currency;

	// working copy of the loan-sensitivity parameter; normalized at step 0
	private double tao;

	// map from agentID to the corresponding account (int-keyed to avoid the
	// boxing/hash-bucket cost of the per-payment lookups that dominate the step
	// loop; see IntAccountMap)
	private final IntAccountMap accounts = new IntAccountMap();

	// total amount of loans
	@Getter
	private double totalLoan;

	// total amount of deposits
	@Getter
	private double totalDeposit;

	// loan interest rate
	@Getter
	private double loanIR;

	// deposit interest rate
	@Getter
	private double depositIR;

	// cumulative retained profit (interest spread + transaction fees)
	@Getter
	private double equity;

	// gross profit ever retained (interest spread + transaction fees) and the
	// portion of it already paid out to owners as dividends. Their difference is
	// the slice of equity attributable to profit — and thus distributable to an
	// owner — as opposed to estates in transit or injected external funds, which
	// also pass through equity but must not be skimmed.
	private double cumulativeProfit;
	@Getter
	private double distributedProfit;

	// long-term loan interest rate
	private double ltLoanIR;

	// long-term deposit interest rate
	private double ltDepositIR;

	// classes used to compute the average interest rate within ltIRWin
	private final Averager depositIRAvger, loanIRAvger;

	private double targetIR;

	/**
	 * Create a new bank.
	 *
	 * @param config
	 *            tunable model parameters
	 * @param colony
	 *            the colony this bank belongs to
	 */
	public Bank(BankConfig config, Settlement colony) {
		this.config = config;
		this.colony = colony;
		this.name = "Bank " + colony.nextBankNumber();
		this.currency = config.currency();
		this.tao = config.tao();
		this.loanIR = config.initLoanIR();
		this.depositIRAvger = new Averager(config.ltIRWin());
		this.loanIRAvger = new Averager(config.ltIRWin());
	}

	/**
	 * Open an account, which includes a checking account and a savings account;
	 *
	 * @param agentID
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 */
	public void openAcct(int agentID, double initCheckingBal,
			double initSavingsBal) {
		if (accounts.containsKey(agentID)) {
			System.err.println("openAcct: account already existed: " + agentID);
			System.exit(1);
		}
		Account acct = new Account(initCheckingBal, initSavingsBal);
		accounts.put(agentID, acct);
	}

	/**
	 * Close an account
	 *
	 * @param agentID
	 */
	public void closeAcct(int agentID) {
		accounts.remove(agentID);
	}

	/**
	 * Settle a deceased account holder's estate and close the account. The bank
	 * inherits any leftover money and absorbs (cancels) any outstanding debt;
	 * the net worth of the account (checking plus savings, the latter negative
	 * for a loan) is added to the bank's equity.
	 *
	 * @param agentID
	 *            the deceased account holder
	 */
	public void inheritAndClose(int agentID) {
		Account acct = accounts.get(agentID);
		if (acct != null) {
			equity += acct.checking + acct.savings;
			accounts.remove(agentID);
		}
	}

	/**
	 * Open an account for an heir, funding it out of the bank's equity. This is
	 * the counterpart to {@link #inheritAndClose(int)}: the deceased estate that
	 * was folded into equity is handed to the successor household, so money
	 * stays in circulation rather than being permanently drained. A loan
	 * (negative savings) carried over likewise reduces equity by the inherited
	 * debt.
	 *
	 * @param agentID
	 *            the heir's account (== agent) id
	 * @param checking
	 *            inherited checking balance
	 * @param savings
	 *            inherited savings balance (negative for a loan)
	 */
	public void openInheritedAcct(int agentID, double checking, double savings) {
		equity -= checking + savings;
		openAcct(agentID, checking, savings);
	}

	/**
	 * Inject money from outside the colony into the bank's equity. Unlike
	 * retained interest spread or fees (which capture money already inside the
	 * colony), this is genuinely new money entering an open colony; it
	 * bankrolls externally-funded household formation. The counterpart drawdown
	 * happens in {@link #openInheritedAcct(int, double, double)} when the new
	 * household's account is opened out of equity.
	 *
	 * @param amount
	 *            the external funds to add to equity
	 */
	public void injectExternalFunds(double amount) {
		equity += amount;
	}

	/**
	 * Remove money from the colony: debit <tt>amount</tt> from <tt>agentID</tt>'s
	 * account and <b>destroy the principal</b> — it is not credited anywhere and
	 * does not enter equity, so the colony's total money supply falls by
	 * {@code amount}. The counterpart to {@link #injectExternalFunds(double)} for
	 * money that <em>leaves</em> the colony (e.g. the {@link
	 * WeddingMarket} paying gold abroad to recruit an immigrant). Pulls
	 * from savings only if checking is short; callers that must not let the payer
	 * borrow should gate on the balance first. No transaction or FX fee is charged
	 * (the whole {@code amount} leaves), so the figure is the exact outflow.
	 *
	 * @param agentID
	 *            the account the money leaves from
	 * @param amount
	 *            the amount to remove from the colony (in copper)
	 */
	public void extractExternalFunds(int agentID, double amount) {
		Account acct = accounts.get(agentID);
		if (acct.checking < amount) {
			double diff = amount - acct.checking;
			acct.checking += diff;
			acct.savings -= diff;
		}
		acct.checking -= amount;
	}

	/**
	 * Move <tt>amount</tt> from the bank's equity into <tt>agentID</tt>'s checking
	 * account. This is the counterpart to {@link #injectExternalFunds(double)} for
	 * an agent that spends <em>out of</em> equity rather than out of its own
	 * income — namely the {@link StrategicFirm}, whose export
	 * earnings are injected into equity and whose wage bill is then funded back
	 * out of it, leaving the firm a pure conduit that turns exported goods into
	 * retained equity. It does not touch {@code cumulativeProfit} /
	 * {@code distributedProfit}, so the distributable-profit accounting (noble
	 * dividends) is unaffected.
	 *
	 * @param agentID
	 *            the account to fund
	 * @param amount
	 *            the amount to move from equity into the account's checking
	 */
	public void payFromEquity(int agentID, double amount) {
		Account acct = accounts.get(agentID);
		acct.checking += amount;
		equity -= amount;
	}

	/**
	 * The bank's retained profit not yet paid out to owners: cumulative interest
	 * spread and transaction fees, less dividends already distributed. This is the
	 * slice of {@link #getEquity() equity} attributable to profit (as opposed to
	 * estates in transit or injected external funds, which also pass through
	 * equity), so a noble owner can skim it as a dividend without disturbing
	 * inheritance or the open-colony money buffer. Zero for a default
	 * zero-profit bank ({@code spread == 0} and {@code feeRate == 0}).
	 *
	 * @return the distributable retained profit
	 */
	public double getDistributableProfit() {
		return Math.max(0, cumulativeProfit - distributedProfit);
	}

	/**
	 * Pay out <tt>amt</tt> of the bank's retained profit to an owner as a
	 * dividend: it leaves the bank's equity. Only genuine profit is distributable
	 * (see {@link #getDistributableProfit()}); the caller must not request more.
	 *
	 * @param amt
	 *            the dividend to pay out (0 ≤ amt ≤ {@link #getDistributableProfit()})
	 */
	public void payDividend(double amt) {
		assert amt >= 0 && amt <= getDistributableProfit() + 1e-9
				: "dividend exceeds distributable profit";
		equity -= amt;
		distributedProfit += amt;
	}

	/**
	 * The total money this bank holds for the colony: the net of every account
	 * (checking + savings, a negative savings being a loan) plus the bank's
	 * {@link #getEquity() equity}. In a closed colony the sum of this across all
	 * banks is the conserved circulating-money stock — the basis for a dissolving
	 * colony's carried hoard (see {@code docs/caravan.md}).
	 *
	 * @return the bank's total money (account balances net of loans, plus equity)
	 */
	public double getTotalMoney() {
		double[] sum = { equity };
		accounts.forEachValue(a -> sum[0] += a.getChecking() + a.getSavings());
		return sum[0];
	}

	/**
	 * Drain every account and the bank's equity into a single returned amount,
	 * zeroing them — the <b>money-conserving teardown</b> a dissolving colony uses to
	 * net its circulating money into a carried hoard (see {@code docs/caravan.md}).
	 * After this the bank holds nothing ({@link #getTotalMoney()} is 0); the colony is
	 * expected to be discarded, so the now-empty accounts are never touched again.
	 *
	 * @return the total money drained (equal to the pre-drain {@link #getTotalMoney()})
	 */
	public double drainAllMoney() {
		double[] sum = { equity };
		accounts.forEachValue(a -> {
			sum[0] += a.getChecking() + a.getSavings();
			a.checking = 0;
			a.savings = 0;
		});
		equity = 0;
		return sum[0];
	}

	// --- Property: the bank is an asset its owner draws a dividend from ---

	/** {@inheritDoc} A bank's distributable profit is its retained spread and fees
	 * not yet paid out — see {@link #getDistributableProfit()}. */
	@Override
	public double distributableProfit() {
		return getDistributableProfit();
	}

	/** {@inheritDoc} A bank pays a dividend by skimming it from its retained equity
	 * — see {@link #payDividend(double)}. */
	@Override
	public void disburse(double amount) {
		payDividend(amount);
	}

	/**
	 * Return the account of <tt>agentID</tt>, exiting if it does not exist.
	 *
	 * @param agentID
	 * @return the agent's account
	 */
	private Account requireAcct(int agentID) {
		Account acct = accounts.get(agentID);
		if (acct == null) {
			System.err.println("account doesn't exist: " + agentID);
			System.exit(1);
		}
		return acct;
	}

	/**
	 * Return the checking account balance of <tt>agentID</tt>
	 *
	 * @param agentID
	 * @return the checking account balance
	 */
	public double getChecking(int agentID) {
		return requireAcct(agentID).checking;
	}

	/**
	 * Return the savings account balance of <tt>agentID</tt>
	 *
	 * @param agentID
	 * @return the savings account balance
	 */
	public double getSavings(int agentID) {
		return requireAcct(agentID).savings;
	}

	/**
	 * Return a reference to the agent's account
	 *
	 * @param agentID
	 * @return a reference to the agent's account
	 */
	public Account getAcct(int agentID) {
		return accounts.get(agentID);
	}

	/**
	 * This bank's currency-exchange (FX) fee rate. Because every price is quoted
	 * in copper (the base unit), a non-copper account converts to/from copper on
	 * every payment, so the bank charges {@link BankConfig#exchangeFeeRate()} as
	 * its money-changer's cut; a copper bank is already in the base currency and
	 * never charges (returns 0).
	 *
	 * @return the FX fee fraction applied to this bank's payments
	 */
	private double exchangeFeeRate() {
		return currency == CurrencyType.COPPER ? 0 : config.exchangeFeeRate();
	}

	/**
	 * Deduct <tt>amt</tt> from the agent's checking account. If the checking
	 * account contains an insufficient balance, funds would be withdrawn from
	 * the savings account to make up the difference. The payer also bears the
	 * transaction fee ({@link BankConfig#feeRate()}) and, for a non-copper bank,
	 * the currency-exchange fee on the copper-quoted amount being paid out (see
	 * {@link #exchangeFeeRate()}); both are retained as the bank's profit.
	 *
	 * @param agentID
	 * @param amt
	 *            amount to withdraw
	 */
	public void withdraw(int agentID, double amt) {
		Account acct = accounts.get(agentID);
		double fee = amt * (config.feeRate() + exchangeFeeRate());
		double total = amt + fee;
		if (acct.checking < total) {
			double diff = total - acct.checking;
			acct.checking += diff;
			acct.savings -= diff;
		}
		acct.checking -= total;
		equity += fee;
		cumulativeProfit += fee;
	}

	/**
	 * Add <tt>amt</tt> to the agent's checking account and record it as income.
	 * For a non-copper bank the payee bears the currency-exchange fee on the
	 * copper-quoted amount being received (see {@link #exchangeFeeRate()}): the
	 * net of the fee is credited and recorded as income, and the fee is retained
	 * as the bank's profit. Money is conserved — the fee moves into equity rather
	 * than the payee's checking.
	 *
	 * @param agentID
	 * @param amt
	 *            amount to credit
	 * @param purpose
	 *            purpose of the payment (either PRIIC or SECIC)
	 */
	public void credit(int agentID, double amt, int purpose) {
		Account acct = accounts.get(agentID);
		double fee = amt * exchangeFeeRate();
		double net = amt - fee;
		acct.checking += net;
		if (purpose == PRIIC)
			acct.priIC += net;
		else
			acct.secIC += net;
		equity += fee;
		cumulativeProfit += fee;
	}

	/**
	 * Deposit <tt>amt</tt> from agent's checking account to the savings
	 * account. If the checking account balance is less than <tt>amt</tt>, all
	 * remaining balance in checking account is deposited
	 *
	 * @param agentID
	 * @param amt
	 *            amount to be paid
	 * @return actual amount of money deposited into the savings account
	 */
	public double deposit(int agentID, double amt) {
		Account acct = accounts.get(agentID);
		double ret = Math.min(amt, acct.checking);
		acct.checking -= ret;
		acct.savings += ret;
		return ret;
	}

	/**
	 * Called by Settlement.newDay() in every time step
	 */
	public void act() {
		totalLoan = 0;
		totalDeposit = 0;

		/* compute total loan and total deposit */
		accounts.forEachValue(acct -> {
			double bal = acct.savings;
			if (bal < 0)
				totalLoan -= bal;
			else
				totalDeposit += bal;
			acct.interest = 0;
		});

		if (colony.getTimeStep() == 0) {
			tao /= Math.max(1, Math.abs(totalDeposit - totalLoan));
			targetIR = loanIR;
		}

		if (totalDeposit == 0) {
			loanIR = 0;
			depositIR = 0;
		} else {
			// set target loan interest rate
			targetIR = config.ir0() - tao * (totalLoan - totalDeposit);

			// set loan interest rate
			loanIR = Math.max(loanIR - 0.001,
					Math.min(loanIR + 0.001, targetIR));

			loanIR = Math.min(config.maxLoanIR(),
					Math.max(config.minLoanIR(), loanIR));

			/*
			 * Compute deposit interest rate. Without a spread the bank
			 * redistributes all collected loan interest to depositors
			 * (depositIR * totalDeposit == loanIR * totalLoan), making zero
			 * profit. A positive spread pays depositors less and retains the
			 * difference as equity.
			 */
			depositIR = loanIR * totalLoan / totalDeposit * (1 - config.spread());
			double spreadProfit = loanIR * totalLoan * config.spread();
			equity += spreadProfit;
			cumulativeProfit += spreadProfit;

			/* pay interest and collect interest payment */
			accounts.forEachValue(acct -> {
				if (acct.savings > 0) {
					acct.interest = acct.savings * depositIR;
					acct.checking += acct.interest;
				} else {
					acct.interest = acct.savings * loanIR;
					acct.savings += acct.interest;
				}
			});
		}

		/* update long-term interest rates */
		ltLoanIR = loanIRAvger.update(loanIR);
		ltDepositIR = depositIRAvger.update(depositIR);
	}

	/**
	 * Return the long-term deposit interest rate in the last step
	 *
	 * @return the long-term deposit interest rate in the last step
	 */
	public double getLTDepositIR() {
		return ltDepositIR;
	}

	/**
	 * Return the long-term loan interest rate in the last step
	 *
	 * @return the long-term loan interest rate in the last step
	 */
	public double getLTLoanIR() {
		return ltLoanIR;
	}

	/**
	 * A concise, debug-friendly summary: name, account count and the latest
	 * pool sizes, rates and retained equity.
	 */
	@Override
	public String toString() {
		return String.format(
				"%s [%s accounts=%d loan=%.1f deposit=%.1f loanIR=%.4f depositIR=%.4f equity=%.2f]",
				name, currency, accounts.size(), totalLoan, totalDeposit, loanIR,
				depositIR, equity);
	}
}
