package eos.bank;

import lombok.Builder;

/**
 * Tunable model parameters for a {@link Bank}. Immutable; {@link #DEFAULT} holds
 * the canonical values. Note {@code tao} is the <em>initial</em> sensitivity;
 * the bank normalizes its own working copy by the initial loan/deposit gap at
 * step 0.
 *
 * @param initLoanIR initial loan interest rate
 * @param ir0        interest rate when total loan == total deposit
 * @param tao        initial sensitivity of interest rate to a change in total
 *                   loan
 * @param ltIRWin    time window within which the long-term interest rate is
 *                   measured
 * @param maxLoanIR  max loan interest rate
 * @param minLoanIR  min loan interest rate
 */
@Builder(toBuilder = true)
public record BankConfig(
		double initLoanIR,
		double ir0,
		double tao,
		int ltIRWin,
		double maxLoanIR,
		double minLoanIR) {

	/** The original hard-coded parameter values. */
	public static final BankConfig DEFAULT =
			new BankConfig(0.01, 0.01, 0.005, 100, 0.01, 0.0005);
}
