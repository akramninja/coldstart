package dev.coldstart.orders.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderView(long id, String status, ConfirmationStatus confirmationStatus, String customerName,
		String customerTier, String country, BigDecimal total, Instant createdAt, List<Line> lines,
		ShippingQuote shipping) {

	/**
	 * Reads every association. Outside a transaction, this only works while open-in-view keeps the
	 * persistence context, and its connection, bound to the request.
	 */
	static OrderView from(CustomerOrder order) {
		List<Line> lines = order.getLines()
			.stream()
			.map((line) -> new Line(line.getProduct().getSku(), line.getProduct().getName(), line.getQuantity(),
					line.getUnitPrice()))
			.toList();
		return new OrderView(order.getId(), order.getStatus(), order.getConfirmationStatus(),
				order.getCustomer().getFullName(), order.getCustomer().getTier().name(), order.getShippingCountry(),
				order.getTotal(), order.getCreatedAt(), lines, null);
	}

	int itemCount() {
		return lines.stream().mapToInt(Line::quantity).sum();
	}

	OrderView withShipping(ShippingQuote shipping) {
		return new OrderView(id, status, confirmationStatus, customerName, customerTier, country, total, createdAt,
				lines, shipping);
	}

	public record Line(String sku, String name, int quantity, BigDecimal unitPrice) {
	}

}
