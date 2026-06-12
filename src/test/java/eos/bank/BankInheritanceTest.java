package eos.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import eos.settlement.Settlement;
import eos.util.Rng;

/**
 * Verifies how a bank settles a deceased account holder's estate
 * ({@link Bank#inheritAndClose(int)}): it inherits any leftover money and
 * absorbs (cancels) any outstanding debt, recording the account's net worth in
 * equity, then closes the account.
 */
class BankInheritanceTest {

	// Bank only needs the colony for its bank number; the name/demography
	// services, the founding-age mean, the target necessity stock and the mean
	// skill are irrelevant to account settlement, so they can be null/zero here.
	private Bank newBank() {
		Settlement colony = new Settlement(LocalDate.of(1444, 12, 11), new Rng(1L),
				null, null, 0, 0, 0, 0, 0);
		return new Bank(BankConfig.DEFAULT, colony);
	}

	@Test
	void inheritsLeftoverMoneyIntoEquity() {
		Bank bank = newBank();
		bank.openAcct(1, 30.0, 70.0); // net worth 100 (a depositor)
		assertEquals(0.0, bank.getEquity(), "equity starts at zero");

		bank.inheritAndClose(1);

		assertEquals(100.0, bank.getEquity(), 1e-9,
				"bank inherits the leftover money");
		assertNull(bank.getAcct(1), "account is closed");
	}

	@Test
	void cancelsDebtByAbsorbingTheLoss() {
		Bank bank = newBank();
		bank.openAcct(2, 5.0, -25.0); // net worth -20 (a debtor: 25 loan, 5 cash)

		bank.inheritAndClose(2);

		assertEquals(-20.0, bank.getEquity(), 1e-9,
				"bank writes off the debt, absorbing the net loss");
		assertNull(bank.getAcct(2), "account is closed");
	}

	@Test
	void settlesNetWorthAcrossSeveralEstates() {
		Bank bank = newBank();
		bank.openAcct(1, 10.0, 40.0); // +50
		bank.openAcct(2, 0.0, -30.0); // -30
		bank.openAcct(3, 12.5, 0.0); // +12.5

		bank.inheritAndClose(1);
		bank.inheritAndClose(2);
		bank.inheritAndClose(3);

		assertEquals(32.5, bank.getEquity(), 1e-9,
				"equity accumulates each estate's net worth");
	}

	@Test
	void closingAnUnknownAccountIsANoOp() {
		Bank bank = newBank();
		bank.inheritAndClose(999); // no such account
		assertEquals(0.0, bank.getEquity(), "unknown account leaves equity untouched");
	}
}
