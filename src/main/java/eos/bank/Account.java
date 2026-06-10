package eos.bank;

import lombok.Getter;

/**
 * An account of an agent, held by the {@link Bank}. Each account has a checking
 * balance and a savings balance. A positive savings balance signifies a
 * deposit (and earns interest); a negative savings balance signifies a loan
 * (and pays interest).
 *
 * @author zhihongx
 *
 */
public class Account {

	// checking account balance; mutated directly by the Bank (same package)
	@Getter
	double checking;

	// savings account balance; mutated directly by the Bank (same package)
	@Getter
	double savings;

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
	 * Create a new account with checking account balance <tt>checkingBal</tt>
	 * and savings account balance <tt>savingsBal</tt>
	 */
	public Account(double checkingBal, double savingsBal) {
		this.checking = checkingBal;
		this.savings = savingsBal;
		priIC = 0;
		secIC = 0;
	}
}
