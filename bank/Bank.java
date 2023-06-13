package eos.bank;

import java.util.HashMap;

import eos.economy.Economy;
import eos.util.Averager;

/**
 * Bank. There is only one bank in the current model. Each agent has a checking
 * account and a savings account. All transactions are settled exclusively
 * through transfers between the checking accounts. A positive balance in the
 * savings account signifies deposit and earns interest. A negative balance
 * signifies loans and pays interest. There are two interest rates - loan
 * interest rate and deposit interest rate. Loan interest rate is determined by
 * the demand and supply of loans. Deposit interest rate is computed by
 * distributing interest payment from all debtors to creditors.
 * 
 * @author zhihongx
 * 
 */
public class Bank {

	/************* parameters **********************/
	/**
	 * initial loan interest rate
	 */
	public static final double INIT_LOAN_IR = 0.01;

	/**
	 * interest rate when total loan == total deposit
	 */
	public static final double IR0 = 0.01;

	/**
	 * sensitivity of interest rate to a change in total loan
	 */
	public static double tao = 0.005;

	/**
	 * time window within which long-term interest rate is measured
	 */
	public static final int LT_IR_WIN = 100;

	/**
	 * max loan interest rate
	 */
	public static double MAX_LOAN_IR = 0.01;

	/**
	 * min loan interest rate
	 */
	public static double MIN_LOAN_IR = 0.0005;

	/*********************************************/

	/**
	 * account type - checking account
	 */
	public static final int CHECKING = 0;

	/**
	 * account type - savings account
	 */
	public static final int SAVINGS = 1;

	/* payment purposes */

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

	/**
	 * An account of an agent
	 * 
	 * @author zhihongx
	 * 
	 */
	public static class Account {
		// account balance: <tt>balance[CHECKING]</tt> gives the checking
		// account balance, and <tt>balance[SAVINGS]</tt> gives the savings
		// account balance
		private double[] balance;

		/* These give the most recent payment information */
		/**
		 * primary income in the last step
		 */
		public double priIC;
		
		/**
		 *  secondary income in the last step
		 */
		public double secIC; 
		
		/**
		 *  interest in the last step
		 */
		public double interest; 

		/**
		 * Create a new account with checking account balance
		 * <tt>checkingBal</tt> and savings account balance <tt>savingsBal</tt>
		 */
		public Account(double checkingBal, double savingsBal) {
			balance = new double[2];
			balance[CHECKING] = checkingBal;
			balance[SAVINGS] = savingsBal;
			priIC = 0;
			secIC = 0;
		}

		/**
		 * Return the balance of <tt>acctType</tt>
		 * 
		 * @param acctType
		 * @return balance of <tt>acctType</tt>
		 */
		public double getBalance(int acctType) {
			if (acctType == CHECKING)
				return balance[CHECKING];
			else if (acctType == SAVINGS)
				return balance[SAVINGS];
			else {
				System.err.println("getBalance: Illegal acctType");
				System.exit(1);
				return 0;
			}
		}
	}

	// map from agentID to the corresponding account
	private static HashMap<Integer, Account> accounts = new HashMap<Integer, Account>();

	// total amount of loans
	private static double totalLoan;

	// total amount of deposits
	private static double totalDeposit;

	// loan interest rate
	private static double loanIR = INIT_LOAN_IR;

	// deposit interest rate
	private static double depositIR;

	// long-term loan interest rate
	private static double ltLoanIR;

	// long-term deposit interest rate
	private static double ltDepositIR;

	// classes used to compute the average interest rate within LT_IR_WIN
	private static Averager depositIRAvger = new Averager(LT_IR_WIN),
			loanIRAvger = new Averager(LT_IR_WIN);

	private static double targetIR;

