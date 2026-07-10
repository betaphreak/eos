package com.civstudio.good;

/**
 * The kind of consumer resource a {@link ConsumerGood} represents. Consumer
 * goods (the things households buy and consume) fall into one of these
 * categories; production inputs such as {@link Capital} and {@link Labor} are
 * not consumer resources and carry no {@code ResourceType}.
 *
 * @see ConsumerGood
 */
public enum ResourceType {

	/** A staple a household must consume to survive (e.g. {@link Necessity}). */
	NECESSITY,

	/** A discretionary good bought once needs are met (e.g. {@link Enjoyment}). */
	ENJOYMENT,

	/** A strategic resource (e.g. {@link Strategic}). */
	STRATEGIC
}
