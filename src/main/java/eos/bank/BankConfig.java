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
 * @param spread     fraction of gross interest margin the bank retains as
 *                   profit instead of redistributing to depositors (0 = the
 *                   original zero-profit pass-through)
 * @param feeRate    transaction fee charged to the payer on each withdrawal,
 *                   as a fraction of the amount, retained as profit (0 = no
 *                   fee)
 * @param exchangeFeeRate
 *                   currency-exchange (FX) fee charged when money crosses
 *                   between this bank's currency and copper (the base unit in
 *                   which all prices are quoted), as a fraction of the amount,
 *                   retained as profit. Because prices are copper-quoted, a
 *                   non-copper bank's client converts on every payment, so the
 *                   bank acts as its money-changer and skims this fee; a copper
 *                   bank never charges it (it is already in the base currency).
 *                   0 = frictionless exchange (the original behaviour).
 * @param currency   the {@link CurrencyType} the bank denominates its accounts
 *                   in (default {@link CurrencyType#COPPER})
 */
@Builder(toBuilder = true)
public record BankConfig(
		double initLoanIR,
		double ir0,
		double tao,
		int ltIRWin,
		double maxLoanIR,
		double minLoanIR,
		double spread,
		double feeRate,
		double exchangeFeeRate,
		CurrencyType currency) {

	/** The original hard-coded parameter values (zero-profit copper bank). */
	public static final BankConfig DEFAULT =
			new BankConfig(0.01, 0.01, 0.005, 100, 0.01, 0.0005, 0, 0, 0,
					CurrencyType.COPPER);
}
