package dev.coldstart.orders.pricing;

/**
 * Lowest to highest: a promotion for a tier also applies to every tier above it.
 */
public enum CustomerTier {

	BRONZE, SILVER, GOLD;

	boolean atLeast(CustomerTier minimum) {
		return compareTo(minimum) >= 0;
	}

}
