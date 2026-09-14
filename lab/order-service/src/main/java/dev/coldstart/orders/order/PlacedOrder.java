package dev.coldstart.orders.order;

import java.math.BigDecimal;
import java.util.List;

import dev.coldstart.orders.pricing.Quote;

/**
 * Everything the confirmation e-mail needs, copied out of the transaction so the async task holds
 * no entity.
 */
record PlacedOrder(long id, String customerName, String customerEmail, String country, Quote quote,
		List<Line> lines) {

	record Line(String sku, String name, String description, int quantity, BigDecimal unitPrice) {
	}

}