	/**
	 * Open an account, which includes a checking account and a savings account;
	 * 
	 * @param agentID
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 */
	public static void openAcct(int agentID, double initCheckingBal,
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
	public static void closeAcct(int agentID) {
		accounts.remove(agentID);
	}

	/**
	 * Return the balance of the specified account
	 * 
	 * @param agentID
	 * @param acctType
	 *            account type, either CHECKING or SAVINGS
	 * @return the balance of the specified account
	 */
	public static double getBalance(int agentID, int acctType) {
		Account acct = accounts.get(agentID);
		if (acct == null) {
			System.err.println("getBalance: account doesn't exist: " + agentID);
			System.exit(1);
		}
		return acct.getBalance(acctType);
	}

	/**
	 * Return a reference to the agent's account
	 * 
	 * @param agentID
	 * @return a reference to the agent's account
	 */
	public static Account getAcct(int agentID) {
		return accounts.get(agentID);
	}

	/**
	 * Deduct <tt>amt</tt> from the payer's checking account. If the checking
	 * account contains an insufficient balance, funds would be withdrawn from
	 * the savings account to make up the difference.
	 * 
	 * @param payerID
	 * @param amt
	 *            amount to be paid
	 */
	public static void payFrom(int payerID, double amt) {
		Account fromAcct = accounts.get(payerID);

		if (fromAcct.balance[CHECKING] < amt) {
			double diff = amt - fromAcct.balance[CHECKING];
			fromAcct.balance[CHECKING] += diff;
			fromAcct.balance[SAVINGS] -= diff;
		}
		fromAcct.balance[CHECKING] -= amt;
	}

	/**
	 * Add <tt>amt</tt> to payee's checking account.
	 * 
	 * @param payeeID
	 * @param amt
	 *            amount to be paid
	 * @param purpose
	 *            purpose of the payment (either PRIIC or SECIC)
	 */
	public static void payTo(int payeeID, double amt, int purpose) {
		Account toAcct = accounts.get(payeeID);
		toAcct.balance[CHECKING] += amt;
		if (purpose == PRIIC)
			toAcct.priIC += amt;
		else
			toAcct.secIC += amt;
	}

	/**
	 * Transfer <tt>amt</tt> from payer's checking account to payee's checking
	 * account. If payer's checking account has a balance less than <tt>amt</tt>
	 * , funds will be withdrawn from the savings account to make up the
	 * difference.
	 * 
	 * @param payerID
	 * @param payeeID
	 * @param amt
	 *            amount to be paid
	 * @param purpose
	 *            either PRIIC or SECIC
	 */
	public static void pay(int payerID, int payeeID, double amt, int purpose) {
		Account fromAcct = accounts.get(payerID);
		Account toAcct = accounts.get(payeeID);
		if (fromAcct.balance[CHECKING] < amt) {
			double diff = amt - fromAcct.balance[CHECKING];
			fromAcct.balance[CHECKING] += diff;
			fromAcct.balance[SAVINGS] -= diff;
		}

		fromAcct.balance[CHECKING] -= amt;
		toAcct.balance[CHECKING] += amt;
		if (purpose == PRIIC)
			toAcct.priIC += amt;
		else
			toAcct.secIC += amt;
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
	public static double deposit(int agentID, double amt) {
		Account acct = accounts.get(agentID);
		double ret = Math.min(amt, acct.balance[CHECKING]);
		acct.balance[CHECKING] -= ret;
		acct.balance[SAVINGS] += ret;
		return ret;
	}

	/**
	 * Called by Economy.step() in every time step
	 */
	public static void act() {
		totalLoan = 0;
		totalDeposit = 0;

		/* compute total loan and total deposit */
		for (Account acct : accounts.values()) {
			double bal = acct.balance[SAVINGS];
			if (bal < 0)
				totalLoan -= bal;
			else
				totalDeposit += bal;
			acct.interest = 0;
		}

		if (Economy.getTimeStep() == 0) {
			tao /= Math.max(1, Math.abs(totalDeposit - totalLoan));
			targetIR = loanIR;
		}

		if (totalDeposit == 0) {
			loanIR = 0;
			depositIR = 0;
		} else {
			// set target loan interest rate
			targetIR = IR0 - tao * (totalLoan - totalDeposit);

			// set loan interest rate
			loanIR = Math.max(loanIR - 0.001,
					Math.min(loanIR + 0.001, targetIR));

			/*
			 * if (Economy.getTimeStep() == 3000) loanIR = 0.001;
			 * 
			 * if (Economy.getTimeStep() == 3001) loanIR = oldLoanIR;
			 */

			loanIR = Math.min(MAX_LOAN_IR, Math.max(MIN_LOAN_IR, loanIR));

			/* compute deposit interest rate */
			depositIR = loanIR * totalLoan / totalDeposit;

			/* pay interest and collect interest payment */
			for (Account acct : accounts.values()) {
				if (acct.balance[SAVINGS] > 0) {
					acct.interest = acct.balance[SAVINGS] * depositIR;
					acct.balance[CHECKING] += acct.interest;
				} else {
					acct.interest = acct.balance[SAVINGS] * loanIR;
					acct.balance[SAVINGS] += acct.interest;
				}
			}
		}

		/* update long-term interest rates */
		ltLoanIR = loanIRAvger.update(loanIR);
		ltDepositIR = depositIRAvger.update(depositIR);
	}

	/**
	 * Return the loan interest rate in the last step
	 * 
	 * @return the loan interest rate in the last step
	 */
	public static double getLoanIR() {
		return loanIR;
	}

	/**
	 * Return the deposit interest rate in the last step
	 * 
	 * @return the deposit interest rate in the last step
	 */
	public static double getDepositIR() {
		return depositIR;
	}

	/**
	 * Return the long-term deposit interest rate in the last step
	 * 
	 * @return the long-term deposit interest rate in the last step
	 */
	public static double getLTDepositIR() {
		return ltDepositIR;
	}

	/**
	 * Return the long-term loan interest rate in the last step
	 * 
	 * @return the long-term loan interest rate in the last step
	 */
	public static double getLTLoanIR() {
		return ltLoanIR;
	}

	/**
	 * Return the total loan in the last step
	 * 
	 * @return the total loan in the last step
	 */
	public static double getTotalLoan() {
		return totalLoan;
	}

	/**
	 * Return the total deposit in the last step
	 * 
	 * @return the total deposit in the last step
	 */
	public static double getTotalDeposit() {
		return totalDeposit;
	}
}
