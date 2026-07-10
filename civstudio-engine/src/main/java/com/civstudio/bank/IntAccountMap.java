package com.civstudio.bank;

import java.util.function.Consumer;

/**
 * A minimal {@code int}-keyed map to {@link Account}, used by {@link Bank} for
 * its per-agent accounts. It replaces a {@code HashMap<Integer, Account>} to
 * avoid boxing every account id and walking hash buckets with
 * {@code Integer.equals} on the withdraw/credit/deposit/getAcct lookups that
 * dominate the step loop.
 * <p>
 * Account ids are agent ids: a dense, non-negative, per-colony counter. So the
 * map is backed by a growable array indexed <em>directly</em> by id — O(1) with
 * no hashing and no boxing; an absent id holds {@code null}. This is not a
 * general-purpose map: keys must be small non-negative ints, and a bank's array
 * grows to its largest client id (sparse high ids waste only null references).
 */
final class IntAccountMap {

	private Account[] table;
	private int size;

	IntAccountMap() {
		table = new Account[16];
	}

	/** @return the account for {@code key}, or {@code null} if none */
	Account get(int key) {
		return (key >= 0 && key < table.length) ? table[key] : null;
	}

	/** @return whether an account exists for {@code key} */
	boolean containsKey(int key) {
		return get(key) != null;
	}

	/** Associate {@code value} with {@code key} (key must be non-negative). */
	void put(int key, Account value) {
		if (key < 0)
			throw new IllegalArgumentException("negative account id: " + key);
		if (key >= table.length)
			grow(key + 1);
		if (table[key] == null)
			size++;
		table[key] = value;
	}

	/** Remove the account for {@code key} if present. */
	void remove(int key) {
		if (key >= 0 && key < table.length && table[key] != null) {
			table[key] = null;
			size--;
		}
	}

	/** @return the number of accounts held */
	int size() {
		return size;
	}

	/** Apply {@code action} to every (non-null) account, in id order. */
	void forEachValue(Consumer<Account> action) {
		for (Account acct : table)
			if (acct != null)
				action.accept(acct);
	}

	private void grow(int minCapacity) {
		int newCap = table.length;
		while (newCap < minCapacity)
			newCap <<= 1;
		Account[] bigger = new Account[newCap];
		System.arraycopy(table, 0, bigger, 0, table.length);
		table = bigger;
	}
}
