package eos.bank;

import java.util.HashMap;

import eos.economy.Economy;
import eos.util.Averager;
import lombok.Getter;

/**
 * Bank. The economy may contain more than one bank; each agent holds its
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
public class Bank {

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

	// the economy this bank belongs to
	private final Economy economy;

	// display name, defaulted from the economy's bank sequence (e.g. "Bank 1")
	@Getter
	private final String name;

	// working copy of the loan-sensitivity parameter; normalized at step 0
	private double tao;

	// map from agentID to the corresponding account
	private final HashMap<Integer, Account> accounts = new HashMap<Integer, Account>();

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
	 * @param economy
	 *            the economy this bank belongs to
	 */
	public Bank(BankConfig config, Economy economy) {
		this.config = config;
		this.economy = economy;
		this.name = "Bank " + economy.nextBankNumber();
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
	 * Deduct <tt>amt</tt> from the agent's checking account. If the checking
	 * account contains an insufficient balance, funds would be withdrawn from
	 * the savings account to make up the difference.
	 *
	 * @param agentID
	 * @param amt
	 *            amount to withdraw
	 */
	public void withdraw(int agentID, double amt) {
		Account acct = accounts.get(agentID);
		double fee = amt * config.feeRate();
		double total = amt + fee;
		if (acct.checking < total) {
			double diff = total - acct.checking;
			acct.checking += diff;
			acct.savings -= diff;
		}
		acct.checking -= total;
		equity += fee;
	}

	/**
	 * Add <tt>amt</tt> to the agent's checking account and record it as income.
	 *
	 * @param agentID
	 * @param amt
	 *            amount to credit
	 * @param purpose
	 *            purpose of the payment (either PRIIC or SECIC)
	 */
	public void credit(int agentID, double amt, int purpose) {
		Account acct = accounts.get(agentID);
		acct.checking += amt;
		if (purpose == PRIIC)
			acct.priIC += amt;
		else
			acct.secIC += amt;
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
	 * Called by Economy.newDay() in every time step
	 */
	public void act() {
		totalLoan = 0;
		totalDeposit = 0;

		/* compute total loan and total deposit */
		for (Account acct : accounts.values()) {
			double bal = acct.savings;
			if (bal < 0)
				totalLoan -= bal;
			else
				totalDeposit += bal;
			acct.interest = 0;
		}

		if (economy.getTimeStep() == 0) {
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
			equity += loanIR * totalLoan * config.spread();

			/* pay interest and collect interest payment */
			for (Account acct : accounts.values()) {
				if (acct.savings > 0) {
					acct.interest = acct.savings * depositIR;
					acct.checking += acct.interest;
				} else {
					acct.interest = acct.savings * loanIR;
					acct.savings += acct.interest;
				}
			}
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
				"%s [accounts=%d loan=%.1f deposit=%.1f loanIR=%.4f depositIR=%.4f equity=%.2f]",
				name, accounts.size(), totalLoan, totalDeposit, loanIR,
				depositIR, equity);
	}
}
