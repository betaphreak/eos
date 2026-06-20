package com.civstudio.good;

import lombok.Getter;

/**
 * A consumer resource: a good households buy and consume, classified by its
 * {@link ResourceType}. This is the common base of {@link Necessity},
 * {@link Enjoyment} and {@link Strategic}. Production inputs ({@link Capital},
 * {@link Labor}) extend {@link Good} directly and have no {@code ResourceType},
 * so the classification stays confined to consumer goods.
 *
 * @author zhihongx
 */
public abstract class ConsumerGood extends Good {

	/** The resource category this good belongs to. */
	@Getter
	private final ResourceType resourceType;

	/**
	 * Create <tt>quantity</tt> of a consumer good of the given type.
	 *
	 * @param quantity
	 *            initial quantity of the good
	 * @param resourceType
	 *            the resource category of this good
	 */
	protected ConsumerGood(double quantity, ResourceType resourceType) {
		super(quantity);
		this.resourceType = resourceType;
	}
}
