package eos.name;

/**
 * A named individual: a given name and a dynasty (household) surname. In the
 * current model each laborer is a household represented by its head, so the
 * surname identifies the household and the given name identifies the head.
 *
 * @param givenName
 *            the individual's given name (e.g. "James")
 * @param surname
 *            the dynasty / household surname (e.g. "Smith")
 */
public record Person(String givenName, String surname) {

	/** The full name, given name followed by surname (e.g. "James Smith"). */
	public String fullName() {
		return givenName + " " + surname;
	}
}
