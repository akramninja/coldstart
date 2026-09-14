package dev.coldstart.orders.pricing;

import java.math.BigDecimal;

/**
 * @param category the category it applies to, or {@code null} for all
 * @param country the shipping country it applies to, or {@code null} for all
 * @param rate the share of the line amount taken off, e.g. {@code 0.15}
 */
record PromotionRule(int id, String category, CustomerTier minimumTier, int minimumQuantity, String country,
		BigDecimal rate) {

	boolean appliesTo(PricingLine line, CustomerTier tier, String shippingCountry) {
		return line.quantity() >= minimumQuantity && tier.atLeast(minimumTier)
				&& (category == null || category.equals(line.category()))
				&& (country == null || country.equals(shippingCountry));
	}

}
