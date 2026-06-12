package eos.agent;

import eos.name.Person;

/**
 * An agent that is a <b>household</b> headed by a named {@link Person} carrying a
 * unique dynasty surname — a laborer or a noble. The surname identifies the
 * dynasty. When such a household dies <em>without</em> a successor (its
 * replacement policy yields none), its dynasty is extinct and the surname can be
 * recycled back into the drawable pool (see
 * {@link eos.name.NameRegistry#releaseDynastyName(String)}), so a finite name
 * pool is not drained over a long run or across many colonies in one session.
 */
public interface Household {

	/**
	 * The head of this household, whose surname names the dynasty.
	 *
	 * @return the household head
	 */
	Person getHead();
}
